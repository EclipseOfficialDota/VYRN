package xyz.eclipseofficial.vyrn.test;

import android.app.Activity;
import android.app.Instrumentation;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.LaunchActivity;
import xyz.eclipseofficial.vyrn.compat.VyrnBridge;
import xyz.eclipseofficial.vyrn.data.IdMap;
import xyz.eclipseofficial.vyrn.data.RestRepository;
import xyz.eclipseofficial.vyrn.data.SignalDeviceKeys;
import xyz.eclipseofficial.vyrn.data.SignalMessages;
import static xyz.eclipseofficial.vyrn.test.ReactionReadIntegration.*;

/** Real Signal ciphertext, native history, Search/IME/navigation with transport disconnected. */
final class PrivateSearchUiIntegration {
    static void run(Instrumentation test,Activity activity,JSONObject fixture,AuthUiIntegration.Check check) throws Exception {
        AuthUiIntegration.registration(test,activity,fixture,check);
        int account=UserConfig.selectedAccount;
        RestRepository recipient=VyrnBridge.forAccount(account).repository;
        RestRepository sender=SignalNetworkIntegration.register(test.getTargetContext(),fixture,9174,"-search-ui-sender");
        RestRepository other=SignalNetworkIntegration.register(test.getTargetContext(),fixture,9175,"-search-ui-other");
        String query="#VyrnPrivate"+UUID.randomUUID().toString().substring(0,8);
        ArrayList<Integer> expected=new ArrayList<>();long peer;
        try(SignalDeviceKeys a=new SignalDeviceKeys(test.getTargetContext(),sender);
            SignalDeviceKeys b=new SignalDeviceKeys(test.getTargetContext(),recipient);
            SignalDeviceKeys c=new SignalDeviceKeys(test.getTargetContext(),other)){
            a.publishInitial();b.publishInitial();c.publishInitial();
            SignalMessages outbound=new SignalMessages(a),inbound=new SignalMessages(b);
            String conversation=((JSONObject)sender.request("POST","/v1/conversations",new JSONObject().put("kind","private").put("members",new JSONArray().put(b.user)),true)).getString("id");
            check.verify(inbound.cachedConversation(a.user)==null,"private_search_no_discovery_before_authenticated_receive");
            for(int i=0;i<24;i++){
                String client=UUID.randomUUID().toString(),text=query+" result "+i;
                outbound.prepare(b.user,conversation,client,text);String id=outbound.send(client);
                JSONObject message=(JSONObject)recipient.request("GET","/v1/messages/"+id,null,true);
                if(!text.equals(inbound.receive(a.user,conversation,message)))throw new AssertionError("private_search_signal_receive");
                expected.add(message.getInt("ordinal"));
            }
            String different=((JSONObject)recipient.request("POST","/v1/conversations",new JSONObject().put("kind","private").put("members",new JSONArray().put(c.user)),true)).getString("id");
            String client=UUID.randomUUID().toString();inbound.prepare(c.user,different,client,query+" other peer");inbound.send(client);
            check.verify(conversation.equals(inbound.cachedConversation(a.user))&&inbound.searchCached(a.user,conversation,query,0,100).getInt("total")==24,"private_search_discovery_excludes_own_sent_cache_for_other_peer");
            CompletableFuture<TLRPC.TL_messages_dialogs> dialogs=new CompletableFuture<>();
            ConnectionsManager.getInstance(account).sendRequest(new TLRPC.TL_messages_getDialogs(),(value,error)->{if(error==null)dialogs.complete((TLRPC.TL_messages_dialogs)value);else dialogs.completeExceptionally(new AssertionError("private_search_dialogs"));});
            TLRPC.TL_messages_dialogs loaded=dialogs.get(30,TimeUnit.SECONDS);
            test.runOnMainSync(()->{MessagesController.getInstance(account).putUsers(loaded.users,false);MessagesController.getInstance(account).putChats(loaded.chats,false);});
            try(IdMap ids=new IdMap(test.getTargetContext(),account)){peer=ids.local("user",a.user);}
            int latest=expected.get(expected.size()-1);String messageId;try(IdMap ids=new IdMap(test.getTargetContext(),account)){messageId=ids.remote("message",latest);}
            JSONObject before=(JSONObject)recipient.request("GET","/v1/messages/"+messageId,null,true);
            TLRPC.TL_messages_sendReaction select=new TLRPC.TL_messages_sendReaction();select.peer=new TLRPC.TL_inputPeerUser();select.peer.user_id=peer;select.msg_id=latest;select.flags=1;TLRPC.TL_reactionEmoji emoji=new TLRPC.TL_reactionEmoji();emoji.emoticon="👍";select.reaction.add(emoji);
            TLRPC.Updates selected=ReactionReadIntegration.request(account,select);
            check.verify(selected.updates.size()==1&&selected.updates.get(0) instanceof org.telegram.tgnet.tl.TL_update.TL_updateMessageReactions&&((org.telegram.tgnet.tl.TL_update.TL_updateMessageReactions)selected.updates.get(0)).peer.user_id==peer&&((org.telegram.tgnet.tl.TL_update.TL_updateMessageReactions)selected.updates.get(0)).reactions.results.size()==1&&((org.telegram.tgnet.tl.TL_update.TL_updateMessageReactions)selected.updates.get(0)).reactions.results.get(0).chosen,"private_reaction_native_selection_bound_to_original_peer");
            ReactionReadIntegration.apply(test,account,selected);
            TLRPC.TL_messages_getMessagesReactions metadata=new TLRPC.TL_messages_getMessagesReactions();metadata.peer=select.peer;metadata.id.add(latest);
            TLRPC.Updates loadedReactions=ReactionReadIntegration.request(account,metadata);
            check.verify(loadedReactions.updates.size()==1&&loadedReactions.updates.get(0) instanceof org.telegram.tgnet.tl.TL_update.TL_updateMessageReactions&&((org.telegram.tgnet.tl.TL_update.TL_updateMessageReactions)loadedReactions.updates.get(0)).reactions.results.get(0).count==1,"private_reaction_native_metadata_read_returns_confirmed_count");
            JSONObject after=(JSONObject)recipient.request("GET","/v1/messages/"+messageId,null,true);
            check.verify(after.getBoolean("encrypted")&&after.getInt("revision")==before.getInt("revision")&&after.getJSONObject("payload").toString().equals(before.getJSONObject("payload").toString())&&inbound.display(a.user,conversation,messageId,a.user,after.getInt("revision"),latest).getString("text").equals(query+" result 23"),"private_reaction_preserves_ciphertext_revision_and_authenticated_text");
            TLRPC.TL_messages_sendReaction clear=new TLRPC.TL_messages_sendReaction();clear.peer=select.peer;clear.msg_id=latest;
            TLRPC.Updates cleared=ReactionReadIntegration.request(account,clear);
            check.verify(cleared.updates.size()==1&&cleared.updates.get(0) instanceof org.telegram.tgnet.tl.TL_update.TL_updateMessageReactions&&((org.telegram.tgnet.tl.TL_update.TL_updateMessageReactions)cleared.updates.get(0)).reactions.results.isEmpty(),"private_reaction_native_empty_selection_clears_confirmed_state");
            ReactionReadIntegration.apply(test,account,cleared);
            verifyReactions(test,account,peer,latest,messageId,recipient,sender,before,query+" result 23",check);
            SearchUiIntegration.verifyUi(test,peer,true,query,expected,check);
            CompletableFuture<Integer> clean=new CompletableFuture<>();
            MessagesStorage storage=MessagesStorage.getInstance(account);
            storage.getStorageQueue().postRunnable(()->{try{
                org.telegram.SQLite.SQLiteCursor cursor=storage.getDatabase().queryFinalized("SELECT data,custom_params,replydata FROM messages_v2 WHERE uid="+peer);
                int count=0;try{while(cursor.next()){
                    for(int column=0;column<3;column++){
                        byte[] bytes=cursor.byteArrayValue(column);
                        if(bytes!=null&&new String(bytes,StandardCharsets.UTF_8).contains(query))throw new AssertionError("private_search_plaintext_in_native_storage");
                    }
                    count++;
                }}finally{cursor.dispose();}clean.complete(count);
            }catch(Throwable failure){clean.completeExceptionally(failure);}});
            check.verify(clean.get(20,TimeUnit.SECONDS)>=24,"private_search_actual_native_rows_contain_no_private_query_or_text");
        }finally{
            try{sender.request("POST","/v1/auth/logout",new JSONObject(),true);other.request("POST","/v1/auth/logout",new JSONObject(),true);}finally{sender.vault.clear();other.vault.clear();}
        }
    }
    private static void verifyReactions(Instrumentation test,int account,long peer,int ordinal,String id,RestRepository recipient,RestRepository sender,JSONObject before,String text,AuthUiIntegration.Check check) throws Exception {
        String path="/v1/messages/"+id+"/reactions";
        sender.request("PUT",path,new JSONObject().put("emoji","👍"),true);
        ChatActivity chat=SearchUiIntegration.openChat(test,peer,true,check);
        await(test,()->matches(message(chat,ordinal),new String[]{"👍"},new int[]{1},new boolean[]{false})&&authenticatedText(chat,ordinal,text),check,"private_reaction_chooser_authenticated_message_and_other_selection_loaded");
        test.runOnMainSync(()->chat.scrollToLastMessage(true,false));Thread.sleep(650);
        chooser(test,chat,ordinal,check);row(test,chat,1,check);
        Thread.sleep(650);capture(test,"vyrn-test-private-reaction-picker.png");
        button(test,chat,android.content.DialogInterface.BUTTON_POSITIVE,check);
        await(test,()->matches(message(chat,ordinal),new String[]{"👍","❤️"},new int[]{1,1},new boolean[]{false,true})&&authenticatedText(chat,ordinal,text),check,"private_reaction_actual_menu_save_keeps_authenticated_text");
        check.verify(stored(account,peer,ordinal,new String[]{"👍","❤️"},new int[]{1,1},new boolean[]{false,true}),"private_reaction_native_sqlite_persists_own_selection");
        chooser(test,chat,ordinal,check);row(test,chat,2,check);
        button(test,chat,android.content.DialogInterface.BUTTON_NEGATIVE,check);
        await(test,()->picker(chat)==null&&matches(message(chat,ordinal),new String[]{"👍","❤️"},new int[]{1,1},new boolean[]{false,true}),check,"private_reaction_cancel_keeps_confirmed_ui_selection");
        check.verify(recipient.message(id).reactions.stream().noneMatch(r->r.emoji.equals("🔥")),"private_reaction_cancel_does_not_mutate_server");
        test.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
        await(test,()->LaunchActivity.getLastFragment()!=chat,check,"private_reaction_original_back_leaves_chat");
        ChatActivity reopened=SearchUiIntegration.openChat(test,peer,true,check);
        await(test,()->matches(message(reopened,ordinal),new String[]{"👍","❤️"},new int[]{1,1},new boolean[]{false,true})&&authenticatedText(reopened,ordinal,text),check,"private_reaction_reopened_native_chat_restores_confirmed_selection_and_text");
        test.runOnMainSync(()->reopened.scrollToLastMessage(true,false));Thread.sleep(650);
        chooser(test,reopened,ordinal,check);
        button(test,reopened,android.content.DialogInterface.BUTTON_NEUTRAL,check);
        await(test,()->matches(message(reopened,ordinal),new String[]{"👍"},new int[]{1},new boolean[]{false}),check,"private_reaction_actual_clear_preserves_other_participant");
        JSONObject after=(JSONObject)recipient.request("GET","/v1/messages/"+id,null,true);
        check.verify(after.getBoolean("encrypted")&&after.getInt("revision")==before.getInt("revision")&&after.getJSONObject("payload").toString().equals(before.getJSONObject("payload").toString()),"private_reaction_save_cancel_reopen_clear_preserve_ciphertext_and_revision");
        sender.request("DELETE",path,new JSONObject().put("emoji","👍"),true);
        await(test,()->matches(message(reopened,ordinal),new String[]{},new int[]{},new boolean[]{})&&authenticatedText(reopened,ordinal,text),check,"private_reaction_other_participant_clear_syncs_without_losing_text");
        test.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
        await(test,()->LaunchActivity.getLastFragment()!=reopened,check,"private_reaction_leaves_chat_before_offline_search_regression");
    }
    private static boolean authenticatedText(ChatActivity chat,int id,String text) {
        return chat.messages.stream().anyMatch(row->row.getId()==id&&row.messageText!=null&&text.contentEquals(row.messageText)&&!row.messageOwner.message.contains(text));
    }
}
