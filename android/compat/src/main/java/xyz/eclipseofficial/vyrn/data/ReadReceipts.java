package xyz.eclipseofficial.vyrn.data;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.json.JSONObject;

/** Persist the highest observed conversation read position before attempting transport. */
public final class ReadReceipts {
    private static final Object LOCK=new Object();
    private final SharedPreferences records;
    private String lastAttempt="";
    public ReadReceipts(Context context,int account){records=context.getSharedPreferences("vyrn_read_receipts_"+account,Context.MODE_PRIVATE);}
    private String prefix(String user,String session){UUID.fromString(user);UUID.fromString(session);return user+":"+session+":";}
    public int pending(String user,String session,String conversation){UUID.fromString(conversation);synchronized(LOCK){return records.getInt(prefix(user,session)+conversation,0);}}
    public void enqueue(String user,String session,String conversation,int max){
        UUID.fromString(conversation);if(max<1)throw new IllegalArgumentException("invalid_read_position");
        synchronized(LOCK){String key=prefix(user,session)+conversation;int previous=records.getInt(key,0);if(previous>=max)return;
            if(previous==0&&records.getAll().size()>=1000)throw new IllegalStateException("read_receipt_journal_full");
            if(!records.edit().putInt(key,max).commit()){
                SharedPreferences.Editor rollback=records.edit();if(previous==0)rollback.remove(key);else rollback.putInt(key,previous);rollback.commit();
                throw new IllegalStateException("read_receipt_storage_failed");
            }
        }
    }
    private void deliver(RestRepository repository,String user,String session,String conversation,int max) throws Exception {
        UUID.fromString(conversation);
        repository.requestForSession(user,session,"PUT","/v1/conversations/"+conversation+"/read",new JSONObject().put("max_id",max));
        synchronized(LOCK){String key=prefix(user,session)+conversation;if(records.getInt(key,0)==max&&!records.edit().remove(key).commit())throw new IllegalStateException("read_receipt_storage_failed");}
    }
    public void send(RestRepository repository,String user,String session,String conversation,int max) throws Exception {enqueue(user,session,conversation,max);deliver(repository,user,session,conversation,max);}
    public void drain(RestRepository repository) throws Exception {
        JSONObject tokens=repository.vault.load();String user=tokens.optString("user_id"),session=tokens.optString("session_id");if(user.isEmpty()||session.isEmpty())return;
        String scope=prefix(user,session),chosen=null;int max=0;
        synchronized(LOCK){Map<String,?> snapshot=new TreeMap<>(records.getAll());for(String key:snapshot.keySet())if(key.startsWith(scope)&&key.compareTo(lastAttempt)>0){chosen=key;break;}
            if(chosen==null)for(String key:snapshot.keySet())if(key.startsWith(scope)){chosen=key;break;}
            if(chosen==null)return;lastAttempt=chosen;max=records.getInt(chosen,0);
        }
        deliver(repository,user,session,chosen.substring(scope.length()),max);
    }
}
