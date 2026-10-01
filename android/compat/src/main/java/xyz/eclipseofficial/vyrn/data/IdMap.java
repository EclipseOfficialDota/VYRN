package xyz.eclipseofficial.vyrn.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

/** Local stable IDs isolate the Telegram UI's integer identifiers from VYRN UUIDs. */
public final class IdMap extends SQLiteOpenHelper {
    public IdMap(Context context,int account){super(context,"vyrn_ids_"+account+".db",null,1);}
    public void onCreate(SQLiteDatabase db){db.execSQL("CREATE TABLE ids(id INTEGER PRIMARY KEY AUTOINCREMENT,kind TEXT NOT NULL,uuid TEXT NOT NULL,UNIQUE(kind,uuid))");}
    public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion){throw new IllegalStateException("id_map_migration_required");}
    public synchronized int local(String kind,String uuid){
        SQLiteDatabase db=getWritableDatabase();ContentValues v=new ContentValues();v.put("kind",kind);v.put("uuid",uuid);db.insertWithOnConflict("ids",null,v,SQLiteDatabase.CONFLICT_IGNORE);
        try(Cursor c=db.rawQuery("SELECT id FROM ids WHERE kind=? AND uuid=?",new String[]{kind,uuid})){if(!c.moveToFirst())throw new IllegalStateException("id_map_missing");long id=c.getLong(0);if(id>Integer.MAX_VALUE)throw new IllegalStateException("id_map_exhausted");return (int)id;}
    }
    public synchronized String remote(String kind,long id){try(Cursor c=getReadableDatabase().rawQuery("SELECT uuid FROM ids WHERE kind=? AND id=?",new String[]{kind,Long.toString(id)})){if(!c.moveToFirst())throw new IllegalStateException("unknown_identifier");return c.getString(0);}}
}
