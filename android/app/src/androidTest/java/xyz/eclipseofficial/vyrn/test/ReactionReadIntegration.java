package xyz.eclipseofficial.vyrn.test;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.view.ViewGroup;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.tl.TL_update;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.Cells.ChatMessageCell;
import xyz.eclipseofficial.vyrn.compat.VyrnBridge;
import xyz.eclipseofficial.vyrn.data.IdMap;
import xyz.eclipseofficial.vyrn.data.RestRepository;
import xyz.eclipseofficial.vyrn.domain.Models;

/** Real native history/cells/SQLite and automatic WebSocket updates; no reaction selection UI claim. */
final class ReactionReadIntegration {
    static void run(Instrumentation test,Activity activity,JSONObject fixture,AuthUiIntegration.Check check) throws Exception {
        AuthUiIntegration.registration(test,activity,fixture,check);
        int account=UserConfig.selectedAccount;VyrnBridge bridge=VyrnBridge.forAccount(account);RestRepository repository=bridge.repository;
        RestRepository other=SignalNetworkIntegration.register(test.getTargetContext(),fixture,9181,"-reaction-reader");
        try{
            String group=((JSONObject)repository.request("POST","/v1/conversations",new JSONObject().put("kind","group").put("title","Reaction read model").put("members",new JSONArray().put(other.me().id)),true)).getString("id");
            String message=((JSONObject)other.request("POST","/v1/conversations/"+group+"/messages",new JSONObject().put("client_id",UUID.randomUUID().toString()).put("encrypted",false).put("payload",new JSONObject().put("text","Reaction read model")),true)).getString("id");
            String path="/v1/messages/"+message+"/reactions";
            other.request("PUT",path,new JSONObject().put("emoji","👍"),true);
            repository.request("PUT",path,new JSONObject().put("emoji","❤️"),true);
            Models.Message domain=repository.message(message);
            check.verify(domain.reactions.size()==2&&domain.reactions.stream().filter(row->row.mine).count()==1&&domain.reactions.stream().anyMatch(row->row.mine&&row.emoji.equals("❤️")&&row.count==1),"reaction_repository_maps_viewer_specific_selection_and_counts");
            JSONObject raw=(JSONObject)repository.request("GET","/v1/messages/"+message,null,true);
            java.lang.reflect.Method parse=RestRepository.class.getDeclaredMethod("message",JSONObject.class);parse.setAccessible(true);
            JSONArray[] invalid={new JSONArray().put(summary("👍",0,false)),new JSONArray().put(summary("👍",1,false)).put(summary("👍",1,true)),new JSONArray().put(summary("👍",1.5,false)),new JSONArray().put(summary("👍",1,true)).put(summary("❤️",1,true)).put(summary("🔥",1,true)).put(summary("🎉",1,true))};
            for(int i=0;i<invalid.length;i++){
                boolean denied=false;try{parse.invoke(repository,new JSONObject(raw.toString()).put("reactions",invalid[i]));}catch(java.lang.reflect.InvocationTargetException failure){denied=failure.getCause() instanceof SecurityException;}
                check.verify(denied,"reaction_malformed_summary_rejected_"+i);
            }
            CompletableFuture<TLRPC.TL_messages_dialogs> dialogs=new CompletableFuture<>();
            ConnectionsManager.getInstance(account).sendRequest(new TLRPC.TL_messages_getDialogs(),(value,error)->{if(error==null)dialogs.complete((TLRPC.TL_messages_dialogs)value);else dialogs.completeExceptionally(new AssertionError("reaction_dialogs"));});
            TLRPC.TL_messages_dialogs loaded=dialogs.get(30,TimeUnit.SECONDS);
            test.runOnMainSync(()->{MessagesController.getInstance(account).putUsers(loaded.users,false);MessagesController.getInstance(account).putChats(loaded.chats,false);});
            long local;try(IdMap ids=new IdMap(test.getTargetContext(),account)){local=ids.local("conversation",group);}
            await(test,()->LaunchActivity.getLastFragment()!=null&&!LaunchActivity.getLastFragment().getParentLayout().checkTransitionAnimation(),check,"reaction_parent_ready");
            Bundle args=new Bundle();args.putLong("chat_id",local);ChatActivity chat=new ChatActivity(args);
            test.runOnMainSync(()->{if(!LaunchActivity.getLastFragment().presentFragment(chat))throw new AssertionError("reaction_chat_present");});
            await(test,()->chat.getFragmentView()!=null&&!chat.getParentLayout().checkTransitionAnimation()&&matches(message(chat,domain.ordinal),new String[]{"👍","❤️"},new int[]{1,1},new boolean[]{false,true}),check,"reaction_native_history_has_counts_and_own_selection");
            AuthUiIntegration.handlePrompts(test,LaunchActivity.instance,check);
            await(test,()->{
                ViewGroup list=(ViewGroup)field(chat,"chatListView");
                for(int i=0;i<list.getChildCount();i++)if(list.getChildAt(i) instanceof ChatMessageCell){
                    ChatMessageCell cell=(ChatMessageCell)list.getChildAt(i);if(cell.getMessageObject()==null||cell.getMessageObject().getId()!=domain.ordinal)continue;
                    boolean heart=false,thumb=false;
                    for(var button:cell.reactionsLayoutInBubble.reactionButtons){
                        TLRPC.ReactionCount row=(TLRPC.ReactionCount)field(button,"reactionCount");
                        if(!(row.reaction instanceof TLRPC.TL_reactionEmoji)||button.imageReceiver.getStaticThumb()==null||button.realCount!=1)continue;
                        String emoji=((TLRPC.TL_reactionEmoji)row.reaction).emoticon;if(emoji.equals("❤️"))heart=true;if(emoji.equals("👍"))thumb=true;
                    }
                    if(cell.isShown()&&cell.getHeight()>0&&heart&&thumb&&cell.reactionsLayoutInBubble.reactionButtons.size()==2)return true;
                }
                return false;
            },check,"reaction_original_visible_cell_has_two_reaction_glyphs");
            capture(test);
            check.verify(stored(account,-local,domain.ordinal,new String[]{"👍","❤️"},new int[]{1,1},new boolean[]{false,true}),"reaction_actual_native_sqlite_serializes_selection_flags");
            chooser(test,chat,domain.ordinal,check);
            capture(test);
            row(test,chat,1,check);row(test,chat,2,check);row(test,chat,3,check);row(test,chat,4,check);row(test,chat,5,check);
            await(test,()->!picker(chat).getListView().isItemChecked(5),check,"reaction_picker_enforces_three_selections");
            row(test,chat,3,check);row(test,chat,4,check);
            button(test,chat,android.content.DialogInterface.BUTTON_POSITIVE,check);
            await(test,()->matches(message(chat,domain.ordinal),new String[]{"👍","🔥"},new int[]{1,1},new boolean[]{false,true}),check,"reaction_original_menu_picker_saves_confirmed_selection");
            chooser(test,chat,domain.ordinal,check);
            button(test,chat,android.content.DialogInterface.BUTTON_NEUTRAL,check);
            await(test,()->matches(message(chat,domain.ordinal),new String[]{"👍"},new int[]{1},new boolean[]{false}),check,"reaction_picker_clear_preserves_other_participant");
            chooser(test,chat,domain.ordinal,check);row(test,chat,2,check);
            button(test,chat,android.content.DialogInterface.BUTTON_POSITIVE,check);
            await(test,()->matches(message(chat,domain.ordinal),new String[]{"👍","🔥"},new int[]{1,1},new boolean[]{false,true}),check,"reaction_picker_reopens_after_clear");
            chooser(test,chat,domain.ordinal,check);
            row(test,chat,2,check);row(test,chat,1,check);
            try {
                AuthUiIntegration.networkStep(test,LaunchActivity.instance,"offline-load",check);
                button(test,chat,android.content.DialogInterface.BUTTON_POSITIVE,check);
                await(test,()->picker(chat)!=null&&picker(chat).getButton(android.content.DialogInterface.BUTTON_POSITIVE)!=null&&picker(chat).getButton(android.content.DialogInterface.BUTTON_POSITIVE).isShown(),check,"reaction_offline_failure_offers_reconciliation");
                test.runOnMainSync(()->check.verify(matches(message(chat,domain.ordinal),new String[]{"👍","🔥"},new int[]{1,1},new boolean[]{false,true}),"reaction_failed_write_does_not_change_confirmed_counts"));
                AuthUiIntegration.networkStep(test,LaunchActivity.instance,"online-retry",check);
                button(test,chat,android.content.DialogInterface.BUTTON_POSITIVE,check);
                await(test,()->picker(chat)!=null&&picker(chat).getButton(android.content.DialogInterface.BUTTON_POSITIVE)!=null&&picker(chat).getButton(android.content.DialogInterface.BUTTON_POSITIVE).isShown(),check,"reaction_retry_reads_server_before_confirmation");
                check.verify(repository.message(message).reactions.stream().anyMatch(r->r.mine&&r.emoji.equals("🔥")),"reaction_reconciliation_does_not_replay_unconfirmed_write");
                button(test,chat,android.content.DialogInterface.BUTTON_POSITIVE,check);
                await(test,()->matches(message(chat,domain.ordinal),new String[]{"👍","❤️"},new int[]{1,1},new boolean[]{false,true}),check,"reaction_explicit_retry_saves_original_choice");
            } finally {AuthUiIntegration.networkStep(test,LaunchActivity.instance,"online-confirm",check);}
            TLRPC.TL_messages_sendReaction captured=selection(local,domain.ordinal,"❤️","🔥");
            CompletableFuture<TLRPC.Updates> accepted=new CompletableFuture<>();
            synchronized(repository.vault.guard()){
                ConnectionsManager.getInstance(account).sendRequest(captured,(value,error)->{if(error==null)accepted.complete((TLRPC.Updates)value);else accepted.completeExceptionally(new AssertionError("native_reaction_snapshot"));});
                captured.peer.chat_id=0;captured.msg_id=0;((TLRPC.TL_reactionEmoji)captured.reaction.get(0)).emoticon="mutated";captured.reaction.clear();captured.flags=0;
            }
            TLRPC.Updates snapshot=accepted.get(30,TimeUnit.SECONDS);
            check.verify(snapshot.updates.size()==1&&snapshot.updates.get(0) instanceof TL_update.TL_updateMessageReactions&&((TL_update.TL_updateMessageReactions)snapshot.updates.get(0)).msg_id==domain.ordinal&&((TL_update.TL_updateMessageReactions)snapshot.updates.get(0)).peer.chat_id==local,"reaction_native_selection_captures_original_peer_id_and_values");
            apply(test,account,snapshot);
            await(test,()->matches(message(chat,domain.ordinal),new String[]{"👍","❤️","🔥"},new int[]{1,1,1},new boolean[]{false,true,true}),check,"reaction_native_selection_update_reaches_original_chat");
            TLRPC.TL_messages_getMessagesReactions capturedQuery=new TLRPC.TL_messages_getMessagesReactions();capturedQuery.peer=selection(local,domain.ordinal).peer;capturedQuery.id.add(domain.ordinal);
            CompletableFuture<TLRPC.Updates> queried=new CompletableFuture<>();
            synchronized(repository.vault.guard()){
                ConnectionsManager.getInstance(account).sendRequest(capturedQuery,(value,error)->{if(error==null)queried.complete((TLRPC.Updates)value);else queried.completeExceptionally(new AssertionError("native_reaction_query_snapshot"));});
                capturedQuery.id.clear();capturedQuery.peer.chat_id=0;
            }
            TLRPC.Updates metadata=queried.get(30,TimeUnit.SECONDS);
            check.verify(metadata.updates.size()==1&&metadata.updates.get(0) instanceof TL_update.TL_updateMessageReactions&&((TL_update.TL_updateMessageReactions)metadata.updates.get(0)).reactions.results.size()==3,"reaction_native_metadata_query_captures_ids_and_peer");
            TLRPC.TL_messages_sendReaction custom=selection(local,domain.ordinal);custom.flags=1;custom.reaction.add(new TLRPC.TL_reactionCustomEmoji());
            check.verify("VYRN_FEATURE_UNAVAILABLE".equals(error(account,custom)),"reaction_custom_emoji_rejected_before_mutation");
            TLRPC.TL_messages_sendReaction invalidFlags=selection(local,domain.ordinal,"❤️");invalidFlags.flags|=8;
            check.verify("VYRN_FEATURE_UNAVAILABLE".equals(error(account,invalidFlags)),"reaction_unknown_options_rejected_before_mutation");
            String wrongGroup=((JSONObject)repository.request("POST","/v1/conversations",new JSONObject().put("kind","group").put("title","Wrong reaction context").put("members",new JSONArray()),true)).getString("id");
            long wrong;try(IdMap ids=new IdMap(test.getTargetContext(),account)){wrong=ids.local("conversation",wrongGroup);}
            check.verify(!error(account,selection(wrong,domain.ordinal,"🎉")).isEmpty(),"reaction_message_from_different_peer_rejected");
            Models.Message unchanged=repository.message(message);
            check.verify(unchanged.reactions.size()==3&&unchanged.reactions.stream().noneMatch(row->row.emoji.equals("🎉")),"reaction_rejected_native_requests_preserve_server_selection");
            apply(test,account,request(account,selection(local,domain.ordinal,"❤️")));
            await(test,()->matches(message(chat,domain.ordinal),new String[]{"👍","❤️"},new int[]{1,1},new boolean[]{false,true}),check,"reaction_native_complete_selection_removes_old_own_value");
            await(test,()->VyrnBridge.connectionState(account)==ConnectionsManager.ConnectionStateConnected,check,"reaction_automatic_stream_connected");
            long cursor=bridge.syncCursor();
            other.request("PUT",path,new JSONObject().put("emoji","❤️"),true);
            await(test,()->matches(message(chat,domain.ordinal),new String[]{"👍","❤️"},new int[]{1,2},new boolean[]{false,true}),check,"reaction_other_account_update_reaches_native_chat");
            other.request("DELETE",path,new JSONObject().put("emoji","👍"),true);
            await(test,()->matches(message(chat,domain.ordinal),new String[]{"❤️"},new int[]{2},new boolean[]{true}),check,"reaction_other_account_removal_clears_old_native_count");
            check.verify(stored(account,-local,domain.ordinal,new String[]{"❤️"},new int[]{2},new boolean[]{true}),"reaction_realtime_change_persisted_in_native_sqlite");
            apply(test,account,request(account,selection(local,domain.ordinal)));
            await(test,()->matches(message(chat,domain.ordinal),new String[]{"❤️"},new int[]{1},new boolean[]{false}),check,"reaction_native_empty_selection_preserves_other_account");
            other.request("DELETE",path,new JSONObject().put("emoji","❤️"),true);
            await(test,()->matches(message(chat,domain.ordinal),new String[]{},new int[]{},new boolean[]{}),check,"reaction_empty_server_summary_clears_native_reactions");
            check.verify(stored(account,-local,domain.ordinal,new String[]{},new int[]{},new boolean[]{}),"reaction_empty_summary_persisted_in_native_sqlite");
            check.verify(bridge.syncCursor()>cursor&&bridge.syncDead()==0,"reaction_updates_acknowledged_without_dead_events");
            test.runOnMainSync(()->{TLRPC.Message current=message(chat,domain.ordinal);check.verify(current!=null&&current.message.equals("Reaction read model")&&current.edit_date==0,"reaction_metadata_updates_preserve_original_text_and_edit_date");});
            other.request("DELETE","/v1/messages/"+message,null,true);
            TLRPC.TL_messages_getMessagesReactions deleted=new TLRPC.TL_messages_getMessagesReactions();deleted.peer=selection(local,domain.ordinal).peer;deleted.id.add(domain.ordinal);
            TLRPC.Updates tombstone=request(account,deleted);
            check.verify(tombstone.updates.size()==1&&tombstone.updates.get(0) instanceof TL_update.TL_updateDeleteMessages&&((TL_update.TL_updateDeleteMessages)tombstone.updates.get(0)).messages.contains(domain.ordinal),"reaction_native_metadata_read_learns_deleted_message");
        }finally{try{other.request("POST","/v1/auth/logout",new JSONObject(),true);}finally{other.vault.clear();}}
    }
    private static TLRPC.TL_messages_sendReaction selection(long chat,int id,String... values){TLRPC.TL_messages_sendReaction request=new TLRPC.TL_messages_sendReaction();request.peer=new TLRPC.TL_inputPeerChat();request.peer.chat_id=chat;request.msg_id=id;for(String value:values){TLRPC.TL_reactionEmoji reaction=new TLRPC.TL_reactionEmoji();reaction.emoticon=value;request.reaction.add(reaction);request.flags|=1;}return request;}
    static TLRPC.Updates request(int account,TLObject request) throws Exception {CompletableFuture<TLRPC.Updates> done=new CompletableFuture<>();ConnectionsManager.getInstance(account).sendRequest(request,(value,error)->{if(error==null)done.complete((TLRPC.Updates)value);else done.completeExceptionally(new AssertionError("native_reaction_request"));});return done.get(30,TimeUnit.SECONDS);}
    private static String error(int account,TLObject request) throws Exception {CompletableFuture<String> done=new CompletableFuture<>();ConnectionsManager.getInstance(account).sendRequest(request,(value,error)->done.complete(error==null?"":error.text));return done.get(30,TimeUnit.SECONDS);}
    private static android.app.AlertDialog picker(ChatActivity chat) {
        Object dialog=field(chat,"visibleDialog");return dialog instanceof android.app.AlertDialog?(android.app.AlertDialog)dialog:null;
    }
    private static void chooser(Instrumentation test,ChatActivity chat,int id,AuthUiIntegration.Check check) throws Exception {
        android.graphics.Rect bounds=new android.graphics.Rect();
        await(test,()->{
            ViewGroup list=(ViewGroup)field(chat,"chatListView");
            for(int i=0;i<list.getChildCount();i++)if(list.getChildAt(i) instanceof ChatMessageCell){
                ChatMessageCell cell=(ChatMessageCell)list.getChildAt(i);
                if(cell.getMessageObject()!=null&&cell.getMessageObject().getId()==id&&cell.isShown()){
                    int[] location=new int[2];cell.getLocationOnScreen(location);
                    int x=location[0]+cell.getTextX()+org.telegram.messenger.AndroidUtilities.dp(16),y=location[1]+cell.getTextY()+org.telegram.messenger.AndroidUtilities.dp(8);
                    bounds.set(x-4,y-4,x+4,y+4);
                    return !bounds.isEmpty();
                }
            }return false;
        },check,"reaction_menu_message_visible");
        check.verify(AuthUiIntegration.touch(test,bounds),"reaction_message_tapped_for_original_menu");
        AuthUiIntegration.clickWindow(test,LaunchActivity.instance,xyz.eclipseofficial.vyrn.compat.ReactionUi.label(),check);
        await(test,()->picker(chat)!=null&&picker(chat).getListView()!=null&&picker(chat).getListView().isShown(),check,"reaction_picker_opened_from_original_message_menu");
    }
    private static void row(Instrumentation test,ChatActivity chat,int index,AuthUiIntegration.Check check) throws Exception {
        android.graphics.Rect bounds=new android.graphics.Rect();
        await(test,()->{android.app.AlertDialog dialog=picker(chat);if(dialog==null||dialog.getListView()==null)return false;android.widget.ListView list=dialog.getListView();android.view.View row=list.getChildAt(index-list.getFirstVisiblePosition());return screenBounds(row,bounds);},check,"reaction_picker_row_visible_"+index);
        check.verify(AuthUiIntegration.touch(test,bounds),"reaction_picker_row_touched_"+index);test.waitForIdleSync();Thread.sleep(250);
        test.runOnMainSync(()->{try{org.json.JSONObject diagnostic=new org.json.JSONObject().put("row",index).put("bounds",bounds.toShortString()).put("checked",picker(chat).getListView().getCheckedItemPositions().toString());java.nio.file.Files.write(new java.io.File(test.getTargetContext().getFilesDir(),"vyrn-test-reaction-touches.jsonl").toPath(),(diagnostic.toString()+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8),java.nio.file.StandardOpenOption.CREATE,java.nio.file.StandardOpenOption.APPEND);}catch(Exception failure){throw new IllegalStateException(failure);}});
    }
    private static void button(Instrumentation test,ChatActivity chat,int which,AuthUiIntegration.Check check) throws Exception {
        android.graphics.Rect bounds=new android.graphics.Rect();
        await(test,()->picker(chat)!=null&&screenBounds(picker(chat).getButton(which),bounds),check,"reaction_dialog_button_visible");
        check.verify(AuthUiIntegration.touch(test,bounds),"reaction_dialog_button_touched");test.waitForIdleSync();
    }
    private static boolean screenBounds(android.view.View view,android.graphics.Rect bounds) {
        if(view==null||!view.isShown()||!view.isEnabled()||view.isLayoutRequested()||!view.getGlobalVisibleRect(bounds))return false;
        int[] location=new int[2];view.getLocationOnScreen(location);bounds.set(location[0],location[1],location[0]+view.getWidth(),location[1]+view.getHeight());return !bounds.isEmpty();
    }
    static void apply(Instrumentation test,int account,TLRPC.Updates updates){test.runOnMainSync(()->MessagesController.getInstance(account).processUpdates(updates,false));}
    private static JSONObject summary(String emoji,Number count,boolean mine) throws Exception {return new JSONObject().put("emoji",emoji).put("count",count).put("mine",mine);}
    private static TLRPC.Message message(ChatActivity chat,int id){for(var value:chat.messages)if(value.getId()==id)return value.messageOwner;return null;}
    private static boolean matches(TLRPC.Message message,String[] emojis,int[] counts,boolean[] mine){
        if(message==null||message.reactions==null||message.reactions.results.size()!=emojis.length)return false;
        for(int i=0;i<emojis.length;i++){boolean found=false;for(var row:message.reactions.results)if(row.reaction instanceof TLRPC.TL_reactionEmoji&&emojis[i].equals(((TLRPC.TL_reactionEmoji)row.reaction).emoticon)&&row.count==counts[i]&&row.chosen==mine[i]&&((row.flags&1)!=0)==mine[i])found=true;if(!found)return false;}return true;
    }
    private static boolean stored(int account,long dialog,int message,String[] emojis,int[] counts,boolean[] mine) throws Exception {
        MessagesStorage storage=MessagesStorage.getInstance(account);CompletableFuture<Boolean> result=new CompletableFuture<>();
        storage.getStorageQueue().postRunnable(()->{try{
            org.telegram.SQLite.SQLiteCursor cursor=storage.getDatabase().queryFinalized("SELECT data FROM messages_v2 WHERE uid="+dialog+" AND mid="+message);
            try{if(!cursor.next()){result.complete(false);return;}var data=cursor.byteBufferValue(0);try{result.complete(matches(TLRPC.Message.TLdeserialize(data,data.readInt32(true),true),emojis,counts,mine));}finally{data.reuse();}}finally{cursor.dispose();}
        }catch(Throwable failure){result.completeExceptionally(failure);}});return result.get(20,TimeUnit.SECONDS);
    }
    private static Object field(Object object,String name){
        for(Class<?> type=object.getClass();type!=null;type=type.getSuperclass())try{var field=type.getDeclaredField(name);field.setAccessible(true);return field.get(object);}catch(NoSuchFieldException inherited){/* Search the original button's parent class. */}catch(Exception failure){throw new IllegalStateException(name,failure);}
        throw new IllegalStateException(name);
    }
    private static void await(Instrumentation test,BooleanSupplier condition,AuthUiIntegration.Check check,String name) throws Exception {
        boolean[] ready={false};RuntimeException[] failure={null};long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);while(!ready[0]&&System.nanoTime()<end){test.runOnMainSync(()->{try{ready[0]=condition.getAsBoolean();}catch(RuntimeException problem){failure[0]=problem;}});if(failure[0]!=null){capture(test);throw failure[0];}if(!ready[0])Thread.sleep(100);}if(!ready[0])capture(test);check.verify(ready[0],name);
    }
    private static void capture(Instrumentation test) throws Exception {var bitmap=test.getUiAutomation().takeScreenshot();if(bitmap!=null)try(var output=new java.io.FileOutputStream(new java.io.File(test.getTargetContext().getFilesDir(),"vyrn-test-reaction-read.png"))){bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output);}finally{bitmap.recycle();}}
}
