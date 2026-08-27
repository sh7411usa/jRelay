package com.sh7411usa.jrelay.db;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.sh7411usa.jrelay.model.MessageRecord;

import java.util.ArrayList;
import java.util.List;

public class MessageRepository {

    private final DbHelper dbHelper;

    public MessageRepository(Context context) {
        dbHelper = DbHelper.getInstance(context);
    }

    public long log(Long memberId, String direction, String category, String body) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues cv = new ContentValues();
        if (memberId != null) {
            cv.put("member_id", memberId);
        }
        cv.put("direction", direction);
        cv.put("category", category);
        cv.put("body", body);
        cv.put("timestamp", System.currentTimeMillis());
        return db.insert(DbHelper.TABLE_MESSAGE_LOG, null, cv);
    }

    public List<MessageRecord> getRecent(int limit) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = recentQuery(null, null, limit);
        List<MessageRecord> list = new ArrayList<>();
        while (c.moveToNext()) {
            list.add(fromCursor(c));
        }
        c.close();
        return list;
    }

    public List<MessageRecord> getRecentForMember(long memberId, int limit) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = recentQuery("ml.member_id = ?", new String[]{String.valueOf(memberId)}, limit);
        List<MessageRecord> list = new ArrayList<>();
        while (c.moveToNext()) {
            list.add(fromCursor(c));
        }
        c.close();
        return list;
    }

    public List<MessageRecord> getFailedOutgoing(int limit) {
        Cursor c = recentQuery("o.status IN ('FAILED', 'DELIVERY_FAILED', 'RETRY_PENDING')", null, limit);
        List<MessageRecord> list = new ArrayList<>();
        while (c.moveToNext()) {
            list.add(fromCursor(c));
        }
        c.close();
        return list;
    }

    public int countForMember(long memberId, String direction) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery("SELECT COUNT(*) FROM " + DbHelper.TABLE_MESSAGE_LOG +
                " WHERE member_id = ? AND direction = ?", new String[]{String.valueOf(memberId), direction});
        int count = 0;
        if (c.moveToFirst()) {
            count = c.getInt(0);
        }
        c.close();
        return count;
    }

    public int countForMemberSince(long memberId, long sinceTimestamp) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery("SELECT COUNT(*) FROM " + DbHelper.TABLE_MESSAGE_LOG +
                " WHERE member_id = ? AND timestamp >= ?", new String[]{String.valueOf(memberId), String.valueOf(sinceTimestamp)});
        int count = 0;
        if (c.moveToFirst()) {
            count = c.getInt(0);
        }
        c.close();
        return count;
    }

    public long lastActivityForMember(long memberId) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery("SELECT MAX(timestamp) FROM " + DbHelper.TABLE_MESSAGE_LOG +
                " WHERE member_id = ?", new String[]{String.valueOf(memberId)});
        long ts = 0;
        if (c.moveToFirst() && !c.isNull(0)) {
            ts = c.getLong(0);
        }
        c.close();
        return ts;
    }

    public int countSince(long sinceTimestamp) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery("SELECT COUNT(*) FROM " + DbHelper.TABLE_MESSAGE_LOG +
                " WHERE timestamp >= ?", new String[]{String.valueOf(sinceTimestamp)});
        int count = 0;
        if (c.moveToFirst()) {
            count = c.getInt(0);
        }
        c.close();
        return count;
    }

    public int countAll() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery("SELECT COUNT(*) FROM " + DbHelper.TABLE_MESSAGE_LOG, null);
        int count = 0;
        if (c.moveToFirst()) {
            count = c.getInt(0);
        }
        c.close();
        return count;
    }

    /** Permanently erases message history only (members/outbox untouched). Used by "Clear History". */
    public void deleteAll() {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        db.delete(DbHelper.TABLE_MESSAGE_LOG, null, null);
    }

    /** Rough on-disk size estimate for the message log, for the "Clear History" button label. */
    public long estimateStorageBytes() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery("SELECT COUNT(*), COALESCE(SUM(LENGTH(body)), 0) FROM " + DbHelper.TABLE_MESSAGE_LOG, null);
        long bytes = 0;
        if (c.moveToFirst()) {
            long rowCount = c.getLong(0);
            long bodyBytes = c.getLong(1);
            bytes = bodyBytes + rowCount * 40L;
        }
        c.close();
        return bytes;
    }

    private MessageRecord fromCursor(Cursor c) {
        MessageRecord r = new MessageRecord();
        r.id = c.getLong(c.getColumnIndexOrThrow("id"));
        int memberIdx = c.getColumnIndexOrThrow("member_id");
        r.memberId = c.isNull(memberIdx) ? null : c.getLong(memberIdx);
        r.direction = c.getString(c.getColumnIndexOrThrow("direction"));
        r.category = c.getString(c.getColumnIndexOrThrow("category"));
        r.body = c.getString(c.getColumnIndexOrThrow("body"));
        r.timestamp = c.getLong(c.getColumnIndexOrThrow("timestamp"));
        int statusIdx = c.getColumnIndex("delivery_status");
        r.deliveryStatus = statusIdx < 0 || c.isNull(statusIdx) ? null : c.getString(statusIdx);
        int errorIdx = c.getColumnIndex("delivery_error_code");
        r.deliveryErrorCode = errorIdx < 0 || c.isNull(errorIdx) ? null : c.getInt(errorIdx);
        int phoneIdx = c.getColumnIndex("delivery_phone");
        r.deliveryPhone = phoneIdx < 0 || c.isNull(phoneIdx) ? null : c.getString(phoneIdx);
        r.enqueuedAt = nullableLong(c, "delivery_enqueued_at");
        r.submittedAt = nullableLong(c, "delivery_submitted_at");
        r.deliveredAt = nullableLong(c, "delivery_delivered_at");
        r.partsTotal = nullableInt(c, "delivery_parts_total");
        r.partsSent = nullableInt(c, "delivery_parts_sent");
        r.partsDelivered = nullableInt(c, "delivery_parts_delivered");
        r.outboxId = nullableLong(c, "outbox_id");
        r.attemptCount = nullableInt(c, "delivery_attempt_count");
        r.lastAttemptAt = nullableLong(c, "delivery_last_attempt_at");
        r.nextRetryAt = nullableLong(c, "delivery_next_retry_at");
        return r;
    }

    private Cursor recentQuery(String selection, String[] selectionArgs, int limit) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        String sql = "SELECT ml.*, o.status AS delivery_status, " +
                "o.error_code AS delivery_error_code, o.phone_e164 AS delivery_phone, " +
                "o.enqueued_at AS delivery_enqueued_at, o.submitted_at AS delivery_submitted_at, " +
                "o.delivered_at AS delivery_delivered_at, o.parts_total AS delivery_parts_total, " +
                "o.parts_sent AS delivery_parts_sent, o.parts_delivered AS delivery_parts_delivered, " +
                "o.id AS outbox_id, o.attempt_count AS delivery_attempt_count, " +
                "o.last_attempt_at AS delivery_last_attempt_at, o.next_retry_at AS delivery_next_retry_at FROM " +
                DbHelper.TABLE_MESSAGE_LOG + " ml " +
                "LEFT JOIN " + DbHelper.TABLE_OUTBOX + " o ON o.message_log_id = ml.id";
        if (selection != null) {
            sql += " WHERE " + selection;
        }
        sql += " ORDER BY ml.timestamp DESC LIMIT " + Math.max(1, limit);
        return db.rawQuery(sql, selectionArgs);
    }

    private Long nullableLong(Cursor c, String column) {
        int idx = c.getColumnIndex(column);
        return idx < 0 || c.isNull(idx) ? null : c.getLong(idx);
    }

    private Integer nullableInt(Cursor c, String column) {
        int idx = c.getColumnIndex(column);
        return idx < 0 || c.isNull(idx) ? null : c.getInt(idx);
    }
}
