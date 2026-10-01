package xyz.eclipseofficial.vyrn.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

/** Local stable IDs isolate the Telegram UI's integer identifiers from VYRN UUIDs. */
public final class IdMap extends SQLiteOpenHelper {
    public IdMap(Context context,int account){super(context,"vyrn_ids_"+account+".db",null,2);}
    public void onCreate(SQLiteDatabase db){db.execSQL("CREATE TABLE ids(id INTEGER PRIMARY KEY AUTOINCREMENT,kind TEXT NOT NULL,uuid TEXT NOT NULL,UNIQUE(kind,uuid))");createMessageMap(db);}
    private void createMessageMap(SQLiteDatabase db){db.execSQL("CREATE TABLE message_ids(id INTEGER PRIMARY KEY CHECK(id>0),uuid TEXT NOT NULL UNIQUE)");}
    public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion){if(oldVersion==1 && newVersion==2){createMessageMap(db);return;}throw new IllegalStateException("id_map_migration_required");}
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
