// SPDX-License-Identifier: AGPL-3.0-only
package xyz.eclipseofficial.vyrn.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONArray;
import org.json.JSONObject;
import org.signal.libsignal.protocol.IdentityKey;
import org.signal.libsignal.protocol.IdentityKeyPair;
import org.signal.libsignal.protocol.InvalidKeyIdException;
import org.signal.libsignal.protocol.NoSessionException;
import org.signal.libsignal.protocol.ReusedBaseKeyException;
import org.signal.libsignal.protocol.SignalProtocolAddress;
import org.signal.libsignal.protocol.ecc.ECPublicKey;
import org.signal.libsignal.protocol.ecc.ECKeyPair;
import org.signal.libsignal.protocol.kem.KEMKeyPair;
import org.signal.libsignal.protocol.kem.KEMKeyType;
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord;
import org.signal.libsignal.protocol.state.*;
import org.signal.libsignal.protocol.util.KeyHelper;

/** Device-scoped libsignal state. Every mutation must share its caller's transaction. */
public final class SignalStore implements SignalProtocolStore, AutoCloseable {
    private static final ConcurrentHashMap<String,Object> LOCKS=new ConcurrentHashMap<>();
    private final String namespace, alias;
    private final Object guard;
    private final SQLiteOpenHelper helper;

    public SignalStore(Context context,String user,String device) throws Exception {
        if(!UUID.fromString(user).toString().equals(user) || !UUID.fromString(device).toString().equals(device))throw new IllegalArgumentException("invalid_signal_owner");
        namespace=user+"_"+device; alias="vyrn.signal."+namespace; guard=LOCKS.computeIfAbsent(namespace,ignored->new Object());
        String path=new File(context.getNoBackupFilesDir(),"vyrn_signal_"+namespace+".db").getAbsolutePath();
        helper=new SQLiteOpenHelper(context.getApplicationContext(),path,null,1){
            @Override public void onConfigure(SQLiteDatabase db){db.execSQL("PRAGMA synchronous=FULL");}
            @Override public void onCreate(SQLiteDatabase db){db.execSQL("CREATE TABLE records(kind TEXT NOT NULL,name TEXT NOT NULL,device INTEGER NOT NULL,key_id INTEGER NOT NULL,value BLOB NOT NULL,PRIMARY KEY(kind,name,device,key_id)) WITHOUT ROWID");}
            @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion){throw new IllegalStateException("unsupported_signal_schema");}
        };
        try { transaction(()->{
            byte[] identity=read("local","",0,0), registration=read("local","",0,1);
            if(identity==null && registration==null){
                if(count()!=0)throw new SecurityException("missing_signal_identity");
                write("local","",0,0,IdentityKeyPair.generate().serialize());
                write("local","",0,1,ByteBuffer.allocate(4).putInt(KeyHelper.generateRegistrationId(false)).array());
            }else if(identity==null || registration==null || registration.length!=4){throw new SecurityException("incomplete_signal_identity");}
            getIdentityKeyPair(); getLocalRegistrationId(); return null;
        }); } catch(Exception failure) { helper.close(); throw failure; }
    }

    /** Ratchet advancement and durable ciphertext/plaintext-cache writes commit together. */
    public <T> T transaction(Callable<T> operation) throws Exception { synchronized(guard){
        SQLiteDatabase db=helper.getWritableDatabase();
        if(db.inTransaction())throw new IllegalStateException("nested_signal_transaction");
        db.beginTransaction();
        try{T result=operation.call();db.setTransactionSuccessful();return result;}finally{db.endTransaction();}
    }}
    private long count(){try(Cursor c=helper.getReadableDatabase().rawQuery("SELECT count(*) FROM records",null)){c.moveToFirst();return c.getLong(0);}}
    private SecretKey key(boolean create) throws Exception {
        KeyStore keys=KeyStore.getInstance("AndroidKeyStore");keys.load(null);
        if(!keys.containsAlias(alias)){
            if(!create || count()!=0)throw new SecurityException("signal_keystore_key_missing");
            KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build());
            generator.generateKey();
        }
        return (SecretKey)keys.getKey(alias,null);
    }
    private byte[] aad(String kind,String name,int device,int id){return new JSONArray().put(namespace).put(kind).put(name).put(device).put(id).toString().getBytes(StandardCharsets.UTF_8);}
    private String[] selector(String kind,String name,int device,int id){return new String[]{kind,name,Integer.toString(device),Integer.toString(id)};}
    private byte[] read(String kind,String name,int device,int id){synchronized(guard){
        try(Cursor c=helper.getReadableDatabase().rawQuery("SELECT value FROM records WHERE kind=? AND name=? AND device=? AND key_id=?",selector(kind,name,device,id))){
            if(!c.moveToFirst())return null;
            byte[] value=c.getBlob(0);if(value.length<29 || value[0]!=1)throw new SecurityException("invalid_signal_record");
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(false),new GCMParameterSpec(128,Arrays.copyOfRange(value,1,13)));cipher.updateAAD(aad(kind,name,device,id));return cipher.doFinal(value,13,value.length-13);
        }catch(Exception failure){throw new SecurityException("signal_storage_read_failed",failure);}
    }}
    private void requireTransaction(){if(!helper.getWritableDatabase().inTransaction())throw new IllegalStateException("signal_transaction_required");}
    private void write(String kind,String name,int device,int id,byte[] value){synchronized(guard){
        requireTransaction();
        try{
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key(true));cipher.updateAAD(aad(kind,name,device,id));
            byte[] iv=cipher.getIV(), encrypted=cipher.doFinal(value);if(iv.length!=12)throw new SecurityException("invalid_signal_iv");
            ContentValues row=new ContentValues();row.put("kind",kind);row.put("name",name);row.put("device",device);row.put("key_id",id);row.put("value",ByteBuffer.allocate(13+encrypted.length).put((byte)1).put(iv).put(encrypted).array());
            if(helper.getWritableDatabase().insertWithOnConflict("records",null,row,SQLiteDatabase.CONFLICT_REPLACE)<0)throw new IllegalStateException("signal_record_not_saved");
        }catch(Exception failure){throw new SecurityException("signal_storage_write_failed",failure);}
    }}
    private void remove(String kind,String name,int device,int id){synchronized(guard){requireTransaction();helper.getWritableDatabase().delete("records","kind=? AND name=? AND device=? AND key_id=?",selector(kind,name,device,id));}}
    private List<Integer> ids(String kind,String name){synchronized(guard){List<Integer> out=new ArrayList<>();try(Cursor c=helper.getReadableDatabase().rawQuery("SELECT key_id FROM records WHERE kind=? AND name=? ORDER BY key_id",new String[]{kind,name})){while(c.moveToNext())out.add(c.getInt(0));}return out;}}
    /** Private application records, for transactional outbox and decrypted cache persistence. */
    public byte[] loadValue(String kind,String id){return read("app:"+kind,id,0,0);}
    public void saveValue(String kind,String id,byte[] value){write("app:"+kind,id,0,0,value);}
    public void removeValue(String kind,String id){remove("app:"+kind,id,0,0);}
    public void removeValues(String kind,String prefix){synchronized(guard){requireTransaction();helper.getWritableDatabase().delete("records","kind=? AND substr(name,1,?)=?",new String[]{"app:"+kind,Integer.toString(prefix.length()),prefix});}}

    /** Persist private prekeys before exposing their public publication; retries reuse exact bytes. */
    public JSONObject initialPublication() throws Exception {return transaction(()->{
        byte[] pending=loadValue("keys","initial");
        if(pending!=null)return new JSONObject(new String(pending,StandardCharsets.UTF_8));
        // Never overwrite existing private key IDs when a publication record is missing.
        if(!ids("prekey","").isEmpty() || !ids("signed","").isEmpty() || !ids("kyber","").isEmpty())throw new SecurityException("signal_publication_missing");
        long now=System.currentTimeMillis();
        ECKeyPair signed=ECKeyPair.generate();KEMKeyPair kyber=KEMKeyPair.generate(KEMKeyType.KYBER_1024);
        byte[] signature=getIdentityKeyPair().getPrivateKey().calculateSignature(signed.getPublicKey().serialize());
        byte[] kyberSignature=getIdentityKeyPair().getPrivateKey().calculateSignature(kyber.getPublicKey().serialize());
        storeSignedPreKey(1,new SignedPreKeyRecord(1,now,signed,signature));
        storeKyberPreKey(1,new KyberPreKeyRecord(1,now,kyber,kyberSignature));
        JSONArray prekeys=new JSONArray();
        for(int id=1;id<=100;id++){ECKeyPair pair=ECKeyPair.generate();storePreKey(id,new PreKeyRecord(id,pair));prekeys.put(publicKey(id,pair.getPublicKey().serialize(),null));}
        JSONObject publication=new JSONObject().put("expected_version",0).put("registration_id",getLocalRegistrationId())
            .put("identity_key",Base64.encodeToString(getIdentityKeyPair().getPublicKey().serialize(),Base64.NO_WRAP))
            .put("signed_prekey",publicKey(1,signed.getPublicKey().serialize(),signature))
            .put("kyber_prekey",publicKey(1,kyber.getPublicKey().serialize(),kyberSignature)).put("prekeys",prekeys);
        saveValue("keys","initial",publication.toString().getBytes(StandardCharsets.UTF_8));return publication;
    });}
    private static JSONObject publicKey(int id,byte[] key,byte[] signature) throws Exception {
        JSONObject result=new JSONObject().put("id",id).put("public_key",Base64.encodeToString(key,Base64.NO_WRAP));
        if(signature!=null)result.put("signature",Base64.encodeToString(signature,Base64.NO_WRAP));return result;
    }

    @Override public IdentityKeyPair getIdentityKeyPair(){try{return new IdentityKeyPair(read("local","",0,0));}catch(Exception e){throw new SecurityException("invalid_signal_identity",e);}}
    @Override public int getLocalRegistrationId(){byte[] bytes=read("local","",0,1);if(bytes==null || bytes.length!=4)throw new SecurityException("invalid_signal_registration");int id=ByteBuffer.wrap(bytes).getInt();if(id<1 || id>16380)throw new SecurityException("invalid_signal_registration");return id;}
    @Override public IdentityChange saveIdentity(SignalProtocolAddress address,IdentityKey identity){
        IdentityKey previous=getIdentity(address);
        if(previous!=null && !Arrays.equals(previous.serialize(),identity.serialize()))throw new SecurityException("signal_identity_changed");
        write("identity",address.getName(),address.getDeviceId(),0,identity.serialize());return IdentityChange.NEW_OR_UNCHANGED;
    }
    @Override public boolean isTrustedIdentity(SignalProtocolAddress address,IdentityKey identity,Direction direction){IdentityKey previous=getIdentity(address);return previous==null || Arrays.equals(previous.serialize(),identity.serialize());}
    @Override public IdentityKey getIdentity(SignalProtocolAddress address){byte[] bytes=read("identity",address.getName(),address.getDeviceId(),0);try{return bytes==null?null:new IdentityKey(bytes);}catch(Exception e){throw new SecurityException("invalid_signal_peer_identity",e);}}
    @Override public PreKeyRecord loadPreKey(int id) throws InvalidKeyIdException {byte[] bytes=read("prekey","",0,id);if(bytes==null)throw new InvalidKeyIdException("missing_prekey");try{return new PreKeyRecord(bytes);}catch(Exception e){throw new SecurityException("invalid_signal_prekey",e);}}
    @Override public void storePreKey(int id,PreKeyRecord record){write("prekey","",0,id,record.serialize());}
    @Override public boolean containsPreKey(int id){return read("prekey","",0,id)!=null;}
    @Override public void removePreKey(int id){remove("prekey","",0,id);}
    @Override public SignedPreKeyRecord loadSignedPreKey(int id) throws InvalidKeyIdException {byte[] bytes=read("signed","",0,id);if(bytes==null)throw new InvalidKeyIdException("missing_signed_prekey");try{return new SignedPreKeyRecord(bytes);}catch(Exception e){throw new SecurityException("invalid_signal_signed_prekey",e);}}
    @Override public List<SignedPreKeyRecord> loadSignedPreKeys(){List<SignedPreKeyRecord> records=new ArrayList<>();try{for(int id:ids("signed",""))records.add(loadSignedPreKey(id));}catch(InvalidKeyIdException e){throw new SecurityException(e);}return records;}
    @Override public void storeSignedPreKey(int id,SignedPreKeyRecord record){write("signed","",0,id,record.serialize());}
    @Override public boolean containsSignedPreKey(int id){return read("signed","",0,id)!=null;}
    @Override public void removeSignedPreKey(int id){remove("signed","",0,id);}
    @Override public KyberPreKeyRecord loadKyberPreKey(int id) throws InvalidKeyIdException {byte[] bytes=read("kyber","",0,id);if(bytes==null)throw new InvalidKeyIdException("missing_kyber_prekey");try{return new KyberPreKeyRecord(bytes);}catch(Exception e){throw new SecurityException("invalid_signal_kyber_prekey",e);}}
    @Override public List<KyberPreKeyRecord> loadKyberPreKeys(){List<KyberPreKeyRecord> records=new ArrayList<>();try{for(int id:ids("kyber",""))records.add(loadKyberPreKey(id));}catch(InvalidKeyIdException e){throw new SecurityException(e);}return records;}
    @Override public void storeKyberPreKey(int id,KyberPreKeyRecord record){write("kyber","",0,id,record.serialize());}
    @Override public boolean containsKyberPreKey(int id){return read("kyber","",0,id)!=null;}
    @Override public void markKyberPreKeyUsed(int id,int signedId,ECPublicKey baseKey) throws ReusedBaseKeyException {
        String tuple=signedId+":"+Base64.encodeToString(baseKey.serialize(),Base64.NO_WRAP);
        if(read("kyber-used",tuple,0,id)!=null)throw new ReusedBaseKeyException();
        write("kyber-used",tuple,0,id,new byte[]{1});
    }
    @Override public SessionRecord loadSession(SignalProtocolAddress address){byte[] bytes=read("session",address.getName(),address.getDeviceId(),0);try{return bytes==null?null:new SessionRecord(bytes);}catch(Exception e){throw new SecurityException("invalid_signal_session",e);}}
    @Override public List<SessionRecord> loadExistingSessions(List<SignalProtocolAddress> addresses) throws NoSessionException {List<SessionRecord> records=new ArrayList<>();for(SignalProtocolAddress address:addresses){SessionRecord record=loadSession(address);if(record==null)throw new NoSessionException(address,"missing_signal_session");records.add(record);}return records;}
    @Override public List<Integer> getSubDeviceSessions(String name){synchronized(guard){List<Integer> out=new ArrayList<>();try(Cursor c=helper.getReadableDatabase().rawQuery("SELECT device FROM records WHERE kind='session' AND name=? AND device<>1 ORDER BY device",new String[]{name})){while(c.moveToNext())out.add(c.getInt(0));}return out;}}
    @Override public void storeSession(SignalProtocolAddress address,SessionRecord record){write("session",address.getName(),address.getDeviceId(),0,record.serialize());}
    @Override public boolean containsSession(SignalProtocolAddress address){return read("session",address.getName(),address.getDeviceId(),0)!=null;}
    @Override public void deleteSession(SignalProtocolAddress address){remove("session",address.getName(),address.getDeviceId(),0);}
    @Override public void deleteAllSessions(String name){synchronized(guard){requireTransaction();helper.getWritableDatabase().delete("records","kind='session' AND name=?",new String[]{name});}}
    @Override public void storeSenderKey(SignalProtocolAddress sender,UUID distribution,SenderKeyRecord record){write("sender:"+distribution,sender.getName(),sender.getDeviceId(),0,record.serialize());}
    @Override public SenderKeyRecord loadSenderKey(SignalProtocolAddress sender,UUID distribution){byte[] bytes=read("sender:"+distribution,sender.getName(),sender.getDeviceId(),0);try{return bytes==null?null:new SenderKeyRecord(bytes);}catch(Exception e){throw new SecurityException("invalid_signal_sender_key",e);}}
    @Override public void close(){synchronized(guard){helper.close();}}
}
