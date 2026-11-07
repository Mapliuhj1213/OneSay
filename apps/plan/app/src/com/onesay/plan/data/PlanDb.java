package com.onesay.plan.data;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.onesay.plan.core.Contract;
import com.onesay.plan.core.Schema;

/** plan.db（App 私有目录，不联网、不备份）。所有表结构来自 {@link Schema}。进程内单例，不 close。 */
public final class PlanDb extends SQLiteOpenHelper {

    public static final String NAME = "plan.db";
    private static volatile PlanDb sInst;

    public static PlanDb get(Context c) {
        if (sInst == null) {
            synchronized (PlanDb.class) {
                if (sInst == null) sInst = new PlanDb(c.getApplicationContext());
            }
        }
        return sInst;
    }

    private PlanDb(Context c) {
        super(c, NAME, null, Contract.SCHEMA_VERSION);
    }

    @Override public void onConfigure(SQLiteDatabase db) {
        db.enableWriteAheadLogging();
    }

    @Override public void onCreate(SQLiteDatabase db) {
        for (Schema.Table t : Schema.ALL) {
            db.execSQL(t.createSql());
            for (String s : t.indexSql()) db.execSQL(s);
        }
    }

    @Override public void onUpgrade(SQLiteDatabase db, int o, int n) {
        onCreate(db);
        for (Schema.Table t : Schema.ALL) {
            java.util.Set<String> have = new java.util.HashSet<String>();
            android.database.Cursor c = db.rawQuery("PRAGMA table_info(" + t.name + ")", null);
            try {
                while (c.moveToNext()) have.add(c.getString(1));
            } finally {
                c.close();
            }
            for (String sql : t.addMissingSql(have)) db.execSQL(sql);
        }
    }

    @Override public void onDowngrade(SQLiteDatabase db, int o, int n) {
    }
}
