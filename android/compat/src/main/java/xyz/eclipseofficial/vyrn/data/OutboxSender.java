package xyz.eclipseofficial.vyrn.data;

import android.content.Context;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.function.Consumer;
import org.json.JSONObject;
import xyz.eclipseofficial.vyrn.domain.Models;

/** Persist before sending; acknowledgement is delivered only after the server result is persisted. */
public final class OutboxSender implements AutoCloseable {
    private final OutboxStore store;
    private final RestRepository repository;
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor();
    private final ConcurrentHashMap<String,CompletableFuture<Models.Message>> waiting=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,CompletableFuture<Boolean>> cancellations=new ConcurrentHashMap<>();
    private volatile Consumer<List<OutboxStore.UiResolution>> uiListener;
    public OutboxSender(Context context,int account,RestRepository repository){this.repository=repository;store=new OutboxStore(context,account);store.recover();worker.scheduleWithFixedDelay(this::drain,1,1,TimeUnit.SECONDS);worker.scheduleWithFixedDelay(this::publishUi,1,1,TimeUnit.SECONDS);worker.scheduleWithFixedDelay(store::prune,1,1,TimeUnit.HOURS);}
    public void setUiListener(Consumer<List<OutboxStore.UiResolution>> listener){uiListener=listener;}
    /** Share the existing account worker lifecycle; the callback must only enqueue bounded work. */
    public void setRecoveryListener(Runnable listener){worker.scheduleWithFixedDelay(()->{try{listener.run();}catch(Exception unavailable){/* Preserve durable work for the next tick. */}},1,5,TimeUnit.SECONDS);}
    private void publishUi(){try{Consumer<List<OutboxStore.UiResolution>> listener=uiListener;if(listener==null)return;String user=repository.vault.load().optString("user_id");List<OutboxStore.UiResolution> ready=store.resolvedUi(user);if(!ready.isEmpty())listener.accept(ready);}catch(Exception unavailable){/* Keep the journal for the next attempt. */}}
    public void cancelFromUi(String user,String client,String conversation,String text,int localId,long dialogId,long randomId) throws Exception {
        if(!user.equals(repository.vault.load().optString("user_id")))throw new SecurityException("account_changed");
        store.queueUiCancellation(user,client,conversation,text,localId,dialogId,randomId);worker.execute(this::drain);worker.execute(this::publishUi);
    }
    public void acknowledgeUi(OutboxStore.UiResolution resolution){store.acknowledgeUi(resolution);}
    private String key(String user,String client){return user+":"+client;}
    private Models.Message result(OutboxStore.Entry e){return new Models.Message(e.server,e.user,"",e.date,e.ordinal,false,false);}
    public CompletableFuture<Models.Message> send(String user,String conversation,String client,String text) throws Exception {
        if(!user.equals(repository.vault.load().optString("user_id")))throw new SecurityException("account_changed");
        OutboxStore.Entry entry=store.enqueue(user,client,conversation,text);
        if(entry.state==OutboxStore.SENT)return CompletableFuture.completedFuture(result(entry));
        CompletableFuture<Models.Message> future=waiting.computeIfAbsent(key(user,client),ignored->new CompletableFuture<>());
        // Re-read after attaching: the worker may have committed between enqueue and registration.
        entry=store.get(user,client);
        if(entry.state==OutboxStore.SENT){waiting.remove(key(user,client),future);future.complete(result(entry));}
        else if(entry.state==OutboxStore.FAILED){waiting.remove(key(user,client),future);future.completeExceptionally(new RestRepository.ApiException(entry.error,"send_failed"));}
        else if(entry.state==OutboxStore.CANCELLED){waiting.remove(key(user,client),future);future.cancel(false);}
        worker.execute(this::drain);return future;
    }
    private void drain(){
        try {
            String user=repository.vault.load().optString("user_id");if(user.isEmpty())return;
            OutboxStore.Entry entry=store.next(user);if(entry==null)return;
            try {
                Models.Message sent;
                if(entry.cancellation){
                    JSONObject reply=(JSONObject)repository.requestForUser(user,"PUT","/v1/conversations/"+entry.conversation+"/sends/"+entry.client+"/cancel",null,true);
                    if("cancelled".equals(reply.getString("status"))){store.cancelled(entry);completeCancellation(user,entry.client,true);return;}
                    if(!"committed".equals(reply.getString("status")))throw new IOException("invalid_cancel_response");
                    sent=repository.messageForUser(user,reply.getString("message_id"));
                }else sent=repository.sendForUser(user,entry.conversation,entry.client,entry.text);
                store.accepted(entry,sent);
                CompletableFuture<Models.Message> future=waiting.remove(key(user,entry.client));if(future!=null)future.complete(sent);
                completeCancellation(user,entry.client,false);
            }catch(SecurityException changed){store.release(entry);return;
            }catch(Exception failure){
                int status=failure instanceof RestRepository.ApiException?((RestRepository.ApiException)failure).status:503;
                boolean retryable=failure instanceof IOException || failure instanceof RestRepository.ApiException && (status==429 || status>=500);
                if(!store.failed(entry,status,retryable)){
                    CompletableFuture<Models.Message> future=waiting.remove(key(user,entry.client));if(future!=null)future.completeExceptionally(failure);
                    CompletableFuture<Boolean> cancellation=cancellations.remove(key(user,entry.client));if(cancellation!=null)cancellation.completeExceptionally(failure);
                }
            }
        }catch(Exception unavailable){/* Storage/auth failure: preserve rows; never log message bodies or credentials. */}
    }
    public boolean cancel(String user,String client){
        if(!store.cancel(user,client))return false;
        CompletableFuture<Models.Message> future=waiting.remove(key(user,client));if(future!=null)future.cancel(false);return true;
    }
    /** True means cancellation was confirmed; false means the server already committed the send. */
    public CompletableFuture<Boolean> cancelDurably(String user,String client) throws Exception {
        if(!user.equals(repository.vault.load().optString("user_id")))throw new SecurityException("account_changed");
        return cancelForOrigin(user,client);
    }
    /** The bridge supplies the immutable originating user from its request token. No network/auth lock is needed to persist intent. */
    public CompletableFuture<Boolean> cancelForOrigin(String user,String client) throws Exception {
        CompletableFuture<Boolean> future=cancellations.computeIfAbsent(key(user,client),ignored->new CompletableFuture<>());
        try {
            if(store.cancel(user,client)){completeCancellation(user,client,true);return future;}
            OutboxStore.Entry entry=store.requestCancellation(user,client);
            if(entry.state==OutboxStore.CANCELLED)completeCancellation(user,client,true);
            else worker.execute(this::drain);
            return future;
        }catch(Exception failure){cancellations.remove(key(user,client),future);future.completeExceptionally(failure);throw failure;}
    }
    private void completeCancellation(String user,String client,boolean cancelled){
        if(cancelled){CompletableFuture<Models.Message> message=waiting.remove(key(user,client));if(message!=null)message.cancel(false);}
        CompletableFuture<Boolean> future=cancellations.remove(key(user,client));if(future!=null)future.complete(cancelled);
    }
    @Override public void close(){worker.shutdownNow();try{if(worker.awaitTermination(40,TimeUnit.SECONDS))store.close();}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}}
}
