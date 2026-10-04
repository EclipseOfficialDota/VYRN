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

/** Actual HTTPS public-key exchange and JNI decryption; private-chat UI/fanout remain disabled. */
final class SignalNetworkIntegration {
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
            a.request("POST","/v1/conversations",new JSONObject().put("kind","private").put("members",new JSONArray().put(bob.user)),true);
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
            byte[] ciphertext=alice.store.transaction(()->new SessionCipher(alice.store,aa,ba).encrypt(text).serialize());
            byte[] decoded=bob.store.transaction(()->new SessionCipher(bob.store,ba,aa).decrypt(new PreKeySignalMessage(ciphertext)));
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
        }finally{
            try{a.request("POST","/v1/auth/logout",new JSONObject(),true);}finally{a.vault.clear();}
            try{b.request("POST","/v1/auth/logout",new JSONObject(),true);}finally{b.vault.clear();}
        }
    }
}
