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
    private static final Map<MessageObject,EditRequest> EDITS=new java.util.WeakHashMap<>();
    /** Local provenance only; inherited TL serialization deliberately omits it. */
    private static final class ObservedMessage extends TLRPC.TL_message {
        final String serverId,conversation,sender;final int revision,ordinal;
        ObservedMessage(Models.Message value){serverId=value.id;conversation=value.conversation;sender=value.sender;revision=value.deleted||value.encrypted?0:value.revision;ordinal=value.ordinal;}
    }
    private static final class EditRequest extends TLRPC.TL_messages_editMessage {
        final int account,revision,originalId;final long dialog;final String user,conversation,serverId;
        EditRequest(int account,int revision,int id,long dialog,String user,String conversation,String serverId){this.account=account;this.revision=revision;this.originalId=id;this.dialog=dialog;this.user=user;this.conversation=conversation;this.serverId=serverId;}
    }
    public static synchronized void captureEdit(int account,MessageObject object){
        if(object==null)return;EDITS.remove(object);
        if(!(object.messageOwner instanceof ObservedMessage)||object.getDialogId()>=0)return;
        ObservedMessage value=(ObservedMessage)object.messageOwner;VyrnBridge bridge=forAccount(account);
        if(value.revision<=0||value.ordinal!=object.getId()||!bridge.repository.vault.isUser(value.sender)||UserConfig.getInstance(account).getClientUserId()!=bridge.ids.local("user",value.sender))return;
        EDITS.put(object,new EditRequest(account,value.revision,object.getId(),object.getDialogId(),value.sender,value.conversation,value.serverId));
    }
    public static synchronized TLRPC.TL_messages_editMessage editRequest(int account,MessageObject object){
        EditRequest basis=EDITS.get(object);
        if(basis==null||basis.account!=account||basis.originalId!=object.getId()||basis.dialog!=object.getDialogId())return new EditRequest(account,0,object.getId(),object.getDialogId(),null,null,null);
        return new EditRequest(account,basis.revision,basis.originalId,basis.dialog,basis.user,basis.conversation,basis.serverId);
    }
    public static void processEditUpdates(int account,TLRPC.TL_messages_editMessage request,TLRPC.Updates updates){
        if(!(request instanceof EditRequest))return;EditRequest basis=(EditRequest)request;
        Utilities.stageQueue.postRunnable(()->{VyrnBridge bridge=forAccount(account);if(bridge.repository.vault.isUser(basis.user)&&UserConfig.getInstance(account).getClientUserId()==bridge.ids.local("user",basis.user))MessagesController.getInstance(account).processUpdateArray(updates.updates,updates.users,updates.chats,true,updates.date);});
    }
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
        final int deletedId;
        if(resolution.state==OutboxStore.SENT){
            Models.Message remote=repository.messageForUser(resolution.user,resolution.server);
            if(remote.deleted){committed=null;deletedId=ids.message(remote.id,remote.ordinal);}
            else{deletedId=0;TLRPC.TL_peerChat peer=new TLRPC.TL_peerChat();peer.chat_id=-resolution.dialogId;
                committed=message(remote,peer);committed.dialog_id=resolution.dialogId;committed.random_id=resolution.randomId;committed.out=true;committed.send_state=MessageObject.MESSAGE_SEND_STATE_SENT;}
        }else{committed=null;deletedId=0;}
        MessagesStorage storage=MessagesStorage.getInstance(account);CountDownLatch written=new CountDownLatch(1);boolean[] safe={false};
        storage.getStorageQueue().postRunnable(()->{try{
            if(!repository.vault.isUser(resolution.user) || UserConfig.getInstance(account).getClientUserId()!=selfId)return;
            org.telegram.SQLite.SQLiteCursor cursor=storage.getDatabase().queryFinalized("SELECT r.random_id FROM messages_v2 m LEFT JOIN randoms_v2 r ON r.mid=m.mid AND r.uid=m.uid WHERE m.mid="+resolution.localId+" AND m.uid="+resolution.dialogId);
            try{if(cursor.next() && (cursor.isNull(0) || cursor.longValue(0)!=resolution.randomId))return;}finally{cursor.dispose();}
            ArrayList<Integer> localIds=new ArrayList<>();localIds.add(resolution.localId);if(deletedId!=0)localIds.add(deletedId);
            if(committed==null){storage.markMessagesAsDeleted(resolution.dialogId,localIds,false,false,0,0);storage.updateDialogsWithDeletedMessages(resolution.dialogId,0,localIds,null);}
            else{storage.updateMessageStateAndId(resolution.randomId,resolution.dialogId,resolution.localId,committed.id,committed.date,false,0,0);ArrayList<TLRPC.Message> messages=new ArrayList<>();messages.add(committed);storage.putMessages(messages,true,false,false,0,0,0);}
            safe[0]=true;
        }catch(Exception unavailable){/* No acknowledgement until reads prove the mutation. */}finally{written.countDown();}});
        if(!written.await(20,TimeUnit.SECONDS) || !safe[0])return;
        CountDownLatch displayed=new CountDownLatch(1);boolean[] active={false};
        AndroidUtilities.runOnUIThread(()->{try{
            if(!repository.vault.isUser(resolution.user) || UserConfig.getInstance(account).getClientUserId()!=selfId)return;
            SendMessagesHelper.getInstance(account).vyrnResolveCancellation(resolution.localId,resolution.dialogId,committed);
            if(deletedId!=0)SendMessagesHelper.getInstance(account).vyrnResolveCancellation(deletedId,resolution.dialogId,null);active[0]=true;
        }finally{displayed.countDown();}});
        if(!displayed.await(20,TimeUnit.SECONDS) || !active[0])return;
        CountDownLatch verified=new CountDownLatch(1);boolean[] complete={false};
        storage.getStorageQueue().postRunnable(()->{try{
            if(!repository.vault.isUser(resolution.user) || UserConfig.getInstance(account).getClientUserId()!=selfId)return;
            org.telegram.SQLite.SQLiteCursor cursor=storage.getDatabase().queryFinalized("SELECT count(*) FROM messages_v2 WHERE mid="+resolution.localId+" AND uid="+resolution.dialogId);
            try{complete[0]=cursor.next() && cursor.intValue(0)==0;}finally{cursor.dispose();}
            if(complete[0] && committed!=null){cursor=storage.getDatabase().queryFinalized("SELECT count(*) FROM messages_v2 WHERE mid="+committed.id+" AND uid="+resolution.dialogId+" AND send_state=0");try{complete[0]=cursor.next() && cursor.intValue(0)==1;}finally{cursor.dispose();}}
            if(complete[0] && deletedId!=0){cursor=storage.getDatabase().queryFinalized("SELECT count(*) FROM messages_v2 WHERE mid="+deletedId+" AND uid="+resolution.dialogId);try{complete[0]=cursor.next() && cursor.intValue(0)==0;}finally{cursor.dispose();}}
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
                    try{bridge.ids.observe(sent);TLRPC.TL_updateShortSentMessage out=new TLRPC.TL_updateShortSentMessage();out.id=bridge.ids.message(sent.id,sent.ordinal);out.date=sent.date;out.out=true;out.pts=out.id;out.pts_count=1;pending.complete(out,null);}catch(Exception error){pending.complete(null,error);}
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
        if(failure instanceof RestRepository.ApiException&&((RestRepository.ApiException)failure).code.equals("revision_conflict"))error.text="VYRN_EDIT_CONFLICT";
        if(failure instanceof IllegalStateException&&"edit_snapshot_required".equals(failure.getMessage())){error.code=409;error.text="VYRN_EDIT_RELOAD_REQUIRED";}
        return error;
    }
    public static void showEditError(int account,TLRPC.TL_error error,org.telegram.ui.ActionBar.BaseFragment fragment,TLRPC.TL_messages_editMessage request){
        boolean rtl=org.telegram.messenger.LocaleController.isRTL;
        if("VYRN_EDIT_CONFLICT".equals(error.text)||"VYRN_EDIT_RELOAD_REQUIRED".equals(error.text))org.telegram.ui.Components.AlertsCreator.showSimpleAlert(fragment,rtl?"پیام تغییر کرده یا نسخهٔ آن مشخص نیست. پیام را دوباره بارگیری کنید و ویرایش را تکرار کنید.":"The message changed or its version is unknown. Reload the message and edit again.");
        else org.telegram.ui.Components.AlertsCreator.processError(account,error,fragment,request);
    }
    public TLRPC.User mapUser(Models.User value){TLRPC.TL_user u=new TLRPC.TL_user();u.id=ids.local("user",value.id);u.flags=2|4|32|64;u.first_name=value.name;u.last_name="";u.phone="";u.self=u.id==UserConfig.getInstance(account).getClientUserId();u.photo=new TLRPC.TL_userProfilePhotoEmpty();u.status=new TLRPC.TL_userStatusOffline();return u;}
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
        ids.observe(m);
        TLRPC.TL_message out=new ObservedMessage(m);out.id=ids.message(m.id,m.ordinal);out.peer_id=peer;TLRPC.TL_peerUser sender=new TLRPC.TL_peerUser();sender.user_id=ids.local("user",m.sender);out.from_id=sender;out.flags|=256;out.date=m.date;out.message=m.deleted?"":m.encrypted?(org.telegram.messenger.LocaleController.isRTL?"این پیام در این دستگاه قابل نمایش نیست.":"This message cannot be displayed on this device."):m.text;out.out=sender.user_id==UserConfig.getInstance(account).getClientUserId();out.media=new TLRPC.TL_messageMediaEmpty();out.edit_date=m.editedDate;if(m.editedDate>0)out.flags|=TLRPC.MESSAGE_FLAG_EDITED;return out;
    }
    private TLRPC.TL_messages_dialogs dialogs() throws Exception {
        TLRPC.TL_messages_dialogs result=new TLRPC.TL_messages_dialogs();
        for(Models.Conversation c:repository.conversations()){
            List<Models.User> members=repository.members(c.id);TLRPC.Peer peer=peer(c,members);for(Models.User u:members)result.users.add(mapUser(u));if(c.kind.equals("group"))result.chats.add(chat(c));
            TLRPC.TL_dialog dialog=new TLRPC.TL_dialog();dialog.peer=peer;dialog.id=peer.chat_id!=0?-peer.chat_id:peer.user_id;dialog.notify_settings=new TLRPC.TL_peerNotifySettings();dialog.ttl_period=c.disappearingSeconds;if(c.disappearingSeconds>0)dialog.flags|=32;
            List<Models.Message> messages=repository.messages(c.id,null,1);if(!messages.isEmpty()){TLRPC.Message message=message(messages.get(0),peer);result.messages.add(message);dialog.top_message=message.id;dialog.last_message_date=message.date;}result.dialogs.add(dialog);
        }return result;
    }
    private Models.Conversation conversationModel(String id) throws Exception{return repository.conversationDetails(id).conversation;}
    private void applyEvent(String kind,JSONObject body) throws Exception {
        if(kind.equals("conversation.updated")){applyConversationEvent(body.getString("id"),true);return;}
        if(kind.equals("member.updated")||kind.equals("member.removed")){applyConversationEvent(body.getString("conversation_id"),false);return;}
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
    private void applyConversationEvent(String id,boolean refresh) throws Exception {
        String user;Models.ConversationDetails details;
        synchronized(repository.vault.guard()){user=repository.vault.load().getString("user_id");details=repository.conversationDetails(id);}
        if(!details.conversation.kind.equals("group"))return;
        TLRPC.Chat group=chat(details.conversation);group.date=details.createdDate;
        ArrayList<TLRPC.Chat> chats=new ArrayList<>();chats.add(group);ArrayList<TLRPC.User> users=new ArrayList<>();for(Models.Member member:details.members)users.add(mapUser(member.user));
        ArrayList<TLRPC.Update> updates=new ArrayList<>();if(refresh){TL_update.TL_updateChat changed=new TL_update.TL_updateChat();changed.chat_id=group.id;updates.add(changed);}
        TL_update.TL_updateChatParticipants membership=new TL_update.TL_updateChatParticipants();membership.participants=participants(details);updates.add(membership);
        TL_update.TL_updatePeerHistoryTTL ttl=new TL_update.TL_updatePeerHistoryTTL();TLRPC.TL_peerChat destination=new TLRPC.TL_peerChat();destination.chat_id=group.id;ttl.peer=destination;ttl.ttl_period=details.conversation.disappearingSeconds;if(ttl.ttl_period>0)ttl.flags=1;updates.add(ttl);
        CountDownLatch applied=new CountDownLatch(1);boolean[] accepted={false};
        Utilities.stageQueue.postRunnable(()->{try{if(!repository.vault.isUser(user)||UserConfig.getInstance(account).getClientUserId()!=ids.local("user",user))return;AndroidUtilities.runOnUIThread(()->{if(!repository.vault.isUser(user))return;TLRPC.ChatFull full=MessagesController.getInstance(account).getChatFull(group.id);if(full!=null)full.participants=membership.participants;});MessagesStorage.getInstance(account).putUsersAndChats(users,chats,false,true);accepted[0]=MessagesController.getInstance(account).processUpdateArray(updates,users,chats,true,(int)(System.currentTimeMillis()/1000));}finally{applied.countDown();}});
        if(!applied.await(20,TimeUnit.SECONDS)||!accepted[0])throw new IllegalStateException("telegram_metadata_rejected");
        CountDownLatch scheduled=new CountDownLatch(1);AndroidUtilities.runOnUIThread(scheduled::countDown);if(!scheduled.await(20,TimeUnit.SECONDS))throw new IllegalStateException("telegram_metadata_timeout");
        CountDownLatch persisted=new CountDownLatch(1);boolean[] durable={false};MessagesStorage storage=MessagesStorage.getInstance(account);
        storage.getStorageQueue().postRunnable(()->{try{TLRPC.Chat saved=storage.getChat(group.id);durable[0]=repository.vault.isUser(user)&&saved!=null&&saved.title.equals(group.title);}finally{persisted.countDown();}});
        if(!persisted.await(20,TimeUnit.SECONDS)||!durable[0])throw new IllegalStateException("telegram_metadata_not_saved");
    }
    private TLRPC.TL_chatParticipants participants(Models.ConversationDetails details){
        TLRPC.TL_chatParticipants result=new TLRPC.TL_chatParticipants();result.chat_id=ids.local("conversation",details.conversation.id);result.version=1;
        for(Models.Member member:details.members){TLRPC.ChatParticipant participant;
            if(member.role.equals("owner"))participant=new TLRPC.TL_chatParticipantCreator();else if(member.role.equals("admin"))participant=new TLRPC.TL_chatParticipantAdmin();else participant=new TLRPC.TL_chatParticipant();
            participant.user_id=ids.local("user",member.user.id);participant.date=member.joinedDate;result.participants.add(participant);}
        return result;
    }
    public long syncCursor() throws Exception {String user=repository.vault.load().optString("user_id");return user.isEmpty()?0:realtime.cursor(user);}
    public int syncPending() throws Exception {String user=repository.vault.load().optString("user_id");return user.isEmpty()?0:realtime.pending(user);}
    public int syncDead() throws Exception {String user=repository.vault.load().optString("user_id");return user.isEmpty()?0:realtime.dead(user);}
    private synchronized TLObject handle(TLObject request) throws Exception {
        if(request instanceof TLRPC.TL_messages_editMessage){
            if(!(request instanceof EditRequest))throw new UnsupportedOperationException();EditRequest in=(EditRequest)request;
            if(in.revision<=0)throw new IllegalStateException("edit_snapshot_required");
            if(in.account!=account||in.id!=in.originalId||in.peer==null||in.peer.chat_id<=0||-in.peer.chat_id!=in.dialog||!in.conversation.equals(ids.remote("conversation",in.peer.chat_id))||!in.serverId.equals(ids.remote("message",in.id)))throw new SecurityException("edit_identity_changed");
            if((in.flags&2048)==0||(in.flags&~(2048|8|2))!=0||in.media!=null||in.reply_markup!=null||in.rich_message!=null||in.entities!=null&&!in.entities.isEmpty())throw new UnsupportedOperationException();
            synchronized(repository.vault.guard()){
                if(!repository.vault.isUser(in.user)||UserConfig.getInstance(account).getClientUserId()!=ids.local("user",in.user))throw new SecurityException("account_changed");
                Models.Message edited=repository.editText(in.user,in.serverId,in.revision,in.message);
                TLRPC.TL_updates result=new TLRPC.TL_updates();result.date=edited.editedDate;
                if(edited.deleted){TL_update.TL_updateDeleteMessages deleted=new TL_update.TL_updateDeleteMessages();deleted.messages.add(ids.message(edited.id,edited.ordinal));result.updates.add(deleted);}
                else{TLRPC.TL_peerChat peer=new TLRPC.TL_peerChat();peer.chat_id=in.peer.chat_id;TL_update.TL_updateEditMessage update=new TL_update.TL_updateEditMessage();update.message=message(edited,peer);result.updates.add(update);}
                Models.ConversationDetails details=repository.conversationDetails(in.conversation);TLRPC.Chat group=chat(details.conversation);group.date=details.createdDate;result.chats.add(group);for(Models.Member member:details.members)result.users.add(mapUser(member.user));return result;
            }
        }
        if(request instanceof TLRPC.TL_messages_addChatUser){
            TLRPC.TL_messages_addChatUser in=(TLRPC.TL_messages_addChatUser)request;if(in.fwd_limit!=0)throw new UnsupportedOperationException();String id=ids.remote("conversation",in.chat_id);repository.inviteMember(id,inputUser(in.user_id));
            TLRPC.TL_messages_invitedUsers result=new TLRPC.TL_messages_invitedUsers();result.updates=membershipUpdates(id);return result;
        }
        if(request instanceof TLRPC.TL_messages_editChatAdmin){
            TLRPC.TL_messages_editChatAdmin in=(TLRPC.TL_messages_editChatAdmin)request;repository.setMemberRole(ids.remote("conversation",in.chat_id),inputUser(in.user_id),in.is_admin);return new TLRPC.TL_boolTrue();
        }
        if(request instanceof TLRPC.TL_messages_deleteChatUser){
            TLRPC.TL_messages_deleteChatUser in=(TLRPC.TL_messages_deleteChatUser)request;String user=inputUser(in.user_id);if(in.revoke_history||user.equals(repository.vault.load().getString("user_id")))throw new UnsupportedOperationException();String id=ids.remote("conversation",in.chat_id);repository.removeMember(id,user);return membershipUpdates(id);
        }
        if(request instanceof TLRPC.TL_messages_setHistoryTTL){
            TLRPC.TL_messages_setHistoryTTL in=(TLRPC.TL_messages_setHistoryTTL)request;String id=conversation(in.peer);repository.setHistoryTTL(id,in.period);
            Models.ConversationDetails details=repository.conversationDetails(id);TLRPC.TL_updates result=new TLRPC.TL_updates();result.date=(int)(System.currentTimeMillis()/1000);
            TL_update.TL_updatePeerHistoryTTL update=new TL_update.TL_updatePeerHistoryTTL();update.peer=peer(details.conversation,repository.members(id));update.ttl_period=details.conversation.disappearingSeconds;if(update.ttl_period>0)update.flags=1;result.updates.add(update);return result;
        }
        if(request instanceof TLRPC.TL_messages_editChatTitle){
            TLRPC.TL_messages_editChatTitle in=(TLRPC.TL_messages_editChatTitle)request;String id=ids.remote("conversation",in.chat_id);repository.renameGroup(id,in.title);
            Models.ConversationDetails details=repository.conversationDetails(id);TLRPC.TL_updates result=new TLRPC.TL_updates();result.date=(int)(System.currentTimeMillis()/1000);TLRPC.Chat group=chat(details.conversation);group.date=details.createdDate;result.chats.add(group);TL_update.TL_updateChat changed=new TL_update.TL_updateChat();changed.chat_id=in.chat_id;result.updates.add(changed);return result;
        }
        if(request instanceof TLRPC.TL_messages_getFullChat){
            TLRPC.TL_messages_getFullChat in=(TLRPC.TL_messages_getFullChat)request;Models.ConversationDetails details=repository.conversationDetails(ids.remote("conversation",in.chat_id));
            if(!details.conversation.kind.equals("group"))throw new IllegalArgumentException("group_required");
            TLRPC.TL_messages_chatFull result=new TLRPC.TL_messages_chatFull();TLRPC.Chat group=chat(details.conversation);group.date=details.createdDate;result.chats.add(group);
            TLRPC.TL_chatFull full=new TLRPC.TL_chatFull();full.id=in.chat_id;full.about="";full.chat_photo=new TLRPC.TL_photoEmpty();full.flags=4;full.notify_settings=new TLRPC.TL_peerNotifySettings();full.ttl_period=details.conversation.disappearingSeconds;if(full.ttl_period>0)full.flags|=16384;
            for(Models.Member member:details.members)result.users.add(mapUser(member.user));
            full.participants=participants(details);result.full_chat=full;return result;
        }
        if(request instanceof TLRPC.TL_contacts_search){
            TLRPC.TL_contacts_search in=(TLRPC.TL_contacts_search)request;TLRPC.TL_contacts_found result=new TLRPC.TL_contacts_found();
            for(Models.User found:repository.searchUsers(in.q,in.limit)){TLRPC.User user=mapUser(found);result.users.add(user);TLRPC.TL_peerUser peer=new TLRPC.TL_peerUser();peer.user_id=user.id;result.results.add(peer);}return result;
        }
        if(request instanceof TLRPC.TL_messages_createChat){
            TLRPC.TL_messages_createChat in=(TLRPC.TL_messages_createChat)request;int ttl=(in.flags&1)!=0?in.ttl_period:0;
            ArrayList<String> members=new ArrayList<>();for(TLRPC.InputUser member:in.users){if(member instanceof TLRPC.TL_inputUserSelf)continue;if(!(member instanceof TLRPC.TL_inputUser))throw new IllegalArgumentException("invalid_group_member");members.add(ids.remote("user",member.user_id));}
            Models.Conversation created=repository.createGroup(in.title,members,ttl);TLRPC.TL_updates updates=new TLRPC.TL_updates();updates.date=(int)(System.currentTimeMillis()/1000);updates.chats.add(chat(created));
            if(created.disappearingSeconds>0){TL_update.TL_updatePeerHistoryTTL update=new TL_update.TL_updatePeerHistoryTTL();TLRPC.TL_peerChat peer=new TLRPC.TL_peerChat();peer.chat_id=ids.local("conversation",created.id);update.peer=peer;update.flags=1;update.ttl_period=created.disappearingSeconds;updates.updates.add(update);}
            for(Models.User member:repository.members(created.id))updates.users.add(mapUser(member));TLRPC.TL_messages_invitedUsers result=new TLRPC.TL_messages_invitedUsers();result.updates=updates;return result;
        }
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
    private String inputUser(TLRPC.InputUser user) throws Exception {
        if(user instanceof TLRPC.TL_inputUserSelf)return repository.vault.load().getString("user_id");
        if(!(user instanceof TLRPC.TL_inputUser))throw new IllegalArgumentException("invalid_group_member");return ids.remote("user",user.user_id);
    }
    private TLRPC.TL_updates membershipUpdates(String id) throws Exception {
        Models.ConversationDetails details=repository.conversationDetails(id);TLRPC.TL_updates result=new TLRPC.TL_updates();result.date=(int)(System.currentTimeMillis()/1000);TLRPC.Chat group=chat(details.conversation);group.date=details.createdDate;result.chats.add(group);
        for(Models.Member member:details.members)result.users.add(mapUser(member.user));TL_update.TL_updateChatParticipants changed=new TL_update.TL_updateChatParticipants();changed.participants=participants(details);result.updates.add(changed);return result;
    }
}
