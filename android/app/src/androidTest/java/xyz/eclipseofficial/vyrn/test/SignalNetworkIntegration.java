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
    static RestRepository register(Context context,JSONObject fixture,int slot,String suffix) throws Exception {
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
                edits(context,a,b,alice,bob,bob2,conversation,check);
                SignalMessages verification=new SignalMessages(alice);JSONObject displayed=verification.verification(bob2.user,bob2.device);String displayedNumber=displayed.getString("fingerprint");byte[] deviceSession=alice.store.loadSession(b2a).serialize();
                check.accept(!displayed.getBoolean("verified"),"signal_additional_peer_device_does_not_inherit_verification");
                second.request("POST","/v1/auth/logout",new JSONObject(),true);
                JSONArray active=verification.verificationDevices(bob2.user);boolean removed=true;for(int i=0;i<active.length();i++)removed&=!bob2.device.equals(active.getJSONObject(i).getString("device"));
                check.accept(removed&&active.length()==1&&bob.device.equals(active.getJSONObject(0).getString("device")),"signal_verification_signed_roster_excludes_revoked_peer_device");
                denied=false;try{verification.verifyCurrentPeer(bob2.user,bob2.device,displayedNumber,true);}catch(SecurityException expected){denied="signal_device_not_ready".equals(expected.getMessage());}
                JSONObject unchanged=verification.verification(bob2.user,bob2.device);
                check.accept(denied&&!unchanged.getBoolean("verified")&&displayedNumber.equals(unchanged.getString("fingerprint"))&&Arrays.equals(deviceSession,alice.store.loadSession(b2a).serialize()),"signal_revoked_device_displayed_number_cannot_confirm_or_change_ratchet");
                try(SignalDeviceKeys reopened=SignalDeviceKeys.openLocal(context,a)){check.accept(!new SignalMessages(reopened).verification(bob2.user,bob2.device).getBoolean("verified"),"signal_revoked_device_rejection_preserves_encrypted_status_after_reopen");}
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
    private static final String STAGED_TEXT="پیام تازه در حالت آفلاین 🔐";
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
        SignalDeviceKeys.ClaimedKey sender=bob.senderKey(alice.user,alice.device,new JSONObject(body).getJSONObject("payload").getJSONObject("sender").getString("signal_address_id"),1);
        check.accept(sender.bundle.getPreKeyId()==org.signal.libsignal.protocol.state.PreKeyBundle.NULL_PRE_KEY_ID,"signal_reader_signed_metadata_without_prekey_claim");
        boolean readerDenied=false;try{bob.senderKey(alice.user,alice.device,UUID.randomUUID().toString(),1);}catch(SecurityException expected){readerDenied=true;}check.accept(readerDenied,"signal_reader_wrong_address_rejected");
        readerDenied=false;try{bob.senderKey(alice.user,alice.device,sender.address.getName(),2);}catch(SecurityException expected){readerDenied=true;}check.accept(readerDenied,"signal_reader_future_sender_version_rejected");
        check.accept(text.equals(new SignalMessages(bob).receive(alice.user,conversation,(JSONObject)b.request("GET","/v1/messages/"+message,null,true))) && text.equals(new SignalMessages(bob2).receive(alice.user,conversation,(JSONObject)second.request("GET","/v1/messages/"+message,null,true))),"signal_auto_roster_new_session_ciphertext_two_device_delivery");
        check.accept(text.equals(new SignalMessages(alice2).receive(bob.user,conversation,(JSONObject)other.request("GET","/v1/messages/"+message,null,true))),"signal_auto_roster_sender_other_device_decrypts_own_history");
        check.accept(bob.claim(alice.user,alice.device,1).bundle.getPreKeyId()==1,"signal_reader_automatic_receive_does_not_consume_sender_prekey");
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
        String pending=UUID.nameUUIDFromBytes((alice.user+":7190319").getBytes(StandardCharsets.UTF_8)).toString(),committed=UUID.randomUUID().toString();
        long nativeDialog;try(xyz.eclipseofficial.vyrn.data.IdMap ids=new xyz.eclipseofficial.vyrn.data.IdMap(context,3)){nativeDialog=ids.local("user",bob.user);}
        String body=outgoing.prepareNative(bob.user,conversation,pending,RESTART_TEXT,3,-995,nativeDialog,7190319L);
        nativePendingHydration(context,a,bob.user,-995,nativeDialog,7190319L,RESTART_TEXT,false,check);
        outgoing.prepare(bob.user,conversation,committed,RESTART_TEXT,targets);String message=outgoing.send(committed);
        JSONObject delivered=(JSONObject)b.request("GET","/v1/messages/"+message,null,true);
        check.accept(RESTART_TEXT.equals(incoming.receive(alice.user,conversation,delivered,sender)),"signal_codec_restart_fixture_committed_cache");
        JSONObject state=new JSONObject().put("alice",alice.user).put("bob",bob.user).put("conversation",conversation).put("pending",pending).put("committed",committed).put("message",message).put("body",body).put("delivered",delivered).put("sender_address",sender.address.getName()).put("recipient_address",targets.get(0).address.getName())
            .put("sender_ratchet",fingerprint(alice.store.loadSession(targets.get(0).address).serialize())).put("receiver_ratchet",fingerprint(bob.store.loadSession(sender.address).serialize()));
        // Test-only app-private state: no credentials or decrypted message text.
        state.put("native_dialog",nativeDialog);
        outgoing.scheduleNative(3,pending);String attempt=outgoing.claimNative(3,pending);outgoing.failNative(3,pending,attempt,new SecurityException("fixture_permanent_failure"));state.put("native_retry",outgoing.nativeBinding(3,pending));
        Files.write(new File(context.getFilesDir(),"vyrn-signal-network-state.json").toPath(),state.toString().getBytes(StandardCharsets.UTF_8));
        check.accept(alice.store.loadValue("private-outgoing",pending)!=null && bob.store.loadValue("private-cache",message+"_1")!=null,"signal_codec_restart_fixture_pending_ciphertext");
    }
    static void offlineRestart(Context context,BiConsumer<Boolean,String> check) throws Exception {
        JSONObject state=new JSONObject(new String(Files.readAllBytes(new File(context.getFilesDir(),"vyrn-signal-network-state.json").toPath()),StandardCharsets.UTF_8));
        RestRepository a=new RestRepository(new TokenVault(context,9101)),b=new RestRepository(new TokenVault(context,9102));
        boolean unavailable=false;try{a.me();}catch(java.io.IOException expected){unavailable=true;}check.accept(unavailable,"signal_offline_actual_authenticated_request_unavailable");
        check.accept(org.telegram.messenger.UserConfig.getInstance(3).getClientUserId()==0&&!new TokenVault(context,3).load().has("access_token"),"signal_native_borrowed_slot_cleanup_survives_process_restart");
        try(SignalDeviceKeys alice=SignalDeviceKeys.openLocal(context,a);SignalDeviceKeys bob=SignalDeviceKeys.openLocal(context,b)){
            check.accept(alice.user.equals(state.getString("alice"))&&bob.user.equals(state.getString("bob")),"signal_offline_existing_authenticated_devices");
            SignalMessages outgoing=new SignalMessages(alice),incoming=new SignalMessages(bob);String message=state.getString("message");
            boolean editOffline=false;try{outgoing.edit(bob.user,state.getString("conversation"),message,1,"ویرایش آفلاین پایدار");}catch(java.io.IOException expected){editOffline=true;}
            byte[] editIntent=alice.store.loadValue("private-edit",message+"_1");JSONObject intent=new JSONObject(new String(editIntent,StandardCharsets.UTF_8));
            check.accept(editOffline&&intent.isNull("body")&&!intent.has("inner")&&"ویرایش آفلاین پایدار".equals(intent.getString("text"))&&state.getString("sender_ratchet").equals(fingerprint(alice.store.loadSession(new SignalProtocolAddress(state.getString("recipient_address"),1)).serialize()))&&RESTART_TEXT.equals(outgoing.cachedText(message)),"signal_offline_edit_stages_encrypted_intent_before_http_without_ciphertext_ratchet_or_false_confirmation");
            boolean changedDenied=false;try{outgoing.edit(bob.user,state.getString("conversation"),message,1,"changed retry");}catch(SecurityException expected){changedDenied=true;}check.accept(changedDenied&&Arrays.equals(editIntent,alice.store.loadValue("private-edit",message+"_1")),"signal_offline_edit_changed_retry_cannot_replace_original_intent");
            state.put("offline_edit_hash",fingerprint(editIntent));Files.write(new File(context.getFilesDir(),"vyrn-signal-network-state.json").toPath(),state.toString().getBytes(StandardCharsets.UTF_8));
            check.accept(RESTART_TEXT.equals(outgoing.nativePendingText(bob.user,3,-995,state.getLong("native_dialog"),7190319L)),"signal_pending_cold_offline_exact_original_native_text");
            nativePendingHydration(context,a,bob.user,-995,state.getLong("native_dialog"),7190319L,RESTART_TEXT,true,check);
            check.accept(RESTART_TEXT.equals(outgoing.cachedText(message))&&RESTART_TEXT.equals(incoming.cachedText(message)),"signal_offline_cold_encrypted_history_both_devices");
            check.accept(state.getString("body").equals(outgoing.prepare(bob.user,state.getString("conversation"),state.getString("pending"),RESTART_TEXT,null))&&state.getString("sender_ratchet").equals(fingerprint(alice.store.loadSession(new SignalProtocolAddress(state.getString("recipient_address"),1)).serialize())),"signal_offline_pending_exact_ciphertext_without_ratchet_advance");
            JSONArray nativeRows=outgoing.nativePending(3);JSONObject nativeRow=nativeRows.length()==1?nativeRows.getJSONObject(0):new JSONObject();
            check.accept(state.getString("pending").equals(nativeRow.optString("client_id"))&&nativeRow.optInt("local_id")==-995&&nativeRow.optLong("dialog_id")==state.getLong("native_dialog")&&nativeRow.optLong("random_id")==7190319L&&nativeRow.isNull("message_id")&&!nativeRows.toString().contains(RESTART_TEXT)&&outgoing.nativePending(0).length()==0,"signal_offline_native_identity_recovered_without_plaintext_or_other_account");
            check.accept(nativeRow.toString().equals(state.getJSONObject("native_retry").toString())&&nativeRow.getBoolean("failed")&&nativeRow.getInt("attempts")==1&&outgoing.claimNative(3,state.getString("pending"))==null,"signal_offline_cold_failed_retry_metadata_and_no_automatic_claim");
            outgoing.scheduleNative(3,state.getString("pending"));check.accept(outgoing.nativeBinding(3,state.getString("pending")).getBoolean("failed")&&outgoing.retryNative(3,state.getString("pending"))&&outgoing.nativeBinding(3,state.getString("pending")).getInt("attempts")==0,"signal_offline_explicit_manual_retry_preserves_original_record_and_resets_failure");
            check.accept(state.getString("body").equals(outgoing.prepareNative(bob.user,state.getString("conversation"),state.getString("pending"),RESTART_TEXT,3,-995,state.getLong("native_dialog"),7190319L))&&state.getString("sender_ratchet").equals(fingerprint(alice.store.loadSession(new SignalProtocolAddress(state.getString("recipient_address"),1)).serialize())),"signal_offline_native_retry_exact_identity_ciphertext_and_ratchet");
            check.accept(RESTART_TEXT.equals(incoming.receive(alice.user,state.getString("conversation"),state.getJSONObject("delivered")))&&state.getString("receiver_ratchet").equals(fingerprint(bob.store.loadSession(new SignalProtocolAddress(state.getString("sender_address"),1)).serialize())),"signal_offline_cached_duplicate_without_directory");
            stageOffline(context,a,alice,bob,outgoing,state,check);
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
    private static void stageOffline(Context context,RestRepository repository,SignalDeviceKeys alice,SignalDeviceKeys bob,SignalMessages messages,JSONObject state,BiConsumer<Boolean,String> check) throws Exception {
        long random=7190320L,dialog=state.getLong("native_dialog");String client=UUID.nameUUIDFromBytes((alice.user+":"+random).getBytes(StandardCharsets.UTF_8)).toString(),conversation=state.getString("conversation");SignalProtocolAddress remote=new SignalProtocolAddress(state.getString("recipient_address"),1);byte[] ratchet=alice.store.loadSession(remote).serialize();
        JSONObject binding=messages.stageNative(bob.user,conversation,client,STAGED_TEXT,3,-994,dialog,random),record=new JSONObject(new String(alice.store.loadValue("private-outgoing",client),StandardCharsets.UTF_8));
        check.accept(record.isNull("body")&&alice.store.loadValue("private-own",client)==null&&binding.getBoolean("scheduled")&&binding.isNull("message_id")&&binding.getInt("date")>0&&!binding.toString().contains(STAGED_TEXT)&&STAGED_TEXT.equals(messages.nativePendingText(bob.user,3,-994,dialog,random,binding.getInt("date")))&&Arrays.equals(ratchet,alice.store.loadSession(remote).serialize()),"signal_offline_new_native_intent_without_network_ciphertext_or_ratchet_advance");
        check.accept(binding.toString().equals(messages.stageNative(bob.user,conversation,client,STAGED_TEXT,3,-994,dialog,random).toString()),"signal_offline_stage_repeat_preserves_original_identity_date_and_schedule");
        boolean denied=false;try{messages.stageNative(bob.user,conversation,client,STAGED_TEXT,3,-993,dialog,random);}catch(SecurityException expected){denied=true;}check.accept(denied&&record.toString().equals(new JSONObject(new String(alice.store.loadValue("private-outgoing",client),StandardCharsets.UTF_8)).toString()),"signal_offline_stage_conflicting_native_row_cannot_replace_intent");
        denied=false;try{messages.stageNative(bob.user,conversation,client,STAGED_TEXT+" changed",3,-994,dialog,random);}catch(SecurityException expected){denied=true;}check.accept(denied&&record.toString().equals(new JSONObject(new String(alice.store.loadValue("private-outgoing",client),StandardCharsets.UTF_8)).toString()),"signal_offline_stage_conflicting_text_cannot_replace_intent");
        denied=false;try{messages.prepare(UUID.randomUUID().toString(),conversation,client,STAGED_TEXT);}catch(SecurityException expected){denied="signal_client_conflict".equals(expected.getMessage());}check.accept(denied&&Arrays.equals(ratchet,alice.store.loadSession(remote).serialize()),"signal_offline_stage_conflicting_peer_rejected_before_directory_request");
        denied=false;try{messages.prepareNative(bob.user,conversation,client,STAGED_TEXT+" changed",3,-994,dialog,random);}catch(SecurityException expected){denied="signal_client_conflict".equals(expected.getMessage());}check.accept(denied&&Arrays.equals(ratchet,alice.store.loadSession(remote).serialize()),"signal_offline_stage_native_conflicting_text_rejected_before_directory_request");
        try(SignalDeviceKeys reopened=SignalDeviceKeys.openLocal(context,repository)){SignalMessages recovered=new SignalMessages(reopened);check.accept(binding.toString().equals(recovered.nativeBinding(3,client).toString())&&STAGED_TEXT.equals(recovered.nativePendingText(bob.user,3,-994,dialog,random)),"signal_offline_unprepared_intent_survives_encrypted_store_reopen");}
        String cancelled=UUID.nameUUIDFromBytes((alice.user+":7190321").getBytes(StandardCharsets.UTF_8)).toString();JSONObject cancel=messages.stageNative(bob.user,conversation,cancelled,STAGED_TEXT,3,-993,dialog,7190321L);
        check.accept(SignalMessages.requestNativeCancellation(context,repository,alice.user,repository.vault.cachedSession(),alice.device,3,-993,dialog,7190321L,cancel.getInt("date"),bob.user),"signal_offline_unprepared_intent_accepts_original_native_cancel");
        denied=false;try{messages.send(cancelled);}catch(SecurityException expected){denied="signal_cancel_requested".equals(expected.getMessage());}check.accept(denied&&new JSONObject(new String(alice.store.loadValue("private-outgoing",cancelled),StandardCharsets.UTF_8)).isNull("body")&&Arrays.equals(ratchet,alice.store.loadSession(remote).serialize()),"signal_offline_unprepared_cancel_stops_before_directory_claim_or_encryption");
        String unresolved=UUID.nameUUIDFromBytes((alice.user+":7190323").getBytes(StandardCharsets.UTF_8)).toString();JSONObject first;String session=repository.vault.cachedSession();
        synchronized(repository.vault.guard()){first=java.util.concurrent.CompletableFuture.supplyAsync(()->{try{return SignalMessages.stageNative(context,repository,alice.user,session,alice.device,bob.user,unresolved,STAGED_TEXT,3,-991,dialog,7190323L);}catch(Exception failure){throw new java.util.concurrent.CompletionException(failure);}}).get(5,java.util.concurrent.TimeUnit.SECONDS);}
        check.accept(alice.device.equals(repository.vault.cachedSignalDevice(alice.user,session))&&first.isNull("conversation"),"signal_native_local_staging_completes_while_http_vault_lock_is_held");JSONObject unprepared=new JSONObject(new String(alice.store.loadValue("private-outgoing",unresolved),StandardCharsets.UTF_8));
        denied=false;try{SignalMessages.stageNative(context,repository,alice.user,UUID.randomUUID().toString(),alice.device,bob.user,unresolved,STAGED_TEXT,3,-991,dialog,7190323L);}catch(SecurityException expected){denied=true;}check.accept(denied&&unprepared.toString().equals(new JSONObject(new String(alice.store.loadValue("private-outgoing",unresolved),StandardCharsets.UTF_8)).toString()),"signal_local_staging_changed_session_cannot_mutate_original_intent");
        check.accept(first.isNull("conversation")&&unprepared.isNull("conversation")&&unprepared.isNull("body")&&first.getBoolean("scheduled")&&STAGED_TEXT.equals(messages.nativePendingText(bob.user,3,-991,dialog,7190323L))&&Arrays.equals(ratchet,alice.store.loadSession(remote).serialize()),"signal_offline_stage_without_server_conversation_keeps_encrypted_original_intent");
        check.accept(first.toString().equals(messages.stageNative(bob.user,null,unresolved,STAGED_TEXT,3,-991,dialog,7190323L).toString()),"signal_offline_unresolved_stage_repeat_keeps_original_identity");
        denied=false;try{messages.stageNative(bob.user,conversation,unresolved,STAGED_TEXT,3,-991,dialog,7190323L);}catch(SecurityException expected){denied="signal_client_conflict".equals(expected.getMessage());}check.accept(denied&&unprepared.toString().equals(new JSONObject(new String(alice.store.loadValue("private-outgoing",unresolved),StandardCharsets.UTF_8)).toString()),"signal_unresolved_intent_rejects_caller_supplied_conversation_rebinding");
        boolean disconnected=false;try{messages.send(unresolved);}catch(java.io.IOException expected){disconnected=true;}check.accept(disconnected&&first.toString().equals(messages.nativeBinding(3,unresolved).toString())&&unprepared.toString().equals(new JSONObject(new String(alice.store.loadValue("private-outgoing",unresolved),StandardCharsets.UTF_8)).toString())&&Arrays.equals(ratchet,alice.store.loadSession(remote).serialize()),"signal_unresolved_offline_send_preserves_null_conversation_body_and_ratchet");
        String unresolvedCancel=UUID.nameUUIDFromBytes((alice.user+":7190324").getBytes(StandardCharsets.UTF_8)).toString();JSONObject firstCancel=messages.stageNative(bob.user,null,unresolvedCancel,STAGED_TEXT,3,-990,dialog,7190324L);
        check.accept(SignalMessages.requestNativeCancellation(context,repository,alice.user,repository.vault.cachedSession(),alice.device,3,-990,dialog,7190324L,firstCancel.getInt("date"),bob.user),"signal_unresolved_offline_intent_accepts_original_cancellation");
        state.put("staged",client).put("staged_binding",binding).put("staged_cancelled",cancelled).put("unresolved",unresolved).put("unresolved_binding",first).put("unresolved_cancelled",unresolvedCancel);Files.write(new File(context.getFilesDir(),"vyrn-signal-network-state.json").toPath(),state.toString().getBytes(StandardCharsets.UTF_8));
    }
    static void restart(Context context,BiConsumer<Boolean,String> check) throws Exception {
        JSONObject state=new JSONObject(new String(Files.readAllBytes(new File(context.getFilesDir(),"vyrn-signal-network-state.json").toPath()),StandardCharsets.UTF_8));
        RestRepository a=new RestRepository(new TokenVault(context,9101)),b=new RestRepository(new TokenVault(context,9102));
        try(SignalDeviceKeys alice=new SignalDeviceKeys(context,a);SignalDeviceKeys bob=new SignalDeviceKeys(context,b)){
            check.accept(alice.user.equals(state.getString("alice")) && bob.user.equals(state.getString("bob")),"signal_codec_restart_original_account_binding");
            byte[] editIntent=alice.store.loadValue("private-edit",state.getString("message")+"_1");check.accept(state.getString("offline_edit_hash").equals(fingerprint(editIntent))&&new JSONObject(new String(editIntent,StandardCharsets.UTF_8)).isNull("body"),"signal_offline_edit_exact_unprepared_intent_survives_actual_process_restart");
            SignalMessages outgoing=new SignalMessages(alice),incoming=new SignalMessages(bob);String peer=state.getString("bob"),conversation=state.getString("conversation"),pending=state.getString("pending"),message=state.getString("message");
            JSONObject editHead=new JSONObject(new String(alice.store.loadValue("private-head",message),StandardCharsets.UTF_8));int editOrdinal=editHead.getInt("ordinal");
            JSONObject editDisplay=outgoing.display(peer,conversation,message,alice.user,1,editOrdinal);
            check.accept(RESTART_TEXT.equals(editDisplay.getString("text"))&&"ویرایش آفلاین پایدار".equals(editDisplay.getString("edit_text"))&&!incoming.display(alice.user,conversation,message,alice.user,1,editOrdinal).has("edit_text"),"signal_cold_pending_edit_display_only_for_original_sender");
            try{
                JSONObject changedSession=new JSONObject(new String(editIntent,StandardCharsets.UTF_8)).put("session",UUID.randomUUID().toString());alice.store.transaction(()->{alice.store.saveValue("private-edit",message+"_1",changedSession.toString().getBytes(StandardCharsets.UTF_8));return null;});
                JSONObject guarded=outgoing.display(peer,conversation,message,alice.user,1,editOrdinal);check.accept(RESTART_TEXT.equals(guarded.getString("text"))&&!guarded.has("edit_text"),"signal_pending_edit_other_session_not_exposed_to_composer");
            }finally{alice.store.transaction(()->{alice.store.saveValue("private-edit",message+"_1",editIntent);return null;});}
            check.accept(RESTART_TEXT.equals(outgoing.cachedText(message)) && RESTART_TEXT.equals(incoming.cachedText(message)),"signal_codec_restart_both_encrypted_history_caches");
            check.accept(state.getString("body").equals(outgoing.prepare(peer,conversation,pending,RESTART_TEXT,null)) && state.getString("sender_ratchet").equals(fingerprint(alice.store.loadSession(new SignalProtocolAddress(state.getString("recipient_address"),1)).serialize())),"signal_codec_restart_pending_exact_body_no_ratchet_advance");
            check.accept(RESTART_TEXT.equals(incoming.receive(alice.user,conversation,state.getJSONObject("delivered"),null)) && state.getString("receiver_ratchet").equals(fingerprint(bob.store.loadSession(new SignalProtocolAddress(state.getString("sender_address"),1)).serialize())),"signal_codec_restart_cached_duplicate_no_directory_or_ratchet");
            check.accept(message.equals(outgoing.send(state.getString("committed"))),"signal_codec_restart_acknowledged_retry_same_server_id");
            String pendingMessage=outgoing.send(pending);JSONObject delivered=(JSONObject)b.request("GET","/v1/messages/"+pendingMessage,null,true);
            check.accept(RESTART_TEXT.equals(incoming.receive(alice.user,conversation,delivered,null)) && pendingMessage.equals(outgoing.send(pending)),"signal_codec_restart_pending_out_of_order_delivery_once");
            String staged=state.getString("staged");JSONObject original=state.getJSONObject("staged_binding");check.accept(original.toString().equals(outgoing.nativeBinding(3,staged).toString())&&new JSONObject(new String(alice.store.loadValue("private-outgoing",staged),StandardCharsets.UTF_8)).isNull("body"),"signal_staged_native_intent_survives_actual_process_restart_before_preparation");
            String stagedMessage=outgoing.send(staged);JSONObject packet=(JSONObject)b.request("GET","/v1/messages/"+stagedMessage,null,true),binding=outgoing.nativeBinding(3,staged);String body=new JSONObject(new String(alice.store.loadValue("private-outgoing",staged),StandardCharsets.UTF_8)).getString("body");byte[] ratchet=alice.store.loadSession(new SignalProtocolAddress(state.getString("recipient_address"),1)).serialize();
            check.accept(STAGED_TEXT.equals(incoming.receive(alice.user,conversation,packet))&&binding.getInt("local_id")==original.getInt("local_id")&&binding.getLong("random_id")==original.getLong("random_id")&&binding.getInt("date")==original.getInt("date")&&binding.getBoolean("scheduled")&&!binding.getBoolean("ui_done"),"signal_staged_online_prepare_and_delivery_preserve_original_native_identity_date_and_schedule");
            check.accept(stagedMessage.equals(outgoing.send(staged))&&body.equals(new JSONObject(new String(alice.store.loadValue("private-outgoing",staged),StandardCharsets.UTF_8)).getString("body"))&&Arrays.equals(ratchet,alice.store.loadSession(new SignalProtocolAddress(state.getString("recipient_address"),1)).serialize()),"signal_staged_retry_reuses_original_ciphertext_and_ratchet");
            String cancelled=state.getString("staged_cancelled");check.accept(outgoing.resolveNativeCancellation(3,cancelled)&&outgoing.nativeBinding(3,cancelled).getBoolean("cancelled")&&alice.store.loadValue("private-send-fence",cancelled)!=null,"signal_staged_cancel_server_arbitration_fences_unsent_original_client");
            boolean denied=false;try{outgoing.send(cancelled);}catch(SecurityException expected){denied="signal_message_deleted".equals(expected.getMessage());}check.accept(denied&&outgoing.nativePendingText(peer,3,-993,state.getLong("native_dialog"),7190321L)==null,"signal_staged_cancelled_intent_cannot_resurrect_or_show_text");
            String wrong=UUID.nameUUIDFromBytes((alice.user+":7190322").getBytes(StandardCharsets.UTF_8)).toString();outgoing.stageNative(UUID.randomUUID().toString(),conversation,wrong,STAGED_TEXT,3,-992,state.getLong("native_dialog"),7190322L);denied=false;try{outgoing.send(wrong);}catch(SecurityException expected){denied="signal_private_conversation_changed".equals(expected.getMessage());}check.accept(denied&&new JSONObject(new String(alice.store.loadValue("private-outgoing",wrong),StandardCharsets.UTF_8)).isNull("body")&&alice.store.loadValue("private-own",wrong)==null&&Arrays.equals(ratchet,alice.store.loadSession(new SignalProtocolAddress(state.getString("recipient_address"),1)).serialize()),"signal_staged_wrong_conversation_peer_rejected_before_claim_or_ratchet");
            String unresolved=state.getString("unresolved");JSONObject first=state.getJSONObject("unresolved_binding");check.accept(first.toString().equals(outgoing.nativeBinding(3,unresolved).toString())&&first.isNull("conversation"),"signal_unresolved_intent_survives_actual_cold_process_restart");
            String firstMessage=outgoing.send(unresolved);JSONObject resolved=outgoing.nativeBinding(3,unresolved),firstPacket=(JSONObject)b.request("GET","/v1/messages/"+firstMessage,null,true);String firstBody=new JSONObject(new String(alice.store.loadValue("private-outgoing",unresolved),StandardCharsets.UTF_8)).getString("body");byte[] firstRatchet=alice.store.loadSession(new SignalProtocolAddress(state.getString("recipient_address"),1)).serialize();
            check.accept(conversation.equals(resolved.getString("conversation"))&&STAGED_TEXT.equals(incoming.receive(alice.user,conversation,firstPacket))&&first.getInt("local_id")==resolved.getInt("local_id")&&first.getLong("random_id")==resolved.getLong("random_id")&&first.getInt("date")==resolved.getInt("date")&&!resolved.getBoolean("ui_done"),"signal_unresolved_reconnect_resolves_unique_private_pair_and_delivers_original_intent");
            check.accept(firstMessage.equals(outgoing.send(unresolved))&&firstBody.equals(new JSONObject(new String(alice.store.loadValue("private-outgoing",unresolved),StandardCharsets.UTF_8)).getString("body"))&&Arrays.equals(firstRatchet,alice.store.loadSession(new SignalProtocolAddress(state.getString("recipient_address"),1)).serialize()),"signal_resolved_retry_reuses_ciphertext_server_identity_and_ratchet");
            String unresolvedCancel=state.getString("unresolved_cancelled");check.accept(outgoing.nativeBinding(3,unresolvedCancel).isNull("conversation")&&outgoing.resolveNativeCancellation(3,unresolvedCancel)&&conversation.equals(outgoing.nativeBinding(3,unresolvedCancel).getString("conversation"))&&alice.store.loadValue("private-send-fence",unresolvedCancel)!=null&&alice.store.loadValue("private-own",unresolvedCancel)==null&&Arrays.equals(firstRatchet,alice.store.loadSession(new SignalProtocolAddress(state.getString("recipient_address"),1)).serialize()),"signal_unresolved_cancel_resolves_original_pair_and_fences_without_encryption");
            denied=false;try{outgoing.send(unresolvedCancel);}catch(SecurityException expected){denied="signal_message_deleted".equals(expected.getMessage());}check.accept(denied&&outgoing.nativePendingText(peer,3,-990,state.getLong("native_dialog"),7190324L)==null,"signal_unresolved_cancelled_intent_cannot_reappear_after_reconnect");
            JSONObject edited=outgoing.edit(peer,conversation,message,1,"ویرایش آفلاین پایدار");check.accept(message.equals(edited.getString("id"))&&edited.getInt("revision")==2&&"ویرایش آفلاین پایدار".equals(incoming.receive(alice.user,conversation,edited))&&"ویرایش آفلاین پایدار".equals(outgoing.cachedText(message)),"signal_offline_edit_cold_reconnect_commits_original_message_once_and_recipient_decrypts");
            check.accept(!outgoing.display(peer,conversation,message,alice.user,2,edited.getInt("ordinal")).has("edit_text"),"signal_confirmed_revision_does_not_restore_old_pending_editor");
            byte[] preparedEdit=alice.store.loadValue("private-edit",message+"_1"),editRatchet=alice.store.loadSession(new SignalProtocolAddress(state.getString("recipient_address"),1)).serialize();JSONObject repeatedEdit=outgoing.edit(peer,conversation,message,1,"ویرایش آفلاین پایدار");check.accept(repeatedEdit.getInt("revision")==2&&Arrays.equals(preparedEdit,alice.store.loadValue("private-edit",message+"_1"))&&Arrays.equals(editRatchet,alice.store.loadSession(new SignalProtocolAddress(state.getString("recipient_address"),1)).serialize()),"signal_offline_edit_repeated_reconnect_reuses_exact_body_without_new_revision_or_ratchet");
        }finally{cleanup(context);}
    }
    static void cleanup(Context context) throws Exception {
        Exception failure=null;
        for(int slot:new int[]{9101,9102}){RestRepository repository=new RestRepository(new TokenVault(context,slot));try{if(repository.vault.load().has("access_token"))repository.request("POST","/v1/auth/logout",new JSONObject(),true);}catch(Exception error){if(failure==null)failure=error;}finally{repository.vault.clear();}}
        if(failure!=null)throw failure;
    }

    private static boolean awaitNativeText(java.util.concurrent.atomic.AtomicReference<org.telegram.messenger.MessageObject> target,String expected) throws Exception {
        java.util.concurrent.CompletableFuture<Boolean> result=new java.util.concurrent.CompletableFuture<>();long deadline=android.os.SystemClock.elapsedRealtime()+8000;
        org.telegram.messenger.AndroidUtilities.runOnUIThread(new Runnable(){public void run(){org.telegram.messenger.MessageObject object=target.get();if(object!=null&&expected.contentEquals(object.messageText)){result.complete(true);return;}if(android.os.SystemClock.elapsedRealtime()>=deadline){result.complete(false);return;}org.telegram.messenger.AndroidUtilities.runOnUIThread(this,100);}});
        return result.get(10,java.util.concurrent.TimeUnit.SECONDS);
    }
    private static void metadataMigration(Context context,BiConsumer<Boolean,String> check) throws Exception {
        final int slot=9016;String file="vyrn_ids_"+slot+".db",message=UUID.randomUUID().toString(),sender=UUID.randomUUID().toString(),conversation=UUID.randomUUID().toString();context.deleteDatabase(file);
        try{
            try(SQLiteDatabase legacy=SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(file),null)){
                legacy.execSQL("CREATE TABLE ids(id INTEGER PRIMARY KEY AUTOINCREMENT,kind TEXT NOT NULL,uuid TEXT NOT NULL,UNIQUE(kind,uuid))");legacy.execSQL("INSERT INTO ids VALUES(7,'user',?)",new Object[]{sender});
                legacy.execSQL("CREATE TABLE message_ids(id INTEGER PRIMARY KEY CHECK(id>0),uuid TEXT NOT NULL UNIQUE)");legacy.execSQL("INSERT INTO message_ids VALUES(17,?)",new Object[]{message});
                legacy.execSQL("CREATE TABLE observed_messages(uuid TEXT PRIMARY KEY,conversation TEXT NOT NULL,sender TEXT NOT NULL,revision INTEGER NOT NULL CHECK(revision>0),body_hash BLOB)");legacy.execSQL("INSERT INTO observed_messages VALUES(?,?,?,?,NULL)",new Object[]{message,conversation,sender,2});legacy.setVersion(3);
            }
            try(IdMap ids=new IdMap(context,slot)){
                check.accept(ids.remote("user",7).equals(sender)&&ids.remote("message",17).equals(message)&&ids.privateContext(17)==null,"signal_native_populated_v3_migration_preserves_ids_and_requires_fresh_private_metadata");
                ids.observe(new xyz.eclipseofficial.vyrn.domain.Models.Message(message,sender,"ignored ciphertext",10,17,true,false,conversation,2,11));
                JSONObject value=ids.privateContext(17);check.accept(value!=null&&value.getInt("revision")==2&&value.getInt("date")==10&&value.getInt("edited")==11,"signal_native_migrated_private_context_binds_revision_date_edit");
                ids.observe(new xyz.eclipseofficial.vyrn.domain.Models.Message(message,sender,"old",9,17,true,false,conversation,1,0));check.accept(ids.privateContext(17).toString().equals(value.toString()),"signal_native_private_metadata_rejects_revision_regression");
                ids.observe(new xyz.eclipseofficial.vyrn.domain.Models.Message(message,sender,"",10,17,true,true,conversation,2,11));boolean rejected=false;try{ids.observe(new xyz.eclipseofficial.vyrn.domain.Models.Message(message,sender,"revive",10,17,true,false,conversation,3,12));}catch(IllegalStateException expected){rejected=true;}check.accept(rejected&&ids.privateContext(17)==null,"signal_native_migrated_private_tombstone_cannot_revive");
            }
            try(IdMap ids=new IdMap(context,slot)){check.accept(ids.privateContext(17)==null&&ids.remote("message",17).equals(message),"signal_native_migration_tombstone_survives_reopen");}
        }finally{context.deleteDatabase(file);}
    }
    private static void nativeHydration(Context context,RestRepository receiver,JSONObject packet,String text,BiConsumer<Boolean,String> check) throws Exception {
        final int account=3;TokenVault vault=new TokenVault(context,account);
        if(org.telegram.messenger.UserConfig.getInstance(account).getClientUserId()!=0||vault.load().has("access_token"))throw new SecurityException("native_test_slot_in_use");
        xyz.eclipseofficial.vyrn.domain.Models.Message value=receiver.message(packet.getString("id"));
        java.util.concurrent.CountDownLatch ready=new java.util.concurrent.CountDownLatch(1);
        org.telegram.messenger.MessagesStorage.getInstance(account).getStorageQueue().postRunnable(ready::countDown);
        if(!ready.await(20,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("native_storage_not_ready");
        java.util.concurrent.CompletableFuture<Boolean> hydrated=new java.util.concurrent.CompletableFuture<>();
        java.util.concurrent.atomic.AtomicReference<org.telegram.messenger.MessageObject> target=new java.util.concurrent.atomic.AtomicReference<>();
        org.telegram.messenger.NotificationCenter.NotificationCenterDelegate observer=(event,slot,args)->{try{
            org.telegram.messenger.MessageObject object=target.get();if(object==null||!text.contentEquals(object.messageText))return;
            org.telegram.tgnet.SerializedData serialized=new org.telegram.tgnet.SerializedData();try{object.messageOwner.serializeToStream(serialized);hydrated.complete("Encrypted message".equals(object.messageOwner.message)&&!new String(serialized.toByteArray(),StandardCharsets.UTF_8).contains(text));}finally{serialized.cleanup();}
        }catch(Throwable error){hydrated.completeExceptionally(error);}};
        try(IdMap ids=new IdMap(context,account)){
            vault.save(receiver.vault.load());long owner=ids.local("user",receiver.vault.load().getString("user_id")),peer=ids.local("user",value.sender);ids.observe(value);
            org.telegram.messenger.AndroidUtilities.runOnUIThread(()->{try{
                org.telegram.tgnet.TLRPC.TL_user user=new org.telegram.tgnet.TLRPC.TL_user();user.id=owner;user.first_name="Signal fixture";org.telegram.messenger.UserConfig.getInstance(account).setCurrentUser(user);
                org.telegram.messenger.NotificationCenter.getInstance(account).addObserver(observer,org.telegram.messenger.NotificationCenter.updateInterfaces);
                org.telegram.tgnet.TLRPC.TL_message row=new org.telegram.tgnet.TLRPC.TL_message();row.id=value.ordinal;row.date=value.date;row.edit_date=value.editedDate;row.message="Encrypted message";row.flags=256;row.from_id=new org.telegram.tgnet.TLRPC.TL_peerUser();row.from_id.user_id=peer;row.peer_id=new org.telegram.tgnet.TLRPC.TL_peerUser();row.peer_id.user_id=peer;row.media=new org.telegram.tgnet.TLRPC.TL_messageMediaEmpty();
                target.set(new org.telegram.messenger.MessageObject(account,row,true,false));
            }catch(Throwable error){hydrated.completeExceptionally(error);}});
            check.accept(hydrated.get(30,java.util.concurrent.TimeUnit.SECONDS),"signal_native_constructor_hydrates_encrypted_cache_without_serializing_text");
            org.telegram.messenger.MessagesStorage storage=org.telegram.messenger.MessagesStorage.getInstance(account);
            java.util.ArrayList<org.telegram.tgnet.TLRPC.Message> rows=new java.util.ArrayList<>();rows.add(target.get().messageOwner);storage.putMessages(rows,true,true,false,0,0,0);
            java.util.concurrent.CompletableFuture<Boolean> persisted=new java.util.concurrent.CompletableFuture<>();
            storage.getStorageQueue().postRunnable(()->{try{org.telegram.SQLite.SQLiteCursor cursor=storage.getDatabase().queryFinalized("SELECT data,custom_params,replydata FROM messages_v2 WHERE uid="+peer+" AND mid="+value.ordinal);try{
                boolean safe=cursor.next();if(safe){for(int column=0;column<3;column++){byte[] bytes=cursor.byteArrayValue(column);if(bytes!=null&&new String(bytes,StandardCharsets.UTF_8).contains(text))safe=false;}org.telegram.tgnet.NativeByteBuffer data=cursor.byteBufferValue(0);try{org.telegram.tgnet.TLRPC.Message stored=org.telegram.tgnet.TLRPC.Message.TLdeserialize(data,data.readInt32(true),true);safe &= "Encrypted message".equals(stored.message);}finally{data.reuse();}}persisted.complete(safe);
            }finally{cursor.dispose();}}catch(Throwable error){persisted.completeExceptionally(error);}});
            check.accept(persisted.get(20,java.util.concurrent.TimeUnit.SECONDS),"signal_native_messages_storage_persists_placeholder_without_private_text");
            org.telegram.tgnet.TLRPC.Message originalOwner=target.get().messageOwner;org.telegram.messenger.AndroidUtilities.runOnUIThread(()->{org.telegram.tgnet.TLRPC.TL_message replacement=new org.telegram.tgnet.TLRPC.TL_message();replacement.message="";target.get().messageOwner=replacement;});check.accept(awaitNativeText(target,"Encrypted message"),"signal_native_replaced_owner_clears_old_private_layout");
            org.telegram.messenger.AndroidUtilities.runOnUIThread(()->{target.get().messageOwner=originalOwner;xyz.eclipseofficial.vyrn.compat.VyrnBridge.hydratePrivateText(target.get());});check.accept(awaitNativeText(target,text),"signal_native_restored_owner_hydrates_authenticated_layout");
            org.telegram.messenger.AndroidUtilities.runOnUIThread(()->target.get().messageOwner.from_id.user_id=owner);
            check.accept(awaitNativeText(target,"Encrypted message"),"signal_native_sender_mutation_clears_volatile_text");
            org.telegram.messenger.AndroidUtilities.runOnUIThread(()->{target.get().messageOwner.from_id.user_id=peer;xyz.eclipseofficial.vyrn.compat.VyrnBridge.hydratePrivateText(target.get());});
            check.accept(awaitNativeText(target,text),"signal_native_verified_sender_restores_volatile_text");
            org.telegram.messenger.AndroidUtilities.runOnUIThread(()->target.get().messageOwner.date++);
            check.accept(awaitNativeText(target,"Encrypted message"),"signal_native_date_mutation_clears_volatile_text");
            org.telegram.messenger.AndroidUtilities.runOnUIThread(()->{target.get().messageOwner.date=value.date;xyz.eclipseofficial.vyrn.compat.VyrnBridge.hydratePrivateText(target.get());});
            check.accept(awaitNativeText(target,text),"signal_native_verified_date_restores_volatile_text");
            vault.save(new JSONObject(receiver.vault.load().toString()).put("user_id",UUID.randomUUID().toString()));
            check.accept(awaitNativeText(target,"Encrypted message"),"signal_native_same_session_changed_user_clears_volatile_text");
            vault.save(receiver.vault.load());org.telegram.messenger.AndroidUtilities.runOnUIThread(()->xyz.eclipseofficial.vyrn.compat.VyrnBridge.hydratePrivateText(target.get()));
            check.accept(awaitNativeText(target,text),"signal_native_restored_authenticated_user_restores_volatile_text");
            ids.observe(new xyz.eclipseofficial.vyrn.domain.Models.Message(value.id,value.sender,"",value.date,value.ordinal,true,true,value.conversation,value.revision,value.editedDate));
            check.accept(ids.privateContext(value.ordinal)==null,"signal_native_private_context_tombstone_fence");
            check.accept(awaitNativeText(target,"Encrypted message"),"signal_native_metadata_deletion_clears_displayed_private_text");
        }finally{
            vault.clear();java.util.concurrent.CountDownLatch cleared=new java.util.concurrent.CountDownLatch(1);
            org.telegram.messenger.AndroidUtilities.runOnUIThread(()->{org.telegram.messenger.NotificationCenter.getInstance(account).removeObserver(observer,org.telegram.messenger.NotificationCenter.updateInterfaces);org.telegram.messenger.UserConfig.getInstance(account).setCurrentUser(new org.telegram.tgnet.TLRPC.TL_user());org.telegram.messenger.UserConfig.getInstance(account).saveConfig(true);cleared.countDown();});if(!cleared.await(10,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("native_test_slot_cleanup_timeout");
        }
    }
    private static void nativePendingHydration(Context context,RestRepository sender,String peer,int local,long dialog,long random,String text,boolean cold,BiConsumer<Boolean,String> check) throws Exception {
        final int account=3;TokenVault vault=new TokenVault(context,account);
        if(org.telegram.messenger.UserConfig.getInstance(account).getClientUserId()!=0||vault.load().has("access_token"))throw new SecurityException("native_test_slot_in_use");
        org.telegram.messenger.MessagesStorage storage=org.telegram.messenger.MessagesStorage.getInstance(account);java.util.concurrent.CountDownLatch ready=new java.util.concurrent.CountDownLatch(1);storage.getStorageQueue().postRunnable(ready::countDown);if(!ready.await(20,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("native_storage_not_ready");
        java.util.concurrent.atomic.AtomicReference<org.telegram.messenger.MessageObject> target=new java.util.concurrent.atomic.AtomicReference<>();
        String placeholder=xyz.eclipseofficial.vyrn.compat.VyrnBridge.PRIVATE_PENDING_PLACEHOLDER;
        try(xyz.eclipseofficial.vyrn.data.IdMap ids=new xyz.eclipseofficial.vyrn.data.IdMap(context,account)){
            vault.save(sender.vault.load());String user=sender.vault.load().getString("user_id");long owner=ids.local("user",user);if(!peer.equals(ids.remote("user",dialog)))throw new SecurityException("native_fixture_peer_changed");
            org.telegram.tgnet.TLRPC.Message row;
            if(cold){
                java.util.concurrent.CompletableFuture<org.telegram.tgnet.TLRPC.Message> loaded=new java.util.concurrent.CompletableFuture<>();
                storage.getStorageQueue().postRunnable(()->{try{org.telegram.SQLite.SQLiteCursor cursor=storage.getDatabase().queryFinalized("SELECT m.data,r.random_id,m.send_state FROM messages_v2 m LEFT JOIN randoms_v2 r ON r.mid=m.mid AND r.uid=m.uid WHERE m.uid="+dialog+" AND m.mid="+local);try{if(!cursor.next()||cursor.isNull(1))throw new SecurityException("native_pending_fixture_missing");org.telegram.tgnet.NativeByteBuffer data=cursor.byteBufferValue(0);try{org.telegram.tgnet.TLRPC.Message value=org.telegram.tgnet.TLRPC.Message.TLdeserialize(data,data.readInt32(true),true);value.random_id=cursor.longValue(1);value.send_state=cursor.intValue(2);value.dialog_id=dialog;loaded.complete(value);}finally{data.reuse();}}finally{cursor.dispose();}}catch(Throwable failure){loaded.completeExceptionally(failure);}});
                row=loaded.get(20,java.util.concurrent.TimeUnit.SECONDS);check.accept(row.id==local&&row.random_id==random&&placeholder.equals(row.message),"signal_pending_cold_actual_native_row_identity_and_placeholder");
            }else{try(SignalDeviceKeys keysForDate=SignalDeviceKeys.openLocal(context,sender)){org.telegram.tgnet.TLRPC.TL_message value=new org.telegram.tgnet.TLRPC.TL_message();value.id=local;value.date=new xyz.eclipseofficial.vyrn.data.SignalMessages(keysForDate).nativeBinding(account,UUID.nameUUIDFromBytes((user+":"+random).getBytes(StandardCharsets.UTF_8)).toString()).getInt("date");value.message=placeholder;value.flags=256;value.out=true;value.from_id=new org.telegram.tgnet.TLRPC.TL_peerUser();value.from_id.user_id=owner;value.peer_id=new org.telegram.tgnet.TLRPC.TL_peerUser();value.peer_id.user_id=dialog;value.dialog_id=dialog;value.random_id=random;value.send_state=org.telegram.messenger.MessageObject.MESSAGE_SEND_STATE_SENDING;value.media=new org.telegram.tgnet.TLRPC.TL_messageMediaEmpty();row=value;}}
            java.util.concurrent.CountDownLatch created=new java.util.concurrent.CountDownLatch(1);org.telegram.messenger.AndroidUtilities.runOnUIThread(()->{try{org.telegram.tgnet.TLRPC.TL_user nativeUser=new org.telegram.tgnet.TLRPC.TL_user();nativeUser.id=owner;nativeUser.first_name="Signal pending fixture";org.telegram.messenger.UserConfig.getInstance(account).setCurrentUser(nativeUser);target.set(new org.telegram.messenger.MessageObject(account,row,true,false));}finally{created.countDown();}});if(!created.await(10,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("native_pending_constructor_timeout");
            check.accept(awaitNativeText(target,text),cold?"signal_pending_cold_offline_constructor_restores_volatile_text":"signal_pending_constructor_hydrates_original_encrypted_outgoing_text");
            if(cold)check.accept(row.send_state==org.telegram.messenger.MessageObject.MESSAGE_SEND_STATE_SEND_ERROR,"signal_pending_cold_encrypted_failed_state_replaces_stale_native_sending_state");
            org.telegram.tgnet.SerializedData bytes=new org.telegram.tgnet.SerializedData();try{row.serializeToStream(bytes);check.accept(placeholder.equals(row.message)&&!new String(bytes.toByteArray(),StandardCharsets.UTF_8).contains(text),"signal_pending_native_owner_serialization_contains_no_private_text");}finally{bytes.cleanup();}
            if(!cold){java.util.ArrayList<org.telegram.tgnet.TLRPC.Message> rows=new java.util.ArrayList<>();rows.add(row);storage.putMessages(rows,true,true,false,0,0,0);}
            java.util.concurrent.CompletableFuture<Boolean> safe=new java.util.concurrent.CompletableFuture<>();storage.getStorageQueue().postRunnable(()->{try{org.telegram.SQLite.SQLiteCursor cursor=storage.getDatabase().queryFinalized("SELECT data,custom_params,replydata FROM messages_v2 WHERE uid="+dialog+" AND mid="+local);try{boolean clean=cursor.next();if(clean)for(int column=0;column<3;column++){byte[] bytesInRow=cursor.byteArrayValue(column);if(bytesInRow!=null&&new String(bytesInRow,StandardCharsets.UTF_8).contains(text))clean=false;}safe.complete(clean);}finally{cursor.dispose();}}catch(Throwable failure){safe.completeExceptionally(failure);}});check.accept(safe.get(20,java.util.concurrent.TimeUnit.SECONDS),"signal_pending_actual_native_storage_contains_no_private_text");
            org.telegram.messenger.AndroidUtilities.runOnUIThread(()->row.random_id++);check.accept(awaitNativeText(target,placeholder),"signal_pending_changed_random_identity_clears_volatile_text");
            org.telegram.messenger.AndroidUtilities.runOnUIThread(()->{row.random_id=random;xyz.eclipseofficial.vyrn.compat.VyrnBridge.hydratePrivateText(target.get());});check.accept(awaitNativeText(target,text),"signal_pending_restored_original_identity_restores_volatile_text");
            org.telegram.messenger.AndroidUtilities.runOnUIThread(()->{org.telegram.tgnet.TLRPC.TL_message replacement=new org.telegram.tgnet.TLRPC.TL_message();replacement.message="";target.get().messageOwner=replacement;});check.accept(awaitNativeText(target,"Encrypted message"),"signal_pending_replaced_owner_clears_old_private_layout");
            org.telegram.messenger.AndroidUtilities.runOnUIThread(()->{target.get().messageOwner=row;xyz.eclipseofficial.vyrn.compat.VyrnBridge.hydratePrivateText(target.get());});check.accept(awaitNativeText(target,text),"signal_pending_restored_owner_hydrates_authenticated_layout");
            vault.save(new JSONObject(sender.vault.load().toString()).put("user_id",UUID.randomUUID().toString()));check.accept(awaitNativeText(target,placeholder),"signal_pending_changed_vault_user_same_session_clears_volatile_text");
            vault.save(sender.vault.load());org.telegram.messenger.AndroidUtilities.runOnUIThread(()->xyz.eclipseofficial.vyrn.compat.VyrnBridge.hydratePrivateText(target.get()));check.accept(awaitNativeText(target,text),"signal_pending_restored_authenticated_user_restores_volatile_text");
        }finally{
            vault.clear();java.util.concurrent.CountDownLatch cleared=new java.util.concurrent.CountDownLatch(1);org.telegram.messenger.AndroidUtilities.runOnUIThread(()->{try{org.telegram.messenger.UserConfig.getInstance(account).setCurrentUser(new org.telegram.tgnet.TLRPC.TL_user());org.telegram.messenger.UserConfig.getInstance(account).saveConfig(true);}finally{cleared.countDown();}});if(!cleared.await(10,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("native_pending_slot_cleanup_timeout");
            if(cold){java.util.concurrent.CompletableFuture<Boolean> removed=new java.util.concurrent.CompletableFuture<>();storage.getStorageQueue().postRunnable(()->{try{storage.getDatabase().executeFast("DELETE FROM messages_v2 WHERE uid="+dialog+" AND mid="+local).stepThis().dispose();storage.getDatabase().executeFast("DELETE FROM randoms_v2 WHERE uid="+dialog+" AND mid="+local).stepThis().dispose();removed.complete(true);}catch(Throwable failure){removed.completeExceptionally(failure);}});if(!removed.get(20,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("native_pending_row_cleanup_failed");}
        }
    }
    private static void edits(Context context,RestRepository a,RestRepository b,SignalDeviceKeys alice,SignalDeviceKeys bob,SignalDeviceKeys bob2,String conversation,BiConsumer<Boolean,String> check) throws Exception {
        SignalMessages sending=new SignalMessages(alice),receiving=new SignalMessages(bob),second=new SignalMessages(bob2);String client=UUID.randomUUID().toString(),original="متن اولیه",edited="ویرایش خصوصی 🔐";
        sending.prepare(bob.user,conversation,client,original);String id=sending.send(client);JSONObject before=(JSONObject)a.request("GET","/v1/messages/"+id,null,true);receiving.receive(alice.user,conversation,before);second.receive(alice.user,conversation,before);
        JSONObject committed=sending.edit(bob.user,conversation,id,1,edited);
        check.accept(committed.getInt("revision")==2&&id.equals(committed.getString("id"))&&client.equals(committed.getString("client_id"))&&before.getInt("ordinal")==committed.getInt("ordinal")&&edited.equals(sending.cachedText(id)),"signal_edit_preserves_original_server_identity_and_confirms_own_cache");
        check.accept(edited.equals(receiving.receive(alice.user,conversation,committed))&&edited.equals(second.receive(alice.user,conversation,committed)),"signal_edit_two_peer_devices_decrypt_new_revision");
        byte[] journal=alice.store.loadValue("private-edit",id+"_1");org.signal.libsignal.protocol.SignalProtocolAddress address=bob.publishInitial();byte[] ratchet=alice.store.loadSession(address).serialize();
        try(SignalDeviceKeys reopened=new SignalDeviceKeys(context,a)){JSONObject repeated=new SignalMessages(reopened).edit(bob.user,conversation,id,1,edited);check.accept(repeated.getInt("revision")==2&&Arrays.equals(journal,reopened.store.loadValue("private-edit",id+"_1"))&&Arrays.equals(ratchet,reopened.store.loadSession(address).serialize()),"signal_edit_cold_exact_retry_does_not_advance_revision_ciphertext_or_ratchet");}
        boolean denied=false;try{sending.edit(bob.user,conversation,id,1,"changed retry");}catch(SecurityException expected){denied=true;}check.accept(denied&&Arrays.equals(journal,alice.store.loadValue("private-edit",id+"_1"))&&Arrays.equals(ratchet,alice.store.loadSession(address).serialize()),"signal_edit_changed_retry_preserves_original_encrypted_intent");
        denied=false;try{receiving.edit(alice.user,conversation,id,2,"forged");}catch(SecurityException expected){denied=true;}check.accept(denied&&bob.store.loadValue("private-edit",id+"_2")==null,"signal_edit_recipient_cannot_edit_sender_message");
        JSONObject next=sending.edit(bob.user,conversation,id,2,"ویرایش دوم");check.accept(next.getInt("revision")==3&&"ویرایش دوم".equals(receiving.receive(alice.user,conversation,next))&&"ویرایش دوم".equals(second.receive(alice.user,conversation,next)),"signal_edit_sequential_revision_decrypts_without_new_message");
        denied=false;try{sending.edit(bob.user,conversation,id,1,edited);}catch(SecurityException expected){denied=true;}check.accept(denied&&((JSONObject)a.request("GET","/v1/messages/"+id,null,true)).getInt("revision")==3,"signal_edit_stale_retry_cannot_overwrite_newer_revision");
        b.request("DELETE","/v1/messages/"+id,null,true);sending.receive(bob.user,conversation,(JSONObject)a.request("GET","/v1/messages/"+id,null,true));
        check.accept(sending.cachedText(id)==null&&alice.store.valueNames("private-edit").stream().noneMatch(value->value.startsWith(id+"_")),"signal_edit_shared_deletion_retires_all_edit_plaintext_and_ciphertext_journals");
        denied=false;try{sending.edit(bob.user,conversation,id,3,"resurrect");}catch(SecurityException expected){denied=true;}check.accept(denied&&alice.store.loadValue("private-edit",id+"_3")==null,"signal_edit_deleted_message_cannot_prepare_or_resurrect");
    }
    private static void codec(Context context,RestRepository a,RestRepository b,RestRepository second,SignalDeviceKeys alice,SignalDeviceKeys bob,SignalDeviceKeys bob2,String conversation,java.util.List<SignalDeviceKeys.ClaimedKey> targets,BiConsumer<Boolean,String> check) throws Exception {
        metadataMigration(context,check);
        SignalMessages sending=new SignalMessages(alice),receiving=new SignalMessages(bob),receiving2=new SignalMessages(bob2);
        SignalDeviceKeys.ClaimedKey senderKey=bob.claim(alice.user,alice.device,1);
        String client=UUID.nameUUIDFromBytes((alice.user+":6190319").getBytes(StandardCharsets.UTF_8)).toString(),text="پیام خصوصی پایدار 🔐";
        boolean invalid=false;try{sending.prepareNative(bob.user,conversation,client,text,3,-991,-9102,6190319L);}catch(IllegalArgumentException expected){invalid=true;}check.accept(invalid&&alice.store.loadValue("private-outgoing",client)==null,"signal_native_send_rejects_group_identity_before_prepare");
        byte[] before=alice.store.loadSession(targets.get(0).address).serialize();
        File database=new File(context.getNoBackupFilesDir(),"vyrn_signal_"+alice.user+"_"+alice.device+".db");
        try(SQLiteDatabase db=SQLiteDatabase.openDatabase(database.getAbsolutePath(),null,SQLiteDatabase.OPEN_READWRITE)){db.execSQL("CREATE TRIGGER fail_private_outgoing BEFORE INSERT ON records WHEN NEW.kind='app:private-outgoing' BEGIN SELECT RAISE(ABORT,'fixture_outgoing_failure'); END");}
        boolean denied=false;try{sending.prepareNative(bob.user,conversation,client,text,3,-991,9102,6190319L);}catch(Exception expected){denied=true;}
        check.accept(denied && alice.store.loadValue("private-outgoing",client)==null && alice.store.loadValue("private-own",client)==null && Arrays.equals(before,alice.store.loadSession(targets.get(0).address).serialize()),"signal_codec_outgoing_failure_rolls_back_ratchet_and_cache");
        try(SQLiteDatabase db=SQLiteDatabase.openDatabase(database.getAbsolutePath(),null,SQLiteDatabase.OPEN_READWRITE)){db.execSQL("DROP TRIGGER fail_private_outgoing");}
        String body=sending.prepareNative(bob.user,conversation,client,text,3,-991,9102,6190319L);byte[] prepared=alice.store.loadSession(targets.get(0).address).serialize();
        JSONObject nativeIdentity=sending.nativePending(3).getJSONObject(0);check.accept(client.equals(nativeIdentity.getString("client_id"))&&nativeIdentity.getInt("local_id")==-991&&nativeIdentity.getLong("dialog_id")==9102&&nativeIdentity.getLong("random_id")==6190319L&&nativeIdentity.isNull("message_id")&&!nativeIdentity.toString().contains(text),"signal_native_send_identity_committed_with_ciphertext");
        denied=false;try{sending.scheduleNative(0,client);}catch(SecurityException expected){denied=true;}check.accept(denied&&!sending.nativeBinding(3,client).getBoolean("scheduled"),"signal_native_scheduling_wrong_account_rejected");
        sending.scheduleNative(3,client);sending.scheduleNative(3,client);check.accept(sending.nativeBinding(3,client).getBoolean("scheduled")&&body.equals(new JSONObject(new String(alice.store.loadValue("private-outgoing",client),StandardCharsets.UTF_8)).getString("body"))&&Arrays.equals(prepared,alice.store.loadSession(targets.get(0).address).serialize()),"signal_native_scheduling_idempotent_retains_original_ciphertext_and_ratchet");
        nativeRetry(a,alice,sending,client,body,targets.get(0).address,prepared,check);
        denied=false;try{sending.prepareNative(bob.user,conversation,client,text,3,-992,9102,6190319L);}catch(SecurityException expected){denied=true;}check.accept(denied&&Arrays.equals(prepared,alice.store.loadSession(targets.get(0).address).serialize()),"signal_native_send_changed_local_identity_rejected_without_ratchet_advance");
        check.accept(text.equals(sending.nativePendingText(bob.user,3,-991,9102,6190319L)),"signal_pending_text_requires_original_native_identity");
        int nativeDate=nativeIdentity.getInt("date");check.accept(nativeDate>0&&text.equals(sending.nativePendingText(bob.user,3,-991,9102,6190319L,nativeDate)),"signal_pending_text_original_authenticated_date");
        denied=false;try{sending.nativePendingText(bob.user,3,-991,9102,6190319L,nativeDate+1);}catch(SecurityException expected){denied=true;}check.accept(denied,"signal_pending_text_changed_date_rejected");
        denied=false;try{sending.acknowledgeNative(3,client,-991,9102,6190319L,UUID.randomUUID().toString());}catch(SecurityException expected){denied=true;}check.accept(denied&&!sending.nativeBinding(3,client).getBoolean("ui_done"),"signal_native_ui_ack_before_server_ack_rejected");
        boolean pendingDenied=true;for(int variant=0;variant<5;variant++){boolean rejected=false;try{rejected=sending.nativePendingText(variant==0?UUID.randomUUID().toString():bob.user,variant==1?0:3,variant==2?-992:-991,variant==3?9103:9102,variant==4?6190320L:6190319L)==null;}catch(SecurityException expected){rejected=true;}pendingDenied &= rejected;}check.accept(pendingDenied,"signal_pending_text_rejects_changed_peer_account_row_dialog_random");
        check.accept(body.equals(sending.prepare(bob.user,conversation,client,text,targets)) && Arrays.equals(prepared,alice.store.loadSession(targets.get(0).address).serialize()) && !body.contains(text),"signal_codec_prepare_retry_exact_ciphertext_no_ratchet_advance");
        denied=false;try{sending.prepare(bob.user,conversation,client,"changed",targets);}catch(SecurityException expected){denied=true;}
        check.accept(denied,"signal_codec_client_id_plaintext_conflict");
        String message=sending.send(client);
        check.accept(sending.nativePendingText(bob.user,3,-991,9102,6190319L)==null,"signal_pending_acknowledged_row_no_longer_displays_pending_text");
        check.accept(message.equals(sending.send(client)) && text.equals(sending.cachedText(message)) && Arrays.equals(prepared,alice.store.loadSession(targets.get(0).address).serialize()),"signal_codec_http_retry_and_own_cache");
        check.accept(message.equals(sending.nativePending(3).getJSONObject(0).getString("message_id")),"signal_native_identity_keeps_authoritative_acknowledgement");
        boolean uiDenied=true;for(int variant=0;variant<5;variant++){boolean rejected=false;try{sending.acknowledgeNative(variant==0?0:3,client,variant==1?-992:-991,variant==2?9103:9102,variant==3?6190320L:6190319L,variant==4?UUID.randomUUID().toString():message);}catch(SecurityException expected){rejected=true;}uiDenied &= rejected;}
        check.accept(uiDenied&&!sending.nativeBinding(3,client).getBoolean("ui_done")&&sending.nativePending(3).length()==1,"signal_native_ui_ack_changed_account_row_dialog_random_message_rejected");
        sending.acknowledgeNative(3,client,-991,9102,6190319L,message);sending.acknowledgeNative(3,client,-991,9102,6190319L,message);JSONObject resolved=sending.nativeBinding(3,client);
        check.accept(sending.nativePending(3).length()==0&&resolved.getBoolean("ui_done")&&resolved.getInt("date")==nativeDate&&message.equals(resolved.getString("message_id"))&&Arrays.equals(prepared,alice.store.loadSession(targets.get(0).address).serialize()),"signal_native_ui_ack_idempotent_retains_identity_without_ratchet_advance");
        JSONObject delivered=(JSONObject)b.request("GET","/v1/messages/"+message,null,true);
        JSONObject changed=new JSONObject(delivered.toString()).put("client_id",UUID.randomUUID().toString());
        byte[] receiveBefore=bob.store.loadSession(senderKey.address).serialize(),bindingBefore=bob.store.loadValue("private-peer",alice.user+"_"+alice.device);
        denied=false;try{receiving.receive(alice.user,conversation,changed,senderKey);}catch(SecurityException expected){denied=true;}
        check.accept(denied && Arrays.equals(receiveBefore,bob.store.loadSession(senderKey.address).serialize()) && Arrays.equals(bindingBefore,bob.store.loadValue("private-peer",alice.user+"_"+alice.device)),"signal_codec_authenticated_inner_rejection_rolls_back_receive");
        check.accept(text.equals(receiving.receive(alice.user,conversation,delivered,senderKey)) && text.equals(receiving2.receive(alice.user,conversation,(JSONObject)second.request("GET","/v1/messages/"+message,null,true),senderKey)),"signal_codec_two_device_authenticated_plaintext_delivery");
        JSONObject display=receiving.display(alice.user,conversation,message,alice.user,delivered.getInt("revision"),delivered.getInt("ordinal"));
        check.accept(text.equals(display.getString("text"))&&display.isNull("expires_at"),"signal_display_matches_authenticated_row_context");
        boolean displayDenied=true;
        for(int variant=0;variant<5;variant++){
            boolean rejected=false;try{receiving.display(variant==0?UUID.randomUUID().toString():alice.user,variant==1?UUID.randomUUID().toString():conversation,message,variant==2?bob.user:alice.user,delivered.getInt("revision")+(variant==3?1:0),delivered.getInt("ordinal")+(variant==4?1:0));}catch(SecurityException expected){rejected=true;}displayDenied &= rejected;
        }
        check.accept(displayDenied&&text.equals(receiving.cachedText(message)),"signal_display_rejects_wrong_peer_conversation_sender_revision_ordinal");
        java.util.concurrent.CompletableFuture<Boolean> nativePrivacy=new java.util.concurrent.CompletableFuture<>();
        org.telegram.messenger.AndroidUtilities.runOnUIThread(()->{try{
            org.telegram.tgnet.TLRPC.TL_message row=new org.telegram.tgnet.TLRPC.TL_message();row.id=delivered.getInt("ordinal");row.date=(int)(System.currentTimeMillis()/1000);row.message="Encrypted message";row.flags=256;row.from_id=new org.telegram.tgnet.TLRPC.TL_peerUser();row.from_id.user_id=1;row.peer_id=new org.telegram.tgnet.TLRPC.TL_peerUser();row.peer_id.user_id=2;row.media=new org.telegram.tgnet.TLRPC.TL_messageMediaEmpty();
            org.telegram.messenger.MessageObject object=new org.telegram.messenger.MessageObject(0,row,true,false);object.applyNewText(display.getString("text"));
            org.telegram.tgnet.SerializedData serialized=new org.telegram.tgnet.SerializedData();try{row.serializeToStream(serialized);nativePrivacy.complete("Encrypted message".equals(row.message)&&text.contentEquals(object.messageText)&&!new String(serialized.toByteArray(),StandardCharsets.UTF_8).contains(text));}finally{serialized.cleanup();}
        }catch(Throwable failure){nativePrivacy.completeExceptionally(failure);}});
        check.accept(nativePrivacy.get(20,java.util.concurrent.TimeUnit.SECONDS),"signal_native_volatile_layout_does_not_serialize_private_text");
        nativeHydration(context,b,delivered,text,check);
        identityVerification(context,a,alice,sending,bob,receiving,check);
        byte[] after=bob.store.loadSession(senderKey.address).serialize();
        check.accept(text.equals(receiving.receive(alice.user,conversation,delivered,null)) && Arrays.equals(after,bob.store.loadSession(senderKey.address).serialize()) && text.equals(sending.receive(bob.user,conversation,(JSONObject)a.request("GET","/v1/messages/"+message,null,true),null)),"signal_codec_duplicate_and_producing_device_history_cache");
        changed=new JSONObject(delivered.toString()).put("id",UUID.randomUUID().toString());denied=false;try{receiving.receive(alice.user,conversation,changed,null);}catch(SecurityException expected){denied=true;}
        check.accept(denied,"signal_codec_ciphertext_message_id_rebound_rejected");
        try(SignalDeviceKeys reopened=new SignalDeviceKeys(context,b)){check.accept(text.equals(new SignalMessages(reopened).cachedText(message)) && text.equals(new SignalMessages(reopened).receive(alice.user,conversation,delivered,null)),"signal_codec_encrypted_cache_survives_store_reopen");}
        a.request("DELETE","/v1/messages/"+message,null,true);
        JSONObject tombstone=(JSONObject)b.request("GET","/v1/messages/"+message,null,true);
        check.accept(!tombstone.isNull("deleted_at") && tombstone.getInt("revision")>delivered.getInt("revision") && tombstone.getJSONObject("payload").length()==0,"signal_codec_server_deletion_tombstone_delivery");
        check.accept(receiving.receive(alice.user,conversation,tombstone,null)==null && receiving.cachedText(message)==null,"signal_codec_deletion_purges_decrypted_cache");
        check.accept(receiving.display(alice.user,conversation,message,alice.user,delivered.getInt("revision"),delivered.getInt("ordinal"))==null,"signal_display_deleted_message_returns_no_plaintext");
        denied=false;try{receiving.receive(alice.user,conversation,delivered,null);}catch(SecurityException expected){denied=true;}
        check.accept(denied,"signal_codec_deleted_history_cannot_resurrect");
        sending.receive(bob.user,conversation,tombstone,null);denied=false;try{sending.send(client);}catch(SecurityException expected){denied=true;}
        check.accept(denied && sending.cachedText(message)==null && alice.store.loadValue("private-outgoing",client)==null,"signal_codec_deleted_own_message_fences_outbox");
        check.accept(sending.nativePending(3).length()==0,"signal_native_deleted_identity_not_recovered");
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
        long deadline=xyz.eclipseofficial.vyrn.data.Timestamps.parse(expiring.getString("expires_at")).toEpochMilli();
        while(System.currentTimeMillis()<=deadline)android.os.SystemClock.sleep(Math.max(1,Math.min(100,deadline-System.currentTimeMillis()+1)));
        check.accept(receiving.cachedText(expiresMessage)==null && sending.cachedText(expiresMessage)==null && bob.store.loadValue("private-cache",expiresMessage+"_1")==null && alice.store.loadValue("private-own",expiresClient)==null && alice.store.loadValue("private-outgoing",expiresClient)==null,"signal_codec_offline_cache_read_expiry_purges_plaintext");
        check.accept(receiving2.pruneExpired()==1 && receiving2.pruneExpired()==0 && bob2.store.loadValue("private-cache",expiresMessage+"_1")==null,"signal_codec_idle_expiry_sweep_is_durable_idempotent");
        denied=false;try{sending.send(expiresClient);}catch(SecurityException expected){denied=true;}
        check.accept(denied,"signal_codec_expired_own_outbox_cannot_retry");
        check.accept(receiving.receive(alice.user,conversation,extension,null)==null && receiving.cachedText(expiresMessage)==null,"signal_codec_expired_stale_extension_cannot_resurrect");
        a.request("PATCH","/v1/conversations/"+conversation,new JSONObject().put("disappearing_seconds",0),true);
        nativeCancellation(context,a,alice,sending,bob.user,conversation,targets.get(0).address,check);
    }

    private static void identityVerification(Context context,RestRepository repository,SignalDeviceKeys alice,SignalMessages sending,SignalDeviceKeys bob,SignalMessages receiving,BiConsumer<Boolean,String> check) throws Exception {
        JSONObject own=sending.verification(bob.user,bob.device),other=receiving.verification(alice.user,alice.device);String number=own.getString("fingerprint");
        check.accept(number.matches("[0-9]{60}")&&number.equals(other.getString("fingerprint"))&&!own.getBoolean("verified")&&!other.getBoolean("verified"),"signal_identity_pair_safety_number_symmetric_and_initially_unverified");
        boolean denied=false;try{sending.verifyPeer(bob.user,bob.device,(number.charAt(0)=='0'?"1":"0")+number.substring(1),true);}catch(SecurityException expected){denied=true;}check.accept(denied&&!sending.verification(bob.user,bob.device).getBoolean("verified"),"signal_verification_changed_display_number_cannot_verify");
        sending.verifyPeer(bob.user,bob.device,number,true);sending.verifyPeer(bob.user,bob.device,number,true);check.accept(sending.verification(bob.user,bob.device).getBoolean("verified")&&!receiving.verification(alice.user,alice.device).getBoolean("verified"),"signal_verification_explicit_local_confirmation_is_idempotent_not_remote_confirmation");
        try(SignalDeviceKeys reopened=SignalDeviceKeys.openLocal(context,repository)){check.accept(new SignalMessages(reopened).verification(bob.user,bob.device).toString().equals(sending.verification(bob.user,bob.device).toString()),"signal_verification_encrypted_status_and_original_number_survive_store_reopen");}
        denied=false;try{sending.verification(bob.user,UUID.randomUUID().toString());}catch(SecurityException expected){denied=true;}check.accept(denied,"signal_verification_unknown_device_cannot_inherit_verified_status");
        String name=bob.user+"_"+bob.device,address=own.getString("address");SignalProtocolAddress remote=new SignalProtocolAddress(address,1);byte[] record=alice.store.loadValue("private-peer",name),session=alice.store.loadSession(remote).serialize();
        try{
            for(int variant=0;variant<3;variant++){final int kind=variant;final boolean changedAddress=variant==0;alice.store.transaction(()->{JSONObject pinned=new JSONObject(new String(record,StandardCharsets.UTF_8));if(kind==2)pinned.put("version",pinned.getInt("version")+1);else if(changedAddress)pinned.put("address",UUID.randomUUID().toString());else pinned.put("identity",Base64.encodeToString(alice.store.getIdentityKeyPair().getPublicKey().serialize(),Base64.NO_WRAP));alice.store.saveValue("private-peer",name,pinned.toString().getBytes(StandardCharsets.UTF_8));alice.store.deleteSession(remote);return null;});
                denied=false;try{alice.targets(bob.user);}catch(SecurityException expected){denied=true;}check.accept(denied&&!alice.store.containsSession(remote),"signal_changed_pinned_"+(kind==2?"version_rollback":changedAddress?"address":"identity")+"_rejects_new_roster_before_session_creation");
                denied=false;try{sending.verifyCurrentPeer(bob.user,bob.device,number,true);}catch(SecurityException expected){denied=true;}check.accept(denied,"signal_changed_pinned_binding_cannot_be_repaired_by_old_verification_number_"+variant);
            }
        }finally{alice.store.transaction(()->{alice.store.saveValue("private-peer",name,record);alice.store.storeSession(remote,new org.signal.libsignal.protocol.state.SessionRecord(session));return null;});}
        sending.verifyPeer(bob.user,bob.device,number,false);check.accept(!sending.verification(bob.user,bob.device).getBoolean("verified")&&number.equals(sending.verification(bob.user,bob.device).getString("fingerprint"))&&Arrays.equals(session,alice.store.loadSession(remote).serialize()),"signal_verification_explicit_unverify_preserves_identity_number_and_ratchet");
    }

    private static void nativeCancellation(Context context,RestRepository repository,SignalDeviceKeys keys,SignalMessages messages,String peer,String conversation,SignalProtocolAddress address,BiConsumer<Boolean,String> check) throws Exception {
        String session=repository.vault.cachedSession(),text="لغو خصوصی 🔐";
        for(int outcome=0;outcome<3;outcome++){
            long random=8190319L+outcome;int local=-996-outcome;String client=UUID.nameUUIDFromBytes((keys.user+":"+random).getBytes(StandardCharsets.UTF_8)).toString();
            String body=messages.prepareNative(peer,conversation,client,text,3,local,9102,random);JSONObject binding=messages.nativeBinding(3,client);byte[] ratchet=keys.store.loadSession(address).serialize();String committed=null;
            if(outcome>0){committed=((JSONObject)repository.request("POST","/v1/conversations/"+conversation+"/messages",new JSONObject(body),true)).getString("id");if(outcome==2)repository.request("DELETE","/v1/messages/"+committed,null,true);}
            boolean denied=false;try{SignalMessages.requestNativeCancellation(context,repository,keys.user,session,keys.device,3,local,9102,random,binding.getInt("date")+1,peer);}catch(SecurityException expected){denied=true;}
            check.accept(denied&&!messages.nativeBinding(3,client).getBoolean("cancel_requested"),"signal_cancel_changed_original_date_rejected_"+outcome);
            java.util.concurrent.CountDownLatch locked=new java.util.concurrent.CountDownLatch(1),release=new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.CompletableFuture<Void> holder=java.util.concurrent.CompletableFuture.runAsync(()->{synchronized(repository.vault.guard()){locked.countDown();try{if(!release.await(10,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("cancel_lock_timeout");}catch(InterruptedException failure){throw new RuntimeException(failure);}}});
            try{if(!locked.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("cancel_lock_not_held");check.accept(SignalMessages.requestNativeCancellation(context,repository,keys.user,session,keys.device,3,local,9102,random,binding.getInt("date"),peer)&&release.getCount()==1,"signal_cancel_intent_persists_while_transport_vault_lock_held_"+outcome);}finally{release.countDown();holder.get(5,java.util.concurrent.TimeUnit.SECONDS);}
            JSONObject requested=messages.nativeBinding(3,client);SignalMessages.requestNativeCancellation(context,repository,keys.user,session,keys.device,3,local,9102,random,binding.getInt("date"),peer);
            check.accept(requested.toString().equals(messages.nativeBinding(3,client).toString())&&body.equals(new JSONObject(new String(keys.store.loadValue("private-outgoing",client),StandardCharsets.UTF_8)).getString("body"))&&Arrays.equals(ratchet,keys.store.loadSession(address).serialize()),"signal_cancel_repeated_intent_preserves_ciphertext_ratchet_and_retry_budget_"+outcome);
            denied=false;try{messages.send(client);}catch(SecurityException expected){denied=true;}check.accept(denied,"signal_cancel_intent_prevents_new_post_"+outcome);
            boolean cancelled=messages.resolveNativeCancellation(3,client);JSONObject resolved=messages.nativeBinding(3,client);
            check.accept(cancelled==(outcome!=1)&&resolved.getBoolean("cancelled")==cancelled&&(outcome==0?resolved.isNull("message_id"):committed.equals(resolved.getString("message_id"))),"signal_cancel_server_arbitrates_unsent_live_and_deleted_commit_"+outcome);
            if(cancelled){
                JSONObject retained=new JSONObject(new String(keys.store.loadValue("private-outgoing",client),StandardCharsets.UTF_8));
                check.accept(!retained.has("text")&&!retained.has("body")&&messages.claimNative(3,client)==null&&keys.store.loadValue("private-send-fence",client)!=null&&messages.nativePendingText(peer,3,local,9102,random)==null,"signal_cancel_retains_cleanup_identity_and_scrubs_payload_"+outcome);
                if(outcome==0){denied=false;try{repository.request("POST","/v1/conversations/"+conversation+"/messages",new JSONObject(body),true);}catch(RestRepository.ApiException expected){denied=expected.status==409;}check.accept(denied,"signal_cancel_server_fence_rejects_original_ciphertext_replay");}
                try(SignalDeviceKeys reopened=SignalDeviceKeys.openLocal(context,repository)){check.accept(new SignalMessages(reopened).nativeBinding(3,client).toString().equals(resolved.toString()),"signal_cancel_cleanup_metadata_survives_store_reopen_"+outcome);}
                denied=false;try{messages.acknowledgeNativeCancellation(3,client,local-1,9102,random);}catch(SecurityException expected){denied=true;}check.accept(denied&&!messages.nativeBinding(3,client).getBoolean("ui_done"),"signal_cancel_cleanup_changed_identity_rejected_"+outcome);
                messages.acknowledgeNativeCancellation(3,client,local,9102,random);
            }else{check.accept(text.equals(messages.cachedText(committed)),"signal_cancel_live_commit_keeps_message");messages.acknowledgeNative(3,client,local,9102,random,committed);repository.request("DELETE","/v1/messages/"+committed,null,true);messages.receive(peer,conversation,(JSONObject)repository.request("GET","/v1/messages/"+committed,null,true),null);}
            check.accept(messages.nativePending(3).length()==0&&Arrays.equals(ratchet,keys.store.loadSession(address).serialize()),"signal_cancel_only_resolved_work_leaves_pending_without_ratchet_advance_"+outcome);
        }
    }

    private static void nativeRetry(RestRepository repository,SignalDeviceKeys keys,SignalMessages messages,String client,String body,SignalProtocolAddress address,byte[] ratchet,BiConsumer<Boolean,String> check) throws Exception {
        boolean denied=false;try{messages.claimNative(0,client);}catch(SecurityException expected){denied=true;}check.accept(denied&&messages.nativeBinding(3,client).getInt("attempts")==0,"signal_native_retry_wrong_account_claim_rejected");
        String first=messages.claimNative(3,client);JSONObject claimed=messages.nativeBinding(3,client);
        check.accept(first!=null&&claimed.getInt("attempts")==1&&claimed.getLong("next_at")>System.currentTimeMillis()&&messages.claimNative(3,client)==null,"signal_native_retry_durable_lease_rejects_parallel_claim");
        denied=false;try{messages.failNative(3,client,UUID.randomUUID().toString(),new java.io.IOException("fixture"));}catch(SecurityException expected){denied=true;}check.accept(denied&&messages.nativeBinding(3,client).getInt("error")==0,"signal_native_retry_stale_attempt_cannot_change_record");
        messages.failNative(3,client,first,new java.io.IOException("fixture"));JSONObject retry=messages.nativeBinding(3,client);
        check.accept(!retry.getBoolean("failed")&&retry.getInt("error")==503&&retry.getLong("next_at")>0&&!messages.retryNative(3,client),"signal_native_retry_transient_failure_keeps_backoff_and_rejects_manual_budget_reset");
        for(int attempt=2;attempt<=8;attempt++){
            keys.store.transaction(()->{JSONObject value=new JSONObject(new String(keys.store.loadValue("private-outgoing",client),StandardCharsets.UTF_8));value.put("native_next_at",0);keys.store.saveValue("private-outgoing",client,value.toString().getBytes(StandardCharsets.UTF_8));return null;});
            String claim=messages.claimNative(3,client);if(claim==null)throw new AssertionError("retry_fixture_not_claimed");messages.failNative(3,client,claim,new java.io.IOException("fixture"));
        }
        JSONObject exhausted=messages.nativeBinding(3,client);messages.scheduleNative(3,client);
        check.accept(exhausted.getBoolean("failed")&&exhausted.getInt("attempts")==8&&messages.claimNative(3,client)==null&&messages.nativeBinding(3,client).getBoolean("failed"),"signal_native_retry_eight_failures_stop_automatic_delivery_and_rescheduling_cannot_reset");
        denied=false;try{messages.retryNative(0,client);}catch(SecurityException expected){denied=true;}check.accept(denied&&messages.nativeBinding(3,client).getBoolean("failed"),"signal_native_manual_retry_wrong_account_rejected");
        check.accept(messages.retryNative(3,client)&&!messages.retryNative(3,client)&&messages.nativeBinding(3,client).getInt("attempts")==0,"signal_native_manual_retry_only_failed_record_and_idempotent");
        Exception permanent=null;try{repository.request("GET","/v1/missing-private-retry-fixture",null,true);}catch(RestRepository.ApiException expected){permanent=expected;}if(permanent==null)throw new AssertionError("missing_fixture_not_denied");
        String second=messages.claimNative(3,client);messages.failNative(3,client,second,permanent);
        check.accept(messages.nativeBinding(3,client).getBoolean("failed")&&messages.nativeBinding(3,client).getInt("error")==404&&messages.claimNative(3,client)==null,"signal_native_retry_permanent_http_error_stops_without_eight_attempts");
        messages.retryNative(3,client);String current=messages.claimNative(3,client);denied=false;try{messages.failNative(3,client,second,new java.io.IOException("fixture"));}catch(SecurityException expected){denied=true;}
        check.accept(denied&&messages.nativeBinding(3,client).getInt("error")==0,"signal_native_retry_previous_cycle_callback_rejected");
        messages.failNative(3,client,current,new SecurityException("fixture"));messages.retryNative(3,client);
        check.accept(body.equals(new JSONObject(new String(keys.store.loadValue("private-outgoing",client),StandardCharsets.UTF_8)).getString("body"))&&Arrays.equals(ratchet,keys.store.loadSession(address).serialize()),"signal_native_retry_failed_manual_cycles_preserve_original_ciphertext_and_ratchet");
    }
}
