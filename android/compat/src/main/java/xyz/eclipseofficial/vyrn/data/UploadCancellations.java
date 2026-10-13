package xyz.eclipseofficial.vyrn.data;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.UUID;

/** Opaque transfer identities only; persist cancellation before any network attempt. */
public final class UploadCancellations {
    private final SharedPreferences records;
    public UploadCancellations(Context context,int account){records=context.getSharedPreferences("vyrn_upload_cancellations_"+account,Context.MODE_PRIVATE);}
    private String prefix(String user,String session){UUID.fromString(user);UUID.fromString(session);return user+":"+session+":";}
    public synchronized void enqueue(String user,String session,String upload){
        UUID.fromString(upload);String key=prefix(user,session)+upload;
        if(records.contains(key))return;
        if(records.getAll().size()>=1000)throw new IllegalStateException("upload_cancellation_journal_full");
        if(!records.edit().putLong(key,System.currentTimeMillis()).commit()){records.edit().remove(key).apply();throw new IllegalStateException("upload_cancellation_storage_failed");}
    }
    public synchronized boolean contains(String user,String session,String upload){return records.contains(prefix(user,session)+upload);}
    public void drain(RestRepository repository) throws Exception {
        synchronized(repository.vault.guard()){
            String user=repository.vault.load().optString("user_id"),session=repository.vault.cachedSession();if(user.isEmpty()||session==null)return;
            String prefix=prefix(user,session);
            for(String key:records.getAll().keySet())if(key.startsWith(prefix)){
                String upload=key.substring(prefix.length());UUID.fromString(upload);
                repository.requestForSession(user,session,"DELETE","/v1/media-uploads/"+upload,null);
                if(!records.edit().remove(key).commit())throw new IllegalStateException("upload_cancellation_storage_failed");
                return; // One per existing recovery tick; offline failures retain the record.
            }
        }
    }
}
