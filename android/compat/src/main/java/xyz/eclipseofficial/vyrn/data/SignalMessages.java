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

/** Text transport/cache primitive. UI, scheduling, verification and device lifecycle remain gated. */
public final class SignalMessages {
    private final SignalDeviceKeys keys;
    private final SignalStore store;
    public SignalMessages(SignalDeviceKeys keys){this.keys=keys;store=keys.store;}

    /** Existing ciphertext retries stay offline; only new sends acquire the complete current roster. */
    public String prepare(String peer,String conversation,String client,String text) throws Exception {synchronized(keys.guard()){
        keys.requireSession();id(peer);id(conversation);id(client);text(text);if(peer.equals(keys.user))throw new IllegalArgumentException("signal_private_peer_required");if(store.loadValue("private-send-fence",client)!=null || store.loadValue("private-outgoing",client)!=null)return prepare(peer,conversation,client,text,null);
        return prepare(peer,conversation,client,text,keys.targets(peer));
    }}

    /** Caller obtains the complete verified ready-device snapshot before entering this transaction. */
    public String prepare(String peer,String conversation,String client,String text,List<SignalDeviceKeys.ClaimedKey> targets) throws Exception {synchronized(keys.guard()){
        keys.requireSession();id(peer);id(conversation);id(client);text(text);if(peer.equals(keys.user))throw new IllegalArgumentException("signal_private_peer_required");
        return store.transaction(()->{
            if(store.loadValue("private-send-fence",client)!=null)throw new SecurityException("signal_message_deleted");
            JSONObject prior=load("private-outgoing",client);
            if(prior!=null){if(!peer.equals(prior.getString("peer")) || !conversation.equals(prior.getString("conversation")) || !text.equals(prior.getString("text")))throw new SecurityException("signal_client_conflict");return prior.getString("body");}
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
            save("private-outgoing",client,new JSONObject().put("peer",peer).put("conversation",conversation).put("text",text).put("body",body).put("message_id",JSONObject.NULL));
            save("private-own",client,own);return body;
        });
    }}

    /** Retry only the exact persisted body, never advance a ratchet for an HTTP retry. */
    public String send(String client) throws Exception {synchronized(keys.guard()){
        keys.requireSession();id(client);if(store.loadValue("private-send-fence",client)!=null)throw new SecurityException("signal_message_deleted");
        JSONObject queued=load("private-outgoing",client);if(queued==null)throw new IllegalStateException("signal_outgoing_missing");
        String body=queued.getString("body"),conversation=queued.getString("conversation");
        store.transaction(()->{if(!queued.isNull("message_id")){JSONObject head=load("private-head",queued.getString("message_id"));if(head!=null && expired(head))retire(head);}return null;});
        if(store.loadValue("private-send-fence",client)!=null)throw new SecurityException("signal_message_deleted");
        JSONObject acknowledgement=(JSONObject)keys.request("POST","/v1/conversations/"+conversation+"/messages",new JSONObject(body));String message=id(string(acknowledgement,"id"));
        store.transaction(()->{JSONObject current=load("private-outgoing",client);if(current==null || !body.equals(current.getString("body")))throw new SecurityException("signal_outgoing_changed");if(!current.isNull("message_id") && !message.equals(current.getString("message_id")))throw new SecurityException("signal_ack_changed");current.put("message_id",message);save("private-outgoing",client,current);save("private-own-message",message,new JSONObject().put("client_id",client));return null;});
        receive(queued.getString("peer"),conversation,(JSONObject)keys.request("GET","/v1/messages/"+message,null),null);return message;
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
    /** Call on startup and from the lifecycle worker; cache reads also enforce learned deadlines. */
    // ponytail: decrypt all retained heads; add a deadline index if large-history measurements require it.
    public int pruneExpired() throws Exception {synchronized(keys.guard()){keys.requireSession();return store.transaction(()->{int removed=0;for(String message:store.valueNames("private-head")){JSONObject head=load("private-head",message);if(!head.optBoolean("deleted") && expired(head)){retire(head);removed++;}}return removed;});}}
    private static boolean expired(JSONObject head) throws Exception {return !head.isNull("expires_at") && !Instant.parse(head.getString("expires_at")).isAfter(Instant.now());}
    private void retire(JSONObject head) throws Exception {
        String message=head.getString("message_id"),client=head.getString("client");save("private-head",message,head.put("deleted",true));store.removeValues("private-cache",message+"_");
        if(keys.user.equals(head.getString("owner"))){store.removeValue("private-own",client);store.removeValue("private-outgoing",client);store.removeValue("private-own-message",message);store.saveValue("private-send-fence",client,new byte[]{1});}
    }

    private void bind(SignalDeviceKeys.ClaimedKey key) throws Exception {
        String identity=Base64.encodeToString(key.bundle.getIdentityKey().serialize(),Base64.NO_WRAP),name=key.user+"_"+key.device;JSONObject prior=load("private-peer",name),addressOwner=load("private-address",key.address.getName());
        if(prior!=null && (!key.address.getName().equals(prior.getString("address")) || !identity.equals(prior.getString("identity"))) || addressOwner!=null && !name.equals(addressOwner.getString("owner")) || !store.isTrustedIdentity(key.address,key.bundle.getIdentityKey(),org.signal.libsignal.protocol.state.IdentityKeyStore.Direction.SENDING))throw new SecurityException("signal_identity_changed");
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
