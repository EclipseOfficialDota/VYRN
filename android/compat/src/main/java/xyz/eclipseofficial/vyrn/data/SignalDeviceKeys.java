// SPDX-License-Identifier: AGPL-3.0-only
package xyz.eclipseofficial.vyrn.data;

import android.content.Context;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.signal.libsignal.protocol.IdentityKey;
import org.signal.libsignal.protocol.SignalProtocolAddress;
import org.signal.libsignal.protocol.ecc.ECPublicKey;
import org.signal.libsignal.protocol.kem.KEMPublicKey;
import org.signal.libsignal.protocol.state.PreKeyBundle;

/** Session-bound public-key networking. Does not enable the unfinished private-chat UI. */
public final class SignalDeviceKeys implements AutoCloseable {
    public final String user,device;
    public final SignalStore store;
    private final String session;
    private final RestRepository repository;
    public SignalDeviceKeys(Context context,RestRepository repository) throws Exception {
        this.repository=repository;
        synchronized(repository.vault.guard()){
            JSONObject tokens=repository.vault.load();user=id(tokens.getString("user_id"));session=id(tokens.getString("session_id"));
            JSONObject me=(JSONObject)request("GET","/v1/me",null);
            if(!user.equals(me.getString("id")))throw new SecurityException("signal_owner_changed");
            device=id(me.getString("device_id"));store=new SignalStore(context,user,device);
        }
    }
    private Object request(String method,String path,JSONObject body) throws Exception {return repository.requestForSession(user,session,method,path,body);}
    public SignalProtocolAddress publishInitial() throws Exception {synchronized(repository.vault.guard()){
        JSONObject acknowledgement=(JSONObject)request("PUT","/v1/me/device-keys",store.initialPublication());
        String address=id(acknowledgement.getString("signal_address_id"));
        if(!device.equals(acknowledgement.getString("device_id")) || integer(acknowledgement,"version")!=1)throw new SecurityException("signal_publication_mismatch");
        store.transaction(()->{
            byte[] prior=store.loadValue("keys","acknowledged");
            if(prior!=null && !address.equals(new JSONObject(new String(prior,StandardCharsets.UTF_8)).getString("signal_address_id")))throw new SecurityException("signal_address_changed");
            store.saveValue("keys","acknowledged",new JSONObject().put("signal_address_id",address).put("version",1).toString().getBytes(StandardCharsets.UTF_8));return null;
        });
        return new SignalProtocolAddress(address,1);
    }}
    public JSONArray devices(String recipient) throws Exception {
        JSONObject response=(JSONObject)request("GET","/v1/users/"+id(recipient)+"/device-keys",null);
        if(!recipient.equals(response.getString("user_id")))throw new SecurityException("signal_roster_mismatch");
        return response.getJSONArray("devices");
    }
    public ClaimedKey claim(String recipient,String recipientDevice,int version) throws Exception {
        id(recipient);id(recipientDevice);if(version<1)throw new IllegalArgumentException("invalid_signal_version");
        JSONObject response=(JSONObject)request("POST","/v1/users/"+recipient+"/device-keys/"+recipientDevice+"/claim",new JSONObject().put("expected_version",version));
        return parseClaim(response,recipient,recipientDevice,version);
    }
    public static ClaimedKey parseClaim(JSONObject response,String recipient,String device,int version) throws Exception {
        id(recipient);id(device);
        if(version<1 || !recipient.equals(response.getString("user_id")) || !device.equals(response.getString("device_id")) || integer(response,"version")!=version)throw new SecurityException("signal_claim_mismatch");
        SignalProtocolAddress address=new SignalProtocolAddress(id(response.getString("signal_address_id")),1);
        int registration=integer(response,"registration_id");if(registration<1 || registration>16380)throw new SecurityException("invalid_signal_registration");
        IdentityKey identity=new IdentityKey(bytes(response.getString("identity_key"),33,5));
        JSONObject signed=response.getJSONObject("signed_prekey"),kyber=response.getJSONObject("kyber_prekey");
        byte[] signedBytes=bytes(signed.getString("public_key"),33,5),kyberBytes=bytes(kyber.getString("public_key"),1569,8);
        byte[] signedSignature=bytes(signed.getString("signature"),64,-1),kyberSignature=bytes(kyber.getString("signature"),64,-1);
        if(!identity.getPublicKey().verifySignature(signedBytes,signedSignature) || !identity.getPublicKey().verifySignature(kyberBytes,kyberSignature))throw new SecurityException("invalid_signal_signature");
        if(!response.has("prekey"))throw new SecurityException("signal_prekey_missing");
        JSONObject ec=response.isNull("prekey")?null:response.getJSONObject("prekey");
        PreKeyBundle bundle=new PreKeyBundle(registration,1,ec==null?PreKeyBundle.NULL_PRE_KEY_ID:keyId(ec),ec==null?null:new ECPublicKey(bytes(ec.getString("public_key"),33,5),0),keyId(signed),new ECPublicKey(signedBytes,0),signedSignature,identity,keyId(kyber),new KEMPublicKey(kyberBytes),kyberSignature);
        return new ClaimedKey(address,bundle,version);
    }
    private static int integer(JSONObject object,String field) throws Exception {Object value=object.get(field);if(!(value instanceof Integer) && !(value instanceof Long))throw new SecurityException("invalid_signal_integer");long number=((Number)value).longValue();if(number<0 || number>Integer.MAX_VALUE)throw new SecurityException("invalid_signal_integer");return (int)number;}
    private static int keyId(JSONObject key) throws Exception {int id=integer(key,"id");if(id>16777215)throw new SecurityException("invalid_signal_key_id");return id;}
    private static byte[] bytes(String value,int size,int prefix){byte[] decoded=Base64.decode(value,Base64.NO_WRAP);if(decoded.length!=size || prefix>=0 && decoded[0]!=(byte)prefix || !Base64.encodeToString(decoded,Base64.NO_WRAP).equals(value))throw new SecurityException("invalid_signal_key_encoding");return decoded;}
    private static String id(String value){if(!UUID.fromString(value).toString().equals(value))throw new IllegalArgumentException("invalid_signal_id");return value;}
    public static final class ClaimedKey {
        public final SignalProtocolAddress address;
        public final PreKeyBundle bundle;
        public final int version;
        private ClaimedKey(SignalProtocolAddress address,PreKeyBundle bundle,int version){this.address=address;this.bundle=bundle;this.version=version;}
    }
    @Override public void close(){store.close();}
}
