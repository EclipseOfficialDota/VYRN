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
        boolean offlineReopen=phase.equals("chat-offline-reopen")||phase.equals("chat-failed-reopen")||phase.equals("chat-private-offline-view")||phase.equals("chat-private-deleted-offline-view")||phase.equals("chat-private-pending-reopen");
        String user=offlineReopen?repository.vault.load().getString("user_id"):repository.me().id;
        if(phase.equals("chat-key-readiness")){
            java.util.concurrent.CompletableFuture<org.telegram.tgnet.TLObject> dialogs=new java.util.concurrent.CompletableFuture<>();
            org.telegram.tgnet.ConnectionsManager.getInstance(account).sendRequest(new org.telegram.tgnet.TLRPC.TL_messages_getDialogs(),(value,error)->{if(error!=null)dialogs.completeExceptionally(new AssertionError());else dialogs.complete(value);});
            check.verify(dialogs.get(30,java.util.concurrent.TimeUnit.SECONDS) instanceof org.telegram.tgnet.TLRPC.TL_messages_dialogs,"private_keys_native_dialog_load");
            xyz.eclipseofficial.vyrn.compat.VyrnBridge bridge=xyz.eclipseofficial.vyrn.compat.VyrnBridge.forAccount(account);
            await(bridge::privateKeysReady,check,"private_keys_automatic_dialog_publication");
            bridge.preparePrivateKeys().get(60,java.util.concurrent.TimeUnit.SECONDS);
            check.verify(bridge.privateKeysReady(),"private_keys_native_session_ready");
            try(xyz.eclipseofficial.vyrn.data.SignalDeviceKeys keys=new xyz.eclipseofficial.vyrn.data.SignalDeviceKeys(instrumentation.getTargetContext(),repository)){
                String address=keys.ensurePublished().getName();check.verify(user.equals(keys.user)&&!keys.device.equals(address),"private_keys_authenticated_device_identity");
                if(fixture.has("key_address"))check.verify(address.equals(fixture.getString("key_address")),"private_keys_cold_restart_same_publication");
                write(instrumentation,new JSONObject().put("user",keys.user).put("key_device",keys.device).put("key_address",address));
            }return;
        }
        if(phase.equals("chat-identity")){write(instrumentation,new JSONObject().put("user",user));return;}
        if(phase.startsWith("chat-private-")){privateChat(instrumentation,activity,fixture,repository,user,phase,check,checkpoints);return;}
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
    private static void privateChat(Instrumentation instrumentation,Activity activity,JSONObject fixture,RestRepository repository,String user,String phase,AuthUiIntegration.Check check,java.util.List<String> checkpoints) throws Exception {
        final int account=UserConfig.selectedAccount;String peer=fixture.getString("peer_user"),text=fixture.getString("text");check.verify(!peer.equals(user),"private_native_distinct_peer");
        check.verify(text.startsWith("چت خصوصی بومی "),"private_native_utf8_fixture_preserved");
        if(phase.equals("chat-private-auto-recover")){
            JSONObject[] resolved={null};await(()->{try(xyz.eclipseofficial.vyrn.data.SignalDeviceKeys keys=xyz.eclipseofficial.vyrn.data.SignalDeviceKeys.openLocal(instrumentation.getTargetContext(),repository)){JSONObject value=new xyz.eclipseofficial.vyrn.data.SignalMessages(keys).nativeBinding(account,fixture.getString("client_id"));if(value.getBoolean("scheduled")&&value.getBoolean("ui_done")&&!value.isNull("message_id")){resolved[0]=value;return true;}}catch(Exception unavailable){}return false;},check,"private_scheduled_cold_reconnect_automatic_journal_completion",80);
            JSONObject binding=resolved[0];check.verify(binding.getInt("local_id")==fixture.getInt("local_id")&&binding.getLong("random_id")==fixture.getLong("random_id")&&binding.getInt("date")==fixture.getInt("date")&&binding.getString("conversation").equals(fixture.getString("conversation"))&&binding.getString("peer").equals(peer),"private_automatic_recovery_preserves_original_native_identity");
            xyz.eclipseofficial.vyrn.domain.Models.Message value=repository.message(binding.getString("message_id"));check.verify(value.encrypted&&!value.deleted&&value.sender.equals(user),"private_automatic_recovery_owned_encrypted_server_message");fixture.put("message_id",value.id).put("ordinal",value.ordinal);
            org.json.JSONArray history=(org.json.JSONArray)repository.request("GET","/v1/conversations/"+value.conversation+"/messages?limit=100&live_only=true",null,true);int copies=0;for(int i=0;i<history.length();i++){JSONObject row=history.getJSONObject(i);if(user.equals(row.getString("sender_id"))&&fixture.getString("client_id").equals(row.getString("client_id")))copies++;}check.verify(copies==1,"private_automatic_recovery_one_server_client_identity");
        }
        if(phase.equals("chat-private-delete-fixture")){
            String message=fixture.getString("message_id");xyz.eclipseofficial.vyrn.domain.Models.Message before=repository.message(message);check.verify(before.encrypted&&!before.deleted&&before.sender.equals(user),"private_delete_fixture_owned_encrypted_message");
            repository.request("DELETE","/v1/messages/"+message,null,true);xyz.eclipseofficial.vyrn.domain.Models.Message deleted=repository.message(message);check.verify(deleted.deleted&&deleted.ordinal==before.ordinal&&deleted.revision>before.revision,"private_delete_fixture_same_identity_tombstone");
            write(instrumentation,new JSONObject().put("message_id",message).put("ordinal",deleted.ordinal));return;
        }
        if(phase.equals("chat-private-fixture-send")||phase.equals("chat-private-pending-prepare")||phase.equals("chat-private-pending-reopen")){
            String conversation=fixture.optString("conversation");if(conversation.isEmpty())conversation=((JSONObject)repository.request("POST","/v1/conversations",new JSONObject().put("kind","private").put("members",new org.json.JSONArray().put(peer)),true)).getString("id");
            long dialog;try(IdMap ids=new IdMap(instrumentation.getTargetContext(),account)){dialog=ids.local("user",peer);}
            xyz.eclipseofficial.vyrn.compat.VyrnBridge bridge=xyz.eclipseofficial.vyrn.compat.VyrnBridge.forAccount(account);JSONObject binding;
            if(fixture.has("client_id")){try(xyz.eclipseofficial.vyrn.data.SignalDeviceKeys keys=xyz.eclipseofficial.vyrn.data.SignalDeviceKeys.openLocal(instrumentation.getTargetContext(),repository)){binding=new xyz.eclipseofficial.vyrn.data.SignalMessages(keys).nativeBinding(account,fixture.getString("client_id"));}check.verify(binding.getInt("local_id")==fixture.getInt("local_id")&&binding.getLong("random_id")==fixture.getLong("random_id")&&binding.getInt("date")==fixture.getInt("date")&&binding.getLong("dialog_id")==dialog&&binding.getString("peer").equals(peer)&&binding.getString("conversation").equals(conversation),"private_native_cold_original_encrypted_identity_restored");}
            else binding=bridge.preparePrivateText(dialog,conversation,text).get(45,java.util.concurrent.TimeUnit.SECONDS);
            int local=binding.getInt("local_id");
            if(phase.equals("chat-private-pending-reopen")){boolean disconnected=false;try{repository.me();}catch(java.io.IOException expected){disconnected=true;}check.verify(disconnected,"private_pending_cold_transport_actually_disconnected");org.telegram.tgnet.TLRPC.User cached=org.telegram.messenger.MessagesStorage.getInstance(account).getUserSync(dialog);check.verify(cached!=null,"private_pending_cold_native_peer_persisted");instrumentation.runOnMainSync(()->MessagesController.getInstance(account).putUser(cached,true));}
            check.verify(local<0&&binding.isNull("message_id")&&!binding.toString().contains(text),"private_native_adapter_prepared_encrypted_identity_without_plaintext");
            await(()->!AuthUiIntegration.handlePrompts(instrumentation,activity,check),check,"private_pending_native_startup_prompts_handled");
            instrumentation.runOnMainSync(()->{Bundle args=new Bundle();args.putLong("user_id",dialog);LaunchActivity.getLastFragment().presentFragment(new ChatActivity(args));});
            await(()->{boolean[] visible={false};instrumentation.runOnMainSync(()->visible[0]=privateCell(activity.getWindow().getDecorView(),local,text)!=null);return visible[0];},check,"private_native_actual_pending_chat_bubble_from_encrypted_journal");
            instrumentation.runOnMainSync(()->check.verify(xyz.eclipseofficial.vyrn.compat.VyrnBridge.PRIVATE_PENDING_PLACEHOLDER.equals(privateCell(activity.getWindow().getDecorView(),local,text).getMessageObject().messageOwner.message),"private_native_pending_visible_bubble_keeps_owner_placeholder"));
            if(!phase.equals("chat-private-fixture-send")){if(fixture.optBoolean("auto_schedule")){bridge.schedulePrivateText(binding.getString("client_id")).get(30,java.util.concurrent.TimeUnit.SECONDS);try(xyz.eclipseofficial.vyrn.data.SignalDeviceKeys keys=xyz.eclipseofficial.vyrn.data.SignalDeviceKeys.openLocal(instrumentation.getTargetContext(),repository)){binding=new xyz.eclipseofficial.vyrn.data.SignalMessages(keys).nativeBinding(account,binding.getString("client_id"));}check.verify(binding.getBoolean("scheduled")&&binding.isNull("message_id")&&!binding.getBoolean("ui_done"),"private_offline_original_encrypted_scheduling_intent_persisted");}write(instrumentation,binding);return;}
            if(fixture.has("server_id")){JSONObject proof=privateSendProof(instrumentation,repository,binding.getString("client_id"));check.verify(fixture.getString("ciphertext_sha256").equals(proof.getString("ciphertext_sha256"))&&fixture.getString("ratchet_sha256").equals(proof.getString("ratchet_sha256")),"private_unknown_ack_cold_original_ciphertext_and_ratchet_preserved");}
            if(fixture.optBoolean("interrupt_send")){JSONObject proof=privateSendProof(instrumentation,repository,binding.getString("client_id"));write(instrumentation,new JSONObject(binding.toString()).put("pending",true).put("interrupted_checks",new org.json.JSONArray(checkpoints)).put("ciphertext_sha256",proof.getString("ciphertext_sha256")).put("ratchet_sha256",proof.getString("ratchet_sha256")));}
            xyz.eclipseofficial.vyrn.domain.Models.Message value=bridge.deliverPrivateText(binding.getString("client_id")).get(45,java.util.concurrent.TimeUnit.SECONDS);
            if(fixture.optBoolean("interrupt_send"))throw new AssertionError("private_held_response_escaped_before_interruption");
            check.verify(value.encrypted&&!value.deleted&&value.conversation.equals(conversation)&&value.sender.equals(user),"private_encrypted_fixture_committed");
            await(()->{boolean[] confirmed={false};instrumentation.runOnMainSync(()->{ChatActivity chat=(ChatActivity)LaunchActivity.getLastFragment();confirmed[0]=privateCell(activity.getWindow().getDecorView(),value.ordinal,text)!=null&&chat.messages.stream().noneMatch(message->message.getId()==local)&&chat.messages.stream().filter(message->message.getId()==value.ordinal).count()==1;});return confirmed[0];},check,"private_native_pending_reconciled_to_one_actual_confirmed_bubble");
            check.verify(bridge.deliverPrivateText(binding.getString("client_id")).get(30,java.util.concurrent.TimeUnit.SECONDS).id.equals(value.id),"private_native_repeated_delivery_preserves_server_identity");
            if(fixture.has("server_id")){JSONObject proof=privateSendProof(instrumentation,repository,binding.getString("client_id"));org.json.JSONArray history=(org.json.JSONArray)repository.request("GET","/v1/conversations/"+conversation+"/messages?limit=100&live_only=true",null,true);int copies=0;for(int i=0;i<history.length();i++){JSONObject message=history.getJSONObject(i);if(user.equals(message.getString("sender_id"))&&binding.getString("client_id").equals(message.getString("client_id")))copies++;}check.verify(value.id.equals(fixture.getString("server_id"))&&fixture.getString("ciphertext_sha256").equals(proof.getString("ciphertext_sha256"))&&fixture.getString("ratchet_sha256").equals(proof.getString("ratchet_sha256"))&&copies==1,"private_unknown_ack_recovers_exact_server_identity_without_duplicate_or_ratchet_advance");}
            try(xyz.eclipseofficial.vyrn.data.SignalDeviceKeys keys=xyz.eclipseofficial.vyrn.data.SignalDeviceKeys.openLocal(instrumentation.getTargetContext(),repository)){xyz.eclipseofficial.vyrn.data.SignalMessages messages=new xyz.eclipseofficial.vyrn.data.SignalMessages(keys);check.verify(messages.nativePending(account).length()==0&&messages.nativeBinding(account,binding.getString("client_id")).getBoolean("ui_done"),"private_native_ui_acknowledgement_removes_only_resolved_journal_work");}
            write(instrumentation,new JSONObject().put("conversation",conversation).put("message_id",value.id).put("ordinal",value.ordinal));return;
        }
        boolean offline=phase.equals("chat-private-offline-view")||phase.equals("chat-private-deleted-offline-view"),deleted=phase.equals("chat-private-deleted-view")||phase.equals("chat-private-deleted-offline-view");long local;try(IdMap ids=new IdMap(instrumentation.getTargetContext(),account)){local=ids.local("user",peer);}
        if(offline){boolean disconnected=false;try{repository.me();}catch(java.io.IOException expected){disconnected=true;}check.verify(disconnected,"private_cold_reopen_transport_actually_disconnected");
            org.telegram.tgnet.TLRPC.User cached=org.telegram.messenger.MessagesStorage.getInstance(account).getUserSync(local);check.verify(cached!=null,"private_cold_reopen_native_peer_persisted");instrumentation.runOnMainSync(()->MessagesController.getInstance(account).putUser(cached,true));
        }else{
            java.util.concurrent.CompletableFuture<org.telegram.tgnet.TLObject> loaded=new java.util.concurrent.CompletableFuture<>();org.telegram.tgnet.ConnectionsManager.getInstance(account).sendRequest(new org.telegram.tgnet.TLRPC.TL_messages_getDialogs(),(value,error)->{if(error!=null)loaded.completeExceptionally(new AssertionError());else loaded.complete(value);});
            org.telegram.tgnet.TLRPC.TL_messages_dialogs dialogs=(org.telegram.tgnet.TLRPC.TL_messages_dialogs)loaded.get(30,java.util.concurrent.TimeUnit.SECONDS);instrumentation.runOnMainSync(()->MessagesController.getInstance(account).putUsers(dialogs.users,false));org.telegram.messenger.MessagesStorage.getInstance(account).putUsersAndChats(dialogs.users,dialogs.chats,true,true);
        }
        await(()->!AuthUiIntegration.handlePrompts(instrumentation,activity,check),check,"private_native_startup_prompts_handled");
        instrumentation.runOnMainSync(()->{Bundle args=new Bundle();args.putLong("user_id",local);LaunchActivity.getLastFragment().presentFragment(new ChatActivity(args));});
        await(()->{boolean[] ready={false};instrumentation.runOnMainSync(()->ready[0]=LaunchActivity.getLastFragment() instanceof ChatActivity&&!LaunchActivity.getLastFragment().getParentLayout().isTransitionAnimationInProgress());return ready[0];},check,"private_native_chat_screen_ready");
        final int ordinal=fixture.getInt("ordinal");View root=activity.getWindow().getDecorView();
        if(deleted){privateDeleted(instrumentation,fixture,repository,local,offline,root,check);return;}
        await(()->{boolean[] visible={false};instrumentation.runOnMainSync(()->visible[0]=privateCell(root,ordinal,text)!=null);return visible[0];},check,offline?"private_cold_offline_native_plaintext_bubble":"private_native_plaintext_bubble");
        if(phase.equals("chat-private-auto-recover"))instrumentation.runOnMainSync(()->check.verify(((ChatActivity)LaunchActivity.getLastFragment()).messages.stream().noneMatch(message->message.getId()==fixture.optInt("local_id")),"private_automatic_recovery_removes_original_negative_chat_row"));
        instrumentation.runOnMainSync(()->{ChatActivity chat=(ChatActivity)LaunchActivity.getLastFragment();check.verify(chat.messages.stream().filter(message->message.getId()==ordinal).count()==1,"private_native_single_message_identity");org.telegram.messenger.MessageObject object=privateCell(root,ordinal,text).getMessageObject();check.verify(!text.equals(object.messageOwner.message),"private_native_visible_bubble_keeps_owner_placeholder");});
        org.telegram.messenger.MessagesStorage storage=org.telegram.messenger.MessagesStorage.getInstance(account);java.util.concurrent.CompletableFuture<Boolean> stored=new java.util.concurrent.CompletableFuture<>();
        storage.getStorageQueue().postRunnable(()->{try{org.telegram.SQLite.SQLiteCursor cursor=storage.getDatabase().queryFinalized("SELECT data,custom_params,replydata FROM messages_v2 WHERE uid="+local+" AND mid="+ordinal);try{boolean safe=cursor.next();if(safe)for(int column=0;column<3;column++){byte[] bytes=cursor.byteArrayValue(column);if(bytes!=null&&new String(bytes,StandardCharsets.UTF_8).contains(text))safe=false;}stored.complete(safe);}finally{cursor.dispose();}}catch(Throwable failure){stored.completeExceptionally(failure);}});
        check.verify(stored.get(20,java.util.concurrent.TimeUnit.SECONDS),"private_native_chat_database_contains_no_plaintext_body");
        String draft="پیش‌نویس خصوصی 🔐 "+text,record=Long.toString(local);android.content.Context context=instrumentation.getTargetContext();
        if(offline){await(()->{boolean[] restored={false};instrumentation.runOnMainSync(()->restored[0]=draft.contentEquals(String.valueOf(((ChatActivity)LaunchActivity.getLastFragment()).getChatActivityEnterView().getFieldText())));return restored[0];},check,"private_draft_cold_offline_actual_composer_restored");}
        else instrumentation.runOnMainSync(()->{ChatActivity chat=(ChatActivity)LaunchActivity.getLastFragment();chat.getChatActivityEnterView().setFieldText(draft);chat.saveDraft();check.verify(draft.contentEquals(chat.getChatActivityEnterView().getFieldText()),"private_draft_actual_composer_input_retained");});
        String envelope=context.getSharedPreferences(account==0?"drafts":"drafts"+account,android.content.Context.MODE_PRIVATE).getString(record,"");
        check.verify(envelope.startsWith("vyrn-draft-v1:")&&!envelope.contains(org.telegram.messenger.Utilities.bytesToHex(draft.getBytes(StandardCharsets.UTF_8))),"private_draft_native_preferences_encrypted");
        byte[] decoded=xyz.eclipseofficial.vyrn.data.DraftVault.decode(account,record,envelope);org.telegram.tgnet.SerializedData draftData=new org.telegram.tgnet.SerializedData(decoded);try{org.telegram.tgnet.TLRPC.DraftMessage loaded=org.telegram.tgnet.TLRPC.DraftMessage.TLdeserialize(draftData,draftData.readInt32(true),true);check.verify(draft.equals(loaded.message),"private_draft_authenticated_native_serialization_round_trip");}finally{draftData.cleanup();}
        if(!offline){
            await(()->{boolean[] disabled={false};instrumentation.runOnMainSync(()->{View button=((ChatActivity)LaunchActivity.getLastFragment()).getChatActivityEnterView().getSendButtonInternal();disabled[0]=button.isShown()&&!button.isEnabled()&&button.getAlpha()<=0.5f;});return disabled[0];},check,"private_send_unsupported_button_disabled_and_dimmed");
            instrumentation.runOnMainSync(()->{ChatActivity chat=(ChatActivity)LaunchActivity.getLastFragment();ChatActivityEnterView composer=chat.getChatActivityEnterView();check.verify(!composer.sendMessage(),"private_send_programmatic_composer_rejected_before_enqueue");org.telegram.messenger.SendMessagesHelper.getInstance(account).sendMessage(org.telegram.messenger.SendMessagesHelper.SendMessageParams.of(draft,local));java.util.ArrayList<org.telegram.messenger.MessageObject> forwarded=new java.util.ArrayList<>();forwarded.add(privateCell(root,ordinal,text).getMessageObject());check.verify(org.telegram.messenger.SendMessagesHelper.getInstance(account).sendMessage(forwarded,local,false,false,true,0,0)==0,"private_send_forward_entry_rejected_before_enqueue");org.telegram.messenger.SendMessagesHelper.getInstance(account).sendScreenshotMessage(MessagesController.getInstance(account).getUser(local),ordinal,null);check.verify(draft.contentEquals(composer.getFieldText()),"private_send_unavailable_keeps_actual_composer_draft");check.verify(chat.messages.stream().noneMatch(message->message.getId()<0&&draft.equals(message.messageOwner.message)),"private_send_unavailable_no_plaintext_pending_bubble");});
            java.util.concurrent.CompletableFuture<Boolean> noPending=new java.util.concurrent.CompletableFuture<>();storage.getStorageQueue().postRunnable(()->{try{org.telegram.SQLite.SQLiteCursor cursor=storage.getDatabase().queryFinalized("SELECT count(*) FROM messages_v2 WHERE uid="+local+" AND mid<0");try{noPending.complete(cursor.next()&&cursor.intValue(0)==0);}finally{cursor.dispose();}}catch(Throwable failure){noPending.completeExceptionally(failure);}});check.verify(noPending.get(20,java.util.concurrent.TimeUnit.SECONDS),"private_send_shared_native_entry_no_plaintext_pending_database_row");
            boolean denied=false;try{xyz.eclipseofficial.vyrn.data.DraftVault.decode(account,Long.toString(local+100000),envelope);}catch(SecurityException expected){denied=true;}check.verify(denied,"private_draft_other_dialog_ciphertext_swap_rejected");
            denied=false;try{xyz.eclipseofficial.vyrn.data.DraftVault.decode(account,"r_"+record,envelope);}catch(SecurityException expected){denied=true;}check.verify(denied,"private_draft_reply_record_ciphertext_swap_rejected");
            denied=false;try{xyz.eclipseofficial.vyrn.data.DraftVault.decode(account,record,org.telegram.messenger.Utilities.bytesToHex(decoded));}catch(SecurityException expected){denied=true;}check.verify(denied,"private_draft_unbound_legacy_plaintext_not_loaded");
            String encoded=envelope.substring("vyrn-draft-v1:".length());byte[] corrupt=android.util.Base64.decode(encoded,android.util.Base64.NO_WRAP);corrupt[corrupt.length-1]^=1;denied=false;try{xyz.eclipseofficial.vyrn.data.DraftVault.decode(account,record,"vyrn-draft-v1:"+android.util.Base64.encodeToString(corrupt,android.util.Base64.NO_WRAP));}catch(SecurityException expected){denied=true;}check.verify(denied,"private_draft_corrupted_envelope_rejected");
            denied=false;try{xyz.eclipseofficial.vyrn.data.DraftVault.decode(1,record,envelope);}catch(SecurityException expected){denied=true;}check.verify(denied,"private_draft_other_native_account_rejected");
            check.verify(java.util.Arrays.equals(decoded,xyz.eclipseofficial.vyrn.data.DraftVault.decode(account,"-1",xyz.eclipseofficial.vyrn.data.DraftVault.encode(account,"-1",decoded))),"group_draft_standard_native_serialization_preserved");
            String dummy=java.util.UUID.randomUUID().toString();int dummyId;try(IdMap ids=new IdMap(context,account)){dummyId=ids.local("user",dummy);}String dummyRecord=Integer.toString(dummyId),dummyEnvelope=xyz.eclipseofficial.vyrn.data.DraftVault.encode(account,dummyRecord,decoded);
            try(android.database.sqlite.SQLiteDatabase db=android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath("vyrn_ids_"+account+".db").getAbsolutePath(),null,android.database.sqlite.SQLiteDatabase.OPEN_READWRITE)){
                try{db.execSQL("UPDATE ids SET uuid=? WHERE kind=? AND id=?",new Object[]{java.util.UUID.randomUUID().toString(),"user",dummyId});denied=false;try{xyz.eclipseofficial.vyrn.data.DraftVault.decode(account,dummyRecord,dummyEnvelope);}catch(SecurityException expected){denied=true;}check.verify(denied,"private_draft_reused_native_id_other_peer_uuid_rejected");}finally{db.execSQL("UPDATE ids SET uuid=? WHERE kind=? AND id=?",new Object[]{dummy,"user",dummyId});}
            }
        }
        write(instrumentation,new JSONObject().put("message_id",fixture.getString("message_id")).put("ordinal",ordinal).put("offline",offline).put("conversation",fixture.getString("conversation")));
    }
    private static void privateDeleted(Instrumentation instrumentation,JSONObject fixture,RestRepository repository,long dialog,boolean offline,View root,AuthUiIntegration.Check check) throws Exception {
        int account=UserConfig.selectedAccount,id=fixture.getInt("ordinal"),liveId=fixture.getInt("live_ordinal");String text=fixture.getString("text"),liveText=fixture.getString("live_text");
        if(!offline)check.verify(repository.message(fixture.getString("message_id")).deleted,"private_delete_authoritative_server_tombstone");
        await(()->{java.util.Map<Long,java.util.Set<Integer>> fences=org.telegram.messenger.MessagesStorage.getInstance(account).getVyrnDeletedMessages();return fences!=null&&fences.containsKey(dialog)&&fences.get(dialog).contains(id);},check,"private_delete_native_persistent_fence_learned");
        await(()->{boolean[] loaded={false};instrumentation.runOnMainSync(()->loaded[0]=privateCell(root,liveId,liveText)!=null);return loaded[0];},check,"private_delete_known_live_bubble_proves_history_loaded");
        instrumentation.runOnMainSync(()->{ChatActivity chat=(ChatActivity)LaunchActivity.getLastFragment();check.verify(privateCell(root,id,text)==null&&chat.messages.stream().noneMatch(message->message.getId()==id),offline?"private_delete_cold_offline_no_resurrection":"private_delete_native_history_absent");});
        long sender;try(IdMap ids=new IdMap(instrumentation.getTargetContext(),account)){sender=ids.local("user",fixture.getString("sender_user"));}
        org.telegram.tgnet.TLRPC.TL_message row=new org.telegram.tgnet.TLRPC.TL_message();row.id=id;row.date=1;row.dialog_id=dialog;row.peer_id=new org.telegram.tgnet.TLRPC.TL_peerUser();row.peer_id.user_id=dialog;row.from_id=new org.telegram.tgnet.TLRPC.TL_peerUser();row.from_id.user_id=sender;row.flags=256;row.message="Encrypted message";row.media=new org.telegram.tgnet.TLRPC.TL_messageMediaEmpty();
        instrumentation.runOnMainSync(()->{
            org.telegram.messenger.MessageObject stale=new org.telegram.messenger.MessageObject(account,row,true,false);stale.applyNewText(text);
            java.util.ArrayList<org.telegram.messenger.MessageObject> objects=new java.util.ArrayList<>();objects.add(stale);check.verify(xyz.eclipseofficial.vyrn.compat.VyrnBridge.retainLiveMessages(account,objects)&&objects.isEmpty(),"private_delete_prefetched_plaintext_array_filtered");
            java.util.ArrayList<org.telegram.tgnet.TLRPC.Update> updates=new java.util.ArrayList<>();org.telegram.tgnet.tl.TL_update.TL_updateNewMessage created=new org.telegram.tgnet.tl.TL_update.TL_updateNewMessage();created.message=row;updates.add(created);org.telegram.tgnet.tl.TL_update.TL_updateEditMessage edited=new org.telegram.tgnet.tl.TL_update.TL_updateEditMessage();edited.message=row;updates.add(edited);
            check.verify(xyz.eclipseofficial.vyrn.compat.VyrnBridge.filterDeletedUpdates(account,updates)&&updates.stream().allMatch(update->update instanceof org.telegram.tgnet.tl.TL_update.TL_updateDeleteMessages&&((org.telegram.tgnet.tl.TL_update.TL_updateDeleteMessages)update).messages.contains(id)),"private_delete_prefetched_new_edit_updates_become_deletion");
            org.telegram.messenger.NotificationCenter center=org.telegram.messenger.NotificationCenter.getInstance(account);boolean[] appeared={false};org.telegram.messenger.NotificationCenter.NotificationCenterDelegate observer=(event,slot,args)->appeared[0]=true;center.addObserver(observer,org.telegram.messenger.NotificationCenter.replaceMessagesObjects);
            try{java.util.ArrayList<org.telegram.messenger.MessageObject> replay=new java.util.ArrayList<>();replay.add(stale);center.postNotificationName(org.telegram.messenger.NotificationCenter.replaceMessagesObjects,dialog,replay);check.verify(!appeared[0]&&replay.isEmpty(),"private_delete_actual_notification_dispatch_rejects_stale_object");}finally{center.removeObserver(observer,org.telegram.messenger.NotificationCenter.replaceMessagesObjects);}
            org.telegram.tgnet.TLRPC.TL_dialog top=new org.telegram.tgnet.TLRPC.TL_dialog();top.id=dialog;top.top_message=id;check.verify(xyz.eclipseofficial.vyrn.compat.VyrnBridge.isDeletedDialogTop(account,top),"private_delete_dialog_top_replay_detected");
            org.telegram.messenger.MessageObject live=((ChatActivity)LaunchActivity.getLastFragment()).messages.stream().filter(message->message.getId()==liveId).findFirst().orElseThrow(AssertionError::new);
            java.util.ArrayList<org.telegram.messenger.MessageObject> incoming=new java.util.ArrayList<>();incoming.add(stale);java.util.ArrayList<org.telegram.messenger.MessageObject> prior=new java.util.ArrayList<>();prior.add(live);
            check.verify(xyz.eclipseofficial.vyrn.compat.VyrnBridge.repairDialogTop(account,top,incoming,prior)&&top.top_message==liveId&&incoming.contains(live)&&!incoming.contains(stale),"private_delete_dialog_top_preserves_known_live_fallback");
            org.telegram.tgnet.TLRPC.TL_message unrelatedRow=new org.telegram.tgnet.TLRPC.TL_message();unrelatedRow.id=id+1;unrelatedRow.date=Integer.MAX_VALUE;unrelatedRow.dialog_id=dialog+1;unrelatedRow.peer_id=new org.telegram.tgnet.TLRPC.TL_peerUser();unrelatedRow.peer_id.user_id=dialog+1;unrelatedRow.message="Encrypted message";unrelatedRow.media=new org.telegram.tgnet.TLRPC.TL_messageMediaEmpty();
            org.telegram.messenger.MessageObject unrelated=new org.telegram.messenger.MessageObject(account,unrelatedRow,true,false);incoming.clear();incoming.add(unrelated);top.top_message=id;
            check.verify(xyz.eclipseofficial.vyrn.compat.VyrnBridge.repairDialogTop(account,top,incoming,prior)&&top.top_message==liveId,"private_delete_dialog_top_rejects_newer_other_dialog");
            incoming.clear();incoming.add(unrelated);top.top_message=id;
            check.verify(xyz.eclipseofficial.vyrn.compat.VyrnBridge.repairDialogTop(account,top,incoming,null)&&top.top_message==0,"private_delete_dialog_top_empty_without_same_dialog_fallback");
        });
        org.telegram.messenger.MessagesStorage storage=org.telegram.messenger.MessagesStorage.getInstance(account);java.util.ArrayList<org.telegram.tgnet.TLRPC.Message> replay=new java.util.ArrayList<>();replay.add(row);storage.putMessages(replay,true,true,false,0,0,0);
        java.util.concurrent.CompletableFuture<Boolean> absent=new java.util.concurrent.CompletableFuture<>();storage.getStorageQueue().postRunnable(()->{try{org.telegram.SQLite.SQLiteCursor cursor=storage.getDatabase().queryFinalized("SELECT count(*) FROM messages_v2 WHERE uid="+dialog+" AND mid="+id);try{absent.complete(cursor.next()&&cursor.intValue(0)==0);}finally{cursor.dispose();}}catch(Throwable failure){absent.completeExceptionally(failure);}});
        check.verify(absent.get(20,java.util.concurrent.TimeUnit.SECONDS),"private_delete_actual_native_sql_replay_rejected");
        java.util.concurrent.CompletableFuture<Boolean> safeTop=new java.util.concurrent.CompletableFuture<>();storage.getStorageQueue().postRunnable(()->{try{org.telegram.SQLite.SQLiteCursor cursor=storage.getDatabase().queryFinalized("SELECT last_mid FROM dialogs WHERE did="+dialog);try{safeTop.complete(cursor.next()&&cursor.intValue(0)!=id);}finally{cursor.dispose();}}catch(Throwable failure){safeTop.completeExceptionally(failure);}});
        check.verify(safeTop.get(20,java.util.concurrent.TimeUnit.SECONDS),"private_delete_actual_native_sql_dialog_top_not_deleted");write(instrumentation,new JSONObject().put("message_id",fixture.getString("message_id")).put("ordinal",id).put("offline",offline));
    }
    private static ChatMessageCell privateCell(View view,int id,String text){
        if(!view.isShown())return null;if(view instanceof ChatMessageCell){org.telegram.messenger.MessageObject object=((ChatMessageCell)view).getMessageObject();if(object!=null&&object.getId()==id&&text.contentEquals(object.messageText))return (ChatMessageCell)view;}
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){ChatMessageCell found=privateCell(((ViewGroup)view).getChildAt(i),id,text);if(found!=null)return found;}return null;
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
    private static JSONObject privateSendProof(Instrumentation instrumentation,RestRepository repository,String client) throws Exception {
        try(xyz.eclipseofficial.vyrn.data.SignalDeviceKeys keys=xyz.eclipseofficial.vyrn.data.SignalDeviceKeys.openLocal(instrumentation.getTargetContext(),repository)){
            String body=new JSONObject(new String(keys.store.loadValue("private-outgoing",client),StandardCharsets.UTF_8)).getString("body");java.security.MessageDigest ratchet=java.security.MessageDigest.getInstance("SHA-256");java.util.TreeSet<String> addresses=new java.util.TreeSet<>();new JSONObject(body).getJSONObject("payload").getJSONObject("devices").keys().forEachRemaining(addresses::add);
            for(String address:addresses){ratchet.update(address.getBytes(StandardCharsets.UTF_8));ratchet.update(keys.store.loadSession(new org.signal.libsignal.protocol.SignalProtocolAddress(address,1)).serialize());}
            return new JSONObject().put("ciphertext_sha256",android.util.Base64.encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)),android.util.Base64.NO_WRAP)).put("ratchet_sha256",android.util.Base64.encodeToString(ratchet.digest(),android.util.Base64.NO_WRAP));
        }
    }
    private static void await(BooleanSupplier condition,AuthUiIntegration.Check check,String name) throws Exception {await(condition,check,name,40);}
    private static void await(BooleanSupplier condition,AuthUiIntegration.Check check,String name,int seconds) throws Exception {long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(seconds);while(!condition.getAsBoolean()&&System.nanoTime()<deadline)Thread.sleep(100);check.verify(condition.getAsBoolean(),name);}
    private static void write(Instrumentation instrumentation,JSONObject state) throws Exception {Files.write(new java.io.File(instrumentation.getTargetContext().getFilesDir(),"vyrn-test-chat-state.json").toPath(),state.toString().getBytes(StandardCharsets.UTF_8));}
}
