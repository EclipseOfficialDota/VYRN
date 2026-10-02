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
    private final SharedPreferences preferences;
    private final String alias;
    public Object guard(){return LOCKS.computeIfAbsent(alias,ignored->new Object());}
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
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());
        byte[] encrypted=cipher.doFinal(tokens.toString().getBytes(StandardCharsets.UTF_8));
        if(!preferences.edit().putString("iv",Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP)).putString("tokens",Base64.encodeToString(encrypted,Base64.NO_WRAP)).commit())throw new IllegalStateException("session_storage_failed");
        USERS.put(alias,tokens.optString("user_id"));
    }}
    public JSONObject load() throws Exception { synchronized(guard()) {
        String value=preferences.getString("tokens",null);if(value==null){USERS.remove(alias);return new JSONObject();}
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(preferences.getString("iv",""),Base64.NO_WRAP)));
        JSONObject tokens=new JSONObject(new String(cipher.doFinal(Base64.decode(value,Base64.NO_WRAP)),StandardCharsets.UTF_8));USERS.put(alias,tokens.optString("user_id"));return tokens;
    }}
    public String device(){synchronized(guard()){String id=preferences.getString("device",null);if(id==null){id=UUID.randomUUID().toString();if(!preferences.edit().putString("device",id).commit())throw new IllegalStateException("device_storage_failed");}return id;}}
    public void clear(){synchronized(guard()){if(!preferences.edit().remove("tokens").remove("iv").commit())throw new IllegalStateException("session_storage_failed");USERS.remove(alias);}}
}
