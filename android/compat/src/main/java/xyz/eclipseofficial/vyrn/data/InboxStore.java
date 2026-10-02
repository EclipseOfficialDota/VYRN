package xyz.eclipseofficial.vyrn.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONArray;
import org.json.JSONObject;

/** Raw durable event journal. A server cursor advances only in the same transaction as its events. */
public final class InboxStore extends SQLiteOpenHelper {
    public static final class Entry {
        public long sequence;
        public String kind, body;
        public int attempts;
    }
    public InboxStore(Context context,int account){super(context,"vyrn_inbox_"+account+".db",null,1);}
    @Override public void onCreate(SQLiteDatabase db){
        db.execSQL("CREATE TABLE cursors(user_id TEXT PRIMARY KEY,cursor INTEGER NOT NULL CHECK(cursor>=0))");
        db.execSQL("CREATE TABLE events(user_id TEXT NOT NULL,sequence INTEGER NOT NULL CHECK(sequence>0),kind TEXT NOT NULL,body TEXT NOT NULL,processed INTEGER NOT NULL DEFAULT 0,attempts INTEGER NOT NULL DEFAULT 0,created_at TEXT NOT NULL,PRIMARY KEY(user_id,sequence))");
        db.execSQL("CREATE INDEX inbox_pending ON events(user_id,processed,sequence)");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion){throw new IllegalStateException("inbox_migration_required");}
    public synchronized long cursor(String user){
        try(Cursor c=getReadableDatabase().rawQuery("SELECT cursor FROM cursors WHERE user_id=?",new String[]{user})){return c.moveToFirst()?c.getLong(0):0;}
    }
    public synchronized void persist(String user,JSONArray events,long frameCursor) throws Exception {
        java.util.UUID.fromString(user);long old=cursor(user),previous=old;
        if(frameCursor<old || events.length()>100)throw new IllegalArgumentException("invalid_event_batch");
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try{
            for(int i=0;i<events.length();i++){
                JSONObject event=events.getJSONObject(i);long sequence=event.getLong("sequence");String kind=event.getString("kind"),body=event.getJSONObject("body").toString(),created=event.getString("created_at");
                if(sequence<=previous || kind.length()<1 || kind.length()>64 || body.length()>65536 || created.length()>64)throw new IllegalArgumentException("invalid_event_batch");
                ContentValues v=new ContentValues();v.put("user_id",user);v.put("sequence",sequence);v.put("kind",kind);v.put("body",body);v.put("created_at",created);db.insertOrThrow("events",null,v);previous=sequence;
            }
            if(events.length()>0 && previous!=frameCursor || events.length()==0 && frameCursor!=old)throw new IllegalArgumentException("invalid_event_cursor");
            ContentValues cursor=new ContentValues();cursor.put("user_id",user);cursor.put("cursor",frameCursor);db.insertWithOnConflict("cursors",null,cursor,SQLiteDatabase.CONFLICT_REPLACE);
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    public synchronized Entry next(String user){
        try(Cursor c=getReadableDatabase().rawQuery("SELECT sequence,kind,body,attempts FROM events WHERE user_id=? AND processed=0 ORDER BY sequence LIMIT 1",new String[]{user})){
            if(!c.moveToFirst())return null;Entry e=new Entry();e.sequence=c.getLong(0);e.kind=c.getString(1);e.body=c.getString(2);e.attempts=c.getInt(3);return e;
        }
    }
    public synchronized void processed(String user,long sequence){
        SQLiteDatabase db=getWritableDatabase();ContentValues v=new ContentValues();v.put("processed",1);if(db.update("events",v,"user_id=? AND sequence=?",new String[]{user,Long.toString(sequence)})!=1)throw new IllegalStateException("inbox_missing");
        db.execSQL("DELETE FROM events WHERE user_id=? AND processed=1 AND sequence<(SELECT sequence FROM events WHERE user_id=? AND processed=1 ORDER BY sequence DESC LIMIT 1 OFFSET 999)",new Object[]{user,user});
    }
    public synchronized boolean retry(String user,Entry event){
        int attempts=event.attempts+1;ContentValues v=new ContentValues();v.put("attempts",attempts);if(attempts>=8)v.put("processed",2);
        if(getWritableDatabase().update("events",v,"user_id=? AND sequence=?",new String[]{user,Long.toString(event.sequence)})!=1)throw new IllegalStateException("inbox_missing");return attempts<8;
    }
    public synchronized int pending(String user){try(Cursor c=getReadableDatabase().rawQuery("SELECT count(*) FROM events WHERE user_id=? AND processed=0",new String[]{user})){c.moveToFirst();return c.getInt(0);}}
    public synchronized int dead(String user){try(Cursor c=getReadableDatabase().rawQuery("SELECT count(*) FROM events WHERE user_id=? AND processed=2",new String[]{user})){c.moveToFirst();return c.getInt(0);}}
}
