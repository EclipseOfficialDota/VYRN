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
    static void run(Instrumentation instrumentation,Activity activity,JSONObject fixture,String phase,AuthUiIntegration.Check check) throws Exception {
        int account=UserConfig.selectedAccount;
        check.verify(account==0&&UserConfig.getInstance(account).isClientActivated(),"chat_native_account0_active");
        RestRepository repository=new RestRepository(new TokenVault(instrumentation.getTargetContext(),account));
        String user=repository.me().id;
        if(phase.equals("chat-identity")){write(instrumentation,new JSONObject().put("user",user));return;}
        if(phase.equals("chat-create")){
            String peer=fixture.getString("peer_user");check.verify(!user.equals(peer),"chat_distinct_accounts");
            String group=repository.createGroup(fixture.getString("title"),java.util.Collections.singletonList(peer),0).id;
            check.verify(repository.members(group).size()==2,"chat_fixture_two_members");write(instrumentation,new JSONObject().put("group",group));return;
        }
        String group=fixture.getString("group"),text=fixture.getString("text");
        long local;try(IdMap map=new IdMap(instrumentation.getTargetContext(),account)){local=map.local("conversation",group);}
        await(()->!AuthUiIntegration.handlePrompts(instrumentation,activity,check),check,"chat_startup_permissions_handled");
        java.util.concurrent.CompletableFuture<org.telegram.tgnet.TLObject> loaded=new java.util.concurrent.CompletableFuture<>();
        org.telegram.tgnet.ConnectionsManager.getInstance(account).sendRequest(new org.telegram.tgnet.TLRPC.TL_messages_getDialogs(),(value,error)->{if(error!=null)loaded.completeExceptionally(new AssertionError());else loaded.complete(value);});
        org.telegram.tgnet.TLRPC.TL_messages_dialogs dialogs=(org.telegram.tgnet.TLRPC.TL_messages_dialogs)loaded.get(30,java.util.concurrent.TimeUnit.SECONDS);
        instrumentation.runOnMainSync(()->{MessagesController.getInstance(account).putChats(dialogs.chats,false);MessagesController.getInstance(account).putUsers(dialogs.users,false);});
        await(()->{boolean[] ready={false};instrumentation.runOnMainSync(()->ready[0]=MessagesController.getInstance(account).getChat(local)!=null);return ready[0];},check,"chat_native_group_loaded");
        instrumentation.runOnMainSync(()->{Bundle args=new Bundle();args.putLong("chat_id",local);LaunchActivity.getLastFragment().presentFragment(new ChatActivity(args));});
        await(()->{if(AuthUiIntegration.handlePrompts(instrumentation,activity,check))return false;boolean[] ready={false};instrumentation.runOnMainSync(()->ready[0]=LaunchActivity.getLastFragment() instanceof ChatActivity&&!LaunchActivity.getLastFragment().getParentLayout().isTransitionAnimationInProgress());return ready[0];},check,"chat_native_screen_ready");
        instrumentation.runOnMainSync(()->check.verify(org.telegram.messenger.ChatObject.canSendPlain(MessagesController.getInstance(account).getChat(local)),"chat_member_can_send_plain"));
        if(phase.equals("chat-receive"))write(instrumentation,new JSONObject().put("ready",true));
        if(phase.equals("chat-send")){
            ChatActivityEnterView[] composer={null};
            instrumentation.runOnMainSync(()->{try{java.lang.reflect.Field field=ChatActivity.class.getDeclaredField("chatActivityEnterView");field.setAccessible(true);composer[0]=(ChatActivityEnterView)field.get(LaunchActivity.getLastFragment());}catch(Exception failure){throw new AssertionError("composer_unavailable");}composer[0].getEditField().setText(text);});
            Rect bounds=new Rect();
            Rect previous=new Rect();long[] stableSince={0};
            await(()->{boolean[] ready={false};instrumentation.runOnMainSync(()->{View button=composer[0].getSendButtonInternal();ready[0]=button.isShown()&&button.isEnabled()&&button.getAlpha()>=0.99f&&!button.isLayoutRequested()&&button.getGlobalVisibleRect(bounds);});long now=android.os.SystemClock.uptimeMillis();if(!ready[0]||!bounds.equals(previous)){previous.set(bounds);stableSince[0]=now;return false;}return now-stableSince[0]>=600;},check,"chat_send_button_visible_and_stable");
            instrumentation.runOnMainSync(()->check.verify(composer[0].getSendButtonInternal().performClick(),"chat_native_send_button_clicked"));
            await(()->{boolean[] empty={false};instrumentation.runOnMainSync(()->empty[0]=composer[0].getEditField().length()==0);return empty[0];},check,"chat_composer_cleared");
        }else if(!phase.equals("chat-receive")){throw new IllegalArgumentException();}
        try{await(()->{boolean[] shown={false};instrumentation.runOnMainSync(()->shown[0]=bubble(activity.getWindow().getDecorView(),text,phase.equals("chat-send")));return shown[0];},check,phase.equals("chat-send")?"chat_sender_confirmed_bubble":"chat_receiver_visible_bubble");}
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
    }
    private static boolean bubble(View view,String text,boolean outgoing){
        if(!view.isShown())return false;
        if(view instanceof ChatMessageCell){org.telegram.messenger.MessageObject message=((ChatMessageCell)view).getMessageObject();Rect visible=new Rect();if(message!=null&&text.equals(message.messageOwner.message)&&message.isOutOwner()==outgoing&&message.messageOwner.send_state==0&&message.getId()>0&&view.getGlobalVisibleRect(visible))return true;}
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)if(bubble(((ViewGroup)view).getChildAt(i),text,outgoing))return true;
        return false;
    }
    private static void await(BooleanSupplier condition,AuthUiIntegration.Check check,String name) throws Exception {long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(40);while(!condition.getAsBoolean()&&System.nanoTime()<deadline)Thread.sleep(100);check.verify(condition.getAsBoolean(),name);}
    private static void write(Instrumentation instrumentation,JSONObject state) throws Exception {Files.write(new java.io.File(instrumentation.getTargetContext().getFilesDir(),"vyrn-test-chat-state.json").toPath(),state.toString().getBytes(StandardCharsets.UTF_8));}
}
