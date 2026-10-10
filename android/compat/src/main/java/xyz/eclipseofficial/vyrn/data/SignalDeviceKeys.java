// SPDX-License-Identifier: AGPL-3.0-only
package xyz.eclipseofficial.vyrn.data;

import android.content.Context;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
        this(context,repository,false);
    }
    /** Offline cache access requires an existing encrypted store and previously authenticated device binding. */
    public static SignalDeviceKeys openLocal(Context context,RestRepository repository) throws Exception {return new SignalDeviceKeys(context,repository,true);}
    private SignalDeviceKeys(Context context,RestRepository repository,boolean local) throws Exception {
        this.repository=repository;
        synchronized(repository.vault.guard()){
            JSONObject tokens=repository.vault.load();user=id(tokens.getString("user_id"));session=id(tokens.getString("session_id"));
            if(local){if(!tokens.has("signal_device_id")||!session.equals(tokens.optString("signal_device_session")))throw new SecurityException("signal_local_device_missing");device=id(tokens.getString("signal_device_id"));store=SignalStore.openExisting(context,user,device);}
            else{JSONObject me=(JSONObject)request("GET","/v1/me",null);if(!user.equals(me.getString("id")))throw new SecurityException("signal_owner_changed");device=id(me.getString("device_id"));requireSession();JSONObject current=repository.vault.load();if(current.has("signal_device_id")&&!device.equals(current.getString("signal_device_id")))throw new SecurityException("signal_device_changed");repository.vault.save(current.put("signal_device_id",device).put("signal_device_session",session));store=new SignalStore(context,user,device);}
            try{
                store.transaction(()->{
                    byte[] prior=store.loadValue("keys","authenticated-device");
                    if(prior==null){if(local)throw new SecurityException("signal_local_device_unverified");store.saveValue("keys","authenticated-device",new JSONObject().put("user",user).put("device",device).toString().getBytes(StandardCharsets.UTF_8));}
                    else{JSONObject binding=new JSONObject(new String(prior,StandardCharsets.UTF_8));if(!user.equals(binding.getString("user"))||!device.equals(binding.getString("device")))throw new SecurityException("signal_device_changed");}
                    if(local)acknowledged();return null;
                });
            }catch(Exception failure){store.close();throw failure;}
        }
    }
    Object guard(){return repository.vault.guard();}
    String sessionId(){return session;}
    void requireSession() throws Exception {JSONObject tokens=repository.vault.load();if(!user.equals(tokens.optString("user_id")) || !session.equals(tokens.optString("session_id")))throw new SecurityException("signal_session_changed");}
    Object request(String method,String path,JSONObject body) throws Exception {return repository.requestForSession(user,session,method,path,body);}
    JSONObject acknowledged() throws Exception {byte[] bytes=store.loadValue("keys","acknowledged");if(bytes==null)throw new SecurityException("signal_publication_required");JSONObject value=new JSONObject(new String(bytes,StandardCharsets.UTF_8));id(value.getString("signal_address_id"));if(integer(value,"version")<1)throw new SecurityException("invalid_signal_version");return value;}
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
    /** Replay initial publication only when its durable acknowledgement is missing. Never replace an existing generation. */
    public SignalProtocolAddress ensurePublished() throws Exception {synchronized(guard()){
        requireSession();
        if(store.loadValue("keys","acknowledged")==null)publishInitial();
        JSONObject local=acknowledged();JSONArray roster=devices(user);
        if(roster.length()<1||roster.length()>3)throw new SecurityException("invalid_signal_roster");
        ClaimedKey own=null;
        for(int i=0;i<roster.length();i++){
            JSONObject item=roster.getJSONObject(i);
            if(!device.equals(item.getString("device_id")))continue;
            if(own!=null||item.get("ready")!=Boolean.TRUE)throw new SecurityException("signal_local_publication_changed");
            own=parseClaim(new JSONObject(item.toString()).put("user_id",user).put("prekey",JSONObject.NULL),user,device,integer(item,"version"));
        }
        if(own==null||!own.address.getName().equals(local.getString("signal_address_id"))||own.version!=integer(local,"version")||!own.bundle.getIdentityKey().equals(store.getIdentityKeyPair().getPublicKey())||own.bundle.getRegistrationId()!=store.getLocalRegistrationId())throw new SecurityException("signal_local_publication_changed");
        return own.address;
    }}
    public ClaimedKey claim(String recipient,String recipientDevice,int version) throws Exception {
        id(recipient);id(recipientDevice);if(version<1)throw new IllegalArgumentException("invalid_signal_version");
        JSONObject response=(JSONObject)request("POST","/v1/users/"+recipient+"/device-keys/"+recipientDevice+"/claim",new JSONObject().put("expected_version",version));
        return parseClaim(response,recipient,recipientDevice,version);
    }
    /** Authenticate a received sender using signed directory metadata without consuming its one-time prekeys. */
    public ClaimedKey senderKey(String owner,String senderDevice,String address,int version) throws Exception {synchronized(guard()){
        requireSession();id(owner);id(senderDevice);id(address);if(version<1)throw new SecurityException("invalid_signal_version");
        JSONArray roster=devices(owner);if(roster.length()<1||roster.length()>3)throw new SecurityException("invalid_signal_roster");
        Set<String> seen=new HashSet<>();ClaimedKey found=null;
        for(int i=0;i<roster.length();i++){
            JSONObject item=roster.getJSONObject(i);String device=id(item.getString("device_id"));if(!seen.add(device))throw new SecurityException("invalid_signal_roster");
            if(!senderDevice.equals(device))continue;if(item.get("ready")!=Boolean.TRUE)throw new SecurityException("signal_sender_key_required");
            found=parseClaim(new JSONObject(item.toString()).put("user_id",owner).put("prekey",JSONObject.NULL),owner,device,integer(item,"version"));
        }
        if(found==null||found.version<version||!address.equals(found.address.getName()))throw new SecurityException("signal_sender_changed");validateKnownIdentity(found);return found;
    }}
    /** Validate both complete rosters before consuming any one-time keys. */
    public List<ClaimedKey> verificationDevices(String peer) throws Exception {synchronized(guard()){
        requireSession();id(peer);if(peer.equals(user))throw new IllegalArgumentException("signal_private_peer_required");ensurePublished();JSONArray roster=devices(peer);if(roster.length()<1||roster.length()>3)throw new SecurityException("invalid_signal_roster");List<ClaimedKey> result=new ArrayList<>();Set<String> devices=new HashSet<>(),addresses=new HashSet<>();
        for(int i=0;i<roster.length();i++){JSONObject item=roster.getJSONObject(i);String device=id(item.getString("device_id"));if(!devices.add(device)||item.get("ready")!=Boolean.TRUE)throw new SecurityException("signal_device_not_ready");ClaimedKey key=parseClaim(new JSONObject(item.toString()).put("user_id",peer).put("prekey",JSONObject.NULL),peer,device,integer(item,"version"));if(!addresses.add(key.address.getName()))throw new SecurityException("invalid_signal_roster");validateKnownIdentity(key);result.add(key);}return result;
    }}
    public List<ClaimedKey> targets(String peer) throws Exception {synchronized(guard()){
        requireSession();id(peer);if(peer.equals(user))throw new IllegalArgumentException("signal_private_peer_required");
        JSONObject local=acknowledged();List<ClaimedKey> targets=new ArrayList<>();Set<String> addresses=new HashSet<>();boolean producer=false;
        for(String owner:new String[]{user,peer}){
            JSONArray roster=devices(owner);if(roster.length()<1 || roster.length()>3)throw new SecurityException("invalid_signal_roster");Set<String> devices=new HashSet<>();
            for(int i=0;i<roster.length();i++){
                JSONObject item=roster.getJSONObject(i);String targetDevice=id(item.getString("device_id"));
                if(!devices.add(targetDevice) || item.get("ready")!=Boolean.TRUE)throw new SecurityException("signal_device_not_ready");
                ClaimedKey target=parseClaim(new JSONObject(item.toString()).put("user_id",owner).put("prekey",JSONObject.NULL),owner,targetDevice,integer(item,"version"));
                if(!addresses.add(target.address.getName()))throw new SecurityException("invalid_signal_roster");
                validateKnownIdentity(target);
                if(owner.equals(user) && targetDevice.equals(device)){
                    producer=true;if(!target.address.getName().equals(local.getString("signal_address_id")) || target.version!=integer(local,"version") || !target.bundle.getIdentityKey().equals(store.getIdentityKeyPair().getPublicKey()) || target.bundle.getRegistrationId()!=store.getLocalRegistrationId())throw new SecurityException("signal_local_publication_changed");
                }else targets.add(target);
            }
        }
        if(!producer || targets.isEmpty() || targets.size()>5)throw new SecurityException("invalid_signal_roster");
        for(int i=0;i<targets.size();i++){
            ClaimedKey snapshot=targets.get(i);
            if(!store.containsSession(snapshot.address)){
                ClaimedKey claimed=claim(snapshot.user,snapshot.device,snapshot.version);
                if(!snapshot.address.equals(claimed.address) || !snapshot.bundle.getIdentityKey().equals(claimed.bundle.getIdentityKey()) || snapshot.bundle.getRegistrationId()!=claimed.bundle.getRegistrationId())throw new SecurityException("signal_roster_changed");
                targets.set(i,claimed);
            }
        }
        return targets;
    }}
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
        return new ClaimedKey(recipient,device,address,bundle,version);
    }
    /** Reject a changed pinned identity/address before a new send consumes any one-time keys. */
    void validateKnownIdentity(ClaimedKey key) throws Exception {
        byte[] peer=store.loadValue("private-peer",key.user+"_"+key.device),owner=store.loadValue("private-address",key.address.getName());String identity=Base64.encodeToString(key.bundle.getIdentityKey().serialize(),Base64.NO_WRAP);
        if(peer!=null){JSONObject pinned=new JSONObject(new String(peer,StandardCharsets.UTF_8));if(!key.address.getName().equals(pinned.getString("address"))||!identity.equals(pinned.getString("identity"))||key.version<pinned.getInt("version"))throw new SecurityException("signal_identity_changed");}
        if(owner!=null&&!new JSONObject(new String(owner,StandardCharsets.UTF_8)).getString("owner").equals(key.user+"_"+key.device)||!store.isTrustedIdentity(key.address,key.bundle.getIdentityKey(),org.signal.libsignal.protocol.state.IdentityKeyStore.Direction.SENDING))throw new SecurityException("signal_identity_changed");
    }
    private static int integer(JSONObject object,String field) throws Exception {Object value=object.get(field);if(!(value instanceof Integer) && !(value instanceof Long))throw new SecurityException("invalid_signal_integer");long number=((Number)value).longValue();if(number<0 || number>Integer.MAX_VALUE)throw new SecurityException("invalid_signal_integer");return (int)number;}
    private static int keyId(JSONObject key) throws Exception {int id=integer(key,"id");if(id>16777215)throw new SecurityException("invalid_signal_key_id");return id;}
    private static byte[] bytes(String value,int size,int prefix){if(value.length()!=4*((size+2)/3))throw new SecurityException("invalid_signal_key_encoding");byte[] decoded=Base64.decode(value,Base64.NO_WRAP);if(decoded.length!=size || prefix>=0 && decoded[0]!=(byte)prefix || !Base64.encodeToString(decoded,Base64.NO_WRAP).equals(value))throw new SecurityException("invalid_signal_key_encoding");return decoded;}
    private static String id(String value){if(!UUID.fromString(value).toString().equals(value))throw new IllegalArgumentException("invalid_signal_id");return value;}
    public static final class ClaimedKey {
        public final String user,device;
        public final SignalProtocolAddress address;
        public final PreKeyBundle bundle;
        public final int version;
        private ClaimedKey(String user,String device,SignalProtocolAddress address,PreKeyBundle bundle,int version){this.user=user;this.device=device;this.address=address;this.bundle=bundle;this.version=version;}
    }
    @Override public void close(){store.close();}
}
