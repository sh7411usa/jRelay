package com.sh7411usa.jrelay.db;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

// Mark-first, at-most-once seen-set for inbound SMS: claim BEFORE handing to CommandProcessor.
public class ProcessedSmsRepository {
    private final DbHelper dbHelper;

    public ProcessedSmsRepository(Context context) {
        dbHelper = DbHelper.getInstance(context);
    }

    // One transaction so a live claim and a catch-up claim of the same message cannot both win.
    public boolean claim(String dedupeKey, String[] refuseIfAnyExists, String[] alsoRecord, String source) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        db.beginTransaction();
        try {
            boolean owned = true;
            for (String key : refuseIfAnyExists) {
                if (exists(db, key)) { owned = false; break; }
            }
            if (owned) owned = insertOrIgnore(db, dedupeKey, source);
            if (owned) for (String key : alsoRecord) insertOrIgnore(db, key, source);
            db.setTransactionSuccessful();
            return owned;
        } finally {
            db.endTransaction();
        }
    }

    public void pruneOlderThan(long cutoffMillis) {
        dbHelper.getWritableDatabase().delete(DbHelper.TABLE_PROCESSED_SMS,
                "processed_at < ?", new String[]{String.valueOf(cutoffMillis)});
    }

    private static boolean insertOrIgnore(SQLiteDatabase db, String key, String source) {
        ContentValues cv = new ContentValues();
        cv.put("dedupe_key", key);
        cv.put("processed_at", System.currentTimeMillis());
        cv.put("source", source);
        return db.insertWithOnConflict(DbHelper.TABLE_PROCESSED_SMS, null, cv, SQLiteDatabase.CONFLICT_IGNORE) != -1;
    }

    private static boolean exists(SQLiteDatabase db, String key) {
        try (Cursor c = db.query(DbHelper.TABLE_PROCESSED_SMS, new String[]{"dedupe_key"},
                "dedupe_key = ?", new String[]{key}, null, null, null)) {
            return c.moveToFirst();
        }
    }
}
