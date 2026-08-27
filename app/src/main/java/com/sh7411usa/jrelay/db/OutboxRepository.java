package com.sh7411usa.jrelay.db;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.List;

public class OutboxRepository {

    public static class OutboxItem {
        public long id;
        public Long memberId;
        public String phoneE164;
        public String body;
    }

    private final DbHelper dbHelper;

    public OutboxRepository(Context context) {
        dbHelper = DbHelper.getInstance(context);
    }

    public void enqueue(Long memberId, long messageLogId, String phoneE164, String body) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues cv = new ContentValues();
        if (memberId != null) {
            cv.put("member_id", memberId);
        }
        cv.put("message_log_id", messageLogId);
        cv.put("phone_e164", phoneE164);
        cv.put("body", body);
        cv.put("enqueued_at", System.currentTimeMillis());
        cv.put("status", "PENDING");
        db.insert(DbHelper.TABLE_OUTBOX, null, cv);
    }

    /** Atomically selects up to `limit` pending rows and flips them to SENDING so overlapping drains cannot double-send. */
    public List<OutboxItem> takeBurst(int limit) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        List<OutboxItem> list = new ArrayList<>();
        db.beginTransaction();
        try {
            Cursor c = db.query(DbHelper.TABLE_OUTBOX, null, "status = ?", new String[]{"PENDING"},
                    null, null, "enqueued_at ASC", String.valueOf(limit));
            while (c.moveToNext()) {
                OutboxItem item = new OutboxItem();
                item.id = c.getLong(c.getColumnIndexOrThrow("id"));
                int memberIdx = c.getColumnIndexOrThrow("member_id");
                item.memberId = c.isNull(memberIdx) ? null : c.getLong(memberIdx);
                item.phoneE164 = c.getString(c.getColumnIndexOrThrow("phone_e164"));
                item.body = c.getString(c.getColumnIndexOrThrow("body"));
                list.add(item);
            }
            c.close();
            for (OutboxItem item : list) {
                ContentValues cv = new ContentValues();
                cv.put("status", "SENDING");
                db.update(DbHelper.TABLE_OUTBOX, cv, "id = ?", new String[]{String.valueOf(item.id)});
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return list;
    }

    /** Messages still waiting to go out: not yet claimed for sending, or currently mid-send. */
    public int countUnsent() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery("SELECT COUNT(*) FROM " + DbHelper.TABLE_OUTBOX +
                " WHERE status IN ('PENDING', 'SENDING', 'RETRY_PENDING')", null);
        int count = 0;
        if (c.moveToFirst()) {
            count = c.getInt(0);
        }
        c.close();
        return count;
    }

    public int countPending() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery("SELECT COUNT(*) FROM " + DbHelper.TABLE_OUTBOX +
                " WHERE status = 'PENDING'", null);
        int count = 0;
        if (c.moveToFirst()) {
            count = c.getInt(0);
        }
        c.close();
        return count;
    }

    public void prepareTracking(long id, int partsTotal) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("parts_total", Math.max(1, partsTotal));
        cv.put("parts_sent", 0);
        cv.put("parts_delivered", 0);
        cv.putNull("submitted_at");
        cv.putNull("delivered_at");
        cv.putNull("error_code");
        db.execSQL("UPDATE " + DbHelper.TABLE_OUTBOX + " SET parts_total = ?, parts_sent = 0, " +
                        "parts_delivered = 0, submitted_at = NULL, delivered_at = NULL, error_code = NULL, " +
                        "last_attempt_at = ?, attempt_count = attempt_count + 1, next_retry_at = NULL WHERE id = ?",
                new Object[]{Math.max(1, partsTotal), System.currentTimeMillis(), id});
    }

    public void recordPartSent(long id, boolean success, int resultCode) {
        recordPartResult(id, success, resultCode, false);
    }

    public void recordPartDelivered(long id, boolean success, int resultCode) {
        recordPartResult(id, success, resultCode, true);
    }

    private void recordPartResult(long id, boolean success, int resultCode, boolean delivery) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        db.beginTransaction();
        try {
            Cursor c = db.query(DbHelper.TABLE_OUTBOX,
                    new String[]{"status", "parts_total", "parts_sent", "parts_delivered"},
                    "id = ?", new String[]{String.valueOf(id)}, null, null, null);
            if (!c.moveToFirst()) {
                c.close();
                db.setTransactionSuccessful();
                return;
            }
            String status = c.getString(0);
            int total = Math.max(1, c.getInt(1));
            int sent = c.getInt(2);
            int delivered = c.getInt(3);
            c.close();

            ContentValues cv = new ContentValues();
            if (!success) {
                cv.put("status", delivery ? "DELIVERY_FAILED" : "FAILED");
                cv.put("error_code", resultCode);
            } else if (delivery && !"FAILED".equals(status) && !"DELIVERY_FAILED".equals(status)) {
                delivered++;
                cv.put("parts_delivered", delivered);
                if (delivered >= total) {
                    cv.put("status", "DELIVERED");
                    cv.put("delivered_at", System.currentTimeMillis());
                }
            } else {
                sent++;
                cv.put("parts_sent", sent);
                if ("RETRY_PENDING".equals(status)) {
                    cv.put("status", "FAILED");
                    cv.putNull("next_retry_at");
                } else if (!"FAILED".equals(status) && !"DELIVERY_FAILED".equals(status) && sent >= total) {
                    cv.put("status", "SENT");
                    cv.put("submitted_at", System.currentTimeMillis());
                }
            }
            db.update(DbHelper.TABLE_OUTBOX, cv, "id = ?", new String[]{String.valueOf(id)});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public void markFailed(long id, int errorCode) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("status", "FAILED");
        cv.put("error_code", errorCode);
        db.update(DbHelper.TABLE_OUTBOX, cv, "id = ?", new String[]{String.valueOf(id)});
    }

    /** Schedules a retry only when Android accepted no segment. Returns the retry time, or null. */
    public Long scheduleAutoRetry(long id) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        db.beginTransaction();
        try {
            Cursor c = db.query(DbHelper.TABLE_OUTBOX,
                    new String[]{"status", "parts_sent", "attempt_count"}, "id = ?",
                    new String[]{String.valueOf(id)}, null, null, null);
            if (!c.moveToFirst()) {
                c.close();
                db.setTransactionSuccessful();
                return null;
            }
            String status = c.getString(0);
            int sent = c.getInt(1);
            int attempts = c.getInt(2);
            c.close();
            long delay = com.sh7411usa.jrelay.sms.SmsRetryPolicy.delayForAttemptCount(attempts);
            if (!"FAILED".equals(status) || sent != 0 || delay < 0) {
                db.setTransactionSuccessful();
                return null;
            }
            long retryAt = System.currentTimeMillis() + delay;
            ContentValues cv = new ContentValues();
            cv.put("status", "RETRY_PENDING");
            cv.put("next_retry_at", retryAt);
            db.update(DbHelper.TABLE_OUTBOX, cv, "id = ?", new String[]{String.valueOf(id)});
            db.setTransactionSuccessful();
            return retryAt;
        } finally {
            db.endTransaction();
        }
    }

    public boolean releaseScheduledRetry(long id) {
        ContentValues cv = new ContentValues();
        cv.put("status", "PENDING");
        cv.putNull("next_retry_at");
        return dbHelper.getWritableDatabase().update(DbHelper.TABLE_OUTBOX, cv,
                "id = ? AND status = 'RETRY_PENDING' AND parts_sent = 0 AND next_retry_at <= ?",
                new String[]{String.valueOf(id), String.valueOf(System.currentTimeMillis())}) == 1;
    }

    public boolean retryNow(long id) {
        ContentValues cv = new ContentValues();
        cv.put("status", "PENDING");
        cv.putNull("next_retry_at");
        return dbHelper.getWritableDatabase().update(DbHelper.TABLE_OUTBOX, cv,
                "id = ? AND status IN ('FAILED', 'RETRY_PENDING') AND parts_sent = 0",
                new String[]{String.valueOf(id)}) == 1;
    }

    /** Requeues only failures for which Android reported zero submitted segments. */
    public int retryAllSafe() {
        ContentValues cv = new ContentValues();
        cv.put("status", "PENDING");
        cv.putNull("next_retry_at");
        return dbHelper.getWritableDatabase().update(DbHelper.TABLE_OUTBOX, cv,
                "status IN ('FAILED', 'RETRY_PENDING') AND parts_sent = 0", null);
    }

    public int countByStatus(String... statuses) {
        if (statuses.length == 0) {
            return 0;
        }
        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < statuses.length; i++) {
            if (i > 0) placeholders.append(',');
            placeholders.append('?');
        }
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery("SELECT COUNT(*) FROM " + DbHelper.TABLE_OUTBOX +
                " WHERE status IN (" + placeholders + ")", statuses);
        int count = c.moveToFirst() ? c.getInt(0) : 0;
        c.close();
        return count;
    }

}
