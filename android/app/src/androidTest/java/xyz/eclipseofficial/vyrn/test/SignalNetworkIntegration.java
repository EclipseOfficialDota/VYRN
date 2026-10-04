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
                SignalProtocolAddress b2a=bob2.publishInitial();
                check.accept(bob2.device.equals(alice.device) && !b2a.equals(aa) && !bob2.device.equals(second.vault.device()),"signal_network_cross_account_device_UUID_addresses");
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
                second.request("POST","/v1/auth/logout",new JSONObject(),true);
                denied=false;try{a.request("POST","/v1/conversations/"+conversation+"/messages",new JSONObject(multiple.toString()).put("client_id",UUID.randomUUID().toString()),true);}catch(RestRepository.ApiException expected){denied=expected.status==409;}
                check.accept(denied,"signal_network_revoked_recipient_copy_rejected");
                check.accept(multipleId.equals(((JSONObject)a.request("POST","/v1/conversations/"+conversation+"/messages",multiple,true)).getString("id")),"signal_network_committed_retry_after_device_revocation");
            }finally{second.vault.clear();}
        }finally{
            try{a.request("POST","/v1/auth/logout",new JSONObject(),true);}finally{a.vault.clear();}
            try{b.request("POST","/v1/auth/logout",new JSONObject(),true);}finally{b.vault.clear();}
        }
    }
}
