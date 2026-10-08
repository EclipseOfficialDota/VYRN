package xyz.eclipseofficial.vyrn.test;

import android.app.Activity;
import android.app.Instrumentation;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.Cells.ChatMessageCell;
import org.telegram.ui.Components.ChatActivityEnterView;
import xyz.eclipseofficial.vyrn.data.IdMap;
import xyz.eclipseofficial.vyrn.data.RestRepository;
import xyz.eclipseofficial.vyrn.data.TokenVault;

/** Real composer/bubble checks on two separate native account0 clients. Group setup is a REST fixture. */
final class ChatUiIntegration {
    static void run(Instrumentation instrumentation,Activity activity,JSONObject fixture,String phase,AuthUiIntegration.Check verification) throws Exception {
        java.util.ArrayList<String> checkpoints=new java.util.ArrayList<>();
        AuthUiIntegration.Check check=(condition,name)->{verification.verify(condition,name);checkpoints.add(name);};
        int account=UserConfig.selectedAccount;
        check.verify(account==0&&UserConfig.getInstance(account).isClientActivated(),"chat_native_account0_active");
        RestRepository repository=new RestRepository(new TokenVault(instrumentation.getTargetContext(),account));
        boolean offlineReopen=phase.equals("chat-offline-reopen")||phase.equals("chat-failed-reopen");
        String user=offlineReopen?repository.vault.load().getString("user_id"):repository.me().id;
        if(phase.equals("chat-identity")){write(instrumentation,new JSONObject().put("user",user));return;}
        if(phase.equals("chat-create")){
            String peer=fixture.getString("peer_user");check.verify(!user.equals(peer),"chat_distinct_accounts");
            String group=repository.createGroup(fixture.getString("title"),java.util.Collections.singletonList(peer),0).id;
            check.verify(repository.members(group).size()==2,"chat_fixture_two_members");write(instrumentation,new JSONObject().put("group",group));return;
        }
        String group=fixture.getString("group"),text=fixture.getString("text");
        if(phase.equals("chat-message-identity")){
            java.util.List<xyz.eclipseofficial.vyrn.domain.Models.Message> live=repository.liveMessages(group,null,100);
            check.verify(live.stream().filter(message->text.equals(message.text)).count()==1,"mutation_original_unique_server_message");
            xyz.eclipseofficial.vyrn.domain.Models.Message original=live.stream().filter(message->text.equals(message.text)).findFirst().orElseThrow(AssertionError::new);
            check.verify(user.equals(original.sender)&&!original.deleted,"mutation_original_owned_and_live");
            write(instrumentation,new JSONObject().put("message_id",original.id).put("original_ordinal",original.ordinal).put("original_revision",original.revision));return;
        }
        long local;try(IdMap map=new IdMap(instrumentation.getTargetContext(),account)){local=map.local("conversation",group);}
        await(()->!AuthUiIntegration.handlePrompts(instrumentation,activity,check),check,"chat_startup_permissions_handled");
        if(!offlineReopen){
        java.util.concurrent.CompletableFuture<org.telegram.tgnet.TLObject> loaded=new java.util.concurrent.CompletableFuture<>();
        org.telegram.tgnet.ConnectionsManager.getInstance(account).sendRequest(new org.telegram.tgnet.TLRPC.TL_messages_getDialogs(),(value,error)->{if(error!=null)loaded.completeExceptionally(new AssertionError());else loaded.complete(value);});
        org.telegram.tgnet.TLRPC.TL_messages_dialogs dialogs=(org.telegram.tgnet.TLRPC.TL_messages_dialogs)loaded.get(30,java.util.concurrent.TimeUnit.SECONDS);
        instrumentation.runOnMainSync(()->{MessagesController.getInstance(account).putChats(dialogs.chats,false);MessagesController.getInstance(account).putUsers(dialogs.users,false);});
        }
        await(()->{boolean[] ready={false};instrumentation.runOnMainSync(()->ready[0]=MessagesController.getInstance(account).getChat(local)!=null);return ready[0];},check,"chat_native_group_loaded");
        instrumentation.runOnMainSync(()->{Bundle args=new Bundle();args.putLong("chat_id",local);LaunchActivity.getLastFragment().presentFragment(new ChatActivity(args));});
        await(()->{if(AuthUiIntegration.handlePrompts(instrumentation,activity,check))return false;boolean[] ready={false};instrumentation.runOnMainSync(()->ready[0]=LaunchActivity.getLastFragment() instanceof ChatActivity&&!LaunchActivity.getLastFragment().getParentLayout().isTransitionAnimationInProgress());return ready[0];},check,"chat_native_screen_ready");
        instrumentation.runOnMainSync(()->check.verify(org.telegram.messenger.ChatObject.canSendPlain(MessagesController.getInstance(account).getChat(local)),"chat_member_can_send_plain"));
        if(!offlineReopen)await(()->{boolean[] ready={false};instrumentation.runOnMainSync(()->{try{java.lang.reflect.Field field=ChatActivity.class.getDeclaredField("firstLoading");field.setAccessible(true);ready[0]=!field.getBoolean(LaunchActivity.getLastFragment());}catch(Exception failure){throw new AssertionError("chat_loading_state_unavailable");}});return ready[0];},check,"chat_initial_history_complete");
        if(offlineReopen){pending(instrumentation,activity,fixture,repository,user,check,true);return;}
        if(phase.equals("chat-delete")||phase.equals("chat-watch-delete")||phase.equals("chat-deleted-reopen")){delete(instrumentation,activity,fixture,repository,phase,check,checkpoints);return;}
        if(phase.equals("chat-edit")||phase.equals("chat-watch-edit")){edit(instrumentation,activity,fixture,repository,phase.equals("chat-edit"),check,checkpoints);return;}
        if(phase.equals("chat-edited-reopen")){editedReopen(instrumentation,activity,fixture,repository,user,check);return;}
        if(phase.equals("chat-receive"))write(instrumentation,new JSONObject().put("ready",true));
        boolean sending=phase.equals("chat-send")||phase.equals("chat-offline-send")||phase.equals("chat-failed-send")||phase.equals("chat-unknown-send");
        if(phase.equals("chat-offline-send")||phase.equals("chat-failed-send"))AuthUiIntegration.networkStep(instrumentation,activity,"offline-send",check);
        if(sending){
            ChatActivityEnterView[] composer={null};
            instrumentation.runOnMainSync(()->{try{java.lang.reflect.Field field=ChatActivity.class.getDeclaredField("chatActivityEnterView");field.setAccessible(true);composer[0]=(ChatActivityEnterView)field.get(LaunchActivity.getLastFragment());}catch(Exception failure){throw new AssertionError("composer_unavailable");}composer[0].getEditField().setText(text);});
            Rect bounds=new Rect();
            Rect previous=new Rect();long[] stableSince={0};
            await(()->{boolean[] ready={false};instrumentation.runOnMainSync(()->{View button=composer[0].getSendButtonInternal();ready[0]=button.isShown()&&button.isEnabled()&&button.getAlpha()>=0.99f&&!button.isLayoutRequested()&&button.getGlobalVisibleRect(bounds);});long now=android.os.SystemClock.uptimeMillis();if(!ready[0]||!bounds.equals(previous)){previous.set(bounds);stableSince[0]=now;return false;}return now-stableSince[0]>=600;},check,"chat_send_button_visible_and_stable");
            instrumentation.runOnMainSync(()->check.verify(composer[0].getSendButtonInternal().performClick(),"chat_native_send_button_clicked"));
            await(()->{boolean[] empty={false};instrumentation.runOnMainSync(()->empty[0]=composer[0].getEditField().length()==0);return empty[0];},check,"chat_composer_cleared");
        }else if(!phase.equals("chat-receive")&&!phase.equals("chat-recover")&&!phase.equals("chat-recovered-reopen")&&!phase.equals("chat-manual-retry")){throw new IllegalArgumentException();}
        if(phase.equals("chat-offline-send")){pending(instrumentation,activity,fixture,repository,user,check,false);return;}
        if(phase.equals("chat-unknown-send")){
            pending(instrumentation,activity,fixture,repository,user,check,false);
            java.nio.file.Path statePath=instrumentation.getTargetContext().getFilesDir().toPath().resolve("vyrn-test-chat-state.json");
            JSONObject state=new JSONObject(new String(Files.readAllBytes(statePath),StandardCharsets.UTF_8));
            try(xyz.eclipseofficial.vyrn.data.OutboxStore store=new xyz.eclipseofficial.vyrn.data.OutboxStore(instrumentation.getTargetContext(),account)){check.verify(store.get(user,state.getString("client_id")).state==xyz.eclipseofficial.vyrn.data.OutboxStore.SENDING,"interrupted_send_original_queue_in_flight");}
            state.put("interrupted_checks",new org.json.JSONArray(checkpoints));write(instrumentation,state);
            Thread.sleep(200000);throw new AssertionError("Host did not interrupt the held send");
        }
        if(phase.equals("chat-failed-send")){
            pending(instrumentation,activity,fixture,repository,user,check,false);
            await(()->{boolean[] failed={false};instrumentation.runOnMainSync(()->{ChatMessageCell value=cell(activity.getWindow().getDecorView(),text);failed[0]=value!=null&&value.getMessageObject().getId()<0&&value.getMessageObject().isSendError();});return failed[0];},check,"retry_real_network_attempts_exhausted_native_error",200);
            pending(instrumentation,activity,new JSONObject(fixture.toString()).put("expect_failed",true),repository,user,check,false);return;
        }
        if(phase.equals("chat-manual-retry")){
            await(()->{boolean[] failed={false};instrumentation.runOnMainSync(()->{ChatMessageCell value=cell(activity.getWindow().getDecorView(),text);failed[0]=value!=null&&value.getMessageObject().getId()<0&&value.getMessageObject().isSendError();});return failed[0];},check,"retry_original_failed_bubble_visible");
            try(xyz.eclipseofficial.vyrn.data.OutboxStore store=new xyz.eclipseofficial.vyrn.data.OutboxStore(instrumentation.getTargetContext(),account)){xyz.eclipseofficial.vyrn.data.OutboxStore.Entry entry=store.get(user,fixture.getString("client_id"));check.verify(entry!=null&&entry.state==xyz.eclipseofficial.vyrn.data.OutboxStore.FAILED&&entry.attempts==8,"retry_original_durable_entry_failed");}
            instrumentation.runOnMainSync(()->cell(activity.getWindow().getDecorView(),text).performAccessibilityAction(org.telegram.messenger.R.id.acc_action_msg_options,new Bundle()));
            AuthUiIntegration.clickWindow(instrumentation,activity,org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.Retry),check);
        }
        boolean outgoing=!phase.equals("chat-receive");
        try{await(()->{boolean[] shown={false};instrumentation.runOnMainSync(()->shown[0]=bubble(activity.getWindow().getDecorView(),text,outgoing));return shown[0];},check,outgoing?"chat_sender_confirmed_bubble":"chat_receiver_visible_bubble");}
        catch(AssertionError failure){
            JSONObject diagnostic=new JSONObject();
            int[] size={0};long[] dialog={0};java.util.ArrayList<int[]> matched=new java.util.ArrayList<>();
            instrumentation.runOnMainSync(()->{ChatActivity chat=(ChatActivity)LaunchActivity.getLastFragment();size[0]=chat.messages.size();dialog[0]=chat.getDialogId();for(org.telegram.messenger.MessageObject message:chat.messages)if(text.equals(message.messageOwner.message))matched.add(new int[]{message.getId(),message.messageOwner.send_state,message.isOutOwner()?1:0});});
            org.json.JSONArray states=new org.json.JSONArray();for(int[] message:matched)states.put(new JSONObject().put("id",message[0]).put("state",message[1]).put("outgoing",message[2]==1));diagnostic.put("native_messages",size[0]).put("matching_native_messages",states).put("native_dialog",dialog[0]);
            try{diagnostic.put("server_matches",repository.liveMessages(group,null,100).stream().filter(message->text.equals(message.text)).count());}catch(Exception unavailable){diagnostic.put("server_probe","unavailable");}
            write(instrumentation,diagnostic);throw failure;
        }
        long count=repository.liveMessages(group,null,100).stream().filter(message->text.equals(message.text)).count();
        check.verify(count==1,"chat_server_exactly_one_message");
        if(phase.equals("chat-recover")||phase.equals("chat-recovered-reopen")||phase.equals("chat-manual-retry")){
            instrumentation.runOnMainSync(()->check.verify(((ChatActivity)LaunchActivity.getLastFragment()).messages.stream().filter(message->text.equals(message.messageOwner.message)).count()==1,"offline_recovery_exactly_one_native_message"));
            String client=fixture.getString("client_id");try(xyz.eclipseofficial.vyrn.data.OutboxStore store=new xyz.eclipseofficial.vyrn.data.OutboxStore(instrumentation.getTargetContext(),account)){xyz.eclipseofficial.vyrn.data.OutboxStore.Entry entry=store.get(user,client);check.verify(entry!=null&&entry.state==xyz.eclipseofficial.vyrn.data.OutboxStore.SENT,"offline_recovery_original_queue_entry_sent");check.verify(repository.liveMessages(group,null,100).stream().anyMatch(message->text.equals(message.text)&&message.id.equals(entry.server)),"offline_recovery_server_identity_matches_queue");if(fixture.has("server_id"))check.verify(entry.server.equals(fixture.getString("server_id")),"interrupted_send_reuses_pre_restart_server_commit");}
        }
    }
    private static void pending(Instrumentation instrumentation,Activity activity,JSONObject fixture,RestRepository repository,String user,AuthUiIntegration.Check check,boolean reopen) throws Exception {
        String text=fixture.getString("text"),group=fixture.getString("group");
        boolean failed=fixture.optBoolean("expect_failed");
        await(()->{boolean[] shown={false};instrumentation.runOnMainSync(()->{ChatMessageCell value=cell(activity.getWindow().getDecorView(),text);shown[0]=value!=null&&value.getMessageObject().getId()<0&&(failed?value.getMessageObject().isSendError():value.getMessageObject().messageOwner.send_state!=0);});return shown[0];},check,reopen?(failed?"retry_failed_bubble_survives_process_restart":"offline_pending_bubble_survives_process_restart"):(failed?"retry_failed_bubble_visible":"offline_native_pending_bubble_visible"));
        try(xyz.eclipseofficial.vyrn.data.OutboxStore store=new xyz.eclipseofficial.vyrn.data.OutboxStore(instrumentation.getTargetContext(),UserConfig.selectedAccount)){
            await(()->{try(android.database.Cursor cursor=store.getReadableDatabase().rawQuery("SELECT count(*) FROM outbox WHERE user_id=? AND conversation_id=? AND body=?",new String[]{user,group,text})){return cursor.moveToFirst()&&cursor.getInt(0)==1;}},check,"offline_pending_persisted");
            String client;try(android.database.Cursor cursor=store.getReadableDatabase().rawQuery("SELECT client_id FROM outbox WHERE user_id=? AND conversation_id=? AND body=?",new String[]{user,group,text})){check.verify(cursor.getCount()==1&&cursor.moveToFirst(),"offline_exactly_one_durable_queue_entry");client=cursor.getString(0);}
            xyz.eclipseofficial.vyrn.data.OutboxStore.Entry entry=store.get(user,client);check.verify(failed?entry.state==xyz.eclipseofficial.vyrn.data.OutboxStore.FAILED&&entry.attempts==8:entry.state==xyz.eclipseofficial.vyrn.data.OutboxStore.QUEUED||entry.state==xyz.eclipseofficial.vyrn.data.OutboxStore.SENDING,failed?"retry_queue_failed_after_eight_attempts":"offline_queue_retryable_not_sent");
            if(reopen){
                check.verify(client.equals(fixture.getString("client_id")),"offline_restart_keeps_same_client_id");
                long[] random={0};instrumentation.runOnMainSync(()->random[0]=cell(activity.getWindow().getDecorView(),text).getMessageObject().messageOwner.random_id);
                check.verify(random[0]!=0&&client.equals(java.util.UUID.nameUUIDFromBytes((user+":"+random[0]).getBytes(StandardCharsets.UTF_8)).toString()),"offline_native_retry_identity_survives_process_restart");
            }
            write(instrumentation,new JSONObject().put("client_id",client).put("pending",true));
        }
    }
    private static boolean bubble(View view,String text,boolean outgoing){
        if(!view.isShown())return false;
        if(view instanceof ChatMessageCell){org.telegram.messenger.MessageObject message=((ChatMessageCell)view).getMessageObject();Rect visible=new Rect();if(message!=null&&text.equals(message.messageOwner.message)&&message.isOutOwner()==outgoing&&message.messageOwner.send_state==0&&message.getId()>0&&view.getGlobalVisibleRect(visible))return true;}
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)if(bubble(((ViewGroup)view).getChildAt(i),text,outgoing))return true;
        return false;
    }
    private static ChatMessageCell cell(View view,String text){
        if(!view.isShown())return null;
        if(view instanceof ChatMessageCell){org.telegram.messenger.MessageObject message=((ChatMessageCell)view).getMessageObject();if(message!=null&&text.equals(message.messageOwner.message))return (ChatMessageCell)view;}
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){ChatMessageCell found=cell(((ViewGroup)view).getChildAt(i),text);if(found!=null)return found;}
        return null;
    }
    private static ChatActivityEnterView composer(){try{java.lang.reflect.Field field=ChatActivity.class.getDeclaredField("chatActivityEnterView");field.setAccessible(true);return (ChatActivityEnterView)field.get(LaunchActivity.getLastFragment());}catch(Exception failure){throw new AssertionError("composer_unavailable");}}
    private static void edit(Instrumentation instrumentation,Activity activity,JSONObject fixture,RestRepository repository,boolean sender,AuthUiIntegration.Check check,java.util.List<String> checkpoints) throws Exception {
        String group=fixture.getString("group"),old=fixture.getString("text"),edited=fixture.getString("edited_text");View root=activity.getWindow().getDecorView();
        await(()->{boolean[] shown={false};instrumentation.runOnMainSync(()->shown[0]=bubble(root,old,sender));return shown[0];},check,"edit_original_visible");
        java.util.List<xyz.eclipseofficial.vyrn.domain.Models.Message> before=repository.liveMessages(group,null,100);xyz.eclipseofficial.vyrn.domain.Models.Message original=before.stream().filter(message->old.equals(message.text)).findFirst().orElseThrow(AssertionError::new);
        check.verify(before.stream().filter(message->old.equals(message.text)).count()==1,"edit_original_unique");
        if(sender){
            instrumentation.runOnMainSync(()->cell(root,old).performAccessibilityAction(org.telegram.messenger.R.id.acc_action_msg_options,new Bundle()));
            AuthUiIntegration.clickWindow(instrumentation,activity,org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.Edit),check);
            await(()->{boolean[] editing={false};instrumentation.runOnMainSync(()->editing[0]=composer().getEditingMessageObject()!=null&&old.contentEquals(composer().getEditField().getText()));return editing[0];},check,"edit_native_editor_loaded_original");
            await(()->{boolean[] ready={false};instrumentation.runOnMainSync(()->{try{java.lang.reflect.Field field=ChatActivity.class.getDeclaredField("editingMessageObjectReqId");field.setAccessible(true);ready[0]=field.getInt(LaunchActivity.getLastFragment())==0&&composer().getEditingMessageObject()!=null;}catch(Exception failure){throw new AssertionError("edit_preflight_state_unavailable");}});return ready[0];},check,"edit_native_preflight_complete");
            instrumentation.runOnMainSync(()->composer().getEditField().setText(edited));
            View[] done={null};instrumentation.runOnMainSync(()->{try{java.lang.reflect.Field field=ChatActivityEnterView.class.getDeclaredField("doneButton");field.setAccessible(true);done[0]=(View)field.get(composer());}catch(Exception failure){throw new AssertionError("edit_done_unavailable");}});
            await(()->{boolean[] ready={false};instrumentation.runOnMainSync(()->ready[0]=done[0].isShown()&&done[0].isEnabled());return ready[0];},check,"edit_native_done_visible");
            instrumentation.runOnMainSync(()->check.verify(done[0].performClick(),"edit_native_done_clicked"));
            if(fixture.optBoolean("interrupt_mutation")){interruptedMutation(instrumentation,original,checkpoints);return;}
            await(()->{boolean[] finished={false};instrumentation.runOnMainSync(()->finished[0]=composer().getEditingMessageObject()==null);return finished[0];},check,"edit_native_editor_closed");
        }else{write(instrumentation,new JSONObject().put("ready",true));}
        try{await(()->{boolean[] changed={false};instrumentation.runOnMainSync(()->changed[0]=bubble(root,edited,sender)&&!bubble(root,old,sender));return changed[0];},check,sender?"edit_sender_new_bubble":"edit_receiver_new_bubble_same_process");}
        catch(AssertionError failure){
            int[] counts={0,0,0};instrumentation.runOnMainSync(()->{ChatActivity chat=(ChatActivity)LaunchActivity.getLastFragment();counts[0]=chat.messages.size();for(org.telegram.messenger.MessageObject message:chat.messages){if(old.equals(message.messageOwner.message))counts[1]++;if(edited.equals(message.messageOwner.message))counts[2]++;}});
            JSONObject state=new JSONObject().put("native_messages",counts[0]).put("native_old_matches",counts[1]).put("native_edited_matches",counts[2]);
            try{java.util.List<xyz.eclipseofficial.vyrn.domain.Models.Message> live=repository.liveMessages(group,null,100);state.put("server_old_matches",live.stream().filter(message->old.equals(message.text)).count()).put("server_edited_matches",live.stream().filter(message->edited.equals(message.text)).count());}catch(Exception unavailable){state.put("server_probe","unavailable");}
            write(instrumentation,state);throw failure;
        }
        java.util.List<xyz.eclipseofficial.vyrn.domain.Models.Message> after=repository.liveMessages(group,null,100);xyz.eclipseofficial.vyrn.domain.Models.Message changed=after.stream().filter(message->edited.equals(message.text)).findFirst().orElseThrow(AssertionError::new);
        check.verify(changed.id.equals(original.id)&&changed.ordinal==original.ordinal&&changed.revision>original.revision&&changed.editedDate>0,"edit_same_message_identity_new_revision");
        check.verify(after.stream().filter(message->edited.equals(message.text)).count()==1&&after.stream().noneMatch(message->old.equals(message.text)),"edit_no_duplicate_or_old_server_text");
    }
    private static org.telegram.ui.ActionBar.AlertDialog dialog(){org.telegram.ui.ActionBar.BaseFragment fragment=LaunchActivity.getLastFragment();return fragment!=null&&fragment.visibleDialog instanceof org.telegram.ui.ActionBar.AlertDialog&&fragment.visibleDialog.isShowing()?(org.telegram.ui.ActionBar.AlertDialog)fragment.visibleDialog:null;}
    private static boolean hasCheckbox(View view){if(view instanceof org.telegram.ui.Cells.CheckBoxCell)return true;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)if(hasCheckbox(((ViewGroup)view).getChildAt(i)))return true;return false;}
    private static void deleteMenu(Instrumentation instrumentation,Activity activity,String text,AuthUiIntegration.Check check) throws Exception {
        instrumentation.runOnMainSync(()->cell(activity.getWindow().getDecorView(),text).performAccessibilityAction(org.telegram.messenger.R.id.acc_action_msg_options,new Bundle()));
        AuthUiIntegration.clickWindow(instrumentation,activity,org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.Delete),check);
        await(()->{boolean[] showing={false};instrumentation.runOnMainSync(()->showing[0]=dialog()!=null);return showing[0];},check,"delete_native_confirmation_visible");
        instrumentation.runOnMainSync(()->{org.telegram.ui.ActionBar.AlertDialog alert=dialog();String expected=org.telegram.messenger.LocaleController.isRTL?"این پیام‌ها برای همهٔ اعضای گروه حذف می‌شوند. ادامه می‌دهید؟":"These messages will be deleted for everyone in the group. Continue?";check.verify(expected.contentEquals(alert.getMessageTextView().getText())&&!hasCheckbox(alert.getWindow().getDecorView()),"delete_shared_semantics_no_local_only_option");});
    }
    private static void delete(Instrumentation instrumentation,Activity activity,JSONObject fixture,RestRepository repository,String phase,AuthUiIntegration.Check check,java.util.List<String> checkpoints) throws Exception {
        String group=fixture.getString("group"),text=fixture.getString("text");boolean actor=phase.equals("chat-delete"),reopen=phase.equals("chat-deleted-reopen");View root=activity.getWindow().getDecorView();
        xyz.eclipseofficial.vyrn.domain.Models.Message original;
        if(reopen){original=repository.messageForUser(repository.me().id,fixture.getString("message_id"));check.verify(original.deleted,"delete_reopen_server_tombstone");}
        else{
            await(()->{boolean[] shown={false};instrumentation.runOnMainSync(()->shown[0]=bubble(root,text,actor));return shown[0];},check,"delete_original_visible");
            java.util.List<xyz.eclipseofficial.vyrn.domain.Models.Message> live=repository.liveMessages(group,null,100);original=live.stream().filter(message->text.equals(message.text)).findFirst().orElseThrow(AssertionError::new);check.verify(live.stream().filter(message->text.equals(message.text)).count()==1,"delete_original_unique");
            if(actor){
                deleteMenu(instrumentation,activity,text,check);
                instrumentation.runOnMainSync(()->check.verify(dialog().getButton(android.content.DialogInterface.BUTTON_NEGATIVE).performClick(),"delete_native_cancel_clicked"));
                await(()->{boolean[] closed={false};instrumentation.runOnMainSync(()->closed[0]=dialog()==null&&bubble(root,text,true));return closed[0];},check,"delete_cancel_preserves_native_bubble");
                xyz.eclipseofficial.vyrn.domain.Models.Message preserved=repository.messageForUser(repository.me().id,original.id);check.verify(!preserved.deleted&&preserved.revision==original.revision&&text.equals(preserved.text),"delete_cancel_preserves_server_revision");
                deleteMenu(instrumentation,activity,text,check);
                instrumentation.runOnMainSync(()->check.verify(dialog().getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick(),"delete_native_confirm_clicked"));
                if(fixture.optBoolean("interrupt_mutation")){interruptedMutation(instrumentation,original,checkpoints);return;}
            }else{write(instrumentation,new JSONObject().put("ready",true).put("message_id",original.id));}
        }
        int id=original.ordinal;
        await(()->{boolean[] absent={false};instrumentation.runOnMainSync(()->{ChatActivity chat=(ChatActivity)LaunchActivity.getLastFragment();absent[0]=cell(root,text)==null&&chat.messages.stream().noneMatch(message->message.getId()==id);});return absent[0];},check,reopen?"delete_no_resurrection_after_process_restart":actor?"delete_sender_native_removed":"delete_receiver_removed_same_process");
        xyz.eclipseofficial.vyrn.domain.Models.Message tombstone=repository.messageForUser(repository.me().id,original.id);
        check.verify(tombstone.deleted&&tombstone.id.equals(original.id)&&tombstone.ordinal==id&&(reopen||tombstone.revision>original.revision),"delete_confirmed_same_identity_tombstone");
        if(fixture.has("original_revision"))check.verify(tombstone.ordinal==fixture.getInt("original_ordinal")&&tombstone.revision>fixture.getInt("original_revision"),"delete_restart_preserves_original_identity_and_new_revision");
        check.verify(repository.liveMessages(group,null,100).stream().noneMatch(message->message.id.equals(original.id)||text.equals(message.text)),"delete_absent_from_live_history");
    }
    private static void interruptedMutation(Instrumentation instrumentation,xyz.eclipseofficial.vyrn.domain.Models.Message original,java.util.List<String> checkpoints) throws Exception {
        write(instrumentation,new JSONObject().put("pending",true).put("message_id",original.id).put("original_ordinal",original.ordinal).put("original_revision",original.revision).put("interrupted_checks",new org.json.JSONArray(checkpoints)));
        Thread.sleep(200000);throw new AssertionError("Host did not interrupt the mutation");
    }
    private static void editedReopen(Instrumentation instrumentation,Activity activity,JSONObject fixture,RestRepository repository,String user,AuthUiIntegration.Check check) throws Exception {
        String old=fixture.getString("text"),edited=fixture.getString("edited_text"),id=fixture.getString("message_id");boolean outgoing=fixture.getBoolean("outgoing");
        await(()->{boolean[] shown={false};instrumentation.runOnMainSync(()->shown[0]=bubble(activity.getWindow().getDecorView(),edited,outgoing)&&!bubble(activity.getWindow().getDecorView(),old,outgoing));return shown[0];},check,"edit_committed_text_visible_after_process_restart");
        instrumentation.runOnMainSync(()->{ChatActivity chat=(ChatActivity)LaunchActivity.getLastFragment();check.verify(chat.messages.stream().filter(message->message.getId()==fixture.optInt("original_ordinal")).count()==1&&chat.messages.stream().filter(message->edited.equals(message.messageOwner.message)).count()==1&&chat.messages.stream().noneMatch(message->old.equals(message.messageOwner.message)),"edit_restart_no_native_duplicate_or_old_text");});
        xyz.eclipseofficial.vyrn.domain.Models.Message changed=repository.messageForUser(user,id);
        check.verify(!changed.deleted&&edited.equals(changed.text)&&changed.ordinal==fixture.getInt("original_ordinal")&&changed.revision>fixture.getInt("original_revision")&&changed.editedDate>0,"edit_restart_original_server_identity_new_revision");
        java.util.List<xyz.eclipseofficial.vyrn.domain.Models.Message> live=repository.liveMessages(fixture.getString("group"),null,100);
        check.verify(live.stream().filter(message->edited.equals(message.text)&&id.equals(message.id)).count()==1&&live.stream().noneMatch(message->old.equals(message.text)),"edit_restart_one_server_message_no_old_text");
    }
    private static void await(BooleanSupplier condition,AuthUiIntegration.Check check,String name) throws Exception {await(condition,check,name,40);}
    private static void await(BooleanSupplier condition,AuthUiIntegration.Check check,String name,int seconds) throws Exception {long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(seconds);while(!condition.getAsBoolean()&&System.nanoTime()<deadline)Thread.sleep(100);check.verify(condition.getAsBoolean(),name);}
    private static void write(Instrumentation instrumentation,JSONObject state) throws Exception {Files.write(new java.io.File(instrumentation.getTargetContext().getFilesDir(),"vyrn-test-chat-state.json").toPath(),state.toString().getBytes(StandardCharsets.UTF_8));}
}
