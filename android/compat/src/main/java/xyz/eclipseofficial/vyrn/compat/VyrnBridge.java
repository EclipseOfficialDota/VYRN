package xyz.eclipseofficial.vyrn.compat;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.RequestDelegate;
import org.telegram.tgnet.RequestDelegateTimestamp;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_update;
import xyz.eclipseofficial.vyrn.data.IdMap;
import xyz.eclipseofficial.vyrn.data.RestRepository;
import xyz.eclipseofficial.vyrn.data.TokenVault;
import xyz.eclipseofficial.vyrn.data.OutboxSender;
import xyz.eclipseofficial.vyrn.data.OutboxStore;
import xyz.eclipseofficial.vyrn.data.RealtimeSync;
import xyz.eclipseofficial.vyrn.domain.Models;

/** The only adapter allowed to depend on both Telegram wire-shaped UI models and VYRN domain models. */
public final class VyrnBridge {
    private static final ExecutorService IO=Executors.newFixedThreadPool(2);
    private static final ExecutorService SEND=Executors.newSingleThreadExecutor();
    private static final Map<Integer,VyrnBridge> INSTANCES=new HashMap<>();
    private static final ConcurrentHashMap<Long,PendingRequest> REQUESTS=new ConcurrentHashMap<>();
    private static long requestKey(int account,int token){return ((long)account<<32)|(token&0xffffffffL);}
    private static final class PendingRequest {
        final int account,token;final boolean send;final RequestDelegate callback;final RequestDelegateTimestamp timestamp;
        int guid;boolean cancellationRequested,cancellationStarted,resultReady,finished,ready;
        Boolean cancellationResult;TLObject result;Throwable failure;VyrnBridge bridge;String user,client;
        final ArrayList<Runnable> cancelledCallbacks=new ArrayList<>();
        PendingRequest(int account,int token,boolean send,RequestDelegate callback,RequestDelegateTimestamp timestamp){this.account=account;this.token=token;this.send=send;this.callback=callback;this.timestamp=timestamp;}
        synchronized void complete(TLObject result,Throwable failure){
            if(finished)return;this.result=result;this.failure=failure;resultReady=true;
            if(cancellationRequested && send && !ready)cancellationResult=bridge==null;
            Utilities.stageQueue.postRunnable(this::deliver);
        }
        synchronized void cancel(Runnable onCancelled){
            if(finished)return;cancellationRequested=true;if(onCancelled!=null)cancelledCallbacks.add(onCancelled);
            if(!send || !ready && resultReady){cancellationResult=!send || bridge==null;Utilities.stageQueue.postRunnable(this::deliver);}
            else if(ready)startCancellation();
        }
        synchronized void startCancellation(){
            if(!cancellationRequested || cancellationStarted || finished || !ready)return;cancellationStarted=true;
            try{bridge.outbox.cancelForOrigin(user,client).whenComplete((cancelled,error)->Utilities.stageQueue.postRunnable(()->{
                synchronized(this){if(finished)return;cancellationResult=error==null && Boolean.TRUE.equals(cancelled);}
                deliver();
            }));}catch(Exception error){cancellationResult=false;Utilities.stageQueue.postRunnable(this::deliver);}
        }
        void deliver(){
            TLObject response;Throwable error;ArrayList<Runnable> cancelled;
            synchronized(this){
                if(finished || cancellationRequested && cancellationResult==null)return;
                if(Boolean.TRUE.equals(cancellationResult)){cancelled=new ArrayList<>(cancelledCallbacks);response=null;error=null;}
                else{if(!resultReady)return;cancelled=null;response=result;error=failure;}
                finished=true;if(token!=0)REQUESTS.remove(requestKey(account,token),this);
            }
            if(cancelled!=null){for(Runnable task:cancelled)task.run();return;}
            TLRPC.TL_error problem=problem(error);if(callback!=null)callback.run(response,problem);if(timestamp!=null)timestamp.run(response,problem,System.currentTimeMillis());
        }
    }
    public final RestRepository repository;
    private final IdMap ids;
    private final OutboxSender outbox;
    private final RealtimeSync realtime;
    private final int account;
    private final AtomicBoolean uiReconciling=new AtomicBoolean();
    private final Map<Long,String> privateConversations=new HashMap<>();
    private VyrnBridge(int account){this.account=account;repository=new RestRepository(new TokenVault(ApplicationLoader.applicationContext,account));ids=new IdMap(ApplicationLoader.applicationContext,account);outbox=new OutboxSender(ApplicationLoader.applicationContext,account,repository);realtime=new RealtimeSync(ApplicationLoader.applicationContext,account,repository,this::applyEvent);outbox.setUiListener(this::resolveUi);}
    /** Group text cancellation owns local removal; other message kinds retain their existing flow. */
    public static boolean cancelSendingMessages(int account,ArrayList<MessageObject> objects){
        ArrayList<MessageObject> other=new ArrayList<>();boolean handled=false;
        for(MessageObject object:objects){
            if(object==null || object.messageOwner==null || object.getId()>=0 || object.getDialogId()>=0 || object.messageOwner.random_id==0 || object.type!=MessageObject.TYPE_TEXT || object.scheduled || object.isQuickReply() || MessageObject.isWelcomeMessage(object.messageOwner)){other.add(object);continue;}
            handled=true;final int localId=object.getId(),token=object.messageOwner.reqId;final long dialog=object.getDialogId(),random=object.messageOwner.random_id;final String text=object.messageOwner.message;
            execute(()->{try{VyrnBridge bridge=forAccount(account);String user=bridge.repository.vault.load().getString("user_id");String client=UUID.nameUUIDFromBytes((user+":"+random).getBytes(StandardCharsets.UTF_8)).toString();
                bridge.outbox.cancelFromUi(user,client,bridge.ids.remote("conversation",-dialog),text,localId,dialog,random);
                cancelRequest(account,token,null);
            }catch(Exception unavailable){/* Preserve the visible message unless cancellation is proved. */}});
        }
        if(handled && !other.isEmpty())SendMessagesHelper.getInstance(account).cancelSendingMessage(other);
        return handled;
    }
    private void resolveUi(List<OutboxStore.UiResolution> resolutions){
        if(!uiReconciling.compareAndSet(false,true))return;
        execute(()->{try{for(OutboxStore.UiResolution resolution:resolutions)try{resolveUiOne(resolution);}catch(Exception unavailable){/* Retry the durable journal; never erase on a failed cache operation. */}}finally{uiReconciling.set(false);}});
    }
    private void resolveUiOne(OutboxStore.UiResolution resolution) throws Exception {
        if(!resolution.user.equals(repository.vault.load().optString("user_id")))return;
        final long selfId=ids.local("user",resolution.user);
        final TLRPC.Message committed;
        if(resolution.state==OutboxStore.SENT){
            Models.Message remote=repository.messageForUser(resolution.user,resolution.server);
            TLRPC.TL_peerChat peer=new TLRPC.TL_peerChat();peer.chat_id=-resolution.dialogId;
            committed=message(remote,peer);committed.dialog_id=resolution.dialogId;committed.random_id=resolution.randomId;committed.out=true;committed.send_state=MessageObject.MESSAGE_SEND_STATE_SENT;
        }else committed=null;
        MessagesStorage storage=MessagesStorage.getInstance(account);CountDownLatch written=new CountDownLatch(1);boolean[] safe={false};
        storage.getStorageQueue().postRunnable(()->{try{
            if(!repository.vault.isUser(resolution.user) || UserConfig.getInstance(account).getClientUserId()!=selfId)return;
            org.telegram.SQLite.SQLiteCursor cursor=storage.getDatabase().queryFinalized("SELECT r.random_id FROM messages_v2 m LEFT JOIN randoms_v2 r ON r.mid=m.mid AND r.uid=m.uid WHERE m.mid="+resolution.localId+" AND m.uid="+resolution.dialogId);
            try{if(cursor.next() && (cursor.isNull(0) || cursor.longValue(0)!=resolution.randomId))return;}finally{cursor.dispose();}
            ArrayList<Integer> localIds=new ArrayList<>();localIds.add(resolution.localId);
            if(committed==null){storage.markMessagesAsDeleted(resolution.dialogId,localIds,false,false,0,0);storage.updateDialogsWithDeletedMessages(resolution.dialogId,0,localIds,null);}
            else{storage.updateMessageStateAndId(resolution.randomId,resolution.dialogId,resolution.localId,committed.id,committed.date,false,0,0);ArrayList<TLRPC.Message> messages=new ArrayList<>();messages.add(committed);storage.putMessages(messages,true,false,false,0,0,0);}
            safe[0]=true;
        }catch(Exception unavailable){/* No acknowledgement until reads prove the mutation. */}finally{written.countDown();}});
        if(!written.await(20,TimeUnit.SECONDS) || !safe[0])return;
        CountDownLatch displayed=new CountDownLatch(1);boolean[] active={false};
        AndroidUtilities.runOnUIThread(()->{try{
            if(!repository.vault.isUser(resolution.user) || UserConfig.getInstance(account).getClientUserId()!=selfId)return;
            SendMessagesHelper.getInstance(account).vyrnResolveCancellation(resolution.localId,resolution.dialogId,committed);active[0]=true;
        }finally{displayed.countDown();}});
        if(!displayed.await(20,TimeUnit.SECONDS) || !active[0])return;
        CountDownLatch verified=new CountDownLatch(1);boolean[] complete={false};
        storage.getStorageQueue().postRunnable(()->{try{
            if(!repository.vault.isUser(resolution.user) || UserConfig.getInstance(account).getClientUserId()!=selfId)return;
            org.telegram.SQLite.SQLiteCursor cursor=storage.getDatabase().queryFinalized("SELECT count(*) FROM messages_v2 WHERE mid="+resolution.localId+" AND uid="+resolution.dialogId);
            try{complete[0]=cursor.next() && cursor.intValue(0)==0;}finally{cursor.dispose();}
            if(complete[0] && committed!=null){cursor=storage.getDatabase().queryFinalized("SELECT count(*) FROM messages_v2 WHERE mid="+committed.id+" AND uid="+resolution.dialogId+" AND send_state=0");try{complete[0]=cursor.next() && cursor.intValue(0)==1;}finally{cursor.dispose();}}
        }catch(Exception unavailable){complete[0]=false;}finally{verified.countDown();}});
        if(verified.await(20,TimeUnit.SECONDS) && complete[0])outbox.acknowledgeUi(resolution);
    }
    public static synchronized VyrnBridge forAccount(int account){VyrnBridge bridge=INSTANCES.get(account);if(bridge==null){bridge=new VyrnBridge(account);INSTANCES.put(account,bridge);}return bridge;}
    public static void execute(Runnable task){IO.execute(task);}
    public static boolean dispatch(int account,TLObject request,RequestDelegate callback,RequestDelegateTimestamp timestamp){
        return dispatch(account,request,callback,timestamp,0);
    }
    public static boolean dispatch(int account,TLObject request,RequestDelegate callback,RequestDelegateTimestamp timestamp,int token){
        PendingRequest pending=new PendingRequest(account,token,request instanceof TLRPC.TL_messages_sendMessage,callback,timestamp);
        if(token!=0 && REQUESTS.putIfAbsent(requestKey(account,token),pending)!=null)throw new IllegalStateException("duplicate_request_token");
        if(request instanceof TLRPC.TL_messages_sendMessage){
            SEND.execute(()->{try{VyrnBridge bridge=forAccount(account);TLRPC.TL_messages_sendMessage in=(TLRPC.TL_messages_sendMessage)request;
                if(in.peer.chat_id==0)throw new UnsupportedOperationException("e2ee_not_ready");
                String user=bridge.repository.vault.load().getString("user_id");
                String client=UUID.nameUUIDFromBytes((user+":"+in.random_id).getBytes(StandardCharsets.UTF_8)).toString();
                synchronized(pending){pending.bridge=bridge;pending.user=user;pending.client=client;}
                java.util.concurrent.CompletableFuture<Models.Message> delivery=bridge.outbox.send(user,bridge.ids.remote("conversation",in.peer.chat_id),client,in.message);
                synchronized(pending){pending.ready=true;}
                delivery.whenComplete((sent,failure)->Utilities.stageQueue.postRunnable(()->{
                    if(failure!=null){pending.complete(null,failure);return;}
                    try{TLRPC.TL_updateShortSentMessage out=new TLRPC.TL_updateShortSentMessage();out.id=bridge.ids.message(sent.id,sent.ordinal);out.date=sent.date;out.out=true;out.pts=out.id;out.pts_count=1;pending.complete(out,null);}catch(Exception error){pending.complete(null,error);}
                }));
                pending.startCancellation();
            }catch(Exception failure){pending.complete(null,failure);synchronized(pending){pending.startCancellation();}}});return true;
        }
        execute(()->{try{pending.complete(forAccount(account).handle(request),null);}catch(Exception failure){pending.complete(null,failure);}});return true;
    }
    public static void cancelRequest(int account,int token,Runnable onCancelled){PendingRequest pending=REQUESTS.get(requestKey(account,token));if(pending!=null)pending.cancel(onCancelled);}
    public static void bindRequestToGuid(int account,int token,int guid){PendingRequest pending=REQUESTS.get(requestKey(account,token));if(pending!=null && guid!=0)synchronized(pending){pending.guid=guid;}}
    public static void cancelRequestsForGuid(int account,int guid){if(guid==0)return;for(PendingRequest pending:REQUESTS.values()){boolean matches;synchronized(pending){matches=pending.account==account && pending.guid==guid;}if(matches)pending.cancel(null);}}
    private static TLRPC.TL_error problem(Throwable failure){
        TLRPC.TL_error error=null;if(failure!=null){error=new TLRPC.TL_error();error.code=failure instanceof RestRepository.ApiException?((RestRepository.ApiException)failure).status:503;error.text=failure instanceof UnsupportedOperationException?"VYRN_FEATURE_UNAVAILABLE":"VYRN_REQUEST_FAILED";}
        return error;
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
    private Models.Conversation conversationModel(String id) throws Exception{for(Models.Conversation value:repository.conversations())if(value.id.equals(id))return value;throw new IllegalStateException("conversation_missing");}
    private void applyEvent(String kind,JSONObject body) throws Exception {
        if(!kind.startsWith("message."))return;
        String conversation=body.getString("conversation_id"),messageId=body.getString("message_id");Models.Message value=repository.message(messageId);
        Models.Conversation model=conversationModel(conversation);List<Models.User> members=repository.members(conversation);TLRPC.Peer destination=peer(model,members);
        ArrayList<TLRPC.User> users=new ArrayList<>();for(Models.User user:members)users.add(mapUser(user));ArrayList<TLRPC.Chat> chats=new ArrayList<>();if(model.kind.equals("group"))chats.add(chat(model));
        ArrayList<TLRPC.Update> updates=new ArrayList<>();
        if(kind.equals("message.deleted")||kind.equals("message.expired")){
            TL_update.TL_updateDeleteMessages update=new TL_update.TL_updateDeleteMessages();update.messages.add(ids.message(value.id,value.ordinal));update.pts=value.ordinal;update.pts_count=1;updates.add(update);
        }else{
            TLRPC.Message mapped=message(value,destination);
            if(kind.equals("message.created")){TL_update.TL_updateNewMessage update=new TL_update.TL_updateNewMessage();update.message=mapped;update.pts=value.ordinal;update.pts_count=1;updates.add(update);}
            else{TL_update.TL_updateEditMessage update=new TL_update.TL_updateEditMessage();update.message=mapped;update.pts=value.ordinal;update.pts_count=1;updates.add(update);}
        }
        CountDownLatch complete=new CountDownLatch(1);boolean[] accepted={false};
        Utilities.stageQueue.postRunnable(()->{try{accepted[0]=MessagesController.getInstance(account).processUpdateArray(updates,users,chats,true,value.date);}finally{complete.countDown();}});
        if(!complete.await(20,TimeUnit.SECONDS)||!accepted[0])throw new IllegalStateException("telegram_update_rejected");
    }
    public long syncCursor() throws Exception {String user=repository.vault.load().optString("user_id");return user.isEmpty()?0:realtime.cursor(user);}
    public int syncPending() throws Exception {String user=repository.vault.load().optString("user_id");return user.isEmpty()?0:realtime.pending(user);}
    public int syncDead() throws Exception {String user=repository.vault.load().optString("user_id");return user.isEmpty()?0:realtime.dead(user);}
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
