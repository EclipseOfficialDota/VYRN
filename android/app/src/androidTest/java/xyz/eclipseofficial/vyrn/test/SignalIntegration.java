package xyz.eclipseofficial.vyrn.test;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.UUID;
import java.util.function.BiConsumer;
import org.json.JSONObject;
import org.signal.libsignal.protocol.*;
import org.signal.libsignal.protocol.ecc.ECKeyPair;
import org.signal.libsignal.protocol.ecc.ECPublicKey;
import org.signal.libsignal.protocol.kem.KEMKeyPair;
import org.signal.libsignal.protocol.kem.KEMKeyType;
import org.signal.libsignal.protocol.message.*;
import org.signal.libsignal.protocol.state.*;
import xyz.eclipseofficial.vyrn.data.SignalStore;

/** Real Android JNI/Keystore/PQXDH checks; independent of the unfinished chat UI. */
final class SignalIntegration {
    private static final byte[] HELLO="پیام خصوصی آزمایشی VYRN".getBytes(StandardCharsets.UTF_8);
    private static byte[] packed(CiphertextMessage message){byte[] ciphertext=message.serialize();byte[] result=new byte[ciphertext.length+1];result[0]=(byte)message.getType();System.arraycopy(ciphertext,0,result,1,ciphertext.length);return result;}
    private static byte[] decrypt(SessionCipher cipher,byte[] packed) throws Exception {
        byte[] ciphertext=Arrays.copyOfRange(packed,1,packed.length);
        if(packed[0]==CiphertextMessage.PREKEY_TYPE)return cipher.decrypt(new PreKeySignalMessage(ciphertext));
        if(packed[0]==CiphertextMessage.WHISPER_TYPE)return cipher.decrypt(new SignalMessage(ciphertext));
        throw new IllegalArgumentException("unsupported_test_ciphertext");
    }
    private static PreKeyBundle bundle(SignalStore store) throws Exception {
        return store.transaction(()->{
            ECKeyPair ec=ECKeyPair.generate(),signed=ECKeyPair.generate();KEMKeyPair kyber=KEMKeyPair.generate(KEMKeyType.KYBER_1024);
            byte[] signedSignature=store.getIdentityKeyPair().getPrivateKey().calculateSignature(signed.getPublicKey().serialize());
            byte[] kyberSignature=store.getIdentityKeyPair().getPrivateKey().calculateSignature(kyber.getPublicKey().serialize());
            store.storePreKey(10,new PreKeyRecord(10,ec));store.storeSignedPreKey(1,new SignedPreKeyRecord(1,System.currentTimeMillis(),signed,signedSignature));store.storeKyberPreKey(1,new KyberPreKeyRecord(1,System.currentTimeMillis(),kyber,kyberSignature));
            return new PreKeyBundle(store.getLocalRegistrationId(),1,10,ec.getPublicKey(),1,signed.getPublicKey(),signedSignature,store.getIdentityKeyPair().getPublicKey(),1,kyber.getPublicKey(),kyberSignature);
        });
    }
    private static File database(Context context,String user,String device){return new File(context.getNoBackupFilesDir(),"vyrn_signal_"+user+"_"+device+".db");}
    private static boolean contains(byte[] data,byte[] needle){outer:for(int i=0;i<=data.length-needle.length;i++){for(int j=0;j<needle.length;j++)if(data[i+j]!=needle[j])continue outer;return true;}return false;}
    static void run(Context context,String phase,BiConsumer<Boolean,String> check) throws Exception {
        if(phase.equals("signal")) {
            java.lang.reflect.Method classify=xyz.eclipseofficial.vyrn.compat.VyrnBridge.class.getDeclaredMethod("problem",Throwable.class);classify.setAccessible(true);
            for(Throwable failure:new Throwable[]{new javax.net.ssl.SSLHandshakeException("test"),new java.io.IOException("storage"),new SecurityException("account_changed")})check.accept(!"VYRN_RETRYABLE_REQUEST_FAILED".equals(((org.telegram.tgnet.TLRPC.TL_error)classify.invoke(null,failure)).text),"private_edit_permanent_"+failure.getClass().getSimpleName()+"_not_retryable");
            check.accept("VYRN_RETRYABLE_REQUEST_FAILED".equals(((org.telegram.tgnet.TLRPC.TL_error)classify.invoke(null,new java.net.ConnectException("offline"))).text),"private_edit_connection_failure_retryable");
            javax.net.ssl.SSLHandshakeException interrupted=new javax.net.ssl.SSLHandshakeException("test");interrupted.initCause(new java.io.EOFException());
            check.accept("VYRN_RETRYABLE_REQUEST_FAILED".equals(((org.telegram.tgnet.TLRPC.TL_error)classify.invoke(null,interrupted)).text),"private_edit_tls_connection_closed_retryable");
            javax.net.ssl.SSLHandshakeException certificate=new javax.net.ssl.SSLHandshakeException("test");certificate.initCause(new java.security.cert.CertificateException());
            check.accept(!"VYRN_RETRYABLE_REQUEST_FAILED".equals(((org.telegram.tgnet.TLRPC.TL_error)classify.invoke(null,certificate)).text),"private_edit_tls_certificate_failure_not_retryable");
            java.time.Instant expected=java.time.Instant.ofEpochSecond(1767225600L,123456789);
            check.accept(expected.equals(xyz.eclipseofficial.vyrn.data.Timestamps.parse("2026-01-01T00:00:00.123456789Z"))
                    && expected.equals(xyz.eclipseofficial.vyrn.data.Timestamps.parse("2026-01-01T00:00:00.123456789+00:00"))
                    && expected.equals(xyz.eclipseofficial.vyrn.data.Timestamps.parse("2026-01-01T03:30:00.123456789+03:30")),"timestamp_utc_numeric_offset_and_nanoseconds");
            boolean denied=false;
            try {xyz.eclipseofficial.vyrn.data.Timestamps.parse("2026-01-01T00:00:00");}
            catch(java.time.format.DateTimeParseException expectedFailure) {denied=true;}
            check.accept(denied,"timestamp_missing_offset_rejected");
        }
        File state=new File(context.getFilesDir(),"vyrn-signal-test.json");
        if(phase.equals("signal-restart")){
            JSONObject saved=new JSONObject(new String(Files.readAllBytes(state.toPath()),StandardCharsets.UTF_8));
            String a=saved.getString("alice"),ad=saved.getString("alice_device"),b=saved.getString("bob"),bd=saved.getString("bob_device");
            try(SignalStore alice=new SignalStore(context,a,ad);SignalStore bob=new SignalStore(context,b,bd)){
                check.accept(Arrays.equals(alice.getIdentityKeyPair().getPublicKey().serialize(),android.util.Base64.decode(saved.getString("identity"),android.util.Base64.NO_WRAP)),"signal_identity_process_restart");
                SignalProtocolAddress aa=new SignalProtocolAddress(saved.getString("alice_address"),1),ba=new SignalProtocolAddress(saved.getString("bob_address"),1);
                check.accept(alice.containsSession(ba) && bob.containsSession(aa),"signal_sessions_process_restart");
                byte[] pending=alice.loadValue("outgoing","next");
                byte[] text=bob.transaction(()->decrypt(new SessionCipher(bob,ba,aa),pending));
                check.accept(Arrays.equals(text,"after restart".getBytes(StandardCharsets.UTF_8)),"signal_pending_ciphertext_process_restart");
                byte[] reply=bob.transaction(()->packed(new SessionCipher(bob,ba,aa).encrypt(HELLO)));
                byte[] decoded=alice.transaction(()->decrypt(new SessionCipher(alice,aa,ba),reply));
                check.accept(Arrays.equals(decoded,HELLO),"signal_ratchet_reply_after_restart");
                check.accept(!bob.containsPreKey(10),"signal_consumed_prekey_process_restart");
                ECPublicKey replayBase=new ECPublicKey(android.util.Base64.decode(saved.getString("replay_base"),android.util.Base64.NO_WRAP),0);
                boolean refused=false;try{bob.transaction(()->{bob.markKyberPreKeyUsed(1,1,replayBase);return null;});}catch(ReusedBaseKeyException expected){refused=true;}
                check.accept(refused,"signal_kyber_base_replay_process_restart");
            }
            return;
        }
        if(!phase.equals("signal"))throw new IllegalArgumentException("invalid_signal_test_phase");
        String a=UUID.randomUUID().toString(),ad=UUID.randomUUID().toString(),b=UUID.randomUUID().toString(),bd=UUID.randomUUID().toString();
        String aliceAddress=UUID.randomUUID().toString(),bobAddress=UUID.randomUUID().toString();
        SignalProtocolAddress aa=new SignalProtocolAddress(aliceAddress,1),ba=new SignalProtocolAddress(bobAddress,1);
        String publicationDevice=UUID.randomUUID().toString();
        String publication;
        try(SignalStore prepared=new SignalStore(context,a,publicationDevice)){
            JSONObject keys=prepared.initialPublication();publication=keys.toString();
            boolean matched=keys.getInt("expected_version")==0 && keys.getInt("registration_id")==prepared.getLocalRegistrationId() && keys.getJSONArray("prekeys").length()==100;
            for(int id=1;id<=100;id++)matched &= keys.getJSONArray("prekeys").getJSONObject(id-1).getInt("id")==id && keys.getJSONArray("prekeys").getJSONObject(id-1).getString("public_key").equals(android.util.Base64.encodeToString(prepared.loadPreKey(id).getKeyPair().getPublicKey().serialize(),android.util.Base64.NO_WRAP));
            matched &= keys.getJSONObject("signed_prekey").getString("signature").equals(android.util.Base64.encodeToString(prepared.loadSignedPreKey(1).getSignature(),android.util.Base64.NO_WRAP));
            matched &= keys.getJSONObject("kyber_prekey").getString("public_key").equals(android.util.Base64.encodeToString(prepared.loadKyberPreKey(1).getKeyPair().getPublicKey().serialize(),android.util.Base64.NO_WRAP));
            check.accept(matched,"signal_publication_private_keys_persisted");
            prepared.transaction(()->{prepared.removePreKey(1);return null;});
            check.accept(publication.equals(prepared.initialPublication().toString()) && !prepared.containsPreKey(1),"signal_publication_retry_no_regeneration");
        }
        try(SignalStore prepared=new SignalStore(context,a,publicationDevice)){
            check.accept(publication.equals(prepared.initialPublication().toString()) && !prepared.containsPreKey(1),"signal_publication_reopen_exact_retry");
            prepared.transaction(()->{prepared.removeValue("keys","initial");return null;});
            boolean refused=false;try{prepared.initialPublication();}catch(SecurityException expected){refused=true;}
            check.accept(refused && prepared.containsPreKey(2),"signal_missing_publication_preserves_existing_keys");
        }
        try(SignalStore alice=new SignalStore(context,a,ad);SignalStore bob=new SignalStore(context,b,bd)){
            check.accept(!Arrays.equals(alice.getIdentityKeyPair().serialize(),bob.getIdentityKeyPair().serialize()),"signal_independent_device_identities");
            boolean refused=false;try{alice.saveValue("outgoing","unsafe",HELLO);}catch(IllegalStateException expected){refused=true;}check.accept(refused,"signal_mutation_requires_transaction");
            PreKeyBundle prekey=bundle(bob);
            byte[] badSignature=prekey.getSignedPreKeySignature().clone();badSignature[0]^=1;
            PreKeyBundle forged=new PreKeyBundle(prekey.getRegistrationId(),1,prekey.getPreKeyId(),prekey.getPreKey(),prekey.getSignedPreKeyId(),prekey.getSignedPreKey(),badSignature,prekey.getIdentityKey(),prekey.getKyberPreKeyId(),prekey.getKyberPreKey(),prekey.getKyberPreKeySignature());
            refused=false;try{alice.transaction(()->{new SessionBuilder(alice,ba,aa).process(forged);return null;});}catch(InvalidKeyException expected){refused=true;}check.accept(refused && !alice.containsSession(ba),"signal_reject_invalid_signed_prekey");
            byte[] badKyberSignature=prekey.getKyberPreKeySignature().clone();badKyberSignature[0]^=1;
            PreKeyBundle forgedKyber=new PreKeyBundle(prekey.getRegistrationId(),1,prekey.getPreKeyId(),prekey.getPreKey(),prekey.getSignedPreKeyId(),prekey.getSignedPreKey(),prekey.getSignedPreKeySignature(),prekey.getIdentityKey(),prekey.getKyberPreKeyId(),prekey.getKyberPreKey(),badKyberSignature);
            refused=false;try{alice.transaction(()->{new SessionBuilder(alice,ba,aa).process(forgedKyber);return null;});}catch(InvalidKeyException expected){refused=true;}check.accept(refused && !alice.containsSession(ba),"signal_reject_invalid_kyber_prekey");
            alice.transaction(()->{new SessionBuilder(alice,ba,aa).process(prekey);return null;});
            check.accept(alice.loadSession(ba).getSessionVersion()==4,"signal_real_PQXDH_session");
            byte[] before=alice.loadSession(ba).serialize();
            refused=false;try{alice.transaction(()->{alice.saveValue("outgoing","rollback",packed(new SessionCipher(alice,aa,ba).encrypt(HELLO)));throw new IllegalStateException("simulated_outbox_failure");});}catch(IllegalStateException expected){refused=true;}
            check.accept(refused && alice.loadValue("outgoing","rollback")==null && Arrays.equals(before,alice.loadSession(ba).serialize()),"signal_atomic_ratchet_outbox_rollback");
            byte[] outgoing=alice.transaction(()->{byte[] message=packed(new SessionCipher(alice,aa,ba).encrypt(HELLO));alice.saveValue("outgoing","first",message);return message;});
            check.accept(outgoing[0]==CiphertextMessage.PREKEY_TYPE && Arrays.equals(outgoing,alice.loadValue("outgoing","first")),"signal_durable_exact_ciphertext_retry");
            byte[] corrupted=outgoing.clone();corrupted[corrupted.length-1]^=1;
            refused=false;try{bob.transaction(()->decrypt(new SessionCipher(bob,ba,aa),corrupted));}catch(InvalidMessageException expected){refused=true;}
            check.accept(refused && !bob.containsSession(aa) && bob.containsPreKey(10),"signal_tamper_failure_preserves_prekey");
            byte[] plaintext=bob.transaction(()->{byte[] decoded=decrypt(new SessionCipher(bob,ba,aa),outgoing);bob.saveValue("incoming","first",decoded);return decoded;});
            check.accept(Arrays.equals(plaintext,HELLO) && !bob.containsPreKey(10),"signal_PQXDH_decryption_consumes_prekey");
            check.accept(Arrays.equals(bob.loadValue("incoming","first"),HELLO),"signal_transactional_decrypted_cache");
            refused=false;try{bob.transaction(()->decrypt(new SessionCipher(bob,ba,aa),outgoing));}catch(DuplicateMessageException expected){refused=true;}check.accept(refused,"signal_reject_ciphertext_replay");
            byte[] reply=bob.transaction(()->packed(new SessionCipher(bob,ba,aa).encrypt(HELLO)));
            check.accept(reply[0]==CiphertextMessage.WHISPER_TYPE && Arrays.equals(alice.transaction(()->decrypt(new SessionCipher(alice,aa,ba),reply)),HELLO),"signal_bidirectional_ratchet");
            byte[] first=alice.transaction(()->packed(new SessionCipher(alice,aa,ba).encrypt("first".getBytes(StandardCharsets.UTF_8))));
            byte[] second=alice.transaction(()->packed(new SessionCipher(alice,aa,ba).encrypt("second".getBytes(StandardCharsets.UTF_8))));
            check.accept(Arrays.equals(bob.transaction(()->decrypt(new SessionCipher(bob,ba,aa),second)),"second".getBytes(StandardCharsets.UTF_8)) && Arrays.equals(bob.transaction(()->decrypt(new SessionCipher(bob,ba,aa),first)),"first".getBytes(StandardCharsets.UTF_8)),"signal_out_of_order_ratchet");
            IdentityKey impostor=IdentityKeyPair.generate().getPublicKey();refused=false;try{alice.transaction(()->{alice.saveIdentity(ba,impostor);return null;});}catch(SecurityException expected){refused=true;}
            check.accept(refused && !alice.isTrustedIdentity(ba,impostor,IdentityKeyStore.Direction.SENDING) && !alice.isTrustedIdentity(ba,impostor,IdentityKeyStore.Direction.RECEIVING),"signal_identity_change_fails_closed");
            byte[] privateIdentity=alice.getIdentityKeyPair().serialize();
            boolean absent=true;
            for(File path:new File[]{database(context,a,ad),database(context,b,bd)})try(SQLiteDatabase db=SQLiteDatabase.openDatabase(path.getAbsolutePath(),null,SQLiteDatabase.OPEN_READONLY);Cursor rows=db.rawQuery("SELECT value FROM records",null)){
                while(rows.moveToNext()){byte[] blob=rows.getBlob(0);absent &= !contains(blob,privateIdentity) && !contains(blob,HELLO);}
            }
            check.accept(absent,"signal_records_encrypted_at_rest");
            ECPublicKey replayBase=ECKeyPair.generate().getPublicKey();bob.transaction(()->{bob.markKyberPreKeyUsed(1,1,replayBase);return null;});
            alice.transaction(()->{alice.saveValue("outgoing","next",packed(new SessionCipher(alice,aa,ba).encrypt("after restart".getBytes(StandardCharsets.UTF_8))));return null;});
            JSONObject saved=new JSONObject().put("alice",a).put("alice_device",ad).put("bob",b).put("bob_device",bd).put("alice_address",aliceAddress).put("bob_address",bobAddress).put("identity",android.util.Base64.encodeToString(alice.getIdentityKeyPair().getPublicKey().serialize(),android.util.Base64.NO_WRAP)).put("replay_base",android.util.Base64.encodeToString(replayBase.serialize(),android.util.Base64.NO_WRAP));
            Files.write(state.toPath(),saved.toString().getBytes(StandardCharsets.UTF_8));
        }
        // Deleting only the key generated for this isolated fixture must not regenerate identity.
        String missing=UUID.randomUUID().toString();try(SignalStore isolated=new SignalStore(context,a,missing)){
            isolated.transaction(()->{isolated.saveValue("binding","source",HELLO);isolated.saveValue("binding","target",HELLO);return null;});
            try(SQLiteDatabase db=SQLiteDatabase.openDatabase(database(context,a,missing).getAbsolutePath(),null,SQLiteDatabase.OPEN_READWRITE)){
                db.execSQL("UPDATE records SET value=(SELECT value FROM records WHERE kind='app:binding' AND name='source') WHERE kind='app:binding' AND name='target'");
            }
            boolean refused=false;try{isolated.loadValue("binding","target");}catch(SecurityException expected){refused=true;}
            check.accept(refused,"signal_encrypted_record_binding_rejects_swap");
        }
        KeyStore keys=KeyStore.getInstance("AndroidKeyStore");keys.load(null);keys.deleteEntry("vyrn.signal."+a+"_"+missing);
        boolean refused=false;try(SignalStore ignored=new SignalStore(context,a,missing)){}catch(SecurityException expected){refused=true;}check.accept(refused,"signal_missing_keystore_key_fails_closed");
    }
}
