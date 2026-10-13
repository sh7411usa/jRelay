package com.sh7411usa.jrelay.db;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.List;

public class JoinRequestRepository {

    public static final class JoinRequest {
        public final String phoneE164;
        public final String nickname;

        public JoinRequest(String phoneE164, String nickname) {
            this.phoneE164 = phoneE164;
            this.nickname = nickname;
        }
    }

    private final DbHelper dbHelper;

    public JoinRequestRepository(Context context) {
        dbHelper = DbHelper.getInstance(context);
    }

    public void upsert(String phoneE164, String nickname) {
        ContentValues cv = new ContentValues();
        cv.put("phone_e164", phoneE164);
        cv.put("nickname", nickname);
        cv.put("requested_at", System.currentTimeMillis());
        dbHelper.getWritableDatabase().insertWithOnConflict(DbHelper.TABLE_JOIN_REQUESTS, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public List<JoinRequest> getAll() {
        List<JoinRequest> result = new ArrayList<>();
        try (Cursor c = dbHelper.getReadableDatabase().query(DbHelper.TABLE_JOIN_REQUESTS,
                new String[]{"phone_e164", "nickname"}, null, null, null, null, "requested_at ASC")) {
            while (c.moveToNext()) result.add(new JoinRequest(c.getString(0), c.getString(1)));
        }
        return result;
    }

    public int count() {
        try (Cursor c = dbHelper.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM " + DbHelper.TABLE_JOIN_REQUESTS, null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    public void delete(String phoneE164) {
        if (phoneE164 == null) return; // a null bind arg throws in SQLiteProgram
        dbHelper.getWritableDatabase().delete(DbHelper.TABLE_JOIN_REQUESTS, "phone_e164 = ?", new String[]{phoneE164});
    }
}
