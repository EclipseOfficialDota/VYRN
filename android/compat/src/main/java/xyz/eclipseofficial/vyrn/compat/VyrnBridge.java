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
import org.telegram.messenger.NotificationCenter;
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
import org.telegram.tgnet.tl.TL_account;
import xyz.eclipseofficial.vyrn.data.IdMap;
import xyz.eclipseofficial.vyrn.data.RestRepository;
import xyz.eclipseofficial.vyrn.data.TokenVault;
import xyz.eclipseofficial.vyrn.data.OutboxSender;
import xyz.eclipseofficial.vyrn.data.OutboxStore;
import xyz.eclipseofficial.vyrn.data.RealtimeSync;
import xyz.eclipseofficial.vyrn.data.SignalDeviceKeys;
import xyz.eclipseofficial.vyrn.data.SignalMessages;
import xyz.eclipseofficial.vyrn.domain.Models;

/** The only adapter allowed to depend on both Telegram wire-shaped UI models and VYRN domain models. */
public final class VyrnBridge {
    private static final ExecutorService IO=Executors.newFixedThreadPool(2);
    private static final ExecutorService SEND=Executors.newSingleThreadExecutor();
    private static final Map<Integer,VyrnBridge> INSTANCES=new HashMap<>();
    private static final java.util.Set<Integer> LOGGING_OUT=ConcurrentHashMap.newKeySet();
    public static boolean isLoggingOut(int account){return LOGGING_OUT.contains(account);}
    public static java.util.concurrent.CompletableFuture<Boolean> logout(int account,Runnable cleanup){
        java.util.concurrent.CompletableFuture<Boolean> result=new java.util.concurrent.CompletableFuture<>();
        if(!LOGGING_OUT.add(account)){result.complete(false);return result;}
        VyrnBridge bridge=forAccount(account);
        final long origin=UserConfig.getInstance(account).getClientUserId();
        final String session=bridge.repository.vault.cachedSession();
        execute(()->{
            boolean confirmed=false;
            try{
                synchronized(bridge.repository.vault.guard()){
                    JSONObject tokens=bridge.repository.vault.load();String user=tokens.getString("user_id");
                    if(origin==0||session==null||session.isEmpty()||!session.equals(tokens.optString("session_id"))||bridge.ids.local("user",user)!=origin||UserConfig.getInstance(account).getClientUserId()!=origin)throw new SecurityException("session_changed");
                    try{bridge.repository.requestForSession(user,session,"POST","/v1/auth/logout",null);}catch(RestRepository.ApiException failure){
                        if(failure.status!=401||!failure.code.equals("invalid_credentials"))throw failure;
                    }
                    bridge.repository.vault.clear();
                    synchronized(VyrnBridge.class){if(INSTANCES.get(account)==bridge)INSTANCES.remove(account);}
                    confirmed=true;
                }
                try{bridge.realtime.close();}finally{bridge.outbox.close();}
            }catch(Exception failure){/* Keep the session when logout cannot be confirmed. */}
            final boolean accepted=confirmed;
            AndroidUtilities.runOnUIThread(()->{
                try{
                    if(accepted&&bridge.repository.vault.cachedSession()==null&&UserConfig.getInstance(account).getClientUserId()==origin){cleanup.run();result.complete(true);}
                    else{
                        boolean fa="fa".equals(java.util.Locale.getDefault().getLanguage());
                        android.widget.Toast.makeText(ApplicationLoader.applicationContext,fa?"خروج تأیید نشد. اتصال را بررسی کنید و دوباره تلاش کنید.":"Logout was not confirmed. Check the connection and retry.",android.widget.Toast.LENGTH_LONG).show();result.complete(false);
                    }
                }catch(Exception failure){result.completeExceptionally(failure);}finally{LOGGING_OUT.remove(account);}
            });
        });return result;
    }
    private static final Map<MessageObject,EditRequest> EDITS=new java.util.WeakHashMap<>();
    public static final String EDIT_SNAPSHOT="vyrn_edit_snapshot";
    private static String textHash(String text){try{return java.util.Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
    private static String editSnapshot(Models.Message value){
        if(value.revision<=0||value.deleted||value.encrypted||value.conversation==null)return null;
        try{return new JSONObject().put("version",1).put("id",value.id).put("conversation",value.conversation).put("sender",value.sender).put("ordinal",value.ordinal).put("revision",value.revision).put("date",value.date).put("edited",value.editedDate).put("hash",textHash(value.text)).toString();}catch(org.json.JSONException impossible){throw new IllegalStateException(impossible);}
    }
    /** Keep existing local UI attributes without assigning an older edit basis to incoming content. */
    public static void mergeLocalMessageParams(TLRPC.Message message,org.telegram.tgnet.NativeByteBuffer previous){
        String snapshot=message.params==null?null:message.params.get(EDIT_SNAPSHOT);
        org.telegram.messenger.MessageCustomParamsHelper.readLocalParams(message,previous);
        if(message.params!=null)message.params.remove(EDIT_SNAPSHOT);
        if(snapshot!=null){if(message.params==null)message.params=new HashMap<>();message.params.put(EDIT_SNAPSHOT,snapshot);}
    }
    private static final class EditRequest extends TLRPC.TL_messages_editMessage {
        final int account,revision,originalId;final long dialog;final String user,conversation,serverId;
        EditRequest(int account,int revision,int id,long dialog,String user,String conversation,String serverId){this.account=account;this.revision=revision;this.originalId=id;this.dialog=dialog;this.user=user;this.conversation=conversation;this.serverId=serverId;}
    }
    public static synchronized void captureEdit(int account,MessageObject object){
        if(object==null)return;EDITS.remove(object);
        TLRPC.Message message=object.messageOwner;if(message==null||message.params==null||object.getDialogId()>=0)return;
        try{JSONObject value=new JSONObject(message.params.get(EDIT_SNAPSHOT));VyrnBridge bridge=forAccount(account);String sender=value.getString("sender"),conversation=value.getString("conversation"),id=value.getString("id");
            if(value.getInt("version")!=1||value.getInt("revision")<=0||value.getInt("ordinal")!=object.getId()||value.getInt("date")!=message.date||value.getInt("edited")!=message.edit_date||!value.getString("hash").equals(textHash(message.message))||!bridge.repository.vault.isUser(sender)||UserConfig.getInstance(account).getClientUserId()!=bridge.ids.local("user",sender)||message.from_id==null||message.from_id.user_id!=bridge.ids.local("user",sender)||!conversation.equals(bridge.ids.remote("conversation",-object.getDialogId()))||!id.equals(bridge.ids.remote("message",object.getId())))return;
            EDITS.put(object,new EditRequest(account,value.getInt("revision"),object.getId(),object.getDialogId(),sender,conversation,id));
        }catch(Exception unavailable){/* Unknown or mismatched cache provenance must refresh before editing. */}
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
        long sessionOrigin;
        final ArrayList<Runnable> cancelledCallbacks=new ArrayList<>();
        PendingRequest(int account,int token,boolean send,RequestDelegate callback,RequestDelegateTimestamp timestamp){this.account=account;this.token=token;this.send=send;this.callback=callback;this.timestamp=timestamp;}
        synchronized void complete(TLObject result,Throwable failure){
            if(finished){if(result instanceof TLRPC.TL_upload_file)result.freeResources();return;}this.result=result;this.failure=failure;resultReady=true;
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
            if(cancelled!=null){if(result instanceof TLRPC.TL_upload_file)result.freeResources();for(Runnable task:cancelled)task.run();return;}
            if(sessionOrigin!=0 && (bridge==null || !bridge.repository.vault.isUser(user) || UserConfig.getInstance(account).getClientUserId()!=sessionOrigin)){if(response instanceof TLRPC.TL_upload_file)response.freeResources();response=null;error=new SecurityException("account_changed");}
            TLRPC.TL_error problem=problem(error);try{if(callback!=null)callback.run(response,problem);if(timestamp!=null)timestamp.run(response,problem,System.currentTimeMillis());}finally{if(response instanceof TLRPC.TL_upload_file)response.freeResources();}
        }
    }
    public final RestRepository repository;
    private final IdMap ids;
    private final OutboxSender outbox;
    private final RealtimeSync realtime;
    private final int account;
    private final AtomicBoolean uiReconciling=new AtomicBoolean();
    private final AtomicBoolean privateRecovering=new AtomicBoolean();
    private java.util.concurrent.CompletableFuture<Void> privateKeysPreparation;
    private volatile String privateKeysSession;
    private volatile long privateKeysOwner;
    private final Map<Long,String> privateConversations=new HashMap<>();
    private VyrnBridge(int account){this.account=account;repository=new RestRepository(new TokenVault(ApplicationLoader.applicationContext,account));ids=new IdMap(ApplicationLoader.applicationContext,account);outbox=new OutboxSender(ApplicationLoader.applicationContext,account,repository);realtime=new RealtimeSync(ApplicationLoader.applicationContext,account,repository,this::applyEvent);outbox.setUiListener(this::resolveUi);outbox.setRecoveryListener(this::recoverPrivateSends);}
    public boolean privateKeysReady(){return privateKeysOwner!=0&&privateKeysOwner==UserConfig.getInstance(account).getClientUserId()&&privateKeysSession!=null&&privateKeysSession.equals(repository.vault.cachedSession());}
    public synchronized java.util.concurrent.CompletableFuture<Void> preparePrivateKeys(){
        if(privateKeysReady())return java.util.concurrent.CompletableFuture.completedFuture(null);
        if(privateKeysPreparation!=null&&!privateKeysPreparation.isDone())return privateKeysPreparation;
        final long owner=UserConfig.getInstance(account).getClientUserId();final String session=repository.vault.cachedSession();
        privateKeysPreparation=java.util.concurrent.CompletableFuture.runAsync(()->{
            try{
                if(owner==0||session==null)throw new SecurityException("signal_native_session_missing");
                try(SignalDeviceKeys keys=new SignalDeviceKeys(ApplicationLoader.applicationContext,repository)){
                    if(ids.local("user",keys.user)!=owner)throw new SecurityException("signal_native_owner_changed");
                    keys.ensurePublished();
                    if(UserConfig.getInstance(account).getClientUserId()!=owner||!session.equals(repository.vault.cachedSession()))throw new SecurityException("signal_native_session_changed");
                    privateKeysOwner=owner;privateKeysSession=session;
                }
            }catch(Exception failure){throw new java.util.concurrent.CompletionException(failure);}
        },IO);return privateKeysPreparation;
    }
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
            if(remote.deleted){committed=null;deletedId=ids.message(remote.id,remote.ordinal);fenceDeleted(resolution.user,resolution.dialogId,java.util.Collections.singletonList(deletedId));}
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
    /** The encrypted native outbox is not connected yet; reject before native plaintext persistence. */
    public static boolean privateSendUnavailable(long dialog){return org.telegram.messenger.DialogObject.isUserDialog(dialog)||org.telegram.messenger.DialogObject.isEncryptedDialog(dialog);}
    public static String privateSendUnavailableLabel(){return org.telegram.messenger.LocaleController.isRTL?"ارسال خصوصی هنوز آماده نیست؛ پیش‌نویس حفظ می‌شود.":"Private sending is not ready; your draft is kept.";}
    public static boolean blockPrivateSend(long dialog){
        if(!privateSendUnavailable(dialog))return false;
        AndroidUtilities.runOnUIThread(()->android.widget.Toast.makeText(ApplicationLoader.applicationContext,privateSendUnavailableLabel(),android.widget.Toast.LENGTH_LONG).show());return true;
    }
    public static final int PRIVATE_TEXT_UPDATE=1<<29;
    public static final String PRIVATE_PENDING_PLACEHOLDER="Encrypted pending message";
    /** Adapter integration; composer remains gated until retry/cancellation/trust flows are ready. */
    public java.util.concurrent.CompletableFuture<JSONObject> preparePrivateText(long dialog,String conversation,String text){
        final long owner=UserConfig.getInstance(account).getClientUserId();final String session=repository.vault.cachedSession();
        return java.util.concurrent.CompletableFuture.supplyAsync(()->{try{
            if(owner==0||dialog<=0||dialog>Integer.MAX_VALUE||dialog==owner||session==null||!UUID.fromString(conversation).toString().equals(conversation))throw new SecurityException("private_origin_unavailable");
            String user=repository.vault.load().getString("user_id"),peer=ids.remote("user",dialog);requirePrivateOrigin(user,session,owner,peer,dialog);
            Models.Conversation model=repository.conversationDetails(conversation).conversation;List<Models.User> members=repository.members(conversation);boolean own=false,other=false;ArrayList<TLRPC.User> mapped=new ArrayList<>();
            for(Models.User member:members){own|=member.id.equals(user);other|=member.id.equals(peer);mapped.add(mapUser(member));}
            if(!model.kind.equals("private")||members.size()!=2||!own||!other)throw new SecurityException("private_conversation_changed");
            java.util.concurrent.CompletableFuture<long[]> reserved=new java.util.concurrent.CompletableFuture<>();AndroidUtilities.runOnUIThread(()->{try{requirePrivateOrigin(user,session,owner,peer,dialog);int local=UserConfig.getInstance(account).getNewMessageId();UserConfig.getInstance(account).saveConfig(false);MessagesController.getInstance(account).putUsers(mapped,false);reserved.complete(new long[]{local,SendMessagesHelper.getInstance(account).getNextRandomId()});}catch(Exception unavailable){reserved.completeExceptionally(unavailable);}});
            long[] identity=reserved.get(20,TimeUnit.SECONDS);String client=UUID.nameUUIDFromBytes((user+":"+identity[1]).getBytes(StandardCharsets.UTF_8)).toString();JSONObject binding;
            try(SignalDeviceKeys keys=SignalDeviceKeys.openLocal(ApplicationLoader.applicationContext,repository)){if(!user.equals(keys.user))throw new SecurityException("private_origin_changed");SignalMessages messages=new SignalMessages(keys);messages.prepareNative(peer,conversation,client,text,account,(int)identity[0],dialog,identity[1]);binding=messages.nativeBinding(account,client);}
            writePrivateNative(binding,user,session,owner,null);return binding;
        }catch(Exception unavailable){throw new java.util.concurrent.CompletionException(unavailable);}},SEND);
    }
    public java.util.concurrent.CompletableFuture<Models.Message> deliverPrivateText(String client){
        final long owner=UserConfig.getInstance(account).getClientUserId();final String session=repository.vault.cachedSession();
        return java.util.concurrent.CompletableFuture.supplyAsync(()->{try{return deliverPrivateTextNow(client,owner,session);}catch(Exception unavailable){throw new java.util.concurrent.CompletionException(unavailable);}},SEND);
    }
    private Models.Message deliverPrivateTextNow(String client,long owner,String session) throws Exception {
            String user=repository.vault.load().getString("user_id");JSONObject binding;String message;
            try(SignalDeviceKeys keys=SignalDeviceKeys.openLocal(ApplicationLoader.applicationContext,repository)){
                SignalMessages messages=new SignalMessages(keys);binding=messages.nativeBinding(account,client);requirePrivateOrigin(user,session,owner,binding.getString("peer"),binding.getLong("dialog_id"));message=binding.isNull("message_id")?messages.send(client):binding.getString("message_id");binding=messages.nativeBinding(account,client);
            }
            Models.Message remote=repository.messageForUser(user,message);if(!remote.encrypted||remote.deleted||!user.equals(remote.sender)||!binding.getString("conversation").equals(remote.conversation))throw new SecurityException("private_ack_changed");
            if(!binding.getBoolean("ui_done")){TLRPC.TL_peerUser peer=new TLRPC.TL_peerUser();peer.user_id=binding.getLong("dialog_id");TLRPC.Message committed=message(remote,peer);committed.dialog_id=peer.user_id;committed.random_id=binding.getLong("random_id");committed.send_state=MessageObject.MESSAGE_SEND_STATE_SENT;writePrivateNative(binding,user,session,owner,committed);
                try(SignalDeviceKeys keys=SignalDeviceKeys.openLocal(ApplicationLoader.applicationContext,repository)){new SignalMessages(keys).acknowledgeNative(account,client,binding.getInt("local_id"),peer.user_id,binding.getLong("random_id"),message);}
            }return remote;
    }
    public java.util.concurrent.CompletableFuture<Void> schedulePrivateText(String client){
        final long owner=UserConfig.getInstance(account).getClientUserId();final String session=repository.vault.cachedSession();
        return java.util.concurrent.CompletableFuture.runAsync(()->{try{
            String user=repository.vault.load().getString("user_id");JSONObject binding;try(SignalDeviceKeys keys=SignalDeviceKeys.openLocal(ApplicationLoader.applicationContext,repository)){binding=new SignalMessages(keys).nativeBinding(account,client);}
            requirePrivateOrigin(user,session,owner,binding.getString("peer"),binding.getLong("dialog_id"));if(binding.getBoolean("ui_done"))return;
            if(binding.isNull("message_id"))writePrivateNative(binding,user,session,owner,null);
            try(SignalDeviceKeys keys=SignalDeviceKeys.openLocal(ApplicationLoader.applicationContext,repository)){requirePrivateOrigin(user,session,owner,binding.getString("peer"),binding.getLong("dialog_id"));new SignalMessages(keys).scheduleNative(account,client);}
        }catch(Exception unavailable){throw new java.util.concurrent.CompletionException(unavailable);}},SEND);
    }
    public java.util.concurrent.CompletableFuture<Boolean> retryPrivateText(String client){
        final long owner=UserConfig.getInstance(account).getClientUserId();final String session=repository.vault.cachedSession();
        return retryPrivateText(client,owner,session,0,0,0,0);
    }
    /** Handle only the original failed encrypted row; automatic native unsent scans cannot revive it. */
    public static boolean retryPrivateMessage(int account,MessageObject object,boolean unsent){
        if(unsent||object==null||object.messageOwner==null||object.currentAccount!=account||object.getId()>=0||!object.isSendError())return false;
        long owner=UserConfig.getInstance(account).getClientUserId(),dialog=object.getDialogId(),random=object.messageOwner.random_id;int local=object.getId(),date=object.messageOwner.date;
        if(owner==0||dialog<=0||dialog==owner||random==0||date<=0||!samePrivatePending(object,object.messageOwner,local,dialog,owner,date,random))return false;
        VyrnBridge bridge=forAccount(account);String session=bridge.repository.vault.cachedSession();if(session==null||isLoggingOut(account))return false;
        bridge.retryPrivateText(null,owner,session,local,dialog,random,date).whenComplete((retried,failure)->{if(failure!=null)AndroidUtilities.runOnUIThread(()->{
            if(UserConfig.getInstance(account).getClientUserId()==owner&&session.equals(bridge.repository.vault.cachedSession()))android.widget.Toast.makeText(ApplicationLoader.applicationContext,org.telegram.messenger.LocaleController.isRTL?"تلاش دوباره انجام نشد؛ پیام حفظ شد.":"Could not retry; your message is kept.",android.widget.Toast.LENGTH_LONG).show();
        });});return true;
    }
    private java.util.concurrent.CompletableFuture<Boolean> retryPrivateText(String client,long owner,String session,int local,long dialog,long random,int date){
        return java.util.concurrent.CompletableFuture.supplyAsync(()->{try{
            String user=repository.vault.load().getString("user_id");JSONObject binding;boolean retried;
            String original=client!=null?client:UUID.nameUUIDFromBytes((user+":"+random).getBytes(StandardCharsets.UTF_8)).toString();
            try(SignalDeviceKeys keys=SignalDeviceKeys.openLocal(ApplicationLoader.applicationContext,repository)){SignalMessages messages=new SignalMessages(keys);binding=messages.nativeBinding(account,original);requirePrivateOrigin(user,session,owner,binding.getString("peer"),binding.getLong("dialog_id"));if(local!=0&&(binding.getInt("local_id")!=local||binding.getLong("dialog_id")!=dialog||binding.getLong("random_id")!=random||binding.getInt("date")!=date))throw new SecurityException("private_native_retry_changed");if(isLoggingOut(account))throw new SecurityException("private_origin_changed");retried=messages.retryNative(account,original);binding=messages.nativeBinding(account,original);}
            if(retried&&binding.isNull("message_id"))writePrivateNative(binding,user,session,owner,null);return retried;
        }catch(Exception unavailable){throw new java.util.concurrent.CompletionException(unavailable);}},SEND);
    }
    private void recoverPrivateSends(){
        final long owner=UserConfig.getInstance(account).getClientUserId();final String session=repository.vault.cachedSession();
        if(owner==0||session==null||isLoggingOut(account)||!privateRecovering.compareAndSet(false,true))return;
        SEND.execute(()->{try{
            org.json.JSONArray rows;String user;try(SignalDeviceKeys keys=SignalDeviceKeys.openLocal(ApplicationLoader.applicationContext,repository)){user=keys.user;rows=new SignalMessages(keys).nativePending(account);}
            for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(!row.getBoolean("scheduled")||row.getBoolean("failed"))continue;String attempt=null,client=row.getString("client_id");
                try{requirePrivateOrigin(user,session,owner,row.getString("peer"),row.getLong("dialog_id"));if(isLoggingOut(account))return;
                    try(SignalDeviceKeys keys=SignalDeviceKeys.openLocal(ApplicationLoader.applicationContext,repository)){attempt=new SignalMessages(keys).claimNative(account,client);}if(attempt==null)continue;
                    if(row.isNull("message_id"))writePrivateNative(row,user,session,owner,null);deliverPrivateTextNow(client,owner,session);
                }catch(Exception unavailable){if(attempt!=null)try{JSONObject failed;try(SignalDeviceKeys keys=SignalDeviceKeys.openLocal(ApplicationLoader.applicationContext,repository)){requirePrivateOrigin(user,session,owner,row.getString("peer"),row.getLong("dialog_id"));SignalMessages messages=new SignalMessages(keys);messages.failNative(account,client,attempt,unavailable);failed=messages.nativeBinding(account,client);}if(failed.isNull("message_id"))writePrivateNative(failed,user,session,owner,null);}catch(Exception preserved){/* Keep original encrypted work if its owner/storage changed. */}}
            }
        }catch(Exception unavailable){/* Missing original device/session: no publication, claim or send. */}finally{privateRecovering.set(false);}});
        // ponytail: shared SEND executor; parallel transport can be added only if measured queue latency requires it.
    }
    private void requirePrivateOrigin(String user,String session,long owner,String peer,long dialog) throws Exception {
        if(owner==0||session==null||!repository.vault.isUser(user)||!session.equals(repository.vault.cachedSession())||UserConfig.getInstance(account).getClientUserId()!=owner||!user.equals(ids.remote("user",owner))||!peer.equals(ids.remote("user",dialog)))throw new SecurityException("private_origin_changed");
    }
    private void writePrivateNative(JSONObject binding,String user,String session,long owner,TLRPC.Message committed) throws Exception {
        int local=binding.getInt("local_id"),date=binding.getInt("date");long dialog=binding.getLong("dialog_id"),random=binding.getLong("random_id");String peer=binding.getString("peer");requirePrivateOrigin(user,session,owner,peer,dialog);
        if(local>=0||date<=0||random==0||dialog<=0||binding.getBoolean("ui_done"))throw new SecurityException("private_native_binding_changed");
        final TLRPC.Message row;
        if(committed!=null)row=committed;
        else{TLRPC.TL_message pending=new TLRPC.TL_message();pending.id=pending.local_id=local;pending.dialog_id=dialog;pending.date=date;pending.random_id=random;pending.flags=256;pending.out=true;pending.from_id=new TLRPC.TL_peerUser();pending.from_id.user_id=owner;pending.peer_id=new TLRPC.TL_peerUser();pending.peer_id.user_id=dialog;pending.message=PRIVATE_PENDING_PLACEHOLDER;pending.media=new TLRPC.TL_messageMediaEmpty();pending.send_state=binding.optBoolean("failed")?MessageObject.MESSAGE_SEND_STATE_SEND_ERROR:MessageObject.MESSAGE_SEND_STATE_SENDING;row=pending;}
        MessagesStorage storage=MessagesStorage.getInstance(account);java.util.concurrent.CompletableFuture<Void> written=new java.util.concurrent.CompletableFuture<>();
        storage.getStorageQueue().postRunnable(()->{try{
            requirePrivateOrigin(user,session,owner,peer,dialog);org.telegram.SQLite.SQLiteCursor cursor=storage.getDatabase().queryFinalized("SELECT r.random_id,m.data,m.send_state FROM messages_v2 m LEFT JOIN randoms_v2 r ON r.mid=m.mid AND r.uid=m.uid WHERE m.uid="+dialog+" AND m.mid="+local);try{if(cursor.next()){
                if(cursor.isNull(0)||cursor.longValue(0)!=random)throw new SecurityException("private_native_row_conflict");org.telegram.tgnet.NativeByteBuffer data=cursor.byteBufferValue(1);try{TLRPC.Message previous=TLRPC.Message.TLdeserialize(data,data.readInt32(true),true);if(previous.peer_id==null||previous.peer_id.user_id!=dialog||previous.from_id==null||previous.from_id.user_id!=owner||previous.date!=date||!PRIVATE_PENDING_PLACEHOLDER.equals(previous.message)||(cursor.intValue(2)!=MessageObject.MESSAGE_SEND_STATE_SENDING&&cursor.intValue(2)!=MessageObject.MESSAGE_SEND_STATE_SEND_ERROR))throw new SecurityException("private_native_row_conflict");}finally{data.reuse();}
            }}finally{cursor.dispose();}
            if(committed!=null)storage.updateMessageStateAndId(random,dialog,local,committed.id,committed.date,false,0,0);
            ArrayList<TLRPC.Message> rows=new ArrayList<>();rows.add(row);storage.putMessages(rows,true,false,false,0,0,0);written.complete(null);
        }catch(Throwable unavailable){written.completeExceptionally(unavailable);}});written.get(20,TimeUnit.SECONDS);
        java.util.concurrent.CompletableFuture<Void> shown=new java.util.concurrent.CompletableFuture<>();AndroidUtilities.runOnUIThread(()->{try{
            requirePrivateOrigin(user,session,owner,peer,dialog);
            if(committed==null){ArrayList<MessageObject> rows=new ArrayList<>();rows.add(new MessageObject(account,row,true,false));MessagesController.getInstance(account).updateInterfaceWithMessages(dialog,rows,0);NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogsNeedReload);}
            else SendMessagesHelper.getInstance(account).vyrnResolveCancellation(local,dialog,committed);shown.complete(null);
        }catch(Throwable unavailable){shown.completeExceptionally(unavailable);}});shown.get(20,TimeUnit.SECONDS);
        java.util.concurrent.CompletableFuture<Boolean> verified=new java.util.concurrent.CompletableFuture<>();storage.getStorageQueue().postRunnable(()->{try{
            requirePrivateOrigin(user,session,owner,peer,dialog);org.telegram.SQLite.SQLiteCursor cursor=storage.getDatabase().queryFinalized("SELECT data,send_state FROM messages_v2 WHERE uid="+dialog+" AND mid="+row.id);boolean safe=false;
            try{if(cursor.next()){org.telegram.tgnet.NativeByteBuffer data=cursor.byteBufferValue(0);try{TLRPC.Message saved=TLRPC.Message.TLdeserialize(data,data.readInt32(true),true);safe=row.message.equals(saved.message)&&saved.peer_id!=null&&saved.peer_id.user_id==dialog&&saved.from_id!=null&&saved.from_id.user_id==owner&&cursor.intValue(1)==row.send_state;}finally{data.reuse();}}}finally{cursor.dispose();}
            if(safe&&committed!=null){cursor=storage.getDatabase().queryFinalized("SELECT count(*) FROM messages_v2 WHERE uid="+dialog+" AND mid="+local);try{safe=cursor.next()&&cursor.intValue(0)==0;}finally{cursor.dispose();}}verified.complete(safe);
        }catch(Throwable unavailable){verified.completeExceptionally(unavailable);}});if(!verified.get(20,TimeUnit.SECONDS))throw new SecurityException("private_native_write_unconfirmed");
    }
    private static boolean samePrivateRow(MessageObject object,TLRPC.Message row,int id,long dialog,long sender,int date,int edited,String placeholder){
        return object!=null&&!object.deleted&&object.messageOwner==row&&object.getId()==id&&object.getDialogId()==dialog&&row.peer_id!=null&&row.peer_id.user_id==dialog&&row.from_id!=null&&row.from_id.user_id==sender&&row.date==date&&row.edit_date==edited&&java.util.Objects.equals(row.message,placeholder);
    }
    /** Native messageOwner remains a placeholder. Only the exact object receives volatile layout text. */
    public static void hydratePrivateText(MessageObject object){
        if(object!=null&&object.messageOwner!=null&&object.getId()<0){hydratePrivatePending(object);return;}
        if(object==null||object.messageOwner==null||object.getId()<=0||object.getDialogId()<=0||object.messageOwner.peer_id==null||object.messageOwner.peer_id.user_id==0)return;
        final int account=object.currentAccount,id=object.getId();final long dialog=object.getDialogId(),owner=UserConfig.getInstance(account).getClientUserId();
        if(owner==0||dialog==owner)return;VyrnBridge bridge=forAccount(account);final String session=bridge.repository.vault.cachedSession();if(session==null)return;
        final TLRPC.Message row=object.messageOwner;if(row.from_id==null)return;final long senderId=row.from_id.user_id;final String placeholder=row.message;final int date=row.date,edited=row.edit_date;
        final java.lang.ref.WeakReference<MessageObject> reference=new java.lang.ref.WeakReference<>(object);
        execute(()->{try{
            JSONObject context=bridge.ids.privateContext(id);if(context==null||context.getInt("date")!=date||context.getInt("edited")!=edited)return;
            String peer=bridge.ids.remote("user",dialog),sender=context.getString("sender");
            if(senderId!=bridge.ids.local("user",sender))return;
            JSONObject display;final String authenticatedUser;
            try(SignalDeviceKeys keys=SignalDeviceKeys.openLocal(ApplicationLoader.applicationContext,bridge.repository)){
                if(bridge.ids.local("user",keys.user)!=owner||!session.equals(bridge.repository.vault.cachedSession()))return;
                authenticatedUser=keys.user;
                SignalMessages messages=new SignalMessages(keys);
                display=messages.display(peer,context.getString("conversation"),context.getString("message"),sender,context.getInt("revision"),id);
                if(display==null){JSONObject packet=(JSONObject)bridge.repository.requestForSession(keys.user,session,"GET","/v1/messages/"+context.getString("message"),null);messages.receive(peer,context.getString("conversation"),packet);display=messages.display(peer,context.getString("conversation"),context.getString("message"),sender,context.getInt("revision"),id);}
            }
            if(display==null)return;final String text=display.getString("text");final long expiry=display.isNull("expires_at")?Long.MAX_VALUE:java.time.Instant.parse(display.getString("expires_at")).toEpochMilli();
            AndroidUtilities.runOnUIThread(()->{try{
                MessageObject target=reference.get();if(!samePrivateRow(target,row,id,dialog,senderId,date,edited,placeholder)||UserConfig.getInstance(account).getClientUserId()!=owner||!bridge.repository.vault.isUser(authenticatedUser)||!session.equals(bridge.repository.vault.cachedSession())||expiry<=System.currentTimeMillis()||!context.toString().equals(String.valueOf(bridge.ids.privateContext(id))))return;
                Map<Long,java.util.Set<Integer>> deleted=MessagesStorage.getInstance(account).getVyrnDeletedMessages();if(deleted==null||deleted.containsKey(dialog)&&deleted.get(dialog).contains(id))return;
                target.applyNewText(text);NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.updateInterfaces,PRIVATE_TEXT_UPDATE);
                AndroidUtilities.runOnUIThread(new Runnable(){public void run(){MessageObject current=reference.get();if(current==null)return;if(current.messageOwner!=row){clearReplacedPrivateText(current,row,text);return;}
                    boolean live=false;try{Map<Long,java.util.Set<Integer>> fences=MessagesStorage.getInstance(account).getVyrnDeletedMessages();live=fences!=null&&(!fences.containsKey(dialog)||!fences.get(dialog).contains(id))&&context.toString().equals(String.valueOf(bridge.ids.privateContext(id)));}catch(Exception unavailable){/* Clear volatile text if provenance cannot be checked. */}
                    if(!live||!samePrivateRow(current,row,id,dialog,senderId,date,edited,placeholder)||UserConfig.getInstance(account).getClientUserId()!=owner||!bridge.repository.vault.isUser(authenticatedUser)||!session.equals(bridge.repository.vault.cachedSession())||expiry<=System.currentTimeMillis()){
                        current.applyNewText(placeholder);NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.updateInterfaces,PRIVATE_TEXT_UPDATE);return;
                    }AndroidUtilities.runOnUIThread(this,1000);
                }},1000);
            }catch(Exception unavailable){/* Preserve the placeholder on changed or unavailable provenance. */}});
        }catch(Exception unavailable){/* No plaintext fallback or identity regeneration. */}});
    }
    private static boolean samePrivatePending(MessageObject object,TLRPC.Message row,int id,long dialog,long owner,int date,long random){
        return samePrivateRow(object,row,id,dialog,owner,date,0,PRIVATE_PENDING_PLACEHOLDER)&&row.random_id==random&&row.out&&!object.scheduled&&object.type==MessageObject.TYPE_TEXT&&(row.media==null||row.media instanceof TLRPC.TL_messageMediaEmpty)&&(row.send_state==MessageObject.MESSAGE_SEND_STATE_SENDING||row.send_state==MessageObject.MESSAGE_SEND_STATE_SEND_ERROR);
    }
    /** UI thread only: do not carry old private layout text into a replacement native owner. */
    private static void clearReplacedPrivateText(MessageObject object,TLRPC.Message previous,String text){
        if(object!=null&&object.messageOwner!=previous&&text!=null&&object.messageText!=null&&text.contentEquals(object.messageText)){
            if(object.messageOwner==null){object.messageText="";object.messageTextShort=null;object.messageTextForReply=null;object.textLayoutBlocks=null;}
            else{String body=object.messageOwner.message;object.applyNewText(body==null||body.isEmpty()?"Encrypted message":body);}
            NotificationCenter.getInstance(object.currentAccount).postNotificationName(NotificationCenter.updateInterfaces,PRIVATE_TEXT_UPDATE);
        }
    }
    private static void hydratePrivatePending(MessageObject object){
        final int account=object.currentAccount,id=object.getId();final long dialog=object.getDialogId(),owner=UserConfig.getInstance(account).getClientUserId();
        final TLRPC.Message row=object.messageOwner;final int date=row.date;final long random=row.random_id;
        if(owner==0||dialog<=0||dialog==owner||random==0||!samePrivatePending(object,row,id,dialog,owner,date,random))return;
        VyrnBridge bridge=forAccount(account);final String session=bridge.repository.vault.cachedSession();if(session==null)return;
        final java.lang.ref.WeakReference<MessageObject> reference=new java.lang.ref.WeakReference<>(object);
        // ponytail: one existing-style liveness timer per pending object; batch only if profiling shows pressure.
        execute(new Runnable(){String user,peer;volatile String displayed;public void run(){
            MessageObject target=reference.get();if(target==null)return;if(target.messageOwner!=row){AndroidUtilities.runOnUIThread(()->clearReplacedPrivateText(reference.get(),row,displayed));return;}
            String text=null;int pendingState=-1;try{
                try(SignalDeviceKeys keys=SignalDeviceKeys.openLocal(ApplicationLoader.applicationContext,bridge.repository)){
                    if(user==null){user=keys.user;peer=bridge.ids.remote("user",dialog);}
                    if(!user.equals(keys.user)||!session.equals(bridge.repository.vault.cachedSession())||!user.equals(bridge.ids.remote("user",owner))||!peer.equals(bridge.ids.remote("user",dialog)))throw new SecurityException("signal_native_identity_changed");
                    SignalMessages messages=new SignalMessages(keys);text=messages.nativePendingText(peer,account,id,dialog,random,date);
                    if(text!=null){String client=UUID.nameUUIDFromBytes((user+":"+random).getBytes(StandardCharsets.UTF_8)).toString();pendingState=messages.nativeBinding(account,client).getBoolean("failed")?MessageObject.MESSAGE_SEND_STATE_SEND_ERROR:MessageObject.MESSAGE_SEND_STATE_SENDING;}
                }
            }catch(Exception unavailable){/* Missing, acknowledged or changed origin clears volatile text. */}
            final String display=text;final int state=pendingState;AndroidUtilities.runOnUIThread(()->{
                MessageObject current=reference.get();if(current==null)return;if(current.messageOwner!=row){clearReplacedPrivateText(current,row,displayed);return;}
                boolean live=display!=null&&samePrivatePending(current,row,id,dialog,owner,date,random)&&UserConfig.getInstance(account).getClientUserId()==owner&&bridge.repository.vault.isUser(user)&&session.equals(bridge.repository.vault.cachedSession());
                try{live &= user!=null&&user.equals(bridge.ids.remote("user",owner))&&peer!=null&&peer.equals(bridge.ids.remote("user",dialog));}catch(Exception unavailable){live=false;}
                if(live&&state>=0)row.send_state=state;current.applyNewText(live?display:PRIVATE_PENDING_PLACEHOLDER);NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.updateInterfaces,PRIVATE_TEXT_UPDATE);
                if(live)displayed=display;
                if(live)AndroidUtilities.runOnUIThread(()->execute(this),1000);
            });
        }});
    }
    boolean isLocalUser(String user,long expected){return expected!=0&&user!=null&&!user.isEmpty()&&ids.local("user",user)==expected;}
    /** Replayed live updates must follow a confirmed deletion through the native delete path. */
    public static boolean filterDeletedUpdates(int account,ArrayList<TLRPC.Update> updates){
        ArrayList<Integer> positions=new ArrayList<>();ArrayList<TLRPC.Message> messages=new ArrayList<>();
        for(int i=0;i<updates.size();i++){
            TLRPC.Update update=updates.get(i);TLRPC.Message message=null;
            if(update instanceof TL_update.TL_updateNewMessage)message=((TL_update.TL_updateNewMessage)update).message;
            else if(update instanceof TL_update.TL_updateEditMessage)message=((TL_update.TL_updateEditMessage)update).message;
            if(message!=null&&message.id>0&&deletionDialog(message)!=0){positions.add(i);messages.add(message);}
        }
        if(messages.isEmpty())return true;
        ArrayList<Integer> deleted=deletedMessagePositions(account,messages);if(deleted==null)return false;
        for(int i:deleted){TL_update.TL_updateDeleteMessages deletion=new TL_update.TL_updateDeleteMessages();deletion.messages.add(messages.get(i).id);updates.set(positions.get(i),deletion);}
        return true;
    }
    /** Check again when a queued UI operation actually applies, not only on receipt. */
    public static boolean retainLiveMessages(int account,ArrayList<MessageObject> objects){
        if(objects==null||objects.isEmpty())return true;
        ArrayList<TLRPC.Message> messages=new ArrayList<>();for(MessageObject object:objects)messages.add(object.messageOwner);
        ArrayList<Integer> deleted=deletedMessagePositions(account,messages);if(deleted==null)return false;
        for(int i=deleted.size()-1;i>=0;i--)objects.remove((int)deleted.get(i));return true;
    }
    public static boolean isDeletedDialogTop(int account,TLRPC.Dialog dialog){
        if(dialog==null||dialog.id==0||org.telegram.messenger.DialogObject.isEncryptedDialog(dialog.id)||dialog.top_message<=0)return false;
        Map<Long,java.util.Set<Integer>> snapshot=MessagesStorage.getInstance(account).getVyrnDeletedMessages();
        java.util.Set<Integer> ids=snapshot==null?null:snapshot.get(dialog.id);return ids!=null&&ids.contains(dialog.top_message);
    }
    public static boolean repairDialogTop(int account,TLRPC.Dialog dialog,ArrayList<MessageObject> incoming,ArrayList<MessageObject> previous){
        if(!retainLiveMessages(account,incoming))return false;
        if(dialog.id==0||org.telegram.messenger.DialogObject.isEncryptedDialog(dialog.id))return true;
        Map<Long,java.util.Set<Integer>> snapshot=MessagesStorage.getInstance(account).getVyrnDeletedMessages();if(snapshot==null)return false;
        java.util.Set<Integer> deleted=snapshot.get(dialog.id);if(deleted==null||!deleted.contains(dialog.top_message))return true;
        MessageObject best=null;for(MessageObject object:incoming)if(object.getDialogId()==dialog.id&&(best==null||object.messageOwner.date>best.messageOwner.date||object.messageOwner.date==best.messageOwner.date&&object.getId()>best.getId()))best=object;
        if(previous!=null)for(MessageObject object:previous){if(object==null||object.getDialogId()!=dialog.id)continue;if(deleted.contains(object.getId())){object.deleted=true;continue;}if(best==null||object.messageOwner.date>best.messageOwner.date||object.messageOwner.date==best.messageOwner.date&&object.getId()>best.getId())best=object;}
        if(best!=null){MessageObject chosen=best;if(incoming.stream().noneMatch(object->object.getId()==chosen.getId()))incoming.add(chosen);dialog.top_message=best.getId();dialog.last_message_date=best.messageOwner.date;}
        else{dialog.top_message=0;dialog.last_message_date=0;}
        return true;
    }
    private static long deletionDialog(TLRPC.Message message){
        if(message==null||message.peer_id==null||org.telegram.messenger.DialogObject.isEncryptedDialog(MessageObject.getDialogId(message)))return 0;
        if(message.peer_id.chat_id>0)return -message.peer_id.chat_id;
        return message.peer_id.user_id>0?message.peer_id.user_id:0;
    }
    private static ArrayList<Integer> deletedMessagePositions(int account,ArrayList<TLRPC.Message> messages){
        boolean eligible=false;for(TLRPC.Message message:messages)if(message!=null&&message.id>0&&deletionDialog(message)!=0){eligible=true;break;}
        if(!eligible)return new ArrayList<>();
        MessagesStorage storage=MessagesStorage.getInstance(account);Map<Long,java.util.Set<Integer>> snapshot=storage.getVyrnDeletedMessages();
        if(snapshot==null){
            // Only background callers can wait for native database initialization.
            if(Thread.currentThread()==ApplicationLoader.applicationHandler.getLooper().getThread()||Thread.currentThread()==storage.getStorageQueue())return null;
            CountDownLatch ready=new CountDownLatch(1);storage.getStorageQueue().postRunnable(ready::countDown);
            try{if(!ready.await(20,TimeUnit.SECONDS))return null;}catch(InterruptedException interrupted){Thread.currentThread().interrupt();return null;}
            snapshot=storage.getVyrnDeletedMessages();if(snapshot==null)return null;
        }
        ArrayList<Integer> deleted=new ArrayList<>();
        for(int i=0;i<messages.size();i++){
            TLRPC.Message message=messages.get(i);if(message==null||message.id<=0)continue;long dialog=deletionDialog(message);if(dialog==0)continue;
            java.util.Set<Integer> ids=snapshot.get(dialog);if(ids!=null&&ids.contains(message.id))deleted.add(i);
        }
        return deleted;
    }
    /** Install and normalize app-private guards before any cached rows are exposed. */
    public static void initializeDeletedCache(org.telegram.SQLite.SQLiteDatabase db) throws org.telegram.SQLite.SQLiteException {
        db.executeFast("CREATE TABLE IF NOT EXISTS vyrn_deleted_messages(uid INTEGER NOT NULL, mid INTEGER NOT NULL, PRIMARY KEY(uid,mid))").stepThis().dispose();
        db.executeFast("CREATE TRIGGER IF NOT EXISTS vyrn_delete_cached AFTER INSERT ON vyrn_deleted_messages BEGIN DELETE FROM messages_v2 WHERE uid=NEW.uid AND mid=NEW.mid; END").stepThis().dispose();
        for(String operation:new String[]{"INSERT","UPDATE"})db.executeFast("CREATE TRIGGER IF NOT EXISTS vyrn_block_"+operation+" BEFORE "+operation+" ON messages_v2 WHEN EXISTS(SELECT 1 FROM vyrn_deleted_messages WHERE uid=NEW.uid AND mid=NEW.mid) BEGIN SELECT RAISE(IGNORE); END").stepThis().dispose();
        String live=" FROM messages_v2 m WHERE m.uid=dialogs.did AND NOT EXISTS(SELECT 1 FROM vyrn_deleted_messages f WHERE f.uid=m.uid AND f.mid=m.mid) ORDER BY m.date DESC,m.mid DESC LIMIT 1";
        String repair="UPDATE dialogs SET last_mid=COALESCE((SELECT m.mid"+live+"),0),date=COALESCE((SELECT m.date"+live+"),0),last_mid_group=0 WHERE ";
        for(String operation:new String[]{"INSERT","UPDATE"})db.executeFast("CREATE TRIGGER IF NOT EXISTS vyrn_dialog_"+operation+" AFTER "+operation+" ON dialogs WHEN EXISTS(SELECT 1 FROM vyrn_deleted_messages WHERE uid=NEW.did AND mid=NEW.last_mid) BEGIN "+repair+"did=NEW.did; END").stepThis().dispose();
        db.executeFast("CREATE TRIGGER IF NOT EXISTS vyrn_delete_dialog AFTER INSERT ON vyrn_deleted_messages BEGIN "+repair+"did=NEW.uid AND last_mid=NEW.mid; END").stepThis().dispose();
        db.executeFast("DELETE FROM messages_v2 WHERE EXISTS(SELECT 1 FROM vyrn_deleted_messages f WHERE f.uid=messages_v2.uid AND f.mid=messages_v2.mid)").stepThis().dispose();
        db.executeFast(repair+"EXISTS(SELECT 1 FROM vyrn_deleted_messages f WHERE f.uid=dialogs.did AND f.mid=dialogs.last_mid)").stepThis().dispose();
    }
    /** One SQLite statement records the whole confirmed batch and removes its cached rows.
     * Persistent triggers also reject history/edit responses fetched before deletion. */
    private void fenceDeleted(String user,long dialog,List<Integer> selected) throws Exception {
        MessagesStorage storage=MessagesStorage.getInstance(account);CountDownLatch done=new CountDownLatch(1);Exception[] failure={null};
        storage.getStorageQueue().postRunnable(()->{try{
            if(!repository.vault.isUser(user)||UserConfig.getInstance(account).getClientUserId()!=ids.local("user",user))throw new SecurityException("account_changed");
            org.telegram.SQLite.SQLiteDatabase db=storage.getDatabase();
            StringBuilder sql=new StringBuilder("INSERT OR REPLACE INTO vyrn_deleted_messages(uid,mid) VALUES ");
            for(int i=0;i<selected.size();i++){int id=selected.get(i);if(id<=0)throw new IllegalArgumentException("invalid_deleted_id");if(i>0)sql.append(',');sql.append('(').append(dialog).append(',').append(id).append(')');}
            if(selected.isEmpty())throw new IllegalArgumentException("empty_deleted_batch");
            db.executeFast(sql.toString()).stepThis().dispose();
            storage.vyrnReloadDeletedMessages();
        }catch(Exception error){failure[0]=error;}finally{done.countDown();}});
        if(!done.await(20,TimeUnit.SECONDS))throw new IllegalStateException("deletion_cache_timeout");
        if(failure[0]!=null)throw failure[0];
    }
    /** Preserve selected native rows until the entire server deletion has been authorized and committed. */
    public static java.util.concurrent.CompletableFuture<Boolean> deleteMessages(int account,long dialog,ArrayList<Integer> messages,boolean forAll,int mode,int topic,boolean unsupported){
        java.util.concurrent.CompletableFuture<Boolean> result=new java.util.concurrent.CompletableFuture<>();
        ArrayList<Integer> selected=messages==null?new ArrayList<>():new ArrayList<>(new java.util.LinkedHashSet<>(messages));long self=UserConfig.getInstance(account).getClientUserId();
        execute(()->{String user=null;Throwable failure=null;try{
            if(!forAll||mode!=0||topic!=0||unsupported||dialog>=0||self==0||selected.isEmpty()||selected.size()>100||selected.stream().anyMatch(id->id==null||id<=0))throw new UnsupportedOperationException();
            VyrnBridge bridge=forAccount(account);user=bridge.ids.remote("user",self);ArrayList<String> ids=new ArrayList<>();for(int id:selected)ids.add(bridge.ids.remote("message",id));
            synchronized(bridge.repository.vault.guard()){
                if(!bridge.repository.vault.isUser(user)||UserConfig.getInstance(account).getClientUserId()!=self)throw new SecurityException("account_changed");
                bridge.repository.deleteGroupMessages(user,bridge.ids.remote("conversation",-dialog),ids);
            }
            bridge.fenceDeleted(user,dialog,selected);
        }catch(Throwable error){failure=error;}
        String origin=user;Throwable error=failure;AndroidUtilities.runOnUIThread(()->{
            try{if(error==null){VyrnBridge bridge=forAccount(account);if(!bridge.repository.vault.isUser(origin)||UserConfig.getInstance(account).getClientUserId()!=self){result.complete(false);return;}
                MessagesController.getInstance(account).deleteMessages(selected,null,null,dialog,0,true,0,true);result.complete(true);
            }else{boolean rtl=org.telegram.messenger.LocaleController.isRTL;String text;
                if(error instanceof UnsupportedOperationException)text=rtl?"این نوع حذف هنوز در دسترس نیست.":"This deletion option is not available yet.";
                else if(error instanceof RestRepository.ApiException&&((RestRepository.ApiException)error).status==403)text=rtl?"مجوز حذف همهٔ پیام‌های انتخاب‌شده را ندارید. هیچ پیامی حذف نشد.":"You cannot delete all selected messages. No messages were deleted.";
                else text=rtl?"حذف تأیید نشد. اتصال را بررسی کنید و دوباره تلاش کنید.":"Deletion was not confirmed. Check the connection and retry.";
                android.widget.Toast.makeText(ApplicationLoader.applicationContext,text,android.widget.Toast.LENGTH_LONG).show();result.complete(false);
            }}catch(Throwable unavailable){result.completeExceptionally(unavailable);}
        });});return result;
    }
    public static boolean dispatch(int account,TLObject request,RequestDelegate callback,RequestDelegateTimestamp timestamp){
        return dispatch(account,request,callback,timestamp,0);
    }
    public static boolean dispatch(int account,TLObject request,RequestDelegate callback,RequestDelegateTimestamp timestamp,int token){
        PendingRequest pending=new PendingRequest(account,token,request instanceof TLRPC.TL_messages_sendMessage,callback,timestamp);
        if(token!=0 && REQUESTS.putIfAbsent(requestKey(account,token),pending)!=null)throw new IllegalStateException("duplicate_request_token");
        if(request instanceof TLRPC.TL_photos_uploadProfilePhoto || request instanceof TLRPC.TL_photos_updateProfilePhoto || request instanceof TLRPC.TL_photos_getUserPhotos || request instanceof TLRPC.TL_upload_getFile){
            pending.sessionOrigin=UserConfig.getInstance(account).getClientUserId();
            final boolean upload=request instanceof TLRPC.TL_photos_uploadProfilePhoto,change=request instanceof TLRPC.TL_photos_updateProfilePhoto,list=request instanceof TLRPC.TL_photos_getUserPhotos;
            TLRPC.User current=UserConfig.getInstance(account).getCurrentUser();final long observedPhoto=change&&current!=null&&current.photo!=null?current.photo.photo_id:0;
            TLRPC.TL_photos_uploadProfilePhoto photo=upload?(TLRPC.TL_photos_uploadProfilePhoto)request:null;
            TLRPC.TL_photos_updateProfilePhoto update=change?(TLRPC.TL_photos_updateProfilePhoto)request:null;
            TLRPC.TL_photos_getUserPhotos photos=list?(TLRPC.TL_photos_getUserPhotos)request:null;
            TLRPC.TL_upload_getFile file=!upload&&!change&&!list?(TLRPC.TL_upload_getFile)request:null;
            final long uploaded=photo!=null&&photo.file!=null?photo.file.id:0;
            final boolean unsupported=photo!=null&&(photo.flags!=1||photo.fallback||photo.bot!=null||photo.video!=null||photo.video_emoji_markup!=null)||update!=null&&(update.flags!=0||update.fallback||update.bot!=null||!(update.id instanceof TLRPC.TL_inputPhotoEmpty));
            final boolean self=photos!=null&&photos.user_id instanceof TLRPC.TL_inputUserSelf;
            final long target=photos!=null&&photos.user_id instanceof TLRPC.TL_inputUser?photos.user_id.user_id:0;
            final int offset=photos!=null?photos.offset:0,limit=photos!=null?photos.limit:file!=null?file.limit:0;
            final long max=photos!=null?photos.max_id:0,start=file!=null?file.offset:0;
            final long image=file!=null&&file.location instanceof TLRPC.TL_inputPeerPhotoFileLocation?((TLRPC.TL_inputPeerPhotoFileLocation)file.location).photo_id:file!=null&&file.location instanceof TLRPC.TL_inputPhotoFileLocation?file.location.id:0;
            execute(()->{try{
                VyrnBridge bridge=forAccount(account);pending.bridge=bridge;
                synchronized(bridge.repository.vault.guard()){
                    String owner=bridge.repository.vault.load().getString("user_id");pending.user=owner;
                    if(pending.sessionOrigin==0||bridge.ids.local("user",owner)!=pending.sessionOrigin||UserConfig.getInstance(account).getClientUserId()!=pending.sessionOrigin)throw new SecurityException("account_changed");
                    if(unsupported)throw new UnsupportedOperationException("avatar_variant_unavailable");
                    if(file!=null){
                        if(image<=0)throw new UnsupportedOperationException("file_location_unavailable");
                        byte[] bytes=bridge.repository.avatarBytesForUser(owner,avatarIds().remote("avatar",image),start,limit);TLRPC.TL_upload_file result=new TLRPC.TL_upload_file();result.type=new TLRPC.TL_storage_fileUnknown();result.bytes=new org.telegram.tgnet.NativeByteBuffer(Math.max(1,bytes.length));result.bytes.writeBytes(bytes);result.bytes.limit(bytes.length);result.bytes.position(0);pending.complete(result,null);
                    }else if(list){
                        if(!self&&target<=0||offset<0||limit<1||limit>100||max<0)throw new IllegalArgumentException("invalid_avatar_page");
                        String peer=self?owner:bridge.ids.remote("user",target);Models.User user=bridge.repository.profileForUser(owner,peer).user;TLRPC.TL_photos_photos result=new TLRPC.TL_photos_photos();result.users.add(bridge.mapUser(user));
                        if(user.avatarId!=null&&offset==0){Models.Avatar value=bridge.repository.avatarForUser(owner,peer);TLRPC.Photo mapped=avatarPhoto(value);if(max==0||mapped.id<max)result.photos.add(mapped);}pending.complete(result,null);
                    }else{
                        if(upload&&uploaded<=0)throw new IllegalArgumentException("missing_avatar_file");
                        Models.User user;
                        if(upload)user=bridge.repository.updateProfileForUser(owner,new JSONObject().put("avatar_id",avatarIds().remote("avatar",uploaded)));
                        else{if(observedPhoto>0)bridge.repository.deleteAvatarForUser(owner,avatarIds().remote("avatar",observedPhoto));user=bridge.repository.profileForUser(owner,owner).user;if(user.avatarId!=null)throw new IllegalStateException("avatar_changed");}
                        TLRPC.TL_photos_photo result=new TLRPC.TL_photos_photo();result.users.add(bridge.mapUser(user));result.photo=user.avatarId==null?new TLRPC.TL_photoEmpty():avatarPhoto(bridge.repository.avatarForUser(owner,owner));pending.complete(result,null);
                    }
                }
            }catch(Exception failure){pending.complete(null,failure);}});return true;
        }
        if(request instanceof TL_account.getAuthorizations || request instanceof TL_account.resetAuthorization || request instanceof TLRPC.TL_auth_resetAuthorizations || request instanceof TL_account.updateProfile || request instanceof TLRPC.TL_users_getFullUser || request instanceof TLRPC.TL_contacts_block || request instanceof TLRPC.TL_contacts_unblock || request instanceof TLRPC.TL_contacts_getBlocked){
            pending.sessionOrigin=UserConfig.getInstance(account).getClientUserId();
            final long hash=request instanceof TL_account.resetAuthorization?((TL_account.resetAuthorization)request).hash:0;
            final boolean revoke=request instanceof TL_account.resetAuthorization;
            final boolean revokeOthers=request instanceof TLRPC.TL_auth_resetAuthorizations;
            final boolean profile=request instanceof TL_account.updateProfile;
            final boolean fullProfile=request instanceof TLRPC.TL_users_getFullUser;
            final boolean block=request instanceof TLRPC.TL_contacts_block,unblock=request instanceof TLRPC.TL_contacts_unblock,blocks=request instanceof TLRPC.TL_contacts_getBlocked;
            TLRPC.InputPeer blockPeer=block?((TLRPC.TL_contacts_block)request).id:unblock?((TLRPC.TL_contacts_unblock)request).id:null;
            final long blockTarget=blockPeer instanceof TLRPC.TL_inputPeerUser?blockPeer.user_id:0;
            final int blockFlags=block?((TLRPC.TL_contacts_block)request).flags:unblock?((TLRPC.TL_contacts_unblock)request).flags:blocks?((TLRPC.TL_contacts_getBlocked)request).flags:0;
            final boolean stories=block?((TLRPC.TL_contacts_block)request).my_stories_from:unblock?((TLRPC.TL_contacts_unblock)request).my_stories_from:blocks&&((TLRPC.TL_contacts_getBlocked)request).my_stories_from;
            final int blockOffset=blocks?((TLRPC.TL_contacts_getBlocked)request).offset:0,blockLimit=blocks?((TLRPC.TL_contacts_getBlocked)request).limit:0;
            TLRPC.InputUser input=fullProfile?((TLRPC.TL_users_getFullUser)request).id:null;
            final boolean selfProfile=input instanceof TLRPC.TL_inputUserSelf;
            final long profileTarget=input instanceof TLRPC.TL_inputUser?input.user_id:0;
            final int profileFlags=profile?((TL_account.updateProfile)request).flags:0;
            final String first=profile?((TL_account.updateProfile)request).first_name:null,last=profile?((TL_account.updateProfile)request).last_name:null,about=profile?((TL_account.updateProfile)request).about:null;
            execute(()->{try{
                VyrnBridge bridge=forAccount(account);pending.bridge=bridge;
                synchronized(bridge.repository.vault.guard()){
                    String user=bridge.repository.vault.load().getString("user_id");pending.user=user;
                    if(pending.sessionOrigin==0 || UserConfig.getInstance(account).getClientUserId()!=pending.sessionOrigin || bridge.ids.local("user",user)!=pending.sessionOrigin)throw new SecurityException("account_changed");
                    if(block||unblock||blocks){
                        if(stories||blockFlags!=0)throw new UnsupportedOperationException("story_blocking_unavailable");
                        if(blocks){
                            if(blockOffset<0||blockLimit<1||blockLimit>100)throw new IllegalArgumentException("invalid_block_page");
                            Models.BlockedPage page=bridge.repository.blockedForUser(user,blockOffset,blockLimit);TLRPC.TL_contacts_blockedSlice result=new TLRPC.TL_contacts_blockedSlice();result.count=page.count;
                            for(Models.BlockedUser item:page.users){TLRPC.User mapped=bridge.mapUser(item.user);result.users.add(mapped);TLRPC.TL_peerBlocked entry=new TLRPC.TL_peerBlocked();TLRPC.TL_peerUser peer=new TLRPC.TL_peerUser();peer.user_id=mapped.id;entry.peer_id=peer;entry.date=item.date;result.blocked.add(entry);}pending.complete(result,null);
                        }else{
                            if(blockTarget<=0||blockTarget==pending.sessionOrigin)throw new UnsupportedOperationException("block_target_unavailable");
                            bridge.repository.setBlockedForUser(user,bridge.ids.remote("user",blockTarget),block);pending.complete(new TLRPC.TL_boolTrue(),null);
                        }
                    }
                    else if(fullProfile){
                        if(!selfProfile && profileTarget<=0)throw new UnsupportedOperationException("profile_target_unavailable");
                        Models.Profile value=bridge.repository.profileForUser(user,selfProfile?user:bridge.ids.remote("user",profileTarget));
                        TLRPC.User mapped=bridge.mapUser(value.user);TLRPC.TL_userFull full=new TLRPC.TL_userFull();full.id=mapped.id;full.user=mapped;full.about=value.user.about;full.blocked=value.blocked;full.common_chats_count=value.commonGroups;
                        full.voice_messages_forbidden=true;full.flags=2|1048576|(full.blocked?1:0);full.settings=new TLRPC.TL_peerSettings();full.notify_settings=new TLRPC.TL_peerNotifySettings();
                        if(value.user.avatarId!=null){try{full.profile_photo=avatarPhoto(bridge.repository.avatarForUser(user,value.user.id));full.flags|=4;}catch(RestRepository.ApiException unavailable){if(unavailable.status!=404)throw unavailable;mapped.photo=new TLRPC.TL_userProfilePhotoEmpty();}}
                        TLRPC.TL_users_userFull result=new TLRPC.TL_users_userFull();result.full_user=full;result.users.add(mapped);pending.complete(result,null);
                    }
                    else if(profile){
                        if(profileFlags==0 || (profileFlags&~7)!=0 || (profileFlags&1)!=0 && first==null || (profileFlags&2)!=0 && last==null || (profileFlags&4)!=0 && about==null)throw new IllegalArgumentException("invalid_profile");
                        JSONObject changes=new JSONObject();if((profileFlags&1)!=0)changes.put("first_name",first);if((profileFlags&2)!=0)changes.put("last_name",last);if((profileFlags&4)!=0)changes.put("about",about);
                        pending.complete(bridge.mapUser(bridge.repository.updateProfileForUser(user,changes)),null);
                    }
                    else if(revokeOthers){bridge.repository.requestForUser(user,"DELETE","/v1/sessions",null,true);pending.complete(new TLRPC.TL_boolTrue(),null);}
                    else pending.complete(bridge.sessions(user,revoke,hash),null);
                }
            }catch(Exception failure){pending.complete(null,failure);}});return true;
        }
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
    private TLObject sessions(String user,boolean revoke,long hash) throws Exception {
        String kind="session:"+user;
        if(revoke){
            String id=ids.remote(kind,hash);
            if(id.equals(repository.vault.load().getString("session_id")))throw new UnsupportedOperationException("current_session_requires_logout");
            repository.requestForUser(user,"DELETE","/v1/sessions/"+id,null,true);
            return new TLRPC.TL_boolTrue();
        }
        org.json.JSONArray rows=(org.json.JSONArray)repository.requestForUser(user,"GET","/v1/sessions",null,true);
        TL_account.authorizations result=new TL_account.authorizations();result.authorization_ttl_days=30;
        for(int i=0;i<rows.length();i++){
            JSONObject row=rows.getJSONObject(i);TLRPC.TL_authorization value=new TLRPC.TL_authorization();
            value.current=row.getBoolean("current");value.encrypted_requests_disabled=true;value.call_requests_disabled=true;value.flags=(value.current?1:0)|8|16;
            value.hash=ids.local(kind,row.getString("id"));value.device_model=row.getString("device_name");
            value.platform="";value.system_version="";value.app_name="VYRN";value.app_version="";value.ip="";value.country="";value.region="";
            value.date_created=Math.toIntExact(java.time.Instant.parse(row.getString("created_at")).getEpochSecond());
            value.date_active=Math.toIntExact(java.time.Instant.parse(row.getString("last_seen")).getEpochSecond());result.authorizations.add(value);
        }
        return result;
    }
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
    private static IdMap avatarMap;
    private static synchronized IdMap avatarIds(){if(avatarMap==null)avatarMap=new IdMap(ApplicationLoader.applicationContext,-1);return avatarMap;}
    private static TLRPC.TL_fileLocationToBeDeprecated avatarLocation(long id,int local){TLRPC.TL_fileLocationToBeDeprecated value=new TLRPC.TL_fileLocationToBeDeprecated();value.volume_id=-id;value.local_id=local;value.dc_id=1;return value;}
    private static TLRPC.Photo avatarPhoto(Models.Avatar value){
        TLRPC.TL_photo photo=new TLRPC.TL_photo();photo.id=avatarIds().local("avatar",value.id);photo.date=value.date;photo.dc_id=1;photo.file_reference=new byte[0];
        for(String type:new String[]{"a","c"}){TLRPC.TL_photoSize size=new TLRPC.TL_photoSize();size.type=type;size.w=value.width;size.h=value.height;size.size=value.size;size.location=avatarLocation(photo.id,type.charAt(0));photo.sizes.add(size);}return photo;
    }
    public static java.util.concurrent.atomic.AtomicBoolean uploadAvatar(int account,String path,long origin){
        java.util.concurrent.atomic.AtomicBoolean cancelled=new java.util.concurrent.atomic.AtomicBoolean();execute(()->{TLRPC.InputFile uploaded=null;Exception failure=null;VyrnBridge bridge=forAccount(account);String owner=null;try{
            owner=bridge.ids.remote("user",origin);java.io.File source=new java.io.File(path);long size=source.length();if(size<1||size>10*1024*1024)throw new IllegalArgumentException("avatar_size");byte[] bytes=java.nio.file.Files.readAllBytes(source.toPath());
            synchronized(bridge.repository.vault.guard()){if(cancelled.get())return;if(UserConfig.getInstance(account).getClientUserId()!=origin)throw new SecurityException("account_changed");String id=bridge.repository.uploadAvatarForUser(owner,bytes);TLRPC.TL_inputFile file=new TLRPC.TL_inputFile();file.id=avatarIds().local("avatar",id);file.name="avatar.jpg";file.parts=1;file.md5_checksum="";uploaded=file;}
        }catch(Exception error){failure=error;}TLRPC.InputFile accepted=uploaded;String user=owner;Exception problem=failure;AndroidUtilities.runOnUIThread(()->{
            if(cancelled.get()||origin==0||UserConfig.getInstance(account).getClientUserId()!=origin||!bridge.repository.vault.isUser(user))return;
            if(problem==null)NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.fileUploaded,path,accepted,null,null,null,0L);
            else NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.fileUploadFailed,path,false);
        });});return cancelled;
    }
    public TLRPC.User mapUser(Models.User value){TLRPC.TL_user u=new TLRPC.TL_user();u.id=ids.local("user",value.id);u.flags=1|2|4|32|64;u.access_hash=u.id;u.first_name=value.firstName;u.last_name=value.lastName;u.phone="";u.self=u.id==UserConfig.getInstance(account).getClientUserId();u.photo=new TLRPC.TL_userProfilePhotoEmpty();if(value.avatarId!=null){TLRPC.TL_userProfilePhoto photo=new TLRPC.TL_userProfilePhoto();photo.photo_id=avatarIds().local("avatar",value.avatarId);photo.dc_id=1;photo.photo_small=avatarLocation(photo.photo_id,'a');photo.photo_big=avatarLocation(photo.photo_id,'c');u.photo=photo;}u.status=new TLRPC.TL_userStatusOffline();return u;}
    private TLRPC.Peer peer(Models.Conversation conversation,List<Models.User> members) throws Exception{
        if(conversation.kind.equals("group")){TLRPC.TL_peerChat peer=new TLRPC.TL_peerChat();peer.chat_id=ids.local("conversation",conversation.id);return peer;}
        String self=repository.vault.load().getString("user_id");for(Models.User user:members){if(!user.id.equals(self)){TLRPC.TL_peerUser peer=new TLRPC.TL_peerUser();peer.user_id=ids.local("user",user.id);privateConversations.put(peer.user_id,conversation.id);return peer;}}throw new IllegalStateException("private_member_missing");
    }
    private TLRPC.Chat chat(Models.Conversation c){
        TLRPC.TL_chat chat=new TLRPC.TL_chat();chat.id=ids.local("conversation",c.id);chat.title=c.title;chat.photo=new TLRPC.TL_chatPhotoEmpty();chat.participants_count=c.members;chat.creator=c.role.equals("owner");chat.version=1;chat.date=(int)(System.currentTimeMillis()/1000);
        // Modern native chats treat absent default rights as no member sending permission.
        chat.default_banned_rights=new TLRPC.TL_chatBannedRights();chat.flags|=1<<18;
        TLRPC.TL_chatBannedRights rights=chat.default_banned_rights;
        rights.send_media=rights.send_photos=rights.send_videos=rights.send_roundvideos=rights.send_audios=rights.send_voices=rights.send_docs=rights.send_polls=rights.send_stickers=rights.send_gifs=rights.send_games=rights.send_inline=true;
        rights.change_info=rights.invite_users=rights.pin_messages=true;
        return chat;
    }
    private String conversation(TLRPC.InputPeer peer) throws Exception{
        if(peer.chat_id!=0)return ids.remote("conversation",peer.chat_id);
        String id=privateConversations.get(peer.user_id);if(id==null){dialogs();id=privateConversations.get(peer.user_id);}if(id==null)throw new UnsupportedOperationException();return id;
    }
    private TLRPC.Message message(Models.Message m,TLRPC.Peer peer){
        if(m.deleted)throw new IllegalStateException("tombstone_is_not_a_live_message");
        ids.observe(m);
        TLRPC.TL_message out=new TLRPC.TL_message();String snapshot=editSnapshot(m);if(snapshot!=null){out.params=new HashMap<>();out.params.put(EDIT_SNAPSHOT,snapshot);}out.id=ids.message(m.id,m.ordinal);out.peer_id=peer;TLRPC.TL_peerUser sender=new TLRPC.TL_peerUser();sender.user_id=ids.local("user",m.sender);out.from_id=sender;out.flags|=256;out.date=m.date;out.message=m.deleted?"":m.encrypted?(org.telegram.messenger.LocaleController.isRTL?"این پیام در این دستگاه قابل نمایش نیست.":"This message cannot be displayed on this device."):m.text;out.out=sender.user_id==UserConfig.getInstance(account).getClientUserId();out.media=new TLRPC.TL_messageMediaEmpty();out.edit_date=m.editedDate;if(m.editedDate>0)out.flags|=TLRPC.MESSAGE_FLAG_EDITED;return out;
    }
    private TLRPC.TL_messages_dialogs dialogs() throws Exception {
        return dialogs(null);
    }
    private TLRPC.TL_messages_dialogs dialogs(java.util.Set<Long> requested) throws Exception {
        // Ready devices are needed before a peer can encrypt a first message; sending stays gated until UI integration.
        preparePrivateKeys();
        TLRPC.TL_messages_dialogs result=new TLRPC.TL_messages_dialogs();
        for(Models.Conversation c:repository.conversations()){
            if(requested!=null && (!c.kind.equals("group") || !requested.contains(-(long)ids.local("conversation",c.id))))continue;
            List<Models.User> members=repository.members(c.id);TLRPC.Peer peer=peer(c,members);for(Models.User u:members)result.users.add(mapUser(u));if(c.kind.equals("group"))result.chats.add(chat(c));
            TLRPC.TL_dialog dialog=new TLRPC.TL_dialog();dialog.peer=peer;dialog.id=peer.chat_id!=0?-peer.chat_id:peer.user_id;dialog.notify_settings=new TLRPC.TL_peerNotifySettings();dialog.ttl_period=c.disappearingSeconds;if(c.disappearingSeconds>0)dialog.flags|=32;
            List<Models.Message> messages=repository.liveMessages(c.id,null,1);if(!messages.isEmpty()){TLRPC.Message message=message(messages.get(0),peer);result.messages.add(message);dialog.top_message=message.id;dialog.last_message_date=message.date;}result.dialogs.add(dialog);
        }return result;
    }
    private Models.Conversation conversationModel(String id) throws Exception{return repository.conversationDetails(id).conversation;}
    private void applyEvent(String eventUser,String kind,JSONObject body) throws Exception {
        if(!repository.vault.isUser(eventUser)||UserConfig.getInstance(account).getClientUserId()!=ids.local("user",eventUser))throw new SecurityException("event_account_changed");
        if(kind.equals("user.updated")){applyProfileEvent(eventUser,body.getString("user_id"));return;}
        if(kind.equals("block.updated")){applyBlockEvent(eventUser,body.getString("user_id"));return;}
        if(kind.equals("conversation.updated")){applyConversationEvent(eventUser,body.getString("id"),true);return;}
        if(kind.equals("member.updated")||kind.equals("member.removed")){applyConversationEvent(eventUser,body.getString("conversation_id"),false);return;}
        if(!kind.startsWith("message."))return;
        String conversation=body.getString("conversation_id"),messageId=body.getString("message_id");Models.Message value=repository.messageForUser(eventUser,messageId);
        Models.Conversation model=conversationModel(conversation);List<Models.User> members=repository.members(conversation);TLRPC.Peer destination=peer(model,members);
        ArrayList<TLRPC.User> users=new ArrayList<>();for(Models.User user:members)users.add(mapUser(user));ArrayList<TLRPC.Chat> chats=new ArrayList<>();if(model.kind.equals("group"))chats.add(chat(model));
        ArrayList<TLRPC.Update> updates=new ArrayList<>();
        if(value.deleted||kind.equals("message.deleted")||kind.equals("message.expired")){
            ids.observe(value);
            fenceDeleted(eventUser,destination.chat_id!=0?-destination.chat_id:destination.user_id,java.util.Collections.singletonList(ids.message(value.id,value.ordinal)));
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
    private void applyProfileEvent(String owner,String target) throws Exception {
        long origin=ids.local("user",owner),peer=ids.local("user",target);
        java.util.concurrent.CompletableFuture<TLRPC.UserFull> loaded=new java.util.concurrent.CompletableFuture<>();
        TLRPC.TL_users_getFullUser request=new TLRPC.TL_users_getFullUser();
        if(peer==origin)request.id=new TLRPC.TL_inputUserSelf();else{TLRPC.TL_inputUser input=new TLRPC.TL_inputUser();input.user_id=peer;input.access_hash=peer;request.id=input;}
        dispatch(account,request,(response,error)->{if(error!=null||!(response instanceof TLRPC.TL_users_userFull))loaded.completeExceptionally(new IllegalStateException("profile_reload_failed"));else loaded.complete(((TLRPC.TL_users_userFull)response).full_user);},null);
        TLRPC.UserFull full=loaded.get(30,TimeUnit.SECONDS);TLRPC.User user=full.user;
        if(full.id!=peer)throw new SecurityException("profile_identity_changed");
        CountDownLatch applied=new CountDownLatch(1);boolean[] accepted={false};
        AndroidUtilities.runOnUIThread(()->{try{if(repository.vault.isUser(owner))accepted[0]=MessagesController.getInstance(account).vyrnApplyProfile(origin,full);}finally{applied.countDown();}});
        if(!applied.await(20,TimeUnit.SECONDS)||!accepted[0])throw new IllegalStateException("profile_update_rejected");
        CountDownLatch stored=new CountDownLatch(1);boolean[] confirmed={false};MessagesStorage storage=MessagesStorage.getInstance(account);
        storage.getStorageQueue().postRunnable(()->{try{
            TLRPC.User saved=storage.getUser(peer);java.util.HashSet<Long> peers=new java.util.HashSet<>();peers.add(peer);
            for(TLRPC.UserFull info:storage.loadUserInfos(peers))if(info.id==peer&&java.util.Objects.equals(info.about,full.about)&&photoId(info.profile_photo)==photoId(full.profile_photo))confirmed[0]=saved!=null&&java.util.Objects.equals(saved.first_name,user.first_name)&&java.util.Objects.equals(saved.last_name,user.last_name)&&userPhotoId(saved)==userPhotoId(user)&&repository.vault.isUser(owner)&&UserConfig.getInstance(account).getClientUserId()==origin;
        }finally{stored.countDown();}});
        if(!stored.await(20,TimeUnit.SECONDS)||!confirmed[0])throw new IllegalStateException("profile_update_not_saved");
    }
    private static long photoId(TLRPC.Photo photo){return photo==null?0:photo.id;}
    private static long userPhotoId(TLRPC.User user){return user.photo==null?0:user.photo.photo_id;}
    private void applyBlockEvent(String owner,String target) throws Exception {
        boolean blocked;
        synchronized(repository.vault.guard()){blocked=repository.blockedForUser(owner,target);}
        long origin=ids.local("user",owner),peer=ids.local("user",target);CountDownLatch applied=new CountDownLatch(1);boolean[] accepted={false},cached={false};
        AndroidUtilities.runOnUIThread(()->{try{if(!repository.vault.isUser(owner)||UserConfig.getInstance(account).getClientUserId()!=origin)return;MessagesController controller=MessagesController.getInstance(account);controller.vyrnApplyPeerBlocked(origin,peer,blocked);cached[0]=controller.getUserFull(peer)!=null;accepted[0]=true;}finally{applied.countDown();}});
        if(!applied.await(20,TimeUnit.SECONDS)||!accepted[0])throw new IllegalStateException("block_update_rejected");
        if(cached[0]){
            CountDownLatch stored=new CountDownLatch(1);boolean[] confirmed={false};MessagesStorage storage=MessagesStorage.getInstance(account);
            storage.getStorageQueue().postRunnable(()->{try{java.util.HashSet<Long> peers=new java.util.HashSet<>();peers.add(peer);for(TLRPC.UserFull full:storage.loadUserInfos(peers))if(full.id==peer && full.blocked==blocked)confirmed[0]=repository.vault.isUser(owner)&&UserConfig.getInstance(account).getClientUserId()==origin;}finally{stored.countDown();}});
            if(!stored.await(20,TimeUnit.SECONDS)||!confirmed[0])throw new IllegalStateException("block_profile_not_saved");
        }
    }
    private void applyConversationEvent(String user,String id,boolean refresh) throws Exception {
        Models.ConversationDetails details;
        synchronized(repository.vault.guard()){if(!repository.vault.isUser(user))throw new SecurityException("event_account_changed");details=repository.conversationDetails(id);}
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
        if(request instanceof TLRPC.TL_messages_getPeerDialogs){
            java.util.Set<Long> requested=new java.util.HashSet<>();
            for(TLRPC.InputDialogPeer input:((TLRPC.TL_messages_getPeerDialogs)request).peers){
                if(!(input instanceof TLRPC.TL_inputDialogPeer))throw new UnsupportedOperationException();
                TLRPC.InputPeer peer=((TLRPC.TL_inputDialogPeer)input).peer;
                if(peer==null || peer.chat_id<=0)throw new UnsupportedOperationException();
                requested.add(-peer.chat_id);
            }
            TLRPC.TL_messages_dialogs snapshot=dialogs(requested);
            if(snapshot.dialogs.size()!=requested.size())throw new IllegalArgumentException("peer_unavailable");
            TLRPC.TL_messages_peerDialogs result=new TLRPC.TL_messages_peerDialogs();result.dialogs=snapshot.dialogs;result.messages=snapshot.messages;result.users=snapshot.users;result.chats=snapshot.chats;
            result.state=new TLRPC.TL_updates_state();result.state.date=(int)(System.currentTimeMillis()/1000);return result;
        }
        if(request instanceof TLRPC.TL_messages_getHistory){
            TLRPC.TL_messages_getHistory in=(TLRPC.TL_messages_getHistory)request;String conversation=conversation(in.peer);String before=in.offset_id>0?ids.remote("message",in.offset_id):null;
            TLRPC.Peer peer;if(in.peer.chat_id!=0){TLRPC.TL_peerChat p=new TLRPC.TL_peerChat();p.chat_id=in.peer.chat_id;peer=p;}else{TLRPC.TL_peerUser p=new TLRPC.TL_peerUser();p.user_id=in.peer.user_id;peer=p;}
            TLRPC.TL_messages_messages result=new TLRPC.TL_messages_messages();for(Models.User user:repository.members(conversation))result.users.add(mapUser(user));for(Models.Message m:repository.liveMessages(conversation,before,in.limit))result.messages.add(message(m,peer));return result;
        }
        if(request instanceof TLRPC.TL_messages_getMessageEditData){
            TLRPC.TL_messages_getMessageEditData in=(TLRPC.TL_messages_getMessageEditData)request;
            if(in.peer==null || in.peer.chat_id<=0 || in.id<=0)throw new UnsupportedOperationException();
            String user=repository.vault.load().getString("user_id"),group=conversation(in.peer);
            Models.Message value=repository.messageForUser(user,ids.remote("message",in.id));
            if(value.deleted || value.encrypted || !user.equals(value.sender) || !group.equals(value.conversation))throw new SecurityException("message_not_editable");
            return new TLRPC.TL_messages_messageEditData();
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
