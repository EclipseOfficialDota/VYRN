package xyz.eclipseofficial.vyrn.data;

import android.content.Context;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.BuildConfig;

/** Authenticated active-mode stream. Events and cursor are committed before acknowledgement. */
public final class RealtimeSync implements AutoCloseable {
    public interface Handler { void apply(String kind,JSONObject body) throws Exception; }
    private final RestRepository repository;
    private final InboxStore inbox;
    private final Handler handler;
    private final OkHttpClient client=new OkHttpClient.Builder().pingInterval(20,TimeUnit.SECONDS).connectTimeout(15,TimeUnit.SECONDS).readTimeout(0,TimeUnit.MILLISECONDS).build();
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean connecting=new AtomicBoolean();
    private volatile boolean closed;
    private volatile WebSocket socket;
    private int failures;
    public RealtimeSync(Context context,int account,RestRepository repository,Handler handler){this.repository=repository;this.handler=handler;inbox=new InboxStore(context,account);worker.execute(this::connect);}
    private void connect(){
        if(closed || !connecting.compareAndSet(false,true))return;
        try{
            JSONObject tokens=repository.vault.load();String user=tokens.optString("user_id"),access=tokens.optString("access_token");
            if(user.isEmpty()||access.isEmpty()){connecting.set(false);schedule(2);return;}
            String url=BuildConfig.VYRN_API_URL.replaceFirst("^https://","wss://")+"/v1/ws?cursor="+inbox.cursor(user);
            if(!url.startsWith("wss://")||url.matches("^wss://[0-9.]+.*"))throw new SecurityException("wss_hostname_required");
            Request request=new Request.Builder().url(url).header("Authorization","Bearer "+access).build();
            socket=client.newWebSocket(request,new Listener(user));
        }catch(Exception failure){connecting.set(false);schedule(nextDelay());}
    }
    private int nextDelay(){failures=Math.min(failures+1,8);return Math.min(60,1<<(Math.min(failures-1,6)))+ThreadLocalRandom.current().nextInt(2);}
    private void schedule(int seconds){if(!closed)worker.schedule(this::connect,seconds,TimeUnit.SECONDS);}
    private void scheduleProcess(String user,int attempts){
        int exponent=Math.min(Math.max(attempts,1)-1,6);
        int delay=Math.min(60,1<<exponent)+ThreadLocalRandom.current().nextInt(2);
        if(!closed)worker.schedule(()->processNow(user),delay,TimeUnit.SECONDS);
    }
    private void process(String user){
        worker.execute(()->processNow(user));
    }
    private void processNow(String user){
        while(!closed){
            try{if(!user.equals(repository.vault.load().optString("user_id")))return;}catch(Exception unavailable){return;}
            InboxStore.Entry event=inbox.next(user);if(event==null)return;
            try{handler.apply(event.kind,new JSONObject(event.body));inbox.processed(user,event.sequence);}
            catch(Exception failure){if(inbox.retry(user,event)){scheduleProcess(user,event.attempts+1);return;}continue;}
        }
    }
    private final class Listener extends WebSocketListener {
        private final String user;
        Listener(String user){this.user=user;}
        @Override public void onOpen(WebSocket webSocket,Response response){failures=0;connecting.set(false);process(user);}
        @Override public void onMessage(WebSocket webSocket,String text){
            try{
                if(text.length()>524288)throw new IllegalArgumentException("event_frame_too_large");
                JSONObject frame=new JSONObject(text);if(!"events".equals(frame.optString("type")))return;
                JSONArray events=frame.getJSONArray("events");long cursor=frame.getLong("cursor");
                inbox.persist(user,events,cursor);
                if(!webSocket.send(new JSONObject().put("type","ack").put("cursor",cursor).toString()))throw new IllegalStateException("ack_queue_failed");
                process(user);
            }catch(Exception invalid){webSocket.close(1008,"invalid event batch");}
        }
        @Override public void onClosing(WebSocket webSocket,int code,String reason){webSocket.close(code,"closing");}
        @Override public void onClosed(WebSocket webSocket,int code,String reason){socket=null;connecting.set(false);schedule(nextDelay());}
        @Override public void onFailure(WebSocket webSocket,Throwable error,Response response){
            int status=response==null?0:response.code();if(response!=null)response.close();socket=null;connecting.set(false);
            if(status==401){try{repository.me();}catch(Exception ignored){}}
            schedule(nextDelay());
        }
    }
    public long cursor(String user){return inbox.cursor(user);}
    public int pending(String user){return inbox.pending(user);}
    public int dead(String user){return inbox.dead(user);}
    @Override public void close(){closed=true;WebSocket active=socket;if(active!=null)active.close(1000,"shutdown");client.dispatcher().executorService().shutdown();worker.shutdownNow();inbox.close();}
}
