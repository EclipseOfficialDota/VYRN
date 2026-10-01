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
import java.net.URL;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.CertPathValidatorException;
import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.BuildConfig;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import xyz.eclipseofficial.vyrn.compat.VyrnBridge;
import xyz.eclipseofficial.vyrn.data.IdMap;
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
            if(phase.equals("first")) first(); else if(phase.equals("restart")) restart(); else throw new IllegalArgumentException();
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
        // Keep the real hostname/SNI. Strengthen this test connection to system roots only,
        // so the failure proves client certificate validation rather than a server SNI error.
        KeyStore systemRoots=KeyStore.getInstance("AndroidCAStore");systemRoots.load(null);
        TrustManagerFactory trust=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());trust.init(systemRoots);
        SSLContext strictTLS=SSLContext.getInstance("TLS");strictTLS.init(null,trust.getTrustManagers(),null);
        HttpsURLConnection untrusted=(HttpsURLConnection)new URL("https://localhost:18443/health/ready").openConnection();
        untrusted.setSSLSocketFactory(strictTLS.getSocketFactory());
        untrusted.setConnectTimeout(5000); untrusted.setReadTimeout(5000);
        boolean rejected=false;
        try { untrusted.getInputStream().close(); } catch(SSLException expected) {
            for(Throwable cause=expected;cause!=null;cause=cause.getCause()) if(cause instanceof CertificateException || cause instanceof CertPathValidatorException) rejected=true;
        } finally { untrusted.disconnect(); }
        check(rejected,"untrusted_certificate_rejected");
        passed.put("cleartext_disabled").put("untrusted_certificate_rejected");

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
            Future<Models.User> x=pool.submit(()->a.me()), y=pool.submit(()->a.me());
            check(x.get().id.equals(ta.getString("user_id")) && y.get().id.equals(ta.getString("user_id")),"parallel_authenticated_requests");
        } finally { pool.shutdownNow(); }
        check(!oldRefresh.equals(a.vault.load().getString("refresh_token")),"refresh_rotated");
        check(repo(9001).me().id.equals(ta.getString("user_id")),"vault_reopened");
        passed.put("concurrent_refresh_rotation");

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
            VyrnBridge.dispatch(2,new TLRPC.TL_messages_getDialogs(),(value,error)->{if(error!=null)response.completeExceptionally(new AssertionError());else response.complete(value);},null);
            TLRPC.TL_messages_dialogs dialogs=(TLRPC.TL_messages_dialogs)response.get(30,TimeUnit.SECONDS);
            boolean mapped=false;
            for(TLRPC.Message item:dialogs.messages)if(item.id==history.get(0).ordinal && item.message.equals("three"))mapped=true;
            check(mapped,"Telegram_top_message_id");
        } finally {bridge.repository.vault.clear();}
        passed.put("ordered_UI_identifiers").put("history_pagination").put("Telegram_dialog_adapter");
        write("vyrn-test-state.json",new JSONObject().put("group",groupId).put("message",messageId).put("oldest",oldest).put("oldest_ordinal",upper).put("user",ta.getString("user_id")).put("process_id",android.os.Process.myPid()));
        Files.delete(file("vyrn-test-fixture.json").toPath());
    }

    private void restart() throws Exception {
        stage="process_restart_session";
        JSONObject state=read("vyrn-test-state.json");
        check(state.getInt("process_id")!=android.os.Process.myPid(),"new_process");
        RestRepository a=repo(9001), b=repo(9002);
        check(a.me().id.equals(state.getString("user")),"persisted_session");
        List<Models.Message> messages=b.messages(state.getString("group"),null,100);
        check(messages.size()==1 && messages.get(0).text.equals("Edited ویرایش"),"persisted_history");
        IdMap map=new IdMap(context,9001);
        try {check(map.remote("message",state.getInt("oldest_ordinal")).equals(state.getString("oldest")),"persisted_UI_identifier");}finally{map.close();}
        passed.put("process_restart_Keystore_session").put("process_restart_history");
        passed.put("process_restart_UI_identifiers");
        stage="delete_and_revoke";
        a.request("DELETE","/v1/messages/"+state.getString("message"),null,true);
        check(b.messages(state.getString("group"),null,100).get(0).deleted,"delete_tombstone");
        a.request("POST","/v1/auth/logout",new JSONObject(),true);
        boolean revoked=false;
        try { a.me(); } catch(RestRepository.ApiException e) { revoked=e.status==401; }
        check(revoked,"revoked_session_denied");
        b.request("POST","/v1/auth/logout",new JSONObject(),true);
        a.vault.clear(); b.vault.clear();
        passed.put("delete_tombstone").put("session_revocation");
        Files.delete(file("vyrn-test-state.json").toPath());
    }
}
