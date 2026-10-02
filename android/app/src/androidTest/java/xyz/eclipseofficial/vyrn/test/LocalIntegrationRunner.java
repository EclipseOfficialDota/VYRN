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
            if(failure instanceof RestRepository.ApiException)result.putInt("failure_status",((RestRepository.ApiException)failure).status);
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
            upgraded.getWritableDatabase().setVersion(2);
        }
        try(OutboxStore upgraded=new OutboxStore(context,9009)){
            OutboxStore.Entry legacy=upgraded.get(fixtureUser,legacyClient);
            check(legacy.state==OutboxStore.SENDING && !legacy.cancellation && legacy.text.equals("Legacy pending"),"schema2_upgrade_preserves_claim");
            check(upgraded.requestCancellation(fixtureUser,legacyClient).cancellation,"schema2_cancel_intent_available");
            upgraded.getWritableDatabase().execSQL("DROP TABLE ui_cancellations");upgraded.getWritableDatabase().setVersion(3);
        }
        passed.put("populated_schema2_cancel_upgrade");
        try(OutboxStore upgraded=new OutboxStore(context,9009)){check(upgraded.get(fixtureUser,legacyClient).cancellation && upgraded.pendingUi(fixtureUser)==0,"schema3_ui_journal_upgrade");}
        passed.put("populated_schema3_UI_journal_upgrade");
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
        String createdGroup=createGroupThroughTelegram(a,b,tb.getString("user_id"));
        verifyNativeTextEdit(a,createdGroup,ta.getString("user_id"));
        verifyNativeDeletion(a,b,createdGroup,ta.getString("user_id"));
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
    private TLRPC.TL_error editFailure(TLRPC.TL_messages_editMessage request) throws Exception {
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
            repository.request("DELETE","/v1/messages/"+server,null,true);
            MessagesStorage storage=MessagesStorage.getInstance(2);boolean fenced=false;long deadline=System.currentTimeMillis()+10000;
            while(!fenced&&System.currentTimeMillis()<deadline){CompletableFuture<Boolean> stored=new CompletableFuture<>();storage.getStorageQueue().postRunnable(()->{try{org.telegram.SQLite.SQLiteCursor cursor=storage.getDatabase().queryFinalized("SELECT 1 FROM vyrn_deleted_messages WHERE uid=? AND mid=?",dialog,prefetched.getId());try{stored.complete(cursor.next());}finally{cursor.dispose();}}catch(Exception failure){stored.completeExceptionally(failure);}});fenced=stored.get(5,TimeUnit.SECONDS);if(!fenced)Thread.sleep(50);}
            check(fenced,"authoritative_fence_before_UI_release");release.countDown();deleted.get(15,TimeUnit.SECONDS);CompletableFuture<Boolean> displayed=new CompletableFuture<>();AndroidUtilities.runOnUIThread(()->displayed.complete(true));displayed.get(10,TimeUnit.SECONDS);awaitStorage(storage);
            String name=edit?"queued_edit_not_revived":"queued_new_not_revived";check(!appeared.get()&&storage.getMessage(dialog,prefetched.getId())==null,name);passed.put(name);
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
            JSONObject raw=(JSONObject)a.request("GET","/v1/messages/"+messages.get(0).id,null,true);check(java.time.Duration.between(java.time.Instant.parse(raw.getString("created_at")),java.time.Instant.parse(raw.getString("expires_at"))).getSeconds()==86400,"first_message_uses_creation_TTL");
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
            check(java.time.Duration.between(java.time.Instant.parse(timed.getString("created_at")),java.time.Instant.parse(timed.getString("expires_at"))).getSeconds()==3600,"new_message_uses_changed_TTL");passed.put("MessagesController_group_TTL").put("group_TTL_disable").put("group_TTL_preserves_title").put("new_message_uses_changed_TTL");
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
    }

    private void restart() throws Exception {
        stage="process_restart_session";
        JSONObject state=read("vyrn-test-state.json");
        check(state.getInt("process_id")!=android.os.Process.myPid(),"new_process");
        RestRepository a=repo(9001), b=repo(9002);
        check(a.me().id.equals(state.getString("user")),"persisted_session");
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
