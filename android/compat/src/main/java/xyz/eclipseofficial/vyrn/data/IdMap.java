package xyz.eclipseofficial.vyrn.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import xyz.eclipseofficial.vyrn.domain.Models;

/** Local stable IDs isolate the Telegram UI's integer identifiers from VYRN UUIDs. */
public final class IdMap extends SQLiteOpenHelper {
    public IdMap(Context context,int account){super(context,"vyrn_ids_"+account+".db",null,3);}
    public void onCreate(SQLiteDatabase db){db.execSQL("CREATE TABLE ids(id INTEGER PRIMARY KEY AUTOINCREMENT,kind TEXT NOT NULL,uuid TEXT NOT NULL,UNIQUE(kind,uuid))");createMessageMap(db);createObservedMessages(db);}
    private void createMessageMap(SQLiteDatabase db){db.execSQL("CREATE TABLE message_ids(id INTEGER PRIMARY KEY CHECK(id>0),uuid TEXT NOT NULL UNIQUE)");}
    private void createObservedMessages(SQLiteDatabase db){db.execSQL("CREATE TABLE observed_messages(uuid TEXT PRIMARY KEY,conversation TEXT NOT NULL,sender TEXT NOT NULL,revision INTEGER NOT NULL CHECK(revision>0),body_hash BLOB)");}
    public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion){if(oldVersion<1||oldVersion>2||newVersion!=3)throw new IllegalStateException("id_map_migration_required");if(oldVersion==1)createMessageMap(db);createObservedMessages(db);}
    private static byte[] bodyHash(String text){try{return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));}catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
    public synchronized void observe(Models.Message message){
        if(message.revision<1||message.conversation==null)return;
        message(message.id,message.ordinal);SQLiteDatabase db=getWritableDatabase();byte[] hash=message.encrypted||message.deleted?null:bodyHash(message.text);
        db.beginTransaction();try {
        try(Cursor c=db.rawQuery("SELECT conversation,sender,revision,body_hash FROM observed_messages WHERE uuid=?",new String[]{message.id})){
            if(c.moveToFirst()){
                if(!message.conversation.equals(c.getString(0))||!message.sender.equals(c.getString(1)))throw new IllegalStateException("message_identity_changed");
                int previous=c.getInt(2);if(message.revision<previous)return;
                if(message.revision==previous){if(Arrays.equals(hash,c.getBlob(3)))return;if(!message.deleted)throw new IllegalStateException("message_revision_changed");}
            }
        }
        ContentValues value=new ContentValues();value.put("uuid",message.id);value.put("conversation",message.conversation);value.put("sender",message.sender);value.put("revision",message.revision);value.put("body_hash",hash);db.insertWithOnConflict("observed_messages",null,value,SQLiteDatabase.CONFLICT_REPLACE);
        db.setTransactionSuccessful();}finally{db.endTransaction();}
    }
    public synchronized int editRevision(String sender,String conversation,long localId,String originalText){
        String uuid=remote("message",localId);
        try(Cursor c=getReadableDatabase().rawQuery("SELECT revision,body_hash FROM observed_messages WHERE uuid=? AND sender=? AND conversation=?",new String[]{uuid,sender,conversation})){
            if(!c.moveToFirst()||originalText==null||c.isNull(1)||!Arrays.equals(bodyHash(originalText),c.getBlob(1)))throw new IllegalStateException("edit_snapshot_unavailable");return c.getInt(0);
        }
    }
    public synchronized int message(String uuid,int ordinal){
        if(ordinal<=0)throw new IllegalArgumentException("invalid_message_order");
        SQLiteDatabase db=getWritableDatabase();ContentValues v=new ContentValues();v.put("id",ordinal);v.put("uuid",uuid);db.insertWithOnConflict("message_ids",null,v,SQLiteDatabase.CONFLICT_IGNORE);
        try(Cursor c=db.rawQuery("SELECT uuid FROM message_ids WHERE id=?",new String[]{Integer.toString(ordinal)})){if(!c.moveToFirst()||!uuid.equals(c.getString(0)))throw new IllegalStateException("message_identity_changed");}
        return ordinal;
    }
    public synchronized int local(String kind,String uuid){
        if(kind.equals("message"))throw new IllegalArgumentException("server_message_order_required");
        SQLiteDatabase db=getWritableDatabase();ContentValues v=new ContentValues();v.put("kind",kind);v.put("uuid",uuid);db.insertWithOnConflict("ids",null,v,SQLiteDatabase.CONFLICT_IGNORE);
        try(Cursor c=db.rawQuery("SELECT id FROM ids WHERE kind=? AND uuid=?",new String[]{kind,uuid})){if(!c.moveToFirst())throw new IllegalStateException("id_map_missing");long id=c.getLong(0);if(id>Integer.MAX_VALUE)throw new IllegalStateException("id_map_exhausted");return (int)id;}
    }
    public synchronized String remote(String kind,long id){
        String sql=kind.equals("message")?"SELECT uuid FROM message_ids WHERE id=?":"SELECT uuid FROM ids WHERE kind=? AND id=?";
        String[] args=kind.equals("message")?new String[]{Long.toString(id)}:new String[]{kind,Long.toString(id)};
        try(Cursor c=getReadableDatabase().rawQuery(sql,args)){if(!c.moveToFirst())throw new IllegalStateException("unknown_identifier");return c.getString(0);}
    }
}
