package xyz.eclipseofficial.vyrn.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import android.util.Base64;
import xyz.eclipseofficial.vyrn.domain.Models;

/** App-private, account-bound group-text queue. Private plaintext must never enter this queue. */
public final class OutboxStore extends SQLiteOpenHelper {
    public static final int QUEUED=0, SENT=1, FAILED=2;
    public static final class Entry {
        public String user, client, conversation, text, server;
        public int state, attempts, ordinal, date, error;
    }
    public OutboxStore(Context context,int account){super(context,"vyrn_outbox_"+account+".db",null,1);}
    @Override public void onCreate(SQLiteDatabase db){
        db.execSQL("CREATE TABLE outbox(queue_id INTEGER PRIMARY KEY AUTOINCREMENT,user_id TEXT NOT NULL,client_id TEXT NOT NULL,conversation_id TEXT NOT NULL,body TEXT,fingerprint TEXT NOT NULL,state INTEGER NOT NULL DEFAULT 0,attempts INTEGER NOT NULL DEFAULT 0,next_at INTEGER NOT NULL DEFAULT 0,server_id TEXT,ordinal INTEGER,date INTEGER,error INTEGER NOT NULL DEFAULT 0,created_at INTEGER NOT NULL,UNIQUE(user_id,client_id))");
        db.execSQL("CREATE INDEX outbox_ready ON outbox(user_id,state,next_at,created_at)");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion){throw new IllegalStateException("outbox_migration_required");}
    private Entry entry(Cursor c){Entry e=new Entry();e.user=c.getString(0);e.client=c.getString(1);e.conversation=c.getString(2);e.text=c.getString(3);e.state=c.getInt(4);e.attempts=c.getInt(5);e.server=c.getString(6);e.ordinal=c.getInt(7);e.date=c.getInt(8);e.error=c.getInt(9);return e;}
    private static final String COLUMNS="user_id,client_id,conversation_id,body,state,attempts,server_id,ordinal,date,error";
    public synchronized Entry get(String user,String client){try(Cursor c=getReadableDatabase().rawQuery("SELECT "+COLUMNS+" FROM outbox WHERE user_id=? AND client_id=?",new String[]{user,client})){return c.moveToFirst()?entry(c):null;}}
    public synchronized Entry enqueue(String user,String client,String conversation,String text) throws Exception {
        java.util.UUID.fromString(user);java.util.UUID.fromString(client);java.util.UUID.fromString(conversation);
        if(text==null || text.codePointCount(0,text.length())>16384)throw new IllegalArgumentException("invalid_message");
        String fingerprint=Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest((conversation+"\0"+text).getBytes(StandardCharsets.UTF_8)),Base64.NO_WRAP);
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {
            try(Cursor c=db.rawQuery("SELECT fingerprint,state FROM outbox WHERE user_id=? AND client_id=?",new String[]{user,client})){
                if(c.moveToFirst()){
                    if(!fingerprint.equals(c.getString(0)))throw new IllegalStateException("idempotency_conflict");
                    if(c.getInt(1)==FAILED){ContentValues v=new ContentValues();v.put("state",QUEUED);v.put("attempts",0);v.put("next_at",0);v.put("error",0);db.update("outbox",v,"user_id=? AND client_id=?",new String[]{user,client});}
                }else{
                    if(DatabaseUtils.longForQuery(db,"SELECT count(*) FROM outbox WHERE user_id=? AND state<>1",new String[]{user})>=1000)throw new IllegalStateException("outbox_full");
                    ContentValues v=new ContentValues();v.put("user_id",user);v.put("client_id",client);v.put("conversation_id",conversation);v.put("body",text);v.put("fingerprint",fingerprint);v.put("created_at",System.currentTimeMillis());db.insertOrThrow("outbox",null,v);
                }
            }
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
        return get(user,client);
    }
    public synchronized Entry next(String user){try(Cursor c=getReadableDatabase().rawQuery("SELECT "+COLUMNS+" FROM outbox q WHERE user_id=? AND state=0 AND next_at<=? AND NOT EXISTS(SELECT 1 FROM outbox earlier WHERE earlier.user_id=q.user_id AND earlier.conversation_id=q.conversation_id AND earlier.state=0 AND earlier.queue_id<q.queue_id) ORDER BY queue_id LIMIT 1",new String[]{user,Long.toString(System.currentTimeMillis())})){return c.moveToFirst()?entry(c):null;}}
    public synchronized void accepted(Entry entry,Models.Message result){
        ContentValues v=new ContentValues();v.put("state",SENT);v.putNull("body");v.put("server_id",result.id);v.put("ordinal",result.ordinal);v.put("date",result.date);v.put("error",0);
        if(getWritableDatabase().update("outbox",v,"user_id=? AND client_id=?",new String[]{entry.user,entry.client})!=1)throw new IllegalStateException("outbox_missing");
    }
    public synchronized boolean failed(Entry entry,int status,boolean retryable){
        int attempts=entry.attempts+1;boolean retry=retryable && attempts<8;
        long delay=Math.min(60000L,1000L<<Math.min(attempts-1,6))+java.util.concurrent.ThreadLocalRandom.current().nextInt(251);
        ContentValues v=new ContentValues();v.put("attempts",attempts);v.put("state",retry?QUEUED:FAILED);v.put("error",status);v.put("next_at",System.currentTimeMillis()+delay);
        getWritableDatabase().update("outbox",v,"user_id=? AND client_id=?",new String[]{entry.user,entry.client});return retry;
    }
}
