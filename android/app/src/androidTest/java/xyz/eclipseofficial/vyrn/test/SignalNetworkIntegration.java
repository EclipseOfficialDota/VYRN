package xyz.eclipseofficial.vyrn.test;

import android.content.Context;
import android.util.Base64;
import android.database.sqlite.SQLiteDatabase;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.io.File;
import java.util.Arrays;
import java.util.UUID;
import java.util.function.BiConsumer;
import org.json.JSONArray;
import org.json.JSONObject;
import org.signal.libsignal.protocol.*;
import org.signal.libsignal.protocol.message.*;
import xyz.eclipseofficial.vyrn.data.*;

/** Actual HTTPS key/ciphertext delivery and JNI decryption; private-chat UI remains disabled. */
final class SignalNetworkIntegration {
    private static JSONObject recipient(SignalDeviceKeys keys,CiphertextMessage message) throws Exception {return new JSONObject().put("user_id",keys.user).put("device_id",keys.device).put("version",1).put("type",message.getType()).put("ciphertext",Base64.encodeToString(message.serialize(),Base64.NO_WRAP));}
    private static JSONObject envelope(SignalDeviceKeys sender,SignalProtocolAddress address,JSONObject devices) throws Exception {return new JSONObject().put("protocol","signal-v2").put("sender",new JSONObject().put("user_id",sender.user).put("device_id",sender.device).put("signal_address_id",address.getName()).put("version",1)).put("devices",devices);}
    private static byte[] decrypt(SessionCipher cipher,JSONObject ciphertext) throws Exception {
        byte[] bytes=Base64.decode(ciphertext.getString("ciphertext"),Base64.NO_WRAP);
        if(ciphertext.getInt("type")==CiphertextMessage.PREKEY_TYPE)return cipher.decrypt(new PreKeySignalMessage(bytes));
        if(ciphertext.getInt("type")==CiphertextMessage.WHISPER_TYPE)return cipher.decrypt(new SignalMessage(bytes));
        throw new IllegalArgumentException("unsupported_signal_type");
    }
    private static RestRepository register(Context context,JSONObject fixture,int slot,String suffix) throws Exception {
        RestRepository repo=new RestRepository(new TokenVault(context,slot));repo.vault.clear();
        JSONObject tokens=(JSONObject)repo.request("POST","/v1/auth/register",new JSONObject().put("email",fixture.getString("email").replace("@",suffix+"@"))
            .put("password",fixture.getString("password")).put("invite_code",fixture.getString("invite")).put("display_name","Signal test "+suffix)
            .put("device_id",repo.vault.device()).put("device_name","VYRN local Signal network test"),false);
        tokens.remove("recovery_code");repo.vault.save(tokens);return repo;
    }
    static void run(Context context,BiConsumer<Boolean,String> check) throws Exception {
        JSONObject fixture=new JSONObject(new String(Files.readAllBytes(new File(context.getFilesDir(),"vyrn-signal-network-fixture.json").toPath()),StandardCharsets.UTF_8));
        RestRepository a=register(context,fixture,9101,"a"),b=register(context,fixture,9102,"b");
        boolean handoff=false;
        try(SignalDeviceKeys alice=new SignalDeviceKeys(context,a);SignalDeviceKeys bob=new SignalDeviceKeys(context,b)){
            check.accept(alice.device.equals(a.vault.device()) && bob.device.equals(b.vault.device()),"signal_network_authenticated_device_binding");
            File aliceDatabase=new File(context.getNoBackupFilesDir(),"vyrn_signal_"+alice.user+"_"+alice.device+".db");
            try(SQLiteDatabase db=SQLiteDatabase.openDatabase(aliceDatabase.getAbsolutePath(),null,SQLiteDatabase.OPEN_READWRITE)){
                db.execSQL("CREATE TRIGGER fail_signal_ack BEFORE INSERT ON records WHEN NEW.kind='app:keys' AND NEW.name='acknowledged' BEGIN SELECT RAISE(ABORT,'fixture_ack_failure'); END");
            }
            boolean acknowledgementFailed=false;try{alice.publishInitial();}catch(SecurityException expected){acknowledgementFailed=true;}
            JSONArray published=alice.devices(alice.user);
            check.accept(acknowledgementFailed && alice.store.loadValue("keys","acknowledged")==null && alice.store.containsPreKey(1) && published.getJSONObject(0).getBoolean("ready"),"signal_network_server_commit_local_ack_failure");
            try(SQLiteDatabase db=SQLiteDatabase.openDatabase(aliceDatabase.getAbsolutePath(),null,SQLiteDatabase.OPEN_READWRITE)){db.execSQL("DROP TRIGGER fail_signal_ack");}
            SignalProtocolAddress aa=alice.publishInitial(),ba=bob.publishInitial();
            check.accept(aa.getName().equals(published.getJSONObject(0).getString("signal_address_id")) && alice.store.loadValue("keys","acknowledged")!=null,"signal_network_lost_ack_recovery_same_identity");
            check.accept(!aa.equals(ba),"signal_network_distinct_publication_addresses");
            boolean denied=false;try{alice.devices(bob.user);}catch(RestRepository.ApiException expected){denied=expected.status==404;}
            check.accept(denied,"signal_network_unrelated_roster_denied");
            String conversation=((JSONObject)a.request("POST","/v1/conversations",new JSONObject().put("kind","private").put("members",new JSONArray().put(bob.user)),true)).getString("id");
            JSONArray roster=alice.devices(bob.user);
            check.accept(roster.length()==1 && roster.getJSONObject(0).getBoolean("ready") && roster.getJSONObject(0).getString("signal_address_id").equals(ba.getName()),"signal_network_ready_roster");
            SignalDeviceKeys.ClaimedKey claimed=alice.claim(bob.user,bob.device,1);
            check.accept(claimed.address.equals(ba) && claimed.bundle.getPreKeyId()==1,"signal_network_real_prekey_claim");
            denied=false;try{alice.claim(bob.user,bob.device,2);}catch(RestRepository.ApiException expected){denied=expected.status==409;}
            check.accept(denied,"signal_network_stale_version_rejected");
            JSONObject response=(JSONObject)a.request("POST","/v1/users/"+bob.user+"/device-keys/"+bob.device+"/claim",new JSONObject().put("expected_version",1),true);
            JSONObject forged=new JSONObject(response.toString());byte[] signature=Base64.decode(forged.getJSONObject("signed_prekey").getString("signature"),Base64.NO_WRAP);signature[0]^=1;forged.getJSONObject("signed_prekey").put("signature",Base64.encodeToString(signature,Base64.NO_WRAP));
            denied=false;try{SignalDeviceKeys.parseClaim(forged,bob.user,bob.device,1);}catch(SecurityException expected){denied=true;}
            check.accept(denied,"signal_network_parser_rejects_forged_signature");
            forged=new JSONObject(response.toString()).put("user_id",alice.user);denied=false;try{SignalDeviceKeys.parseClaim(forged,bob.user,bob.device,1);}catch(SecurityException expected){denied=true;}
            check.accept(denied,"signal_network_parser_rejects_wrong_owner");
            forged=new JSONObject(response.toString()).put("registration_id",1.5);denied=false;try{SignalDeviceKeys.parseClaim(forged,bob.user,bob.device,1);}catch(SecurityException expected){denied=true;}
            check.accept(denied,"signal_network_parser_rejects_fractional_registration");
            forged=new JSONObject(response.toString());signature=Base64.decode(forged.getJSONObject("kyber_prekey").getString("signature"),Base64.NO_WRAP);signature[0]^=1;forged.getJSONObject("kyber_prekey").put("signature",Base64.encodeToString(signature,Base64.NO_WRAP));
            denied=false;try{SignalDeviceKeys.parseClaim(forged,bob.user,bob.device,1);}catch(SecurityException expected){denied=true;}
            check.accept(denied,"signal_network_parser_rejects_forged_kyber_signature");
            alice.store.transaction(()->{new SessionBuilder(alice.store,ba,aa).process(claimed.bundle);return null;});
            byte[] text="تبادل واقعی کلید با HTTPS".getBytes(StandardCharsets.UTF_8);
            CiphertextMessage ciphertext=alice.store.transaction(()->new SessionCipher(alice.store,aa,ba).encrypt(text));
            JSONObject input=new JSONObject().put("client_id",UUID.randomUUID().toString()).put("encrypted",true).put("payload",envelope(alice,aa,new JSONObject().put(ba.getName(),recipient(bob,ciphertext))));
            String message=((JSONObject)a.request("POST","/v1/conversations/"+conversation+"/messages",input,true)).getString("id");
            check.accept(message.equals(((JSONObject)a.request("POST","/v1/conversations/"+conversation+"/messages",input,true)).getString("id")),"signal_network_ciphertext_idempotent_send");
            JSONObject received=(JSONObject)b.request("GET","/v1/messages/"+message,null,true);
            JSONObject receivedCipher=received.getJSONObject("payload").getJSONObject("devices").getJSONObject(ba.getName());
            check.accept(received.getBoolean("encrypted") && !received.toString().contains(new String(text,StandardCharsets.UTF_8)) && receivedCipher.getString("ciphertext").equals(Base64.encodeToString(ciphertext.serialize(),Base64.NO_WRAP)),"signal_network_ciphertext_server_delivery");
            byte[] decoded=bob.store.transaction(()->decrypt(new SessionCipher(bob.store,ba,aa),receivedCipher));
            check.accept(Arrays.equals(text,decoded) && !bob.store.containsPreKey(1),"signal_network_PQXDH_decryption_consumes_private_prekey");
            check.accept(bob.publishInitial().equals(ba) && !bob.store.containsPreKey(1),"signal_network_publication_retry_preserves_consumed_key");
            SignalDeviceKeys.ClaimedKey next=alice.claim(bob.user,bob.device,1);
            check.accept(next.bundle.getPreKeyId()==3,"signal_network_publication_retry_no_resurrection");
            boolean ordered=true;for(int id=4;id<=100;id++)ordered &= alice.claim(bob.user,bob.device,1).bundle.getPreKeyId()==id;
            SignalDeviceKeys.ClaimedKey fallback=alice.claim(bob.user,bob.device,1);
            check.accept(ordered && fallback.bundle.getPreKeyId()==org.signal.libsignal.protocol.state.PreKeyBundle.NULL_PRE_KEY_ID,"signal_network_prekey_exhaustion_last_resort_bundle");
            alice.store.transaction(()->{new SessionBuilder(alice.store,ba,aa).process(fallback.bundle);return null;});
            byte[] fallbackMessage=alice.store.transaction(()->new SessionCipher(alice.store,aa,ba).encrypt(text).serialize());
            byte[] fallbackPlaintext=bob.store.transaction(()->new SessionCipher(bob.store,ba,aa).decrypt(new PreKeySignalMessage(fallbackMessage)));
            check.accept(Arrays.equals(text,fallbackPlaintext) && bob.store.containsKyberPreKey(1) && bob.store.containsSignedPreKey(1),"signal_network_last_resort_PQXDH_decryption");
            JSONObject original=a.vault.load();
            try{
                a.vault.save(b.vault.load());denied=false;try{alice.devices(bob.user);}catch(SecurityException expected){denied=true;}
                check.accept(denied,"signal_network_account_switch_guard");
                RestRepository other=new RestRepository(new TokenVault(context,9103));other.vault.clear();
                JSONObject login=(JSONObject)other.request("POST","/v1/auth/login",new JSONObject().put("email",fixture.getString("email").replace("@","a@")).put("password",fixture.getString("password")).put("device_id",other.vault.device()).put("device_name","Signal other session"),false);
                other.vault.save(login);a.vault.save(login);denied=false;try{alice.devices(bob.user);}catch(SecurityException expected){denied=true;}
                check.accept(denied,"signal_network_same_user_session_switch_guard");
                other.request("POST","/v1/auth/logout",new JSONObject(),true);other.vault.clear();
            }finally{a.vault.save(original);}
            JSONObject stale=a.vault.load();stale.put("access_token","invalid");a.vault.save(stale);
            check.accept(alice.devices(bob.user).length()==1 && a.vault.load().getString("session_id").equals(original.getString("session_id")),"signal_network_refresh_preserves_bound_session");
            RestRepository second=new RestRepository(new TokenVault(context,9104));second.vault.clear();
            second.vault.save((JSONObject)second.request("POST","/v1/auth/login",new JSONObject().put("email",fixture.getString("email").replace("@","b@")).put("password",fixture.getString("password")).put("device_id",alice.device).put("device_name","Signal recipient second device"),false));
            try(SignalDeviceKeys bob2=new SignalDeviceKeys(context,second)){
                denied=false;try{a.request("POST","/v1/conversations/"+conversation+"/messages",new JSONObject(input.toString()).put("client_id",UUID.randomUUID().toString()),true);}catch(RestRepository.ApiException expected){denied=expected.status==409;}
                check.accept(denied,"signal_network_unprepared_device_not_omitted");
                String unpreparedClient=UUID.randomUUID().toString();byte[] beforeRoster=alice.store.loadSession(ba).serialize();denied=false;
                try{new SignalMessages(alice).prepare(bob.user,conversation,unpreparedClient,"دستگاه آماده نیست");}catch(SecurityException expected){denied=true;}
                check.accept(denied && alice.store.loadValue("private-outgoing",unpreparedClient)==null && Arrays.equals(beforeRoster,alice.store.loadSession(ba).serialize()),"signal_auto_roster_unprepared_device_stops_before_encryption");
                SignalProtocolAddress b2a=bob2.publishInitial();
                check.accept(bob2.device.equals(alice.device) && !b2a.equals(aa) && !bob2.device.equals(second.vault.device()),"signal_network_cross_account_device_UUID_addresses");
                try(SignalDeviceKeys localSecond=SignalDeviceKeys.openLocal(context,second)){check.accept(localSecond.device.equals(bob2.device)&&localSecond.store.getIdentityKeyPair().getPublicKey().equals(bob2.store.getIdentityKeyPair().getPublicKey()),"signal_local_authenticated_device_independent_of_requested_UUID");}
                automatic(context,fixture,a,b,second,alice,bob,bob2,conversation,check);
                SignalDeviceKeys.ClaimedKey b2key=alice.claim(bob2.user,bob2.device,1);
                alice.store.transaction(()->{new SessionBuilder(alice.store,b2a,aa).process(b2key.bundle);return null;});
                JSONObject copies=alice.store.transaction(()->new JSONObject().put(ba.getName(),recipient(bob,new SessionCipher(alice.store,aa,ba).encrypt(text))).put(b2a.getName(),recipient(bob2,new SessionCipher(alice.store,aa,b2a).encrypt(text))));
                JSONObject multiple=new JSONObject().put("client_id",UUID.randomUUID().toString()).put("encrypted",true).put("payload",envelope(alice,aa,copies));
                String multipleId=((JSONObject)a.request("POST","/v1/conversations/"+conversation+"/messages",multiple,true)).getString("id");
                JSONObject firstCopy=((JSONObject)b.request("GET","/v1/messages/"+multipleId,null,true)).getJSONObject("payload").getJSONObject("devices").getJSONObject(ba.getName());
                JSONObject secondCopy=((JSONObject)second.request("GET","/v1/messages/"+multipleId,null,true)).getJSONObject("payload").getJSONObject("devices").getJSONObject(b2a.getName());
                byte[] firstText=bob.store.transaction(()->decrypt(new SessionCipher(bob.store,ba,aa),firstCopy));
                byte[] secondText=bob2.store.transaction(()->decrypt(new SessionCipher(bob2.store,b2a,aa),secondCopy));
                check.accept(Arrays.equals(firstText,text) && Arrays.equals(secondText,text) && !firstCopy.getString("ciphertext").equals(secondCopy.getString("ciphertext")),"signal_network_two_recipient_devices_decrypt_server_copies");
                codec(context,a,b,second,alice,bob,bob2,conversation,Arrays.asList(fallback,b2key),check);
                second.request("POST","/v1/auth/logout",new JSONObject(),true);
                denied=false;try{a.request("POST","/v1/conversations/"+conversation+"/messages",new JSONObject(multiple.toString()).put("client_id",UUID.randomUUID().toString()),true);}catch(RestRepository.ApiException expected){denied=expected.status==409;}
                check.accept(denied,"signal_network_revoked_recipient_copy_rejected");
                check.accept(multipleId.equals(((JSONObject)a.request("POST","/v1/conversations/"+conversation+"/messages",multiple,true)).getString("id")),"signal_network_committed_retry_after_device_revocation");
            }finally{second.vault.clear();}
            prepareRestart(context,a,b,alice,bob,conversation,check);handoff=true;
        }finally{
            if(!handoff)cleanup(context);
        }
    }

    private static final String RESTART_TEXT="بازیابی پیام خصوصی 🔐";
    private static void automatic(Context context,JSONObject fixture,RestRepository a,RestRepository b,RestRepository second,SignalDeviceKeys alice,SignalDeviceKeys bob,SignalDeviceKeys bob2,String conversation,BiConsumer<Boolean,String> check) throws Exception {
        RestRepository other=new RestRepository(new TokenVault(context,9105));other.vault.clear();
        other.vault.save((JSONObject)other.request("POST","/v1/auth/login",new JSONObject().put("email",fixture.getString("email").replace("@","a@")).put("password",fixture.getString("password")).put("device_id",other.vault.device()).put("device_name","Signal producer second device"),false));
        try(SignalDeviceKeys alice2=new SignalDeviceKeys(context,other)){
        boolean unprepared=false;try{alice.targets(bob.user);}catch(SecurityException expected){unprepared=true;}
        check.accept(unprepared,"signal_auto_roster_sender_other_device_must_be_ready");alice2.publishInitial();
        SignalMessages sending=new SignalMessages(alice);String client=UUID.randomUUID().toString(),text="فهرست خودکار دستگاه‌ها";
        String body=sending.prepare(bob.user,conversation,client,text);
        JSONObject copies=new JSONObject(body).getJSONObject("payload").getJSONObject("devices");
        check.accept(copies.length()==3 && alice.store.containsSession(new SignalProtocolAddress(bob2.publishInitial().getName(),1)),"signal_auto_roster_claims_new_device_and_complete_fanout");
        String message=sending.send(client);
        JSONArray senders=bob.devices(alice.user);JSONObject senderMetadata=null;
        for(int i=0;i<senders.length();i++)if(alice.device.equals(senders.getJSONObject(i).getString("device_id")))senderMetadata=new JSONObject(senders.getJSONObject(i).toString()).put("user_id",alice.user).put("prekey",JSONObject.NULL);
        SignalDeviceKeys.ClaimedKey sender=SignalDeviceKeys.parseClaim(senderMetadata,alice.user,alice.device,1);
        check.accept(text.equals(new SignalMessages(bob).receive(alice.user,conversation,(JSONObject)b.request("GET","/v1/messages/"+message,null,true),sender)) && text.equals(new SignalMessages(bob2).receive(alice.user,conversation,(JSONObject)second.request("GET","/v1/messages/"+message,null,true),sender)),"signal_auto_roster_new_session_ciphertext_two_device_delivery");
        check.accept(text.equals(new SignalMessages(alice2).receive(bob.user,conversation,(JSONObject)other.request("GET","/v1/messages/"+message,null,true),sender)),"signal_auto_roster_sender_other_device_decrypts_own_history");
        other.request("POST","/v1/auth/logout",new JSONObject(),true);other.vault.clear();
        check.accept(body.equals(sending.prepare(bob.user,conversation,client,text)) && message.equals(sending.send(client)),"signal_auto_roster_committed_retry_after_sender_device_logout");
        java.util.List<SignalDeviceKeys.ClaimedKey> metadata=alice.targets(bob.user);
        boolean noClaims=metadata.size()==2;for(SignalDeviceKeys.ClaimedKey key:metadata)noClaims &= key.bundle.getPreKeyId()==org.signal.libsignal.protocol.state.PreKeyBundle.NULL_PRE_KEY_ID;
        check.accept(noClaims && alice.claim(bob2.user,bob2.device,1).bundle.getPreKeyId()==2,"signal_auto_roster_existing_sessions_do_not_consume_prekeys");
        JSONObject original=a.vault.load();try{a.vault.save(b.vault.load());boolean denied=false;try{sending.prepare(bob.user,conversation,client,text);}catch(SecurityException expected){denied=true;}check.accept(denied,"signal_auto_roster_cached_retry_original_session_guard");}finally{a.vault.save(original);}
        check.accept(body.equals(sending.prepare(bob.user,conversation,client,text)),"signal_auto_roster_retry_reuses_persisted_ciphertext");
        }finally{try{if(other.vault.load().has("access_token"))other.request("POST","/v1/auth/logout",new JSONObject(),true);}finally{other.vault.clear();}}
    }
    private static String fingerprint(byte[] value) throws Exception {return Base64.encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(value),Base64.NO_WRAP);}
    private static void prepareRestart(Context context,RestRepository a,RestRepository b,SignalDeviceKeys alice,SignalDeviceKeys bob,String conversation,BiConsumer<Boolean,String> check) throws Exception {
        SignalMessages outgoing=new SignalMessages(alice),incoming=new SignalMessages(bob);
        java.util.List<SignalDeviceKeys.ClaimedKey> targets=java.util.Collections.singletonList(alice.claim(bob.user,bob.device,1));
        SignalDeviceKeys.ClaimedKey sender=bob.claim(alice.user,alice.device,1);
        String pending=UUID.randomUUID().toString(),committed=UUID.randomUUID().toString();
        String body=outgoing.prepare(bob.user,conversation,pending,RESTART_TEXT,targets);
        outgoing.prepare(bob.user,conversation,committed,RESTART_TEXT,targets);String message=outgoing.send(committed);
        JSONObject delivered=(JSONObject)b.request("GET","/v1/messages/"+message,null,true);
        check.accept(RESTART_TEXT.equals(incoming.receive(alice.user,conversation,delivered,sender)),"signal_codec_restart_fixture_committed_cache");
        JSONObject state=new JSONObject().put("alice",alice.user).put("bob",bob.user).put("conversation",conversation).put("pending",pending).put("committed",committed).put("message",message).put("body",body).put("delivered",delivered).put("sender_address",sender.address.getName()).put("recipient_address",targets.get(0).address.getName())
            .put("sender_ratchet",fingerprint(alice.store.loadSession(targets.get(0).address).serialize())).put("receiver_ratchet",fingerprint(bob.store.loadSession(sender.address).serialize()));
        // Test-only app-private state: no credentials or decrypted message text.
        Files.write(new File(context.getFilesDir(),"vyrn-signal-network-state.json").toPath(),state.toString().getBytes(StandardCharsets.UTF_8));
        check.accept(alice.store.loadValue("private-outgoing",pending)!=null && bob.store.loadValue("private-cache",message+"_1")!=null,"signal_codec_restart_fixture_pending_ciphertext");
    }
    static void offlineRestart(Context context,BiConsumer<Boolean,String> check) throws Exception {
        JSONObject state=new JSONObject(new String(Files.readAllBytes(new File(context.getFilesDir(),"vyrn-signal-network-state.json").toPath()),StandardCharsets.UTF_8));
        RestRepository a=new RestRepository(new TokenVault(context,9101)),b=new RestRepository(new TokenVault(context,9102));
        boolean unavailable=false;try{a.me();}catch(java.io.IOException expected){unavailable=true;}check.accept(unavailable,"signal_offline_actual_authenticated_request_unavailable");
        try(SignalDeviceKeys alice=SignalDeviceKeys.openLocal(context,a);SignalDeviceKeys bob=SignalDeviceKeys.openLocal(context,b)){
            check.accept(alice.user.equals(state.getString("alice"))&&bob.user.equals(state.getString("bob")),"signal_offline_existing_authenticated_devices");
            SignalMessages outgoing=new SignalMessages(alice),incoming=new SignalMessages(bob);String message=state.getString("message");
            check.accept(RESTART_TEXT.equals(outgoing.cachedText(message))&&RESTART_TEXT.equals(incoming.cachedText(message)),"signal_offline_cold_encrypted_history_both_devices");
            check.accept(state.getString("body").equals(outgoing.prepare(bob.user,state.getString("conversation"),state.getString("pending"),RESTART_TEXT,null))&&state.getString("sender_ratchet").equals(fingerprint(alice.store.loadSession(new SignalProtocolAddress(state.getString("recipient_address"),1)).serialize())),"signal_offline_pending_exact_ciphertext_without_ratchet_advance");
            check.accept(RESTART_TEXT.equals(incoming.receive(alice.user,state.getString("conversation"),state.getJSONObject("delivered"),null))&&state.getString("receiver_ratchet").equals(fingerprint(bob.store.loadSession(new SignalProtocolAddress(state.getString("sender_address"),1)).serialize())),"signal_offline_cached_duplicate_without_directory");
            JSONObject original=a.vault.load();try{a.vault.save(new JSONObject(original.toString()).put("session_id",UUID.randomUUID().toString()));boolean denied=false;try{outgoing.cachedText(message);}catch(SecurityException expected){denied=true;}check.accept(denied,"signal_offline_changed_session_cannot_read_history");denied=false;try(SignalDeviceKeys ignored=SignalDeviceKeys.openLocal(context,a)){}catch(SecurityException expected){denied=true;}check.accept(denied,"signal_offline_new_session_cannot_reuse_old_device_proof");}finally{a.vault.save(original);}
            try{a.vault.save(b.vault.load());boolean denied=false;try{outgoing.cachedText(message);}catch(SecurityException expected){denied=true;}check.accept(denied,"signal_offline_changed_account_cannot_read_history");}finally{a.vault.save(original);}
            byte[] binding=alice.store.loadValue("keys","authenticated-device");
            try{
                alice.store.transaction(()->{alice.store.saveValue("keys","authenticated-device",new JSONObject().put("user",UUID.randomUUID().toString()).put("device",alice.device).toString().getBytes(StandardCharsets.UTF_8));return null;});
                boolean denied=false;try(SignalDeviceKeys ignored=SignalDeviceKeys.openLocal(context,a)){}catch(SecurityException expected){denied=true;}
                check.accept(denied&&!Arrays.equals(binding,alice.store.loadValue("keys","authenticated-device")),"signal_offline_mismatched_device_binding_not_replaced");
            }finally{alice.store.transaction(()->{alice.store.saveValue("keys","authenticated-device",binding);return null;});}
        }
        String missingUser=UUID.randomUUID().toString(),missingDevice=UUID.randomUUID().toString();File missing=new File(context.getNoBackupFilesDir(),"vyrn_signal_"+missingUser+"_"+missingDevice+".db");
        boolean denied=false;try(SignalStore ignored=SignalStore.openExisting(context,missingUser,missingDevice)){}catch(SecurityException expected){denied=true;}
        check.accept(denied&&!missing.exists(),"signal_offline_missing_store_does_not_create_identity");
        TokenVault replacement=new TokenVault(context,9198);replacement.clear();
        try{
            JSONObject before=b.vault.load(),next=a.vault.load();for(JSONObject value:new JSONObject[]{before,next}){value.remove("signal_device_id");value.remove("signal_device_session");}
            replacement.save(before);context.getSharedPreferences("vyrn_session_9198",Context.MODE_PRIVATE).edit().putString("tokens","corrupt").commit();
            replacement.save(next);check.accept(next.getString("user_id").equals(replacement.load().getString("user_id"))&&!replacement.load().has("signal_device_id"),"signal_new_login_can_replace_corrupt_unrelated_tokens");
        }finally{replacement.clear();}
    }
    static void restart(Context context,BiConsumer<Boolean,String> check) throws Exception {
        JSONObject state=new JSONObject(new String(Files.readAllBytes(new File(context.getFilesDir(),"vyrn-signal-network-state.json").toPath()),StandardCharsets.UTF_8));
        RestRepository a=new RestRepository(new TokenVault(context,9101)),b=new RestRepository(new TokenVault(context,9102));
        try(SignalDeviceKeys alice=new SignalDeviceKeys(context,a);SignalDeviceKeys bob=new SignalDeviceKeys(context,b)){
            check.accept(alice.user.equals(state.getString("alice")) && bob.user.equals(state.getString("bob")),"signal_codec_restart_original_account_binding");
            SignalMessages outgoing=new SignalMessages(alice),incoming=new SignalMessages(bob);String peer=state.getString("bob"),conversation=state.getString("conversation"),pending=state.getString("pending"),message=state.getString("message");
            check.accept(RESTART_TEXT.equals(outgoing.cachedText(message)) && RESTART_TEXT.equals(incoming.cachedText(message)),"signal_codec_restart_both_encrypted_history_caches");
            check.accept(state.getString("body").equals(outgoing.prepare(peer,conversation,pending,RESTART_TEXT,null)) && state.getString("sender_ratchet").equals(fingerprint(alice.store.loadSession(new SignalProtocolAddress(state.getString("recipient_address"),1)).serialize())),"signal_codec_restart_pending_exact_body_no_ratchet_advance");
            check.accept(RESTART_TEXT.equals(incoming.receive(alice.user,conversation,state.getJSONObject("delivered"),null)) && state.getString("receiver_ratchet").equals(fingerprint(bob.store.loadSession(new SignalProtocolAddress(state.getString("sender_address"),1)).serialize())),"signal_codec_restart_cached_duplicate_no_directory_or_ratchet");
            check.accept(message.equals(outgoing.send(state.getString("committed"))),"signal_codec_restart_acknowledged_retry_same_server_id");
            String pendingMessage=outgoing.send(pending);JSONObject delivered=(JSONObject)b.request("GET","/v1/messages/"+pendingMessage,null,true);
            check.accept(RESTART_TEXT.equals(incoming.receive(alice.user,conversation,delivered,null)) && pendingMessage.equals(outgoing.send(pending)),"signal_codec_restart_pending_out_of_order_delivery_once");
        }finally{cleanup(context);}
    }
    static void cleanup(Context context) throws Exception {
        Exception failure=null;
        for(int slot:new int[]{9101,9102}){RestRepository repository=new RestRepository(new TokenVault(context,slot));try{if(repository.vault.load().has("access_token"))repository.request("POST","/v1/auth/logout",new JSONObject(),true);}catch(Exception error){if(failure==null)failure=error;}finally{repository.vault.clear();}}
        if(failure!=null)throw failure;
    }

    private static void codec(Context context,RestRepository a,RestRepository b,RestRepository second,SignalDeviceKeys alice,SignalDeviceKeys bob,SignalDeviceKeys bob2,String conversation,java.util.List<SignalDeviceKeys.ClaimedKey> targets,BiConsumer<Boolean,String> check) throws Exception {
        SignalMessages sending=new SignalMessages(alice),receiving=new SignalMessages(bob),receiving2=new SignalMessages(bob2);
        SignalDeviceKeys.ClaimedKey senderKey=bob.claim(alice.user,alice.device,1);
        String client=UUID.randomUUID().toString(),text="پیام خصوصی پایدار 🔐";
        byte[] before=alice.store.loadSession(targets.get(0).address).serialize();
        File database=new File(context.getNoBackupFilesDir(),"vyrn_signal_"+alice.user+"_"+alice.device+".db");
        try(SQLiteDatabase db=SQLiteDatabase.openDatabase(database.getAbsolutePath(),null,SQLiteDatabase.OPEN_READWRITE)){db.execSQL("CREATE TRIGGER fail_private_outgoing BEFORE INSERT ON records WHEN NEW.kind='app:private-outgoing' BEGIN SELECT RAISE(ABORT,'fixture_outgoing_failure'); END");}
        boolean denied=false;try{sending.prepare(bob.user,conversation,client,text,targets);}catch(Exception expected){denied=true;}
        check.accept(denied && alice.store.loadValue("private-outgoing",client)==null && alice.store.loadValue("private-own",client)==null && Arrays.equals(before,alice.store.loadSession(targets.get(0).address).serialize()),"signal_codec_outgoing_failure_rolls_back_ratchet_and_cache");
        try(SQLiteDatabase db=SQLiteDatabase.openDatabase(database.getAbsolutePath(),null,SQLiteDatabase.OPEN_READWRITE)){db.execSQL("DROP TRIGGER fail_private_outgoing");}
        String body=sending.prepare(bob.user,conversation,client,text,targets);byte[] prepared=alice.store.loadSession(targets.get(0).address).serialize();
        check.accept(body.equals(sending.prepare(bob.user,conversation,client,text,targets)) && Arrays.equals(prepared,alice.store.loadSession(targets.get(0).address).serialize()) && !body.contains(text),"signal_codec_prepare_retry_exact_ciphertext_no_ratchet_advance");
        denied=false;try{sending.prepare(bob.user,conversation,client,"changed",targets);}catch(SecurityException expected){denied=true;}
        check.accept(denied,"signal_codec_client_id_plaintext_conflict");
        String message=sending.send(client);
        check.accept(message.equals(sending.send(client)) && text.equals(sending.cachedText(message)) && Arrays.equals(prepared,alice.store.loadSession(targets.get(0).address).serialize()),"signal_codec_http_retry_and_own_cache");
        JSONObject delivered=(JSONObject)b.request("GET","/v1/messages/"+message,null,true);
        JSONObject changed=new JSONObject(delivered.toString()).put("client_id",UUID.randomUUID().toString());
        byte[] receiveBefore=bob.store.loadSession(senderKey.address).serialize(),bindingBefore=bob.store.loadValue("private-peer",alice.user+"_"+alice.device);
        denied=false;try{receiving.receive(alice.user,conversation,changed,senderKey);}catch(SecurityException expected){denied=true;}
        check.accept(denied && Arrays.equals(receiveBefore,bob.store.loadSession(senderKey.address).serialize()) && Arrays.equals(bindingBefore,bob.store.loadValue("private-peer",alice.user+"_"+alice.device)),"signal_codec_authenticated_inner_rejection_rolls_back_receive");
        check.accept(text.equals(receiving.receive(alice.user,conversation,delivered,senderKey)) && text.equals(receiving2.receive(alice.user,conversation,(JSONObject)second.request("GET","/v1/messages/"+message,null,true),senderKey)),"signal_codec_two_device_authenticated_plaintext_delivery");
        byte[] after=bob.store.loadSession(senderKey.address).serialize();
        check.accept(text.equals(receiving.receive(alice.user,conversation,delivered,null)) && Arrays.equals(after,bob.store.loadSession(senderKey.address).serialize()) && text.equals(sending.receive(bob.user,conversation,(JSONObject)a.request("GET","/v1/messages/"+message,null,true),null)),"signal_codec_duplicate_and_producing_device_history_cache");
        changed=new JSONObject(delivered.toString()).put("id",UUID.randomUUID().toString());denied=false;try{receiving.receive(alice.user,conversation,changed,null);}catch(SecurityException expected){denied=true;}
        check.accept(denied,"signal_codec_ciphertext_message_id_rebound_rejected");
        try(SignalDeviceKeys reopened=new SignalDeviceKeys(context,b)){check.accept(text.equals(new SignalMessages(reopened).cachedText(message)) && text.equals(new SignalMessages(reopened).receive(alice.user,conversation,delivered,null)),"signal_codec_encrypted_cache_survives_store_reopen");}
        a.request("DELETE","/v1/messages/"+message,null,true);
        JSONObject tombstone=(JSONObject)b.request("GET","/v1/messages/"+message,null,true);
        check.accept(!tombstone.isNull("deleted_at") && tombstone.getInt("revision")>delivered.getInt("revision") && tombstone.getJSONObject("payload").length()==0,"signal_codec_server_deletion_tombstone_delivery");
        check.accept(receiving.receive(alice.user,conversation,tombstone,null)==null && receiving.cachedText(message)==null,"signal_codec_deletion_purges_decrypted_cache");
        denied=false;try{receiving.receive(alice.user,conversation,delivered,null);}catch(SecurityException expected){denied=true;}
        check.accept(denied,"signal_codec_deleted_history_cannot_resurrect");
        sending.receive(bob.user,conversation,tombstone,null);denied=false;try{sending.send(client);}catch(SecurityException expected){denied=true;}
        check.accept(denied && sending.cachedText(message)==null && alice.store.loadValue("private-outgoing",client)==null,"signal_codec_deleted_own_message_fences_outbox");
        a.request("PATCH","/v1/conversations/"+conversation,new JSONObject().put("disappearing_seconds",5),true);
        String expiresClient=UUID.randomUUID().toString();sending.prepare(bob.user,conversation,expiresClient,text,targets);String expiresMessage=sending.send(expiresClient);
        JSONObject expiring=(JSONObject)b.request("GET","/v1/messages/"+expiresMessage,null,true);
        File receiveDatabase=new File(context.getNoBackupFilesDir(),"vyrn_signal_"+bob.user+"_"+bob.device+".db");
        byte[] ratchet=bob.store.loadSession(senderKey.address).serialize();
        try(SQLiteDatabase db=SQLiteDatabase.openDatabase(receiveDatabase.getAbsolutePath(),null,SQLiteDatabase.OPEN_READWRITE)){db.execSQL("CREATE TRIGGER fail_private_cache BEFORE INSERT ON records WHEN NEW.kind='app:private-cache' BEGIN SELECT RAISE(ABORT,'fixture_cache_failure'); END");}
        denied=false;try{receiving.receive(alice.user,conversation,expiring,null);}catch(Exception expected){denied=true;}
        check.accept(denied && Arrays.equals(ratchet,bob.store.loadSession(senderKey.address).serialize()) && bob.store.loadValue("private-head",expiresMessage)==null && bob.store.loadValue("private-cache",expiresMessage+"_1")==null,"signal_codec_receive_cache_failure_rolls_back_ratchet");
        try(SQLiteDatabase db=SQLiteDatabase.openDatabase(receiveDatabase.getAbsolutePath(),null,SQLiteDatabase.OPEN_READWRITE)){db.execSQL("DROP TRIGGER fail_private_cache");}
        check.accept(!expiring.isNull("expires_at") && text.equals(receiving.receive(alice.user,conversation,expiring,null)) && text.equals(receiving2.receive(alice.user,conversation,expiring,senderKey)) && text.equals(sending.cachedText(expiresMessage)),"signal_codec_server_expiry_deadline_cached_all_devices");
        JSONObject extension=new JSONObject(expiring.toString()).put("expires_at",java.time.Instant.now().plusSeconds(3600).toString());
        check.accept(text.equals(receiving.receive(alice.user,conversation,extension,null)),"signal_codec_duplicate_expiry_cannot_extend_learned_deadline");
        long deadline=java.time.Instant.parse(expiring.getString("expires_at")).toEpochMilli();
        while(System.currentTimeMillis()<=deadline)android.os.SystemClock.sleep(Math.max(1,Math.min(100,deadline-System.currentTimeMillis()+1)));
        check.accept(receiving.cachedText(expiresMessage)==null && sending.cachedText(expiresMessage)==null && bob.store.loadValue("private-cache",expiresMessage+"_1")==null && alice.store.loadValue("private-own",expiresClient)==null && alice.store.loadValue("private-outgoing",expiresClient)==null,"signal_codec_offline_cache_read_expiry_purges_plaintext");
        check.accept(receiving2.pruneExpired()==1 && receiving2.pruneExpired()==0 && bob2.store.loadValue("private-cache",expiresMessage+"_1")==null,"signal_codec_idle_expiry_sweep_is_durable_idempotent");
        denied=false;try{sending.send(expiresClient);}catch(SecurityException expected){denied=true;}
        check.accept(denied,"signal_codec_expired_own_outbox_cannot_retry");
        check.accept(receiving.receive(alice.user,conversation,extension,null)==null && receiving.cachedText(expiresMessage)==null,"signal_codec_expired_stale_extension_cannot_resurrect");
        a.request("PATCH","/v1/conversations/"+conversation,new JSONObject().put("disappearing_seconds",0),true);
    }
}
