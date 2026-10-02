package xyz.eclipseofficial.vyrn.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.List;
import android.util.Base64;
import xyz.eclipseofficial.vyrn.domain.Models;

/** App-private, account-bound group-text queue. Private plaintext must never enter this queue. */
public final class OutboxStore extends SQLiteOpenHelper {
    public static final int QUEUED=0, SENT=1, FAILED=2, SENDING=3, CANCELLED=4;
    public static final class Entry {
        public String user, client, conversation, text, server;
        public int state, attempts, ordinal, date, error;
        public boolean cancellation;
    }
    public static final class UiResolution {public String user,client;public int localId,state,ordinal,date;public long dialogId,randomId;public String server;}
    public OutboxStore(Context context,int account){super(context,"vyrn_outbox_"+account+".db",null,4);}
    private void createUiJournal(SQLiteDatabase db){db.execSQL("CREATE TABLE ui_cancellations(user_id TEXT NOT NULL,client_id TEXT NOT NULL,local_id INTEGER NOT NULL,dialog_id INTEGER NOT NULL,random_id INTEGER NOT NULL,PRIMARY KEY(user_id,client_id))");}
    @Override public void onCreate(SQLiteDatabase db){
        db.execSQL("CREATE TABLE outbox(queue_id INTEGER PRIMARY KEY AUTOINCREMENT,user_id TEXT NOT NULL,client_id TEXT NOT NULL,conversation_id TEXT NOT NULL,body TEXT,fingerprint TEXT NOT NULL,state INTEGER NOT NULL DEFAULT 0,attempts INTEGER NOT NULL DEFAULT 0,attempted INTEGER NOT NULL DEFAULT 0,next_at INTEGER NOT NULL DEFAULT 0,server_id TEXT,ordinal INTEGER,date INTEGER,error INTEGER NOT NULL DEFAULT 0,created_at INTEGER NOT NULL,completed_at INTEGER,UNIQUE(user_id,client_id))");
        db.execSQL("CREATE INDEX outbox_ready ON outbox(user_id,state,next_at,created_at)");
        db.execSQL("ALTER TABLE outbox ADD COLUMN cancel_requested INTEGER NOT NULL DEFAULT 0");
        createUiJournal(db);
    }
    @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion){if(oldVersion<2){db.execSQL("ALTER TABLE outbox ADD COLUMN completed_at INTEGER");db.execSQL("ALTER TABLE outbox ADD COLUMN attempted INTEGER NOT NULL DEFAULT 1");db.execSQL("UPDATE outbox SET completed_at=created_at WHERE state=1");}if(oldVersion<3)db.execSQL("ALTER TABLE outbox ADD COLUMN cancel_requested INTEGER NOT NULL DEFAULT 0");if(oldVersion<4)createUiJournal(db);}
    private Entry entry(Cursor c){Entry e=new Entry();e.user=c.getString(0);e.client=c.getString(1);e.conversation=c.getString(2);e.text=c.getString(3);e.state=c.getInt(4);e.attempts=c.getInt(5);e.server=c.getString(6);e.ordinal=c.getInt(7);e.date=c.getInt(8);e.error=c.getInt(9);e.cancellation=c.getInt(10)!=0;return e;}
    private static final String COLUMNS="user_id,client_id,conversation_id,body,state,attempts,server_id,ordinal,date,error,cancel_requested";
    public synchronized Entry get(String user,String client){try(Cursor c=getReadableDatabase().rawQuery("SELECT "+COLUMNS+" FROM outbox WHERE user_id=? AND client_id=?",new String[]{user,client})){return c.moveToFirst()?entry(c):null;}}
    public synchronized Entry enqueue(String user,String client,String conversation,String text) throws Exception {
        java.util.UUID.fromString(user);java.util.UUID.fromString(client);java.util.UUID.fromString(conversation);
        if(text==null || text.codePointCount(0,text.length())>16384)throw new IllegalArgumentException("invalid_message");
        String fingerprint=Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest((conversation+"\0"+text).getBytes(StandardCharsets.UTF_8)),Base64.NO_WRAP);
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {
            try(Cursor c=db.rawQuery("SELECT fingerprint,state,cancel_requested FROM outbox WHERE user_id=? AND client_id=?",new String[]{user,client})){
                if(c.moveToFirst()){
                    if(!fingerprint.equals(c.getString(0)))throw new IllegalStateException("idempotency_conflict");
                    if(c.getInt(2)!=0 || c.getInt(1)==CANCELLED)throw new IllegalStateException("send_cancelled_or_pending");
                    if(c.getInt(1)==FAILED){ContentValues v=new ContentValues();v.put("state",QUEUED);v.put("body",text);v.put("attempts",0);v.put("next_at",0);v.put("error",0);v.putNull("completed_at");db.update("outbox",v,"user_id=? AND client_id=?",new String[]{user,client});}
                }else{
                    if(DatabaseUtils.longForQuery(db,"SELECT count(*) FROM outbox WHERE user_id=? AND state IN (0,2,3)",new String[]{user})>=1000)throw new IllegalStateException("outbox_full");
                    ContentValues v=new ContentValues();v.put("user_id",user);v.put("client_id",client);v.put("conversation_id",conversation);v.put("body",text);v.put("fingerprint",fingerprint);v.put("created_at",System.currentTimeMillis());db.insertOrThrow("outbox",null,v);
                }
            }
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
        return get(user,client);
    }
    public synchronized void recover(){SQLiteDatabase db=getWritableDatabase();ContentValues v=new ContentValues();v.put("state",QUEUED);v.put("next_at",0);db.update("outbox",v,"state=?",new String[]{Integer.toString(SENDING)});prune(db);}
    public synchronized Entry next(String user){
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try(Cursor c=db.rawQuery("SELECT "+COLUMNS+" FROM outbox q WHERE user_id=? AND state=0 AND next_at<=? AND (cancel_requested=1 OR NOT EXISTS(SELECT 1 FROM outbox earlier WHERE earlier.user_id=q.user_id AND earlier.conversation_id=q.conversation_id AND earlier.state IN (0,3) AND earlier.queue_id<q.queue_id)) ORDER BY cancel_requested DESC,queue_id LIMIT 1",new String[]{user,Long.toString(System.currentTimeMillis())})){
            if(!c.moveToFirst()){db.setTransactionSuccessful();return null;}Entry result=entry(c);ContentValues claimed=new ContentValues();claimed.put("state",SENDING);claimed.put("attempted",1);
            if(db.update("outbox",claimed,"user_id=? AND client_id=? AND state=?",new String[]{user,result.client,Integer.toString(QUEUED)})!=1)throw new IllegalStateException("outbox_claim_failed");result.state=SENDING;db.setTransactionSuccessful();return result;
        }finally{db.endTransaction();}
    }
    public synchronized void accepted(Entry entry,Models.Message result){
        ContentValues v=new ContentValues();v.put("state",SENT);v.put("cancel_requested",0);v.putNull("body");v.put("server_id",result.id);v.put("ordinal",result.ordinal);v.put("date",result.date);v.put("error",0);v.put("completed_at",System.currentTimeMillis());
        if(getWritableDatabase().update("outbox",v,"user_id=? AND client_id=?",new String[]{entry.user,entry.client})!=1)throw new IllegalStateException("outbox_missing");
    }
    public synchronized boolean failed(Entry entry,int status,boolean retryable){
        Entry current=get(entry.user,entry.client);
        // A cancellation recorded during an HTTP send takes precedence over its retry.
        if(current!=null && current.cancellation && !entry.cancellation){ContentValues v=new ContentValues();v.put("state",QUEUED);v.put("attempts",0);v.put("next_at",0);getWritableDatabase().update("outbox",v,"user_id=? AND client_id=?",new String[]{entry.user,entry.client});return true;}
        int attempts=entry.attempts+1;boolean retry=retryable && attempts<8;
        long delay=Math.min(60000L,1000L<<Math.min(attempts-1,6))+java.util.concurrent.ThreadLocalRandom.current().nextInt(251);
        ContentValues v=new ContentValues();v.put("attempts",attempts);v.put("state",retry?QUEUED:FAILED);v.put("error",status);v.put("next_at",System.currentTimeMillis()+delay);
        if(getWritableDatabase().update("outbox",v,"user_id=? AND client_id=?",new String[]{entry.user,entry.client})!=1)throw new IllegalStateException("outbox_missing");return retry;
    }
    public synchronized void release(Entry entry){ContentValues v=new ContentValues();v.put("state",QUEUED);getWritableDatabase().update("outbox",v,"user_id=? AND client_id=? AND state=?",new String[]{entry.user,entry.client,Integer.toString(SENDING)});}
    /** Only an entry never claimed by a sender can be proven unsent locally. */
    public synchronized boolean cancel(String user,String client){ContentValues v=new ContentValues();v.put("state",CANCELLED);v.putNull("body");v.put("completed_at",System.currentTimeMillis());return getWritableDatabase().update("outbox",v,"user_id=? AND client_id=? AND state=? AND attempted=0 AND cancel_requested=0",new String[]{user,client,Integer.toString(QUEUED)})==1;}
    public synchronized void prune(){prune(getWritableDatabase());}
    public synchronized Entry requestCancellation(String user,String client){
        Entry entry=get(user,client);if(entry==null)throw new IllegalStateException("outbox_missing");if(entry.state==CANCELLED)return entry;
        ContentValues v=new ContentValues();v.put("cancel_requested",1);if(entry.state!=SENDING)v.put("state",QUEUED);v.put("attempts",0);v.put("next_at",0);v.putNull("completed_at");
        getWritableDatabase().update("outbox",v,"user_id=? AND client_id=?",new String[]{user,client});return get(user,client);
    }
    public synchronized void cancelled(Entry entry){ContentValues v=new ContentValues();v.put("state",CANCELLED);v.put("cancel_requested",0);v.putNull("body");v.put("completed_at",System.currentTimeMillis());if(getWritableDatabase().update("outbox",v,"user_id=? AND client_id=?",new String[]{entry.user,entry.client})!=1)throw new IllegalStateException("outbox_missing");}
    /** Atomically retain the original UI identity and cancellation decision before any local removal. */
    public synchronized void queueUiCancellation(String user,String client,String conversation,String text,int localId,long dialogId,long randomId) throws Exception {
        if(localId>=0 || dialogId>=0 || randomId==0 || !client.equals(java.util.UUID.nameUUIDFromBytes((user+":"+randomId).getBytes(StandardCharsets.UTF_8)).toString()))throw new IllegalArgumentException("invalid_ui_identity");
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {
            try(Cursor c=db.rawQuery("SELECT local_id,dialog_id,random_id FROM ui_cancellations WHERE user_id=? AND client_id=?",new String[]{user,client})){
                if(c.moveToFirst()){if(c.getInt(0)!=localId || c.getLong(1)!=dialogId || c.getLong(2)!=randomId)throw new IllegalStateException("ui_identity_conflict");}
                else{ContentValues notice=new ContentValues();notice.put("user_id",user);notice.put("client_id",client);notice.put("local_id",localId);notice.put("dialog_id",dialogId);notice.put("random_id",randomId);db.insertOrThrow("ui_cancellations",null,notice);}
            }
            Entry existing=get(user,client);
            if(existing==null){enqueue(user,client,conversation,text);requestCancellation(user,client);}
            else{
                if(!existing.conversation.equals(conversation))throw new IllegalStateException("idempotency_conflict");
                if(existing.state!=SENT && existing.state!=CANCELLED && !cancel(user,client))requestCancellation(user,client);
            }
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    public synchronized List<UiResolution> resolvedUi(String user){
        ArrayList<UiResolution> results=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT n.user_id,n.client_id,n.local_id,n.dialog_id,n.random_id,q.state,q.server_id,q.ordinal,q.date FROM ui_cancellations n JOIN outbox q ON q.user_id=n.user_id AND q.client_id=n.client_id WHERE n.user_id=? AND q.state IN (1,4) ORDER BY q.queue_id LIMIT 100",new String[]{user})){
            while(c.moveToNext()){UiResolution r=new UiResolution();r.user=c.getString(0);r.client=c.getString(1);r.localId=c.getInt(2);r.dialogId=c.getLong(3);r.randomId=c.getLong(4);r.state=c.getInt(5);r.server=c.getString(6);r.ordinal=c.getInt(7);r.date=c.getInt(8);results.add(r);}
        }return results;
    }
    public synchronized int pendingUi(String user){return (int)DatabaseUtils.longForQuery(getReadableDatabase(),"SELECT count(*) FROM ui_cancellations WHERE user_id=?",new String[]{user});}
    /** Call only after the matching Telegram cache mutation has durably completed. */
    public synchronized void acknowledgeUi(UiResolution resolution){getWritableDatabase().delete("ui_cancellations","user_id=? AND client_id=? AND local_id=? AND dialog_id=? AND random_id=?",new String[]{resolution.user,resolution.client,Integer.toString(resolution.localId),Long.toString(resolution.dialogId),Long.toString(resolution.randomId)});}
    private void prune(SQLiteDatabase db){long now=System.currentTimeMillis();String noUi=" AND NOT EXISTS(SELECT 1 FROM ui_cancellations n WHERE n.user_id=outbox.user_id AND n.client_id=outbox.client_id)";db.delete("outbox","state=? AND completed_at<?"+noUi,new String[]{Integer.toString(SENT),Long.toString(now-TimeUnit.DAYS.toMillis(7))});db.delete("outbox","state=? AND completed_at<?"+noUi,new String[]{Integer.toString(CANCELLED),Long.toString(now-TimeUnit.DAYS.toMillis(1))});}
}
