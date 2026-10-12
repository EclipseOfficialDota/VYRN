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
    static void cancelUpload(Instrumentation instrumentation,Activity activity,JSONObject fixture,AuthUiIntegration.Check check) throws Exception {
        AuthUiIntegration.registration(instrumentation,activity,fixture,check);int account=UserConfig.selectedAccount;VyrnBridge bridge=VyrnBridge.forAccount(account);VyrnBridge.UploadIdentity identity=VyrnBridge.captureUploadIdentity(account);
        File source=new File(instrumentation.getTargetContext().getCacheDir(),"vyrn-native-cancel-upload.bin");byte[] bytes=new byte[16*1024*1024+113];for(int i=0;i<bytes.length;i++)bytes[i]=(byte)i;Files.write(source.toPath(),bytes);String path=source.getAbsolutePath();
        CompletableFuture<Long> cancelled=new CompletableFuture<>();java.util.concurrent.atomic.AtomicLong captured=new java.util.concurrent.atomic.AtomicLong();java.util.concurrent.atomic.AtomicBoolean completed=new java.util.concurrent.atomic.AtomicBoolean();
        org.telegram.messenger.NotificationCenter.NotificationCenterDelegate observer=(event,selected,args)->{
            if(selected!=account||args.length==0||!path.equals(args[0]))return;
            if(event==org.telegram.messenger.NotificationCenter.fileUploaded)completed.set(true);
            else if(event==org.telegram.messenger.NotificationCenter.fileUploadFailed)cancelled.complete(captured.get());
            else if(event==org.telegram.messenger.NotificationCenter.fileUploadProgressChanged&&((Number)args[1]).longValue()>0&&captured.get()==0){
                try{java.lang.reflect.Field mapField=FileLoader.class.getDeclaredField("uploadOperationPaths");mapField.setAccessible(true);Object operation=((java.util.Map<?,?>)mapField.get(FileLoader.getInstance(account))).get(path);if(operation==null)return;java.lang.reflect.Field idField=org.telegram.messenger.FileUploadOperation.class.getDeclaredField("currentFileId");idField.setAccessible(true);long id=idField.getLong(operation);if(id!=0&&captured.compareAndSet(0,id))FileLoader.getInstance(account).cancelFileUpload(path,false);}catch(Exception failure){cancelled.completeExceptionally(new AssertionError(failure));}
            }
        };
        instrumentation.runOnMainSync(()->{org.telegram.messenger.NotificationCenter center=org.telegram.messenger.NotificationCenter.getInstance(account);center.addObserver(observer,org.telegram.messenger.NotificationCenter.fileUploaded);center.addObserver(observer,org.telegram.messenger.NotificationCenter.fileUploadFailed);center.addObserver(observer,org.telegram.messenger.NotificationCenter.fileUploadProgressChanged);});
        long id;try{FileLoader.getInstance(account).uploadFile(path,false,false,ConnectionsManager.FileTypeFile);id=cancelled.get(60,TimeUnit.SECONDS);}finally{instrumentation.runOnMainSync(()->{org.telegram.messenger.NotificationCenter center=org.telegram.messenger.NotificationCenter.getInstance(account);center.removeObserver(observer,org.telegram.messenger.NotificationCenter.fileUploaded);center.removeObserver(observer,org.telegram.messenger.NotificationCenter.fileUploadFailed);center.removeObserver(observer,org.telegram.messenger.NotificationCenter.fileUploadProgressChanged);});}
        check.verify(id!=0&&!completed.get(),"upload_actual_native_cancel_after_progress_before_completion");String upload=VyrnBridge.uploadId(identity,id);long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);boolean fenced=false;
        while(System.nanoTime()<deadline){JSONObject status=(JSONObject)bridge.repository.requestForSession(identity.owner,identity.session,"GET","/v1/media-uploads/"+upload,null);if(status.getString("state").equals("cancelled")&&status.getJSONArray("parts").length()==0){fenced=true;break;}Thread.sleep(100);}
        check.verify(fenced,"upload_actual_native_cancel_hook_fences_and_cleans_server");boolean rejected=false;try{bridge.repository.uploadPartForSession(identity.owner,identity.session,upload,0,new byte[]{1});}catch(RestRepository.ApiException failure){rejected=failure.status==409;}check.verify(rejected,"upload_cancelled_native_id_rejects_late_part");check.verify(source.isFile()&&source.length()==bytes.length,"upload_native_cancel_preserves_original_source");Files.deleteIfExists(source.toPath());
    }
    static void upload(Instrumentation instrumentation,Activity activity,JSONObject fixture,AuthUiIntegration.Check check,boolean big) throws Exception {
        AuthUiIntegration.registration(instrumentation,activity,fixture,check);
        int account=UserConfig.selectedAccount;VyrnBridge bridge=VyrnBridge.forAccount(account);String owner=bridge.repository.me().id;
        VyrnBridge.UploadIdentity identity=VyrnBridge.captureUploadIdentity(account);check.verify(VyrnBridge.currentUploadIdentity(account,identity),"upload_native_origin_captured");
        byte[] bytes=new byte[(big?12:2)*1024*1024+113];for(int i=0;i<bytes.length;i++)bytes[i]=(byte)(i*17);
        File source=new File(instrumentation.getTargetContext().getCacheDir(),big?"vyrn-native-big-upload.bin":"vyrn-native-upload.bin");Files.write(source.toPath(),bytes);String path=source.getAbsolutePath();
        CompletableFuture<TLRPC.InputFile> done=new CompletableFuture<>();java.util.concurrent.atomic.AtomicLong progress=new java.util.concurrent.atomic.AtomicLong();
        org.telegram.messenger.NotificationCenter.NotificationCenterDelegate observer=(event,selected,args)->{
            if(selected!=account||args.length==0||!path.equals(args[0]))return;
            if(event==org.telegram.messenger.NotificationCenter.fileUploaded){if(args[1] instanceof TLRPC.InputFile)done.complete((TLRPC.InputFile)args[1]);else done.completeExceptionally(new AssertionError("upload_not_plain_file"));}
            else if(event==org.telegram.messenger.NotificationCenter.fileUploadFailed)done.completeExceptionally(new AssertionError("native_upload_failed"));
            else if(event==org.telegram.messenger.NotificationCenter.fileUploadProgressChanged)progress.accumulateAndGet(((Number)args[1]).longValue(),Math::max);
        };
        instrumentation.runOnMainSync(()->{org.telegram.messenger.NotificationCenter center=org.telegram.messenger.NotificationCenter.getInstance(account);center.addObserver(observer,org.telegram.messenger.NotificationCenter.fileUploaded);center.addObserver(observer,org.telegram.messenger.NotificationCenter.fileUploadFailed);center.addObserver(observer,org.telegram.messenger.NotificationCenter.fileUploadProgressChanged);});
        TLRPC.InputFile input;
        try{FileLoader.getInstance(account).uploadFile(path,false,false,ConnectionsManager.FileTypeFile);input=done.get(120,TimeUnit.SECONDS);}finally{instrumentation.runOnMainSync(()->{org.telegram.messenger.NotificationCenter center=org.telegram.messenger.NotificationCenter.getInstance(account);center.removeObserver(observer,org.telegram.messenger.NotificationCenter.fileUploaded);center.removeObserver(observer,org.telegram.messenger.NotificationCenter.fileUploadFailed);center.removeObserver(observer,org.telegram.messenger.NotificationCenter.fileUploadProgressChanged);});}
        check.verify(input.id!=0&&input.parts>0&&(big?input instanceof TLRPC.TL_inputFileBig:input instanceof TLRPC.TL_inputFile),big?"upload_original_big_worker_complete":"upload_original_small_worker_complete");
        check.verify(progress.get()==bytes.length,"upload_original_progress_reaches_full_size");
        String id=VyrnBridge.uploadId(identity,input.id);JSONObject status=(JSONObject)bridge.repository.requestForSession(owner,identity.session,"GET","/v1/media-uploads/"+id,null);
        check.verify(status.getString("state").equals("open")&&status.getJSONArray("parts").length()==input.parts,"upload_original_all_native_parts_persisted");
        JSONObject group=(JSONObject)bridge.repository.requestForSession(owner,identity.session,"POST","/v1/conversations",new JSONObject().put("kind","group").put("title","Native uploaded file").put("members",new org.json.JSONArray()));
        JSONObject request=new JSONObject().put("conversation_id",group.getString("id")).put("kind","file").put("filename",input.name).put("part_count",input.parts).put("md5",input.md5_checksum==null?"":input.md5_checksum);
        JSONObject complete=(JSONObject)bridge.repository.requestForSession(owner,identity.session,"POST","/v1/media-uploads/"+id+"/complete",request);
        check.verify(complete.getLong("size")==bytes.length&&complete.getString("sha256").equals(hex(MessageDigest.getInstance("SHA-256").digest(bytes))),"upload_original_worker_final_bytes_and_hash");
        JSONObject repeat=(JSONObject)bridge.repository.requestForSession(owner,identity.session,"POST","/v1/media-uploads/"+id+"/complete",request);check.verify(repeat.getString("id").equals(complete.getString("id")),"upload_completion_replay_same_media_id");
        TLRPC.TL_messages_sendMedia send=VyrnBridge.mediaRequest(account);TLRPC.TL_inputPeerChat destination=new TLRPC.TL_inputPeerChat();
        try(IdMap map=new IdMap(instrumentation.getTargetContext(),account)){destination.chat_id=map.local("conversation",group.getString("id"));}
        send.peer=destination;send.random_id=java.util.concurrent.ThreadLocalRandom.current().nextLong(1,Long.MAX_VALUE);send.message="Native file caption";
        TLRPC.TL_inputMediaUploadedDocument payload=new TLRPC.TL_inputMediaUploadedDocument();payload.file=input;payload.mime_type="application/octet-stream";
        TLRPC.TL_documentAttributeFilename name=new TLRPC.TL_documentAttributeFilename();name.file_name=input.name;payload.attributes.add(name);send.media=payload;
        TLRPC.TL_updateShortSentMessage first=sendFile(account,send),replayed=sendFile(account,send);
        check.verify(first.id==replayed.id&&first.media instanceof TLRPC.TL_messageMediaDocument&&first.media.document.size==bytes.length,"upload_native_send_replay_same_message_and_document");
        java.util.List<xyz.eclipseofficial.vyrn.domain.Models.Message> messages=bridge.repository.liveMessages(group.getString("id"),null,100);
        check.verify(messages.size()==1&&messages.get(0).attachment!=null&&messages.get(0).attachment.id.equals(complete.getString("id")),"upload_native_send_one_server_message_with_original_media");
        String client=UUID.nameUUIDFromBytes((owner+":"+send.random_id).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        try(xyz.eclipseofficial.vyrn.data.OutboxStore outbox=new xyz.eclipseofficial.vyrn.data.OutboxStore(instrumentation.getTargetContext(),account)){
            xyz.eclipseofficial.vyrn.data.OutboxStore.Entry saved=outbox.get(owner,client);
            check.verify(saved.state==xyz.eclipseofficial.vyrn.data.OutboxStore.SENT&&saved.media.equals(complete.getString("id"))&&saved.session.equals(identity.session)&&new JSONObject(saved.upload).getString("id").equals(id),"upload_native_send_durable_origin_and_media_binding");
        }
        VyrnBridge.cancelUpload(account,identity,input.id);long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);boolean cancelled=false;
        while(System.nanoTime()<deadline){JSONObject value=(JSONObject)bridge.repository.requestForSession(owner,identity.session,"GET","/v1/media-uploads/"+id,null);if(value.getString("state").equals("cancelled")&&value.getJSONArray("parts").length()==0){cancelled=true;break;}Thread.sleep(100);}
        check.verify(cancelled,"upload_adapter_cancel_fence_and_part_cleanup");check.verify(bridge.repository.attachmentForUser(owner,complete.getString("id")).size==bytes.length,"upload_cancel_preserves_completed_media");
        check.verify(!VyrnBridge.uploadPreferenceKey(identity,"same-file").equals(VyrnBridge.uploadPreferenceKey(newIdentityForTest(identity),"same-file")),"upload_saved_preferences_separate_session");
        Files.deleteIfExists(source.toPath());
    }
    private static TLRPC.TL_updateShortSentMessage sendFile(int account,TLRPC.TL_messages_sendMedia request) throws Exception {
        CompletableFuture<TLRPC.TL_updateShortSentMessage> result=new CompletableFuture<>();
        VyrnBridge.dispatch(account,request,(response,error)->{if(error!=null||!(response instanceof TLRPC.TL_updateShortSentMessage))result.completeExceptionally(new AssertionError("native_media_send_failed"));else result.complete((TLRPC.TL_updateShortSentMessage)response);},null);
        return result.get(45,TimeUnit.SECONDS);
    }
    private static String hex(byte[] value){StringBuilder result=new StringBuilder();for(byte b:value)result.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return result.toString();}
    private static VyrnBridge.UploadIdentity newIdentityForTest(VyrnBridge.UploadIdentity identity) throws Exception {java.lang.reflect.Constructor<VyrnBridge.UploadIdentity> constructor=VyrnBridge.UploadIdentity.class.getDeclaredConstructor(long.class,String.class,String.class);constructor.setAccessible(true);return constructor.newInstance(identity.origin,identity.owner,UUID.randomUUID().toString());}
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
