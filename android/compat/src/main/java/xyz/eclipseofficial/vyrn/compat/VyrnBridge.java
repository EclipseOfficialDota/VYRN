package xyz.eclipseofficial.vyrn.compat;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.RequestDelegate;
import org.telegram.tgnet.RequestDelegateTimestamp;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import xyz.eclipseofficial.vyrn.data.IdMap;
import xyz.eclipseofficial.vyrn.data.RestRepository;
import xyz.eclipseofficial.vyrn.data.TokenVault;
import xyz.eclipseofficial.vyrn.data.OutboxSender;
import xyz.eclipseofficial.vyrn.domain.Models;

/** The only adapter allowed to depend on both Telegram wire-shaped UI models and VYRN domain models. */
public final class VyrnBridge {
    private static final ExecutorService IO=Executors.newFixedThreadPool(2);
    private static final ExecutorService SEND=Executors.newSingleThreadExecutor();
    private static final Map<Integer,VyrnBridge> INSTANCES=new HashMap<>();
    public final RestRepository repository;
    private final IdMap ids;
    private final OutboxSender outbox;
    private final int account;
    private final Map<Long,String> privateConversations=new HashMap<>();
    private VyrnBridge(int account){this.account=account;repository=new RestRepository(new TokenVault(ApplicationLoader.applicationContext,account));ids=new IdMap(ApplicationLoader.applicationContext,account);outbox=new OutboxSender(ApplicationLoader.applicationContext,account,repository);}
    public static synchronized VyrnBridge forAccount(int account){VyrnBridge bridge=INSTANCES.get(account);if(bridge==null){bridge=new VyrnBridge(account);INSTANCES.put(account,bridge);}return bridge;}
    public static void execute(Runnable task){IO.execute(task);}
    public static boolean dispatch(int account,TLObject request,RequestDelegate callback,RequestDelegateTimestamp timestamp){
        if(request instanceof TLRPC.TL_messages_sendMessage){
            SEND.execute(()->{try{VyrnBridge bridge=forAccount(account);TLRPC.TL_messages_sendMessage in=(TLRPC.TL_messages_sendMessage)request;
                if(in.peer.chat_id==0)throw new UnsupportedOperationException("e2ee_not_ready");
                String user=bridge.repository.vault.load().getString("user_id");
                String client=UUID.nameUUIDFromBytes((user+":"+in.random_id).getBytes(StandardCharsets.UTF_8)).toString();
                bridge.outbox.send(user,bridge.ids.remote("conversation",in.peer.chat_id),client,in.message).whenComplete((sent,failure)->{
                    if(failure!=null){deliver(null,failure,callback,timestamp);return;}
                    try{TLRPC.TL_updateShortSentMessage out=new TLRPC.TL_updateShortSentMessage();out.id=bridge.ids.message(sent.id,sent.ordinal);out.date=sent.date;out.out=true;out.pts=out.id;out.pts_count=1;deliver(out,null,callback,timestamp);}catch(Exception error){deliver(null,error,callback,timestamp);}
                });
            }catch(Exception failure){deliver(null,failure,callback,timestamp);}});return true;
        }
        execute(()->{TLObject response=null;TLRPC.TL_error error=null;try{response=forAccount(account).handle(request);}catch(Exception failure){error=new TLRPC.TL_error();error.code=failure instanceof RestRepository.ApiException?((RestRepository.ApiException)failure).status:503;error.text=failure instanceof UnsupportedOperationException?"VYRN_FEATURE_UNAVAILABLE":"VYRN_REQUEST_FAILED";}
            final TLObject result=response;final TLRPC.TL_error problem=error;Utilities.stageQueue.postRunnable(()->{if(callback!=null)callback.run(result,problem);if(timestamp!=null)timestamp.run(result,problem,System.currentTimeMillis());});});return true;
    }
    private static void deliver(TLObject result,Throwable failure,RequestDelegate callback,RequestDelegateTimestamp timestamp){
        TLRPC.TL_error error=null;if(failure!=null){error=new TLRPC.TL_error();error.code=failure instanceof RestRepository.ApiException?((RestRepository.ApiException)failure).status:503;error.text=failure instanceof UnsupportedOperationException?"VYRN_FEATURE_UNAVAILABLE":"VYRN_REQUEST_FAILED";}
        final TLRPC.TL_error problem=error;Utilities.stageQueue.postRunnable(()->{if(callback!=null)callback.run(result,problem);if(timestamp!=null)timestamp.run(result,problem,System.currentTimeMillis());});
    }
    public TLRPC.User mapUser(Models.User value){TLRPC.TL_user u=new TLRPC.TL_user();u.id=ids.local("user",value.id);u.first_name=value.name;u.last_name="";u.phone="";u.self=u.id==UserConfig.getInstance(account).getClientUserId();u.photo=new TLRPC.TL_userProfilePhotoEmpty();u.status=new TLRPC.TL_userStatusOffline();return u;}
    private TLRPC.Peer peer(Models.Conversation conversation,List<Models.User> members) throws Exception{
        if(conversation.kind.equals("group")){TLRPC.TL_peerChat peer=new TLRPC.TL_peerChat();peer.chat_id=ids.local("conversation",conversation.id);return peer;}
        String self=repository.vault.load().getString("user_id");for(Models.User user:members){if(!user.id.equals(self)){TLRPC.TL_peerUser peer=new TLRPC.TL_peerUser();peer.user_id=ids.local("user",user.id);privateConversations.put(peer.user_id,conversation.id);return peer;}}throw new IllegalStateException("private_member_missing");
    }
    private TLRPC.Chat chat(Models.Conversation c){TLRPC.TL_chat chat=new TLRPC.TL_chat();chat.id=ids.local("conversation",c.id);chat.title=c.title;chat.photo=new TLRPC.TL_chatPhotoEmpty();chat.participants_count=c.members;chat.creator=c.role.equals("owner");chat.version=1;chat.date=(int)(System.currentTimeMillis()/1000);return chat;}
    private String conversation(TLRPC.InputPeer peer) throws Exception{
        if(peer.chat_id!=0)return ids.remote("conversation",peer.chat_id);
        String id=privateConversations.get(peer.user_id);if(id==null){dialogs();id=privateConversations.get(peer.user_id);}if(id==null)throw new UnsupportedOperationException();return id;
    }
    private TLRPC.Message message(Models.Message m,TLRPC.Peer peer){
        TLRPC.TL_message out=new TLRPC.TL_message();out.id=ids.message(m.id,m.ordinal);out.peer_id=peer;TLRPC.TL_peerUser sender=new TLRPC.TL_peerUser();sender.user_id=ids.local("user",m.sender);out.from_id=sender;out.flags|=256;out.date=m.date;out.message=m.deleted?"":m.encrypted?(org.telegram.messenger.LocaleController.isRTL?"این پیام در این دستگاه قابل نمایش نیست.":"This message cannot be displayed on this device."):m.text;out.out=sender.user_id==UserConfig.getInstance(account).getClientUserId();out.media=new TLRPC.TL_messageMediaEmpty();return out;
    }
    private TLRPC.TL_messages_dialogs dialogs() throws Exception {
        TLRPC.TL_messages_dialogs result=new TLRPC.TL_messages_dialogs();
        for(Models.Conversation c:repository.conversations()){
            List<Models.User> members=repository.members(c.id);TLRPC.Peer peer=peer(c,members);for(Models.User u:members)result.users.add(mapUser(u));if(c.kind.equals("group"))result.chats.add(chat(c));
            TLRPC.TL_dialog dialog=new TLRPC.TL_dialog();dialog.peer=peer;dialog.id=peer.chat_id!=0?-peer.chat_id:peer.user_id;dialog.notify_settings=new TLRPC.TL_peerNotifySettings();
            List<Models.Message> messages=repository.messages(c.id,null,1);if(!messages.isEmpty()){TLRPC.Message message=message(messages.get(0),peer);result.messages.add(message);dialog.top_message=message.id;dialog.last_message_date=message.date;}result.dialogs.add(dialog);
        }return result;
    }
    private synchronized TLObject handle(TLObject request) throws Exception {
        if(request instanceof TLRPC.TL_messages_getDialogs)return dialogs();
        if(request instanceof TLRPC.TL_messages_getHistory){
            TLRPC.TL_messages_getHistory in=(TLRPC.TL_messages_getHistory)request;String conversation=conversation(in.peer);String before=in.offset_id>0?ids.remote("message",in.offset_id):null;
            TLRPC.Peer peer;if(in.peer.chat_id!=0){TLRPC.TL_peerChat p=new TLRPC.TL_peerChat();p.chat_id=in.peer.chat_id;peer=p;}else{TLRPC.TL_peerUser p=new TLRPC.TL_peerUser();p.user_id=in.peer.user_id;peer=p;}
            TLRPC.TL_messages_messages result=new TLRPC.TL_messages_messages();for(Models.User user:repository.members(conversation))result.users.add(mapUser(user));for(Models.Message m:repository.messages(conversation,before,in.limit))result.messages.add(message(m,peer));return result;
        }
        if(request instanceof TLRPC.TL_messages_setTyping){TLRPC.TL_messages_setTyping in=(TLRPC.TL_messages_setTyping)request;repository.request("POST","/v1/conversations/"+conversation(in.peer)+"/typing",new JSONObject(),true);return new TLRPC.TL_boolTrue();}
        if(request instanceof TLRPC.TL_updates_getState){TLRPC.TL_updates_state state=new TLRPC.TL_updates_state();state.date=(int)(System.currentTimeMillis()/1000);return state;}
        if(request instanceof TLRPC.TL_contacts_getContacts){TLRPC.TL_contacts_contacts contacts=new TLRPC.TL_contacts_contacts();return contacts;}
        throw new UnsupportedOperationException();
    }
}
