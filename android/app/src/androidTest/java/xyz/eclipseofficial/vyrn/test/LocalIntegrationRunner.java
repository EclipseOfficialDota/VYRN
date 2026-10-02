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
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import xyz.eclipseofficial.vyrn.compat.VyrnBridge;
import xyz.eclipseofficial.vyrn.data.IdMap;
import xyz.eclipseofficial.vyrn.data.OutboxStore;
import xyz.eclipseofficial.vyrn.data.RestRepository;
import xyz.eclipseofficial.vyrn.data.TokenVault;
import xyz.eclipseofficial.vyrn.domain.Models;

/** Runs only in the opt-in local TLS debug APK. No test secrets or test endpoints in release code. */
public final class LocalIntegrationRunner extends Instrumentation {
    private String phase, stage = "initialization";
    private Context context;
    private final JSONArray passed = new JSONArray();

    @Override public void onCreate(Bundle args) { super.onCreate(args); phase=args.getString("phase", "first"); start(); }

    @Override public void onStart() {
        context=getTargetContext();
        Bundle result=new Bundle();
        try {
            check(BuildConfig.VYRN_API_URL.equals("https://localhost:18443"), "local_endpoint_required");
            stage="foreground_activity";
            Intent launch=new Intent(Intent.ACTION_MAIN).setClassName(context,"org.telegram.ui.LaunchActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            check(startActivitySync(launch)!=null,"foreground_activity");
            waitForIdleSync();
            if(phase.equals("first")) first(); else if(phase.equals("offline")) offline(); else if(phase.equals("restart")) restart(); else throw new IllegalArgumentException();
            result.putString("vyrn_result", "passed");
            result.putString("checks", passed.toString());
            result.putInt("process_id", android.os.Process.myPid());
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            // Never return exception messages: network errors can contain headers or bodies.
            result.putString("vyrn_result", "failed");
            result.putString("stage", stage);
            result.putString("failure_type", failure.getClass().getSimpleName());
            StringBuilder trace=new StringBuilder();
            for(StackTraceElement frame:failure.getStackTrace()) if(frame.getClassName().startsWith("xyz.eclipseofficial")) trace.append(frame.toString()).append(";");
            result.putString("failure_location",trace.toString());
            finish(Activity.RESULT_CANCELED, result);
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
        try {
            CompletableFuture<TLObject> response=new CompletableFuture<>();
            VyrnBridge.dispatch(2,new TLRPC.TL_messages_getDialogs(),(value,error)->{if(error!=null){stage="Telegram_dialog_adapter_status_"+error.code;response.completeExceptionally(new AssertionError());}else response.complete(value);},null);
            TLRPC.TL_messages_dialogs dialogs=(TLRPC.TL_messages_dialogs)response.get(30,TimeUnit.SECONDS);
            boolean mapped=false;
            for(TLRPC.Message item:dialogs.messages)if(item.id==history.get(0).ordinal && item.message.equals("three"))mapped=true;
            check(mapped,"Telegram_top_message_id");
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
        String uncertainGroup=((JSONObject)a.request("POST","/v1/conversations",new JSONObject().put("kind","group").put("title","Lost acknowledgement").put("members",new JSONArray().put(tb.getString("user_id"))),true)).getString("id");
        long uncertainRandom=UUID.randomUUID().getMostSignificantBits();String uncertainClient=clientId(ta.getString("user_id"),uncertainRandom);
        try(OutboxStore store=new OutboxStore(context,2)){store.enqueue(ta.getString("user_id"),uncertainClient,uncertainGroup,"Lost acknowledgement");}
        // Simulate a server commit whose response was lost before the local acknowledgement commit.
        String uncertainServer=a.send(uncertainGroup,uncertainClient,"Lost acknowledgement");
        passed.put("bounded_outbox_retry_policy");
        write("vyrn-test-state.json",new JSONObject().put("uncertain_group",uncertainGroup).put("uncertain_random",uncertainRandom).put("uncertain_server",uncertainServer).put("group",groupId).put("message",messageId).put("oldest",oldest).put("oldest_ordinal",upper).put("user",ta.getString("user_id")).put("process_id",android.os.Process.myPid()));
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
    private String clientId(String user,long randomId){return UUID.nameUUIDFromBytes((user+":"+randomId).getBytes(StandardCharsets.UTF_8)).toString();}
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
            check(pending!=null && pending.state==OutboxStore.QUEUED && pending.attempts>0,"outbox_persisted_before_retry");
            OutboxStore.Entry later=store.get(state.getString("user"),clientId(state.getString("user"),second));
            check(later!=null && later.attempts==0,"outbox_conversation_order");
            check(store.next(UUID.randomUUID().toString())==null,"outbox_account_isolation");
            boolean conflict=false;try{store.enqueue(state.getString("user"),pending.client,pending.conversation,"different");}catch(IllegalStateException expected){conflict=true;}
            check(conflict,"outbox_idempotency_conflict");
        }
        passed.put("offline_UI_queue").put("no_premature_ack").put("bounded_backoff_started").put("conversation_send_order").put("account_isolation").put("retry_payload_immutable");
    }

    private void restart() throws Exception {
        stage="process_restart_session";
        JSONObject state=read("vyrn-test-state.json");
        check(state.getInt("process_id")!=android.os.Process.myPid(),"new_process");
        RestRepository a=repo(9001), b=repo(9002);
        check(a.me().id.equals(state.getString("user")),"persisted_session");
        stage="restart_outbox_reconnect";
        TLRPC.TL_updateShortSentMessage first=(TLRPC.TL_updateShortSentMessage)queueThroughUI(state,state.getLong("outbox_first"),"Offline one").get(45,TimeUnit.SECONDS);
        TLRPC.TL_updateShortSentMessage second=(TLRPC.TL_updateShortSentMessage)queueThroughUI(state,state.getLong("outbox_second"),"Offline two").get(45,TimeUnit.SECONDS);
        check(first.id<second.id,"reconnected_send_order");
        TLRPC.TL_updateShortSentMessage replay=(TLRPC.TL_updateShortSentMessage)queueThroughUI(state,state.getLong("outbox_first"),"Offline one").get(5,TimeUnit.SECONDS);
        check(replay.id==first.id,"outbox_completed_retry");
        JSONObject uncertain=new JSONObject().put("group",state.getString("uncertain_group"));
        TLRPC.TL_updateShortSentMessage recovered=(TLRPC.TL_updateShortSentMessage)queueThroughUI(uncertain,state.getLong("uncertain_random"),"Lost acknowledgement").get(45,TimeUnit.SECONDS);
        List<Models.Message> uncertainHistory=b.messages(state.getString("uncertain_group"),null,100);
        check(uncertainHistory.size()==1 && uncertainHistory.get(0).id.equals(state.getString("uncertain_server")) && uncertainHistory.get(0).ordinal==recovered.id,"lost_ack_no_duplicate");
        passed.put("lost_acknowledgement_recovery");
        List<Models.Message> messages=b.messages(state.getString("group"),null,100);
        check(messages.size()==4 && messages.get(0).text.equals("Offline two") && messages.get(1).text.equals("Offline one") && messages.get(2).text.equals("Realtime incoming") && messages.get(3).text.equals("Edited ویرایش"),"persisted_history");
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
