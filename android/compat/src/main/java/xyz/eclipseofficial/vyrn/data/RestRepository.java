package xyz.eclipseofficial.vyrn.data;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.BuildConfig;
import xyz.eclipseofficial.vyrn.domain.Models;

public final class RestRepository implements Models.Repository {
    public final TokenVault vault;
    public RestRepository(TokenVault vault){this.vault=vault;}
    public static final class ApiException extends Exception {
        public final int status;public final String code;
        ApiException(int status,String code){super(code);this.status=status;this.code=code;}
    }
    public Object request(String method,String path,JSONObject body,boolean authenticated) throws Exception {
        return requestForUser(null,method,path,body,authenticated);
    }
    public Object requestForUser(String expectedUser,String method,String path,JSONObject body,boolean authenticated) throws Exception {
        return requestBound(expectedUser,null,method,path,body,authenticated);
    }
    public Object requestForSession(String user,String session,String method,String path,JSONObject body) throws Exception {
        if(user==null || user.isEmpty() || session==null || session.isEmpty())throw new SecurityException("session_required");
        return requestBound(user,session,method,path,body,true);
    }
    private static void requireIdentity(JSONObject tokens,String user,String session){
        if(user!=null && !user.equals(tokens.optString("user_id")))throw new SecurityException("account_changed");
        if(session!=null && !session.equals(tokens.optString("session_id")))throw new SecurityException("session_changed");
    }
    private Object requestBound(String expectedUser,String expectedSession,String method,String path,JSONObject body,boolean authenticated) throws Exception { synchronized(vault.guard()) {
        if(expectedUser!=null || expectedSession!=null)requireIdentity(vault.load(),expectedUser,expectedSession);
        String access=authenticated?vault.load().optString("access_token"):"";
        try{return exchange(method,path,body,access);}catch(ApiException failure){
            if(!authenticated||failure.status!=401)throw failure;
            JSONObject previous=vault.load();String refresh=previous.optString("refresh_token");if(refresh.isEmpty())throw failure;
            JSONObject renewed=(JSONObject)exchange("POST","/v1/auth/refresh",new JSONObject().put("refresh_token",refresh),"");
            requireIdentity(renewed,expectedUser,expectedSession);
            vault.save(renewed);return exchange(method,path,body,renewed.getString("access_token"));
        }
    }}
    private Object exchange(String method,String path,JSONObject body,String access) throws Exception {
        URL endpoint=new URL(BuildConfig.VYRN_API_URL+path);
        if(!"https".equals(endpoint.getProtocol())||endpoint.getHost().matches("[0-9.]+"))throw new SecurityException("https_hostname_required");
        HttpsURLConnection connection=(HttpsURLConnection)endpoint.openConnection();
        connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(15000);connection.setReadTimeout(20000);connection.setRequestMethod(method);
        connection.setRequestProperty("Accept","application/json");if(!access.isEmpty())connection.setRequestProperty("Authorization","Bearer "+access);
        try{
            if(body!=null){byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);connection.setRequestProperty("Content-Type","application/json");connection.setFixedLengthStreamingMode(bytes.length);connection.setDoOutput(true);try(java.io.OutputStream output=connection.getOutputStream()){output.write(bytes);}}
            int status=connection.getResponseCode();if(status==204)return new JSONObject();
            InputStream stream=status>=400?connection.getErrorStream():connection.getInputStream();String text="";
            if(stream!=null){try(InputStream input=stream;ByteArrayOutputStream output=new ByteArrayOutputStream()){byte[] buffer=new byte[8192];int n;while((n=input.read(buffer))!=-1){if(output.size()+n>4*1024*1024)throw new IllegalStateException("response_too_large");output.write(buffer,0,n);}text=output.toString("UTF-8");}}
            if(status<200||status>=300){String code="request_failed";try{code=new JSONObject(text).optString("error",code);}catch(Exception ignored){}throw new ApiException(status,code);}
            return text.trim().startsWith("[")?new JSONArray(text):new JSONObject(text);
        }finally{connection.disconnect();}
    }
    public Models.User me() throws Exception {return user((JSONObject)request("GET","/v1/me",null,true));}
    public Models.User me(String expectedUser,String session) throws Exception {Models.User value=user((JSONObject)requestForSession(expectedUser,session,"GET","/v1/me",null));if(!expectedUser.equals(value.id))throw new SecurityException("account_changed");return value;}
    public void renameGroup(String id,String title) throws Exception {request("PATCH","/v1/conversations/"+id,new JSONObject().put("title",title),true);}
    public void inviteMember(String id,String user) throws Exception {request("POST","/v1/conversations/"+id+"/members",new JSONObject().put("user_id",user).put("preserve_existing",true),true);}
    public void setMemberRole(String id,String user,boolean admin) throws Exception {request("POST","/v1/conversations/"+id+"/members",new JSONObject().put("user_id",user).put("role",admin?"admin":"member"),true);}
    public void removeMember(String id,String user) throws Exception {request("DELETE","/v1/conversations/"+id+"/members/"+user,null,true);}
    public void deleteGroupMessages(String user,String group,List<String> messages) throws Exception {requestForUser(user,"DELETE","/v1/conversations/"+group+"/messages",new JSONObject().put("ids",new JSONArray(messages)),true);}
    public void setHistoryTTL(String id,int seconds) throws Exception {request("PATCH","/v1/conversations/"+id,new JSONObject().put("disappearing_seconds",seconds),true);}
    public Models.ConversationDetails conversationDetails(String id) throws Exception {
        JSONObject data=(JSONObject)request("GET","/v1/conversations/"+id,null,true);JSONArray raw=data.getJSONArray("members");List<Models.Member> members=new ArrayList<>();
        for(int i=0;i<raw.length();i++){JSONObject member=raw.getJSONObject(i);members.add(new Models.Member(user(member),member.getString("role"),(int)Instant.parse(member.getString("joined_at")).getEpochSecond()));}
        Models.Conversation conversation=new Models.Conversation(data.getString("id"),data.getString("kind"),data.getString("title"),data.getString("role"),members.size(),data.getInt("disappearing_seconds"));
        return new Models.ConversationDetails(conversation,members,(int)Instant.parse(data.getString("created_at")).getEpochSecond());
    }
    public List<Models.User> searchUsers(String query,int limit) throws Exception {
        JSONArray data=(JSONArray)request("GET","/v1/users/search?q="+java.net.URLEncoder.encode(query,"UTF-8")+"&limit="+Math.max(1,Math.min(limit,50)),null,true);
        List<Models.User> result=new ArrayList<>();for(int i=0;i<data.length();i++)result.add(user(data.getJSONObject(i)));return result;
    }
    public Models.Conversation createGroup(String title,List<String> members,int disappearingSeconds) throws Exception {
        JSONObject group=(JSONObject)request("POST","/v1/conversations",new JSONObject().put("kind","group").put("title",title).put("members",new JSONArray(members)).put("disappearing_seconds",disappearingSeconds),true);
        return new Models.Conversation(group.getString("id"),"group",group.getString("title"),"owner",group.getJSONArray("members").length(),group.getInt("disappearing_seconds"));
    }
    private Models.User user(JSONObject data) throws Exception{return new Models.User(data.getString("id"),data.getString("display_name"),data.optString("first_name",data.getString("display_name")),data.optString("last_name",""),data.optString("about",""),data.isNull("avatar_id")?null:data.optString("avatar_id",null));}
    public Models.Avatar avatarForUser(String owner,String target) throws Exception {
        JSONObject value=(JSONObject)requestForUser(owner,"GET","/v1/users/"+target+"/avatar",null,true);
        return new Models.Avatar(value.getString("id"),value.getString("content_type"),value.getInt("width"),value.getInt("height"),value.getInt("size"),Math.toIntExact(Instant.parse(value.getString("date")).getEpochSecond()));
    }
    public void deleteAvatarForUser(String owner,String id) throws Exception {requestForUser(owner,"DELETE","/v1/me/avatar/"+id,null,true);}
    public String uploadAvatarForUser(String owner,byte[] image) throws Exception {
        if(image.length<1||image.length>10*1024*1024)throw new IllegalArgumentException("avatar_size");
        return new JSONObject(new String(binaryForUser(owner,"POST","/v1/media?kind=avatar",image,0,65536),StandardCharsets.UTF_8)).getString("id");
    }
    public byte[] avatarBytesForUser(String owner,String id,long offset,int limit) throws Exception {
        if(offset<0||offset>10*1024*1024||limit<1||limit>1024*1024)throw new IllegalArgumentException("avatar_range");
        return binaryForUser(owner,"GET","/v1/media/"+id,null,offset,limit);
    }
    private byte[] binaryForUser(String owner,String method,String path,byte[] body,long offset,int limit) throws Exception {synchronized(vault.guard()){
        JSONObject session=vault.load();if(!owner.equals(session.optString("user_id")))throw new SecurityException("account_changed");
        try{return exchangeBinary(method,path,body,offset,limit,session.getString("access_token"));}catch(ApiException failure){
            if(failure.status!=401)throw failure;
            JSONObject renewed=(JSONObject)exchange("POST","/v1/auth/refresh",new JSONObject().put("refresh_token",session.getString("refresh_token")),"");
            vault.save(renewed);return exchangeBinary(method,path,body,offset,limit,renewed.getString("access_token"));
        }
    }}
    private byte[] exchangeBinary(String method,String path,byte[] body,long offset,int limit,String access) throws Exception {
        URL endpoint=new URL(BuildConfig.VYRN_API_URL+path);if(!"https".equals(endpoint.getProtocol())||endpoint.getHost().matches("[0-9.]+"))throw new SecurityException("https_hostname_required");
        HttpsURLConnection connection=(HttpsURLConnection)endpoint.openConnection();connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(15000);connection.setReadTimeout(20000);connection.setRequestMethod(method);connection.setRequestProperty("Authorization","Bearer "+access);
        try{
            if(body!=null){connection.setRequestProperty("Content-Type","application/octet-stream");connection.setRequestProperty("X-Filename","avatar.jpg");connection.setFixedLengthStreamingMode(body.length);connection.setDoOutput(true);try(java.io.OutputStream output=connection.getOutputStream()){output.write(body);}}
            else connection.setRequestProperty("Range","bytes="+offset+"-"+(offset+limit-1));
            int status=connection.getResponseCode();String range=connection.getHeaderField("Content-Range");
            if(status==416 && range!=null && range.matches("bytes \\*/[0-9]+") && offset>=Long.parseLong(range.substring(8)))return new byte[0];
            if(body==null && status>=200 && status<300 && status!=206)throw new IllegalStateException("range_response_required");
            try(InputStream input=status>=400?connection.getErrorStream():connection.getInputStream();ByteArrayOutputStream output=new ByteArrayOutputStream()){
                if(input!=null){byte[] buffer=new byte[8192];int n;while((n=input.read(buffer))!=-1){if(output.size()+n>Math.max(limit,65536))throw new IllegalStateException("response_too_large");output.write(buffer,0,n);}}
                byte[] value=output.toByteArray();if(status<200||status>=300){String code="request_failed";try{code=new JSONObject(new String(value,StandardCharsets.UTF_8)).optString("error",code);}catch(Exception ignored){}throw new ApiException(status,code);}
                if(body==null && value.length>limit)throw new IllegalStateException("range_too_large");return value;
            }
        }finally{connection.disconnect();}
    }
    public Models.User updateProfileForUser(String user,JSONObject changes) throws Exception{return user((JSONObject)requestForUser(user,"PATCH","/v1/me",changes,true));}
    public Models.Profile profileForUser(String expected,String target) throws Exception{JSONObject data=(JSONObject)requestForUser(expected,"GET",target.equals(expected)?"/v1/me":"/v1/users/"+target,null,true);return new Models.Profile(user(data),data.getBoolean("blocked"),data.getInt("common_group_count"));}
    public void setBlockedForUser(String owner,String target,boolean blocked) throws Exception{requestForUser(owner,blocked?"PUT":"DELETE","/v1/blocks/"+target,null,true);}
    public boolean blockedForUser(String owner,String target) throws Exception{return ((JSONObject)requestForUser(owner,"GET","/v1/blocks/"+target,null,true)).getBoolean("blocked");}
    public Models.BlockedPage blockedForUser(String owner,int offset,int limit) throws Exception{
        JSONObject page=(JSONObject)requestForUser(owner,"GET","/v1/blocks?offset="+offset+"&limit="+limit,null,true);JSONArray items=page.getJSONArray("items");List<Models.BlockedUser> users=new ArrayList<>();
        for(int i=0;i<items.length();i++){JSONObject item=items.getJSONObject(i);users.add(new Models.BlockedUser(user(item),item.isNull("created_at")?0:Math.toIntExact(Instant.parse(item.getString("created_at")).getEpochSecond())));}
        return new Models.BlockedPage(users,page.getInt("count"));
    }
    public List<Models.User> members(String conversation) throws Exception {
        JSONArray data=(JSONArray)request("GET","/v1/conversations/"+conversation+"/members",null,true);List<Models.User> result=new ArrayList<>();for(int i=0;i<data.length();i++)result.add(user(data.getJSONObject(i)));return result;
    }
    public List<Models.Conversation> conversations() throws Exception {
        JSONArray data=(JSONArray)request("GET","/v1/conversations",null,true);List<Models.Conversation> result=new ArrayList<>();for(int i=0;i<data.length();i++){JSONObject c=data.getJSONObject(i);result.add(new Models.Conversation(c.getString("id"),c.getString("kind"),c.getString("title"),c.getString("role"),c.getInt("member_count"),c.getInt("disappearing_seconds")));}return result;
    }
    public List<Models.Message> messages(String conversation,String before,int limit) throws Exception{
        return messages(conversation,before,limit,false);
    }
    public List<Models.Message> liveMessages(String conversation,String before,int limit) throws Exception{return messages(conversation,before,limit,true);}
    private List<Models.Message> messages(String conversation,String before,int limit,boolean liveOnly) throws Exception{
        String path="/v1/conversations/"+conversation+"/messages?limit="+Math.max(1,Math.min(limit,100))+"&live_only="+liveOnly;if(before!=null)path+="&before="+before;
        JSONArray data=(JSONArray)request("GET",path,null,true);List<Models.Message> result=new ArrayList<>();for(int i=0;i<data.length();i++)result.add(message(data.getJSONObject(i)));return result;
    }
    private Models.Message message(JSONObject m) throws Exception {
        boolean encrypted=m.getBoolean("encrypted");int ordinal=m.getInt("ordinal");if(ordinal<=0)throw new IllegalStateException("invalid_message_order");
        int revision=m.getInt("revision");if(revision<1)throw new IllegalStateException("invalid_message_revision");
        return new Models.Message(m.getString("id"),m.getString("sender_id"),encrypted?"":m.getJSONObject("payload").optString("text"),(int)Instant.parse(m.getString("created_at")).getEpochSecond(),ordinal,encrypted,!m.isNull("deleted_at"),m.getString("conversation_id"),revision,m.isNull("edited_at")?0:(int)Instant.parse(m.getString("edited_at")).getEpochSecond());
    }
    public Models.Message message(String id) throws Exception {return message((JSONObject)request("GET","/v1/messages/"+id,null,true));}
    public Models.Message editText(String user,String id,int revision,String text) throws Exception { synchronized(vault.guard()) {
        requestForUser(user,"PATCH","/v1/messages/"+id,new JSONObject().put("revision",revision).put("encrypted",false).put("payload",new JSONObject().put("text",text)),true);
        return messageForUser(user,id);
    }}
    public Models.Message messageForUser(String user,String id) throws Exception {return message((JSONObject)requestForUser(user,"GET","/v1/messages/"+id,null,true));}
    public String send(String conversation,String clientId,String text) throws Exception{
        JSONObject input=new JSONObject().put("client_id",clientId).put("payload",new JSONObject().put("text",text)).put("encrypted",false);
        return ((JSONObject)request("POST","/v1/conversations/"+conversation+"/messages",input,true)).getString("id");
    }
    public Models.Message sendForUser(String user,String conversation,String clientId,String text) throws Exception { synchronized(vault.guard()) {
        if(!user.equals(vault.load().optString("user_id")))throw new SecurityException("account_changed");
        return message(send(conversation,clientId,text));
    }}
}
