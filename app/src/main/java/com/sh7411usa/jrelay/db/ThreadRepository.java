package com.sh7411usa.jrelay.db;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

// Only bridge inbound group MMS whose participant set matches a thread the app created; never prune signatures.
public class ThreadRepository {
    private final DbHelper dbHelper;

    public ThreadRepository(Context context) {
        dbHelper = DbHelper.getInstance(context);
    }

    public void recordThread(String signature, long subgroupId) {
        if (signature == null || signature.isEmpty()) return;
        ContentValues cv = new ContentValues();
        cv.put("signature", signature);
        cv.put("subgroup_id", subgroupId);
        cv.put("last_used_at", System.currentTimeMillis());
        dbHelper.getWritableDatabase().insertWithOnConflict(DbHelper.TABLE_KNOWN_THREADS, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public Long findSubgroupForSignature(String signature) {
        if (signature == null || signature.isEmpty()) return null;
        try (Cursor c = dbHelper.getReadableDatabase().query(DbHelper.TABLE_KNOWN_THREADS, new String[]{"subgroup_id"},
                "signature = ?", new String[]{signature}, null, null, null)) {
            return c.moveToFirst() ? Long.valueOf(c.getLong(0)) : null;
        }
    }

    public boolean markIngested(long mmsId, String outcome) {
        ContentValues cv = new ContentValues();
        cv.put("mms_id", mmsId);
        cv.put("ingested_at", System.currentTimeMillis());
        cv.put("outcome", outcome);
        return dbHelper.getWritableDatabase().insertWithOnConflict(DbHelper.TABLE_INGESTED_MMS, null, cv,
                SQLiteDatabase.CONFLICT_IGNORE) != -1;
    }

    public boolean isIngested(long mmsId) {
        try (Cursor c = dbHelper.getReadableDatabase().query(DbHelper.TABLE_INGESTED_MMS, new String[]{"mms_id"},
                "mms_id = ?", new String[]{String.valueOf(mmsId)}, null, null, null)) {
            return c.moveToFirst();
        }
    }
}
