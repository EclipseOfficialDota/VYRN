package xyz.eclipseofficial.vyrn.test;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.NotificationManager;
import android.content.Intent;
import android.os.PowerManager;
import android.service.notification.StatusBarNotification;
import android.view.KeyEvent;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.LaunchActivity;
import xyz.eclipseofficial.vyrn.compat.VyrnBridge;
import xyz.eclipseofficial.vyrn.data.IdMap;
import xyz.eclipseofficial.vyrn.data.RestRepository;
import xyz.eclipseofficial.vyrn.data.TokenVault;

/** Live-process background delivery. Sender is a distinct REST fixture, not a second phone. */
final class NotificationUiIntegration {
    static void run(Instrumentation test,Activity activity,JSONObject fixture,AuthUiIntegration.Check check) throws Exception {
        AuthUiIntegration.registration(test,activity,fixture,check);
        AuthUiIntegration.handlePrompts(test,activity,check);
        int account=UserConfig.selectedAccount;RestRepository recipient=VyrnBridge.forAccount(account).repository;
        RestRepository sender=new RestRepository(new TokenVault(test.getTargetContext(),9005));
        JSONObject tokens=(JSONObject)sender.request("POST","/v1/auth/register",new JSONObject().put("email","notification-"+UUID.randomUUID()+"@vyrn.local").put("password",fixture.getString("password")).put("invite_code",fixture.getString("invite")).put("display_name","Notification sender").put("device_id",UUID.randomUUID().toString()).put("device_name","Notification fixture"),false);
        sender.vault.save(tokens);String owner=tokens.getString("user_id");
        JSONObject group=(JSONObject)sender.requestForUser(owner,"POST","/v1/conversations",new JSONObject().put("kind","group").put("title","Notification test").put("members",new JSONArray().put(recipient.me().id)),true);
        long dialog;try(IdMap ids=new IdMap(test.getTargetContext(),account)){dialog=-ids.local("conversation",group.getString("id"));}
        NotificationManager manager=test.getTargetContext().getSystemService(NotificationManager.class);
        long permissionDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while(!manager.areNotificationsEnabled()&&System.nanoTime()<permissionDeadline){AuthUiIntegration.handlePrompts(test,activity,check);Thread.sleep(200);}
        check.verify(manager.areNotificationsEnabled(),"notification_permission_enabled");manager.cancelAll();
        PowerManager power=test.getTargetContext().getSystemService(PowerManager.class);
        test.getTargetContext().startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        key(test,KeyEvent.KEYCODE_SLEEP);
        try {
            long backgroundDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
            while((power.isInteractive()||!org.telegram.messenger.ApplicationLoader.mainInterfacePaused)&&System.nanoTime()<backgroundDeadline)Thread.sleep(200);
            if(power.isInteractive()||!org.telegram.messenger.ApplicationLoader.mainInterfacePaused)java.nio.file.Files.write(new java.io.File(test.getTargetContext().getFilesDir(),"vyrn-test-notification.json").toPath(),new JSONObject().put("interactive",power.isInteractive()).put("paused",org.telegram.messenger.ApplicationLoader.mainInterfacePaused).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            check.verify(!power.isInteractive()&&org.telegram.messenger.ApplicationLoader.mainInterfacePaused,"notification_recipient_background_screen_off");
            sender.requestForUser(owner,"POST","/v1/conversations/"+group.getString("id")+"/messages",new JSONObject().put("client_id",UUID.randomUUID().toString()).put("encrypted",false).put("payload",new JSONObject().put("text","Background notification test")),true);
            StatusBarNotification found=null;long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(35);
            while(found==null&&System.nanoTime()<deadline){for(StatusBarNotification item:manager.getActiveNotifications())if(item.getNotification().contentIntent!=null&&(item.getNotification().flags&android.app.Notification.FLAG_GROUP_SUMMARY)==0&&hasFixtureText(item.getNotification(),"Background notification test")){found=item;break;}if(found==null)Thread.sleep(200);}
            if(found==null){
                JSONObject diagnostic=new JSONObject().put("active_notifications",manager.getActiveNotifications().length).put("server_messages",recipient.liveMessages(group.getString("id"),null,100).size()).put("connection_state",VyrnBridge.connectionState(account));
                diagnostic.put("native_time",org.telegram.tgnet.ConnectionsManager.getInstance(account).getCurrentTime()).put("global_notifications",org.telegram.messenger.NotificationsController.getInstance(account).isGlobalNotificationsEnabled(dialog,false,false));
                test.runOnMainSync(()->{try{org.telegram.tgnet.TLRPC.Dialog nativeDialog=org.telegram.messenger.MessagesController.getInstance(account).dialogs_dict.get(dialog);diagnostic.put("dialog_unread",nativeDialog==null?-1:nativeDialog.unread_count);}catch(Exception failure){throw new AssertionError(failure);}});
                try(xyz.eclipseofficial.vyrn.data.InboxStore inbox=new xyz.eclipseofficial.vyrn.data.InboxStore(test.getTargetContext(),account)){String user=recipient.me().id;diagnostic.put("pending_events",inbox.pending(user)).put("dead_events",inbox.dead(user)).put("event_cursor",inbox.cursor(user));}
                java.lang.reflect.Field messages=org.telegram.messenger.NotificationsController.class.getDeclaredField("pushMessages");messages.setAccessible(true);diagnostic.put("native_notification_messages",((java.util.List<?>)messages.get(org.telegram.messenger.NotificationsController.getInstance(account))).size());
                java.nio.file.Files.write(new java.io.File(test.getTargetContext().getFilesDir(),"vyrn-test-notification.json").toPath(),diagnostic.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            check.verify(found!=null,"notification_actual_system_notification_received_screen_off");
            key(test,KeyEvent.KEYCODE_WAKEUP);found.getNotification().contentIntent.send();
            boolean[] opened={false};deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
            while(!opened[0]&&System.nanoTime()<deadline){test.runOnMainSync(()->opened[0]=LaunchActivity.getLastFragment() instanceof ChatActivity&&((ChatActivity)LaunchActivity.getLastFragment()).getDialogId()==dialog);if(!opened[0])Thread.sleep(100);}
            check.verify(opened[0],"notification_original_content_action_opens_correct_conversation");
            boolean read=false;deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(25);
            while(!read&&System.nanoTime()<deadline){JSONArray history=(JSONArray)recipient.request("GET","/v1/conversations/"+group.getString("id")+"/messages",null,true);read=history.length()==1&&history.getJSONObject(0).getJSONObject("receipts").getBoolean("read_by_me");if(!read)Thread.sleep(500);}
            check.verify(read,"notification_open_original_chat_records_server_read_receipt");
            boolean cleared=false;for(xyz.eclipseofficial.vyrn.domain.Models.Conversation current:recipient.conversations())if(current.id.equals(group.getString("id")))cleared=current.unreadCount==0;
            check.verify(cleared,"notification_open_server_unread_cleared_for_dialog_reload");
            RestRepository other=new RestRepository(new TokenVault(test.getTargetContext(),9006));
            try{
                other.vault.save((JSONObject)other.request("POST","/v1/auth/login",new JSONObject().put("email","registration-"+fixture.getString("email")).put("password",fixture.getString("password")).put("device_id",other.vault.device()).put("device_name","Read sync fixture"),false));
                check.verify(other.me().id.equals(recipient.me().id)&&!other.vault.cachedSession().equals(recipient.vault.cachedSession()),"notification_read_other_session_same_account");
                test.getTargetContext().startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));key(test,KeyEvent.KEYCODE_SLEEP);
                deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);while((power.isInteractive()||!org.telegram.messenger.ApplicationLoader.mainInterfacePaused)&&System.nanoTime()<deadline)Thread.sleep(100);
                check.verify(!power.isInteractive()&&org.telegram.messenger.ApplicationLoader.mainInterfacePaused,"notification_remote_read_receiver_background");
                JSONObject second=(JSONObject)sender.requestForUser(owner,"POST","/v1/conversations/"+group.getString("id")+"/messages",new JSONObject().put("client_id",UUID.randomUUID().toString()).put("encrypted",false).put("payload",new JSONObject().put("text","Background notification test remote")),true);
                boolean[] visible={false};deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(35);
                while(!visible[0]&&System.nanoTime()<deadline){boolean notification=false;for(StatusBarNotification item:manager.getActiveNotifications())if(hasFixtureText(item.getNotification(),"Background notification test remote"))notification=true;boolean shown=notification;test.runOnMainSync(()->{org.telegram.tgnet.TLRPC.Dialog current=org.telegram.messenger.MessagesController.getInstance(account).dialogs_dict.get(dialog);visible[0]=shown&&current!=null&&current.unread_count==1;});if(!visible[0])Thread.sleep(100);}
                check.verify(visible[0],"notification_remote_read_has_unread_and_real_notification_before_read");
                int ordinal=recipient.messageForUser(recipient.me().id,second.getString("id")).ordinal;
                other.request("PUT","/v1/conversations/"+group.getString("id")+"/read",new JSONObject().put("max_id",ordinal),true);
                boolean[] synchronizedRead={false};deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(35);
                while(!synchronizedRead[0]&&System.nanoTime()<deadline){boolean notification=false;for(StatusBarNotification item:manager.getActiveNotifications())if(hasFixtureText(item.getNotification(),"Background notification test remote"))notification=true;boolean remains=notification;test.runOnMainSync(()->{org.telegram.tgnet.TLRPC.Dialog current=org.telegram.messenger.MessagesController.getInstance(account).dialogs_dict.get(dialog);synchronizedRead[0]=!remains&&current!=null&&current.unread_count==0;});if(!synchronizedRead[0])Thread.sleep(100);}
                check.verify(synchronizedRead[0],"notification_other_session_read_stream_clears_native_unread_and_notification");
            }finally{other.vault.clear();}
        }finally{key(test,KeyEvent.KEYCODE_WAKEUP);manager.cancelAll();sender.vault.clear();}
    }
    private static void key(Instrumentation test,int code) throws Exception {
        try(java.io.InputStream result=new android.os.ParcelFileDescriptor.AutoCloseInputStream(test.getUiAutomation().executeShellCommand("input keyevent "+code))){while(result.read()!=-1){}}
    }
    private static boolean hasFixtureText(android.app.Notification notification,String target){
        CharSequence text=notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT);
        if(text!=null&&text.toString().contains(target))return true;
        android.os.Parcelable[] messages=notification.extras.getParcelableArray(android.app.Notification.EXTRA_MESSAGES);
        if(messages!=null)for(android.os.Parcelable message:messages)if(message instanceof android.os.Bundle){text=((android.os.Bundle)message).getCharSequence("text");if(text!=null&&text.toString().contains(target))return true;}
        return false;
    }
}
