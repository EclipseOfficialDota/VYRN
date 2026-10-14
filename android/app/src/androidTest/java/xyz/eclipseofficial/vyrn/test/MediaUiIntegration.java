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

/** Native file selection/send and receive/download scenarios; group setup uses API fixtures. */
final class MediaUiIntegration {
    private static void failVoiceOutput(File recorded) throws Exception {
        int output=-1,matches=0;
        android.system.StructStat source=android.system.Os.stat(recorded.getAbsolutePath());
        for(File descriptor:new File("/proc/self/fd").listFiles()){
            try{android.system.StructStat opened=android.system.Os.stat(descriptor.getAbsolutePath());if(opened.st_dev==source.st_dev&&opened.st_ino==source.st_ino){output=Integer.parseInt(descriptor.getName());matches++;}}catch(android.system.ErrnoException vanished){}
        }
        if(output<0||matches!=1)throw new AssertionError("voice_storage_native_output_missing");
        java.io.FileDescriptor readOnly=android.system.Os.open(recorded.getAbsolutePath(),android.system.OsConstants.O_RDONLY,0);
        try{android.system.Os.dup2(readOnly,output);}finally{android.system.Os.close(readOnly);}
    }
    static void voiceStorage(Instrumentation instrumentation,AuthUiIntegration.Check check) throws Exception {
        org.telegram.messenger.MediaController controller=org.telegram.messenger.MediaController.getInstance();
        java.lang.reflect.Method start=controller.getClass().getDeclaredMethod("startRecord",String.class,int.class),finish=controller.getClass().getDeclaredMethod("vyrnFinishVoiceRecord",int.class,int.class,File.class,File.class);
        start.setAccessible(true);finish.setAccessible(true);
        java.lang.reflect.Field failed=controller.getClass().getDeclaredField("vyrnVoiceWriteFailed");failed.setAccessible(true);
        int account=UserConfig.selectedAccount,guid=17041;
        java.util.concurrent.atomic.AtomicReference<CompletableFuture<Void>> error=new java.util.concurrent.atomic.AtomicReference<>(new CompletableFuture<>());java.util.concurrent.atomic.AtomicBoolean published=new java.util.concurrent.atomic.AtomicBoolean();
        org.telegram.messenger.NotificationCenter.NotificationCenterDelegate observer=(event,selected,args)->{if(event==org.telegram.messenger.NotificationCenter.audioDidSent)published.set(true);else if(args.length>0&&args[0].equals(guid))error.get().complete(null);};
        instrumentation.runOnMainSync(()->{org.telegram.messenger.NotificationCenter center=org.telegram.messenger.NotificationCenter.getInstance(account);center.addObserver(observer,org.telegram.messenger.NotificationCenter.recordStartError);center.addObserver(observer,org.telegram.messenger.NotificationCenter.audioDidSent);});
        try {
            for(boolean earlierFailure:new boolean[]{false,true}){
                error.set(new CompletableFuture<>());
                File previous=new File(instrumentation.getTargetContext().getCacheDir(),"voice-storage-previous.ogg"),recorded=new File(instrumentation.getTargetContext().getCacheDir(),"voice-storage-current.ogg");
                Files.write(previous.toPath(),new byte[]{1});Files.write(recorded.toPath(),new byte[]{2});
                CompletableFuture<Boolean> saved=new CompletableFuture<>();
                ((org.telegram.messenger.DispatchQueue)field(controller,"fileEncodingQueue")).postRunnable(()->{try{
                    if((Integer)start.invoke(controller,recorded.getAbsolutePath(),48000)!=1)throw new AssertionError("voice_storage_native_start_failed");
                    failVoiceOutput(recorded);
                    failed.setBoolean(controller,earlierFailure);((java.nio.ByteBuffer)field(controller,"fileBuffer")).rewind();
                    saved.complete((Boolean)finish.invoke(controller,account,guid,previous,recorded));
                }catch(Throwable failure){try{JSONObject diagnostic=new JSONObject().put("type",failure.getClass().getSimpleName());if(failure instanceof AssertionError&&failure.getMessage()!=null&&failure.getMessage().matches("voice_storage_[a-z_]+"))diagnostic.put("guard",failure.getMessage());if(failure instanceof android.system.ErrnoException)diagnostic.put("errno",((android.system.ErrnoException)failure).errno);Files.write(new File(instrumentation.getTargetContext().getFilesDir(),"vyrn-test-voice-storage.json").toPath(),diagnostic.toString().getBytes(StandardCharsets.UTF_8));}catch(Exception ignored){}saved.completeExceptionally(failure);}});
                check.verify(!saved.get(10,TimeUnit.SECONDS),earlierFailure?"voice_storage_prior_frame_failure_rejected":"voice_storage_actual_write_failure_final_flush_rejected");
                error.get().get(10,TimeUnit.SECONDS);
                check.verify(!previous.exists()&&!recorded.exists(),"voice_storage_partial_files_removed_"+earlierFailure);
                check.verify(!published.get(),"voice_storage_failure_never_publishes_audio_"+earlierFailure);
            }
            failed.setBoolean(controller,false);
            voiceCodec(instrumentation,check);
        }finally{failed.setBoolean(controller,false);instrumentation.runOnMainSync(()->{org.telegram.messenger.NotificationCenter center=org.telegram.messenger.NotificationCenter.getInstance(account);center.removeObserver(observer,org.telegram.messenger.NotificationCenter.recordStartError);center.removeObserver(observer,org.telegram.messenger.NotificationCenter.audioDidSent);});}
    }
    static void voiceCodec(Instrumentation instrumentation,AuthUiIntegration.Check check) throws Exception {
        voiceCodec(instrumentation,check,50);
    }
    private static void voiceCodec(Instrumentation instrumentation,AuthUiIntegration.Check check,int frames) throws Exception {
        org.telegram.messenger.MediaController controller=org.telegram.messenger.MediaController.getInstance();
        java.lang.reflect.Method start=controller.getClass().getDeclaredMethod("startRecord",String.class,int.class),write=controller.getClass().getDeclaredMethod("writeFrame",java.nio.ByteBuffer.class,int.class),stop=controller.getClass().getDeclaredMethod("stopRecord");
        start.setAccessible(true);write.setAccessible(true);stop.setAccessible(true);
        for(int tail:new int[]{0,480,959}){
            File file=new File(instrumentation.getTargetContext().getFilesDir(),"vyrn-native-voice-"+tail+".ogg");
            check.verify((Integer)start.invoke(controller,file.getAbsolutePath(),48000)==1,"voice_original_native_encoder_started_"+tail);
            try{
                java.nio.ByteBuffer pcm=java.nio.ByteBuffer.allocateDirect(1920).order(java.nio.ByteOrder.nativeOrder());
                for(int i=0;i<960;i++)pcm.putShort((short)(Math.sin(i*2*Math.PI*400/48000)*6000));
                boolean encoded=true;for(int frame=0;frame<frames;frame++)if((Integer)write.invoke(controller,pcm,1920)!=1){encoded=false;break;}
                check.verify(encoded,"voice_original_native_frames_encoded_"+tail);
                check.verify((Integer)write.invoke(controller,pcm,tail*2)==1,"voice_original_native_eos_encoded_"+tail);
            }finally{stop.invoke(controller);}
            check.verify(file.isFile()&&file.length()>0&&org.telegram.messenger.MediaController.isOpusFile(file.getAbsolutePath())==1,"voice_original_native_container_opens_"+tail);
            File decoded=new File(instrumentation.getTargetContext().getFilesDir(),"vyrn-native-voice-decoded-"+tail+".ogg");
            check.verify(org.telegram.messenger.MediaController.cropOpusFile(file.getAbsolutePath(),decoded.getAbsolutePath(),0,frames*20L+1000)&&decoded.isFile(),"voice_original_native_full_pcm_decode_and_reencode_"+tail);
        }
    }
    static void uploadCold(Instrumentation instrumentation,Activity activity,JSONObject fixture,AuthUiIntegration.Check check,boolean recover) throws Exception {
        android.content.Context context=instrumentation.getTargetContext();File evidence=new File(context.getFilesDir(),"vyrn-test-cold-upload.json");
        if(!recover)AuthUiIntegration.registration(instrumentation,activity,fixture,check);
        int account=UserConfig.selectedAccount;VyrnBridge bridge=VyrnBridge.forAccount(account);VyrnBridge.UploadIdentity origin=VyrnBridge.captureUploadIdentity(account);
        JSONObject saved=recover?new JSONObject(new String(Files.readAllBytes(evidence.toPath()),StandardCharsets.UTF_8)):null;
        File source=recover?new File(saved.getString("path")):new File(context.getCacheDir(),"vyrn-cold-photo.png");
        if(!recover)Files.write(source.toPath(),testPhoto(1024,768));
        String hash=hex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source.toPath())));
        if(recover)check.verify(origin.owner.equals(saved.getString("owner"))&&origin.session.equals(saved.getString("session"))&&hash.equals(saved.getString("hash")),"cold_upload_original_account_session_and_file_restored");
        CompletableFuture<TLRPC.InputFile> done=new CompletableFuture<>();java.util.concurrent.atomic.AtomicLong progress=new java.util.concurrent.atomic.AtomicLong();
        org.telegram.messenger.NotificationCenter.NotificationCenterDelegate observer=(event,selected,args)->{if(selected!=account||args.length<2||!source.getAbsolutePath().equals(args[0]))return;if(event==org.telegram.messenger.NotificationCenter.fileUploaded)done.complete((TLRPC.InputFile)args[1]);else if(event==org.telegram.messenger.NotificationCenter.fileUploadFailed)done.completeExceptionally(new AssertionError("cold_native_upload_failed"));else progress.accumulateAndGet(((Number)args[1]).longValue(),Math::max);};
        instrumentation.runOnMainSync(()->{org.telegram.messenger.NotificationCenter center=org.telegram.messenger.NotificationCenter.getInstance(account);center.addObserver(observer,org.telegram.messenger.NotificationCenter.fileUploaded);center.addObserver(observer,org.telegram.messenger.NotificationCenter.fileUploadFailed);center.addObserver(observer,org.telegram.messenger.NotificationCenter.fileUploadProgressChanged);});
        try{
            FileLoader.getInstance(account).uploadFile(source.getAbsolutePath(),false,false,ConnectionsManager.FileTypePhoto);
            if(!recover){
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10),nativeId=0;
                while(nativeId==0&&System.nanoTime()<deadline){CompletableFuture<Long> identity=new CompletableFuture<>();org.telegram.messenger.Utilities.stageQueue.postRunnable(()->{try{Object operation=((java.util.Map<?,?>)field(FileLoader.getInstance(account),"uploadOperationPaths")).get(source.getAbsolutePath());identity.complete(operation==null?0:(Long)field(operation,"currentFileId"));}catch(Exception failure){identity.completeExceptionally(failure);}});nativeId=identity.get(3,TimeUnit.SECONDS);if(nativeId==0)Thread.sleep(100);}
                check.verify(nativeId!=0,"cold_upload_original_native_identity_created");String upload=VyrnBridge.uploadId(origin,nativeId);boolean committed=false;
                while(System.nanoTime()<deadline){try{JSONObject state=(JSONObject)bridge.repository.requestForSession(origin.owner,origin.session,"GET","/v1/media-uploads/"+upload,null);if(state.getJSONArray("parts").length()>0){committed=true;break;}}catch(RestRepository.ApiException missing){if(missing.status!=404)throw missing;}Thread.sleep(100);}
                check.verify(committed&&!done.isDone()&&progress.get()==0,"cold_upload_server_part_committed_without_native_acknowledgement");
                saved=new JSONObject().put("owner",origin.owner).put("session",origin.session).put("native_id",nativeId).put("upload",upload).put("path",source.getAbsolutePath()).put("hash",hash).put("process",android.os.Process.myPid());Files.write(evidence.toPath(),saved.toString().getBytes(StandardCharsets.UTF_8));
                Files.write(new File(context.getFilesDir(),"vyrn-test-media-upload-id.txt").toPath(),upload.getBytes(StandardCharsets.UTF_8));
            }else{
                check.verify(android.os.Process.myPid()!=saved.getInt("process"),"cold_upload_actual_android_process_changed");
                TLRPC.InputFile input=done.get(120,TimeUnit.SECONDS);check.verify(input.id==saved.getLong("native_id")&&VyrnBridge.uploadId(origin,input.id).equals(saved.getString("upload")),"cold_upload_original_upload_id_reused_after_process_death");
                check.verify(progress.get()==source.length()&&input.parts>1,"cold_upload_original_worker_finishes_all_parts");
                JSONObject group=(JSONObject)bridge.repository.requestForSession(origin.owner,origin.session,"POST","/v1/conversations",new JSONObject().put("kind","group").put("title","Cold upload fixture").put("members",new org.json.JSONArray()));
                JSONObject complete=(JSONObject)bridge.repository.requestForSession(origin.owner,origin.session,"POST","/v1/media-uploads/"+saved.getString("upload")+"/complete",new JSONObject().put("conversation_id",group.getString("id")).put("kind","image").put("filename",input.name).put("part_count",input.parts).put("md5",input.md5_checksum));
                check.verify(complete.getString("sha256").equals(hash)&&complete.getLong("size")==source.length(),"cold_upload_completed_exact_original_image_bytes");Files.delete(evidence.toPath());
            }
        }finally{instrumentation.runOnMainSync(()->{org.telegram.messenger.NotificationCenter center=org.telegram.messenger.NotificationCenter.getInstance(account);center.removeObserver(observer,org.telegram.messenger.NotificationCenter.fileUploaded);center.removeObserver(observer,org.telegram.messenger.NotificationCenter.fileUploadFailed);center.removeObserver(observer,org.telegram.messenger.NotificationCenter.fileUploadProgressChanged);});}
    }
    static void durableCancel(Instrumentation instrumentation,Activity activity,JSONObject fixture,AuthUiIntegration.Check check,boolean recover) throws Exception {
        android.content.Context context=instrumentation.getTargetContext();File evidence=new File(context.getFilesDir(),"vyrn-test-transfer-cancel.json");
        if(!recover){
            AuthUiIntegration.registration(instrumentation,activity,fixture,check);int account=UserConfig.selectedAccount;VyrnBridge bridge=VyrnBridge.forAccount(account);VyrnBridge.UploadIdentity origin=VyrnBridge.captureUploadIdentity(account);
            long nativeId=java.util.concurrent.ThreadLocalRandom.current().nextLong(1,Long.MAX_VALUE);String upload=VyrnBridge.uploadId(origin,nativeId);
            bridge.repository.uploadPartForSession(origin.owner,origin.session,upload,0,new byte[]{1,2,3});
            JSONObject initial=(JSONObject)bridge.repository.requestForSession(origin.owner,origin.session,"GET","/v1/media-uploads/"+upload,null);check.verify(initial.getJSONArray("parts").length()==1,"cancel_fixture_real_staged_part_exists");
            AuthUiIntegration.networkStep(instrumentation,activity,"offline-load",check);VyrnBridge.cancelUpload(account,origin,nativeId);
            xyz.eclipseofficial.vyrn.data.UploadCancellations journal=new xyz.eclipseofficial.vyrn.data.UploadCancellations(context,account);
            check.verify(journal.contains(origin.owner,origin.session,upload),"cancel_offline_persisted_before_network_ack");
            check.verify(!journal.contains(origin.owner,UUID.randomUUID().toString(),upload),"cancel_journal_separates_origin_session");
            Files.write(evidence.toPath(),new JSONObject().put("account",account).put("owner",origin.owner).put("session",origin.session).put("upload",upload).toString().getBytes(StandardCharsets.UTF_8));
            Thread.sleep(2000);check.verify(journal.contains(origin.owner,origin.session,upload),"cancel_network_failure_keeps_durable_intent");
        }else{
            JSONObject saved=new JSONObject(new String(Files.readAllBytes(evidence.toPath()),StandardCharsets.UTF_8));int account=saved.getInt("account");VyrnBridge bridge=VyrnBridge.forAccount(account);String owner=saved.getString("owner"),session=saved.getString("session"),upload=saved.getString("upload");
            check.verify(owner.equals(bridge.repository.vault.load().getString("user_id"))&&session.equals(bridge.repository.vault.cachedSession()),"cancel_cold_restores_original_user_and_session");
            xyz.eclipseofficial.vyrn.data.UploadCancellations journal=new xyz.eclipseofficial.vyrn.data.UploadCancellations(context,account);check.verify(journal.contains(owner,session,upload),"cancel_intent_survives_actual_process_restart");
            AuthUiIntegration.networkStep(instrumentation,activity,"online-retry",check);
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(40);boolean fenced=false;while(System.nanoTime()<deadline){JSONObject state=(JSONObject)bridge.repository.requestForSession(owner,session,"GET","/v1/media-uploads/"+upload,null);if(state.getString("state").equals("cancelled")&&state.getJSONArray("parts").length()==0&&!journal.contains(owner,session,upload)){fenced=true;break;}Thread.sleep(200);}
            check.verify(fenced,"cancel_cold_worker_fences_transfer_and_removes_parts_after_ack");
            boolean denied=false;try{bridge.repository.uploadPartForSession(owner,session,upload,0,new byte[]{1,2,3});}catch(RestRepository.ApiException failure){denied=failure.status==409;}check.verify(denied,"cancel_cold_fence_rejects_late_replay");Files.delete(evidence.toPath());
        }
    }
    static void sendUi(Instrumentation instrumentation,Activity activity,JSONObject fixture,AuthUiIntegration.Check check,boolean member,boolean offline,boolean ackLoss,boolean cancel,boolean photo,boolean coldPrepare,boolean midOffline,boolean voice,boolean storageFailure) throws Exception {
        AuthUiIntegration.registration(instrumentation,activity,fixture,check);
        int account=UserConfig.selectedAccount;RestRepository repository=VyrnBridge.forAccount(account).repository;String owner=repository.me().id;
        RestRepository creator=repository;String creatorId=owner;
        if(member){creator=new RestRepository(new xyz.eclipseofficial.vyrn.data.TokenVault(instrumentation.getTargetContext(),9004));JSONObject tokens=(JSONObject)creator.request("POST","/v1/auth/register",new JSONObject().put("email","file-owner-"+UUID.randomUUID()+"@vyrn.local").put("password",fixture.getString("password")).put("invite_code",fixture.getString("invite")).put("display_name","File owner").put("device_id",UUID.randomUUID().toString()).put("device_name","File group fixture"),false);creator.vault.save(tokens);creatorId=tokens.getString("user_id");}
        JSONObject group=(JSONObject)creator.requestForUser(creatorId,"POST","/v1/conversations",new JSONObject().put("kind","group").put("title","File picker UI").put("members",member?new org.json.JSONArray().put(owner):new org.json.JSONArray()),true);
        String conversation=group.getString("id");CompletableFuture<org.telegram.tgnet.TLObject> dialogs=new CompletableFuture<>();
        ConnectionsManager.getInstance(account).sendRequest(new TLRPC.TL_messages_getDialogs(),(value,error)->{if(error!=null)dialogs.completeExceptionally(new AssertionError("dialogs_failed"));else dialogs.complete(value);});
        TLRPC.TL_messages_dialogs loaded=(TLRPC.TL_messages_dialogs)dialogs.get(40,TimeUnit.SECONDS);
        instrumentation.runOnMainSync(()->{org.telegram.messenger.MessagesController.getInstance(account).putChats(loaded.chats,false);org.telegram.messenger.MessagesController.getInstance(account).putUsers(loaded.users,false);});
        int local;try(IdMap ids=new IdMap(instrumentation.getTargetContext(),account)){local=ids.local("conversation",conversation);}
        TLRPC.Chat mapped=org.telegram.messenger.MessagesController.getInstance(account).getChat((long)local);check.verify(mapped!=null&&mapped.creator!=member&&org.telegram.messenger.ChatObject.canSendDocument(mapped),"file_ui_native_role_allows_supported_document");
        AuthUiIntegration.handlePrompts(instrumentation,activity,check);
        awaitUi(instrumentation,()->LaunchActivity.getLastFragment()!=null&&LaunchActivity.getLastFragment().getParentLayout()!=null&&!LaunchActivity.getLastFragment().getParentLayout().isTransitionAnimationInProgress(),check,"file_ui_navigation_ready");
        boolean[] opened={false};instrumentation.runOnMainSync(()->{Bundle args=new Bundle();args.putLong("chat_id",local);opened[0]=LaunchActivity.getLastFragment().presentFragment(new ChatActivity(args));});check.verify(opened[0],"file_ui_actual_chat_open");
        awaitUi(instrumentation,()->LaunchActivity.getLastFragment() instanceof ChatActivity&&((ChatActivity)LaunchActivity.getLastFragment()).getChatActivityEnterView()!=null&&!LaunchActivity.getLastFragment().getParentLayout().isTransitionAnimationInProgress(),check,"file_ui_composer_ready");
        ChatActivity chat=(ChatActivity)LaunchActivity.getLastFragment();
        if(voice){recordVoice(instrumentation,activity,chat,account,conversation,check,offline,cancel,coldPrepare,storageFailure);return;}
        awaitUi(instrumentation,()->connectionHeader(account,chat,ConnectionsManager.ConnectionStateConnected),check,"file_ui_header_uses_authenticated_vyrn_connection");
        // Directory only is fixture setup; original controls work without camera/gallery grants.
        for(String permission:new String[]{"android.permission.READ_MEDIA_IMAGES","android.permission.READ_MEDIA_VIDEO","android.permission.CAMERA"})check.verify(instrumentation.getTargetContext().checkSelfPermission(permission)==android.content.pm.PackageManager.PERMISSION_DENIED,"file_ui_unrelated_permission_initially_denied");
        File external=instrumentation.getTargetContext().getExternalFilesDir(null);check.verify(external!=null,"file_ui_fixture_external_storage");File directory=new File(external,"vyrn-picker-fixture");if(!directory.isDirectory()&&!directory.mkdir())throw new AssertionError("fixture_directory");
        int photoWidth=ackLoss||coldPrepare||midOffline?1024:256,photoHeight=ackLoss||coldPrepare||midOffline?768:192;
        byte[] bytes=photo?testPhoto(photoWidth,photoHeight):new byte[2*1024*1024+113];if(!photo)for(int i=0;i<bytes.length;i++)bytes[i]=(byte)(i*19);File source=new File(directory,photo?"vyrn-picker-"+UUID.randomUUID()+".png":"vyrn-picker-file.bin");Files.write(source.toPath(),bytes);
        android.net.Uri galleryImage=null;
        if(photo){
            instrumentation.getUiAutomation().grantRuntimePermission(instrumentation.getTargetContext().getPackageName(),"android.permission.READ_MEDIA_IMAGES");
            android.content.ContentResolver resolver=instrumentation.getTargetContext().getContentResolver();android.content.ContentValues values=new android.content.ContentValues();values.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME,source.getName());values.put(android.provider.MediaStore.Images.Media.MIME_TYPE,"image/png");values.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH,"Pictures/VyrnTest");values.put(android.provider.MediaStore.Images.Media.IS_PENDING,1);
            galleryImage=resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values);if(galleryImage==null)throw new AssertionError("gallery_fixture_insert");
            try(java.io.OutputStream output=resolver.openOutputStream(galleryImage)){if(output==null)throw new AssertionError("gallery_fixture_stream");output.write(bytes);}values.clear();values.put(android.provider.MediaStore.Images.Media.IS_PENDING,0);resolver.update(galleryImage,values,null,null);
            org.telegram.messenger.MediaController.loadGalleryPhotosAlbums(0);
        }
        java.util.concurrent.atomic.AtomicReference<String> preparedHash=new java.util.concurrent.atomic.AtomicReference<>();java.util.concurrent.atomic.AtomicLong preparedSize=new java.util.concurrent.atomic.AtomicLong();
        java.util.concurrent.atomic.AtomicLong progress=new java.util.concurrent.atomic.AtomicLong();java.util.concurrent.atomic.AtomicBoolean failed=new java.util.concurrent.atomic.AtomicBoolean();org.telegram.messenger.NotificationCenter.NotificationCenterDelegate observer=(event,selected,args)->{if(selected==account&&args.length>1&&(photo||source.getAbsolutePath().equals(args[0]))){if(event==org.telegram.messenger.NotificationCenter.fileUploadFailed)failed.set(true);else if(event==org.telegram.messenger.NotificationCenter.fileUploadProgressChanged)progress.accumulateAndGet(((Number)args[1]).longValue(),Math::max);else if(photo&&event==org.telegram.messenger.NotificationCenter.fileUploaded){try{File prepared=new File((String)args[0]);if(prepared.length()<1||prepared.length()>10*1024*1024)throw new IllegalStateException("prepared_photo_size");byte[] uploaded=Files.readAllBytes(prepared.toPath());preparedSize.set(uploaded.length);preparedHash.set(hex(MessageDigest.getInstance("SHA-256").digest(uploaded)));}catch(Exception failure){failed.set(true);}}}};
        instrumentation.runOnMainSync(()->{org.telegram.messenger.NotificationCenter.getInstance(account).addObserver(observer,org.telegram.messenger.NotificationCenter.fileUploadProgressChanged);org.telegram.messenger.NotificationCenter.getInstance(account).addObserver(observer,org.telegram.messenger.NotificationCenter.fileUploadFailed);if(photo)org.telegram.messenger.NotificationCenter.getInstance(account).addObserver(observer,org.telegram.messenger.NotificationCenter.fileUploaded);});
        try{
            tap(instrumentation,(View)field(chat.getChatActivityEnterView(),"attachButton"),check,"file_ui_attach_control");
            awaitUi(instrumentation,()->chat.chatAttachAlert!=null&&chat.chatAttachAlert.isShowing(),check,"file_ui_attach_sheet_visible");Thread.sleep(700);
            awaitUi(instrumentation,()->chat.chatAttachAlert.getCurrentAttachLayout() instanceof org.telegram.ui.Components.ChatAttachAlertDocumentLayout,check,"file_ui_opens_documents_without_gallery");
            boolean[] filesOnly={false};instrumentation.runOnMainSync(()->{try{org.telegram.ui.Components.RecyclerListView tabsView=(org.telegram.ui.Components.RecyclerListView)field(chat.chatAttachAlert,"buttonsRecyclerView");filesOnly[0]=tabsView.getAdapter().getItemCount()==2;}catch(Exception failure){throw new AssertionError(failure);}});check.verify(filesOnly[0],"file_ui_unconnected_attachment_options_hidden");
            if(photo){
                ViewGroup tabs=(ViewGroup)field(chat.chatAttachAlert,"buttonsRecyclerView");View[] galleryTab={null};instrumentation.runOnMainSync(()->{for(int i=0;i<tabs.getChildCount();i++){View child=tabs.getChildAt(i);if(Integer.valueOf(1).equals(child.getTag()))galleryTab[0]=child;}});tap(instrumentation,galleryTab[0],check,"photo_ui_gallery_tab");
                awaitUi(instrumentation,()->chat.chatAttachAlert.getCurrentAttachLayout() instanceof org.telegram.ui.Components.ChatAttachAlertPhotoLayout,check,"photo_ui_original_gallery_visible");
                awaitUi(instrumentation,()->attachmentTransitionFinished(chat.chatAttachAlert),check,"photo_ui_gallery_transition_finished");
                org.telegram.ui.Components.ChatAttachAlertPhotoLayout gallery=(org.telegram.ui.Components.ChatAttachAlertPhotoLayout)chat.chatAttachAlert.getCurrentAttachLayout();
                awaitUi(instrumentation,()->photoCell(gallery,source.getName())!=null,check,"photo_ui_fixture_visible_in_native_gallery");
                android.graphics.Rect previous=new android.graphics.Rect();int[] stable={0};awaitUi(instrumentation,()->{org.telegram.ui.Cells.PhotoAttachPhotoCell target=photoCell(gallery,source.getName());android.graphics.Rect current=new android.graphics.Rect();if(target==null||!target.getCheckFrame().getGlobalVisibleRect(current)||!attachmentTransitionFinished(chat.chatAttachAlert)){stable[0]=0;return false;}stable[0]=current.equals(previous)?stable[0]+1:0;previous.set(current);return stable[0]>=3;},check,"photo_ui_selection_geometry_stable");
                View[] selection={null};instrumentation.runOnMainSync(()->selection[0]=photoCell(gallery,source.getName()).getCheckFrame());
                android.graphics.Bitmap before=instrumentation.getUiAutomation().takeScreenshot();if(before!=null){try(java.io.FileOutputStream output=new java.io.FileOutputStream(new File(instrumentation.getTargetContext().getFilesDir(),"vyrn-test-photo-before-tap.png"))){before.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output);}finally{before.recycle();}}
                tap(instrumentation,selection[0],check,"photo_ui_original_selection_control");
                try{awaitUi(instrumentation,()->gallery.getSelectedItemsCount()==1,check,"photo_ui_single_selection");}catch(AssertionError failure){JSONObject diagnostic=new JSONObject();instrumentation.runOnMainSync(()->{try{diagnostic.put("max",field(chat.chatAttachAlert,"maxSelectedPhotos")).put("group",chat.chatAttachAlert.vyrnGroupAttachments()).put("media",field(gallery,"mediaEnabled")).put("photo",field(gallery,"photoEnabled")).put("video",field(gallery,"videoEnabled")).put("no_permissions",field(gallery,"noGalleryPermissions")).put("selected",gallery.getSelectedItemsCount());org.telegram.ui.Cells.PhotoAttachPhotoCell current=photoCell(gallery,source.getName());if(current!=null){android.graphics.Rect rect=new android.graphics.Rect();current.getCheckFrame().getGlobalVisibleRect(rect);diagnostic.put("check_bounds",rect.toShortString()).put("checked",current.getCheckBox().isChecked());}}catch(Exception unavailable){throw new AssertionError(unavailable);}});Files.write(new File(instrumentation.getTargetContext().getFilesDir(),"vyrn-test-photo-selection.json").toPath(),diagnostic.toString().getBytes(StandardCharsets.UTF_8));throw failure;}
            }else{
            ViewGroup tabs=(ViewGroup)field(chat.chatAttachAlert,"buttonsRecyclerView");View[] fileTab={null};instrumentation.runOnMainSync(()->{for(int i=0;i<tabs.getChildCount();i++){View child=tabs.getChildAt(i);if(Integer.valueOf(4).equals(child.getTag()))fileTab[0]=child;}});tap(instrumentation,fileTab[0],check,"file_ui_document_tab");
            awaitUi(instrumentation,()->chat.chatAttachAlert.getCurrentAttachLayout() instanceof org.telegram.ui.Components.ChatAttachAlertDocumentLayout,check,"file_ui_document_picker_visible");
            org.telegram.ui.Components.ChatAttachAlertDocumentLayout picker=(org.telegram.ui.Components.ChatAttachAlertDocumentLayout)chat.chatAttachAlert.getCurrentAttachLayout();
            java.lang.reflect.Method list=picker.getClass().getDeclaredMethod("listFiles",File.class);list.setAccessible(true);Throwable[] listing={null};instrumentation.runOnMainSync(()->{try{if(!Boolean.TRUE.equals(list.invoke(picker,directory)))listing[0]=new AssertionError("fixture_not_listed");}catch(Exception failure){listing[0]=failure;}});if(listing[0]!=null)throw new AssertionError(listing[0]);instrumentation.waitForIdleSync();Thread.sleep(350);
            awaitUi(instrumentation,()->textView(picker,source.getName())!=null,check,"file_ui_filename_visible");View[] row={null};instrumentation.runOnMainSync(()->row[0]=textView(picker,source.getName()));tap(instrumentation,row[0],check,"file_ui_select_visible_file");
            awaitUi(instrumentation,()->picker.getSelectedItemsCount()==1,check,"file_ui_selection_count_one");
            }
            View send=(View)field(chat.chatAttachAlert,"writeButton");awaitUi(instrumentation,()->send.isShown()&&send.getAlpha()>0.9f,check,"file_ui_send_visible");
            if(offline){AuthUiIntegration.networkStep(instrumentation,activity,"offline-load",check);awaitUi(instrumentation,()->connectionHeader(account,chat,ConnectionsManager.ConnectionStateWaitingForNetwork),check,"file_ui_header_displays_real_offline_state");}
            tap(instrumentation,send,check,"file_ui_original_send_control");
            String interruptedUpload=null;
            if(midOffline)interruptedUpload=interruptPhotoUpload(instrumentation,activity,chat,account,conversation,progress,failed,check);
            if(coldPrepare){prepareColdChat(instrumentation,chat,account,conversation,check);return;}
            if(offline){Thread.sleep(3000);check.verify(progress.get()==0&&!failed.get()&&source.isFile(),"file_ui_offline_retains_original_upload_without_failure");awaitUi(instrumentation,()->chat.messages.stream().anyMatch(value->(photo?value.getPhoto()!=null:value.getDocument()!=null)&&value.messageOwner.send_state==1),check,"file_ui_offline_native_bubble_stays_pending");if(cancel){cancelChatMedia(instrumentation,activity,chat,account,conversation,check,photo);return;}AuthUiIntegration.networkStep(instrumentation,activity,"online-retry",check);awaitUi(instrumentation,()->connectionHeader(account,chat,ConnectionsManager.ConnectionStateConnected),check,"file_ui_header_clears_offline_after_authenticated_reconnect");}
            java.util.List<Models.Message> messages=java.util.Collections.emptyList();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(60);while(System.nanoTime()<deadline){messages=repository.liveMessages(conversation,null,100);if(!messages.isEmpty())break;Thread.sleep(200);}
            check.verify(messages.size()==1&&messages.get(0).attachment!=null,"file_ui_one_server_message_with_attachment");Models.Message sent=messages.get(0);
            check.verify(!failed.get()&&sent.attachment.size==(photo?preparedSize.get():bytes.length)&&sent.attachment.sha256.equals(photo?preparedHash.get():hex(MessageDigest.getInstance("SHA-256").digest(bytes))),photo?"photo_ui_uploaded_exact_native_prepared_bytes":"file_ui_uploaded_exact_original_bytes");
            check.verify(progress.get()==sent.attachment.size,"file_ui_original_upload_progress_complete");
            awaitUi(instrumentation,()->cell(chat.getFragmentView(),sent.ordinal)!=null&&cell(chat.getFragmentView(),sent.ordinal).getMessageObject().messageOwner.send_state==0,check,"file_ui_original_bubble_acknowledged");
            Thread.sleep(1500);check.verify(repository.liveMessages(conversation,null,100).size()==1,"file_ui_no_duplicate_after_ack");
            if(photo){check.verify(sent.attachment.kind.equals("image")&&sent.attachment.width==photoWidth&&sent.attachment.height==photoHeight,"photo_ui_real_prepared_dimensions");awaitUi(instrumentation,()->cell(chat.getFragmentView(),sent.ordinal).getMessageObject().getPhoto()!=null&&cell(chat.getFragmentView(),sent.ordinal).getPhotoImage().hasNotThumb(),check,"photo_ui_acknowledged_native_photo_bubble_visible");}
            for(String permission:new String[]{"android.permission.READ_MEDIA_IMAGES","android.permission.READ_MEDIA_VIDEO","android.permission.CAMERA"})if(!photo||!permission.equals("android.permission.READ_MEDIA_IMAGES"))check.verify(instrumentation.getTargetContext().checkSelfPermission(permission)==android.content.pm.PackageManager.PERMISSION_DENIED,"file_ui_send_preserves_unrelated_permission_denial");
            if(ackLoss||midOffline){try(xyz.eclipseofficial.vyrn.data.OutboxStore outbox=new xyz.eclipseofficial.vyrn.data.OutboxStore(instrumentation.getTargetContext(),account);android.database.Cursor intent=outbox.getReadableDatabase().rawQuery("SELECT upload_body FROM outbox WHERE user_id=? AND conversation_id=? AND state=1",new String[]{owner,conversation})){check.verify(intent.getCount()==1&&intent.moveToFirst(),"file_ui_ack_loss_one_durable_media_intent");JSONObject uploadBody=new JSONObject(intent.getString(0));check.verify(uploadBody.getInt("part_count")>1,"media_ack_loss_original_multi_part_transfer");String upload=uploadBody.getString("id");if(midOffline)check.verify(upload.equals(interruptedUpload),"mid_upload_preserves_original_transfer_identity");Files.write(new File(instrumentation.getTargetContext().getFilesDir(),"vyrn-test-media-upload-id.txt").toPath(),upload.getBytes(StandardCharsets.UTF_8));}}
        }finally{instrumentation.runOnMainSync(()->{org.telegram.messenger.NotificationCenter.getInstance(account).removeObserver(observer,org.telegram.messenger.NotificationCenter.fileUploadProgressChanged);org.telegram.messenger.NotificationCenter.getInstance(account).removeObserver(observer,org.telegram.messenger.NotificationCenter.fileUploadFailed);if(photo)org.telegram.messenger.NotificationCenter.getInstance(account).removeObserver(observer,org.telegram.messenger.NotificationCenter.fileUploaded);});if(galleryImage!=null)instrumentation.getTargetContext().getContentResolver().delete(galleryImage,null,null);}
    }
    private static void recordVoice(Instrumentation instrumentation,Activity activity,ChatActivity chat,int account,String conversation,AuthUiIntegration.Check check,boolean offline,boolean cancel,boolean coldPrepare,boolean storageFailure) throws Exception {
        org.telegram.ui.Components.ChatActivityEnterView composer=chat.getChatActivityEnterView();
        check.verify(org.telegram.messenger.ChatObject.canSendVoice(chat.getCurrentChat())&&!composer.hasRecordVideo()&&!composer.isInVideoMode(),"voice_ui_supported_audio_only_composer");
        instrumentation.getUiAutomation().grantRuntimePermission(instrumentation.getTargetContext().getPackageName(),"android.permission.RECORD_AUDIO");
        View control=(View)field(composer,"audioVideoButtonContainer");awaitUi(instrumentation,()->control.isShown()&&control.hasWindowFocus(),check,"voice_ui_original_record_control_ready");
        if(offline){AuthUiIntegration.networkStep(instrumentation,activity,"offline-load",check);awaitUi(instrumentation,()->connectionHeader(account,chat,ConnectionsManager.ConnectionStateWaitingForNetwork),check,"voice_ui_real_offline_header_before_recording");}
        CompletableFuture<Void> failed=new CompletableFuture<>(),toast=new CompletableFuture<>();java.util.concurrent.atomic.AtomicBoolean audioSent=new java.util.concurrent.atomic.AtomicBoolean();File[] failedSource={null};
        if(storageFailure)instrumentation.getUiAutomation().setOnAccessibilityEventListener(event->{if(event.getEventType()==android.view.accessibility.AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED)for(CharSequence text:event.getText())if(text!=null&&(text.toString().contains("Voice could not be saved")||text.toString().contains("ذخیرهٔ ویس انجام نشد")))toast.complete(null);});
        CompletableFuture<Void> started=new CompletableFuture<>();CompletableFuture<String> prepared=new CompletableFuture<>();java.util.concurrent.atomic.AtomicLong progress=new java.util.concurrent.atomic.AtomicLong();
        org.telegram.messenger.NotificationCenter.NotificationCenterDelegate observer=(event,selected,args)->{if(selected!=account)return;if(event==org.telegram.messenger.NotificationCenter.recordStarted)started.complete(null);else if(event==org.telegram.messenger.NotificationCenter.recordStartError){failed.complete(null);started.completeExceptionally(new AssertionError("native_record_start_failed"));}else if(event==org.telegram.messenger.NotificationCenter.audioDidSent)audioSent.set(true);else if(event==org.telegram.messenger.NotificationCenter.fileUploaded&&args.length>1&&args[0] instanceof String&&((String)args[0]).endsWith(".ogg"))prepared.complete((String)args[0]);else if(event==org.telegram.messenger.NotificationCenter.fileUploadProgressChanged&&args.length>1&&args[0] instanceof String&&((String)args[0]).endsWith(".ogg"))progress.accumulateAndGet(((Number)args[1]).longValue(),Math::max);};
        instrumentation.runOnMainSync(()->{for(int event:new int[]{org.telegram.messenger.NotificationCenter.recordStarted,org.telegram.messenger.NotificationCenter.recordStartError,org.telegram.messenger.NotificationCenter.audioDidSent,org.telegram.messenger.NotificationCenter.fileUploaded,org.telegram.messenger.NotificationCenter.fileUploadProgressChanged})org.telegram.messenger.NotificationCenter.getInstance(account).addObserver(observer,event);});
        int[] point=new int[2];instrumentation.runOnMainSync(()->{control.getLocationOnScreen(point);point[0]+=control.getWidth()/2;point[1]+=control.getHeight()/2;});long down=android.os.SystemClock.uptimeMillis();
        try{
            android.view.MotionEvent press=android.view.MotionEvent.obtain(down,down,android.view.MotionEvent.ACTION_DOWN,point[0],point[1],0);press.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);try{check.verify(instrumentation.getUiAutomation().injectInputEvent(press,true),"voice_ui_actual_record_down");}finally{press.recycle();}
            try{started.get(15,TimeUnit.SECONDS);check.verify(true,"voice_ui_original_recorder_started");if(storageFailure){org.telegram.messenger.MediaController controller=org.telegram.messenger.MediaController.getInstance();CompletableFuture<Void> switched=new CompletableFuture<>();((org.telegram.messenger.DispatchQueue)field(controller,"fileEncodingQueue")).postRunnable(()->{try{failedSource[0]=(File)field(controller,"recordingAudioFile");failVoiceOutput(failedSource[0]);switched.complete(null);}catch(Throwable failure){switched.completeExceptionally(failure);}});switched.get(10,TimeUnit.SECONDS);check.verify(true,"voice_ui_original_recording_output_write_failure_injected");}Thread.sleep(4000);}finally{android.view.MotionEvent release=android.view.MotionEvent.obtain(down,android.os.SystemClock.uptimeMillis(),android.view.MotionEvent.ACTION_UP,point[0],point[1],0);release.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);try{check.verify(instrumentation.getUiAutomation().injectInputEvent(release,true),"voice_ui_actual_record_release_send");}finally{release.recycle();}}
            if(storageFailure){
                failed.get(10,TimeUnit.SECONDS);toast.get(10,TimeUnit.SECONDS);
                check.verify(true,"voice_ui_storage_error_visible_accessibility_toast");
                check.verify(failedSource[0]!=null&&!failedSource[0].exists()&&!audioSent.get()&&!prepared.isDone()&&VyrnBridge.forAccount(account).repository.liveMessages(conversation,null,100).isEmpty(),"voice_ui_failed_recording_removed_and_never_sent");
                awaitUi(instrumentation,()->!composer.isRecordingAudioVideo()&&control.isShown(),check,"voice_ui_storage_error_restores_original_composer");
                instrumentation.getUiAutomation().setOnAccessibilityEventListener(null);
                recordVoice(instrumentation,activity,chat,account,conversation,check,false,false,false,false);
                return;
            }
            if(coldPrepare){prepareColdChat(instrumentation,chat,account,conversation,check);return;}
            byte[] retained=null;
            if(offline){
                String[] source={null};awaitUi(instrumentation,()->{for(org.telegram.messenger.MessageObject message:chat.messages)if(message.isVoice()&&message.getId()<0&&message.messageOwner.send_state==1&&message.messageOwner.attachPath!=null&&new File(message.messageOwner.attachPath).isFile()){source[0]=message.messageOwner.attachPath;return true;}return false;},check,"voice_ui_offline_original_pending_bubble_and_recording");
                retained=Files.readAllBytes(new File(source[0]).toPath());Thread.sleep(3000);check.verify(progress.get()==0&&!prepared.isDone()&&new File(source[0]).isFile(),"voice_ui_offline_retains_source_without_upload_ack");
                if(cancel){cancelChatMedia(instrumentation,activity,chat,account,conversation,check,false);return;}AuthUiIntegration.networkStep(instrumentation,activity,"online-retry",check);awaitUi(instrumentation,()->connectionHeader(account,chat,ConnectionsManager.ConnectionStateConnected),check,"voice_ui_authenticated_reconnection_after_recording");
            }
            String path=prepared.get(60,TimeUnit.SECONDS);byte[] bytes=retained!=null?retained:Files.readAllBytes(new File(path).toPath());
            VyrnBridge bridge=VyrnBridge.forAccount(account);java.util.List<Models.Message> messages=java.util.Collections.emptyList();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(60);while(System.nanoTime()<deadline){messages=bridge.repository.liveMessages(conversation,null,100);if(!messages.isEmpty())break;Thread.sleep(500);}
            check.verify(messages.size()==1&&messages.get(0).attachment!=null&&"voice".equals(messages.get(0).attachment.kind),"voice_ui_actual_send_publishes_one_voice");Models.Message sent=messages.get(0);
            check.verify(sent.attachment.sha256.equals(hex(MessageDigest.getInstance("SHA-256").digest(bytes)))&&sent.attachment.size==bytes.length&&sent.attachment.durationMs>=3000&&progress.get()==bytes.length,"voice_ui_original_recorded_bytes_duration_and_progress");
            awaitUi(instrumentation,()->cell(chat.getFragmentView(),sent.ordinal)!=null&&cell(chat.getFragmentView(),sent.ordinal).getMessageObject().isVoice()&&cell(chat.getFragmentView(),sent.ordinal).getMessageObject().messageOwner.send_state==0,check,"voice_ui_original_sent_bubble_acknowledged");
            Thread.sleep(1500);check.verify(bridge.repository.liveMessages(conversation,null,100).size()==1,"voice_ui_actual_send_not_duplicated");
        }finally{if(storageFailure)instrumentation.getUiAutomation().setOnAccessibilityEventListener(null);instrumentation.runOnMainSync(()->{for(int event:new int[]{org.telegram.messenger.NotificationCenter.recordStarted,org.telegram.messenger.NotificationCenter.recordStartError,org.telegram.messenger.NotificationCenter.audioDidSent,org.telegram.messenger.NotificationCenter.fileUploaded,org.telegram.messenger.NotificationCenter.fileUploadProgressChanged})org.telegram.messenger.NotificationCenter.getInstance(account).removeObserver(observer,event);});}
    }
    private static String interruptPhotoUpload(Instrumentation instrumentation,Activity activity,ChatActivity chat,int account,String conversation,java.util.concurrent.atomic.AtomicLong progress,java.util.concurrent.atomic.AtomicBoolean failed,AuthUiIntegration.Check check) throws Exception {
        awaitUi(instrumentation,()->progress.get()>0,check,"mid_upload_original_worker_acknowledged_partial_progress");
        String[] path={null};instrumentation.runOnMainSync(()->{for(org.telegram.messenger.MessageObject message:chat.messages)if(message.getPhoto()!=null&&message.getId()<0&&message.messageOwner.send_state==1){path[0]=message.messageOwner.attachPath;break;}});
        check.verify(path[0]!=null&&new File(path[0]).isFile()&&progress.get()<new File(path[0]).length(),"mid_upload_pending_original_prepared_photo");
        CompletableFuture<Long> identity=new CompletableFuture<>();org.telegram.messenger.Utilities.stageQueue.postRunnable(()->{try{Object operation=((java.util.Map<?,?>)field(FileLoader.getInstance(account),"uploadOperationPaths")).get(path[0]);identity.complete(operation==null?0:(Long)field(operation,"currentFileId"));}catch(Exception failure){identity.completeExceptionally(failure);}});
        VyrnBridge bridge=VyrnBridge.forAccount(account);VyrnBridge.UploadIdentity origin=VyrnBridge.captureUploadIdentity(account);long nativeId=identity.get(3,TimeUnit.SECONDS);check.verify(nativeId!=0,"mid_upload_original_native_identity");
        String upload=VyrnBridge.uploadId(origin,nativeId);long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);boolean second=false;
        while(System.nanoTime()<deadline){JSONObject state=(JSONObject)bridge.repository.requestForSession(origin.owner,origin.session,"GET","/v1/media-uploads/"+upload,null);for(int i=0;i<state.getJSONArray("parts").length();i++)if(state.getJSONArray("parts").getJSONObject(i).getInt("part")==1)second=true;if(second)break;Thread.sleep(100);}
        check.verify(second&&bridge.repository.liveMessages(conversation,null,100).isEmpty(),"mid_upload_second_part_committed_before_message_publication");
        AuthUiIntegration.networkStep(instrumentation,activity,"offline-load",check);awaitUi(instrumentation,()->connectionHeader(account,chat,ConnectionsManager.ConnectionStateWaitingForNetwork),check,"mid_upload_real_offline_header");
        Thread.sleep(12000);check.verify(!failed.get()&&new File(path[0]).isFile()&&progress.get()>0&&progress.get()<new File(path[0]).length(),"mid_upload_offline_retains_partial_worker_and_source");
        awaitUi(instrumentation,()->chat.messages.stream().anyMatch(value->value.getPhoto()!=null&&value.getId()<0&&value.messageOwner.send_state==1),check,"mid_upload_offline_photo_bubble_pending");
        AuthUiIntegration.networkStep(instrumentation,activity,"online-retry",check);awaitUi(instrumentation,()->connectionHeader(account,chat,ConnectionsManager.ConnectionStateConnected),check,"mid_upload_authenticated_connection_restored");
        return upload;
    }
    private static void prepareColdChat(Instrumentation instrumentation,ChatActivity chat,int account,String conversation,AuthUiIntegration.Check check) throws Exception {
        awaitUi(instrumentation,()->chat.messages.stream().anyMatch(value->(value.getPhoto()!=null||value.isVoice())&&value.getId()<0&&value.messageOwner.send_state==1),check,"cold_chat_actual_pending_media_bubble");
        VyrnBridge bridge=VyrnBridge.forAccount(account);VyrnBridge.UploadIdentity origin=VyrnBridge.captureUploadIdentity(account);JSONObject saved=new JSONObject();String[] path={null};
        instrumentation.runOnMainSync(()->{try{for(org.telegram.messenger.MessageObject message:chat.messages)if((message.getPhoto()!=null||message.isVoice())&&message.getId()<0&&message.messageOwner.send_state==1){path[0]=message.messageOwner.attachPath;saved.put("kind",message.isVoice()?"voice":"image").put("local",message.getId()).put("random",message.messageOwner.random_id).put("dialog",message.getDialogId());break;}}catch(Exception failure){throw new AssertionError(failure);}});
        check.verify(path[0]!=null&&new File(path[0]).isFile(),"cold_chat_native_prepared_media_preserved");
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10),nativeId=0;
        while(nativeId==0&&System.nanoTime()<deadline){CompletableFuture<Long> identity=new CompletableFuture<>();org.telegram.messenger.Utilities.stageQueue.postRunnable(()->{try{Object operation=((java.util.Map<?,?>)field(FileLoader.getInstance(account),"uploadOperationPaths")).get(path[0]);identity.complete(operation==null?0:(Long)field(operation,"currentFileId"));}catch(Exception failure){identity.completeExceptionally(failure);}});nativeId=identity.get(3,TimeUnit.SECONDS);if(nativeId==0)Thread.sleep(100);}
        check.verify(nativeId!=0,"cold_chat_original_native_upload_id_created");String upload=VyrnBridge.uploadId(origin,nativeId);boolean committed=false;
        while(System.nanoTime()<deadline){try{JSONObject state=(JSONObject)bridge.repository.requestForSession(origin.owner,origin.session,"GET","/v1/media-uploads/"+upload,null);if(state.getJSONArray("parts").length()>0){committed=true;break;}}catch(RestRepository.ApiException missing){if(missing.status!=404)throw missing;}Thread.sleep(100);}
        check.verify(committed&&bridge.repository.liveMessages(conversation,null,100).isEmpty(),"cold_chat_part_committed_before_message_publication");
        CompletableFuture<Void> stored=new CompletableFuture<>();org.telegram.messenger.MessagesStorage.getInstance(account).getStorageQueue().postRunnable(()->stored.complete(null));stored.get(10,TimeUnit.SECONDS);
        byte[] prepared=Files.readAllBytes(new File(path[0]).toPath());saved.put("owner",origin.owner).put("session",origin.session).put("conversation",conversation).put("upload",upload).put("hash",hex(MessageDigest.getInstance("SHA-256").digest(prepared))).put("size",prepared.length).put("process",android.os.Process.myPid());
        File directory=instrumentation.getTargetContext().getFilesDir();Files.write(new File(directory,"vyrn-test-cold-chat.json").toPath(),saved.toString().getBytes(StandardCharsets.UTF_8));Files.write(new File(directory,"vyrn-test-media-upload-id.txt").toPath(),upload.getBytes(StandardCharsets.UTF_8));
    }
    static void recoverColdChat(Instrumentation instrumentation,Activity activity,AuthUiIntegration.Check check) throws Exception {
        File evidence=new File(instrumentation.getTargetContext().getFilesDir(),"vyrn-test-cold-chat.json");JSONObject saved=new JSONObject(new String(Files.readAllBytes(evidence.toPath()),StandardCharsets.UTF_8));
        int account=UserConfig.selectedAccount;VyrnBridge bridge=VyrnBridge.forAccount(account);VyrnBridge.UploadIdentity origin=VyrnBridge.captureUploadIdentity(account);
        check.verify(android.os.Process.myPid()!=saved.getInt("process")&&origin.owner.equals(saved.getString("owner"))&&origin.session.equals(saved.getString("session")),"cold_chat_original_account_session_in_new_process");
        String conversation=saved.getString("conversation");java.util.List<Models.Message> messages=java.util.Collections.emptyList();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(70);
        while(System.nanoTime()<deadline){messages=bridge.repository.liveMessages(conversation,null,100);if(!messages.isEmpty())break;Thread.sleep(200);}
        check.verify(messages.size()==1&&messages.get(0).attachment!=null,"cold_chat_automatic_native_send_publishes_one_message");Models.Message sent=messages.get(0);
        String client=UUID.nameUUIDFromBytes((origin.owner+":"+saved.getLong("random")).getBytes(StandardCharsets.UTF_8)).toString();
        check.verify(sent.attachment.kind.equals(saved.getString("kind"))&&sent.attachment.sha256.equals(saved.getString("hash"))&&sent.attachment.size==saved.getLong("size"),"cold_chat_recovered_exact_original_prepared_bytes");
        try(xyz.eclipseofficial.vyrn.data.OutboxStore outbox=new xyz.eclipseofficial.vyrn.data.OutboxStore(instrumentation.getTargetContext(),account)){xyz.eclipseofficial.vyrn.data.OutboxStore.Entry entry=outbox.get(origin.owner,client);check.verify(entry!=null&&entry.state==xyz.eclipseofficial.vyrn.data.OutboxStore.SENT&&new JSONObject(entry.upload).getString("id").equals(saved.getString("upload")),"cold_chat_original_client_and_upload_identity_recovered");}
        AuthUiIntegration.handlePrompts(instrumentation,activity,check);awaitUi(instrumentation,()->LaunchActivity.getLastFragment()!=null&&LaunchActivity.getLastFragment().getParentLayout()!=null&&!LaunchActivity.getLastFragment().getParentLayout().isTransitionAnimationInProgress(),check,"cold_chat_navigation_ready");
        boolean[] opened={false};instrumentation.runOnMainSync(()->{Bundle args=new Bundle();args.putLong("chat_id",-saved.optLong("dialog"));opened[0]=LaunchActivity.getLastFragment().presentFragment(new ChatActivity(args));});check.verify(opened[0],"cold_chat_actual_conversation_reopened");
        awaitUi(instrumentation,()->LaunchActivity.getLastFragment() instanceof ChatActivity&&cell(LaunchActivity.getLastFragment().getFragmentView(),sent.ordinal)!=null&&cell(LaunchActivity.getLastFragment().getFragmentView(),sent.ordinal).getMessageObject().messageOwner.send_state==0,check,"cold_chat_recovered_media_acknowledged_in_actual_chat");
        if(saved.getString("kind").equals("voice"))awaitUi(instrumentation,()->cell(LaunchActivity.getLastFragment().getFragmentView(),sent.ordinal).getMessageObject().isVoice()&&sent.attachment.durationMs>=3000,check,"cold_chat_original_voice_type_and_validated_duration_recovered");
        Thread.sleep(1500);check.verify(bridge.repository.liveMessages(conversation,null,100).size()==1,"cold_chat_recovered_send_not_duplicated");Files.delete(evidence.toPath());
    }
    private static org.telegram.ui.Cells.PhotoAttachPhotoCell photoCell(View view,String name){
        if(view instanceof org.telegram.ui.Components.ChatAttachAlertPhotoLayout){try{return photoCell((View)field(view,"gridView"),name);}catch(Exception failure){throw new AssertionError(failure);}}
        if(view instanceof org.telegram.ui.Cells.PhotoAttachPhotoCell){org.telegram.ui.Cells.PhotoAttachPhotoCell cell=(org.telegram.ui.Cells.PhotoAttachPhotoCell)view;if(cell.getPhotoEntry()!=null&&cell.getPhotoEntry().path!=null&&cell.getPhotoEntry().path.endsWith("/"+name))return cell;}
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){org.telegram.ui.Cells.PhotoAttachPhotoCell cell=photoCell(((ViewGroup)view).getChildAt(i),name);if(cell!=null)return cell;}
        return null;
    }
    private static void cancelChatMedia(Instrumentation instrumentation,Activity activity,ChatActivity chat,int account,String conversation,AuthUiIntegration.Check check,boolean photo) throws Exception {
        VyrnBridge bridge=VyrnBridge.forAccount(account);VyrnBridge.UploadIdentity origin=VyrnBridge.captureUploadIdentity(account);
        int[] local={0};String[] nativePath={null};instrumentation.runOnMainSync(()->{for(org.telegram.messenger.MessageObject message:chat.messages)if((photo?message.getPhoto()!=null:message.getDocument()!=null)&&message.messageOwner.send_state==1){local[0]=message.getId();nativePath[0]=message.messageOwner.attachPath;break;}});check.verify(local[0]<0,"cancel_chat_pending_negative_message");
        check.verify(nativePath[0]!=null&&!nativePath[0].isEmpty()&&new File(nativePath[0]).isFile(),"cancel_chat_original_prepared_media_path");
        CompletableFuture<Long> transfer=new CompletableFuture<>();org.telegram.messenger.Utilities.stageQueue.postRunnable(()->{try{Object operation=((java.util.Map<?,?>)field(FileLoader.getInstance(account),"uploadOperationPaths")).get(nativePath[0]);transfer.complete((Long)field(operation,"currentFileId"));}catch(Exception failure){transfer.completeExceptionally(failure);}});
        long nativeId=transfer.get(20,TimeUnit.SECONDS);check.verify(nativeId!=0,"cancel_chat_actual_native_upload_id");String upload=VyrnBridge.uploadId(origin,nativeId);
        awaitUi(instrumentation,()->cell(chat.getFragmentView(),local[0])!=null,check,"cancel_chat_actual_pending_file_bubble");
        boolean[] clicked={false};instrumentation.runOnMainSync(()->clicked[0]=cell(chat.getFragmentView(),local[0]).performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK,new Bundle()));check.verify(clicked[0],"cancel_chat_original_bubble_cancel_action");
        xyz.eclipseofficial.vyrn.data.UploadCancellations journal=new xyz.eclipseofficial.vyrn.data.UploadCancellations(instrumentation.getTargetContext(),account);
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);while(!journal.contains(origin.owner,origin.session,upload)&&System.nanoTime()<deadline)Thread.sleep(100);
        check.verify(journal.contains(origin.owner,origin.session,upload),"cancel_chat_offline_hook_persists_transfer_cancel");
        awaitUi(instrumentation,()->chat.messages.stream().anyMatch(message->message.getId()==local[0]),check,"cancel_chat_keeps_bubble_until_remote_confirmation");
        AuthUiIntegration.networkStep(instrumentation,activity,"online-retry",check);
        deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(40);boolean fenced=false;while(System.nanoTime()<deadline){try{JSONObject status=(JSONObject)bridge.repository.requestForSession(origin.owner,origin.session,"GET","/v1/media-uploads/"+upload,null);if(status.getString("state").equals("cancelled")&&status.getJSONArray("parts").length()==0&&!journal.contains(origin.owner,origin.session,upload)){fenced=true;break;}}catch(RestRepository.ApiException missing){if(missing.status!=404)throw missing;}Thread.sleep(200);}
        check.verify(fenced,"cancel_chat_worker_confirms_server_fence_and_cleanup");check.verify(bridge.repository.liveMessages(conversation,null,100).isEmpty(),"cancel_chat_no_server_message_published");
        awaitUi(instrumentation,()->chat.messages.stream().noneMatch(message->message.getId()==local[0]),check,"cancel_chat_confirmed_cancel_removes_native_bubble");
        boolean denied=false;try{bridge.repository.uploadPartForSession(origin.owner,origin.session,upload,0,new byte[]{1});}catch(RestRepository.ApiException error){denied=error.status==409;}check.verify(denied,"cancel_chat_late_part_replay_denied");
        Thread.sleep(1500);check.verify(bridge.repository.liveMessages(conversation,null,100).isEmpty(),"cancel_chat_cancelled_message_not_revived_after_reconnect");
    }
    private static Object field(Object object,String name) throws Exception {Class<?> type=object.getClass();while(type!=null){try{java.lang.reflect.Field value=type.getDeclaredField(name);value.setAccessible(true);return value.get(object);}catch(NoSuchFieldException missing){type=type.getSuperclass();}}throw new NoSuchFieldException(name);}
    private static boolean attachmentTransitionFinished(org.telegram.ui.Components.ChatAttachAlert alert){try{return field(alert,"viewChangeAnimator")==null;}catch(Exception failure){throw new AssertionError(failure);}}
    private static boolean connectionHeader(int account,ChatActivity chat,int expected){try{Object header=field(chat,"avatarContainer");if(ConnectionsManager.getInstance(account).getConnectionState()!=expected||!Integer.valueOf(expected).equals(field(header,"currentConnectionState")))return false;CharSequence text=((org.telegram.ui.ActionBar.SimpleTextView)field(header,"subtitleTextView")).getText();String offline=org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.WaitingForNetwork),connecting=org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.Connecting);return expected==ConnectionsManager.ConnectionStateWaitingForNetwork?offline.contentEquals(text):!offline.contentEquals(text)&&!connecting.contentEquals(text);}catch(Exception unavailable){return false;}}
    private static View textView(View root,String text){if(root.getVisibility()!=View.VISIBLE||root.getAlpha()<0.9f)return null;if(root instanceof android.widget.TextView&&text.contentEquals(((android.widget.TextView)root).getText()))return root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){View found=textView(((ViewGroup)root).getChildAt(i),text);if(found!=null)return found;}return null;}
    private static void awaitUi(Instrumentation instrumentation,java.util.function.BooleanSupplier condition,AuthUiIntegration.Check check,String name) throws Exception {boolean[] ready={false};long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);do{instrumentation.runOnMainSync(()->ready[0]=condition.getAsBoolean());if(ready[0])break;Thread.sleep(100);}while(System.nanoTime()<deadline);if(!ready[0]&&(name.equals("file_ui_selection_count_one")||name.startsWith("photo_ui_"))){android.graphics.Bitmap screen=instrumentation.getUiAutomation().takeScreenshot();if(screen!=null){try(java.io.FileOutputStream output=new java.io.FileOutputStream(new File(instrumentation.getTargetContext().getFilesDir(),"vyrn-test-picker-failure.png"))){screen.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output);}finally{screen.recycle();}}}check.verify(ready[0],name);}
    private static void tap(Instrumentation instrumentation,View view,AuthUiIntegration.Check check,String name){
        check.verify(view!=null,name+"_present");int[] position=new int[2];boolean[] shown={false};
        instrumentation.runOnMainSync(()->{
            android.graphics.Rect visible=new android.graphics.Rect();int[] screen=new int[2],window=new int[2];
            shown[0]=view.isShown()&&view.getGlobalVisibleRect(visible);view.getLocationOnScreen(screen);view.getLocationInWindow(window);
            position[0]=visible.centerX()+screen[0]-window[0];position[1]=visible.centerY()+screen[1]-window[1];
            if(name.equals("photo_ui_original_selection_control"))try{
                JSONObject point=new JSONObject().put("x",position[0]).put("y",position[1]).put("visible",visible.toShortString()).put("screen_x",screen[0]).put("screen_y",screen[1]).put("window_x",window[0]).put("window_y",window[1]);
                Files.write(new File(instrumentation.getTargetContext().getFilesDir(),"vyrn-test-photo-tap.json").toPath(),point.toString().getBytes(StandardCharsets.UTF_8));
            }catch(Exception failure){throw new AssertionError(failure);}
        });
        check.verify(shown[0],name+"_shown");long time=android.os.SystemClock.uptimeMillis();
        for(int action:new int[]{android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_UP}){
            android.view.MotionEvent event=android.view.MotionEvent.obtain(time,android.os.SystemClock.uptimeMillis(),action,position[0],position[1],0);event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
            try{check.verify(instrumentation.getUiAutomation().injectInputEvent(event,true),name+"_injected");}finally{event.recycle();}
            if(action==android.view.MotionEvent.ACTION_DOWN)android.os.SystemClock.sleep(60);
        }
    }
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
    static void upload(Instrumentation instrumentation,Activity activity,JSONObject fixture,AuthUiIntegration.Check check,boolean big,boolean photo,boolean voice) throws Exception {
        AuthUiIntegration.registration(instrumentation,activity,fixture,check);
        int account=UserConfig.selectedAccount;VyrnBridge bridge=VyrnBridge.forAccount(account);String owner=bridge.repository.me().id;
        VyrnBridge.UploadIdentity identity=VyrnBridge.captureUploadIdentity(account);check.verify(VyrnBridge.currentUploadIdentity(account,identity),"upload_native_origin_captured");
        if(voice)voiceCodec(instrumentation,check);
        byte[] bytes=voice?Files.readAllBytes(new File(instrumentation.getTargetContext().getFilesDir(),"vyrn-native-voice-480.ogg").toPath()):photo?testPhoto():new byte[(big?12:2)*1024*1024+113];if(!photo&&!voice)for(int i=0;i<bytes.length;i++)bytes[i]=(byte)(i*17);
        File source=new File(instrumentation.getTargetContext().getCacheDir(),voice?"vyrn-native-voice.ogg":photo?"vyrn-native-photo.png":big?"vyrn-native-big-upload.bin":"vyrn-native-upload.bin");Files.write(source.toPath(),bytes);String path=source.getAbsolutePath();
        CompletableFuture<TLRPC.InputFile> done=new CompletableFuture<>();java.util.concurrent.atomic.AtomicLong progress=new java.util.concurrent.atomic.AtomicLong();
        org.telegram.messenger.NotificationCenter.NotificationCenterDelegate observer=(event,selected,args)->{
            if(selected!=account||args.length==0||!path.equals(args[0]))return;
            if(event==org.telegram.messenger.NotificationCenter.fileUploaded){if(args[1] instanceof TLRPC.InputFile)done.complete((TLRPC.InputFile)args[1]);else done.completeExceptionally(new AssertionError("upload_not_plain_file"));}
            else if(event==org.telegram.messenger.NotificationCenter.fileUploadFailed)done.completeExceptionally(new AssertionError("native_upload_failed"));
            else if(event==org.telegram.messenger.NotificationCenter.fileUploadProgressChanged)progress.accumulateAndGet(((Number)args[1]).longValue(),Math::max);
        };
        instrumentation.runOnMainSync(()->{org.telegram.messenger.NotificationCenter center=org.telegram.messenger.NotificationCenter.getInstance(account);center.addObserver(observer,org.telegram.messenger.NotificationCenter.fileUploaded);center.addObserver(observer,org.telegram.messenger.NotificationCenter.fileUploadFailed);center.addObserver(observer,org.telegram.messenger.NotificationCenter.fileUploadProgressChanged);});
        TLRPC.InputFile input;
        try{FileLoader.getInstance(account).uploadFile(path,false,voice,voice?ConnectionsManager.FileTypeAudio:photo?ConnectionsManager.FileTypePhoto:ConnectionsManager.FileTypeFile);input=done.get(120,TimeUnit.SECONDS);}finally{instrumentation.runOnMainSync(()->{org.telegram.messenger.NotificationCenter center=org.telegram.messenger.NotificationCenter.getInstance(account);center.removeObserver(observer,org.telegram.messenger.NotificationCenter.fileUploaded);center.removeObserver(observer,org.telegram.messenger.NotificationCenter.fileUploadFailed);center.removeObserver(observer,org.telegram.messenger.NotificationCenter.fileUploadProgressChanged);});}
        check.verify(input.id!=0&&input.parts>0&&(big?input instanceof TLRPC.TL_inputFileBig:input instanceof TLRPC.TL_inputFile),big?"upload_original_big_worker_complete":"upload_original_small_worker_complete");
        check.verify(progress.get()==bytes.length,"upload_original_progress_reaches_full_size");
        String id=VyrnBridge.uploadId(identity,input.id);JSONObject status=(JSONObject)bridge.repository.requestForSession(owner,identity.session,"GET","/v1/media-uploads/"+id,null);
        check.verify(status.getString("state").equals("open")&&status.getJSONArray("parts").length()==input.parts,"upload_original_all_native_parts_persisted");
        JSONObject group=(JSONObject)bridge.repository.requestForSession(owner,identity.session,"POST","/v1/conversations",new JSONObject().put("kind","group").put("title","Native uploaded file").put("members",new org.json.JSONArray()));
        String kind=voice?"voice":photo?"image":"file";
        JSONObject request=new JSONObject().put("conversation_id",group.getString("id")).put("kind",kind).put("filename",input.name).put("part_count",input.parts).put("md5",input.md5_checksum==null?"":input.md5_checksum);
        JSONObject complete=null;
        if(!photo&&!voice){
            complete=(JSONObject)bridge.repository.requestForSession(owner,identity.session,"POST","/v1/media-uploads/"+id+"/complete",request);
            check.verify(complete.getLong("size")==bytes.length&&complete.getString("sha256").equals(hex(MessageDigest.getInstance("SHA-256").digest(bytes))),"upload_original_worker_final_bytes_and_hash");
            JSONObject repeat=(JSONObject)bridge.repository.requestForSession(owner,identity.session,"POST","/v1/media-uploads/"+id+"/complete",request);check.verify(repeat.getString("id").equals(complete.getString("id")),"upload_completion_replay_same_media_id");
        }
        TLRPC.TL_messages_sendMedia send=VyrnBridge.mediaRequest(account);TLRPC.TL_inputPeerChat destination=new TLRPC.TL_inputPeerChat();
        try(IdMap map=new IdMap(instrumentation.getTargetContext(),account)){destination.chat_id=map.local("conversation",group.getString("id"));}
        send.peer=destination;send.random_id=java.util.concurrent.ThreadLocalRandom.current().nextLong(1,Long.MAX_VALUE);send.message=voice?"":"Native file caption";
        TLRPC.TL_inputPeerUser self=new TLRPC.TL_inputPeerUser();self.user_id=identity.origin;send.send_as=self;
        if(photo){TLRPC.TL_inputMediaUploadedPhoto payload=new TLRPC.TL_inputMediaUploadedPhoto();payload.file=input;send.media=payload;}
        else if(voice){TLRPC.TL_inputMediaUploadedDocument payload=new TLRPC.TL_inputMediaUploadedDocument();payload.file=input;payload.mime_type="audio/ogg";TLRPC.TL_documentAttributeAudio audio=new TLRPC.TL_documentAttributeAudio();audio.voice=true;audio.duration=77;audio.waveform=org.telegram.messenger.MediaController.getWaveform(source.getAbsolutePath());if(audio.waveform!=null)audio.flags|=4;payload.attributes.add(audio);send.media=payload;}
        else{TLRPC.TL_inputMediaUploadedDocument payload=new TLRPC.TL_inputMediaUploadedDocument();payload.file=input;payload.mime_type="application/octet-stream";TLRPC.TL_documentAttributeFilename name=new TLRPC.TL_documentAttributeFilename();name.file_name=input.name;payload.attributes.add(name);send.media=payload;}
        TLRPC.TL_updateShortSentMessage first=sendFile(account,send);send.send_as=new TLRPC.TL_inputPeerSelf();TLRPC.TL_updateShortSentMessage replayed=sendFile(account,send);
        if(photo){
            check.verify(first.id==replayed.id&&first.media instanceof TLRPC.TL_messageMediaPhoto&&first.media.photo.sizes.size()==1&&first.media.photo.sizes.get(0).size==bytes.length&&first.media.photo.sizes.get(0).w==256&&first.media.photo.sizes.get(0).h==192,"upload_native_photo_send_replay_same_message_and_dimensions");
            complete=(JSONObject)bridge.repository.requestForSession(owner,identity.session,"POST","/v1/media-uploads/"+id+"/complete",request);
            check.verify(complete.getLong("size")==bytes.length&&complete.getString("sha256").equals(hex(MessageDigest.getInstance("SHA-256").digest(bytes)))&&Arrays.equals(first.media.photo.file_reference,complete.getString("sha256").getBytes(StandardCharsets.US_ASCII)),"upload_native_photo_send_completed_original_parts_with_exact_hash");
        }else{
            check.verify(first.id==replayed.id&&first.media instanceof TLRPC.TL_messageMediaDocument&&first.media.document.size==bytes.length,"upload_native_send_replay_same_message_and_document");
            if(voice){
                complete=(JSONObject)bridge.repository.requestForSession(owner,identity.session,"POST","/v1/media-uploads/"+id+"/complete",request);
                Models.Attachment descriptor=bridge.repository.attachmentForUser(owner,complete.getString("id"));
                check.verify(descriptor.durationMs==1010&&"voice".equals(descriptor.kind)&&"audio/ogg".equals(descriptor.contentType),"voice_upload_verified_duration_and_mime");
                TLRPC.Document document=first.media.document;TLRPC.TL_documentAttributeAudio audio=null;for(TLRPC.DocumentAttribute attribute:document.attributes)if(attribute instanceof TLRPC.TL_documentAttributeAudio)audio=(TLRPC.TL_documentAttributeAudio)attribute;
                check.verify(org.telegram.messenger.MessageObject.isVoiceDocument(document)&&audio!=null&&audio.voice&&Math.abs(audio.duration-1.01)<0.001&&Arrays.equals(document.file_reference,descriptor.sha256.getBytes(StandardCharsets.US_ASCII)),"voice_upload_native_ack_uses_verified_duration_and_hash");
                TLRPC.TL_documentAttributeAudio unsupported=(TLRPC.TL_documentAttributeAudio)((TLRPC.TL_inputMediaUploadedDocument)send.media).attributes.get(0);unsupported.voice=false;
                CompletableFuture<TLRPC.TL_error> refused=new CompletableFuture<>();VyrnBridge.dispatch(account,send,(response,error)->refused.complete(error),null);TLRPC.TL_error musicError=refused.get(45,TimeUnit.SECONDS);check.verify(musicError!=null&&"VYRN_FEATURE_UNAVAILABLE".equals(musicError.text),"voice_upload_music_variant_rejected");unsupported.voice=true;
            }
        }
        java.util.List<xyz.eclipseofficial.vyrn.domain.Models.Message> messages=bridge.repository.liveMessages(group.getString("id"),null,100);
        check.verify(messages.size()==1&&messages.get(0).attachment!=null&&messages.get(0).attachment.id.equals(complete.getString("id")),"upload_native_send_one_server_message_with_original_media");
        TLRPC.TL_inputPeerUser other=new TLRPC.TL_inputPeerUser();other.user_id=identity.origin+1;send.send_as=other;
        CompletableFuture<TLRPC.TL_error> rejected=new CompletableFuture<>();VyrnBridge.dispatch(account,send,(response,error)->rejected.complete(error),null);
        TLRPC.TL_error denial=rejected.get(45,TimeUnit.SECONDS);check.verify(denial!=null&&"VYRN_FEATURE_UNAVAILABLE".equals(denial.text)&&bridge.repository.liveMessages(group.getString("id"),null,100).size()==1,"upload_native_send_rejects_other_sender_identity");
        String client=UUID.nameUUIDFromBytes((owner+":"+send.random_id).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        try(xyz.eclipseofficial.vyrn.data.OutboxStore outbox=new xyz.eclipseofficial.vyrn.data.OutboxStore(instrumentation.getTargetContext(),account)){
            xyz.eclipseofficial.vyrn.data.OutboxStore.Entry saved=outbox.get(owner,client);
            check.verify(saved.state==xyz.eclipseofficial.vyrn.data.OutboxStore.SENT&&saved.media.equals(complete.getString("id"))&&saved.session.equals(identity.session)&&new JSONObject(saved.upload).getString("id").equals(id)&&new JSONObject(saved.upload).getString("kind").equals(kind),"upload_native_send_durable_origin_and_media_binding");
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
    private static byte[] testPhoto(){return testPhoto(256,192);}
    private static byte[] testPhoto(int width,int height){
        android.graphics.Bitmap image=android.graphics.Bitmap.createBitmap(width,height,android.graphics.Bitmap.Config.ARGB_8888);
        try{int[] pixels=new int[width*height];java.util.Random random=new java.util.Random(71);for(int i=0;i<pixels.length;i++)pixels[i]=0xff000000|random.nextInt(0x1000000);image.setPixels(pixels,0,width,0,0,width,height);java.io.ByteArrayOutputStream output=new java.io.ByteArrayOutputStream();if(!image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output))throw new AssertionError("photo_fixture_encode");return output.toByteArray();}finally{image.recycle();}
    }
    static void run(Instrumentation instrumentation,Activity activity,JSONObject fixture,AuthUiIntegration.Check check,boolean photo,boolean voice) throws Exception {
        AuthUiIntegration.registration(instrumentation,activity,fixture,check);
        mediaIds(instrumentation,check);
        int account=UserConfig.selectedAccount;VyrnBridge bridge=VyrnBridge.forAccount(account);RestRepository repository=bridge.repository;
        String owner=repository.me().id;
        RestRepository sender=new RestRepository(new xyz.eclipseofficial.vyrn.data.TokenVault(instrumentation.getTargetContext(),9003));
        JSONObject tokens=(JSONObject)sender.request("POST","/v1/auth/register",new JSONObject().put("email","media-sender-"+UUID.randomUUID()+"@vyrn.local").put("password",fixture.getString("password")).put("invite_code",fixture.getString("invite")).put("display_name","Media sender").put("device_id",UUID.randomUUID().toString()).put("device_name","Media API fixture"),false);
        sender.vault.save(tokens);String senderId=tokens.getString("user_id");
        JSONObject group=(JSONObject)sender.requestForUser(senderId,"POST","/v1/conversations",new JSONObject().put("kind","group").put("title","Native file test").put("members",new org.json.JSONArray().put(owner)),true);
        if(voice)voiceCodec(instrumentation,check,250);
        String conversation=group.getString("id");byte[] bytes=voice?Files.readAllBytes(new File(instrumentation.getTargetContext().getFilesDir(),"vyrn-native-voice-480.ogg").toPath()):photo?testPhoto():new byte[2*1024*1024+113];if(!photo&&!voice)for(int i=0;i<bytes.length;i++)bytes[i]=(byte)(i*31);
        HttpsURLConnection connection=(HttpsURLConnection)new java.net.URL(org.telegram.messenger.BuildConfig.VYRN_API_URL+"/v1/media?kind="+(voice?"voice":photo?"image":"file")+"&conversation_id="+conversation).openConnection();
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
        check.verify(rendered[0]!=null,photo?"media_actual_native_photo_bubble":"media_actual_native_file_bubble");
        instrumentation.runOnMainSync(()->check.verify(!rendered[0].getMessageObject().isOutOwner(),"media_actual_recipient_bubble_from_distinct_sender"));
        TLRPC.InputFileLocation location;
        File file;
        if(photo){
            TLRPC.Photo[] nativePhoto={null};instrumentation.runOnMainSync(()->nativePhoto[0]=rendered[0].getMessageObject().getPhoto());
            check.verify(nativePhoto[0]!=null&&nativePhoto[0].sizes.size()==1&&rendered[0].getMessageObject().type==org.telegram.messenger.MessageObject.TYPE_PHOTO,"media_native_photo_type");
            TLRPC.PhotoSize size=nativePhoto[0].sizes.get(0);
            check.verify(size.w==256&&size.h==192&&size.size==bytes.length&&Arrays.equals(nativePhoto[0].file_reference,upload.getString("sha256").getBytes(StandardCharsets.US_ASCII)),"media_native_photo_dimensions_hash_and_size");
            file=FileLoader.getInstance(account).getPathToAttach(size);
            location=new TLRPC.TL_inputPhotoFileLocation();location.id=nativePhoto[0].id;location.access_hash=nativePhoto[0].access_hash;location.file_reference=nativePhoto[0].file_reference;location.thumb_size="x";
        }else{
            TLRPC.Document[] document={null};instrumentation.runOnMainSync(()->document[0]=rendered[0].getMessageObject().getDocument());
            check.verify(document[0]!=null&&document[0].size==bytes.length&&Arrays.equals(document[0].file_reference,upload.getString("sha256").getBytes(StandardCharsets.US_ASCII)),"media_native_document_hash_and_size");
            file=FileLoader.getInstance(account).getPathToAttach(document[0]);
            location=new TLRPC.TL_inputDocumentFileLocation();location.id=document[0].id;location.file_reference=document[0].file_reference;location.thumb_size="";
        }
        JSONObject beforeDownload=new JSONObject();
        if(!file.isFile())instrumentation.runOnMainSync(()->{
            boolean loading=FileLoader.getInstance(account).isLoadingFile(file.getName());
            if(voice)try{beforeDownload.put("button",field(rendered[0],"buttonState")).put("mini",field(rendered[0],"miniButtonState")).put("loading",loading);}catch(Exception failure){throw new AssertionError(failure);}
            if(!loading)check.verify(rendered[0].performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK,new Bundle()),"media_actual_download_action");
        });
        if(voice)Files.write(new File(instrumentation.getTargetContext().getFilesDir(),"vyrn-test-voice-before-download.json").toPath(),beforeDownload.toString().getBytes(StandardCharsets.UTF_8));
        deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(60);while(System.nanoTime()<deadline&&(!file.isFile()||file.length()!=bytes.length))Thread.sleep(100);
        if(voice&&!file.isFile()){
            JSONObject diagnostic=new JSONObject().put("expected",file.getAbsolutePath()).put("before",beforeDownload);
            instrumentation.runOnMainSync(()->{try{org.telegram.messenger.MessageObject object=rendered[0].getMessageObject();diagnostic.put("type",object.type).put("voice",object.isVoice()).put("message_path",FileLoader.getInstance(account).getPathToMessage(object.messageOwner).getAbsolutePath()).put("button",field(rendered[0],"buttonState")).put("mini",field(rendered[0],"miniButtonState")).put("loading",FileLoader.getInstance(account).isLoadingFile(FileLoader.getAttachFileName(object.getDocument()))).put("playing",org.telegram.messenger.MediaController.getInstance().isPlayingMessage(object));}catch(Exception failure){throw new AssertionError(failure);}});
            Files.write(new File(instrumentation.getTargetContext().getFilesDir(),"vyrn-test-voice-download.json").toPath(),diagnostic.toString().getBytes(StandardCharsets.UTF_8));
        }
        check.verify(file.isFile()&&Arrays.equals(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file.toPath())),MessageDigest.getInstance("SHA-256").digest(bytes)),"media_original_worker_complete_verified_file");
        check.verify(VyrnBridge.verifyAttachment(account,location,file),"media_completion_guard_accepts_exact_file");
        if(voice){
            org.telegram.messenger.MediaController player=org.telegram.messenger.MediaController.getInstance();
            awaitUi(instrumentation,()->rendered[0].getMessageObject().type==org.telegram.messenger.MessageObject.TYPE_VOICE&&rendered[0].getMessageObject().isVoice(),check,"voice_actual_native_received_bubble");
            if(!player.isPlayingMessage(rendered[0].getMessageObject())||player.isMessagePaused())instrumentation.runOnMainSync(()->check.verify(rendered[0].performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK,new Bundle()),"voice_actual_native_play_control"));
            try{awaitUi(instrumentation,()->player.isPlayingMessage(rendered[0].getMessageObject())&&!player.isMessagePaused()&&player.getPlayingMessageObject().audioProgress>0,check,"voice_original_player_advances_received_audio");}catch(AssertionError failure){
                JSONObject diagnostic=new JSONObject();instrumentation.runOnMainSync(()->{try{Object audio=field(player,"audioPlayer");diagnostic.put("playing",player.isPlayingMessage(rendered[0].getMessageObject())).put("paused",player.isMessagePaused()).put("button",field(rendered[0],"buttonState")).put("mini",field(rendered[0],"miniButtonState")).put("progress",player.getPlayingMessageObject()==null?-1:player.getPlayingMessageObject().audioProgress).put("player",audio==null?"none":audio.getClass().getSimpleName());if(audio instanceof org.telegram.ui.Components.VideoPlayer)diagnostic.put("position",((org.telegram.ui.Components.VideoPlayer)audio).getCurrentPosition()).put("duration",((org.telegram.ui.Components.VideoPlayer)audio).getDuration());}catch(Exception error){throw new AssertionError(error);}});
                Files.write(new File(instrumentation.getTargetContext().getFilesDir(),"vyrn-test-voice-playback.json").toPath(),diagnostic.toString().getBytes(StandardCharsets.UTF_8));throw failure;
            }
            instrumentation.runOnMainSync(()->check.verify(rendered[0].performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK,new Bundle()),"voice_actual_native_pause_control"));
            awaitUi(instrumentation,()->player.isPlayingMessage(rendered[0].getMessageObject())&&player.isMessagePaused(),check,"voice_original_player_paused_from_chat");
            instrumentation.runOnMainSync(()->player.cleanupPlayer(true,true));
        }
        if(photo){
            android.graphics.Bitmap decoded=android.graphics.BitmapFactory.decodeFile(file.getAbsolutePath());try{check.verify(decoded!=null&&decoded.getWidth()==256&&decoded.getHeight()==192,"media_native_photo_download_decodes");}finally{if(decoded!=null)decoded.recycle();}
            boolean[] displayed={false};deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
            while(System.nanoTime()<deadline){instrumentation.runOnMainSync(()->{org.telegram.messenger.ImageReceiver receiver=rendered[0].getPhotoImage();android.graphics.Bitmap bitmap=receiver.getBitmap();displayed[0]=rendered[0].isShown()&&receiver.hasNotThumb()&&bitmap!=null&&bitmap.getWidth()>0&&bitmap.getHeight()>0;});if(displayed[0])break;Thread.sleep(100);}
            check.verify(displayed[0],"media_actual_photo_bitmap_visible_in_native_bubble");
            byte[] reference=location.file_reference;location.file_reference=new byte[0];check.verify(!VyrnBridge.verifyAttachment(account,location,file),"media_photo_empty_reference_cannot_bypass_hash_guard");location.file_reference=reference;
        }
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
