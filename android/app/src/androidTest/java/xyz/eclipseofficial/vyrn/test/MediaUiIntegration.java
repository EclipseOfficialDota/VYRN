package xyz.eclipseofficial.vyrn.test;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONObject;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.Cells.ChatMessageCell;
import xyz.eclipseofficial.vyrn.compat.VyrnBridge;
import xyz.eclipseofficial.vyrn.data.IdMap;
import xyz.eclipseofficial.vyrn.data.RestRepository;
import xyz.eclipseofficial.vyrn.domain.Models;

/** Real native file bubble/action/download; upload is an API fixture, not native Send. */
final class MediaUiIntegration {
    private static void mediaIds(Instrumentation instrumentation,AuthUiIntegration.Check check) throws Exception {
        android.content.Context context=instrumentation.getTargetContext();String owner=UUID.randomUUID().toString(),id=UUID.randomUUID().toString();
        File legacy=context.getDatabasePath("vyrn_ids_9006.db");check.verify(!legacy.exists(),"media_legacy_fixture_is_disposable");
        try(android.database.sqlite.SQLiteDatabase db=android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(legacy,null)){
            db.execSQL("CREATE TABLE ids(id INTEGER PRIMARY KEY AUTOINCREMENT,kind TEXT NOT NULL,uuid TEXT NOT NULL,UNIQUE(kind,uuid))");
            db.execSQL("CREATE TABLE message_ids(id INTEGER PRIMARY KEY CHECK(id>0),uuid TEXT NOT NULL UNIQUE)");
            db.execSQL("CREATE TABLE observed_messages(uuid TEXT PRIMARY KEY,conversation TEXT NOT NULL,sender TEXT NOT NULL,revision INTEGER NOT NULL CHECK(revision>0),body_hash BLOB,encrypted INTEGER NOT NULL DEFAULT 0,deleted INTEGER NOT NULL DEFAULT 0,date INTEGER NOT NULL DEFAULT 0,edited INTEGER NOT NULL DEFAULT 0)");
            db.execSQL("INSERT INTO ids(id,kind,uuid) VALUES(7,'user',?)",new Object[]{owner});db.setVersion(4);
        }
        long stable;try(IdMap map=new IdMap(context,9006)){stable=map.media(owner,id);check.verify(map.getReadableDatabase().getVersion()==5&&owner.equals(map.remote("user",7)),"media_v4_migration_preserves_native_user_id");check.verify(map.local("conversation",UUID.randomUUID().toString())==8,"media_long_ids_do_not_advance_user_chat_sequence");check.verify(map.media(UUID.randomUUID().toString(),id)!=stable,"media_cache_identity_separates_accounts");}
        try(IdMap map=new IdMap(context,9006)){check.verify(map.media(owner,id)==stable&&map.mediaRemote(owner,stable).equals(id),"media_identity_survives_database_reopen");}
        try(IdMap fresh=new IdMap(context,9007)){check.verify(fresh.media(owner,id)==stable,"media_cache_identity_stable_across_fresh_database");}
    }
    private static ChatMessageCell cell(View view,int id){
        if(view==null)return null;
        if(view instanceof ChatMessageCell){ChatMessageCell value=(ChatMessageCell)view;if(value.getMessageObject()!=null&&value.getMessageObject().getId()==id)return value;}
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){ChatMessageCell value=cell(group.getChildAt(i),id);if(value!=null)return value;}}
        return null;
    }
    static void run(Instrumentation instrumentation,Activity activity,JSONObject fixture,AuthUiIntegration.Check check) throws Exception {
        AuthUiIntegration.registration(instrumentation,activity,fixture,check);
        mediaIds(instrumentation,check);
        int account=UserConfig.selectedAccount;VyrnBridge bridge=VyrnBridge.forAccount(account);RestRepository repository=bridge.repository;
        String owner=repository.me().id;
        RestRepository sender=new RestRepository(new xyz.eclipseofficial.vyrn.data.TokenVault(instrumentation.getTargetContext(),9003));
        JSONObject tokens=(JSONObject)sender.request("POST","/v1/auth/register",new JSONObject().put("email","media-sender-"+UUID.randomUUID()+"@vyrn.local").put("password",fixture.getString("password")).put("invite_code",fixture.getString("invite")).put("display_name","Media sender").put("device_id",UUID.randomUUID().toString()).put("device_name","Media API fixture"),false);
        sender.vault.save(tokens);String senderId=tokens.getString("user_id");
        JSONObject group=(JSONObject)sender.requestForUser(senderId,"POST","/v1/conversations",new JSONObject().put("kind","group").put("title","Native file test").put("members",new org.json.JSONArray().put(owner)),true);
        String conversation=group.getString("id");byte[] bytes=new byte[2*1024*1024+113];for(int i=0;i<bytes.length;i++)bytes[i]=(byte)(i*31);
        HttpsURLConnection connection=(HttpsURLConnection)new java.net.URL(org.telegram.messenger.BuildConfig.VYRN_API_URL+"/v1/media?kind=file&conversation_id="+conversation).openConnection();
        JSONObject upload;
        try{connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(15000);connection.setReadTimeout(20000);connection.setRequestMethod("POST");connection.setRequestProperty("Authorization","Bearer "+sender.vault.load().getString("access_token"));connection.setRequestProperty("X-Filename","vyrn-native-file.bin");connection.setDoOutput(true);connection.setFixedLengthStreamingMode(bytes.length);try(java.io.OutputStream output=connection.getOutputStream()){output.write(bytes);}check.verify(connection.getResponseCode()==201,"media_fixture_upload");try(java.io.InputStream input=connection.getInputStream();java.io.ByteArrayOutputStream output=new java.io.ByteArrayOutputStream()){byte[] buffer=new byte[4096];int count;while((count=input.read(buffer))!=-1){if(output.size()+count>65536)throw new AssertionError("media_fixture_response_limit");output.write(buffer,0,count);}upload=new JSONObject(output.toString("UTF-8"));}}finally{connection.disconnect();}
        JSONObject posted=(JSONObject)sender.requestForUser(senderId,"POST","/v1/conversations/"+conversation+"/messages",new JSONObject().put("client_id",UUID.randomUUID().toString()).put("encrypted",false).put("media_id",upload.getString("id")).put("payload",new JSONObject().put("text","VYRN native file")),true);
        Models.Message message=repository.messageForUser(owner,posted.getString("id"));
        check.verify(message.attachment!=null&&message.attachment.size==bytes.length&&message.attachment.sha256.equals(upload.getString("sha256")),"media_message_preserves_descriptor");
        CompletableFuture<org.telegram.tgnet.TLObject> dialogs=new CompletableFuture<>();ConnectionsManager.getInstance(account).sendRequest(new TLRPC.TL_messages_getDialogs(),(value,error)->{if(error!=null)dialogs.completeExceptionally(new AssertionError());else dialogs.complete(value);});TLRPC.TL_messages_dialogs loaded=(TLRPC.TL_messages_dialogs)dialogs.get(40,TimeUnit.SECONDS);
        instrumentation.runOnMainSync(()->{org.telegram.messenger.MessagesController.getInstance(account).putChats(loaded.chats,false);org.telegram.messenger.MessagesController.getInstance(account).putUsers(loaded.users,false);});
        int local;try(IdMap ids=new IdMap(instrumentation.getTargetContext(),account)){local=ids.local("conversation",conversation);}
        AuthUiIntegration.handlePrompts(instrumentation,activity,check);
        check.verify(org.telegram.messenger.MessagesController.getInstance(account).getChat((long)local)!=null,"media_native_group_loaded");
        boolean[] ready={false};long navigationDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);while(System.nanoTime()<navigationDeadline){instrumentation.runOnMainSync(()->ready[0]=LaunchActivity.getLastFragment()!=null&&LaunchActivity.getLastFragment().getParentLayout()!=null&&!LaunchActivity.getLastFragment().getParentLayout().isTransitionAnimationInProgress());if(ready[0])break;Thread.sleep(100);}
        check.verify(ready[0],"media_native_navigation_ready");
        int chat=local;boolean[] opened={false};instrumentation.runOnMainSync(()->{Bundle arguments=new Bundle();arguments.putLong("chat_id",chat);opened[0]=LaunchActivity.getLastFragment().presentFragment(new ChatActivity(arguments));});check.verify(opened[0],"media_actual_chat_open");
        ChatMessageCell[] rendered={null};long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(40);
        while(System.nanoTime()<deadline){instrumentation.runOnMainSync(()->rendered[0]=cell(LaunchActivity.getLastFragment().getFragmentView(),message.ordinal));if(rendered[0]!=null&&rendered[0].isShown())break;Thread.sleep(100);}
        if(rendered[0]==null){JSONObject diagnostic=new JSONObject();instrumentation.runOnMainSync(()->{try{org.telegram.ui.ActionBar.BaseFragment fragment=LaunchActivity.getLastFragment();diagnostic.put("fragment",fragment.getClass().getSimpleName());if(fragment instanceof ChatActivity){ChatActivity active=(ChatActivity)fragment;diagnostic.put("native_message_count",active.messages.size()).put("target_in_model",active.messages.stream().anyMatch(value->value.getId()==message.ordinal)).put("document_in_model",active.messages.stream().anyMatch(value->value.getId()==message.ordinal&&value.getDocument()!=null));}}catch(Exception failure){throw new AssertionError(failure);}});Files.write(new File(instrumentation.getTargetContext().getFilesDir(),"vyrn-test-media-diagnostic.json").toPath(),diagnostic.toString().getBytes(StandardCharsets.UTF_8));}
        check.verify(rendered[0]!=null,"media_actual_native_file_bubble");
        instrumentation.runOnMainSync(()->check.verify(!rendered[0].getMessageObject().isOutOwner(),"media_actual_recipient_bubble_from_distinct_sender"));
        TLRPC.Document[] document={null};instrumentation.runOnMainSync(()->document[0]=rendered[0].getMessageObject().getDocument());
        check.verify(document[0]!=null&&document[0].size==bytes.length&&Arrays.equals(document[0].file_reference,upload.getString("sha256").getBytes(StandardCharsets.US_ASCII)),"media_native_document_hash_and_size");
        File file=FileLoader.getInstance(account).getPathToAttach(document[0]);
        if(!file.isFile())instrumentation.runOnMainSync(()->check.verify(rendered[0].performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK,new Bundle()),"media_actual_download_action"));
        deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(60);while(System.nanoTime()<deadline&&(!file.isFile()||file.length()!=bytes.length))Thread.sleep(100);
        check.verify(file.isFile()&&Arrays.equals(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file.toPath())),MessageDigest.getInstance("SHA-256").digest(bytes)),"media_original_worker_complete_verified_file");
        TLRPC.TL_inputDocumentFileLocation location=new TLRPC.TL_inputDocumentFileLocation();location.id=document[0].id;location.file_reference=document[0].file_reference;location.thumb_size="";
        check.verify(VyrnBridge.verifyAttachment(account,location,file),"media_completion_guard_accepts_exact_file");
        File corrupt=new File(instrumentation.getTargetContext().getCacheDir(),"vyrn-test-corrupt-media");byte[] changed=bytes.clone();changed[0]^=1;Files.write(corrupt.toPath(),changed);
        try{check.verify(!VyrnBridge.verifyAttachment(account,location,corrupt),"media_completion_guard_rejects_tampered_cache");}finally{Files.deleteIfExists(corrupt.toPath());}
        location.id=Long.MAX_VALUE;check.verify(!VyrnBridge.verifyAttachment(account,location,file),"media_completion_guard_rejects_unbound_native_id");
        sender.requestForUser(senderId,"DELETE","/v1/messages/"+message.id,null,true);
        boolean deletedDenied=false;try{repository.attachmentBytesForUser(owner,upload.getString("id"),upload.getString("sha256"),0,1024);}catch(RestRepository.ApiException expected){deletedDenied=expected.status==404;}
        check.verify(deletedDenied,"media_recipient_new_download_denied_after_message_deletion");
        boolean denied=false;try{repository.attachmentBytesForUser(UUID.randomUUID().toString(),upload.getString("id"),upload.getString("sha256"),0,1024);}catch(SecurityException expected){denied=true;}
        check.verify(denied,"media_download_rejects_changed_account");
    }
}
