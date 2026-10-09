package xyz.eclipseofficial.vyrn.data;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;

/** Only private native draft/reply records use this owner-bound Keystore envelope. */
public final class DraftVault {
    private static final String PREFIX="vyrn-draft-v1:";
    private static final int MAX=512*1024;
    private static final Pattern RECORD=Pattern.compile("(?:r_|t_|rt_)?(-?[0-9]+)(?:_[0-9]+)?");
    private DraftVault(){}
    private static long dialogRecord(String record){
        Matcher match=RECORD.matcher(record);if(!match.matches())throw new SecurityException("invalid_draft_record");
        try{return Long.parseLong(match.group(1));}catch(NumberFormatException invalid){throw new SecurityException("invalid_draft_record");}
    }
    private static boolean privateRecord(String record){return DialogObject.isUserDialog(dialogRecord(record));}
    public static String encode(int account,String record,byte[] bytes){
        if(!privateRecord(record))return Utilities.bytesToHex(bytes);
        return PREFIX+Base64.encodeToString(crypt(account,record,bytes,true),Base64.NO_WRAP);
    }
    public static byte[] decode(int account,String record,String value){
        if(!privateRecord(record)){if(value.startsWith(PREFIX))throw new SecurityException("private_draft_wrong_record");return Utilities.hexToBytes(value);}
        // Old private hex records have no authenticated owner; never infer one from a new login.
        if(!value.startsWith(PREFIX))throw new SecurityException("private_draft_legacy_unbound");
        String encoded=value.substring(PREFIX.length());if(encoded.length()>(MAX+28)*4/3+4)throw new SecurityException("private_draft_too_large");
        byte[] bytes;try{bytes=Base64.decode(encoded,Base64.NO_WRAP);}catch(IllegalArgumentException invalid){throw new SecurityException("private_draft_unavailable");}
        if(!encoded.equals(Base64.encodeToString(bytes,Base64.NO_WRAP)))throw new SecurityException("private_draft_unavailable");
        return crypt(account,record,bytes,false);
    }
    private static byte[] crypt(int account,String record,byte[] bytes,boolean encrypt){
        if(account<0||account>3||bytes==null||bytes.length>(encrypt?MAX:MAX+28)||!encrypt&&bytes.length<28)throw new SecurityException("private_draft_unavailable");
        Context context=ApplicationLoader.applicationContext;TokenVault vault=new TokenVault(context,account);
        synchronized(vault.guard()){try{
            String user=vault.load().getString("user_id");if(!UUID.fromString(user).toString().equals(user))throw new SecurityException();
            long owner=UserConfig.getInstance(account).getClientUserId(),dialog=dialogRecord(record);String peer;try(IdMap ids=new IdMap(context,account)){if(owner==0||!user.equals(ids.remote("user",owner)))throw new SecurityException();peer=ids.remote("user",dialog);if(!UUID.fromString(peer).toString().equals(peer))throw new SecurityException();}
            String alias="vyrn.draft."+account+"."+user;KeyStore keys=KeyStore.getInstance("AndroidKeyStore");keys.load(null);
            if(!keys.containsAlias(alias)){
                if(!encrypt)throw new SecurityException();
                KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");generator.init(new KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build());generator.generateKey();
            }
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");SecretKey key=(SecretKey)keys.getKey(alias,null);
            if(encrypt)cipher.init(Cipher.ENCRYPT_MODE,key);else cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,bytes,0,12));
            cipher.updateAAD(("vyrn:draft:v1:"+account+":"+user+":"+owner+":"+peer+":"+record).getBytes(StandardCharsets.UTF_8));
            byte[] result=encrypt?cipher.doFinal(bytes):cipher.doFinal(bytes,12,bytes.length-12);
            if(owner!=UserConfig.getInstance(account).getClientUserId()||!vault.isUser(user))throw new SecurityException();
            try(IdMap ids=new IdMap(context,account)){if(!user.equals(ids.remote("user",owner))||!peer.equals(ids.remote("user",dialog)))throw new SecurityException();}
            if(!encrypt)return result;byte[] envelope=new byte[12+result.length];System.arraycopy(cipher.getIV(),0,envelope,0,12);System.arraycopy(result,0,envelope,12,result.length);return envelope;
        }catch(Exception unavailable){throw new SecurityException("private_draft_unavailable");}}
    }
}
