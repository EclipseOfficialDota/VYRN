package xyz.eclipseofficial.vyrn.data;

import android.content.Context;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import xyz.eclipseofficial.vyrn.domain.Models;

/** Persist before sending; acknowledgement is delivered only after the server result is persisted. */
public final class OutboxSender implements AutoCloseable {
    private final OutboxStore store;
    private final RestRepository repository;
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor();
    private final ConcurrentHashMap<String,CompletableFuture<Models.Message>> waiting=new ConcurrentHashMap<>();
    public OutboxSender(Context context,int account,RestRepository repository){this.repository=repository;store=new OutboxStore(context,account);store.recover();worker.scheduleWithFixedDelay(this::drain,1,1,TimeUnit.SECONDS);worker.scheduleWithFixedDelay(store::prune,1,1,TimeUnit.HOURS);}
    private String key(String user,String client){return user+":"+client;}
    private Models.Message result(OutboxStore.Entry e){return new Models.Message(e.server,e.user,"",e.date,e.ordinal,false,false);}
    public synchronized CompletableFuture<Models.Message> send(String user,String conversation,String client,String text) throws Exception {
        if(!user.equals(repository.vault.load().optString("user_id")))throw new SecurityException("account_changed");
        OutboxStore.Entry entry=store.enqueue(user,client,conversation,text);
        if(entry.state==OutboxStore.SENT)return CompletableFuture.completedFuture(result(entry));
        CompletableFuture<Models.Message> future=waiting.computeIfAbsent(key(user,client),ignored->new CompletableFuture<>());
        // Re-read after attaching: the worker may have committed between enqueue and registration.
        entry=store.get(user,client);
        if(entry.state==OutboxStore.SENT){waiting.remove(key(user,client),future);future.complete(result(entry));}
        else if(entry.state==OutboxStore.FAILED){waiting.remove(key(user,client),future);future.completeExceptionally(new RestRepository.ApiException(entry.error,"send_failed"));}
        worker.execute(this::drain);return future;
    }
    private void drain(){
        try {
            String user=repository.vault.load().optString("user_id");if(user.isEmpty())return;
            OutboxStore.Entry entry=store.next(user);if(entry==null)return;
            try {
                Models.Message sent=repository.sendForUser(user,entry.conversation,entry.client,entry.text);
                store.accepted(entry,sent);
                CompletableFuture<Models.Message> future=waiting.remove(key(user,entry.client));if(future!=null)future.complete(sent);
            }catch(SecurityException changed){store.release(entry);return;
            }catch(Exception failure){
                int status=failure instanceof RestRepository.ApiException?((RestRepository.ApiException)failure).status:503;
                boolean retryable=failure instanceof IOException || failure instanceof RestRepository.ApiException && (status==429 || status>=500);
                if(!store.failed(entry,status,retryable)){
                    CompletableFuture<Models.Message> future=waiting.remove(key(user,entry.client));if(future!=null)future.completeExceptionally(failure);
                }
            }
        }catch(Exception unavailable){/* Storage/auth failure: preserve rows; never log message bodies or credentials. */}
    }
    public synchronized boolean cancel(String user,String client){
        if(!store.cancel(user,client))return false;
        CompletableFuture<Models.Message> future=waiting.remove(key(user,client));if(future!=null)future.cancel(false);return true;
    }
    @Override public void close(){worker.shutdownNow();try{if(worker.awaitTermination(40,TimeUnit.SECONDS))store.close();}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}}
}
