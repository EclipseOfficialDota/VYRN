package xyz.eclipseofficial.vyrn.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

public final class TokenVault {
    private static final ConcurrentHashMap<String,Object> LOCKS=new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String,String> USERS=new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String,String> SESSIONS=new ConcurrentHashMap<>();
    private final SharedPreferences preferences;
    private final String alias;
    public Object guard(){return LOCKS.computeIfAbsent(alias,ignored->new Object());}
    public String cachedSession(){return SESSIONS.get(alias);}
    public boolean hasPendingRecovery(){return preferences.getBoolean("recovery_pending",false);}
    /** A nonblocking identity snapshot for UI/cache callbacks; authentication still uses load under guard. */
    public boolean isUser(String user){return user!=null && !user.isEmpty() && user.equals(USERS.get(alias));}
    public TokenVault(Context context,int account){preferences=context.getSharedPreferences("vyrn_session_"+account,Context.MODE_PRIVATE);alias="vyrn.tokens."+account;}
    private SecretKey key() throws Exception {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        if(!store.containsAlias(alias)){
            KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build());
            generator.generateKey();
        }
        return (SecretKey)store.getKey(alias,null);
    }
    public void save(JSONObject tokens) throws Exception { synchronized(guard()) {
        JSONObject stored=new JSONObject(tokens.toString());
        if(!stored.has("signal_device_id")&&isUser(stored.optString("user_id"))&&stored.optString("session_id").equals(cachedSession())){
            JSONObject previous=load();
            if(!stored.optString("user_id").isEmpty()&&!stored.optString("session_id").isEmpty()&&stored.optString("user_id").equals(previous.optString("user_id"))&&stored.optString("session_id").equals(previous.optString("session_id"))&&previous.has("signal_device_id")&&stored.optString("session_id").equals(previous.optString("signal_device_session")))stored.put("signal_device_id",previous.getString("signal_device_id")).put("signal_device_session",previous.getString("signal_device_session"));
        }
        if(!stored.has("recovery_pending")&&hasPendingRecovery()){
            JSONObject previous=load();
            if(!stored.optString("user_id").isEmpty()&&!stored.optString("session_id").isEmpty()&&stored.optString("user_id").equals(previous.optString("user_id"))&&stored.optString("session_id").equals(previous.optString("session_id"))&&previous.optBoolean("recovery_pending")){
                stored.put("recovery_pending",true).put("recovery_code",previous.getString("recovery_code"));
            }
        }
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());
        byte[] encrypted=cipher.doFinal(stored.toString().getBytes(StandardCharsets.UTF_8));
        if(!preferences.edit().putString("iv",Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP)).putString("tokens",Base64.encodeToString(encrypted,Base64.NO_WRAP)).putBoolean("recovery_pending",stored.optBoolean("recovery_pending")).commit())throw new IllegalStateException("session_storage_failed");
        USERS.put(alias,stored.optString("user_id"));
        SESSIONS.put(alias,stored.optString("session_id"));
    }}
    public void acknowledgeRecovery(String user,String session,String code) throws Exception {synchronized(guard()){
        JSONObject tokens=load();
        if(user==null||user.isEmpty()||session==null||session.isEmpty()||code==null||code.isEmpty()||!user.equals(tokens.optString("user_id"))||!session.equals(tokens.optString("session_id"))||!tokens.optBoolean("recovery_pending")||!code.equals(tokens.optString("recovery_code")))throw new SecurityException("recovery_session_changed");
        tokens.remove("recovery_code");tokens.put("recovery_pending",false);save(tokens);
    }}
    public JSONObject load() throws Exception { synchronized(guard()) {
        String value=preferences.getString("tokens",null);if(value==null){USERS.remove(alias);SESSIONS.remove(alias);return new JSONObject();}
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(preferences.getString("iv",""),Base64.NO_WRAP)));
        JSONObject tokens=new JSONObject(new String(cipher.doFinal(Base64.decode(value,Base64.NO_WRAP)),StandardCharsets.UTF_8));USERS.put(alias,tokens.optString("user_id"));SESSIONS.put(alias,tokens.optString("session_id"));return tokens;
    }}
    public String device(){synchronized(guard()){String id=preferences.getString("device",null);if(id==null){id=UUID.randomUUID().toString();if(!preferences.edit().putString("device",id).commit())throw new IllegalStateException("device_storage_failed");}return id;}}
    public void clear(){synchronized(guard()){if(!preferences.edit().remove("tokens").remove("iv").remove("recovery_pending").commit())throw new IllegalStateException("session_storage_failed");USERS.remove(alias);SESSIONS.remove(alias);}}
}
