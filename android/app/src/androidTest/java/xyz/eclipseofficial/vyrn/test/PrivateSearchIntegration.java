package xyz.eclipseofficial.vyrn.test;

import android.content.Context;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import xyz.eclipseofficial.vyrn.data.RestRepository;
import xyz.eclipseofficial.vyrn.data.SignalDeviceKeys;
import xyz.eclipseofficial.vyrn.data.SignalMessages;

/** Real Signal/JNI cache queries; no native search UI acceptance is claimed. */
final class PrivateSearchIntegration {
    static void run(Context context,JSONObject fixture,AuthUiIntegration.Check check) throws Exception {
        RestRepository a=SignalNetworkIntegration.register(context,fixture,9171,"-search-a"),b=SignalNetworkIntegration.register(context,fixture,9172,"-search-b");
        try(SignalDeviceKeys alice=new SignalDeviceKeys(context,a);SignalDeviceKeys bob=new SignalDeviceKeys(context,b)){
            alice.publishInitial();bob.publishInitial();SignalMessages sender=new SignalMessages(alice),receiver=new SignalMessages(bob);
            String conversation=((JSONObject)a.request("POST","/v1/conversations",new JSONObject().put("kind","private").put("members",new JSONArray().put(bob.user)),true)).getString("id");
            String[] texts={"Lookup %_ سلام first","noise","lookup %_ سلام latest"};JSONObject[] messages=new JSONObject[3];
            for(int i=0;i<texts.length;i++){
                String client=UUID.randomUUID().toString();sender.prepare(bob.user,conversation,client,texts[i]);String id=sender.send(client);
                messages[i]=(JSONObject)b.request("GET","/v1/messages/"+id,null,true);
            }
            check.verify(receiver.searchCached(alice.user,conversation,"lookup",0,10).getInt("total")==0,"private_search_undecrypted_ciphertext_has_no_hits");
            check.verify(receiver.cachedConversation(alice.user)==null,"private_search_discovery_requires_authenticated_cached_history");
            for(int i=0;i<messages.length;i++)check.verify(texts[i].equals(receiver.receive(alice.user,conversation,messages[i])),"private_search_real_signal_receive_"+i);
            check.verify(conversation.equals(receiver.cachedConversation(alice.user))&&receiver.cachedConversation(UUID.randomUUID().toString())==null,"private_search_discovery_bound_to_peer");
            JSONObject page=receiver.searchCached(alice.user,conversation,"LOOKUP",0,1);
            check.verify(page.getInt("total")==2&&page.getJSONArray("messages").length()==1&&messages[2].getString("id").equals(page.getJSONArray("messages").getJSONObject(0).getString("message")),"private_search_case_insensitive_total_and_newest_page");
            JSONObject second=receiver.searchCached(alice.user,conversation,"lookup",messages[2].getInt("ordinal"),1);
            check.verify(second.getInt("total")==2&&messages[0].getString("id").equals(second.getJSONArray("messages").getJSONObject(0).getString("message")),"private_search_cursor_keeps_total_and_selects_older_hit");
            JSONObject exhausted=receiver.searchCached(alice.user,conversation,"lookup",messages[0].getInt("ordinal"),1);
            check.verify(exhausted.getInt("total")==2&&exhausted.getJSONArray("messages").length()==0,"private_search_exhausted_cursor_retains_total");
            check.verify(receiver.searchCached(alice.user,conversation,"%_",0,10).getInt("total")==2&&receiver.searchCached(alice.user,conversation,"سلام",0,10).getInt("total")==2,"private_search_literal_and_persian_matching");
            check.verify(!page.toString().contains(texts[2])&&!page.getJSONArray("messages").getJSONObject(0).has("text"),"private_search_results_contain_only_bound_metadata");
            check.verify(sender.searchCached(bob.user,conversation,"lookup",0,10).getInt("total")==2,"private_search_own_authenticated_cache_supported");
            JSONObject original=b.vault.load();
            try{
                b.vault.save(new JSONObject(original.toString()).put("access_token","invalid-offline-fixture").put("refresh_token","invalid-offline-fixture"));
                try(SignalDeviceKeys offline=SignalDeviceKeys.openLocal(context,b)){
                    check.verify(conversation.equals(new SignalMessages(offline).cachedConversation(alice.user)),"private_search_discovery_reopens_without_network");
                    check.verify(new SignalMessages(offline).searchCached(alice.user,conversation,"lookup",0,1).getInt("total")==2,"private_search_reopened_store_works_without_valid_network_tokens");
                }
                b.vault.save(new JSONObject(original.toString()).put("session_id",UUID.randomUUID().toString()));
                boolean denied=false;try{receiver.searchCached(alice.user,conversation,"lookup",0,1);}catch(SecurityException expected){denied=true;}
                check.verify(denied,"private_search_changed_session_rejected_before_cache_read");
            }finally{b.vault.save(original);}
            boolean denied=false;try{receiver.searchCached(UUID.randomUUID().toString(),conversation,"lookup",0,1);}catch(SecurityException expected){denied=true;}
            check.verify(denied,"private_search_wrong_peer_context_rejected");
            check.verify(receiver.searchCached(alice.user,UUID.randomUUID().toString(),"lookup",0,1).getInt("total")==0,"private_search_other_conversation_isolated");
            for(String bad:new String[]{"",new String(new char[101]).replace("\0","🔎")}){
                denied=false;try{receiver.searchCached(alice.user,conversation,bad,0,1);}catch(IllegalArgumentException expected){denied=true;}
                check.verify(denied,bad.isEmpty()?"private_search_empty_query_rejected":"private_search_unicode_query_bound");
            }
            String latest=messages[2].getString("id");byte[] head=bob.store.loadValue("private-head",latest);
            try{
                bob.store.transaction(()->{JSONObject forged=new JSONObject(new String(head,StandardCharsets.UTF_8)).put("message_id",UUID.randomUUID().toString()).put("expires_at",Instant.now().minusSeconds(1).toString());bob.store.saveValue("private-head",latest,forged.toString().getBytes(StandardCharsets.UTF_8));return null;});
                denied=false;try{receiver.searchCached(alice.user,conversation,"lookup",0,1);}catch(SecurityException expected){denied=true;}
                check.verify(denied,"private_search_rebound_head_rejected_before_expiry_mutation");
            }finally{bob.store.transaction(()->{bob.store.saveValue("private-head",latest,head);return null;});}
            String first=messages[0].getString("id");a.request("DELETE","/v1/messages/"+first,null,true);receiver.receive(alice.user,conversation,(JSONObject)b.request("GET","/v1/messages/"+first,null,true));
            check.verify(receiver.searchCached(alice.user,conversation,"lookup",0,10).getInt("total")==1,"private_search_server_deleted_message_removed");
            JSONObject edited=sender.edit(bob.user,conversation,latest,1,"Changed only");receiver.receive(alice.user,conversation,edited);
            JSONObject changed=receiver.searchCached(alice.user,conversation,"changed",0,1);
            check.verify(receiver.searchCached(alice.user,conversation,"lookup",0,10).getInt("total")==0&&changed.getInt("total")==1&&changed.getJSONArray("messages").getJSONObject(0).getInt("revision")==2,"private_search_only_latest_authenticated_revision_matches");
            String expiring=messages[1].getString("id");receiver.receive(alice.user,conversation,sender.edit(bob.user,conversation,expiring,1,"Expiry lookup"));
            bob.store.transaction(()->{JSONObject deadline=new JSONObject(new String(bob.store.loadValue("private-head",expiring),StandardCharsets.UTF_8)).put("expires_at",Instant.now().minusSeconds(1).toString());bob.store.saveValue("private-head",expiring,deadline.toString().getBytes(StandardCharsets.UTF_8));return null;});
            check.verify(receiver.searchCached(alice.user,conversation,"expiry",0,1).getInt("total")==0&&bob.store.loadValue("private-cache",expiring+"_2")==null,"private_search_learned_expiry_retires_cached_plaintext");
            check.verify(receiver.display(alice.user,conversation,latest,alice.user,2,messages[2].getInt("ordinal")).getString("text").equals("Changed only"),"private_search_shared_display_validator_preserves_live_text");
        }finally{
            try{a.request("POST","/v1/auth/logout",new JSONObject(),true);b.request("POST","/v1/auth/logout",new JSONObject(),true);}finally{a.vault.clear();b.vault.clear();}
        }
    }
}
