// SPDX-License-Identifier: AGPL-3.0-only
package xyz.eclipseofficial.vyrn.data;

import android.util.Base64;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.signal.libsignal.protocol.*;
import org.signal.libsignal.protocol.message.*;

/** Encrypted text transport/cache and native recovery journal; composer/trust/device lifecycle remain gated. */
public final class SignalMessages {
    private final SignalDeviceKeys keys;
    private final SignalStore store;
    public SignalMessages(SignalDeviceKeys keys){this.keys=keys;store=keys.store;}

    /** Current signed ready-device list, pinned atomically without consuming prekeys or creating sessions. */
    public JSONArray verificationDevices(String peer) throws Exception {synchronized(keys.guard()){
        keys.requireSession();List<SignalDeviceKeys.ClaimedKey> devices=keys.verificationDevices(peer);return store.transaction(()->{JSONArray result=new JSONArray();for(SignalDeviceKeys.ClaimedKey device:devices){bind(device);store.saveIdentity(device.address,device.bundle.getIdentityKey());result.put(verification(peer,device.device));}return result;});
    }}
    public void verifyCurrentPeer(String peer,String device,String expected,boolean verified) throws Exception {synchronized(keys.guard()){
        keys.requireSession();JSONArray current=verificationDevices(peer);boolean active=false;for(int i=0;i<current.length();i++)active|=device.equals(current.getJSONObject(i).getString("device"));if(!active)throw new SecurityException("signal_device_not_ready");verifyPeer(peer,device,expected,verified);
    }}

    /** Offline device-pair safety number from pinned identities; unknown devices are never marked verified. */
    public JSONObject verification(String peer,String device) throws Exception {synchronized(keys.guard()){
        keys.requireSession();id(peer);id(device);if(peer.equals(keys.user))throw new SecurityException("signal_private_peer_required");JSONObject pinned=load("private-peer",peer+"_"+device);if(pinned==null)throw new SecurityException("signal_sender_key_required");
        String address=id(pinned.getString("address"));JSONObject owner=load("private-address",address);IdentityKey remote=new IdentityKey(Base64.decode(pinned.getString("identity"),Base64.NO_WRAP));IdentityKey saved=store.getIdentity(new SignalProtocolAddress(address,1));
        if(owner==null||!owner.getString("owner").equals(peer+"_"+device)||saved==null||!remote.equals(saved))throw new SecurityException("signal_identity_changed");
        byte[] localId=new JSONArray().put(keys.user).put(keys.device).toString().getBytes(StandardCharsets.UTF_8),remoteId=new JSONArray().put(peer).put(device).toString().getBytes(StandardCharsets.UTF_8);
        String fingerprint=new org.signal.libsignal.protocol.fingerprint.NumericFingerprintGenerator(5200).createFor(2,localId,store.getIdentityKeyPair().getPublicKey(),remoteId,remote).getDisplayableFingerprint().getDisplayText();
        return new JSONObject().put("peer",peer).put("device",device).put("local_device",keys.device).put("address",address).put("fingerprint",fingerprint).put("verified",pinned.optBoolean("verified"));
    }}
    /** Confirm only the currently displayed exact device-pair number; never replace an identity to make it match. */
    public void verifyPeer(String peer,String device,String expected,boolean verified) throws Exception {synchronized(keys.guard()){
        keys.requireSession();if(expected==null||!expected.matches("[0-9]{60}"))throw new SecurityException("signal_verification_changed");store.transaction(()->{JSONObject current=verification(peer,device);if(!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),current.getString("fingerprint").getBytes(StandardCharsets.US_ASCII)))throw new SecurityException("signal_verification_changed");JSONObject pinned=load("private-peer",peer+"_"+device);pinned.put("verified",verified);save("private-peer",peer+"_"+device,pinned);return null;});
    }}

    /** Existing ciphertext retries stay offline; only new sends acquire the complete current roster. */
    public String prepare(String peer,String conversation,String client,String text) throws Exception {synchronized(keys.guard()){
        keys.requireSession();id(peer);id(conversation);id(client);text(text);if(peer.equals(keys.user))throw new IllegalArgumentException("signal_private_peer_required");JSONObject prior=load("private-outgoing",client);if(store.loadValue("private-send-fence",client)!=null || prior!=null&&!prior.isNull("body"))return prepare(peer,conversation,client,text,null);requireIntent(prior,peer,conversation,text);if(prior!=null&&prior.optBoolean("native_cancel_requested"))throw new SecurityException("signal_cancel_requested");
        return prepare(peer,conversation,client,text,keys.targets(peer));
    }}

    /** Caller obtains the complete verified ready-device snapshot before entering this transaction. */
    public String prepare(String peer,String conversation,String client,String text,List<SignalDeviceKeys.ClaimedKey> targets) throws Exception {synchronized(keys.guard()){
        return prepare(peer,conversation,client,text,targets,null);
    }}

    /** Bind the original native row in the same encrypted transaction as ciphertext and ratchet state. */
    public String prepareNative(String peer,String conversation,String client,String text,int account,int localId,long dialogId,long randomId) throws Exception {synchronized(keys.guard()){
        keys.requireSession();id(peer);id(conversation);id(client);text(text);
        if(peer.equals(keys.user))throw new IllegalArgumentException("signal_private_peer_required");
        JSONArray identity=nativeIdentity(client,account,localId,dialogId,randomId);
        if(store.loadValue("private-send-fence",client)!=null)throw new SecurityException("signal_message_deleted");
        JSONObject prior=load("private-outgoing",client);
        requireIntent(prior,peer,conversation,text);
        if(prior!=null&&prior.has("native_ui")&&!identity.toString().equals(prior.getJSONArray("native_ui").toString()))throw new SecurityException("signal_native_identity_changed");
        if(prior!=null&&prior.isNull("body")&&prior.optBoolean("native_cancel_requested"))throw new SecurityException("signal_cancel_requested");
        return prepare(peer,conversation,client,text,(prior==null||prior.isNull("body"))&&store.loadValue("private-send-fence",client)==null?keys.targets(peer):null,identity);
    }}

    private JSONArray nativeIdentity(String client,int account,int localId,long dialogId,long randomId){
        return nativeIdentity(keys.user,keys.sessionId(),client,account,localId,dialogId,randomId);
    }
    private static JSONArray nativeIdentity(String user,String session,String client,int account,int localId,long dialogId,long randomId){
        if(account<0||account>3||localId>=0||dialogId<=0||dialogId>Integer.MAX_VALUE||randomId==0||!client.equals(UUID.nameUUIDFromBytes((user+":"+randomId).getBytes(StandardCharsets.UTF_8)).toString()))throw new IllegalArgumentException("invalid_signal_native_identity");return new JSONArray().put(account).put(localId).put(dialogId).put(randomId).put(session);
    }
    private static void requireIntent(JSONObject value,String peer,String conversation,String text) throws Exception {
        if(value!=null&&(!peer.equals(value.getString("peer"))||!Objects.equals(conversation,value.isNull("conversation")?null:value.getString("conversation"))||!text.equals(value.getString("text"))))throw new SecurityException("signal_client_conflict");
    }
    /** Persist a new offline intent in the existing encrypted journal; preparation is deferred to the worker. */
    public JSONObject stageNative(String peer,String conversation,String client,String text,int account,int localId,long dialogId,long randomId) throws Exception {synchronized(keys.guard()){
        keys.requireSession();id(peer);if(conversation!=null)id(conversation);id(client);text(text);if(peer.equals(keys.user))throw new IllegalArgumentException("signal_private_peer_required");JSONArray identity=nativeIdentity(client,account,localId,dialogId,randomId);keys.acknowledged();
        return store.transaction(()->{stageRecord(store,peer,conversation,client,text,identity);return nativeBinding(account,client);});
    }}
    private static JSONObject stageRecord(SignalStore journal,String peer,String conversation,String client,String text,JSONArray identity) throws Exception {
        if(journal.loadValue("private-send-fence",client)!=null)throw new SecurityException("signal_message_deleted");byte[] bytes=journal.loadValue("private-outgoing",client);JSONObject value=bytes==null?null:parse(bytes);
        if(value==null)value=new JSONObject().put("peer",peer).put("conversation",conversation==null?JSONObject.NULL:conversation).put("text",text).put("body",JSONObject.NULL).put("message_id",JSONObject.NULL);else requireIntent(value,peer,conversation,text);
        if(value.has("native_ui")&&!identity.toString().equals(value.getJSONArray("native_ui").toString()))throw new SecurityException("signal_native_identity_changed");value.put("native_ui",identity);if(!value.has("native_date"))value.put("native_date",(int)(System.currentTimeMillis()/1000));if(!value.optBoolean("native_ui_done"))value.put("native_scheduled",true);journal.saveValue("private-outgoing",client,value.toString().getBytes(StandardCharsets.UTF_8));return value;
    }
    /** Local composer persistence cannot queue behind the vault lock held by HTTP. Never creates an identity. */
    public static JSONObject stageNative(android.content.Context context,RestRepository repository,String user,String session,String device,String peer,String client,String text,int account,int local,long dialog,long random) throws Exception {
        id(user);id(session);id(device);id(peer);id(client);text(text);if(user.equals(peer))throw new SecurityException("signal_private_peer_required");JSONArray identity=nativeIdentity(user,session,client,account,local,dialog,random);
        try(SignalStore journal=SignalStore.openExisting(context,user,device)){return journal.transaction(()->{
            if(!device.equals(repository.vault.cachedSignalDevice(user,session)))throw new SecurityException("signal_session_changed");byte[] proof=journal.loadValue("keys","authenticated-device"),ack=journal.loadValue("keys","acknowledged");if(proof==null||ack==null)throw new SecurityException("signal_publication_required");JSONObject authenticated=parse(proof),published=parse(ack);if(!user.equals(authenticated.getString("user"))||!device.equals(authenticated.getString("device")))throw new SecurityException("signal_device_changed");id(published.getString("signal_address_id"));if(published.getInt("version")<1)throw new SecurityException("invalid_signal_version");
            JSONObject value=stageRecord(journal,peer,null,client,text,identity);if(!value.isNull("message_id")||!device.equals(repository.vault.cachedSignalDevice(user,session)))throw new SecurityException("signal_session_changed");return nativeMetadata(value,client,device,0);
        });}
    }

    /** Recovery returns metadata only; private text remains in the encrypted outgoing record. */
    public JSONArray nativePending(int account) throws Exception {synchronized(keys.guard()){
        keys.requireSession();if(account<0||account>3)throw new IllegalArgumentException("invalid_signal_native_account");JSONArray pending=new JSONArray();
        for(String client:store.valueNames("private-outgoing")){
            JSONObject value=load("private-outgoing",client);if(value==null||!value.has("native_ui"))continue;JSONArray identity=value.getJSONArray("native_ui");
            if(identity.length()!=5)throw new SecurityException("invalid_signal_native_identity");
            if(identity.getInt(0)!=account||!keys.sessionId().equals(identity.getString(4))||value.optBoolean("native_ui_done"))continue;
            pending.put(nativeBinding(account,client));
        }return pending;
    }}
    public JSONObject nativeBinding(int account,String client) throws Exception {synchronized(keys.guard()){
        keys.requireSession();id(client);if(account<0||account>3)throw new SecurityException("signal_native_identity_changed");
        JSONObject value=load("private-outgoing",client);if(value==null||!value.has("native_ui"))throw new SecurityException("signal_native_identity_missing");JSONArray identity=value.getJSONArray("native_ui");
        if(identity.length()!=5||identity.getInt(0)!=account||!keys.sessionId().equals(identity.getString(4)))throw new SecurityException("signal_native_identity_changed");
        return nativeMetadata(value,client,keys.device,nativeOrdinal(client,value));
    }}
    private static JSONObject nativeMetadata(JSONObject value,String client,String device,int ordinal) throws Exception {
        JSONArray identity=value.getJSONArray("native_ui");return new JSONObject().put("client_id",client).put("peer",value.getString("peer")).put("conversation",value.get("conversation")).put("local_id",identity.getInt(1)).put("dialog_id",identity.getLong(2)).put("random_id",identity.getLong(3)).put("device",device).put("ordinal",ordinal).put("message_id",value.get("message_id")).put("date",value.optInt("native_date")).put("ui_done",value.optBoolean("native_ui_done")).put("scheduled",value.optBoolean("native_scheduled")).put("attempts",value.optInt("native_attempts")).put("next_at",value.optLong("native_next_at")).put("error",value.optInt("native_error")).put("failed",value.optBoolean("native_failed")).put("cancel_requested",value.optBoolean("native_cancel_requested")).put("cancelled",value.optBoolean("native_cancelled"));
    }
    /** Cancellation intent must not wait for the HTTP-held TokenVault lock. Never opens or creates an identity. */
    private int nativeOrdinal(String client,JSONObject value) throws Exception {
        if(value.isNull("message_id"))return 0;JSONObject head=load("private-head",value.getString("message_id"));if(head==null)return 0;
        if(!client.equals(head.getString("client"))||!keys.user.equals(head.getString("owner"))||!value.getString("conversation").equals(head.getString("conversation")))throw new SecurityException("signal_native_identity_changed");return head.getInt("ordinal");
    }
    public static boolean requestNativeCancellation(android.content.Context context,RestRepository repository,String user,String session,String device,int account,int local,long dialog,long random,int date,String peer) throws Exception {
        id(user);id(session);id(device);id(peer);if(account<0||account>3||local>=0||dialog<=0||random==0||date<=0)throw new SecurityException("signal_native_identity_changed");String client=UUID.nameUUIDFromBytes((user+":"+random).getBytes(StandardCharsets.UTF_8)).toString();
        try(SignalStore journal=SignalStore.openExisting(context,user,device)){return journal.transaction(()->{
            if(!repository.vault.isUser(user)||!session.equals(repository.vault.cachedSession()))throw new SecurityException("signal_session_changed");
            byte[] proof=journal.loadValue("keys","authenticated-device"),bytes=journal.loadValue("private-outgoing",client);if(proof==null||bytes==null||journal.loadValue("keys","acknowledged")==null)throw new SecurityException("signal_native_identity_missing");JSONObject authenticated=parse(proof),value=parse(bytes);
            if(!user.equals(authenticated.getString("user"))||!device.equals(authenticated.getString("device"))||!peer.equals(value.getString("peer")))throw new SecurityException("signal_native_identity_changed");JSONArray identity=value.getJSONArray("native_ui");
            if(identity.length()!=5||identity.getInt(0)!=account||identity.getInt(1)!=local||identity.getLong(2)!=dialog||identity.getLong(3)!=random||!session.equals(identity.getString(4))||date!=value.getInt("native_date"))throw new SecurityException("signal_native_identity_changed");
            if(value.optBoolean("native_ui_done"))return false;if(value.optBoolean("native_cancel_requested")||value.optBoolean("native_cancelled"))return true;if(journal.loadValue("private-send-fence",client)!=null)throw new SecurityException("signal_message_deleted");
            value.remove("native_attempt");value.put("native_cancel_requested",true).put("native_scheduled",true).put("native_failed",false).put("native_attempts",0).put("native_next_at",0).put("native_error",0);journal.saveValue("private-outgoing",client,value.toString().getBytes(StandardCharsets.UTF_8));return true;
        });}
    }
    /** The server arbitrates against the original client ID; a committed message is retained, never deleted. */
    public boolean resolveNativeCancellation(int account,String client) throws Exception {synchronized(keys.guard()){
        keys.requireSession();JSONObject binding=nativeBinding(account,client);if(binding.getBoolean("cancelled"))return true;if(!binding.getBoolean("cancel_requested")||binding.getBoolean("ui_done"))throw new SecurityException("signal_cancel_not_requested");
        if(binding.isNull("conversation")){resolveNativeConversation(client,load("private-outgoing",client));binding=nativeBinding(account,client);}
        JSONObject response=(JSONObject)keys.request("PUT","/v1/conversations/"+binding.getString("conversation")+"/sends/"+client+"/cancel",null);
        if("committed".equals(response.optString("status"))){String message=id(response.getString("message_id"));JSONObject packet=(JSONObject)keys.request("GET","/v1/messages/"+message,null);
            if(!client.equals(packet.getString("client_id"))||!keys.user.equals(packet.getString("sender_id"))||!binding.getString("conversation").equals(packet.getString("conversation_id"))||!packet.getBoolean("encrypted"))throw new SecurityException("signal_cancel_ack_changed");
            store.transaction(()->{JSONObject value=load("private-outgoing",client);if(value==null||!value.optBoolean("native_cancel_requested")||!value.isNull("message_id")&&!message.equals(value.getString("message_id")))throw new SecurityException("signal_cancel_ack_changed");value.put("message_id",message);save("private-outgoing",client,value);save("private-own-message",message,new JSONObject().put("client_id",client));return null;});receive(binding.getString("peer"),binding.getString("conversation"),packet);return nativeBinding(account,client).getBoolean("cancelled");
        }
        if(!"cancelled".equals(response.optString("status")))throw new SecurityException("signal_cancel_ack_changed");
        store.transaction(()->{JSONObject value=load("private-outgoing",client);if(value==null||!value.optBoolean("native_cancel_requested")||!value.isNull("message_id"))throw new SecurityException("signal_cancel_ack_changed");value.remove("text");value.remove("body");value.remove("native_attempt");value.put("native_cancelled",true).put("native_failed",false).put("native_error",0).put("native_next_at",0);save("private-outgoing",client,value);store.removeValue("private-own",client);store.saveValue("private-send-fence",client,new byte[]{1});return null;});return true;
    }}
    public void acknowledgeNativeCancellation(int account,String client,int local,long dialog,long random) throws Exception {synchronized(keys.guard()){
        keys.requireSession();store.transaction(()->{JSONObject binding=nativeBinding(account,client);if(!binding.getBoolean("cancelled")||binding.getInt("local_id")!=local||binding.getLong("dialog_id")!=dialog||binding.getLong("random_id")!=random||store.loadValue("private-send-fence",client)==null)throw new SecurityException("signal_cancel_ack_changed");JSONObject value=load("private-outgoing",client);value.put("native_ui_done",true).put("native_cancel_requested",false);save("private-outgoing",client,value);return null;});
    }}
    /** Scheduling intent belongs to the original encrypted record, never the group plaintext outbox. */
    public void scheduleNative(int account,String client) throws Exception {synchronized(keys.guard()){
        keys.requireSession();store.transaction(()->{JSONObject binding=nativeBinding(account,client);if(binding.getInt("date")<=0||store.loadValue("private-send-fence",client)!=null)throw new SecurityException("signal_native_identity_changed");if(!binding.getBoolean("ui_done")){JSONObject value=load("private-outgoing",client);value.put("native_scheduled",true);save("private-outgoing",client,value);}return null;});
    }}
    /** Persist the recovery lease before HTTP; an interrupted attempt keeps its exact ciphertext. */
    public String claimNative(int account,String client) throws Exception {synchronized(keys.guard()){
        keys.requireSession();return store.transaction(()->{JSONObject binding=nativeBinding(account,client);long now=System.currentTimeMillis();
            if(binding.getBoolean("cancelled"))return null;if(store.loadValue("private-send-fence",client)!=null||binding.getInt("date")<=0)throw new SecurityException("signal_native_identity_changed");
            if(!binding.getBoolean("scheduled")||binding.getBoolean("ui_done")||binding.getBoolean("failed"))return null;
            long next=binding.getLong("next_at");JSONObject value=load("private-outgoing",client);
            // Wall-clock rollback cannot strand a lease indefinitely; rebase without sending early.
            if(next>now+60000){value.put("native_next_at",now+60000);save("private-outgoing",client,value);return null;}if(next>now)return null;
            String attempt=UUID.randomUUID().toString();value.put("native_attempt",attempt).put("native_attempts",Math.min(Integer.MAX_VALUE-1,binding.getInt("attempts"))+1).put("native_next_at",now+60000).put("native_error",0);save("private-outgoing",client,value);return attempt;
        });
    }}
    /** Store only a bounded numeric status; exception text may contain sensitive transport data. */
    public void failNative(int account,String client,String attempt,Exception failure) throws Exception {synchronized(keys.guard()){
        keys.requireSession();id(attempt);store.transaction(()->{JSONObject binding=nativeBinding(account,client),value=load("private-outgoing",client);
            if(binding.getBoolean("ui_done")||!attempt.equals(value.optString("native_attempt"))||store.loadValue("private-send-fence",client)!=null)throw new SecurityException("signal_native_attempt_changed");
            int status=failure instanceof RestRepository.ApiException?((RestRepository.ApiException)failure).status:failure instanceof java.io.IOException?503:0;
            boolean transientFailure=failure instanceof java.io.IOException||failure instanceof RestRepository.ApiException&&(status==429||status>=500);
            boolean retry=transientFailure&&binding.getInt("attempts")<8;long delay=Math.min(60000L,1000L<<Math.min(Math.max(0,binding.getInt("attempts")-1),6));
            value.remove("native_attempt");value.put("native_error",status).put("native_failed",!retry).put("native_next_at",retry?System.currentTimeMillis()+delay:0);save("private-outgoing",client,value);return null;
        });
    }}
    /** Explicit manual retry revives only failed work; scheduling alone never resets its budget. */
    public boolean retryNative(int account,String client) throws Exception {synchronized(keys.guard()){
        keys.requireSession();return store.transaction(()->{JSONObject binding=nativeBinding(account,client);if(store.loadValue("private-send-fence",client)!=null||binding.getInt("date")<=0)throw new SecurityException("signal_native_identity_changed");if(binding.getBoolean("ui_done")||!binding.getBoolean("failed"))return false;
            JSONObject value=load("private-outgoing",client);value.remove("native_attempt");value.put("native_scheduled",true).put("native_failed",false).put("native_attempts",0).put("native_error",0).put("native_next_at",0);save("private-outgoing",client,value);return true;
        });
    }}
    /** Only the adapter calls this after native cache/UI reconciliation is proved. */
    public void acknowledgeNative(int account,String client,int localId,long dialog,long random,String message) throws Exception {synchronized(keys.guard()){
        keys.requireSession();id(message);store.transaction(()->{JSONObject binding=nativeBinding(account,client);if(binding.getInt("local_id")!=localId||binding.getLong("dialog_id")!=dialog||binding.getLong("random_id")!=random||!message.equals(binding.optString("message_id")))throw new SecurityException("signal_native_identity_changed");JSONObject value=load("private-outgoing",client);value.remove("native_attempt");value.put("native_ui_done",true).put("native_cancel_requested",false).put("native_failed",false).put("native_error",0).put("native_next_at",0);save("private-outgoing",client,value);return null;});
    }}

    /** Volatile pending text only for the exact original unacknowledged native row and session. */
    public String nativePendingText(String peer,int account,int localId,long dialogId,long randomId) throws Exception {synchronized(keys.guard()){
        keys.requireSession();id(peer);
        if(peer.equals(keys.user)||account<0||account>3||localId>=0||dialogId<=0||dialogId>Integer.MAX_VALUE||randomId==0)throw new SecurityException("signal_native_identity_changed");
        String client=UUID.nameUUIDFromBytes((keys.user+":"+randomId).getBytes(StandardCharsets.UTF_8)).toString();
        if(store.loadValue("private-send-fence",client)!=null)return null;
        JSONObject value=load("private-outgoing",client);if(value==null||!value.has("native_ui"))return null;
        JSONArray identity=value.getJSONArray("native_ui");
        if(identity.length()!=5||identity.getInt(0)!=account||identity.getInt(1)!=localId||identity.getLong(2)!=dialogId||identity.getLong(3)!=randomId||!keys.sessionId().equals(identity.getString(4))||!peer.equals(value.getString("peer")))throw new SecurityException("signal_native_identity_changed");
        if(!value.isNull("message_id"))return null;
        String pending=value.getString("text");text(pending);return pending;
    }}
    public String nativePendingText(String peer,int account,int localId,long dialogId,long randomId,int date) throws Exception {synchronized(keys.guard()){
        String client=UUID.nameUUIDFromBytes((keys.user+":"+randomId).getBytes(StandardCharsets.UTF_8)).toString();JSONObject binding=nativeBinding(account,client);
        if(date<=0||date!=binding.getInt("date"))throw new SecurityException("signal_native_identity_changed");return nativePendingText(peer,account,localId,dialogId,randomId);
    }}

    private String prepare(String peer,String conversation,String client,String text,List<SignalDeviceKeys.ClaimedKey> targets,JSONArray nativeIdentity) throws Exception {
        keys.requireSession();id(peer);id(conversation);id(client);text(text);if(peer.equals(keys.user))throw new IllegalArgumentException("signal_private_peer_required");
        return store.transaction(()->{
            if(store.loadValue("private-send-fence",client)!=null)throw new SecurityException("signal_message_deleted");
            JSONObject prior=load("private-outgoing",client);
            if(prior!=null){requireIntent(prior,peer,conversation,text);if(nativeIdentity!=null){if(prior.has("native_ui")&&!nativeIdentity.toString().equals(prior.getJSONArray("native_ui").toString()))throw new SecurityException("signal_native_identity_changed");prior.put("native_ui",nativeIdentity);if(!prior.has("native_date"))prior.put("native_date",(int)(System.currentTimeMillis()/1000));}if(!prior.isNull("body")){if(nativeIdentity!=null)save("private-outgoing",client,prior);return prior.getString("body");}if(prior.optBoolean("native_cancel_requested"))throw new SecurityException("signal_cancel_requested");}
            JSONObject local=keys.acknowledged();JSONObject sender=sender(local);TreeMap<String,SignalDeviceKeys.ClaimedKey> ordered=new TreeMap<>();Set<String> owners=new HashSet<>();boolean hasPeer=false;
            if(targets==null || targets.isEmpty() || targets.size()>5)throw new IllegalArgumentException("invalid_signal_targets");
            for(SignalDeviceKeys.ClaimedKey target:targets){id(target.user);id(target.device);id(target.address.getName());if(!target.user.equals(peer) && !target.user.equals(keys.user) || target.user.equals(keys.user) && target.device.equals(keys.device) || ordered.put(target.address.getName(),target)!=null || !owners.add(target.user+"_"+target.device))throw new SecurityException("invalid_signal_targets");hasPeer|=target.user.equals(peer);}
            if(!hasPeer)throw new SecurityException("signal_recipient_missing");
            JSONObject devices=new JSONObject();JSONArray manifest=new JSONArray();
            for(SignalDeviceKeys.ClaimedKey target:ordered.values())manifest.put(new JSONArray().put(target.user).put(target.device).put(target.address.getName()).put(target.version));
            String routing=hash(new JSONArray().put(senderTuple(sender)).put(manifest).toString());
            JSONObject own=inner(peer,conversation,client,1,text,sender,routing);
            for(SignalDeviceKeys.ClaimedKey target:ordered.values()){
                bind(target);
                if(!store.containsSession(target.address))new SessionBuilder(store,target.address,new SignalProtocolAddress(local.getString("signal_address_id"),1)).process(target.bundle);
                JSONObject plaintext=new JSONObject(own.toString()).put("recipient",new JSONArray().put(target.user).put(target.device).put(target.address.getName()).put(target.version));
                CiphertextMessage encrypted=new SessionCipher(store,new SignalProtocolAddress(local.getString("signal_address_id"),1),target.address).encrypt(plaintext.toString().getBytes(StandardCharsets.UTF_8));
                devices.put(target.address.getName(),new JSONObject().put("user_id",target.user).put("device_id",target.device).put("version",target.version).put("type",encrypted.getType()).put("ciphertext",Base64.encodeToString(encrypted.serialize(),Base64.NO_WRAP)));
            }
            JSONObject envelope=new JSONObject().put("protocol","signal-v2").put("sender",sender).put("devices",devices);
            String body=new JSONObject().put("client_id",client).put("encrypted",true).put("payload",envelope).toString();
            JSONObject outgoing=prior==null?new JSONObject().put("peer",peer).put("conversation",conversation).put("text",text).put("message_id",JSONObject.NULL):new JSONObject(prior.toString());outgoing.put("body",body);if(nativeIdentity!=null){outgoing.put("native_ui",nativeIdentity);if(!outgoing.has("native_date"))outgoing.put("native_date",(int)(System.currentTimeMillis()/1000));}save("private-outgoing",client,outgoing);
            save("private-own",client,own);return body;
        });
    }

    /** Retry only the exact persisted body, never advance a ratchet for an HTTP retry. */
    private void requireNativeIntent(String client,JSONObject value) throws Exception {
        JSONArray identity=value.getJSONArray("native_ui");if(!nativeIdentity(client,identity.getInt(0),identity.getInt(1),identity.getLong(2),identity.getLong(3)).toString().equals(identity.toString())||value.getInt("native_date")<=0||!value.isNull("message_id"))throw new SecurityException("signal_native_identity_changed");
    }
    private void requirePrivateConversation(String peer,String conversation) throws Exception {
        JSONObject details=(JSONObject)keys.request("GET","/v1/conversations/"+conversation,null);JSONArray members=details.getJSONArray("members");boolean own=false,other=false;for(int i=0;i<members.length();i++){String member=id(members.getJSONObject(i).getString("id"));own|=keys.user.equals(member);other|=peer.equals(member);}if(!conversation.equals(details.getString("id"))||!"private".equals(details.getString("kind"))||members.length()!=2||!own||!other)throw new SecurityException("signal_private_conversation_changed");
    }
    /** The server's unique private pair makes interrupted create/resolve safe to repeat. No keys are claimed here. */
    private String resolveNativeConversation(String client,JSONObject original) throws Exception {
        if(!original.isNull("conversation"))return id(original.getString("conversation"));
        requireNativeIntent(client,original);if(!original.isNull("body"))throw new SecurityException("signal_native_identity_changed");String peer=id(original.getString("peer"));if(peer.equals(keys.user))throw new SecurityException("signal_private_peer_required");
        JSONObject response=(JSONObject)keys.request("POST","/v1/conversations",new JSONObject().put("kind","private").put("members",new JSONArray().put(peer)));String conversation=id(response.getString("id"));requirePrivateConversation(peer,conversation);
        return store.transaction(()->{JSONObject value=load("private-outgoing",client);if(value==null||store.loadValue("private-send-fence",client)!=null)throw new SecurityException("signal_message_deleted");requireNativeIntent(client,value);requireIntent(value,peer,null,original.getString("text"));if(!value.isNull("body")||!original.getJSONArray("native_ui").toString().equals(value.getJSONArray("native_ui").toString())||original.getInt("native_date")!=value.getInt("native_date"))throw new SecurityException("signal_native_identity_changed");value.put("conversation",conversation);save("private-outgoing",client,value);return conversation;});
    }
    public String send(String client) throws Exception {synchronized(keys.guard()){
        keys.requireSession();id(client);if(store.loadValue("private-send-fence",client)!=null)throw new SecurityException("signal_message_deleted");
        JSONObject queued=load("private-outgoing",client);if(queued==null)throw new IllegalStateException("signal_outgoing_missing");
        if(queued.optBoolean("native_cancel_requested"))throw new SecurityException("signal_cancel_requested");
        if(queued.isNull("body")){
            requireNativeIntent(client,queued);JSONArray identity=queued.getJSONArray("native_ui");
            String peer=id(queued.getString("peer")),conversation=resolveNativeConversation(client,queued);queued=load("private-outgoing",client);if(queued.optBoolean("native_cancel_requested"))throw new SecurityException("signal_cancel_requested");requirePrivateConversation(peer,conversation);
            prepare(peer,conversation,client,queued.getString("text"),keys.targets(peer),identity);queued=load("private-outgoing",client);
        }
        String body=queued.getString("body"),conversation=queued.getString("conversation");
        JSONObject prepared=queued;store.transaction(()->{if(!prepared.isNull("message_id")){JSONObject head=load("private-head",prepared.getString("message_id"));if(head!=null && expired(head))retire(head);}return null;});
        if(store.loadValue("private-send-fence",client)!=null)throw new SecurityException("signal_message_deleted");
        JSONObject latest=load("private-outgoing",client);if(latest==null||latest.optBoolean("native_cancel_requested"))throw new SecurityException("signal_cancel_requested");
        JSONObject acknowledgement=(JSONObject)keys.request("POST","/v1/conversations/"+conversation+"/messages",new JSONObject(body));String message=id(string(acknowledgement,"id"));
        store.transaction(()->{JSONObject current=load("private-outgoing",client);if(current==null || !body.equals(current.getString("body")))throw new SecurityException("signal_outgoing_changed");if(!current.isNull("message_id") && !message.equals(current.getString("message_id")))throw new SecurityException("signal_ack_changed");current.put("message_id",message);save("private-outgoing",client,current);save("private-own-message",message,new JSONObject().put("client_id",client));return null;});
        receive(queued.getString("peer"),conversation,(JSONObject)keys.request("GET","/v1/messages/"+message,null),null);return message;
    }}

    /** Acquire signed sender metadata only for an unknown or newer immutable binding. Cached reads remain offline. */
    public String receive(String peer,String conversation,JSONObject message) throws Exception {synchronized(keys.guard()){
        keys.requireSession();id(peer);id(conversation);String owner=id(string(message,"sender_id")),device=id(string(message,"sender_device"));
        if(peer.equals(keys.user)||!conversation.equals(string(message,"conversation_id"))||!owner.equals(peer)&&!owner.equals(keys.user)||message.get("encrypted")!=Boolean.TRUE)throw new SecurityException("signal_message_context_changed");
        if(!message.isNull("deleted_at")||!message.isNull("expires_at")&&!Instant.parse(string(message,"expires_at")).isAfter(Instant.now()))return receive(peer,conversation,message,null);
        JSONObject sender=message.getJSONObject("payload").getJSONObject("sender");senderTuple(sender);
        if(!owner.equals(string(sender,"user_id"))||!device.equals(string(sender,"device_id")))throw new SecurityException("signal_sender_changed");
        SignalDeviceKeys.ClaimedKey snapshot=null;
        if(!owner.equals(keys.user)||!device.equals(keys.device)){
            JSONObject prior=load("private-peer",owner+"_"+device);
            if(prior!=null&&!string(sender,"signal_address_id").equals(prior.getString("address")))throw new SecurityException("signal_identity_changed");
            if(prior==null||number(sender,"version")>prior.getInt("version"))snapshot=keys.senderKey(owner,device,string(sender,"signal_address_id"),number(sender,"version"));
        }
        return receive(peer,conversation,message,snapshot);
    }}
    /** Sender snapshot may be null only when its immutable binding is already stored locally. */
    public String receive(String peer,String conversation,JSONObject message,SignalDeviceKeys.ClaimedKey senderKey) throws Exception {synchronized(keys.guard()){
        keys.requireSession();id(peer);id(conversation);String messageId=id(string(message,"id")),client=id(string(message,"client_id")),owner=id(string(message,"sender_id")),device=id(string(message,"sender_device"));int revision=number(message,"revision"),ordinal=number(message,"ordinal");
        if(peer.equals(keys.user) || !conversation.equals(string(message,"conversation_id")) || !owner.equals(peer) && !owner.equals(keys.user) || message.get("encrypted")!=Boolean.TRUE || revision<1 || ordinal<1)throw new SecurityException("signal_message_context_changed");
        String cacheKey=messageId+"_"+revision;
        return store.transaction(()->{
            JSONObject head=load("private-head",messageId);if(head!=null && (!conversation.equals(head.getString("conversation")) || !owner.equals(head.getString("owner")) || !client.equals(head.getString("client")) || ordinal!=head.getInt("ordinal")))throw new SecurityException("signal_message_rebound");
            JSONObject next=head(messageId,conversation,owner,client,head==null?revision:Math.max(revision,head.getInt("revision")),ordinal);String deadline=message.isNull("expires_at")?null:Instant.parse(string(message,"expires_at")).toString();
            if(head!=null && !head.isNull("expires_at") && (deadline==null || Instant.parse(head.getString("expires_at")).isBefore(Instant.parse(deadline))))deadline=head.getString("expires_at");
            next.put("expires_at",deadline==null?JSONObject.NULL:deadline);
            if(!message.isNull("deleted_at") || expired(next)){retire(next);return null;}
            if(head!=null && (head.optBoolean("deleted") || revision<head.getInt("revision")))throw new SecurityException("signal_message_deleted_or_stale");
            if(!message.isNull("media_id") || !message.isNull("reply_to") || !message.isNull("forwarded_from"))throw new SecurityException("signal_text_only");
            JSONObject envelope=message.getJSONObject("payload"),sender=envelope.getJSONObject("sender"),devices=envelope.getJSONObject("devices");String canonical=canonical(envelope);
            if(!owner.equals(string(sender,"user_id")) || !device.equals(string(sender,"device_id")))throw new SecurityException("signal_sender_changed");
            for(String address:addresses(devices)){JSONObject destination=devices.getJSONObject(address);String user=string(destination,"user_id"),targetDevice=string(destination,"device_id");if(!user.equals(peer) && !user.equals(keys.user) || user.equals(owner) && targetDevice.equals(device))throw new SecurityException("invalid_signal_targets");}
            String digest=hash(new JSONArray().put(messageId).put(conversation).put(client).put(owner).put(device).put(revision).put(ordinal).put(canonical).toString());
            JSONObject cached=load("private-cache",cacheKey);
            if(cached!=null){if(!digest.equals(cached.getString("digest")))throw new SecurityException("signal_ciphertext_changed");save("private-head",messageId,next);return cached.getJSONObject("inner").getString("text");}
            String ledgerKey=owner+"_"+client+"_"+revision;JSONObject ledger=load("private-client",ledgerKey);
            if(ledger!=null && !messageId.equals(ledger.getString("message_id")))throw new SecurityException("signal_message_rebound");
            JSONObject local=keys.acknowledged(),decoded;
            if(owner.equals(keys.user) && device.equals(keys.device)){
                JSONObject queued=load("private-outgoing",client);decoded=load("private-own",client);
                if(queued==null || decoded==null || !queued.isNull("message_id") && !messageId.equals(queued.getString("message_id")) || !canonical.equals(canonical(new JSONObject(queued.getString("body")).getJSONObject("payload"))))throw new SecurityException("signal_own_message_changed");
            }else{
                JSONObject destination=devices.getJSONObject(local.getString("signal_address_id"));
                if(!keys.user.equals(string(destination,"user_id")) || !keys.device.equals(string(destination,"device_id")) || number(destination,"version")>number(local,"version"))throw new SecurityException("signal_recipient_changed");
                if(senderKey!=null){if(!owner.equals(senderKey.user) || !device.equals(senderKey.device) || !string(sender,"signal_address_id").equals(senderKey.address.getName()) || senderKey.version<number(sender,"version"))throw new SecurityException("signal_sender_changed");bind(senderKey);}
                JSONObject trusted=load("private-peer",owner+"_"+device);
                if(trusted==null || !string(sender,"signal_address_id").equals(trusted.getString("address")) || number(sender,"version")>trusted.getInt("version"))throw new SecurityException("signal_sender_key_required");
                SignalProtocolAddress remote=new SignalProtocolAddress(trusted.getString("address"),1);byte[] ciphertext=bytes(string(destination,"ciphertext"));SessionCipher cipher=new SessionCipher(store,new SignalProtocolAddress(local.getString("signal_address_id"),1),remote);byte[] plaintext;
                if(number(destination,"type")==CiphertextMessage.PREKEY_TYPE){PreKeySignalMessage packet=new PreKeySignalMessage(ciphertext);if(!Base64.encodeToString(packet.getIdentityKey().serialize(),Base64.NO_WRAP).equals(trusted.getString("identity")))throw new SecurityException("signal_identity_changed");plaintext=cipher.decrypt(packet);}else{IdentityKey identity=store.getIdentity(remote);if(identity==null || !Base64.encodeToString(identity.serialize(),Base64.NO_WRAP).equals(trusted.getString("identity")))throw new SecurityException("signal_identity_changed");plaintext=cipher.decrypt(new SignalMessage(ciphertext));}
                decoded=parse(plaintext);exact(decoded,"protocol","conversation_id","client_id","revision","participants","sender","routing_digest","text","recipient");
                JSONArray expected=new JSONArray().put(keys.user).put(keys.device).put(local.getString("signal_address_id")).put(number(destination,"version"));if(!expected.toString().equals(decoded.getJSONArray("recipient").toString()))throw new SecurityException("signal_recipient_changed");
            }
            String routing=routing(envelope);JSONArray participants=participants(peer);
            if(!"vyrn-text-v1".equals(string(decoded,"protocol")) || !conversation.equals(string(decoded,"conversation_id")) || !client.equals(string(decoded,"client_id")) || revision!=number(decoded,"revision") || !participants.toString().equals(decoded.getJSONArray("participants").toString()) || !senderTuple(sender).toString().equals(senderTuple(decoded.getJSONObject("sender")).toString()) || !routing.equals(string(decoded,"routing_digest")))throw new SecurityException("signal_authenticated_context_changed");
            text(string(decoded,"text"));save("private-cache",cacheKey,new JSONObject().put("digest",digest).put("inner",decoded));save("private-client",ledgerKey,new JSONObject().put("message_id",messageId));save("private-head",messageId,next);return decoded.getString("text");
        });
    }}
    public String cachedText(String message) throws Exception {synchronized(keys.guard()){keys.requireSession();id(message);return store.transaction(()->{JSONObject head=load("private-head",message);if(head==null || head.optBoolean("deleted"))return null;if(expired(head)){retire(head);return null;}JSONObject cached=load("private-cache",message+"_"+head.getInt("revision"));return cached==null?null:cached.getJSONObject("inner").getString("text");});}}
    /** Validate the native row's complete context before returning volatile text; never write this into native messageOwner. */
    public JSONObject display(String peer,String conversation,String message,String sender,int revision,int ordinal) throws Exception {synchronized(keys.guard()){
        keys.requireSession();id(peer);id(conversation);id(message);id(sender);
        if(peer.equals(keys.user)||!sender.equals(peer)&&!sender.equals(keys.user)||revision<1||ordinal<1)throw new SecurityException("signal_display_context_changed");
        return store.transaction(()->{
            JSONObject head=load("private-head",message);if(head==null||head.optBoolean("deleted"))return null;
            if(expired(head)){retire(head);return null;}
            if(!conversation.equals(head.getString("conversation"))||!sender.equals(head.getString("owner"))||revision!=head.getInt("revision")||ordinal!=head.getInt("ordinal"))throw new SecurityException("signal_display_context_changed");
            JSONObject cached=load("private-cache",message+"_"+revision);if(cached==null)return null;JSONObject inner=cached.getJSONObject("inner");
            if(!conversation.equals(string(inner,"conversation_id"))||!head.getString("client").equals(string(inner,"client_id"))||revision!=number(inner,"revision")||!participants(peer).toString().equals(inner.getJSONArray("participants").toString())||!sender.equals(inner.getJSONObject("sender").getString("user_id")))throw new SecurityException("signal_display_context_changed");
            return new JSONObject().put("text",string(inner,"text")).put("expires_at",head.get("expires_at"));
        });
    }}
    /** Call on startup and from the lifecycle worker; cache reads also enforce learned deadlines. */
    // ponytail: decrypt all retained heads; add a deadline index if large-history measurements require it.
    public int pruneExpired() throws Exception {synchronized(keys.guard()){keys.requireSession();return store.transaction(()->{int removed=0;for(String message:store.valueNames("private-head")){JSONObject head=load("private-head",message);if(!head.optBoolean("deleted") && expired(head)){retire(head);removed++;}}return removed;});}}
    private static boolean expired(JSONObject head) throws Exception {return !head.isNull("expires_at") && !Instant.parse(head.getString("expires_at")).isAfter(Instant.now());}
    private void retire(JSONObject head) throws Exception {
        String message=head.getString("message_id"),client=head.getString("client");save("private-head",message,head.put("deleted",true));store.removeValues("private-cache",message+"_");
        if(keys.user.equals(head.getString("owner"))){store.removeValue("private-own",client);JSONObject outgoing=load("private-outgoing",client);if(outgoing!=null&&outgoing.optBoolean("native_cancel_requested")&&outgoing.has("native_ui")&&!outgoing.optBoolean("native_ui_done")){outgoing.remove("text");outgoing.remove("body");outgoing.remove("native_attempt");outgoing.put("native_cancelled",true).put("native_failed",false).put("native_error",0).put("native_next_at",0);save("private-outgoing",client,outgoing);}else store.removeValue("private-outgoing",client);store.removeValue("private-own-message",message);store.saveValue("private-send-fence",client,new byte[]{1});}
    }

    private void bind(SignalDeviceKeys.ClaimedKey key) throws Exception {
        String identity=Base64.encodeToString(key.bundle.getIdentityKey().serialize(),Base64.NO_WRAP),name=key.user+"_"+key.device;JSONObject prior=load("private-peer",name);
        keys.validateKnownIdentity(key);
        save("private-peer",name,new JSONObject().put("address",key.address.getName()).put("identity",identity).put("version",prior==null?key.version:Math.max(key.version,prior.getInt("version"))).put("verified",prior!=null && prior.optBoolean("verified")));save("private-address",key.address.getName(),new JSONObject().put("owner",name));
    }
    private JSONObject sender(JSONObject local) throws Exception {return new JSONObject().put("user_id",keys.user).put("device_id",keys.device).put("signal_address_id",local.getString("signal_address_id")).put("version",number(local,"version"));}
    private JSONArray participants(String peer){String[] users={keys.user,peer};Arrays.sort(users);return new JSONArray().put(users[0]).put(users[1]);}
    private JSONObject inner(String peer,String conversation,String client,int revision,String text,JSONObject sender,String routing) throws Exception {return new JSONObject().put("protocol","vyrn-text-v1").put("conversation_id",conversation).put("client_id",client).put("revision",revision).put("participants",participants(peer)).put("sender",sender).put("routing_digest",routing).put("text",text);}
    private static JSONObject head(String message,String conversation,String owner,String client,int revision,int ordinal) throws Exception {return new JSONObject().put("message_id",message).put("conversation",conversation).put("owner",owner).put("client",client).put("revision",revision).put("ordinal",ordinal).put("deleted",false);}
    private JSONObject load(String kind,String name) throws Exception {byte[] value=store.loadValue(kind,name);return value==null?null:parse(value);}
    private void save(String kind,String name,JSONObject value){store.saveValue(kind,name,value.toString().getBytes(StandardCharsets.UTF_8));}
    private static JSONObject parse(byte[] value){try{return new JSONObject(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(value)).toString());}catch(Exception failure){throw new SecurityException("invalid_signal_plaintext_record");}}
    private static String id(String value){if(!UUID.fromString(value).toString().equals(value))throw new IllegalArgumentException("invalid_signal_id");return value;}
    private static String string(JSONObject object,String name) throws Exception {Object value=object.get(name);if(!(value instanceof String))throw new SecurityException("invalid_signal_string");return (String)value;}
    private static int number(JSONObject object,String name) throws Exception {Object value=object.get(name);if(!(value instanceof Integer) && !(value instanceof Long))throw new SecurityException("invalid_signal_number");long n=((Number)value).longValue();if(n<1 || n>Integer.MAX_VALUE)throw new SecurityException("invalid_signal_number");return (int)n;}
    private static void text(String text){if(text==null || text.isEmpty() || text.codePointCount(0,text.length())>16384)throw new IllegalArgumentException("invalid_signal_text");for(int i=0;i<text.length();i++){char c=text.charAt(i);if(Character.isHighSurrogate(c)){if(++i>=text.length() || !Character.isLowSurrogate(text.charAt(i)))throw new IllegalArgumentException("invalid_signal_text");}else if(Character.isLowSurrogate(c))throw new IllegalArgumentException("invalid_signal_text");}}
    private static void exact(JSONObject object,String...fields) throws Exception {Set<String> allowed=new HashSet<>(Arrays.asList(fields));if(object.length()!=allowed.size())throw new SecurityException("invalid_signal_fields");for(Iterator<String> names=object.keys();names.hasNext();)if(!allowed.contains(names.next()))throw new SecurityException("invalid_signal_fields");}
    private static JSONArray senderTuple(JSONObject sender) throws Exception {exact(sender,"user_id","device_id","signal_address_id","version");return new JSONArray().put(id(string(sender,"user_id"))).put(id(string(sender,"device_id"))).put(id(string(sender,"signal_address_id"))).put(number(sender,"version"));}
    private static byte[] bytes(String ciphertext){if(ciphertext.length()>128*1024)throw new SecurityException("invalid_signal_ciphertext");byte[] value=Base64.decode(ciphertext,Base64.NO_WRAP);if(value.length<32 || !ciphertext.equals(Base64.encodeToString(value,Base64.NO_WRAP)))throw new SecurityException("invalid_signal_ciphertext");return value;}
    private static List<String> addresses(JSONObject devices){List<String> result=new ArrayList<>();for(Iterator<String> iterator=devices.keys();iterator.hasNext();)result.add(iterator.next());Collections.sort(result);return result;}
    private static String routing(JSONObject envelope) throws Exception {JSONArray descriptors=new JSONArray();JSONObject devices=envelope.getJSONObject("devices");for(String address:addresses(devices)){JSONObject item=devices.getJSONObject(address);descriptors.put(new JSONArray().put(id(string(item,"user_id"))).put(id(string(item,"device_id"))).put(id(address)).put(number(item,"version")));}return hash(new JSONArray().put(senderTuple(envelope.getJSONObject("sender"))).put(descriptors).toString());}
    private static String canonical(JSONObject envelope) throws Exception {
        exact(envelope,"protocol","sender","devices");if(!"signal-v2".equals(string(envelope,"protocol")))throw new SecurityException("invalid_signal_protocol");JSONObject devices=envelope.getJSONObject("devices");if(devices.length()<1 || devices.length()>5)throw new SecurityException("invalid_signal_targets");JSONArray copies=new JSONArray();Set<String> owners=new HashSet<>();
        for(String address:addresses(devices)){JSONObject item=devices.getJSONObject(address);exact(item,"user_id","device_id","version","type","ciphertext");String user=id(string(item,"user_id")),device=id(string(item,"device_id"));if(!owners.add(user+"_"+device))throw new SecurityException("invalid_signal_targets");int type=number(item,"type");if(type!=CiphertextMessage.PREKEY_TYPE && type!=CiphertextMessage.WHISPER_TYPE)throw new SecurityException("invalid_signal_type");String ciphertext=string(item,"ciphertext");bytes(ciphertext);copies.put(new JSONArray().put(id(address)).put(user).put(device).put(number(item,"version")).put(type).put(ciphertext));}
        return new JSONArray().put("signal-v2").put(senderTuple(envelope.getJSONObject("sender"))).put(copies).toString();
    }
    private static String hash(String value) throws Exception {byte[] digest=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();for(byte b:digest)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}
}
