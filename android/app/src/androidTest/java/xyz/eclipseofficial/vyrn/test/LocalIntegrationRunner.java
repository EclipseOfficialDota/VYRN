package xyz.eclipseofficial.vyrn.test;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.security.NetworkSecurityPolicy;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.security.KeyStore;
import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.BuildConfig;
import org.telegram.tgnet.tl.TL_update;
import org.telegram.tgnet.tl.TL_account;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.ChatObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import xyz.eclipseofficial.vyrn.compat.VyrnBridge;
import xyz.eclipseofficial.vyrn.data.IdMap;
import xyz.eclipseofficial.vyrn.data.OutboxStore;
import xyz.eclipseofficial.vyrn.data.OutboxSender;
import xyz.eclipseofficial.vyrn.data.RestRepository;
import xyz.eclipseofficial.vyrn.data.TokenVault;
import xyz.eclipseofficial.vyrn.domain.Models;

/** Stateful suites require local TLS; public-connectivity is read-only. No test secrets in release code. */
public final class LocalIntegrationRunner extends Instrumentation {
    private String phase;
    private volatile String stage = "initialization";
    private Context context;
    private final JSONArray passed = new JSONArray();

    @Override public void onCreate(Bundle args) { super.onCreate(args); phase=args.getString("phase", "first"); start(); }

    @Override public void onStart() {
        context=getTargetContext();
        Bundle result=new Bundle();
        Thread diagnostic=null;
        boolean offlineChatReopen=phase.equals("chat-offline-reopen")||phase.equals("chat-failed-reopen")||phase.equals("chat-private-edit-cold")||phase.equals("chat-private-offline-view")||phase.equals("chat-private-deleted-offline-view")||phase.equals("chat-private-pending-reopen")||phase.equals("chat-private-composer-offline")||phase.equals("chat-private-composer-reopen");
        boolean boundedChatStartup=offlineChatReopen||phase.startsWith("chat-private-composer-")||phase.equals("chat-private-fixture-send")||phase.equals("chat-private-auto-recover")||phase.equals("chat-private-manual-retry")||phase.equals("chat-private-cancel-recover");
        if(phase.startsWith("chat-")){
            diagnostic=new Thread(()->{try{Thread.sleep(50000);StringBuilder trace=new StringBuilder("stage=").append(stage).append('\n');for(java.util.Map.Entry<Thread,StackTraceElement[]> entry:Thread.getAllStackTraces().entrySet()){trace.append(entry.getKey().getName().equals("main")?"main":"worker").append(' ').append(entry.getKey().getState()).append('\n');for(StackTraceElement frame:entry.getValue())trace.append(frame).append('\n');}java.nio.file.Files.write(file("vyrn-test-thread-state.txt").toPath(),trace.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}catch(Exception ignored){}},"vyrn-test-diagnostic");diagnostic.setDaemon(true);diagnostic.start();
        }
        try {
            if(phase.equals("branding")) {
                branding();
            } else if(phase.equals("public-connectivity")) {
                publicConnectivity();
            } else {
            check(BuildConfig.VYRN_API_URL.equals("https://localhost:18443"), "local_endpoint_required");
            stage="foreground_activity";
            Intent launch=new Intent(Intent.ACTION_MAIN).setClassName(context,"org.telegram.ui.LaunchActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Activity foreground;
            if(boundedChatStartup){
                context.startActivity(launch);
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);boolean[] visible={false};
                while(!visible[0]&&System.nanoTime()<deadline){
                    org.telegram.ui.LaunchActivity current=org.telegram.ui.LaunchActivity.instance;
                    if(current!=null)AuthUiIntegration.handlePrompts(this,current,(condition,name)->{check(condition,name);passed.put(name);});
                    runOnMainSync(()->{org.telegram.ui.LaunchActivity active=org.telegram.ui.LaunchActivity.instance;visible[0]=active!=null&&!active.isFinishing()&&!active.isDestroyed()&&active.hasWindowFocus()&&org.telegram.ui.LaunchActivity.getLastFragment()!=null;});if(!visible[0])Thread.sleep(100);
                }
                check(visible[0],offlineChatReopen?"offline_foreground_activity_visible":"private_pending_foreground_activity_visible");foreground=org.telegram.ui.LaunchActivity.instance;
            }else{foreground=startActivitySync(launch);}
            check(foreground!=null,"foreground_activity");
            stage="foreground_idle";
            if(!boundedChatStartup)waitForIdleSync();
            if(phase.equals("media-voice-codec")){
                stage="media_voice_codec";MediaUiIntegration.voiceCodec(this,(condition,name)->{check(condition,name);passed.put(name);});
            }else if(phase.equals("media-photo-chat-cold-recover")){
                stage="media_cold_chat";MediaUiIntegration.recoverColdChat(this,foreground,(condition,name)->{check(condition,name);passed.put(name);});
            }else if(phase.equals("media-photo-upload-cold-prepare")||phase.equals("media-photo-upload-cold-recover")){
                stage="media_cold_upload";MediaUiIntegration.uploadCold(this,foreground,read("vyrn-test-fixture.json"),(condition,name)->{check(condition,name);passed.put(name);},phase.endsWith("recover"));
            }else if(phase.equals("media-cancel-prepare")||phase.equals("media-cancel-recover")){
                stage="media_durable_cancel";MediaUiIntegration.durableCancel(this,foreground,read("vyrn-test-fixture.json"),(condition,name)->{check(condition,name);passed.put(name);},phase.equals("media-cancel-recover"));
            }else if(phase.equals("media-send-ui")||phase.equals("media-send-member-ui")||phase.equals("media-send-offline-ui")||phase.equals("media-send-part-ack-ui")||phase.equals("media-cancel-chat-ui")||phase.equals("media-photo-send-ui")||phase.equals("media-photo-send-part-ack-ui")||phase.equals("media-photo-send-member-ui")||phase.equals("media-photo-send-offline-ui")||phase.equals("media-photo-cancel-chat-ui")||phase.equals("media-photo-chat-cold-prepare")||phase.equals("media-photo-send-mid-offline-ui")){
                boolean cancel=phase.equals("media-cancel-chat-ui")||phase.equals("media-photo-cancel-chat-ui");
                stage="media_send_ui";MediaUiIntegration.sendUi(this,foreground,read("vyrn-test-fixture.json"),(condition,name)->{check(condition,name);passed.put(name);},phase.equals("media-send-member-ui")||phase.equals("media-photo-send-member-ui"),phase.equals("media-send-offline-ui")||phase.equals("media-photo-send-offline-ui")||cancel,phase.equals("media-send-part-ack-ui")||phase.equals("media-photo-send-part-ack-ui"),cancel,phase.startsWith("media-photo-"),phase.equals("media-photo-chat-cold-prepare"),phase.equals("media-photo-send-mid-offline-ui"));
            }else if(phase.equals("media-upload-cancel")){
                stage="media_upload_cancel";MediaUiIntegration.cancelUpload(this,foreground,read("vyrn-test-fixture.json"),(condition,name)->{check(condition,name);passed.put(name);});
            }else if(phase.equals("media-upload")||phase.equals("media-upload-big")||phase.equals("media-photo-upload")||phase.equals("media-voice-upload")){
                stage="media_upload";MediaUiIntegration.upload(this,foreground,read("vyrn-test-fixture.json"),(condition,name)->{check(condition,name);passed.put(name);},phase.equals("media-upload-big"),phase.equals("media-photo-upload"),phase.equals("media-voice-upload"));
            }else if(phase.equals("media-ui")||phase.equals("media-photo-ui")){
                stage="media_ui";MediaUiIntegration.run(this,foreground,read("vyrn-test-fixture.json"),(condition,name)->{check(condition,name);passed.put(name);},phase.equals("media-photo-ui"));
            }else if(phase.startsWith("chat-")){
                stage="chat_ui";
                ChatUiIntegration.run(this,foreground,read("vyrn-test-fixture.json"),phase,(condition,name)->{check(condition,name);passed.put(name);});
            } else if(phase.equals("sessions-network")||phase.equals("sessions-ui")||phase.equals("auth-ui")||phase.equals("registration-ui")||phase.startsWith("logout-")||phase.startsWith("onboarding-")){
                stage="auth_ui";
                AuthUiIntegration.Check verification=(condition,name)->{check(condition,name);passed.put(name);};
                if(phase.equals("sessions-network"))AuthUiIntegration.sessionNetwork(this,foreground,read("vyrn-test-fixture.json"),verification);
                else if(phase.equals("sessions-ui"))AuthUiIntegration.sessions(this,foreground,read("vyrn-test-fixture.json"),verification);
                else if(phase.startsWith("onboarding-"))AuthUiIntegration.onboarding(this,foreground,read("vyrn-test-fixture.json"),verification,phase);
                else if(phase.equals("registration-ui"))AuthUiIntegration.registration(this,foreground,read("vyrn-test-fixture.json"),verification);
                else if(phase.equals("logout-ui")||phase.equals("logout-offline"))AuthUiIntegration.logout(this,foreground,read("vyrn-test-fixture.json"),verification,phase.equals("logout-offline"));
                else AuthUiIntegration.run(this,foreground,read("vyrn-test-fixture.json"),verification);
            } else {
            if(phase.equals("signal-network-offline-restart")){stage="signal_network_offline_restart";SignalNetworkIntegration.offlineRestart(context,(condition,name)->{check(condition,name);passed.put(name);});} else if(phase.equals("signal-network-restart")){stage="signal_network_restart";SignalNetworkIntegration.restart(context,(condition,name)->{check(condition,name);passed.put(name);});} else if(phase.equals("signal-network-cleanup")){stage="signal_network_cleanup";SignalNetworkIntegration.cleanup(context);} else if(phase.equals("signal-network")){stage="signal_network";SignalNetworkIntegration.run(context,(condition,name)->{check(condition,name);passed.put(name);});} else if(phase.startsWith("signal")){stage="signal_protocol";SignalIntegration.run(context,phase,(condition,name)->{check(condition,name);passed.put(name);});} else if(phase.equals("first")) first(); else if(phase.equals("mutations")) mutations(); else if(phase.equals("profile")) profile(); else if(phase.equals("avatar")){RestRepository a=repo(9001),b=repo(9002);check(a.me().id.equals(read("vyrn-test-state.json").getString("user")),"avatar_disposable_fixture_identity");verifyAvatar(a,b);} else if(phase.equals("offline")) offline(); else if(phase.equals("restart")) restart(); else throw new IllegalArgumentException();
            }
            }
            result.putString("vyrn_result", "passed");
            result.putString("checks", passed.toString());
            result.putInt("process_id", android.os.Process.myPid());
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            // Never return exception messages: network errors can contain headers or bodies.
            result.putString("vyrn_result", "failed");
            result.putString("stage", stage);
            result.putString("failure_type", failure.getClass().getSimpleName());
            if(failure.getCause()!=null){result.putString("failure_cause_type",failure.getCause().getClass().getSimpleName());if(failure.getCause() instanceof RestRepository.ApiException)result.putInt("failure_cause_status",((RestRepository.ApiException)failure.getCause()).status);}
            if(failure instanceof RestRepository.ApiException)result.putInt("failure_status",((RestRepository.ApiException)failure).status);
            StringBuilder trace=new StringBuilder();
            for(StackTraceElement frame:failure.getStackTrace()) if(frame.getClassName().startsWith("xyz.eclipseofficial")||frame.getClassName().startsWith("android.app.Instrumentation")||frame.getClassName().startsWith("android.view")) trace.append(frame.toString()).append(";");
            result.putString("failure_location",trace.toString());
            finish(Activity.RESULT_CANCELED, result);
        } finally {if(diagnostic!=null)diagnostic.interrupt();}
    }

    private void branding() throws Exception {
        stage="branding";
        check(xyz.eclipseofficial.vyrn.compat.VyrnBrand.text("Telegram تلگرام telegram").equals("Vyrn Vyrn Vyrn"),"localized_brand_names");
        check(xyz.eclipseofficial.vyrn.compat.VyrnBrand.text("https://telegram.org org.telegram").equals("https://telegram.org org.telegram"),"technical_identifiers_preserved");
        check(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.AppName).equals("Vyrn"),"native_app_name");
        passed.put("brand_names_and_identifiers");
        android.graphics.Bitmap mask=xyz.eclipseofficial.vyrn.compat.VyrnBrand.renderLogo(context.getResources(),1024);
        check(mask.getWidth()==1024&&mask.getHeight()==1024&&android.graphics.Color.alpha(mask.getPixel(0,0))==0,"transparent_logo_render");
        try(java.io.FileOutputStream output=new java.io.FileOutputStream(file("vyrn-logo-transparent.png"))){check(mask.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output),"logo_export");}
        mask.recycle();passed.put("source_logo_transparent_export");
        Intent launch=new Intent(Intent.ACTION_MAIN).setClassName(context,"org.telegram.ui.LaunchActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        check(startActivitySync(launch)!=null,"login_activity_launch");waitForIdleSync();passed.put("login_activity_launch");
    }

    private void publicConnectivity() throws Exception {
        check(BuildConfig.VYRN_API_URL.equals("https://api.vyrn.eclipseofficial.xyz"),"public_endpoint_required");
        check(!NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted("api.vyrn.eclipseofficial.xyz"),"public_cleartext_disabled");
        passed.put("public_endpoint_and_cleartext_policy");
        stage="public_foreground_activity";
        Intent launch=new Intent(Intent.ACTION_MAIN).setClassName(context,"org.telegram.ui.LaunchActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        check(startActivitySync(launch)!=null,"public_foreground_activity");
        waitForIdleSync();
        passed.put("public_foreground_activity");
        stage="public_DNS";
        check(java.net.InetAddress.getAllByName("api.vyrn.eclipseofficial.xyz").length>0,"public_DNS");
        passed.put("public_DNS");
        RestRepository repository=repo(9001);
        stage="public_HTTPS_health";
        check(((JSONObject)repository.request("GET","/health/ready",null,false)).getString("status").equals("ready"),"public_HTTPS_health");
        passed.put("public_HTTPS_health_with_platform_trust");
        String[] paths={"/v1/me","/v1/admin","/v1/admin/dashboard","/metrics"};
        int[] statuses={401,404,404,404};
        for(int i=0;i<paths.length;i++){
            stage="public_access_boundary_"+i;
            boolean rejected=false;
            try{repository.request("GET",paths[i],null,false);}catch(RestRepository.ApiException error){rejected=error.status==statuses[i];}
            check(rejected,stage);passed.put(stage);
        }
    }

    private File file(String name) { return new File(context.getFilesDir(), name); }
    private JSONObject read(String name) throws Exception { return new JSONObject(new String(Files.readAllBytes(file(name).toPath()), StandardCharsets.UTF_8)); }
    private void write(String name, JSONObject value) throws Exception { Files.write(file(name).toPath(),value.toString().getBytes(StandardCharsets.UTF_8)); }
    private void check(boolean condition, String name) { if(!condition) { stage=name; throw new AssertionError(); } }
    private RestRepository repo(int slot) { return new RestRepository(new TokenVault(context,slot)); }
    private JSONObject register(RestRepository repo, JSONObject fixture, String suffix) throws Exception {
        JSONObject body=new JSONObject().put("email", fixture.getString("email").replace("@", suffix+"@"))
            .put("password",fixture.getString("password")).put("invite_code",fixture.getString("invite"))
            .put("display_name","Android test "+suffix).put("device_id",repo.vault.device()).put("device_name","VYRN emulator test");
        JSONObject tokens=(JSONObject)repo.request("POST","/v1/auth/register",body,false);
        check(tokens.getString("recovery_code").length()==43,"recovery_code");
        tokens.remove("recovery_code");
        repo.vault.save(tokens);
        return tokens;
    }

    private void first() throws Exception {
        stage="TLS_policy";
        check(!NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted(),"cleartext_disabled");
        passed.put("cleartext_disabled");

        stage="localhost_resolution";
        check(java.net.InetAddress.getAllByName("localhost").length>0,"localhost_resolution");
        stage="local_health_over_TLS";
        check(((JSONObject)repo(9001).request("GET","/health/ready",null,false)).getString("status").equals("ready"),"local_health");
        stage="invite_registration_over_TLS";
        JSONObject fixture=read("vyrn-test-fixture.json");
        RestRepository a=repo(9001), b=repo(9002);
        a.vault.clear(); b.vault.clear();
        JSONObject ta=register(a,fixture,"a"), tb=register(b,fixture,"b");
        check(a.me().id.equals(ta.getString("user_id")),"authenticated_profile");
        check(b.me().id.equals(tb.getString("user_id")),"second_user_profile");
        passed.put("invite_registration_TLS").put("Android_Keystore_session");

        stage="group_message_delivery";
        JSONObject group=(JSONObject)a.request("POST","/v1/conversations",new JSONObject().put("kind","group").put("title","Android integration گروه").put("members",new JSONArray().put(tb.getString("user_id"))),true);
        String groupId=group.getString("id"), clientId=UUID.randomUUID().toString();
        String messageId=a.send(groupId,clientId,"Hello سلام");
        check(messageId.equals(a.send(groupId,clientId,"Hello سلام")),"idempotent_retry");
        List<Models.Message> messages=b.messages(groupId,null,100);
        check(messages.size()==1 && messages.get(0).text.equals("Hello سلام"),"recipient_message");
        passed.put("group_delivery").put("idempotent_retry").put("Persian_text_roundtrip");

        stage="refresh_rotation";
        String oldRefresh=a.vault.load().getString("refresh_token");
        JSONObject stale=a.vault.load(); stale.put("access_token","invalid"); a.vault.save(stale);
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            Future<Models.User> x=pool.submit(()->a.me()), y=pool.submit(()->repo(9001).me());
            check(x.get().id.equals(ta.getString("user_id")) && y.get().id.equals(ta.getString("user_id")),"parallel_authenticated_requests");
        } finally { pool.shutdownNow(); }
        check(!oldRefresh.equals(a.vault.load().getString("refresh_token")),"refresh_rotated");
        check(repo(9001).me().id.equals(ta.getString("user_id")),"vault_reopened");
        passed.put("concurrent_refresh_rotation");
        boolean accountBlocked=false;
        try {a.requestForUser(tb.getString("user_id"),"GET","/v1/me",null,true);}catch(SecurityException expected){accountBlocked=true;}
        check(accountBlocked,"account_bound_request");passed.put("account_bound_request");

        stage="message_mutations";
        a.request("PATCH","/v1/messages/"+messageId,new JSONObject().put("revision",1).put("payload",new JSONObject().put("text","Edited ویرایش")),true);
        b.request("PUT","/v1/messages/"+messageId+"/reactions",new JSONObject().put("emoji","👍"),true);
        b.request("PUT","/v1/messages/"+messageId+"/receipt",new JSONObject().put("read",true),true);
        check(b.messages(groupId,null,100).get(0).text.equals("Edited ویرایش"),"edit_visible");
        JSONArray records=(JSONArray)a.request("GET","/v1/conversations/"+groupId+"/messages",null,true);
        check(records.getJSONObject(0).getJSONArray("reactions").length()==1,"reaction_visible");
        check(records.getJSONObject(0).getJSONObject("receipts").getInt("read")==1,"read_receipt_visible");
        passed.put("edit_reaction_receipt");
        stage="stable_message_order";
        String orderedGroup=((JSONObject)a.request("POST","/v1/conversations",new JSONObject().put("kind","group").put("title","Ordered history").put("members",new JSONArray().put(tb.getString("user_id"))),true)).getString("id");
        String oldest=a.send(orderedGroup,UUID.randomUUID().toString(),"one");
        a.send(orderedGroup,UUID.randomUUID().toString(),"two");
        String newest=a.send(orderedGroup,UUID.randomUUID().toString(),"three");
        List<Models.Message> history=b.messages(orderedGroup,null,100);
        check(history.size()==3 && history.get(0).id.equals(newest) && history.get(2).id.equals(oldest),"history_order");
        IdMap map=new IdMap(context,9001);
        int upper=Integer.MAX_VALUE;
        try {
            for(Models.Message item:history) {int uiId=map.message(item.id,item.ordinal);check(uiId<upper,"UI_ids_follow_history_order");upper=uiId;}
            check(map.remote("message",upper).equals(oldest),"pagination_mapping");
        } finally {map.close();}
        check(b.messages(orderedGroup,newest,1).get(0).id.equals(history.get(1).id),"history_pagination");
        stage="Telegram_dialog_adapter";
        VyrnBridge bridge=VyrnBridge.forAccount(2);
        bridge.repository.vault.save(a.vault.load());
        TLRPC.User authenticatedUser=bridge.mapUser(a.me());onUi(()->{UserConfig.getInstance(2).setCurrentUser(authenticatedUser);MessagesController.getInstance(2).putUser(authenticatedUser,false);return UserConfig.getInstance(2).vyrnSaveCurrentUser(authenticatedUser);});
        try {
            CompletableFuture<TLObject> response=new CompletableFuture<>();
            VyrnBridge.dispatch(2,new TLRPC.TL_messages_getDialogs(),(value,error)->{if(error!=null){stage="Telegram_dialog_adapter_status_"+error.code;response.completeExceptionally(new AssertionError());}else response.complete(value);},null);
            TLRPC.TL_messages_dialogs dialogs=(TLRPC.TL_messages_dialogs)response.get(30,TimeUnit.SECONDS);
            boolean mapped=false;
            for(TLRPC.Message item:dialogs.messages)if(item.id==history.get(0).ordinal && item.message.equals("three"))mapped=true;
            check(mapped,"Telegram_top_message_id");
            Models.Message edited=a.message(messageId);check(edited.revision==2 && edited.editedDate>0,"repository_edit_revision_date");
            TLRPC.Message nativeEdited=null;for(TLRPC.Message item:dialogs.messages)if(item.id==edited.ordinal)nativeEdited=item;
            check(nativeEdited!=null && nativeEdited.edit_date==edited.editedDate && (nativeEdited.flags&TLRPC.MESSAGE_FLAG_EDITED)!=0,"native_edited_message_metadata");
            try(IdMap observed=new IdMap(context,2)){check(observed.editRevision(ta.getString("user_id"),groupId,edited.ordinal,edited.text)==2,"native_observed_revision");}
            verifyObservedMessageUpgrade();passed.put("repository_edit_revision_date").put("native_edited_message_metadata").put("native_observed_revision");
        } finally {bridge.repository.vault.clear();}
        passed.put("ordered_UI_identifiers").put("history_pagination").put("Telegram_dialog_adapter");
        stage="durable_realtime_inbox";
        bridge.repository.vault.save(a.vault.load());long previousCursor=bridge.syncCursor();
        String incoming=b.send(groupId,UUID.randomUUID().toString(),"Realtime incoming");
        JSONObject sync=(JSONObject)a.request("GET","/v1/sync?cursor="+previousCursor,null,true);long incomingCursor=0;
        JSONArray syncEvents=sync.getJSONArray("events");for(int i=0;i<syncEvents.length();i++){JSONObject event=syncEvents.getJSONObject(i);if(incoming.equals(event.getJSONObject("body").optString("message_id")))incomingCursor=event.getLong("sequence");}
        check(incomingCursor>previousCursor,"incoming_event_observed");
        long deadline=System.currentTimeMillis()+40000;
        while(System.currentTimeMillis()<deadline && (bridge.syncCursor()<incomingCursor || bridge.syncPending()!=0))Thread.sleep(250);
        check(bridge.syncCursor()>=incomingCursor,"websocket_cursor_advanced");check(bridge.syncPending()==0 && bridge.syncDead()==0,"inbox_processed");
        check(a.message(incoming).text.equals("Realtime incoming"),"incoming_message_fetch");
        passed.put("WebSocket_authenticated_resume").put("cursor_after_durable_persistence").put("inbox_to_Telegram_adapter");
        stage="outbox_retry_policy";
        try(OutboxStore store=new OutboxStore(context,9009)){
            String client=UUID.randomUUID().toString();OutboxStore.Entry e=store.enqueue(ta.getString("user_id"),client,groupId,"Retry fixture");
            for(int attempt=1;attempt<=8;attempt++){check(store.failed(e,503,true)==(attempt<8),"bounded_attempts");e=store.get(e.user,e.client);}
            check(e.state==OutboxStore.FAILED && e.attempts==8,"retry_stops");
            e=store.enqueue(e.user,e.client,e.conversation,"Retry fixture");check(e.state==OutboxStore.QUEUED && e.attempts==0,"explicit_retry_resets");
            check(!store.failed(e,403,false),"permanent_error_no_retry");
        }
        context.deleteDatabase("vyrn_outbox_9009.db");
        stage="outbox_claim_cancel_recovery";
        String fixtureUser=ta.getString("user_id"),cancelledClient=UUID.randomUUID().toString(),claimedClient=UUID.randomUUID().toString(),cancelInFlight=UUID.randomUUID().toString();
        try(OutboxStore store=new OutboxStore(context,9009)){
            store.enqueue(fixtureUser,cancelledClient,groupId,"Never sent");
            check(!store.cancel(tb.getString("user_id"),cancelledClient),"cancel_account_isolation");
            check(store.cancel(fixtureUser,cancelledClient),"cancel_unsent");
            check(store.get(fixtureUser,cancelledClient).text==null && store.next(fixtureUser)==null,"cancel_removes_payload_and_queue");
            store.enqueue(fixtureUser,claimedClient,groupId,"Claim recovery");
            OutboxStore.Entry claimed=store.next(fixtureUser);
            check(claimed!=null && claimed.state==OutboxStore.SENDING,"durable_claim");
            try(OutboxStore another=new OutboxStore(context,9009)){check(another.next(fixtureUser)==null,"exclusive_claim");}
            check(!store.cancel(fixtureUser,claimedClient),"no_false_cancel_inflight");
        }
        try(OutboxStore store=new OutboxStore(context,9009)){
            store.recover();OutboxStore.Entry recovered=store.get(fixtureUser,claimedClient);
            check(recovered.state==OutboxStore.QUEUED && recovered.text.equals("Claim recovery"),"claim_reopened");
            check(!store.cancel(fixtureUser,claimedClient),"uncertain_send_not_cancelled");
            recovered=store.next(fixtureUser);
            store.accepted(recovered,new Models.Message(UUID.randomUUID().toString(),fixtureUser,"",1,1,false,false));
            store.getWritableDatabase().execSQL("UPDATE outbox SET completed_at=? WHERE user_id=? AND client_id=?",new Object[]{System.currentTimeMillis()-TimeUnit.DAYS.toMillis(8),fixtureUser,claimedClient});
            store.prune();check(store.get(fixtureUser,claimedClient)==null,"sent_retention");
            check(store.get(fixtureUser,cancelledClient)!=null,"recent_cancel_retained");
            store.enqueue(fixtureUser,cancelInFlight,groupId,"Cancellation race");
            OutboxStore.Entry sending=store.next(fixtureUser);
            check(store.requestCancellation(fixtureUser,cancelInFlight).state==OutboxStore.SENDING && store.next(fixtureUser)==null,"cancel_preserves_exclusive_claim");
            check(store.failed(sending,403,false),"cancel_supersedes_send_failure");
        }
        try(OutboxStore store=new OutboxStore(context,9009)){
            store.recover();OutboxStore.Entry pendingCancel=store.next(fixtureUser);
            check(pendingCancel.client.equals(cancelInFlight) && pendingCancel.cancellation,"cancel_intent_survives_reopen");
            store.cancelled(pendingCancel);
            boolean blocked=false;try{store.enqueue(fixtureUser,cancelInFlight,groupId,"Cancellation race");}catch(IllegalStateException expected){blocked=true;}
            check(blocked,"cancelled_client_not_revived");
        }
        context.deleteDatabase("vyrn_outbox_9009.db");
        passed.put("exclusive_outbox_claim").put("unsent_cancel_safety").put("outbox_claim_recovery").put("outbox_retention");
        passed.put("cancel_during_send_preserves_claim").put("cancel_supersedes_send_failure").put("cancel_intent_survives_reopen").put("cancelled_client_not_revived");
        stage="outbox_populated_upgrade";
        String legacyClient=UUID.randomUUID().toString();
        try(android.database.sqlite.SQLiteDatabase legacy=context.openOrCreateDatabase("vyrn_outbox_9009.db",Context.MODE_PRIVATE,null)){
            legacy.execSQL("CREATE TABLE outbox(queue_id INTEGER PRIMARY KEY AUTOINCREMENT,user_id TEXT NOT NULL,client_id TEXT NOT NULL,conversation_id TEXT NOT NULL,body TEXT,fingerprint TEXT NOT NULL,state INTEGER NOT NULL DEFAULT 0,attempts INTEGER NOT NULL DEFAULT 0,next_at INTEGER NOT NULL DEFAULT 0,server_id TEXT,ordinal INTEGER,date INTEGER,error INTEGER NOT NULL DEFAULT 0,created_at INTEGER NOT NULL,UNIQUE(user_id,client_id))");
            legacy.execSQL("INSERT INTO outbox(user_id,client_id,conversation_id,body,fingerprint,created_at) VALUES(?,?,?,?,?,?)",new Object[]{fixtureUser,legacyClient,groupId,"Legacy pending","fixture",System.currentTimeMillis()});
            legacy.setVersion(1);
        }
        try(OutboxStore upgraded=new OutboxStore(context,9009)){
            check(upgraded.get(fixtureUser,legacyClient).text.equals("Legacy pending"),"upgrade_preserves_pending");
            check(!upgraded.cancel(fixtureUser,legacyClient),"legacy_uncertainty_preserved");
            check(upgraded.next(fixtureUser).state==OutboxStore.SENDING,"upgraded_queue_claimable");
            // Model a populated schema2 database independently of the schema1 migration.
            upgraded.getWritableDatabase().execSQL("ALTER TABLE outbox DROP COLUMN cancel_requested");
            upgraded.getWritableDatabase().execSQL("DROP TABLE ui_cancellations");
            for(String column:new String[]{"media_id","upload_body","origin_session"})upgraded.getWritableDatabase().execSQL("ALTER TABLE outbox DROP COLUMN "+column);
            upgraded.getWritableDatabase().setVersion(2);
        }
        try(OutboxStore upgraded=new OutboxStore(context,9009)){
            OutboxStore.Entry legacy=upgraded.get(fixtureUser,legacyClient);
            check(legacy.state==OutboxStore.SENDING && !legacy.cancellation && legacy.text.equals("Legacy pending"),"schema2_upgrade_preserves_claim");
            check(upgraded.requestCancellation(fixtureUser,legacyClient).cancellation,"schema2_cancel_intent_available");
            upgraded.getWritableDatabase().execSQL("DROP TABLE ui_cancellations");
            for(String column:new String[]{"media_id","upload_body","origin_session"})upgraded.getWritableDatabase().execSQL("ALTER TABLE outbox DROP COLUMN "+column);
            upgraded.getWritableDatabase().setVersion(3);
        }
        passed.put("populated_schema2_cancel_upgrade");
        try(OutboxStore upgraded=new OutboxStore(context,9009)){
            check(upgraded.get(fixtureUser,legacyClient).cancellation && upgraded.pendingUi(fixtureUser)==0,"schema3_ui_journal_upgrade");
            for(String column:new String[]{"media_id","upload_body","origin_session"})upgraded.getWritableDatabase().execSQL("ALTER TABLE outbox DROP COLUMN "+column);
            upgraded.getWritableDatabase().setVersion(4);
        }
        passed.put("populated_schema3_UI_journal_upgrade");
        try(OutboxStore upgraded=new OutboxStore(context,9009)){
            OutboxStore.Entry legacy=upgraded.get(fixtureUser,legacyClient);
            check(legacy.cancellation&&legacy.text.equals("Legacy pending")&&legacy.upload==null&&upgraded.getReadableDatabase().getVersion()==5,"schema4_media_upgrade_preserves_text_and_cancel");
        }
        context.deleteDatabase("vyrn_outbox_9009.db");passed.put("populated_outbox_upgrade");
        stage="durable_UI_cancellation_journal";
        long uiRandom=84531;String uiClient=clientId(fixtureUser,uiRandom);
        try(OutboxStore store=new OutboxStore(context,9009)){
            store.enqueue(fixtureUser,uiClient,groupId,"Pending UI fixture");store.next(fixtureUser);
            store.queueUiCancellation(fixtureUser,uiClient,groupId,"Pending UI fixture",-7001,-123,uiRandom);
            check(store.pendingUi(fixtureUser)==1 && store.resolvedUi(fixtureUser).isEmpty(),"uncertain_UI_not_removable");
        }
        try(OutboxStore store=new OutboxStore(context,9009)){
            store.recover();OutboxStore.Entry cancellation=store.next(fixtureUser);check(cancellation.cancellation,"UI_intent_recovered");store.cancelled(cancellation);
            OutboxStore.UiResolution resolution=store.resolvedUi(fixtureUser).get(0);
            check(resolution.localId==-7001 && resolution.dialogId==-123 && resolution.randomId==uiRandom && resolution.state==OutboxStore.CANCELLED,"UI_identity_survives_reopen");
            check(store.resolvedUi(tb.getString("user_id")).isEmpty(),"UI_journal_account_isolation");
            store.getWritableDatabase().execSQL("UPDATE outbox SET completed_at=? WHERE client_id=?",new Object[]{System.currentTimeMillis()-TimeUnit.DAYS.toMillis(2),uiClient});store.prune();
            check(store.get(fixtureUser,uiClient)!=null,"UI_cleanup_prevents_early_pruning");
            resolution.localId=-7002;store.acknowledgeUi(resolution);check(store.pendingUi(fixtureUser)==1,"UI_ack_identity_guard");resolution.localId=-7001;
            store.acknowledgeUi(resolution);store.prune();check(store.pendingUi(fixtureUser)==0 && store.get(fixtureUser,uiClient)==null,"UI_ack_allows_pruning");
            long committedRandom=84532;String committedClient=clientId(fixtureUser,committedRandom);
            store.enqueue(fixtureUser,committedClient,groupId,"Committed UI fixture");store.accepted(store.next(fixtureUser),new Models.Message(UUID.randomUUID().toString(),fixtureUser,"",1,1,false,false));
            store.queueUiCancellation(fixtureUser,committedClient,groupId,"Committed UI fixture",-7003,-123,committedRandom);
            check(store.resolvedUi(fixtureUser).get(0).state==OutboxStore.SENT,"committed_UI_preserved");
            long absentRandom=84533;String absentClient=clientId(fixtureUser,absentRandom);
            store.queueUiCancellation(fixtureUser,absentClient,groupId,"Unknown delivery history",-7004,-123,absentRandom);
            store.queueUiCancellation(fixtureUser,absentClient,groupId,"Unknown delivery history",-7004,-123,absentRandom);
            check(store.get(fixtureUser,absentClient).cancellation && store.get(fixtureUser,absentClient).state!=OutboxStore.CANCELLED,"absent_history_requires_server_proof");
            check(!store.cancel(fixtureUser,absentClient),"pending_server_proof_not_downgraded");
            boolean rollback=false;long invalidRandom=84534;try{store.queueUiCancellation(fixtureUser,clientId(fixtureUser,invalidRandom),groupId,"x".repeat(16385),-7005,-123,invalidRandom);}catch(IllegalArgumentException expected){rollback=true;}
            check(rollback && store.pendingUi(fixtureUser)==2 && store.get(fixtureUser,clientId(fixtureUser,invalidRandom))==null,"UI_journal_transaction_rollback");
            boolean conflict=false;try{store.queueUiCancellation(fixtureUser,committedClient,groupId,"Committed UI fixture",-7006,-123,committedRandom);}catch(IllegalStateException expected){conflict=true;}
            check(conflict && store.resolvedUi(fixtureUser).get(0).localId==-7003,"UI_identity_immutable");
        }
        context.deleteDatabase("vyrn_outbox_9009.db");
        passed.put("UI_cancel_journal_persistence").put("UI_cancel_account_isolation").put("UI_cleanup_ack_retention").put("committed_UI_preserved").put("unknown_UI_history_requires_proof").put("UI_cancel_atomic_rollback").put("UI_cancel_identity_immutable");
        stage="cancel_before_send_preparation";
        CompletableFuture<Boolean> preSendCancelled=new CompletableFuture<>(),readCancelled=new CompletableFuture<>();
        CompletableFuture<TLObject> preSendResponse=new CompletableFuture<>(),readResponse=new CompletableFuture<>();
        Utilities.stageQueue.postRunnable(()->{
            TLRPC.TL_messages_sendMessage privateSend=new TLRPC.TL_messages_sendMessage();privateSend.peer=new TLRPC.TL_inputPeerUser();privateSend.message="Unavailable private fixture";privateSend.random_id=123;
            int token=ConnectionsManager.getInstance(2).sendRequestSync(privateSend,(value,error)->preSendResponse.complete(value),null,null,0,ConnectionsManager.DEFAULT_DATACENTER_ID,ConnectionsManager.ConnectionTypeGeneric,true);
            VyrnBridge.cancelRequest(2,token,()->preSendCancelled.complete(true));
            token=ConnectionsManager.getInstance(2).sendRequestSync(new TLRPC.TL_updates_getState(),(value,error)->readResponse.complete(value),null,null,0,ConnectionsManager.DEFAULT_DATACENTER_ID,ConnectionsManager.ConnectionTypeGeneric,true);
            VyrnBridge.cancelRequest(2,token,()->readCancelled.complete(true));
        });
        preSendCancelled.get(10,TimeUnit.SECONDS);readCancelled.get(10,TimeUnit.SECONDS);
        check(!preSendResponse.isDone() && !readResponse.isDone(),"cancelled_callbacks_suppressed");
        passed.put("cancel_before_send_preparation").put("cancelled_read_callback_suppressed");
        String uncertainGroup=((JSONObject)a.request("POST","/v1/conversations",new JSONObject().put("kind","group").put("title","Lost acknowledgement").put("members",new JSONArray().put(tb.getString("user_id"))),true)).getString("id");
        long uncertainRandom=UUID.randomUUID().getMostSignificantBits();String uncertainClient=clientId(ta.getString("user_id"),uncertainRandom);
        try(OutboxStore store=new OutboxStore(context,2)){store.enqueue(ta.getString("user_id"),uncertainClient,uncertainGroup,"Lost acknowledgement");}
        // Simulate a server commit whose response was lost before the local acknowledgement commit.
        String uncertainServer=a.send(uncertainGroup,uncertainClient,"Lost acknowledgement");
        passed.put("bounded_outbox_retry_policy");
        String tokenGroup=((JSONObject)a.request("POST","/v1/conversations",new JSONObject().put("kind","group").put("title","Token cancellation").put("members",new JSONArray().put(tb.getString("user_id"))),true)).getString("id");
        long uiCommittedRandom=UUID.randomUUID().getMostSignificantBits();String uiCommittedServer=a.send(tokenGroup,clientId(ta.getString("user_id"),uiCommittedRandom),"Committed UI cancellation");
        long uiDeletedRandom=UUID.randomUUID().getMostSignificantBits();String uiDeletedServer=a.send(tokenGroup,clientId(ta.getString("user_id"),uiDeletedRandom),"Deleted UI cancellation");
        int uiDeletedOrdinal=a.message(uiDeletedServer).ordinal;
        a.request("DELETE","/v1/messages/"+uiDeletedServer,null,true);
        CompletableFuture<Boolean> configured=new CompletableFuture<>();TLRPC.User uiUser=bridge.mapUser(a.me());
        AndroidUtilities.runOnUIThread(()->{UserConfig.getInstance(2).setCurrentUser(uiUser);UserConfig.getInstance(2).saveConfig(true);MessagesController.getInstance(2).putUser(uiUser,false);configured.complete(true);});configured.get(10,TimeUnit.SECONDS);
        check(UserConfig.getInstance(2).getPreferences().contains("user") && UserConfig.getInstance(2).getPreferences().edit().commit(),"Telegram_fixture_identity_durable");
        ConnectionsManager.onLogout(2);waitForIdleSync();
        check(UserConfig.getInstance(2).getClientUserId()==uiUser.id && bridge.repository.vault.isUser(ta.getString("user_id")),"Telegram_native_logout_isolated");passed.put("Telegram_native_logout_isolated");
        check(bridge.repository.vault.isUser(ta.getString("user_id")) && !bridge.repository.vault.isUser(tb.getString("user_id")),"nonblocking_identity_snapshot");passed.put("nonblocking_identity_snapshot");
        verifyNativeSessions(a,b,fixture);
        RestRepository blockDevice=repo(9025);blockDevice.vault.clear();blockDevice.vault.save((JSONObject)blockDevice.request("POST","/v1/auth/login",new JSONObject().put("email",fixture.getString("email").replace("@","a@")).put("password",fixture.getString("password")).put("device_id",blockDevice.vault.device()).put("device_name","VYRN block sync device"),false));check(blockDevice.me().id.equals(ta.getString("user_id")),"block_second_device_session");passed.put("block_second_device_session");
        String createdGroup=createGroupThroughTelegram(a,b,tb.getString("user_id"));
        write("vyrn-test-state.json",new JSONObject().put("native_created_group",createdGroup).put("ui_deleted_random",uiDeletedRandom).put("ui_deleted_server",uiDeletedServer).put("ui_deleted_ordinal",uiDeletedOrdinal).put("ui_committed_random",uiCommittedRandom).put("ui_committed_server",uiCommittedServer).put("token_group",tokenGroup).put("uncertain_group",uncertainGroup).put("uncertain_random",uncertainRandom).put("uncertain_server",uncertainServer).put("group",groupId).put("message",messageId).put("oldest",oldest).put("oldest_ordinal",upper).put("user",ta.getString("user_id")).put("process_id",android.os.Process.myPid()));
        Files.delete(file("vyrn-test-fixture.json").toPath());

        // Run the isolated trust-store probe last. Some Android TLS providers retain
        // process-level trust state after a custom SSLContext has been initialized.
        stage="untrusted_certificate_rejected";
        KeyStore androidRoots=KeyStore.getInstance("AndroidCAStore");androidRoots.load(null);
        KeyStore isolatedRoot=KeyStore.getInstance(KeyStore.getDefaultType());isolatedRoot.load(null);
        java.util.Enumeration<String> aliases=androidRoots.aliases();
        while(aliases.hasMoreElements()) {
            java.security.cert.Certificate candidate=androidRoots.getCertificate(aliases.nextElement());
            if(candidate instanceof java.security.cert.X509Certificate && !((java.security.cert.X509Certificate)candidate).getSubjectX500Principal().getName().toLowerCase().contains("caddy")) {
                isolatedRoot.setCertificateEntry("public-root",candidate);break;
            }
        }
        check(isolatedRoot.size()==1,"public_trust_anchor_available");
        TrustManagerFactory trust=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());trust.init(isolatedRoot);
        SSLContext strictTLS=SSLContext.getInstance("TLS");strictTLS.init(null,trust.getTrustManagers(),null);
        boolean rejected=false;
        javax.net.ssl.SSLSocket untrusted=(javax.net.ssl.SSLSocket)strictTLS.getSocketFactory().createSocket("localhost",18443);
        untrusted.setSoTimeout(5000);
        javax.net.ssl.SSLParameters parameters=untrusted.getSSLParameters();parameters.setEndpointIdentificationAlgorithm("HTTPS");untrusted.setSSLParameters(parameters);
        try { untrusted.startHandshake(); } catch(SSLException expected) { rejected=true; } finally { untrusted.close(); }
        check(rejected,"untrusted_certificate_rejected");
        passed.put("untrusted_certificate_rejected");
    }

    private void verifyObservedMessageUpgrade() throws Exception {
        stage="observed_message_upgrade";context.deleteDatabase("vyrn_ids_9014.db");String message=UUID.randomUUID().toString(),sender=UUID.randomUUID().toString(),group=UUID.randomUUID().toString();
        android.database.sqlite.SQLiteDatabase legacy=android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("vyrn_ids_9014.db"),null);
        legacy.execSQL("CREATE TABLE ids(id INTEGER PRIMARY KEY AUTOINCREMENT,kind TEXT NOT NULL,uuid TEXT NOT NULL,UNIQUE(kind,uuid))");legacy.execSQL("CREATE TABLE message_ids(id INTEGER PRIMARY KEY CHECK(id>0),uuid TEXT NOT NULL UNIQUE)");legacy.execSQL("INSERT INTO message_ids VALUES(17,?)",new Object[]{message});legacy.setVersion(2);legacy.close();
        Models.Message first=new Models.Message(message,sender,"نسخه اول",10,17,false,false,group,1,0),second=new Models.Message(message,sender,"نسخه دوم",10,17,false,false,group,2,11);
        try(IdMap map=new IdMap(context,9014)){
            check(map.remote("message",17).equals(message),"id_map_upgrade_preserves_identity");boolean missing=false;try{map.editRevision(sender,group,17,first.text);}catch(IllegalStateException expected){missing=true;}check(missing,"legacy_snapshot_requires_reload");
            map.observe(first);map.observe(second);map.observe(first);check(map.editRevision(sender,group,17,second.text)==2,"snapshot_revision_never_regresses");
            boolean stale=false,wrongUser=false,wrongGroup=false;try{map.editRevision(sender,group,17,first.text);}catch(IllegalStateException expected){stale=true;}try{map.editRevision(UUID.randomUUID().toString(),group,17,second.text);}catch(IllegalStateException expected){wrongUser=true;}try{map.editRevision(sender,UUID.randomUUID().toString(),17,second.text);}catch(IllegalStateException expected){wrongGroup=true;}check(stale&&wrongUser&&wrongGroup,"snapshot_edit_identity_and_body_bound");
        }
        try(IdMap map=new IdMap(context,9014)){
            check(map.editRevision(sender,group,17,second.text)==2,"snapshot_revision_survives_reopen");map.observe(new Models.Message(message,sender,"",10,17,false,true,group,2,11));
            boolean deleted=false,revived=false;try{map.editRevision(sender,group,17,second.text);}catch(IllegalStateException expected){deleted=true;}try{map.observe(second);}catch(IllegalStateException expected){revived=true;}check(deleted&&revived,"snapshot_expiry_tombstone_not_revived");
        }finally{context.deleteDatabase("vyrn_ids_9014.db");}
        passed.put("populated_id_map_v2_upgrade").put("observed_revision_monotonic").put("edit_snapshot_identity_body_binding").put("observed_revision_reopen").put("same_revision_expiry_tombstone");
    }
    private CompletableFuture<TLObject> queueThroughUI(JSONObject state,long randomId,String text) throws Exception {
        IdMap map=new IdMap(context,2);
        TLRPC.TL_messages_sendMessage request=new TLRPC.TL_messages_sendMessage();
        TLRPC.TL_inputPeerChat peer=new TLRPC.TL_inputPeerChat();
        try{peer.chat_id=map.local("conversation",state.getString("group"));}finally{map.close();}
        request.peer=peer;request.message=text;request.random_id=randomId;
        CompletableFuture<TLObject> response=new CompletableFuture<>();
        VyrnBridge.dispatch(2,request,(value,error)->{if(error!=null)response.completeExceptionally(new AssertionError());else response.complete(value);},null);
        return response;
    }
    private MessageObject observedText(String group,String server) throws Exception {
        TLRPC.TL_messages_getHistory history=new TLRPC.TL_messages_getHistory();TLRPC.TL_inputPeerChat peer=new TLRPC.TL_inputPeerChat();try(IdMap ids=new IdMap(context,2)){peer.chat_id=ids.local("conversation",group);}history.peer=peer;history.limit=100;
        CompletableFuture<TLObject> result=new CompletableFuture<>();ConnectionsManager.getInstance(2).sendRequest(history,(value,error)->{if(error==null)result.complete(value);else result.completeExceptionally(new AssertionError());});
        try(IdMap ids=new IdMap(context,2)){for(TLRPC.Message message:((TLRPC.messages_Messages)result.get(30,TimeUnit.SECONDS)).messages)if(ids.remote("message",message.id).equals(server))return new MessageObject(2,message,false,false);}
        throw new AssertionError();
    }
    private void mutations() throws Exception {
        JSONObject state=read("vyrn-test-state.json");RestRepository a=repo(9001),b=repo(9002);check(a.me().id.equals(state.getString("user")),"mutations_process_identity");
        verifyNativeTextEdit(a,state.getString("native_created_group"),state.getString("user"));verifyNativeDeletion(a,b,state.getString("native_created_group"),state.getString("user"));
    }
    private void profile() throws Exception {
        RestRepository a=repo(9001),b=repo(9002);check(a.me().id.equals(read("vyrn-test-state.json").getString("user")),"profile_process_identity");verifyNativeProfile(a,b);verifyNameEditor(a);verifyBioEditor(a,b);verifyNativeBlocking(a,b);verifyAvatar(a,b);verifyProfileRealtime(a,b);
    }
    private <T> T onUi(java.util.concurrent.Callable<T> action) throws Exception {
        CompletableFuture<T> result=new CompletableFuture<>();AndroidUtilities.runOnUIThread(()->{try{result.complete(action.call());}catch(Throwable failure){result.completeExceptionally(failure);}});return result.get(15,TimeUnit.SECONDS);
    }
    private Object editorField(Object editor,String name) throws Exception {java.lang.reflect.Field field=editor.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(editor);}
    private void selectAvatar(Object updater,int color) throws Exception {
        onUi(()->{android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(320,320,android.graphics.Bitmap.Config.ARGB_8888);bitmap.eraseColor(color);((org.telegram.ui.Components.ImageUpdater)updater).didFinishEdit(bitmap);return true;});
    }
    private void waitAvatar(boolean present) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
        while(System.nanoTime()<deadline){if(onUi(()->{TLRPC.User user=UserConfig.getInstance(2).getCurrentUser();return user!=null && (!(user.photo instanceof TLRPC.TL_userProfilePhotoEmpty) && user.photo!=null)==present;}))return;Thread.sleep(50);}
        check(false,"avatar_confirmation_timeout");
    }
    private void waitNewAvatar(long previous) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);while(System.nanoTime()<deadline){if(onUi(()->{TLRPC.User user=UserConfig.getInstance(2).getCurrentUser();return user!=null&&user.photo instanceof TLRPC.TL_userProfilePhoto&&user.photo.photo_id!=previous;}))return;Thread.sleep(50);}check(false,"new_avatar_confirmation_timeout");
    }
    private void verifyAvatar(RestRepository a,RestRepository b) throws Exception {
        stage="original_profile_avatar";VyrnBridge bridge=VyrnBridge.forAccount(2);long origin=UserConfig.getInstance(2).getClientUserId();String owner=a.me().id;
        org.telegram.ui.SettingsActivity screen=new org.telegram.ui.SettingsActivity();screen.setCurrentAccount(2);boolean opened=false;long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(!opened&&System.nanoTime()<deadline){opened=onUi(()->{org.telegram.ui.ActionBar.BaseFragment parent=(org.telegram.ui.ActionBar.BaseFragment)org.telegram.ui.LaunchActivity.class.getMethod("getSafeLastFragment").invoke(null);return parent!=null&&parent.presentFragment(screen,false,true);});if(!opened)Thread.sleep(50);}
        check(opened,"original_settings_avatar_screen");Object updater=onUi(()->editorField(screen,"imageUpdater"));
        TLRPC.UserProfilePhoto previous=onUi(()->UserConfig.getInstance(2).getCurrentUser().photo);long previousID=previous==null?0:previous.photo_id;
        synchronized(bridge.repository.vault.guard()){selectAvatar(updater,android.graphics.Color.rgb(130,70,220));check(onUi(()->UserConfig.getInstance(2).getCurrentUser().photo==previous),"avatar_waits_for_server");}
        waitNewAvatar(previousID);Models.User accepted=a.me();check(accepted.avatarId!=null,"avatar_server_confirmed");TLRPC.User mapped=bridge.mapUser(accepted);check(mapped.photo.photo_id>0 && org.telegram.messenger.ImageLocation.getForUser(2,mapped,org.telegram.messenger.ImageLocation.TYPE_BIG)!=null,"avatar_native_image_location");
        Models.Avatar metadata=a.avatarForUser(owner,owner);check(metadata.id.equals(accepted.avatarId)&&metadata.width==320&&metadata.height==320&&metadata.contentType.equals("image/jpeg"),"avatar_actual_dimensions");
        check(UserConfig.getInstance(2).getCurrentUser().photo.photo_id==mapped.photo.photo_id,"avatar_settings_native_confirmation");
        TLRPC.TL_photos_getUserPhotos photos=new TLRPC.TL_photos_getUserPhotos();photos.user_id=new TLRPC.TL_inputUserSelf();photos.limit=20;TLRPC.photos_Photos page=(TLRPC.photos_Photos)nativeResponse(photos);check(page.photos.size()==1&&page.photos.get(0).id==mapped.photo.photo_id&&page.photos.get(0).date==metadata.date,"native_current_avatar_page");
        TLRPC.TL_users_getFullUser full=new TLRPC.TL_users_getFullUser();full.id=new TLRPC.TL_inputUserSelf();TLRPC.TL_users_userFull info=(TLRPC.TL_users_userFull)nativeResponse(full);check(info.full_user.profile_photo!=null&&(info.full_user.flags&4)!=0&&info.full_user.profile_photo.id==mapped.photo.photo_id,"native_full_profile_avatar");
        File target=org.telegram.messenger.FileLoader.getInstance(2).getPathToAttach(mapped.photo.photo_big,true);check(!target.exists()||target.delete(),"avatar_download_cache_reset");CompletableFuture<File> download=new CompletableFuture<>();NotificationCenter center=NotificationCenter.getInstance(2);NotificationCenter.NotificationCenterDelegate observer=(id,account,args)->{if(args.length>0&&target.getName().equals(args[0])){if(id==NotificationCenter.fileLoaded)download.complete((File)args[1]);else download.completeExceptionally(new AssertionError());}};
        onUi(()->{center.addObserver(observer,NotificationCenter.fileLoaded);center.addObserver(observer,NotificationCenter.fileLoadFailed);org.telegram.messenger.FileLoader.getInstance(2).loadFile(org.telegram.messenger.ImageLocation.getForUser(2,mapped,org.telegram.messenger.ImageLocation.TYPE_BIG),mapped,"jpg",org.telegram.messenger.FileLoader.PRIORITY_NORMAL,1);return true;});
        try{File loaded=download.get(30,TimeUnit.SECONDS);byte[] raw=a.avatarBytesForUser(owner,accepted.avatarId,0,1024*1024);check(java.util.Arrays.equals(Files.readAllBytes(loaded.toPath()),raw),"native_avatar_file_download");android.graphics.Bitmap image=android.graphics.BitmapFactory.decodeFile(loaded.getAbsolutePath());check(image!=null&&image.getWidth()==320&&image.getHeight()==320,"native_avatar_bitmap_decode");image.recycle();}finally{onUi(()->{center.removeObserver(observer,NotificationCenter.fileLoaded);center.removeObserver(observer,NotificationCenter.fileLoadFailed);return true;});}
        check(a.avatarBytesForUser(owner,accepted.avatarId,metadata.size,32).length==0,"avatar_range_EOF");
        TLRPC.TL_upload_getFile chunk=new TLRPC.TL_upload_getFile();TLRPC.TL_inputPeerPhotoFileLocation location=new TLRPC.TL_inputPeerPhotoFileLocation();location.peer=new TLRPC.TL_inputPeerSelf();location.photo_id=mapped.photo.photo_id;chunk.location=location;chunk.limit=1024;
        CompletableFuture<Boolean> released=new CompletableFuture<>();ConnectionsManager.getInstance(2).sendRequest(chunk,(response,error)->{if(error!=null||!(response instanceof TLRPC.TL_upload_file)){released.complete(false);return;}TLRPC.TL_upload_file file=(TLRPC.TL_upload_file)response;boolean usable=file.bytes!=null&&file.bytes.limit()>0;Utilities.stageQueue.postRunnable(()->released.complete(usable&&file.bytes==null));});check(released.get(30,TimeUnit.SECONDS),"native_avatar_response_released");passed.put("native_avatar_response_released");
        TLRPC.TL_photos_uploadProfilePhoto unsupported=new TLRPC.TL_photos_uploadProfilePhoto();unsupported.flags=2;unsupported.video=new TLRPC.TL_inputFile();check(editFailure(unsupported)!=null&&a.me().avatarId.equals(accepted.avatarId),"native_avatar_video_rejected");
        JSONObject saved=bridge.repository.vault.load();try{bridge.repository.vault.save(b.vault.load());TLRPC.TL_photos_updateProfilePhoto remove=new TLRPC.TL_photos_updateProfilePhoto();remove.id=new TLRPC.TL_inputPhotoEmpty();check(editFailure(remove)!=null,"native_avatar_account_guard");}finally{bridge.repository.vault.save(saved);}check(a.me().avatarId.equals(accepted.avatarId),"avatar_failed_change_preserves_server");
        synchronized(bridge.repository.vault.guard()){onUi(()->{MessagesController.getInstance(2).deleteUserPhoto(null);return true;});check(onUi(()->UserConfig.getInstance(2).getCurrentUser().photo.photo_id==mapped.photo.photo_id),"avatar_delete_waits_for_server");}
        waitAvatar(false);check(a.me().avatarId==null,"avatar_delete_confirmed");selectAvatar(updater,android.graphics.Color.rgb(30,170,110));waitNewAvatar(0);Models.User finalUser=a.me();TLRPC.User finalMapped=bridge.mapUser(finalUser);awaitStorage(MessagesStorage.getInstance(2));TLRPC.User cached=MessagesStorage.getInstance(2).getUserSync(origin);check(cached!=null&&cached.photo.photo_id==finalMapped.photo.photo_id,"avatar_native_cache_confirmed");write("vyrn-avatar-ui.json",new JSONObject().put("user",origin).put("id",finalUser.avatarId).put("photo",finalMapped.photo.photo_id));
        onUi(()->{screen.finishFragment();return true;});
        for(String name:new String[]{"original_settings_avatar_screen","avatar_waits_for_server","avatar_server_confirmed","avatar_native_image_location","avatar_actual_dimensions","avatar_settings_native_confirmation","native_current_avatar_page","native_full_profile_avatar","native_avatar_file_download","native_avatar_bitmap_decode","avatar_range_EOF","native_avatar_video_rejected","native_avatar_account_guard","avatar_failed_change_preserves_server","avatar_delete_waits_for_server","avatar_delete_confirmed","avatar_native_cache_confirmed"})passed.put(name);
    }
    private void verifyProfileRealtime(RestRepository a,RestRepository b) throws Exception {
        stage="profile_realtime";RestRepository other=repo(9025);Models.User original=a.me();String owner=original.id;
        check(other.vault.load().getString("user_id").equals(owner)&&!other.vault.load().getString("session_id").equals(a.vault.load().getString("session_id")),"profile_distinct_device");passed.put("profile_distinct_device");
        realtimeProfileChange(a,other,owner,new JSONObject().put("first_name","نام دستگاه دوم").put("last_name","زنده").put("about","بیو دستگاه دوم"),"other_device_profile_text");
        android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(48,48,android.graphics.Bitmap.Config.ARGB_8888);bitmap.eraseColor(android.graphics.Color.BLUE);java.io.ByteArrayOutputStream encoded=new java.io.ByteArrayOutputStream();bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,encoded);bitmap.recycle();
        String photo=other.uploadAvatarForUser(owner,encoded.toByteArray());realtimeProfileChange(a,other,owner,new JSONObject().put("avatar_id",photo),"other_device_avatar_replace");
        realtimeProfileChange(a,other,owner,new JSONObject().put("avatar_id",JSONObject.NULL),"other_device_avatar_clear");
        realtimeProfileChange(a,other,owner,new JSONObject().put("first_name",original.firstName).put("last_name",original.lastName).put("about",original.about).put("avatar_id",original.avatarId),"other_device_profile_restore");
        Models.User peer=b.me();realtimeProfileChange(a,b,peer.id,new JSONObject().put("first_name","نام عضو زنده").put("about","بیو عضو زنده"),"peer_profile_text_realtime");
        String peerPhoto=b.uploadAvatarForUser(peer.id,encoded.toByteArray());realtimeProfileChange(a,b,peer.id,new JSONObject().put("avatar_id",peerPhoto),"peer_avatar_realtime");
        TLRPC.User mapped=VyrnBridge.forAccount(2).mapUser(a.profileForUser(owner,peer.id).user);check(mapped.photo.photo_id!=UserConfig.getInstance(2).getCurrentUser().photo.photo_id,"different_users_avatar_cache_identity");passed.put("different_users_avatar_cache_identity");
        write("vyrn-profile-realtime.json",new JSONObject().put("peer",mapped.id).put("first",mapped.first_name).put("photo",mapped.photo.photo_id).put("about","بیو عضو زنده"));
        other.request("POST","/v1/auth/logout",new JSONObject(),true);other.vault.clear();
    }
    private void realtimeProfileChange(RestRepository viewer,RestRepository writer,String target,JSONObject changes,String name) throws Exception {
        VyrnBridge bridge=VyrnBridge.forAccount(2);long before=bridge.syncCursor();writer.updateProfileForUser(target,changes);
        JSONObject batch=(JSONObject)viewer.request("GET","/v1/sync?cursor="+before,null,true);long cursor=0;JSONArray events=batch.getJSONArray("events");
        for(int i=0;i<events.length();i++){JSONObject event=events.getJSONObject(i);if(event.getString("kind").equals("user.updated")&&event.getJSONObject("body").optString("user_id").equals(target))cursor=event.getLong("sequence");}
        check(cursor>before,"profile_event_emitted");long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(45);while(System.nanoTime()<deadline&&(bridge.syncCursor()<cursor||bridge.syncPending()!=0))Thread.sleep(100);
        check(bridge.syncCursor()>=cursor&&bridge.syncPending()==0&&bridge.syncDead()==0,"profile_event_processed");Models.User expected=viewer.profileForUser(viewer.vault.load().getString("user_id"),target).user;TLRPC.User mapped=bridge.mapUser(expected);
        check(onUi(()->{TLRPC.User nativeUser=MessagesController.getInstance(2).getUser(mapped.id);TLRPC.UserFull full=MessagesController.getInstance(2).getUserFull(mapped.id);return nativeUser!=null&&nativeUser.first_name.equals(expected.firstName)&&nativeUser.last_name.equals(expected.lastName)&&nativeUser.photo.photo_id==mapped.photo.photo_id&&full!=null&&full.about.equals(expected.about)&&(mapped.photo.photo_id==0?full.profile_photo==null:full.profile_photo!=null&&full.profile_photo.id==mapped.photo.photo_id);}),"profile_event_native_values");
        if(mapped.id==UserConfig.getInstance(2).getClientUserId())check(UserConfig.getInstance(2).getCurrentUser().first_name.equals(expected.firstName)&&UserConfig.getInstance(2).getCurrentUser().photo.photo_id==mapped.photo.photo_id,"profile_event_self_config");
        CompletableFuture<Boolean> cached=new CompletableFuture<>();MessagesStorage storage=MessagesStorage.getInstance(2);storage.getStorageQueue().postRunnable(()->{java.util.HashSet<Long> peers=new java.util.HashSet<>();peers.add(mapped.id);TLRPC.User saved=storage.getUser(mapped.id);cached.complete(saved!=null&&saved.first_name.equals(expected.firstName)&&saved.last_name.equals(expected.lastName)&&saved.photo.photo_id==mapped.photo.photo_id&&storage.loadUserInfos(peers).stream().anyMatch(full->full.id==mapped.id&&full.about.equals(expected.about)&&(mapped.photo.photo_id==0?full.profile_photo==null:full.profile_photo!=null&&full.profile_photo.id==mapped.photo.photo_id)));});check(cached.get(10,TimeUnit.SECONDS),"profile_event_native_persistence");passed.put(name);
    }
    private void waitBlocked(long peer,boolean expected) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);while(!onUi(()->(MessagesController.getInstance(2).blockePeers.indexOfKey(peer)>=0)==expected) && System.nanoTime()<deadline)Thread.sleep(50);
        check(onUi(()->(MessagesController.getInstance(2).blockePeers.indexOfKey(peer)>=0)==expected),"native_block_wait");
    }
    private void waitBlockedList() throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);while(onUi(()->MessagesController.getInstance(2).loadingBlockedPeers) && System.nanoTime()<deadline)Thread.sleep(50);
        check(onUi(()->!MessagesController.getInstance(2).loadingBlockedPeers),"native_block_list_wait");
    }
    private boolean blockedCache(long peer,boolean expected) throws Exception {
        CompletableFuture<Boolean> persisted=new CompletableFuture<>();MessagesStorage storage=MessagesStorage.getInstance(2);storage.getStorageQueue().postRunnable(()->{java.util.HashSet<Long> ids=new java.util.HashSet<>();ids.add(peer);persisted.complete(storage.loadUserInfos(ids).stream().anyMatch(full->full.id==peer && full.blocked==expected && ((full.flags&1)!=0)==expected));});return persisted.get(10,TimeUnit.SECONDS);
    }
    private void verifyNativeBlocking(RestRepository a,RestRepository b) throws Exception {
        stage="native_blocking";VyrnBridge bridge=VyrnBridge.forAccount(2);String owner=a.me().id,target=b.me().id;long origin=UserConfig.getInstance(2).getClientUserId();TLRPC.User peer=bridge.mapUser(b.me());
        CompletableFuture<Boolean> loaded=new CompletableFuture<>();NotificationCenter.NotificationCenterDelegate info=(id,account,args)->{if(((Long)args[0])==peer.id)loaded.complete(true);};
        onUi(()->{NotificationCenter.getInstance(2).addObserver(info,NotificationCenter.userInfoDidLoad);MessagesController.getInstance(2).loadFullUser(peer,0,true);return true;});try{loaded.get(30,TimeUnit.SECONDS);}finally{onUi(()->{NotificationCenter.getInstance(2).removeObserver(info,NotificationCenter.userInfoDidLoad);return true;});}
        onUi(()->{MessagesController.getInstance(2).getBlockedPeers(true);return true;});waitBlockedList();check(onUi(()->MessagesController.getInstance(2).totalBlockedCount==0),"native_empty_block_list");
        synchronized(bridge.repository.vault.guard()){
            onUi(()->{MessagesController.getInstance(2).blockPeer(peer.id);check(MessagesController.getInstance(2).blockePeers.indexOfKey(peer.id)<0 && MessagesController.getInstance(2).totalBlockedCount==0 && !MessagesController.getInstance(2).getUserFull(peer.id).blocked,"native_block_waits_for_server");return true;});
        }
        waitBlocked(peer.id,true);check(a.blockedForUser(owner,target) && onUi(()->MessagesController.getInstance(2).totalBlockedCount==1 && MessagesController.getInstance(2).getUserFull(peer.id).blocked),"native_block_confirmed");
        check(blockedCache(peer.id,true),"native_block_profile_cache");
        TLRPC.TL_contacts_getBlocked list=new TLRPC.TL_contacts_getBlocked();list.limit=1;TLRPC.contacts_Blocked page=(TLRPC.contacts_Blocked)nativeResponse(list);check(page.count==1 && page.blocked.size()==1 && page.blocked.get(0).peer_id.user_id==peer.id && page.blocked.get(0).date>0 && page.users.get(0).first_name.equals(b.me().firstName),"native_block_list_real_metadata");list.offset=1;page=(TLRPC.contacts_Blocked)nativeResponse(list);check(page.count==1 && page.blocked.isEmpty(),"native_block_list_empty_page_count");
        TLRPC.TL_contacts_block repeat=new TLRPC.TL_contacts_block();repeat.id=MessagesController.getInstance(2).getInputPeer(peer.id);CompletableFuture<TLObject> captured=new CompletableFuture<>();synchronized(bridge.repository.vault.guard()){ConnectionsManager.getInstance(2).sendRequest(repeat,(response,error)->{if(error!=null)captured.completeExceptionally(new AssertionError());else captured.complete(response);});repeat.id.user_id=origin;repeat.flags=1;repeat.my_stories_from=true;}check(captured.get(30,TimeUnit.SECONDS) instanceof TLRPC.TL_boolTrue,"native_block_dispatch_immutable");check(onUi(()->MessagesController.getInstance(2).totalBlockedCount==1),"native_block_repeat_preserves_count");
        org.telegram.ui.PrivacyUsersActivity screen=new org.telegram.ui.PrivacyUsersActivity();screen.setCurrentAccount(2);boolean opened=false;long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(!opened && System.nanoTime()<deadline){opened=onUi(()->{org.telegram.ui.ActionBar.BaseFragment parent=(org.telegram.ui.ActionBar.BaseFragment)org.telegram.ui.LaunchActivity.class.getMethod("getSafeLastFragment").invoke(null);return parent!=null && parent.presentFragment(screen,false,true);});if(!opened)Thread.sleep(50);}check(opened && onUi(()->(Integer)editorField(screen,"usersEndRow")-(Integer)editorField(screen,"usersStartRow")==1),"original_block_list_screen_rows");
        JSONObject saved=bridge.repository.vault.load();try{bridge.repository.vault.save(b.vault.load());TLRPC.TL_contacts_unblock rejected=new TLRPC.TL_contacts_unblock();rejected.id=MessagesController.getInstance(2).getInputPeer(peer.id);check(editFailure(rejected)!=null,"native_block_mutation_account_guard");onUi(()->{MessagesController.getInstance(2).getBlockedPeers(true);return true;});waitBlockedList();check(onUi(()->MessagesController.getInstance(2).vyrnBlockedLoadFailed && MessagesController.getInstance(2).blockePeers.indexOfKey(peer.id)>=0 && screen.getVisibleDialog()!=null),"native_block_load_failure_preserves_cache");}finally{bridge.repository.vault.save(saved);}
        onUi(()->{((org.telegram.ui.ActionBar.AlertDialog)screen.getVisibleDialog()).getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick();return true;});waitBlockedList();check(onUi(()->!MessagesController.getInstance(2).vyrnBlockedLoadFailed && MessagesController.getInstance(2).totalBlockedCount==1),"original_block_list_retry");
        CompletableFuture<Boolean> unblocked=new CompletableFuture<>();synchronized(bridge.repository.vault.guard()){onUi(()->{MessagesController.getInstance(2).unblockPeer(peer.id,()->unblocked.complete(true));check(MessagesController.getInstance(2).blockePeers.indexOfKey(peer.id)>=0 && MessagesController.getInstance(2).totalBlockedCount==1,"native_unblock_waits_for_server");return true;});check(!unblocked.isDone(),"native_unblock_callback_waits");}unblocked.get(30,TimeUnit.SECONDS);waitBlocked(peer.id,false);check(!a.blockedForUser(owner,target) && onUi(()->MessagesController.getInstance(2).totalBlockedCount==0),"native_unblock_confirmed");
        repeat.id=MessagesController.getInstance(2).getInputPeer(peer.id);repeat.flags=0;repeat.my_stories_from=true;check(editFailure(repeat)!=null && !a.blockedForUser(owner,target),"native_story_block_rejected");TLRPC.TL_contacts_block self=new TLRPC.TL_contacts_block();self.id=new TLRPC.TL_inputPeerSelf();check(editFailure(self)!=null,"native_self_block_rejected");
        list.limit=101;check(editFailure(list)!=null,"native_block_page_bounds");
        RestRepository other=repo(9025);check(!other.vault.load().getString("session_id").equals(a.vault.load().getString("session_id")),"block_sync_distinct_session");other.setBlockedForUser(owner,target,true);waitBlocked(peer.id,true);check(onUi(()->MessagesController.getInstance(2).getUserFull(peer.id).blocked && MessagesController.getInstance(2).totalBlockedCount==1),"other_device_block_realtime");
        other.setBlockedForUser(owner,target,false);waitBlocked(peer.id,false);check(onUi(()->!MessagesController.getInstance(2).getUserFull(peer.id).blocked && MessagesController.getInstance(2).totalBlockedCount==0),"other_device_unblock_realtime");
        check(blockedCache(peer.id,false),"native_unblock_profile_cache");write("vyrn-blocking-ui.json",new JSONObject().put("peer",peer.id).put("target",target));
        onUi(()->{screen.finishFragment();return true;});
        for(String name:new String[]{"native_empty_block_list","native_block_waits_for_server","native_block_confirmed","native_block_list_real_metadata","native_block_list_empty_page_count","native_block_repeat_preserves_count","original_block_list_screen_rows","native_block_load_failure_preserves_cache","original_block_list_retry","native_unblock_waits_for_server","native_unblock_callback_waits","native_unblock_confirmed","native_story_block_rejected","native_self_block_rejected","native_block_page_bounds","block_sync_distinct_session","other_device_block_realtime","other_device_unblock_realtime","native_unblock_profile_cache"})passed.put(name);
        passed.put("native_block_profile_cache").put("native_block_dispatch_immutable").put("native_block_mutation_account_guard");
    }
    private void clearFullProfile(long user) throws Exception {
        onUi(()->{Object cache=editorField(MessagesController.getInstance(2),"fullUsers");cache.getClass().getMethod("remove",long.class).invoke(cache,user);return true;});
    }
    private org.telegram.ui.ChangeBioActivity openBioEditor() throws Exception {
        org.telegram.ui.ChangeBioActivity editor=new org.telegram.ui.ChangeBioActivity();editor.setCurrentAccount(2);boolean opened=false;long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(!opened && System.nanoTime()<deadline){opened=onUi(()->{org.telegram.ui.ActionBar.BaseFragment parent=(org.telegram.ui.ActionBar.BaseFragment)org.telegram.ui.LaunchActivity.class.getMethod("getSafeLastFragment").invoke(null);return parent!=null && parent.presentFragment(editor,false,true);});if(!opened)Thread.sleep(50);}
        check(opened,"bio_editor_presented");return editor;
    }
    private void verifyBioEditor(RestRepository a,RestRepository b) throws Exception {
        stage="original_bio_editor";long self=UserConfig.getInstance(2).getClientUserId();
        waitProfileEvents(a);
        TLRPC.TL_users_getFullUser request=new TLRPC.TL_users_getFullUser();request.id=new TLRPC.TL_inputUserSelf();
        TLRPC.TL_users_userFull response=(TLRPC.TL_users_userFull)nativeResponse(request);
        check(response.full_user.id==self && response.full_user.about.equals(a.me().about) && (response.full_user.flags&2)!=0 && !response.full_user.phone_calls_available && !response.full_user.video_calls_available,"native_full_profile_bio_caps");passed.put("native_full_profile_bio_caps");
        CompletableFuture<Boolean> loaded=new CompletableFuture<>();NotificationCenter.NotificationCenterDelegate loading=(id,account,args)->{if(((Long)args[0])==self)loaded.complete(true);};
        clearFullProfile(self);onUi(()->{NotificationCenter.getInstance(2).addObserver(loading,NotificationCenter.userInfoDidLoad);return true;});
        org.telegram.ui.ChangeBioActivity editor;
        try{
            synchronized(VyrnBridge.forAccount(2).repository.vault.guard()){
                editor=openBioEditor();check(onUi(()->!((android.widget.EditText)editorField(editor,"firstNameField")).isEnabled() && !((android.view.View)editorField(editor,"doneButton")).isEnabled()),"bio_editor_missing_cache_locked");check(!loaded.isDone(),"bio_editor_missing_cache_waits");
            }
            loaded.get(30,TimeUnit.SECONDS);
        }finally{onUi(()->{NotificationCenter.getInstance(2).removeObserver(loading,NotificationCenter.userInfoDidLoad);return true;});}
        check(onUi(()->((android.widget.EditText)editorField(editor,"firstNameField")).isEnabled() && ((android.widget.EditText)editorField(editor,"firstNameField")).getText().toString().equals("دربارهٔ VYRN")),"bio_editor_loads_actual_profile");passed.put("bio_editor_missing_cache_locked").put("bio_editor_missing_cache_waits").put("bio_editor_loads_actual_profile");
        String target="توضیحات پایدار VYRN";CompletableFuture<Boolean> saved=new CompletableFuture<>();NotificationCenter.NotificationCenterDelegate saving=(id,account,args)->{if(((Long)args[0])==self && target.equals(((TLRPC.UserFull)args[1]).about))saved.complete(true);};
        onUi(()->{NotificationCenter.getInstance(2).addObserver(saving,NotificationCenter.userInfoDidLoad);((android.widget.EditText)editorField(editor,"firstNameField")).setText(target);return true;});
        try{
            synchronized(VyrnBridge.forAccount(2).repository.vault.guard()){
                onUi(()->{((android.view.View)editorField(editor,"doneButton")).performClick();check(!((android.view.View)editorField(editor,"doneButton")).isEnabled() && MessagesController.getInstance(2).getUserFull(self).about.equals("دربارهٔ VYRN"),"bio_editor_waits_for_server");return true;});check(!saved.isDone(),"bio_editor_no_premature_confirmation");
            }
            saved.get(30,TimeUnit.SECONDS);
        }finally{onUi(()->{NotificationCenter.getInstance(2).removeObserver(saving,NotificationCenter.userInfoDidLoad);return true;});}
        Models.User profile=a.me();check(profile.about.equals(target) && profile.firstName.equals("نام نهایی") && profile.lastName.equals("آزمایش"),"bio_editor_preserves_names");
        CompletableFuture<Boolean> cached=new CompletableFuture<>();MessagesStorage storage=MessagesStorage.getInstance(2);storage.getStorageQueue().postRunnable(()->{java.util.HashSet<Long> ids=new java.util.HashSet<>();ids.add(self);cached.complete(storage.loadUserInfos(ids).stream().anyMatch(info->info.id==self && target.equals(info.about)));});check(cached.get(10,TimeUnit.SECONDS),"bio_editor_native_cache_confirmed");
        JSONObject fixture=read("vyrn-profile-ui.json");fixture.put("about",target);write("vyrn-profile-ui.json",fixture);
        passed.put("bio_editor_waits_for_server").put("bio_editor_no_premature_confirmation").put("bio_editor_preserves_names").put("bio_editor_native_cache_confirmed");
        verifyBioLoadRetry(a,b,target);
    }
    private void verifyBioLoadRetry(RestRepository a,RestRepository b,String previous) throws Exception {
        stage="bio_load_retry";waitProfileEvents(a);long self=UserConfig.getInstance(2).getClientUserId();clearFullProfile(self);VyrnBridge bridge=VyrnBridge.forAccount(2);JSONObject original=bridge.repository.vault.load();org.telegram.ui.ChangeBioActivity editor;
        try{
            bridge.repository.vault.save(b.vault.load());editor=openBioEditor();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
            while(!onUi(()->editor.getVisibleDialog()!=null) && System.nanoTime()<deadline)Thread.sleep(50);
            check(onUi(()->editor.getVisibleDialog()!=null && !((android.widget.EditText)editorField(editor,"firstNameField")).isEnabled() && !((android.view.View)editorField(editor,"doneButton")).isEnabled()),"bio_load_failure_visible_locked");
        }finally{bridge.repository.vault.save(original);}
        check(a.me().about.equals(previous),"bio_load_failure_preserves_server");
        onUi(()->{((org.telegram.ui.ActionBar.AlertDialog)editor.getVisibleDialog()).getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick();return true;});long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
        while(!onUi(()->((android.widget.EditText)editorField(editor,"firstNameField")).isEnabled()) && System.nanoTime()<deadline)Thread.sleep(50);
        check(onUi(()->((android.widget.EditText)editorField(editor,"firstNameField")).isEnabled() && ((android.widget.EditText)editorField(editor,"firstNameField")).getText().toString().equals(previous)),"bio_load_retry_restores_real_profile");
        String boundary="🛰".repeat(280);onUi(()->{android.widget.EditText field=(android.widget.EditText)editorField(editor,"firstNameField");field.setText("B".repeat(281));check(field.length()==280,"bio_editor_BMP_limit_280");field.setText(boundary+"X");check(field.getText().toString().equals(boundary),"bio_editor_unicode_limit_280");field.getText().replace(2,4,"Q🛰X",1,4);check(field.getText().toString().equals(boundary),"bio_editor_selected_unicode_replacement");return true;});
        CompletableFuture<Boolean> saved=new CompletableFuture<>();NotificationCenter.NotificationCenterDelegate observer=(id,account,args)->{if(((Long)args[0])==self && boundary.equals(((TLRPC.UserFull)args[1]).about))saved.complete(true);};
        onUi(()->{NotificationCenter.getInstance(2).addObserver(observer,NotificationCenter.userInfoDidLoad);((android.view.View)editorField(editor,"doneButton")).performClick();return true;});
        try{saved.get(30,TimeUnit.SECONDS);}finally{onUi(()->{NotificationCenter.getInstance(2).removeObserver(observer,NotificationCenter.userInfoDidLoad);return true;});}
        check(a.me().about.equals(boundary),"bio_editor_boundary_server_confirmed");JSONObject fixture=read("vyrn-profile-ui.json");fixture.put("about",boundary);write("vyrn-profile-ui.json",fixture);
        passed.put("bio_load_failure_visible_locked").put("bio_load_failure_preserves_server").put("bio_load_retry_restores_real_profile").put("bio_editor_BMP_limit_280").put("bio_editor_unicode_limit_280").put("bio_editor_selected_unicode_replacement").put("bio_editor_boundary_server_confirmed");
    }
    private void enterName(org.telegram.ui.ChangeNameActivity editor,String first,String last) throws Exception {
        onUi(()->{((android.widget.EditText)editorField(editor,"firstNameField")).setText(first);((android.widget.EditText)editorField(editor,"lastNameField")).setText(last);return true;});
    }
    private void verifyNameEditor(RestRepository a) throws Exception {
        stage="original_name_editor";
        onUi(()->{UserConfig.getInstance(2).getCurrentUser().flags&=~4;return true;});
        org.telegram.ui.ChangeNameActivity editor=onUi(()->{
            org.telegram.ui.ActionBar.BaseFragment parent=(org.telegram.ui.ActionBar.BaseFragment)org.telegram.ui.LaunchActivity.class.getMethod("getSafeLastFragment").invoke(null);
            org.telegram.ui.ChangeNameActivity opened=new org.telegram.ui.ChangeNameActivity(null);opened.setCurrentAccount(2);
            check(parent!=null && parent.presentFragment(opened,false,true),"name_editor_presented");return opened;
        });
        String before=onUi(()->UserConfig.getInstance(2).getCurrentUser().first_name);
        enterName(editor,"A".repeat(60),"B".repeat(60));
        onUi(()->{((android.view.View)editorField(editor,"doneButton")).performClick();return true;});
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
        while(!onUi(()->editor.getVisibleDialog()!=null) && System.nanoTime()<deadline)Thread.sleep(50);
        check(onUi(()->editor.getVisibleDialog()!=null && ((android.view.View)editorField(editor,"doneButton")).isEnabled() && !((Boolean)editorField(editor,"vyrnSaving")) && UserConfig.getInstance(2).getCurrentUser().first_name.equals(before)),"name_editor_failed_save_preserves_local");
        check(a.me().firstName.equals("نام جدید"),"name_editor_failed_save_preserves_server");
        onUi(()->{editor.dismissCurrentDialog();return true;});enterName(editor,"نام نهایی","آزمایش");
        CompletableFuture<Boolean> accepted=new CompletableFuture<>();NotificationCenter.NotificationCenterDelegate observer=(id,account,args)->accepted.complete(true);
        onUi(()->{NotificationCenter.getInstance(2).addObserver(observer,NotificationCenter.mainUserInfoChanged);return true;});
        TLRPC.User previous=onUi(()->UserConfig.getInstance(2).getCurrentUser());TLRPC.UserProfilePhoto photo=previous.photo;
        try{
            synchronized(VyrnBridge.forAccount(2).repository.vault.guard()){
                onUi(()->{((android.view.View)editorField(editor,"doneButton")).performClick();check(!((android.view.View)editorField(editor,"doneButton")).isEnabled() && UserConfig.getInstance(2).getCurrentUser().first_name.equals(before),"name_editor_waits_for_server");return true;});
                check(!accepted.isDone(),"name_editor_no_optimistic_confirmation");
            }
            accepted.get(30,TimeUnit.SECONDS);
        }finally{onUi(()->{NotificationCenter.getInstance(2).removeObserver(observer,NotificationCenter.mainUserInfoChanged);return true;});}
        check(onUi(()->UserConfig.getInstance(2).getCurrentUser()==previous && previous.first_name.equals("نام نهایی") && previous.last_name.equals("آزمایش") && (previous.flags&6)==6 && previous.photo==photo),"name_editor_confirmed_preserves_attributes");
        check(a.me().firstName.equals("نام نهایی") && a.me().lastName.equals("آزمایش"),"name_editor_server_confirmed");
        // A queue barrier precedes a real native-cache read; no test-only storage implementation.
        CompletableFuture<Boolean> written=new CompletableFuture<>();MessagesStorage storage=MessagesStorage.getInstance(2);storage.getStorageQueue().postRunnable(()->written.complete(true));written.get(10,TimeUnit.SECONDS);
        TLRPC.User cached=storage.getUserSync(previous.id);check(cached!=null && cached.first_name.equals("نام نهایی") && cached.last_name.equals("آزمایش"),"name_editor_native_user_cache");
        write("vyrn-profile-ui.json",new JSONObject().put("user",previous.id).put("first","نام نهایی").put("last","آزمایش"));
        passed.put("name_editor_presented").put("name_editor_failed_save_preserves_local").put("name_editor_failed_save_preserves_server").put("name_editor_waits_for_server").put("name_editor_no_optimistic_confirmation").put("name_editor_confirmed_preserves_attributes").put("name_editor_server_confirmed").put("name_editor_native_user_cache");
    }
    private void verifyNativeProfile(RestRepository a,RestRepository b) throws Exception {
        stage="native_profile";
        long beforeCursor=VyrnBridge.forAccount(2).syncCursor();
        TL_account.updateProfile names=new TL_account.updateProfile();names.flags=3;names.first_name="نام";names.last_name="خانوادگی";
        TLRPC.User result=(TLRPC.User)nativeResponse(names);check(result.first_name.equals("نام") && result.last_name.equals("خانوادگی") && result.id==UserConfig.getInstance(2).getClientUserId(),"native_profile_separate_names");
        TL_account.updateProfile first=new TL_account.updateProfile();first.flags=1;first.first_name="نام جدید";
        result=(TLRPC.User)nativeResponse(first);check(result.first_name.equals("نام جدید") && result.last_name.equals("خانوادگی"),"native_profile_partial_name");
        TL_account.updateProfile about=new TL_account.updateProfile();about.flags=4;about.about="دربارهٔ VYRN";nativeResponse(about);
        Models.User current=a.me();check(current.firstName.equals("نام جدید") && current.lastName.equals("خانوادگی") && current.about.equals("دربارهٔ VYRN"),"native_profile_bio_preserves_names");
        TL_account.updateProfile invalid=new TL_account.updateProfile();invalid.flags=3;invalid.first_name=" ";invalid.last_name="bad";check(editFailure(invalid)!=null && a.me().lastName.equals("خانوادگی"),"native_invalid_profile_preserves_server");
        VyrnBridge bridge=VyrnBridge.forAccount(2);JSONObject saved=bridge.repository.vault.load();try{bridge.repository.vault.save(b.vault.load());check(editFailure(names)!=null,"native_profile_origin_guard");}finally{bridge.repository.vault.save(saved);}
        long target=((JSONObject)a.request("GET","/v1/sync?cursor="+beforeCursor,null,true)).getLong("cursor");long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(45);while(System.nanoTime()<deadline&&(bridge.syncCursor()<target||bridge.syncPending()!=0))Thread.sleep(100);check(bridge.syncCursor()>=target&&bridge.syncPending()==0&&bridge.syncDead()==0,"prior_profile_events_applied");
        passed.put("native_profile_separate_names").put("native_profile_partial_name").put("native_profile_bio_preserves_names").put("native_invalid_profile_preserves_server").put("native_profile_origin_guard");
    }
    private TLObject nativeResponse(TLObject request) throws Exception {
        CompletableFuture<TLObject> result=new CompletableFuture<>();ConnectionsManager.getInstance(2).sendRequest(request,(value,error)->{if(error!=null){stage="native_request_status_"+error.code;result.completeExceptionally(new AssertionError());}else result.complete(value);});return result.get(30,TimeUnit.SECONDS);
    }
    private void waitProfileEvents(RestRepository viewer) throws Exception {
        VyrnBridge bridge=VyrnBridge.forAccount(2);long target=((JSONObject)viewer.request("GET","/v1/sync?cursor="+bridge.syncCursor(),null,true)).getLong("cursor");long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(45);
        while(System.nanoTime()<deadline&&(bridge.syncCursor()<target||bridge.syncPending()!=0))Thread.sleep(100);
        check(bridge.syncCursor()>=target&&bridge.syncPending()==0&&bridge.syncDead()==0,"prior_profile_events_applied");
    }
    private void verifyNativeSessions(RestRepository a,RestRepository b,JSONObject fixture) throws Exception {
        stage="native_sessions";RestRepository other=repo(9020);other.vault.clear();
        JSONObject login=new JSONObject().put("email",fixture.getString("email").replace("@","a@")).put("password",fixture.getString("password")).put("device_id",other.vault.device()).put("device_name","دستگاه دوم VYRN");
        other.vault.save((JSONObject)other.request("POST","/v1/auth/login",login,false));
        TL_account.authorizations listed=(TL_account.authorizations)nativeResponse(new TL_account.getAuthorizations());
        check(listed.authorizations.size()==2 && listed.authorization_ttl_days==30,"native_sessions_list");
        TLRPC.TL_authorization current=null,second=null;
        for(TLRPC.TL_authorization item:listed.authorizations){if(item.current)current=item;else second=item;}
        check(current!=null && (current.flags&1)!=0 && second!=null && second.device_model.equals("دستگاه دوم VYRN") && second.date_created>0 && second.date_active>0 && second.ip.isEmpty(),"native_sessions_metadata");
        TL_account.authorizations repeated=(TL_account.authorizations)nativeResponse(new TL_account.getAuthorizations());
        final long secondHash=second.hash;check(repeated.authorizations.stream().anyMatch(item->!item.current && item.hash==secondHash),"native_session_hash_stable");
        TL_account.resetAuthorization own=new TL_account.resetAuthorization();own.hash=current.hash;
        check(editFailure(own)!=null && a.me()!=null,"native_current_session_revoke_denied");
        TL_account.resetAuthorization unknown=new TL_account.resetAuthorization();unknown.hash=Long.MAX_VALUE;
        check(editFailure(unknown)!=null && other.me()!=null,"native_unknown_session_revoke_denied");
        VyrnBridge bridge=VyrnBridge.forAccount(2);JSONObject saved=bridge.repository.vault.load();
        try{bridge.repository.vault.save(b.vault.load());TL_account.resetAuthorization switched=new TL_account.resetAuthorization();switched.hash=secondHash;check(editFailure(switched)!=null && other.me()!=null,"native_session_account_binding");}finally{bridge.repository.vault.save(saved);}
        TL_account.resetAuthorization revoke=new TL_account.resetAuthorization();revoke.hash=secondHash;
        check(nativeResponse(revoke) instanceof TLRPC.TL_boolTrue,"native_other_session_revoked");
        boolean rejected=false;try{other.me();}catch(RestRepository.ApiException expected){rejected=expected.status==401;}
        check(rejected && a.me()!=null,"native_revoked_device_cannot_refresh");
        listed=(TL_account.authorizations)nativeResponse(new TL_account.getAuthorizations());check(listed.authorizations.size()==1 && listed.authorizations.get(0).current,"native_sessions_after_revoke");other.vault.clear();
        RestRepository third=repo(9021);third.vault.clear();
        other.vault.save((JSONObject)other.request("POST","/v1/auth/login",login,false));
        JSONObject thirdLogin=new JSONObject(login.toString()).put("device_id",third.vault.device()).put("device_name","دستگاه سوم VYRN");
        third.vault.save((JSONObject)third.request("POST","/v1/auth/login",thirdLogin,false));
        listed=(TL_account.authorizations)nativeResponse(new TL_account.getAuthorizations());check(listed.authorizations.size()==3,"native_three_device_sessions");
        check(nativeResponse(new TLRPC.TL_auth_resetAuthorizations()) instanceof TLRPC.TL_boolTrue,"native_revoke_all_other_sessions");
        for(RestRepository device:new RestRepository[]{other,third}){boolean denied=false;try{device.me();}catch(RestRepository.ApiException expected){denied=expected.status==401;}check(denied,"native_bulk_revoked_devices_cannot_refresh");device.vault.clear();}
        listed=(TL_account.authorizations)nativeResponse(new TL_account.getAuthorizations());check(listed.authorizations.size()==1 && listed.authorizations.get(0).current && a.me()!=null && b.me()!=null,"native_bulk_preserves_current_and_other_user");
        check(nativeResponse(new TLRPC.TL_auth_resetAuthorizations()) instanceof TLRPC.TL_boolTrue,"native_bulk_revoke_idempotent");
        passed.put("native_three_device_sessions").put("native_revoke_all_other_sessions").put("native_bulk_revoked_devices_cannot_refresh").put("native_bulk_preserves_current_and_other_user").put("native_bulk_revoke_idempotent");
        passed.put("native_sessions_list").put("native_sessions_metadata").put("native_session_hash_stable").put("native_current_session_revoke_denied").put("native_unknown_session_revoke_denied").put("native_session_account_binding").put("native_other_session_revoked").put("native_revoked_device_cannot_refresh").put("native_sessions_after_revoke");
    }
    private TLRPC.TL_error editFailure(TLObject request) throws Exception {
        CompletableFuture<TLRPC.TL_error> result=new CompletableFuture<>();ConnectionsManager.getInstance(2).sendRequest(request,(value,error)->result.complete(error));return result.get(30,TimeUnit.SECONDS);
    }
    private void verifyNativeTextEdit(RestRepository repository,String group,String user) throws Exception {
        stage="native_text_edit";String server=repository.send(group,UUID.randomUUID().toString(),"ویرایش اولیه");MessageObject viewed=observedText(group,server);VyrnBridge.captureEdit(2,viewed);
        CompletableFuture<Integer> queued=new CompletableFuture<>();AndroidUtilities.runOnUIThread(()->{try{org.telegram.ui.ActionBar.BaseFragment fragment=(org.telegram.ui.ActionBar.BaseFragment)Class.forName("org.telegram.ui.LaunchActivity").getMethod("getSafeLastFragment").invoke(null);queued.complete(SendMessagesHelper.getInstance(2).editMessage(viewed,"ویرایش اصلی",false,fragment,null,0,0));}catch(Exception failure){queued.completeExceptionally(failure);}});
        check(queued.get(15,TimeUnit.SECONDS)!=0,"original_text_edit_helper_dispatched");
        long deadline=System.currentTimeMillis()+30000;Models.Message edited=repository.message(server);while(edited.revision==1&&System.currentTimeMillis()<deadline){Thread.sleep(100);edited=repository.message(server);}
        check(edited.revision==2&&edited.text.equals("ویرایش اصلی")&&edited.editedDate>0,"native_text_edit_committed");
        TLRPC.Message cached=null;while(System.currentTimeMillis()<deadline){cached=MessagesStorage.getInstance(2).getMessage(viewed.getDialogId(),viewed.getId());if(cached!=null&&cached.message.equals(edited.text))break;Thread.sleep(100);}
        check(cached!=null&&cached.message.equals(edited.text)&&cached.edit_date==edited.editedDate,"native_text_edit_cached");passed.put("original_text_edit_helper_dispatched").put("native_text_edit_committed").put("native_text_edit_cached");
        MessageObject stale=observedText(group,server);VyrnBridge.captureEdit(2,stale);
        repository.editText(user,server,2,"temporary other device");repository.editText(user,server,3,edited.text);
        observedText(group,server); // A newer equal-text observation must not replace the frozen composer basis.
        TLRPC.TL_messages_editMessage request=VyrnBridge.editRequest(2,stale);request.id=stale.getId();request.peer=MessagesController.getInstance(2).getInputPeer(stale.getDialogId());request.flags=2048;request.message="must not overwrite";
        TLRPC.TL_error conflict=editFailure(request);check(conflict!=null&&conflict.code==409&&conflict.text.equals("VYRN_EDIT_CONFLICT")&&repository.message(server).revision==4&&repository.message(server).text.equals(edited.text),"native_edit_ABA_conflict");passed.put("native_edit_ABA_conflict");
        MessageObject cachedStale=new MessageObject(2,cached,false,false);VyrnBridge.captureEdit(2,cachedStale);request=VyrnBridge.editRequest(2,cachedStale);request.id=cachedStale.getId();request.peer=MessagesController.getInstance(2).getInputPeer(cachedStale.getDialogId());request.flags=2048;request.message="stale cache";
        conflict=editFailure(request);check(conflict!=null&&conflict.text.equals("VYRN_EDIT_CONFLICT")&&repository.message(server).revision==4,"cached_exact_revision_conflict");passed.put("cached_exact_revision_conflict");
        verifyLocalEditParams(cached);
        cached.params=null; // Simulate a pre-provenance cache row, not a newly mapped message.
        MessageObject unknown=new MessageObject(2,cached,false,false);VyrnBridge.captureEdit(2,unknown);request=VyrnBridge.editRequest(2,unknown);request.id=unknown.getId();request.peer=MessagesController.getInstance(2).getInputPeer(unknown.getDialogId());request.flags=2048;request.message="unknown basis";
        TLRPC.TL_error missing=editFailure(request);check(missing!=null&&missing.text.equals("VYRN_EDIT_RELOAD_REQUIRED")&&repository.message(server).revision==4,"cached_edit_requires_reload");passed.put("cached_edit_requires_reload");
        MessageObject current=observedText(group,server);VyrnBridge.captureEdit(2,current);request=VyrnBridge.editRequest(2,current);request.id=current.getId();TLRPC.TL_inputPeerChat wrong=new TLRPC.TL_inputPeerChat();wrong.chat_id=-current.getDialogId()+1;request.peer=wrong;request.flags=2048;request.message="wrong destination";
        check(editFailure(request)!=null&&repository.message(server).revision==4,"edit_destination_bound");passed.put("edit_destination_bound");
        request=VyrnBridge.editRequest(1,current);request.id=current.getId();request.peer=MessagesController.getInstance(2).getInputPeer(current.getDialogId());request.flags=2048;request.message="wrong account";
        check(editFailure(request)!=null&&repository.message(server).revision==4,"edit_account_bound");passed.put("edit_account_bound");
        long snapshotDeadline=System.currentTimeMillis()+30000;boolean persisted=false;while(System.currentTimeMillis()<snapshotDeadline){TLRPC.Message latest=MessagesStorage.getInstance(2).getMessage(current.getDialogId(),current.getId());if(latest!=null&&latest.params!=null){String provenance=latest.params.get(VyrnBridge.EDIT_SNAPSHOT);if(provenance!=null&&new JSONObject(provenance).getInt("revision")==4){persisted=true;break;}}Thread.sleep(100);}check(persisted,"latest_edit_provenance_persisted");passed.put("latest_edit_provenance_persisted");
        write("vyrn-edit-test.json",new JSONObject().put("server",server).put("dialog",current.getDialogId()).put("ordinal",current.getId()).put("text",edited.text));
        String deleted=repository.send(group,UUID.randomUUID().toString(),"Deleted during editing");MessageObject removed=observedText(group,deleted);VyrnBridge.captureEdit(2,removed);repository.request("DELETE","/v1/messages/"+deleted,null,true);request=VyrnBridge.editRequest(2,removed);request.id=removed.getId();request.peer=MessagesController.getInstance(2).getInputPeer(removed.getDialogId());request.flags=2048;request.message="must not resurrect";
        check(editFailure(request)!=null&&repository.message(deleted).deleted,"deleted_edit_not_revived");passed.put("deleted_edit_not_revived");
    }
    private void verifyLocalEditParams(TLRPC.Message cached) throws Exception {
        TLRPC.TL_message legacy=new TLRPC.TL_message();legacy.originalLanguage="fa";org.telegram.tgnet.NativeByteBuffer encoded=org.telegram.messenger.MessageCustomParamsHelper.writeLocalParams(legacy);encoded.position(0);TLRPC.TL_message decoded=new TLRPC.TL_message();decoded.params=new java.util.HashMap<>(cached.params);org.telegram.messenger.MessageCustomParamsHelper.readLocalParams(decoded,encoded);encoded.reuse();
        check(decoded.originalLanguage.equals("fa")&&(decoded.params==null||!decoded.params.containsKey(VyrnBridge.EDIT_SNAPSHOT)),"legacy_local_params_decode");passed.put("legacy_local_params_decode");
        TLRPC.TL_message altered=new TLRPC.TL_message();altered.id=cached.id;altered.peer_id=cached.peer_id;altered.from_id=cached.from_id;altered.date=cached.date;altered.edit_date=cached.edit_date;altered.out=cached.out;altered.media=new TLRPC.TL_messageMediaEmpty();altered.message="altered local content";altered.params=new java.util.HashMap<>(cached.params);
        MessageObject object=new MessageObject(2,altered,false,false);VyrnBridge.captureEdit(2,object);TLRPC.TL_messages_editMessage request=VyrnBridge.editRequest(2,object);request.id=object.getId();request.peer=MessagesController.getInstance(2).getInputPeer(object.getDialogId());request.flags=2048;request.message="must not use unrelated provenance";
        TLRPC.TL_error missing=editFailure(request);check(missing!=null&&missing.text.equals("VYRN_EDIT_RELOAD_REQUIRED"),"cached_edit_content_bound");passed.put("cached_edit_content_bound");
    }
    private void deleteThroughController(long dialog,int id) throws Exception {
        CompletableFuture<Boolean> confirmed=new CompletableFuture<>();NotificationCenter center=NotificationCenter.getInstance(2);
        NotificationCenter.NotificationCenterDelegate observer=(event,account,args)->{if(event==NotificationCenter.messagesDeleted&&((ArrayList<Integer>)args[0]).contains(id))confirmed.complete(true);};
        AndroidUtilities.runOnUIThread(()->{center.addObserver(observer,NotificationCenter.messagesDeleted);ArrayList<Integer> ids=new ArrayList<>();ids.add(id);MessagesController.getInstance(2).deleteMessages(ids,null,null,dialog,0,true,0);});
        try{confirmed.get(30,TimeUnit.SECONDS);}finally{AndroidUtilities.runOnUIThread(()->center.removeObserver(observer,NotificationCenter.messagesDeleted));}
        long deadline=System.currentTimeMillis()+30000;while(MessagesStorage.getInstance(2).getMessage(dialog,id)!=null&&System.currentTimeMillis()<deadline)Thread.sleep(100);
        check(MessagesStorage.getInstance(2).getMessage(dialog,id)==null,"confirmed_delete_native_cache");
    }
    private void verifyNativeDeletion(RestRepository a,RestRepository b,String otherGroup,String user) throws Exception {
        stage="native_atomic_deletion";String group=b.createGroup("حذف اتمیک",java.util.Collections.singletonList(user),0).id;
        String own=a.send(group,UUID.randomUUID().toString(),"Own deletion"),other=b.send(group,UUID.randomUUID().toString(),"Owner deletion");MessageObject mine=observedText(group,own),theirs=observedText(group,other);long dialog=mine.getDialogId();
        ArrayList<TLRPC.Message> rows=new ArrayList<>();rows.add(mine.messageOwner);rows.add(theirs.messageOwner);MessagesStorage storage=MessagesStorage.getInstance(2);storage.putMessages(rows,true,true,false,0,0,0);CompletableFuture<Boolean> cached=new CompletableFuture<>();storage.getStorageQueue().postRunnable(()->cached.complete(true));cached.get(15,TimeUnit.SECONDS);
        ArrayList<Integer> ids=new ArrayList<>();ids.add(mine.getId());ids.add(theirs.getId());
        check(!VyrnBridge.deleteMessages(2,dialog,ids,true,0,0,false).get(30,TimeUnit.SECONDS)&&!a.message(own).deleted&&!a.message(other).deleted,"native_delete_batch_permission_atomic");
        check(storage.getMessage(dialog,mine.getId())!=null&&storage.getMessage(dialog,theirs.getId())!=null,"denied_delete_keeps_native_cache");passed.put("native_delete_batch_permission_atomic").put("denied_delete_keeps_native_cache");
        String foreign=a.send(otherGroup,UUID.randomUUID().toString(),"Different group");MessageObject wrong=observedText(otherGroup,foreign);ArrayList<Integer> mixed=new ArrayList<>();mixed.add(mine.getId());mixed.add(wrong.getId());
        check(!VyrnBridge.deleteMessages(2,dialog,mixed,true,0,0,false).get(30,TimeUnit.SECONDS)&&!a.message(own).deleted&&!a.message(foreign).deleted,"native_delete_conversation_bound");passed.put("native_delete_conversation_bound");
        ids=new ArrayList<>();ids.add(mine.getId());check(!VyrnBridge.deleteMessages(2,dialog,ids,false,0,0,false).get(30,TimeUnit.SECONDS)&&!a.message(own).deleted&&storage.getMessage(dialog,mine.getId())!=null,"delete_for_me_not_reinterpreted");passed.put("delete_for_me_not_reinterpreted");
        deleteThroughController(dialog,mine.getId());check(a.message(own).deleted&&!a.message(other).deleted,"MessagesController_authorized_delete");passed.put("MessagesController_authorized_delete").put("confirmed_delete_native_cache");
        verifyLiveDialogFallback(dialog,mine.messageOwner,theirs.messageOwner);
        check(VyrnBridge.deleteMessages(2,dialog,ids,true,0,0,false).get(30,TimeUnit.SECONDS)&&a.message(own).revision==2,"native_delete_retry_idempotent");passed.put("native_delete_retry_idempotent");
        b.setMemberRole(group,user,true);deleteThroughController(dialog,theirs.getId());check(a.message(other).deleted,"native_admin_delete_other_sender");passed.put("native_admin_delete_other_sender");
        verifyDeletedCacheWriters(dialog,mine.messageOwner,"prefetched");
        TLRPC.TL_messages_getHistory history=new TLRPC.TL_messages_getHistory();history.peer=MessagesController.getInstance(2).getInputPeer(dialog);history.limit=100;CompletableFuture<TLObject> historyResult=new CompletableFuture<>();ConnectionsManager.getInstance(2).sendRequest(history,(value,error)->{if(error==null)historyResult.complete(value);else historyResult.completeExceptionally(new AssertionError());});check(((TLRPC.messages_Messages)historyResult.get(30,TimeUnit.SECONDS)).messages.isEmpty(),"native_history_excludes_tombstones");passed.put("native_history_excludes_tombstones");
        CompletableFuture<TLObject> dialogsResult=new CompletableFuture<>();ConnectionsManager.getInstance(2).sendRequest(new TLRPC.TL_messages_getDialogs(),(value,error)->{if(error==null)dialogsResult.complete(value);else dialogsResult.completeExceptionally(new AssertionError());});TLRPC.TL_messages_dialogs dialogs=(TLRPC.TL_messages_dialogs)dialogsResult.get(30,TimeUnit.SECONDS);check(dialogs.dialogs.stream().anyMatch(item->item.id==dialog&&item.top_message==0)&&dialogs.messages.stream().noneMatch(item->item.id==mine.getId()||item.id==theirs.getId()),"native_deleted_dialog_no_blank_top");passed.put("native_deleted_dialog_no_blank_top");
        org.telegram.tgnet.SerializedData staleBytes=new org.telegram.tgnet.SerializedData();mine.messageOwner.serializeToStream(staleBytes);
        write("vyrn-delete-test.json",new JSONObject().put("group",group).put("dialog",dialog).put("own",own).put("other",other).put("own_id",mine.getId()).put("other_id",theirs.getId()).put("prefetched",android.util.Base64.encodeToString(staleBytes.toByteArray(),android.util.Base64.NO_WRAP)));staleBytes.cleanup();
    }
    private void verifyQueuedDeletion(RestRepository repository,String group,boolean edit) throws Exception {
        stage=edit?"queued_edit_deletion":"queued_new_deletion";String server=repository.send(group,UUID.randomUUID().toString(),"Queued before deletion");MessageObject prefetched=observedText(group,server);long dialog=prefetched.getDialogId();
        NotificationCenter center=NotificationCenter.getInstance(2);java.util.concurrent.CountDownLatch release=new java.util.concurrent.CountDownLatch(1);CompletableFuture<Boolean> held=new CompletableFuture<>(),deleted=new CompletableFuture<>();java.util.concurrent.atomic.AtomicBoolean appeared=new java.util.concurrent.atomic.AtomicBoolean();
        NotificationCenter.NotificationCenterDelegate observer=(event,account,args)->{if(event==NotificationCenter.messagesDeleted&&((ArrayList<Integer>)args[0]).contains(prefetched.getId()))deleted.complete(true);if((event==NotificationCenter.didReceiveNewMessages||event==NotificationCenter.replaceMessagesObjects)&&((Long)args[0])==dialog)for(MessageObject object:(ArrayList<MessageObject>)args[1])if(object.getId()==prefetched.getId())appeared.set(true);};
        AndroidUtilities.runOnUIThread(()->{center.addObserver(observer,NotificationCenter.messagesDeleted);center.addObserver(observer,NotificationCenter.didReceiveNewMessages);center.addObserver(observer,NotificationCenter.replaceMessagesObjects);held.complete(true);try{release.await(15,TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}});
        try{
            held.get(10,TimeUnit.SECONDS);ArrayList<TLRPC.Update> updates=new ArrayList<>();if(edit){TL_update.TL_updateEditMessage update=new TL_update.TL_updateEditMessage();update.message=prefetched.messageOwner;updates.add(update);}else{TL_update.TL_updateNewMessage update=new TL_update.TL_updateNewMessage();update.message=prefetched.messageOwner;updates.add(update);}
            CompletableFuture<Boolean> applied=new CompletableFuture<>();Utilities.stageQueue.postRunnable(()->applied.complete(MessagesController.getInstance(2).processUpdateArray(updates,null,null,true,prefetched.messageOwner.date)));check(applied.get(10,TimeUnit.SECONDS)&&!(updates.get(0) instanceof TL_update.TL_updateDeleteMessages),"live_update_processed_before_deletion");
            MessagesController.getInstance(2).processLoadedDialogs(staleDialogs(dialog,prefetched.messageOwner),null,null,0,0,1,3,false,false,false);awaitStage();
            repository.request("DELETE","/v1/messages/"+server,null,true);
            MessagesStorage storage=MessagesStorage.getInstance(2);boolean fenced=false;long deadline=System.currentTimeMillis()+10000;
            while(!fenced&&System.currentTimeMillis()<deadline){CompletableFuture<Boolean> stored=new CompletableFuture<>();storage.getStorageQueue().postRunnable(()->{try{org.telegram.SQLite.SQLiteCursor cursor=storage.getDatabase().queryFinalized("SELECT 1 FROM vyrn_deleted_messages WHERE uid=? AND mid=?",dialog,prefetched.getId());try{stored.complete(cursor.next());}finally{cursor.dispose();}}catch(Exception failure){stored.completeExceptionally(failure);}});fenced=stored.get(5,TimeUnit.SECONDS);if(!fenced)Thread.sleep(50);}
            check(fenced,"authoritative_fence_before_UI_release");release.countDown();deleted.get(15,TimeUnit.SECONDS);CompletableFuture<Boolean> displayed=new CompletableFuture<>();AndroidUtilities.runOnUIThread(()->displayed.complete(true));displayed.get(10,TimeUnit.SECONDS);awaitStorage(storage);
            String name=edit?"queued_edit_not_revived":"queued_new_not_revived";check(!appeared.get()&&storage.getMessage(dialog,prefetched.getId())==null,name);passed.put(name);
            CompletableFuture<Boolean> top=new CompletableFuture<>();AndroidUtilities.runOnUIThread(()->{top.complete(nativeDialogOmits(dialog,prefetched.getId(),false));});String dialogCheck=edit?"queued_edit_dialog_not_revived":"queued_new_dialog_not_revived";check(top.get(10,TimeUnit.SECONDS),dialogCheck);passed.put(dialogCheck);
        }finally{release.countDown();AndroidUtilities.runOnUIThread(()->{center.removeObserver(observer,NotificationCenter.messagesDeleted);center.removeObserver(observer,NotificationCenter.didReceiveNewMessages);center.removeObserver(observer,NotificationCenter.replaceMessagesObjects);});}
    }
    private void verifyDeletedCacheWriters(long dialog,TLRPC.Message stale,String prefix) throws Exception {
        MessagesStorage storage=MessagesStorage.getInstance(2);ArrayList<TLRPC.Message> rows=new ArrayList<>();rows.add(stale);
        storage.putMessages(rows,true,true,false,0,0,0);awaitStorage(storage);
        check(storage.getMessage(dialog,stale.id)==null,prefix+"_array_cannot_revive");passed.put(prefix+"_array_cannot_revive");
        TLRPC.TL_messages_messages history=new TLRPC.TL_messages_messages();history.messages.add(stale);
        storage.putMessages(history,dialog,0,0,false,0,0);awaitStorage(storage);
        check(storage.getMessage(dialog,stale.id)==null,prefix+"_history_cannot_revive");passed.put(prefix+"_history_cannot_revive");
        storage.replaceMessageIfExists(stale,new ArrayList<>(),new ArrayList<>(),false);awaitStorage(storage);
        check(storage.getMessage(dialog,stale.id)==null,prefix+"_edit_cannot_revive");passed.put(prefix+"_edit_cannot_revive");
        verifyDeletedNativeUpdate(dialog,stale,prefix,false);verifyDeletedNativeUpdate(dialog,stale,prefix,true);
        verifyDeletedHistoryNotification(dialog,stale,prefix);
        verifyDeletedDialogs(dialog,stale,prefix);
    }
    private TLRPC.TL_messages_dialogs staleDialogs(long dialog,TLRPC.Message stale){
        TLRPC.TL_messages_dialogs result=new TLRPC.TL_messages_dialogs();result.messages.add(stale);TLRPC.TL_dialog item=new TLRPC.TL_dialog();item.id=dialog;item.peer=stale.peer_id;item.top_message=stale.id;item.last_message_date=stale.date;item.notify_settings=new TLRPC.TL_peerNotifySettings();result.dialogs.add(item);return result;
    }
    private void verifyLiveDialogFallback(long dialog,TLRPC.Message deleted,TLRPC.Message live) throws Exception {
        MessagesStorage storage=MessagesStorage.getInstance(2);storage.putDialogs(staleDialogs(dialog,deleted),0);awaitStorage(storage);CompletableFuture<Boolean> stored=new CompletableFuture<>();
        storage.getStorageQueue().postRunnable(()->{try{org.telegram.SQLite.SQLiteCursor cursor=storage.getDatabase().queryFinalized("SELECT last_mid,date FROM dialogs WHERE did=?",dialog);try{stored.complete(cursor.next()&&cursor.longValue(0)==live.id&&cursor.intValue(1)==live.date);}finally{cursor.dispose();}}catch(Exception failure){stored.completeExceptionally(failure);}});check(stored.get(10,TimeUnit.SECONDS),"dialog_cache_keeps_surviving_message");passed.put("dialog_cache_keeps_surviving_message");
        MessagesController controller=MessagesController.getInstance(2);controller.processLoadedDialogs(staleDialogs(dialog,live),null,null,0,0,1,3,false,false,false);awaitStage();CompletableFuture<Boolean> ready=new CompletableFuture<>();AndroidUtilities.runOnUIThread(()->ready.complete(controller.getDialog(dialog)!=null&&controller.getDialog(dialog).top_message==live.id));check(ready.get(10,TimeUnit.SECONDS),"live_dialog_loaded");
        controller.processLoadedDialogs(staleDialogs(dialog,deleted),null,null,0,0,1,3,false,false,false);awaitStage();CompletableFuture<Boolean> visible=new CompletableFuture<>();AndroidUtilities.runOnUIThread(()->visible.complete(nativeDialogOmits(dialog,deleted.id,false)&&controller.getDialog(dialog).top_message==live.id));check(visible.get(10,TimeUnit.SECONDS),"dialog_UI_keeps_surviving_message");passed.put("dialog_UI_keeps_surviving_message");
    }
    private boolean nativeDialogOmits(long dialog,int deleted,boolean empty){
        MessagesController controller=MessagesController.getInstance(2);TLRPC.Dialog item=controller.getDialog(dialog);
        try{Object table=MessagesController.class.getField("dialogMessage").get(controller);ArrayList<MessageObject> rows=(ArrayList<MessageObject>)table.getClass().getMethod("get",long.class).invoke(table,dialog);
            return item!=null&&(empty?item.top_message==0:item.top_message!=deleted)&&(rows==null||rows.stream().noneMatch(object->object.getId()==deleted));
        }catch(ReflectiveOperationException failure){throw new IllegalStateException(failure);}
    }
    private void awaitStage() throws Exception {
        CompletableFuture<Boolean> done=new CompletableFuture<>();Utilities.stageQueue.postRunnable(()->done.complete(true));done.get(15,TimeUnit.SECONDS);
    }
    private void verifyDeletedDialogs(long dialog,TLRPC.Message stale,String prefix) throws Exception {
        MessagesStorage storage=MessagesStorage.getInstance(2);storage.putDialogs(staleDialogs(dialog,stale),0);awaitStorage(storage);CompletableFuture<Boolean> stored=new CompletableFuture<>();
        storage.getStorageQueue().postRunnable(()->{try{org.telegram.SQLite.SQLiteCursor cursor=storage.getDatabase().queryFinalized("SELECT last_mid,date FROM dialogs WHERE did=?",dialog);try{stored.complete(cursor.next()&&cursor.longValue(0)==0&&cursor.intValue(1)==0);}finally{cursor.dispose();}}catch(Exception error){stored.completeExceptionally(error);}});String cache=prefix+"_dialog_cache_rejects_deleted_top";check(stored.get(10,TimeUnit.SECONDS),cache);passed.put(cache);
        for(boolean update:new boolean[]{false,true}){
            MessagesController controller=MessagesController.getInstance(2);if(update)controller.processDialogsUpdate(staleDialogs(dialog,stale),null,false);else controller.processLoadedDialogs(staleDialogs(dialog,stale),null,null,0,0,1,3,false,false,false);awaitStage();
            CompletableFuture<Boolean> visible=new CompletableFuture<>();AndroidUtilities.runOnUIThread(()->{visible.complete(nativeDialogOmits(dialog,stale.id,true));});String name=prefix+(update?"_dialog_update_rejects_deleted_top":"_dialog_load_rejects_deleted_top");check(visible.get(15,TimeUnit.SECONDS),name);passed.put(name);
        }
    }
    private void verifyDeletedHistoryNotification(long dialog,TLRPC.Message stale,String prefix) throws Exception {
        org.telegram.tgnet.SerializedData encoded=new org.telegram.tgnet.SerializedData();stale.serializeToStream(encoded);org.telegram.tgnet.SerializedData decoded=new org.telegram.tgnet.SerializedData(encoded.toByteArray());TLRPC.Message pending=TLRPC.Message.TLdeserialize(decoded,decoded.readInt32(true),true);encoded.cleanup();decoded.cleanup();pending.id=-91030;pending.dialog_id=dialog;pending.send_state=MessageObject.MESSAGE_SEND_STATE_SENDING;
        ArrayList<MessageObject> objects=new ArrayList<>();objects.add(new MessageObject(2,stale,false,false));objects.add(new MessageObject(2,pending,false,false));
        java.util.concurrent.CountDownLatch release=new java.util.concurrent.CountDownLatch(1);CompletableFuture<Boolean> blocked=new CompletableFuture<>();
        MessagesStorage.getInstance(2).getStorageQueue().postRunnable(()->{blocked.complete(true);try{release.await(10,TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}});blocked.get(5,TimeUnit.SECONDS);
        NotificationCenter center=NotificationCenter.getInstance(2);CompletableFuture<Boolean> delivered=new CompletableFuture<>();NotificationCenter.NotificationCenterDelegate observer=(event,account,args)->{if((Long)args[0]==dialog){ArrayList<MessageObject> received=(ArrayList<MessageObject>)args[2];delivered.complete(received.size()==1&&received.get(0).getId()==pending.id&&(Integer)args[1]==2&&release.getCount()==1);}};
        AndroidUtilities.runOnUIThread(()->{center.addObserver(observer,NotificationCenter.messagesDidLoad);center.postNotificationName(NotificationCenter.messagesDidLoad,dialog,2,objects,true,0,stale.id,0,stale.date,0,true,0,0,stale.id,0,0);});
        try{String name=prefix+"_late_history_preserves_pending";check(delivered.get(3,TimeUnit.SECONDS),name);passed.put(name).put(prefix+"_UI_does_not_wait_for_storage");}
        finally{release.countDown();AndroidUtilities.runOnUIThread(()->center.removeObserver(observer,NotificationCenter.messagesDidLoad));}
    }
    private void verifyDeletedNativeUpdate(long dialog,TLRPC.Message stale,String prefix,boolean edit) throws Exception {
        NotificationCenter center=NotificationCenter.getInstance(2);CompletableFuture<Boolean> deleted=new CompletableFuture<>();java.util.concurrent.atomic.AtomicBoolean appeared=new java.util.concurrent.atomic.AtomicBoolean();
        NotificationCenter.NotificationCenterDelegate observer=(event,account,args)->{
            if(event==NotificationCenter.messagesDeleted&&((ArrayList<Integer>)args[0]).contains(stale.id))deleted.complete(true);
            if((event==NotificationCenter.didReceiveNewMessages||event==NotificationCenter.replaceMessagesObjects)&&((Long)args[0])==dialog)for(MessageObject object:(ArrayList<MessageObject>)args[1])if(object.getId()==stale.id)appeared.set(true);
        };
        CompletableFuture<Boolean> registered=new CompletableFuture<>();AndroidUtilities.runOnUIThread(()->{center.addObserver(observer,NotificationCenter.messagesDeleted);center.addObserver(observer,NotificationCenter.didReceiveNewMessages);center.addObserver(observer,NotificationCenter.replaceMessagesObjects);registered.complete(true);});registered.get(10,TimeUnit.SECONDS);
        try{
            ArrayList<TLRPC.Update> updates=new ArrayList<>();if(edit){TL_update.TL_updateEditMessage update=new TL_update.TL_updateEditMessage();update.message=stale;updates.add(update);}else{TL_update.TL_updateNewMessage update=new TL_update.TL_updateNewMessage();update.message=stale;updates.add(update);}
            CompletableFuture<Boolean> applied=new CompletableFuture<>();Utilities.stageQueue.postRunnable(()->applied.complete(MessagesController.getInstance(2).processUpdateArray(updates,null,null,true,stale.date)));
            check(applied.get(20,TimeUnit.SECONDS),"native_stale_update_processed");deleted.get(20,TimeUnit.SECONDS);awaitStorage(MessagesStorage.getInstance(2));
            CompletableFuture<Boolean> displayed=new CompletableFuture<>();AndroidUtilities.runOnUIThread(()->displayed.complete(true));displayed.get(10,TimeUnit.SECONDS);
            String name=prefix+(edit?"_native_edit_deleted":"_native_new_deleted");check(!appeared.get()&&MessagesStorage.getInstance(2).getMessage(dialog,stale.id)==null,name);passed.put(name);
        }finally{AndroidUtilities.runOnUIThread(()->{center.removeObserver(observer,NotificationCenter.messagesDeleted);center.removeObserver(observer,NotificationCenter.didReceiveNewMessages);center.removeObserver(observer,NotificationCenter.replaceMessagesObjects);});}
    }
    private void awaitStorage(MessagesStorage storage) throws Exception {
        CompletableFuture<Boolean> done=new CompletableFuture<>();storage.getStorageQueue().postRunnable(()->done.complete(true));done.get(20,TimeUnit.SECONDS);
    }
    private String createGroupThroughTelegram(RestRepository a,RestRepository b,String memberId) throws Exception {
        stage="Telegram_display_name_discovery";
        String name="عضو "+UUID.randomUUID();b.request("PATCH","/v1/me",new JSONObject().put("display_name",name),true);
        TLRPC.TL_contacts_search search=new TLRPC.TL_contacts_search();search.q=name;search.limit=20;
        CompletableFuture<TLObject> found=new CompletableFuture<>();ConnectionsManager.getInstance(2).sendRequest(search,(value,error)->{if(error!=null)found.completeExceptionally(new AssertionError());else found.complete(value);});
        TLRPC.TL_contacts_found results=(TLRPC.TL_contacts_found)found.get(30,TimeUnit.SECONDS);
        check(results.users.size()==1 && results.results.size()==1 && results.users.get(0).first_name.equals(name),"Telegram_discovery_response");
        TLRPC.User member=results.users.get(0);try(IdMap map=new IdMap(context,2)){check(map.remote("user",member.id).equals(memberId),"discovered_member_identity");}
        org.telegram.tgnet.SerializedData encoded=new org.telegram.tgnet.SerializedData();member.serializeToStream(encoded);org.telegram.tgnet.SerializedData decoded=new org.telegram.tgnet.SerializedData(encoded.toByteArray());
        TLRPC.User restored=TLRPC.User.TLdeserialize(decoded,decoded.readInt32(true),true);encoded.cleanup();decoded.cleanup();check(restored.first_name.equals(name),"mapped_user_name_serialization");
        passed.put("Telegram_display_name_discovery").put("mapped_user_name_serialization");
        stage="MessagesController_group_creation";
        CompletableFuture<Long> created=new CompletableFuture<>();NotificationCenter center=NotificationCenter.getInstance(2);
        NotificationCenter.NotificationCenterDelegate observer=(id,account,args)->{if(id==NotificationCenter.chatDidCreated)created.complete((Long)args[0]);else created.completeExceptionally(new AssertionError());};
        try {
            AndroidUtilities.runOnUIThread(()->{MessagesController controller=MessagesController.getInstance(2);controller.putUser(member,false);center.addObserver(observer,NotificationCenter.chatDidCreated);center.addObserver(observer,NotificationCenter.chatDidFailCreate);ArrayList<Long> selected=new ArrayList<>();selected.add(member.id);controller.createChat("گروه از رابط اصلی",selected,"",ChatObject.CHAT_TYPE_CHAT,false,null,null,86400,null);});
            long local=created.get(30,TimeUnit.SECONDS);String group;try(IdMap map=new IdMap(context,2)){group=map.remote("conversation",local);}
            TLRPC.Chat cached=MessagesStorage.getInstance(2).getChat(local);check(cached!=null && cached.title.equals("گروه از رابط اصلی") && cached.participants_count==2 && cached.creator,"created_group_native_cache");
            List<Models.User> members=b.members(group);check(members.size()==2 && members.stream().anyMatch(u->u.id.equals(memberId)),"created_group_membership");
            TLRPC.TL_updateShortSentMessage ack=(TLRPC.TL_updateShortSentMessage)queueThroughUI(new JSONObject().put("group",group),UUID.randomUUID().getMostSignificantBits(),"پیام در گروه جدید").get(30,TimeUnit.SECONDS);
            List<Models.Message> messages=b.messages(group,null,100);check(messages.size()==1 && messages.get(0).ordinal==ack.id && messages.get(0).text.equals("پیام در گروه جدید"),"created_group_message_delivery");
            JSONObject raw=(JSONObject)a.request("GET","/v1/messages/"+messages.get(0).id,null,true);check(java.time.Duration.between(xyz.eclipseofficial.vyrn.data.Timestamps.parse(raw.getString("created_at")),xyz.eclipseofficial.vyrn.data.Timestamps.parse(raw.getString("expires_at"))).getSeconds()==86400,"first_message_uses_creation_TTL");
            CompletableFuture<TLObject> snapshot=new CompletableFuture<>();ConnectionsManager.getInstance(2).sendRequest(new TLRPC.TL_messages_getDialogs(),(value,error)->{if(error!=null)snapshot.completeExceptionally(new AssertionError());else snapshot.complete(value);});
            TLRPC.TL_messages_dialogs dialogs=(TLRPC.TL_messages_dialogs)snapshot.get(30,TimeUnit.SECONDS);TLRPC.Dialog ttlDialog=null;for(TLRPC.Dialog dialog:dialogs.dialogs)if(dialog.id==-local)ttlDialog=dialog;
            check(ttlDialog!=null && ttlDialog.ttl_period==86400 && (ttlDialog.flags&32)!=0,"created_group_dialog_TTL");passed.put("first_message_uses_creation_TTL").put("created_group_dialog_TTL");
            a.request("POST","/v1/conversations/"+group+"/members",new JSONObject().put("user_id",memberId).put("role","admin"),true);
            verifyFullGroup(local,a.conversationDetails(group));
            CompletableFuture<Boolean> renamed=new CompletableFuture<>();AndroidUtilities.runOnUIThread(()->MessagesController.getInstance(2).changeChatTitle(local,"نام جدید گروه",()->renamed.complete(true)));renamed.get(30,TimeUnit.SECONDS);
            TLRPC.Chat renamedCache=cachedGroup(local);check(renamedCache!=null && renamedCache.title.equals("نام جدید گروه"),"native_group_rename_cache");
            Models.ConversationDetails changed=b.conversationDetails(group);check(changed.conversation.title.equals("نام جدید گروه") && changed.conversation.disappearingSeconds==86400,"group_rename_preserves_TTL");passed.put("MessagesController_group_rename").put("group_rename_preserves_TTL");
            changeGroupTTL(local,3600);changeGroupTTL(local,0);changeGroupTTL(local,3600);
            changed=b.conversationDetails(group);check(changed.conversation.title.equals("نام جدید گروه") && changed.conversation.disappearingSeconds==3600,"group_TTL_preserves_title");
            String expiring=a.send(group,UUID.randomUUID().toString(),"پیام با زمان جدید");JSONObject timed=(JSONObject)a.request("GET","/v1/messages/"+expiring,null,true);
            check(java.time.Duration.between(xyz.eclipseofficial.vyrn.data.Timestamps.parse(timed.getString("created_at")),xyz.eclipseofficial.vyrn.data.Timestamps.parse(timed.getString("expires_at"))).getSeconds()==3600,"new_message_uses_changed_TTL");passed.put("MessagesController_group_TTL").put("group_TTL_disable").put("group_TTL_preserves_title").put("new_message_uses_changed_TTL");
            verifyRealtimeGroup(a,b,group,local,"نام از دستگاه دیگر",7200);
            verifyRealtimeGroup(a,b,group,local,"نام از دستگاه دیگر",0);
            verifyRealtimeGroup(a,b,group,local,"نام جدید گروه",3600);
            passed.put("remote_group_rename_realtime").put("remote_group_TTL_realtime").put("remote_group_TTL_disable_realtime").put("remote_group_metadata_durable");
            verifyGroupMembershipEvents(a,group,local,memberId);
            passed.put("MessagesController_group_creation").put("created_group_native_cache").put("created_group_member_delivery");return group;
        } finally {AndroidUtilities.runOnUIThread(()->{center.removeObserver(observer,NotificationCenter.chatDidCreated);center.removeObserver(observer,NotificationCenter.chatDidFailCreate);});}
    }

    private void verifyGroupMembershipEvents(RestRepository owner,String group,long local,String memberId) throws Exception {
        stage="group_membership_realtime";VyrnBridge bridge=VyrnBridge.forAccount(2);
        for(String role:new String[]{"member","admin"}){long before=bridge.syncCursor();owner.request("POST","/v1/conversations/"+group+"/members",new JSONObject().put("user_id",memberId).put("role",role),true);verifyMembershipCache(owner,group,local,before);}
        RestRepository extra=repo(9013);extra.vault.clear();String added=register(extra,read("vyrn-test-fixture.json"),"member-events").getString("user_id");
        long before=bridge.syncCursor();owner.request("POST","/v1/conversations/"+group+"/members",new JSONObject().put("user_id",added).put("role","member"),true);verifyMembershipCache(owner,group,local,before);
        before=bridge.syncCursor();owner.request("DELETE","/v1/conversations/"+group+"/members/"+added,null,true);verifyMembershipCache(owner,group,local,before);
        verifyNativeMemberOperations(owner,extra,group,local,added);
        extra.vault.clear();passed.put("group_role_demotion_realtime").put("group_role_promotion_realtime").put("group_member_add_realtime").put("group_member_remove_realtime").put("group_member_cache_roles_dates");
    }
    private void verifyNativeMemberOperations(RestRepository owner,RestRepository extra,String group,long local,String added) throws Exception {
        stage="MessagesController_member_operations";VyrnBridge bridge=VyrnBridge.forAccount(2);TLRPC.User user=bridge.mapUser(extra.me());
        CompletableFuture<Boolean> invited=new CompletableFuture<>();long before=bridge.syncCursor();
        AndroidUtilities.runOnUIThread(()->{MessagesController controller=MessagesController.getInstance(2);controller.putUser(user,false);controller.addUserToChat(local,user,0,null,null,()->invited.complete(true));});invited.get(30,TimeUnit.SECONDS);verifyMembershipCache(owner,group,local,before);
        for(boolean admin:new boolean[]{true,false,true}){
            CompletableFuture<Boolean> changed=new CompletableFuture<>();before=bridge.syncCursor();TLRPC.TL_chatAdminRights rights=new TLRPC.TL_chatAdminRights();rights.change_info=admin;
            AndroidUtilities.runOnUIThread(()->MessagesController.getInstance(2).setUserAdminRole(local,user,rights,"",false,null,false,false,null,()->changed.complete(true),error->{changed.completeExceptionally(new AssertionError());return false;}));changed.get(30,TimeUnit.SECONDS);verifyMembershipCache(owner,group,local,before);
        }
        CompletableFuture<Boolean> repeated=new CompletableFuture<>();AndroidUtilities.runOnUIThread(()->MessagesController.getInstance(2).addUserToChat(local,user,0,null,null,()->repeated.complete(true)));repeated.get(30,TimeUnit.SECONDS);
        check(owner.conversationDetails(group).members.stream().anyMatch(member->member.user.id.equals(added)&&member.role.equals("admin")),"native_reinvite_preserves_admin");
        TLRPC.TL_messages_editChatAdmin forbidden=new TLRPC.TL_messages_editChatAdmin();forbidden.chat_id=local;TLRPC.TL_inputUser self=new TLRPC.TL_inputUser();self.user_id=UserConfig.getInstance(2).getClientUserId();forbidden.user_id=self;forbidden.is_admin=false;CompletableFuture<Integer> denied=new CompletableFuture<>();ConnectionsManager.getInstance(2).sendRequest(forbidden,(value,error)->denied.complete(error==null?0:error.code));check(denied.get(30,TimeUnit.SECONDS)==403,"native_owner_role_protected");
        before=bridge.syncCursor();TLRPC.TL_messages_deleteChatUser remove=new TLRPC.TL_messages_deleteChatUser();remove.chat_id=local;TLRPC.TL_inputUser target=new TLRPC.TL_inputUser();target.user_id=user.id;remove.user_id=target;CompletableFuture<Boolean> removed=new CompletableFuture<>();ConnectionsManager.getInstance(2).sendRequest(remove,(value,error)->{if(error!=null)removed.completeExceptionally(new AssertionError());else{MessagesController.getInstance(2).processUpdates((TLRPC.Updates)value,false);removed.complete(true);}});removed.get(30,TimeUnit.SECONDS);verifyMembershipCache(owner,group,local,before);
        passed.put("MessagesController_member_add").put("MessagesController_admin_promotion").put("MessagesController_admin_demotion").put("native_reinvite_preserves_admin").put("native_owner_role_protected").put("native_member_removal_without_revoke");
    }
    private void verifyMembershipCache(RestRepository owner,String group,long local,long before) throws Exception {
        VyrnBridge bridge=VyrnBridge.forAccount(2);JSONObject batch=(JSONObject)owner.request("GET","/v1/sync?cursor="+before,null,true);long target=0;JSONArray events=batch.getJSONArray("events");
        for(int i=0;i<events.length();i++){JSONObject event=events.getJSONObject(i);if(event.getString("kind").startsWith("member.") && event.getJSONObject("body").optString("conversation_id").equals(group))target=event.getLong("sequence");}
        check(target>before,"membership_event_emitted");long deadline=System.currentTimeMillis()+40000;while(System.currentTimeMillis()<deadline && (bridge.syncCursor()<target || bridge.syncPending()!=0))Thread.sleep(100);
        check(bridge.syncCursor()>=target && bridge.syncPending()==0 && bridge.syncDead()==0,"membership_inbox_processed");
        Models.ConversationDetails expected=owner.conversationDetails(group);TLRPC.Chat cached=cachedGroup(local);TLRPC.ChatFull full=cachedFullGroup(local);
        check(cached!=null && cached.participants_count==expected.members.size() && full!=null && full.participants.participants.size()==expected.members.size(),"membership_native_count");
        try(IdMap map=new IdMap(context,2)){for(TLRPC.ChatParticipant member:full.participants.participants){String remote=map.remote("user",member.user_id);String role=member instanceof TLRPC.TL_chatParticipantCreator?"owner":member instanceof TLRPC.TL_chatParticipantAdmin?"admin":"member";check(expected.members.stream().anyMatch(source->source.user.id.equals(remote)&&source.role.equals(role)&&(role.equals("owner")?member.date==0:source.joinedDate==member.date)),"membership_native_role_date");}}
    }
    private void verifyRealtimeGroup(RestRepository viewer,RestRepository writer,String group,long local,String title,int seconds) throws Exception {
        stage="remote_group_metadata_realtime";VyrnBridge bridge=VyrnBridge.forAccount(2);long before=bridge.syncCursor();
        writer.request("PATCH","/v1/conversations/"+group,new JSONObject().put("title",title).put("disappearing_seconds",seconds),true);
        JSONObject batch=(JSONObject)viewer.request("GET","/v1/sync?cursor="+before,null,true);long target=0;JSONArray events=batch.getJSONArray("events");
        for(int i=0;i<events.length();i++){JSONObject event=events.getJSONObject(i);if(event.getString("kind").equals("conversation.updated") && event.getJSONObject("body").optString("id").equals(group))target=event.getLong("sequence");}
        check(target>before,"remote_group_event_emitted");long deadline=System.currentTimeMillis()+40000;
        while(System.currentTimeMillis()<deadline && (bridge.syncCursor()<target || bridge.syncPending()!=0))Thread.sleep(100);
        check(bridge.syncCursor()>=target && bridge.syncPending()==0 && bridge.syncDead()==0,"remote_group_inbox_processed");
        TLRPC.Chat cached=cachedGroup(local);TLRPC.ChatFull full=cachedFullGroup(local);
        check(cached!=null && cached.title.equals(title) && full!=null && full.ttl_period==seconds && ((full.flags&16384)!=0)==(seconds>0),"remote_group_native_cache");
    }
    private void changeGroupTTL(long local,int seconds) throws Exception {
        stage="MessagesController_group_TTL";CompletableFuture<Boolean> applied=new CompletableFuture<>();NotificationCenter center=NotificationCenter.getInstance(2);
        NotificationCenter.NotificationCenterDelegate observer=(id,account,args)->{TLRPC.ChatFull full=(TLRPC.ChatFull)args[0];if(full.id==local && full.ttl_period==seconds && ((full.flags&16384)!=0)==(seconds>0))applied.complete(true);};
        try {
            AndroidUtilities.runOnUIThread(()->{center.addObserver(observer,NotificationCenter.chatInfoDidLoad);MessagesController.getInstance(2).setDialogHistoryTTL(-local,seconds);});applied.get(30,TimeUnit.SECONDS);
            CompletableFuture<Boolean> scheduled=new CompletableFuture<>();AndroidUtilities.runOnUIThread(()->scheduled.complete(true));scheduled.get(10,TimeUnit.SECONDS);
            TLRPC.ChatFull cached=cachedFullGroup(local);check(cached!=null && cached.ttl_period==seconds && ((cached.flags&16384)!=0)==(seconds>0),"native_TTL_cache");
        } finally {AndroidUtilities.runOnUIThread(()->center.removeObserver(observer,NotificationCenter.chatInfoDidLoad));}
    }
    private TLRPC.Chat cachedGroup(long local) throws Exception {
        CompletableFuture<TLRPC.Chat> cached=new CompletableFuture<>();MessagesStorage storage=MessagesStorage.getInstance(2);
        storage.getStorageQueue().postRunnable(()->{try{cached.complete(storage.getChat(local));}catch(Throwable error){cached.completeExceptionally(error);}});return cached.get(10,TimeUnit.SECONDS);
    }
    private TLRPC.ChatFull cachedFullGroup(long local) throws Exception {
        CompletableFuture<TLRPC.ChatFull> cached=new CompletableFuture<>();MessagesStorage storage=MessagesStorage.getInstance(2);
        storage.getStorageQueue().postRunnable(()->{try{cached.complete(storage.loadChatInfoInQueue(local,false,false,false,0));}catch(Throwable error){cached.completeExceptionally(error);}});return cached.get(10,TimeUnit.SECONDS);
    }
    private void verifyFullGroup(long local,Models.ConversationDetails expected) throws Exception {
        stage="native_full_group_information";CompletableFuture<TLRPC.ChatFull> loaded=new CompletableFuture<>();NotificationCenter center=NotificationCenter.getInstance(2);
        NotificationCenter.NotificationCenterDelegate observer=(id,account,args)->{TLRPC.ChatFull full=(TLRPC.ChatFull)args[0];if(full.id==local)loaded.complete(full);};
        try {
            AndroidUtilities.runOnUIThread(()->{center.addObserver(observer,NotificationCenter.chatInfoDidLoad);MessagesController.getInstance(2).loadFullChat(local,0,true);});
            TLRPC.ChatFull full=loaded.get(30,TimeUnit.SECONDS);check(full.ttl_period==86400 && (full.flags&16384)!=0 && full.participants.participants.size()==2,"native_full_group_TTL_members");
            int owners=0,admins=0;try(IdMap map=new IdMap(context,2)){for(TLRPC.ChatParticipant member:full.participants.participants){if(member instanceof TLRPC.TL_chatParticipantCreator)owners++;if(member instanceof TLRPC.TL_chatParticipantAdmin)admins++;String remote=map.remote("user",member.user_id);check(expected.members.stream().anyMatch(source->source.user.id.equals(remote) && source.joinedDate==member.date),"native_member_join_timestamp");}}
            check(owners==1 && admins==1,"native_full_group_roles");TLRPC.ChatFull cached=cachedFullGroup(local);check(cached!=null && cached.ttl_period==86400 && cached.participants.participants.size()==2,"native_full_group_persisted");
            passed.put("MessagesController_full_group_load").put("native_full_group_roles_dates").put("native_full_group_persisted");
        } finally {AndroidUtilities.runOnUIThread(()->center.removeObserver(observer,NotificationCenter.chatInfoDidLoad));}
    }
    private String clientId(String user,long randomId){return UUID.nameUUIDFromBytes((user+":"+randomId).getBytes(StandardCharsets.UTF_8)).toString();}
    private static final class TokenSend {int token;final CompletableFuture<TLObject> response=new CompletableFuture<>();}
    private TokenSend queueThroughTransport(JSONObject state,long randomId,String text) throws Exception {
        TLRPC.TL_messages_sendMessage request=new TLRPC.TL_messages_sendMessage();TLRPC.TL_inputPeerChat peer=new TLRPC.TL_inputPeerChat();
        try(IdMap map=new IdMap(context,2)){peer.chat_id=map.local("conversation",state.getString("group"));}
        request.peer=peer;request.message=text;request.random_id=randomId;
        TokenSend send=new TokenSend();send.token=ConnectionsManager.getInstance(2).sendRequest(request,(value,error)->{if(error!=null)send.response.completeExceptionally(new AssertionError());else send.response.complete(value);});return send;
    }
    private MessageObject pendingUi(JSONObject state,int localId,long randomId,String text) throws Exception {
        return pendingUi(state,localId,randomId,text,false);
    }
    private MessageObject pendingUi(JSONObject state,int localId,long randomId,String text,boolean deleted) throws Exception {
        long chatId;try(IdMap map=new IdMap(context,2)){chatId=map.local("conversation",state.getString("token_group"));}
        TLRPC.TL_message message=new TLRPC.TL_message();message.id=localId;message.random_id=randomId;message.dialog_id=-chatId;message.date=(int)(System.currentTimeMillis()/1000);message.out=true;message.message=text;message.send_state=MessageObject.MESSAGE_SEND_STATE_SENDING;
        TLRPC.TL_peerChat peer=new TLRPC.TL_peerChat();peer.chat_id=chatId;message.peer_id=peer;TLRPC.TL_peerUser sender=new TLRPC.TL_peerUser();sender.user_id=UserConfig.getInstance(2).getClientUserId();message.from_id=sender;message.flags|=256;message.media=new TLRPC.TL_messageMediaEmpty();
        ArrayList<TLRPC.Message> messages=new ArrayList<>();messages.add(message);MessagesStorage storage=MessagesStorage.getInstance(2);storage.putMessages(messages,true,true,false,0,0,0);
        CompletableFuture<Boolean> saved=new CompletableFuture<>();storage.getStorageQueue().postRunnable(()->saved.complete(true));saved.get(10,TimeUnit.SECONDS);
        check((storage.getMessage(-chatId,localId)==null)==deleted,deleted?"offline_deleted_cache_rejected":"pending_UI_cache_written");if(deleted)passed.put("offline_deleted_cache_rejected");return new MessageObject(2,message,false,false);
    }
    private void offline() throws Exception {
        stage="offline_UI_outbox";
        JSONObject state=read("vyrn-test-state.json");
        VyrnBridge.forAccount(2).repository.vault.save(repo(9001).vault.load());
        long first=UUID.randomUUID().getMostSignificantBits(),second=UUID.randomUUID().getMostSignificantBits();
        state.put("outbox_first",first).put("outbox_second",second);write("vyrn-test-state.json",state);
        CompletableFuture<TLObject> one=queueThroughUI(state,first,"Offline one"),two=queueThroughUI(state,second,"Offline two");
        Thread.sleep(6000);
        check(!one.isDone() && !two.isDone(),"offline_no_false_ack");
        try(OutboxStore store=new OutboxStore(context,2)){
            OutboxStore.Entry pending=store.get(state.getString("user"),clientId(state.getString("user"),first));
            long retryDeadline=System.currentTimeMillis()+20000;
            while((pending==null || pending.state!=OutboxStore.QUEUED || pending.attempts==0) && System.currentTimeMillis()<retryDeadline){Thread.sleep(100);pending=store.get(state.getString("user"),clientId(state.getString("user"),first));}
            check(pending!=null && pending.state==OutboxStore.QUEUED && pending.attempts>0,"outbox_persisted_before_retry");
            OutboxStore.Entry later=store.get(state.getString("user"),clientId(state.getString("user"),second));
            check(later!=null && later.attempts==0,"outbox_conversation_order");
            check(store.next(UUID.randomUUID().toString())==null,"outbox_account_isolation");
            boolean conflict=false;try{store.enqueue(state.getString("user"),pending.client,pending.conversation,"different");}catch(IllegalStateException expected){conflict=true;}
            check(conflict,"outbox_idempotency_conflict");
        }
        passed.put("offline_UI_queue").put("no_premature_ack").put("bounded_backoff_started").put("conversation_send_order").put("account_isolation").put("retry_payload_immutable");
        stage="offline_durable_cancellation";
        String cancelled=UUID.randomUUID().toString(),committed=clientId(state.getString("user"),state.getLong("uncertain_random"));
        try(OutboxStore store=new OutboxStore(context,9008)){
            store.enqueue(state.getString("user"),cancelled,state.getString("uncertain_group"),"Must never appear");
            store.next(state.getString("user")); // Model an attempted send with no known acknowledgement.
        }
        try(OutboxSender sender=new OutboxSender(context,9008,repo(9001))){
            CompletableFuture<Boolean> cancellation=sender.cancelDurably(state.getString("user"),cancelled);
            Thread.sleep(2500);check(!cancellation.isDone(),"offline_cancel_no_false_confirmation");
        }
        try(OutboxStore store=new OutboxStore(context,9008)){
            check(store.get(state.getString("user"),cancelled).cancellation,"cancel_intent_persisted");
            store.enqueue(state.getString("user"),committed,state.getString("uncertain_group"),"Lost acknowledgement");
            // The server-committed fixture represents a claimed send whose response was lost.
            store.getWritableDatabase().execSQL("UPDATE outbox SET attempted=1,state=? WHERE user_id=? AND client_id=?",new Object[]{OutboxStore.SENDING,state.getString("user"),committed});
            store.requestCancellation(state.getString("user"),committed);
        }
        state.put("cancelled_client",cancelled).put("committed_cancel_client",committed);write("vyrn-test-state.json",state);
        passed.put("offline_cancel_no_false_confirmation").put("durable_cancel_intent");
        stage="offline_Telegram_request_cancellation";
        JSONObject cancellationGroup=new JSONObject().put("group",state.getString("token_group"));
        long cancelRandom=UUID.randomUUID().getMostSignificantBits(),guidRandom=UUID.randomUUID().getMostSignificantBits();
        TokenSend tokenSend=queueThroughTransport(cancellationGroup,cancelRandom,"Token cancellation");
        String tokenClient=clientId(state.getString("user"),cancelRandom),guidClient=clientId(state.getString("user"),guidRandom);
        try(OutboxStore store=new OutboxStore(context,2)){
            long deadline=System.currentTimeMillis()+45000;OutboxStore.Entry entry=store.get(state.getString("user"),tokenClient);
            while((entry==null || entry.attempts==0) && System.currentTimeMillis()<deadline){Thread.sleep(100);entry=store.get(state.getString("user"),tokenClient);}
            check(entry!=null && entry.attempts>0,"token_send_attempted");
            CompletableFuture<Boolean> wrongAccount=new CompletableFuture<>();VyrnBridge.cancelRequest(9007,tokenSend.token,()->wrongAccount.complete(true));
            CompletableFuture<Boolean> barrier=new CompletableFuture<>();Utilities.stageQueue.postRunnable(()->barrier.complete(true));barrier.get(5,TimeUnit.SECONDS);
            check(!wrongAccount.isDone() && !store.get(state.getString("user"),tokenClient).cancellation,"token_account_isolation");
            CompletableFuture<Boolean> cancelledCallback=new CompletableFuture<>();ConnectionsManager.getInstance(2).cancelRequest(tokenSend.token,true,()->cancelledCallback.complete(true));
            deadline=System.currentTimeMillis()+10000;
            while(!store.get(state.getString("user"),tokenClient).cancellation && System.currentTimeMillis()<deadline)Thread.sleep(100);
            check(store.get(state.getString("user"),tokenClient).cancellation,"token_cancellation_persisted");
            check(!cancelledCallback.isDone() && !tokenSend.response.isDone(),"token_cancel_no_false_callback");
            TokenSend guidSend=queueThroughTransport(cancellationGroup,guidRandom,"GUID cancellation");
            ConnectionsManager.getInstance(2).bindRequestToGuid(guidSend.token,3455);ConnectionsManager.getInstance(2).cancelRequestsForGuid(3455);
            deadline=System.currentTimeMillis()+45000;OutboxStore.Entry guid=store.get(state.getString("user"),guidClient);
            while((guid==null || !guid.cancellation && guid.state!=OutboxStore.CANCELLED) && System.currentTimeMillis()<deadline){Thread.sleep(100);guid=store.get(state.getString("user"),guidClient);}
            check(guid!=null && (guid.cancellation || guid.state==OutboxStore.CANCELLED) && !guidSend.response.isDone(),"guid_cancellation_routed");
        }
        state.put("token_cancel_client",tokenClient).put("guid_cancel_client",guidClient);write("vyrn-test-state.json",state);
        passed.put("Telegram_request_token_cancellation").put("request_token_account_isolation").put("cancel_callback_waits_for_confirmation").put("Telegram_GUID_cancellation");
        stage="offline_SendMessagesHelper_cancellation";
        long uiRandom=UUID.randomUUID().getMostSignificantBits();String uiClient=clientId(state.getString("user"),uiRandom),uiCommittedClient=clientId(state.getString("user"),state.getLong("ui_committed_random"));
        MessageObject uiPending=pendingUi(state,-91001,uiRandom,"Pending UI cancellation"),uiCommitted=pendingUi(state,-91002,state.getLong("ui_committed_random"),"Committed UI cancellation");
        MessageObject uiDeleted=pendingUi(state,-91003,state.getLong("ui_deleted_random"),"Deleted UI cancellation");
        pendingUi(state,state.getInt("ui_deleted_ordinal"),state.getLong("ui_deleted_random"),"Stale deleted cached body",true);
        String uiDeletedClient=clientId(state.getString("user"),state.getLong("ui_deleted_random"));
        try(OutboxStore store=new OutboxStore(context,2)){
            store.enqueue(state.getString("user"),uiClient,state.getString("token_group"),"Pending UI cancellation");store.enqueue(state.getString("user"),uiCommittedClient,state.getString("token_group"),"Committed UI cancellation");
            store.enqueue(state.getString("user"),uiDeletedClient,state.getString("token_group"),"Deleted UI cancellation");
            store.getWritableDatabase().execSQL("UPDATE outbox SET attempted=1 WHERE user_id=? AND client_id IN (?,?,?)",new Object[]{state.getString("user"),uiClient,uiCommittedClient,uiDeletedClient});
        }
        CompletableFuture<Boolean> requested=new CompletableFuture<>();AndroidUtilities.runOnUIThread(()->{SendMessagesHelper.getInstance(2).cancelSendingMessage(uiPending);SendMessagesHelper.getInstance(2).cancelSendingMessage(uiCommitted);SendMessagesHelper.getInstance(2).cancelSendingMessage(uiDeleted);requested.complete(true);});requested.get(10,TimeUnit.SECONDS);
        try(OutboxStore store=new OutboxStore(context,2)){
            long deadline=System.currentTimeMillis()+45000;while(store.pendingUi(state.getString("user"))<3 && System.currentTimeMillis()<deadline)Thread.sleep(100);
            check(store.pendingUi(state.getString("user"))==3 && store.resolvedUi(state.getString("user")).isEmpty(),"UI_cancellation_waits_for_server_proof");
        }
        check(MessagesStorage.getInstance(2).getMessage(uiPending.getDialogId(),-91001)!=null && MessagesStorage.getInstance(2).getMessage(uiCommitted.getDialogId(),-91002)!=null,"offline_UI_not_optimistically_removed");
        state.put("ui_dialog",uiPending.getDialogId()).put("ui_cancel_client",uiClient);write("vyrn-test-state.json",state);
        passed.put("SendMessagesHelper_cancel_interception").put("offline_UI_retained_until_proof");
        seedLegacyDeletionCache();
    }

    private TLRPC.Message legacyPrefetched(JSONObject state) throws Exception {
        org.telegram.tgnet.SerializedData data=new org.telegram.tgnet.SerializedData(android.util.Base64.decode(state.getString("prefetched"),android.util.Base64.NO_WRAP));
        try{TLRPC.Message message=TLRPC.Message.TLdeserialize(data,data.readInt32(true),true);message.dialog_id=state.getLong("dialog");return message;}finally{data.cleanup();}
    }
    private void seedLegacyDeletionCache() throws Exception {
        stage="seed_legacy_native_cache";check(UserConfig.getInstance(3).getClientUserId()==0,"legacy_fixture_inactive_account");MessagesStorage storage=MessagesStorage.getInstance(3);awaitStorage(storage);
        JSONObject state=read("vyrn-delete-test.json"),edit=read("vyrn-edit-test.json");TLRPC.Message stale=legacyPrefetched(state),live=MessagesStorage.getInstance(2).getMessage(edit.getLong("dialog"),edit.getInt("ordinal"));check(live!=null,"legacy_live_fixture_available");
        CompletableFuture<Boolean> prepared=new CompletableFuture<>();storage.getStorageQueue().postRunnable(()->{try{
            org.telegram.SQLite.SQLiteDatabase db=storage.getDatabase();for(String name:new String[]{"vyrn_delete_cached","vyrn_block_INSERT","vyrn_block_UPDATE","vyrn_dialog_INSERT","vyrn_dialog_UPDATE","vyrn_delete_dialog"})db.executeFast("DROP TRIGGER IF EXISTS "+name).stepThis().dispose();
            db.executeFast("INSERT OR REPLACE INTO vyrn_deleted_messages(uid,mid) VALUES("+stale.dialog_id+","+stale.id+")").stepThis().dispose();prepared.complete(true);
        }catch(Exception failure){prepared.completeExceptionally(failure);}});prepared.get(10,TimeUnit.SECONDS);
        storage.putDialogs(staleDialogs(stale.dialog_id,stale),0);storage.putDialogs(staleDialogs(edit.getLong("dialog"),live),0);awaitStorage(storage);
        check(storage.getMessage(stale.dialog_id,stale.id)!=null&&storage.getMessage(edit.getLong("dialog"),live.id)!=null,"legacy_fixture_genuinely_populated");passed.put("legacy_fixture_genuinely_populated");
        write("vyrn-legacy-cache.json",new JSONObject().put("live_dialog",edit.getLong("dialog")).put("live_id",live.id).put("live_text",live.message));
    }
    private void verifyLegacyDeletionCache() throws Exception {
        stage="legacy_native_cache_reopen";check(UserConfig.getInstance(3).getClientUserId()==0,"legacy_reopen_no_authenticated_sync");JSONObject state=read("vyrn-delete-test.json"),fixture=read("vyrn-legacy-cache.json");TLRPC.Message stale=legacyPrefetched(state);MessagesStorage storage=MessagesStorage.getInstance(3);
        check(storage.getMessage(stale.dialog_id,stale.id)==null,"legacy_reopen_removes_deleted_cache");passed.put("legacy_reopen_removes_deleted_cache");
        TLRPC.Message live=storage.getMessage(fixture.getLong("live_dialog"),fixture.getInt("live_id"));check(live!=null&&live.message.equals(fixture.getString("live_text")),"legacy_reopen_preserves_live_cache");passed.put("legacy_reopen_preserves_live_cache");
        check(storage.getVyrnDeletedMessages()!=null&&storage.getVyrnDeletedMessages().get(stale.dialog_id).contains(stale.id),"legacy_reopen_restores_fences");passed.put("legacy_reopen_restores_fences");
        storage.putDialogs(staleDialogs(stale.dialog_id,stale),0);awaitStorage(storage);CompletableFuture<Boolean> guarded=new CompletableFuture<>();storage.getStorageQueue().postRunnable(()->{try{org.telegram.SQLite.SQLiteCursor cursor=storage.getDatabase().queryFinalized("SELECT last_mid,date FROM dialogs WHERE did=?",stale.dialog_id);try{guarded.complete(cursor.next()&&cursor.longValue(0)==0&&cursor.intValue(1)==0);}finally{cursor.dispose();}}catch(Exception failure){guarded.completeExceptionally(failure);}});
        check(guarded.get(10,TimeUnit.SECONDS)&&storage.getMessage(stale.dialog_id,stale.id)==null,"legacy_reopen_blocks_stale_writer");passed.put("legacy_reopen_blocks_stale_writer");
        CompletableFuture<Boolean> repeated=new CompletableFuture<>();storage.getStorageQueue().postRunnable(()->{try{VyrnBridge.initializeDeletedCache(storage.getDatabase());VyrnBridge.initializeDeletedCache(storage.getDatabase());storage.vyrnReloadDeletedMessages();repeated.complete(true);}catch(Exception failure){repeated.completeExceptionally(failure);}});repeated.get(10,TimeUnit.SECONDS);
        check(storage.getMessage(stale.dialog_id,stale.id)==null&&storage.getMessage(fixture.getLong("live_dialog"),fixture.getInt("live_id"))!=null&&storage.getVyrnDeletedMessages().get(stale.dialog_id).contains(stale.id),"legacy_initializer_repeat_safe");passed.put("legacy_initializer_repeat_safe");
    }

    private void restart() throws Exception {
        stage="process_restart_session";
        JSONObject state=read("vyrn-test-state.json");
        check(state.getInt("process_id")!=android.os.Process.myPid(),"new_process");
        verifyLegacyDeletionCache();
        RestRepository a=repo(9001), b=repo(9002);
        check(a.me().id.equals(state.getString("user")),"persisted_session");
        JSONObject blocking=read("vyrn-blocking-ui.json");check(blockedCache(blocking.getLong("peer"),false),"native_unblock_cache_after_restart");onUi(()->{MessagesController.getInstance(2).getBlockedPeers(true);return true;});waitBlockedList();check(!a.blockedForUser(state.getString("user"),blocking.getString("target")) && onUi(()->MessagesController.getInstance(2).totalBlockedCount==0 && !MessagesController.getInstance(2).vyrnBlockedLoadFailed),"native_block_list_after_restart");passed.put("native_unblock_cache_after_restart").put("native_block_list_after_restart");
        Models.User restoredProfile=a.me();JSONObject avatar=read("vyrn-avatar-ui.json");check(restoredProfile.avatarId.equals(avatar.getString("id"))&&UserConfig.getInstance(2).getCurrentUser().photo.photo_id==avatar.getLong("photo")&&UserConfig.getInstance(2).getCurrentUser().photo.dc_id==1,"avatar_user_config_after_restart");TLRPC.User avatarCached=MessagesStorage.getInstance(2).getUserSync(avatar.getLong("user"));check(avatarCached!=null&&avatarCached.photo.photo_id==avatar.getLong("photo")&&avatarCached.photo.dc_id==1,"avatar_native_cache_after_restart");passed.put("avatar_user_config_after_restart").put("avatar_native_cache_after_restart");
        JSONObject realtimeProfile=read("vyrn-profile-realtime.json");TLRPC.User peerCached=MessagesStorage.getInstance(2).getUserSync(realtimeProfile.getLong("peer"));CompletableFuture<Boolean> peerFullCached=new CompletableFuture<>();MessagesStorage.getInstance(2).getStorageQueue().postRunnable(()->{java.util.HashSet<Long> peers=new java.util.HashSet<>();peers.add(realtimeProfile.optLong("peer"));peerFullCached.complete(MessagesStorage.getInstance(2).loadUserInfos(peers).stream().anyMatch(full->full.id==realtimeProfile.optLong("peer")&&realtimeProfile.optString("about").equals(full.about)&&full.profile_photo!=null&&full.profile_photo.id==realtimeProfile.optLong("photo")));});check(peerCached!=null&&peerCached.first_name.equals(realtimeProfile.getString("first"))&&peerCached.photo.photo_id==realtimeProfile.getLong("photo")&&peerFullCached.get(10,TimeUnit.SECONDS),"peer_profile_realtime_after_restart");passed.put("peer_profile_realtime_after_restart");
        JSONObject profile=read("vyrn-profile-ui.json");check(restoredProfile.firstName.equals(profile.getString("first")) && restoredProfile.lastName.equals(profile.getString("last")) && restoredProfile.about.equals(profile.getString("about")),"profile_names_bio_after_restart");passed.put("profile_names_bio_after_restart");
        CompletableFuture<Boolean> restoredBio=new CompletableFuture<>();MessagesStorage.getInstance(2).getStorageQueue().postRunnable(()->{java.util.HashSet<Long> ids=new java.util.HashSet<>();try{ids.add(profile.getLong("user"));restoredBio.complete(MessagesStorage.getInstance(2).loadUserInfos(ids).stream().anyMatch(info->profile.optString("about").equals(info.about)));}catch(Exception failure){restoredBio.completeExceptionally(failure);}});check(restoredBio.get(10,TimeUnit.SECONDS),"bio_editor_native_cache_after_restart");passed.put("bio_editor_native_cache_after_restart");
        TLRPC.User restoredNative=UserConfig.getInstance(2).getCurrentUser(),restoredCached=MessagesStorage.getInstance(2).getUserSync(profile.getLong("user"));
        check(restoredNative!=null && restoredNative.id==profile.getLong("user"),"name_editor_restarted_config_identity");
        check(restoredNative.first_name.equals(profile.getString("first")),"name_editor_restarted_config_first");check(restoredNative.last_name.equals(profile.getString("last")),"name_editor_restarted_config_last");
        check(restoredCached!=null,"name_editor_restarted_cache_present");check(restoredCached.first_name.equals(profile.getString("first")),"name_editor_restarted_cache_first");check(restoredCached.last_name.equals(profile.getString("last")),"name_editor_restarted_cache_last");passed.put("name_editor_user_config_cache_after_restart");
        stage="restart_durable_cancellation";
        try(OutboxSender sender=new OutboxSender(context,9008,a)){
            check(sender.cancelDurably(state.getString("user"),state.getString("cancelled_client")).get(45,TimeUnit.SECONDS),"cancel_confirmed_after_restart");
            check(!sender.cancelDurably(state.getString("user"),state.getString("committed_cancel_client")).get(45,TimeUnit.SECONDS),"committed_send_not_falsely_cancelled");
        }
        try(OutboxStore store=new OutboxStore(context,9008)){
            check(store.get(state.getString("user"),state.getString("cancelled_client")).state==OutboxStore.CANCELLED,"confirmed_cancel_saved");
            OutboxStore.Entry committed=store.get(state.getString("user"),state.getString("committed_cancel_client"));
            check(committed.state==OutboxStore.SENT && committed.server.equals(state.getString("uncertain_server")) && committed.text==null,"committed_cancel_reconciled");
        }
        boolean fenced=false;try{a.send(state.getString("uncertain_group"),state.getString("cancelled_client"),"Must never appear");}catch(RestRepository.ApiException expected){fenced=expected.status==409;}
        check(fenced,"cancel_prevents_delayed_retry");
        passed.put("cancel_recovery_after_process_death").put("committed_cancel_reconciliation").put("cancelled_send_retry_fenced");
        stage="restart_outbox_reconnect";
        TLRPC.TL_updateShortSentMessage first=(TLRPC.TL_updateShortSentMessage)queueThroughUI(state,state.getLong("outbox_first"),"Offline one").get(45,TimeUnit.SECONDS);
        TLRPC.TL_updateShortSentMessage second=(TLRPC.TL_updateShortSentMessage)queueThroughUI(state,state.getLong("outbox_second"),"Offline two").get(45,TimeUnit.SECONDS);
        check(first.id<second.id,"reconnected_send_order");
        TLRPC.TL_updateShortSentMessage replay=(TLRPC.TL_updateShortSentMessage)queueThroughUI(state,state.getLong("outbox_first"),"Offline one").get(5,TimeUnit.SECONDS);
        check(replay.id==first.id,"outbox_completed_retry");
        JSONObject uncertain=new JSONObject().put("group",state.getString("uncertain_group"));
        stage="committed_Telegram_token_cancellation";
        TokenSend committedToken=queueThroughTransport(uncertain,state.getLong("uncertain_random"),"Lost acknowledgement");
        CompletableFuture<Boolean> falseCancellation=new CompletableFuture<>();ConnectionsManager.getInstance(2).cancelRequest(committedToken.token,true,()->falseCancellation.complete(true));
        TLRPC.TL_updateShortSentMessage reconciled=(TLRPC.TL_updateShortSentMessage)committedToken.response.get(45,TimeUnit.SECONDS);
        check(!falseCancellation.isDone(),"committed_token_not_reported_cancelled");
        TLRPC.TL_updateShortSentMessage recovered=(TLRPC.TL_updateShortSentMessage)queueThroughUI(uncertain,state.getLong("uncertain_random"),"Lost acknowledgement").get(45,TimeUnit.SECONDS);
        check(reconciled.id==recovered.id,"committed_token_ack_preserved");
        stage="token_cancellation_restart_recovery";
        try(OutboxStore store=new OutboxStore(context,2)){
            long deadline=System.currentTimeMillis()+45000;
            while(System.currentTimeMillis()<deadline){OutboxStore.Entry token=store.get(state.getString("user"),state.getString("token_cancel_client")),guid=store.get(state.getString("user"),state.getString("guid_cancel_client"));if(token!=null && guid!=null && token.state==OutboxStore.CANCELLED && guid.state==OutboxStore.CANCELLED)break;Thread.sleep(100);}
            check(store.get(state.getString("user"),state.getString("token_cancel_client")).state==OutboxStore.CANCELLED && store.get(state.getString("user"),state.getString("guid_cancel_client")).state==OutboxStore.CANCELLED,"token_cancellations_survive_process_death");
        }
        passed.put("committed_token_cancel_ack").put("token_cancel_process_restart");
        try(IdMap map=new IdMap(context,2)){long local=map.local("conversation",state.getString("native_created_group"));TLRPC.Chat cached=MessagesStorage.getInstance(2).getChat(local);check(cached!=null && cached.title.equals("نام جدید گروه"),"created_group_survives_restart");}passed.put("created_group_survives_restart");passed.put("renamed_group_survives_restart");
        boolean ttlRestored=false;for(Models.Conversation group:a.conversations())if(group.id.equals(state.getString("native_created_group")))ttlRestored=group.disappearingSeconds==3600;check(ttlRestored,"created_group_TTL_survives_restart");passed.put("created_group_TTL_survives_restart");passed.put("changed_group_TTL_survives_restart");
        try(IdMap map=new IdMap(context,2)){TLRPC.ChatFull cached=cachedFullGroup(map.local("conversation",state.getString("native_created_group")));check(cached!=null && cached.ttl_period==3600 && cached.participants.participants.stream().anyMatch(p->p instanceof TLRPC.TL_chatParticipantAdmin),"full_group_survives_restart");}passed.put("full_group_survives_restart");
        stage="Telegram_UI_cancellation_restart";
        try(IdMap map=new IdMap(context,2)){check(UserConfig.getInstance(2).getClientUserId()==map.local("user",state.getString("user")),"Telegram_identity_restored");}passed.put("Telegram_identity_restored");
        try(OutboxStore store=new OutboxStore(context,2)){
            long deadline=System.currentTimeMillis()+45000;while(store.pendingUi(state.getString("user"))!=0 && System.currentTimeMillis()<deadline)Thread.sleep(100);
            check(store.pendingUi(state.getString("user"))==0,"UI_cleanup_durably_acknowledged");
            OutboxStore.Entry committed=store.get(state.getString("user"),clientId(state.getString("user"),state.getLong("ui_committed_random")));
            check(committed.state==OutboxStore.SENT && committed.server.equals(state.getString("ui_committed_server")),"UI_committed_send_preserved");
            MessagesStorage storage=MessagesStorage.getInstance(2);check(storage.getMessage(state.getLong("ui_dialog"),-91001)==null && storage.getMessage(state.getLong("ui_dialog"),-91002)==null,"negative_UI_rows_resolved");
            TLRPC.Message cached=storage.getMessage(state.getLong("ui_dialog"),committed.ordinal);check(cached!=null && cached.message.equals("Committed UI cancellation"),"committed_UI_message_cached");
            OutboxStore.Entry deleted=store.get(state.getString("user"),clientId(state.getString("user"),state.getLong("ui_deleted_random")));
            check(deleted.state==OutboxStore.SENT && deleted.server.equals(state.getString("ui_deleted_server")),"deleted_committed_send_keeps_ack");
            check(storage.getMessage(state.getLong("ui_dialog"),-91003)==null && storage.getMessage(state.getLong("ui_dialog"),state.getInt("ui_deleted_ordinal"))==null,"server_deleted_UI_not_revived");
        }
        List<Models.Message> uiHistory=b.messages(state.getString("token_group"),null,100);
        check(uiHistory.size()==2 && uiHistory.stream().filter(m->m.deleted && m.id.equals(state.optString("ui_deleted_server"))).count()==1,"UI_cancel_no_server_message_created");
        passed.put("server_deleted_UI_not_revived").put("deleted_committed_send_keeps_ack");
        passed.put("UI_cancel_cleanup_after_process_death").put("committed_UI_restored_after_process_death").put("UI_journal_ack_after_cache_proof");
        List<Models.Message> uncertainHistory=b.messages(state.getString("uncertain_group"),null,100);
        check(uncertainHistory.size()==1 && uncertainHistory.get(0).id.equals(state.getString("uncertain_server")) && uncertainHistory.get(0).ordinal==recovered.id,"lost_ack_no_duplicate");
        passed.put("lost_acknowledgement_recovery");
        List<Models.Message> messages=b.messages(state.getString("group"),null,100);
        check(messages.size()==4 && messages.get(0).text.equals("Offline two") && messages.get(1).text.equals("Offline one") && messages.get(2).text.equals("Realtime incoming") && messages.get(3).text.equals("Edited ویرایش"),"persisted_history");
        JSONObject editState=read("vyrn-edit-test.json");TLRPC.Message savedEdit=MessagesStorage.getInstance(2).getMessage(editState.getLong("dialog"),editState.getInt("ordinal"));
        check(savedEdit!=null&&savedEdit.message.equals(editState.getString("text"))&&savedEdit.edit_date>0,"native_edit_survives_restart");passed.put("native_edit_survives_restart");
        check(savedEdit.params!=null&&savedEdit.params.containsKey(VyrnBridge.EDIT_SNAPSHOT),"edit_provenance_survives_restart");passed.put("edit_provenance_survives_restart");
        JSONObject snapshot=new JSONObject(savedEdit.params.get(VyrnBridge.EDIT_SNAPSHOT));MessageObject restoredEdit=new MessageObject(2,savedEdit,false,false);VyrnBridge.captureEdit(2,restoredEdit);
        CompletableFuture<Integer> editQueued=new CompletableFuture<>();AndroidUtilities.runOnUIThread(()->{try{org.telegram.ui.ActionBar.BaseFragment fragment=(org.telegram.ui.ActionBar.BaseFragment)Class.forName("org.telegram.ui.LaunchActivity").getMethod("getSafeLastFragment").invoke(null);editQueued.complete(SendMessagesHelper.getInstance(2).editMessage(restoredEdit,"ویرایش پس از بازگشت",false,fragment,null,0,0));}catch(Exception failure){editQueued.completeExceptionally(failure);}});
        check(editQueued.get(15,TimeUnit.SECONDS)!=0,"restored_edit_dispatched");long editDeadline=System.currentTimeMillis()+30000;Models.Message afterRestart=b.message(editState.getString("server"));while(afterRestart.revision==snapshot.getInt("revision")&&System.currentTimeMillis()<editDeadline){Thread.sleep(100);afterRestart=b.message(editState.getString("server"));}
        check(afterRestart.revision==snapshot.getInt("revision")+1&&afterRestart.text.equals("ویرایش پس از بازگشت"),"cached_native_edit_after_restart");passed.put("cached_native_edit_after_restart");
        JSONObject deletedState=read("vyrn-delete-test.json");check(b.message(deletedState.getString("own")).deleted&&b.message(deletedState.getString("other")).deleted&&MessagesStorage.getInstance(2).getMessage(deletedState.getLong("dialog"),deletedState.getInt("own_id"))==null&&MessagesStorage.getInstance(2).getMessage(deletedState.getLong("dialog"),deletedState.getInt("other_id"))==null,"native_deletion_survives_restart");passed.put("native_deletion_survives_restart");
        org.telegram.tgnet.SerializedData prefetched=new org.telegram.tgnet.SerializedData(android.util.Base64.decode(deletedState.getString("prefetched"),android.util.Base64.NO_WRAP));TLRPC.Message stale=TLRPC.Message.TLdeserialize(prefetched,prefetched.readInt32(true),true);prefetched.cleanup();stale.dialog_id=deletedState.getLong("dialog");verifyDeletedCacheWriters(stale.dialog_id,stale,"restart_prefetched");
        verifyQueuedDeletion(a,deletedState.getString("group"),false);verifyQueuedDeletion(a,deletedState.getString("group"),true);
        try(OutboxStore store=new OutboxStore(context,2)){
            OutboxStore.Entry sent=store.get(state.getString("user"),clientId(state.getString("user"),state.getLong("outbox_first")));
            check(sent.state==OutboxStore.SENT && sent.text==null,"outbox_ack_persisted_body_removed");
        }
        passed.put("outbox_process_restart_delivery").put("outbox_no_duplicate").put("acknowledged_payload_removed");
        IdMap map=new IdMap(context,9001);
        try {check(map.remote("message",state.getInt("oldest_ordinal")).equals(state.getString("oldest")),"persisted_UI_identifier");}finally{map.close();}
        passed.put("process_restart_Keystore_session").put("process_restart_history");
        passed.put("process_restart_UI_identifiers");
        stage="delete_and_revoke";
        a.request("DELETE","/v1/messages/"+state.getString("message"),null,true);
        check(b.message(state.getString("message")).deleted,"delete_tombstone");
        a.request("POST","/v1/auth/logout",new JSONObject(),true);
        boolean revoked=false;
        try { a.me(); } catch(RestRepository.ApiException e) { revoked=e.status==401; }
        check(revoked,"revoked_session_denied");
        b.request("POST","/v1/auth/logout",new JSONObject(),true);
        a.vault.clear(); b.vault.clear();
        VyrnBridge.forAccount(2).repository.vault.clear();
        passed.put("delete_tombstone").put("session_revocation");
        Files.delete(file("vyrn-test-state.json").toPath());
    }
}
