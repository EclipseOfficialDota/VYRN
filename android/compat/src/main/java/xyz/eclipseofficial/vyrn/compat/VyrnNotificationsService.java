package xyz.eclipseofficial.vyrn.compat;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.LaunchActivity;

/** User-enabled, visible connection to the self-hosted messaging server. */
public class VyrnNotificationsService extends Service {
    private static final String CHANNEL="vyrn_connection", STOP="vyrn.connection.STOP";
    private static final int NOTIFICATION=781901;
    private final Handler timer=new Handler(android.os.Looper.getMainLooper());
    private final Runnable check=new Runnable(){public void run(){
        if(!enabled()||!hasAccount()){stopSelf();return;}
        for(int account=0;account<UserConfig.MAX_ACCOUNT_COUNT;account++)
            if(UserConfig.getInstance(account).isClientActivated())VyrnBridge.forAccount(account);
        timer.postDelayed(this,30000);
    }};

    public static boolean enabled(){return MessagesController.getGlobalNotificationsSettings().getBoolean("pushService",false);}
    private static boolean hasAccount(){for(int account=0;account<UserConfig.MAX_ACCOUNT_COUNT;account++)if(UserConfig.getInstance(account).isClientActivated())return true;return false;}
    public static void refresh(){
        Context context=ApplicationLoader.applicationContext;
        // Application.onCreate calls this before LaunchActivity loads saved accounts.
        ApplicationLoader.postInitApplication();
        Intent intent=new Intent(context,org.telegram.messenger.NotificationsService.class);
        if(!enabled()||!hasAccount()){context.stopService(intent);return;}
        try{context.startForegroundService(intent);}
        catch(RuntimeException unavailable){
            android.util.Log.w("VyrnConnection","start_failed="+unavailable.getClass().getSimpleName());
            if(!ApplicationLoader.mainInterfacePaused)android.widget.Toast.makeText(context,
                fa()?"اتصال پس‌زمینه شروع نشد؛ اپ را باز نگه دارید و دوباره فعال کنید.":"Background connection could not start. Keep the app open and enable it again.",android.widget.Toast.LENGTH_LONG).show();
        }
    }
    private static boolean fa(){return "fa".equals(java.util.Locale.getDefault().getLanguage());}

    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent!=null&&STOP.equals(intent.getAction())){
            MessagesController.getGlobalNotificationsSettings().edit().putBoolean("pushService",false).commit();
            stopSelf();return START_NOT_STICKY;
        }
        if(!enabled()){stopSelf();return START_NOT_STICKY;}
        NotificationManager manager=getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL,fa()?"اتصال پس‌زمینه":"Background connection",NotificationManager.IMPORTANCE_LOW));
        PendingIntent open=PendingIntent.getActivity(this,NOTIFICATION,new Intent(this,LaunchActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop=PendingIntent.getService(this,NOTIFICATION,new Intent(this,org.telegram.messenger.NotificationsService.class).setAction(STOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification notification=new Notification.Builder(this,CHANNEL)
            .setSmallIcon(org.telegram.messenger.R.drawable.notification).setContentTitle("Vyrn")
            .setContentText(fa()?"اتصال پس‌زمینه فعال است؛ مصرف باتری افزایش می‌یابد.":"Background connection enabled; uses additional battery.")
            .setContentIntent(open).setOngoing(true).setShowWhen(false)
            .addAction(new Notification.Action.Builder(null,fa()?"خاموش کردن":"Turn off",stop).build()).build();
        if(Build.VERSION.SDK_INT>=34)startForeground(NOTIFICATION,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(NOTIFICATION,notification);
        ApplicationLoader.postInitApplication();
        timer.removeCallbacks(check);check.run();
        return hasAccount()?START_STICKY:START_NOT_STICKY;
    }
    @Override public void onDestroy(){timer.removeCallbacks(check);stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}
