package xyz.eclipseofficial.vyrn.test;

import android.app.Activity;
import android.app.Instrumentation;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import xyz.eclipseofficial.vyrn.compat.VyrnBridge;
import xyz.eclipseofficial.vyrn.data.IdMap;
import xyz.eclipseofficial.vyrn.data.RestRepository;

/** Real original search controls, result count and navigation across a server page. */
final class SearchUiIntegration {
    private static ChatActivity current;
    static void run(Instrumentation test,Activity activity,JSONObject fixture,AuthUiIntegration.Check check) throws Exception {
        AuthUiIntegration.registration(test,activity,fixture,check);
        int account=UserConfig.selectedAccount;RestRepository repository=VyrnBridge.forAccount(account).repository;
        String query="VyrnSearch"+UUID.randomUUID().toString().substring(0,8);
        JSONObject group=(JSONObject)repository.request("POST","/v1/conversations",new JSONObject().put("kind","group").put("title","Search UI").put("members",new JSONArray()),true);
        String conversation=group.getString("id");ArrayList<Integer> expected=new ArrayList<>();
        for(int i=0;i<24;i++){
            JSONObject sent=(JSONObject)repository.request("POST","/v1/conversations/"+conversation+"/messages",new JSONObject().put("client_id",UUID.randomUUID().toString()).put("encrypted",false).put("payload",new JSONObject().put("text",query+" result "+i)),true);
            xyz.eclipseofficial.vyrn.domain.Models.Message row=repository.message(sent.getString("id"));
            try(IdMap ids=new IdMap(test.getTargetContext(),account)){expected.add(ids.message(row.id,row.ordinal));}
        }
        CompletableFuture<TLRPC.TL_messages_dialogs> dialogs=new CompletableFuture<>();
        ConnectionsManager.getInstance(account).sendRequest(new TLRPC.TL_messages_getDialogs(),(value,error)->{if(error==null)dialogs.complete((TLRPC.TL_messages_dialogs)value);else dialogs.completeExceptionally(new AssertionError("search_dialog_load"));});
        TLRPC.TL_messages_dialogs loaded=dialogs.get(30,TimeUnit.SECONDS);
        test.runOnMainSync(()->{MessagesController.getInstance(account).putUsers(loaded.users,false);MessagesController.getInstance(account).putChats(loaded.chats,false);});
        long local;try(IdMap ids=new IdMap(test.getTargetContext(),account)){local=ids.local("conversation",conversation);}
        TLRPC.TL_messages_search captured=request(local,query);CompletableFuture<TLRPC.messages_Messages> snapshot=new CompletableFuture<>();
        synchronized(repository.vault.guard()){
            ConnectionsManager.getInstance(account).sendRequest(captured,(value,error)->{if(error==null)snapshot.complete((TLRPC.messages_Messages)value);else snapshot.completeExceptionally(new AssertionError("search_snapshot_request"));});
            captured.q="mutated";captured.peer.chat_id=0;captured.limit=1;
        }
        TLRPC.messages_Messages result=snapshot.get(30,TimeUnit.SECONDS);
        check.verify(result.count==24&&result.messages.size()==21&&result.messages.get(0).id==expected.get(23),"search_native_request_captures_original_query_peer_limit");
        CompletableFuture<TLRPC.messages_Messages> reset=new CompletableFuture<>();ConnectionsManager.getInstance(account).sendRequest(request(local,""),(value,error)->{if(error==null)reset.complete((TLRPC.messages_Messages)value);else reset.completeExceptionally(new AssertionError("search_empty_reset"));});
        TLRPC.messages_Messages empty=reset.get(20,TimeUnit.SECONDS);check.verify(empty.count==0&&empty.messages.isEmpty(),"search_empty_input_resets_without_false_network_failure");
        TLRPC.TL_messages_search privateSearch=request(local,query);TLRPC.TL_inputPeerUser peer=new TLRPC.TL_inputPeerUser();peer.user_id=UserConfig.getInstance(account).getClientUserId();privateSearch.peer=peer;
        CompletableFuture<String> denied=new CompletableFuture<>();ConnectionsManager.getInstance(account).sendRequest(privateSearch,(value,error)->denied.complete(error==null?"":error.text));
        check.verify("VYRN_FEATURE_UNAVAILABLE".equals(denied.get(20,TimeUnit.SECONDS)),"search_self_dialog_rejected_before_server_search");
        verifyUi(test,local,false,query,expected,check);
    }
    static ChatActivity openChat(Instrumentation test,long local,boolean privatePeer,AuthUiIntegration.Check check) throws Exception {
        await(test,()->LaunchActivity.getLastFragment()!=null&&!LaunchActivity.getLastFragment().getParentLayout().checkTransitionAnimation(),check,"search_parent_transition_ready");
        Bundle args=new Bundle();args.putLong(privatePeer?"user_id":"chat_id",local);ChatActivity chat=new ChatActivity(args);current=chat;
        test.runOnMainSync(()->{if(!LaunchActivity.getLastFragment().presentFragment(chat))throw new AssertionError("search_chat_present");});
        await(test,()->chat.getFragmentView()!=null&&!chat.getParentLayout().checkTransitionAnimation(),check,"search_original_chat_ready");
        AuthUiIntegration.handlePrompts(test,LaunchActivity.instance,check);
        await(test,()->chat.messages.stream().anyMatch(row->row.getId()>0&&!row.isDateObject)&&chat.messages.stream().filter(row->row.getId()>0&&!row.isDateObject).noneMatch(org.telegram.messenger.MessageObject::canSetReaction),check,"native_chat_unwired_reaction_capability_disabled");
        return chat;
    }
    static void verifyUi(Instrumentation test,long local,boolean privatePeer,String query,ArrayList<Integer> expected,AuthUiIntegration.Check check) throws Exception {
        int account=UserConfig.selectedAccount;
        ChatActivity chat=openChat(test,local,privatePeer,check);
        if(privatePeer){
            await(test,()->!(Boolean)value(chat,"firstLoading")&&expected.stream().allMatch(id->chat.messages.stream().anyMatch(row->row.getId()==id)),check,"private_search_native_history_loaded_before_disconnect");
            await(test,()->VyrnBridge.privateTextReady(account,local),check,"private_search_native_device_ready");
            AuthUiIntegration.networkStep(test,LaunchActivity.instance,"offline-load",check);
        }
        try {
        View icon=(View)value(chat,"searchIconItem");
        if(!tap(test,icon)){
            check.verify(tap(test,(View)value(chat,"headerItem")),"search_original_header_menu_touched");
            Rect menu=new Rect();long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);boolean found=false;
            while(!found&&System.nanoTime()<end){AccessibilityNodeInfo root=test.getUiAutomation().getRootInActiveWindow();if(root!=null)for(AccessibilityNodeInfo node:root.findAccessibilityNodeInfosByText(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.Search))){if(node.getText()!=null&&node.getText().toString().equals(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.Search))){node.getBoundsInScreen(menu);found=!menu.isEmpty();break;}}if(!found)Thread.sleep(100);}
            check.verify(found&&AuthUiIntegration.touch(test,menu),"search_original_menu_item_touched");
        }else check.verify(true,"search_original_icon_touched");
        ActionBarMenuItem field=(ActionBarMenuItem)value(chat,"searchItem");
        await(test,()->field.getSearchField().hasFocus(),check,"search_original_text_field_focused");
        Thread.sleep(650); // Let the original search expansion's delayed IME show finish.
        boolean[] entered={false};test.runOnMainSync(()->{
            android.view.inputmethod.InputConnection input=field.getSearchField().onCreateInputConnection(new android.view.inputmethod.EditorInfo());
            entered[0]=input!=null&&input.commitText(query,1)&&query.contentEquals(field.getSearchField().getText());
        });
        check.verify(entered[0],"search_native_ime_commits_complete_query");
        // Upstream listens for a non-null ENTER/SEARCH KeyEvent, not a bare editor action.
        test.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_ENTER);
        await(test,()->query.contentEquals(field.getSearchField().getText())&&query.equals(org.telegram.messenger.MediaDataController.getInstance(account).getLastSearchQuery())&&(int)value(chat,"searchLastCount")==24&&(int)value(chat,"searchLastIndex")==0&&chat.highlightMessageId==expected.get(23),check,"search_original_count_and_newest_result");
        // Search expansion posts a delayed keyboard show. Close the actual IME after it settles.
        Thread.sleep(500);boolean[] keyboard={false};test.runOnMainSync(()->keyboard[0]=android.os.Build.VERSION.SDK_INT>=30&&chat.getFragmentView().getRootWindowInsets()!=null&&chat.getFragmentView().getRootWindowInsets().isVisible(android.view.WindowInsets.Type.ime()));
        if(keyboard[0])test.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
        else test.runOnMainSync(()->org.telegram.messenger.AndroidUtilities.hideKeyboard(field.getSearchField()));
        await(test,()->android.os.Build.VERSION.SDK_INT<30||chat.getFragmentView().getRootWindowInsets()!=null&&!chat.getFragmentView().getRootWindowInsets().isVisible(android.view.WindowInsets.Type.ime()),check,"search_keyboard_closed_before_navigation");
        check.verify((value(chat,"searchUserButton")==null||!((View)value(chat,"searchUserButton")).isEnabled())&&!((View)value(chat,"searchCalendarButton")).isEnabled(),"search_unsupported_sender_date_filters_disabled");
        for(int i=1;i<24;i++){
            View next=button(chat,chat.reversed?org.telegram.ui.Components.chat.layouts.ChatActivitySideControlsButtonsLayout.BUTTON_SEARCH_DOWN:org.telegram.ui.Components.chat.layouts.ChatActivitySideControlsButtonsLayout.BUTTON_SEARCH_UP);
            check.verify(tap(test,next),"search_next_control_touched_"+i);
            final int index=i;await(test,()->(int)value(chat,"searchLastIndex")==index&&(int)value(chat,"searchLastCount")==24&&chat.highlightMessageId==expected.get(23-index),check,"search_original_result_index_"+i);
            Thread.sleep(150);
        }
        check.verify(((org.telegram.ui.Components.AnimatedTextView)value(chat,"searchCountText")).getText().toString().contains("24"),"search_counter_rendered_after_crossing_server_page");
        check.verify(tap(test,button(chat,chat.reversed?org.telegram.ui.Components.chat.layouts.ChatActivitySideControlsButtonsLayout.BUTTON_SEARCH_UP:org.telegram.ui.Components.chat.layouts.ChatActivitySideControlsButtonsLayout.BUTTON_SEARCH_DOWN)),"search_previous_control_touched");
        await(test,()->(int)value(chat,"searchLastIndex")==22&&chat.highlightMessageId==expected.get(1),check,"search_previous_result_uses_native_navigation");
        if(privatePeer){
            check.verify(VyrnBridge.privateSearchLabel().contentEquals(field.getSearchField().getHint()),"private_search_scope_visible_in_field");
            await(test,()->{
                android.view.ViewGroup list=(android.view.ViewGroup)value(chat,"chatListView");
                for(int i=0;i<list.getChildCount();i++)if(list.getChildAt(i) instanceof org.telegram.ui.Cells.ChatMessageCell){
                    org.telegram.messenger.MessageObject object=((org.telegram.ui.Cells.ChatMessageCell)list.getChildAt(i)).getMessageObject();
                    if(object!=null&&object.getId()==chat.highlightMessageId&&object.messageText!=null&&object.messageText.toString().contains(query)&&!object.messageOwner.message.contains(query))return true;
                }
                return false;
            },check,"private_search_selected_result_decrypted_in_visible_native_cell");
            check.verify(!org.telegram.messenger.HashtagSearchController.getInstance(account).history.contains(query)&&!test.getTargetContext().getSharedPreferences("hashtag_search_history"+account,0).getAll().containsValue(query),"private_search_hashtag_not_saved_to_public_history");
        }
        String missing=query+"Absent";
        enter(test,field,missing,check,"search_no_match_query_entered");
        await(test,()->missing.equals(org.telegram.messenger.MediaDataController.getInstance(account).getLastSearchQuery())&&(int)value(chat,"searchLastCount")==0&&org.telegram.messenger.MediaDataController.getInstance(account).getFoundMessageObjects().isEmpty(),check,"search_no_match_clears_previous_results");
        await(test,()->org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.NoResult).contentEquals(((org.telegram.ui.Components.AnimatedTextView)value(chat,"searchCountText")).getText())&&!button(chat,org.telegram.ui.Components.chat.layouts.ChatActivitySideControlsButtonsLayout.BUTTON_SEARCH_UP).isEnabled()&&!button(chat,org.telegram.ui.Components.chat.layouts.ChatActivitySideControlsButtonsLayout.BUTTON_SEARCH_DOWN).isEnabled(),check,"search_no_match_visible_counter_and_navigation_disabled");
        enter(test,field,query,check,"search_after_no_match_query_entered");
        await(test,()->(int)value(chat,"searchLastCount")==24&&(int)value(chat,"searchLastIndex")==0&&chat.highlightMessageId==expected.get(23),check,"search_after_no_match_recovers_first_result");
        check.verify(tap(test,field.getSearchClearButton()),"search_original_clear_control_touched");
        await(test,()->field.getSearchField().length()==0&&"".equals(org.telegram.messenger.MediaDataController.getInstance(account).getLastSearchQuery())&&(int)value(chat,"searchLastCount")==0&&org.telegram.messenger.MediaDataController.getInstance(account).getFoundMessageObjects().isEmpty(),check,"search_clear_control_resets_without_enter");
        enter(test,field,query,check,"search_after_clear_query_entered");
        await(test,()->(int)value(chat,"searchLastCount")==24&&(int)value(chat,"searchLastIndex")==0&&chat.highlightMessageId==expected.get(23),check,"search_after_clear_recovers_first_result");
        if(privatePeer)check.verify(!org.telegram.messenger.HashtagSearchController.getInstance(account).history.contains(missing)&&!test.getTargetContext().getSharedPreferences("hashtag_search_history"+account,0).getAll().containsValue(missing),"private_search_no_match_query_not_in_public_history");
        } finally {if(privatePeer)AuthUiIntegration.networkStep(test,LaunchActivity.instance,"online-retry",check);}
    }
    private static void enter(Instrumentation test,ActionBarMenuItem field,String text,AuthUiIntegration.Check check,String name) throws Exception {
        boolean[] entered={false};test.runOnMainSync(()->{
            field.getSearchField().requestFocus();
            android.view.inputmethod.InputConnection input=field.getSearchField().onCreateInputConnection(new android.view.inputmethod.EditorInfo());
            entered[0]=input!=null&&input.setSelection(0,field.getSearchField().length())&&input.commitText(text,1)&&text.contentEquals(field.getSearchField().getText());
        });
        check.verify(entered[0],name);test.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_ENTER);
    }
    private static TLRPC.TL_messages_search request(long chat,String query){TLRPC.TL_messages_search value=new TLRPC.TL_messages_search();TLRPC.TL_inputPeerChat peer=new TLRPC.TL_inputPeerChat();peer.chat_id=chat;value.peer=peer;value.q=query;value.limit=21;value.filter=new TLRPC.TL_inputMessagesFilterEmpty();return value;}
    private static Object value(Object object,String name){try{java.lang.reflect.Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);}catch(Exception failure){throw new IllegalStateException(name,failure);}}
    private static View button(ChatActivity chat,int id){Object[] holders=(Object[])value(value(chat,"sideControlsButtonsLayout"),"buttonHolders");return (View)value(holders[id],"button");}
    private static boolean tap(Instrumentation test,View view) throws Exception {
        Rect bounds=new Rect(),previous=new Rect();boolean[] visible={false};long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);int stable=0;
        do{test.runOnMainSync(()->visible[0]=view!=null&&view.isEnabled()&&view.isShown()&&view.getAlpha()>.95f&&!view.isLayoutRequested()&&!view.getRootView().isLayoutRequested()&&view.getGlobalVisibleRect(bounds)&&!bounds.isEmpty());if(visible[0]&&bounds.equals(previous))stable++;else stable=0;if(stable>=2)return AuthUiIntegration.touch(test,bounds);previous.set(bounds);Thread.sleep(100);}while(System.nanoTime()<end);return false;
    }
    private static void await(Instrumentation test,BooleanSupplier condition,AuthUiIntegration.Check check,String name) throws Exception {android.util.Log.i("VyrnSearchTest",name);boolean[] ready={false};long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);while(!ready[0]&&System.nanoTime()<end){test.runOnMainSync(()->ready[0]=condition.getAsBoolean());if(!ready[0])Thread.sleep(100);}if(!ready[0]&&current!=null)test.runOnMainSync(()->{try{JSONObject diagnostic=new JSONObject().put("stage",name).put("count",value(current,"searchLastCount")).put("index",value(current,"searchLastIndex")).put("highlight",current.highlightMessageId).put("reversed",current.reversed);java.nio.file.Files.write(new java.io.File(test.getTargetContext().getFilesDir(),"vyrn-test-search-diagnostic.json").toPath(),diagnostic.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}catch(Exception ignored){}});check.verify(ready[0],name);}
}
