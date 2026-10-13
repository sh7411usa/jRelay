package com.sh7411usa.jrelay.db;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;

import com.sh7411usa.jrelay.model.MessageRecord;

import java.util.ArrayList;
import java.util.List;

public class MessageRepository {
    private static final String T = DbHelper.TABLE_MESSAGE_LOG;
    // Hides the RELAYED quota marker and per-recipient OUT/RELAY fan-out copies.
    private static final String NO_DUPES = "category != 'RELAYED' AND NOT (direction = 'OUT' AND category = 'RELAY')";
    private final DbHelper dbHelper;

    public MessageRepository(Context context) {
        dbHelper = DbHelper.getInstance(context);
    }

    public long log(Long memberId, String direction, String category, String body) {
        ContentValues cv = new ContentValues();
        if (memberId != null) cv.put("member_id", memberId);
        cv.put("direction", direction);
        cv.put("category", category);
        cv.put("body", body);
        cv.put("timestamp", System.currentTimeMillis());
        return dbHelper.getWritableDatabase().insert(T, null, cv);
    }

    public MessageRecord getById(long id) {
        List<MessageRecord> l = query("id = ?", new String[]{String.valueOf(id)}, null, null);
        return l.isEmpty() ? null : l.get(0);
    }

    public List<MessageRecord> getRecent(int limit) {
        return query(NO_DUPES, null, "timestamp DESC", String.valueOf(limit));
    }

    public List<MessageRecord> getRecentForMember(long memberId, int limit) {
        return query("member_id = ? AND " + NO_DUPES, new String[]{String.valueOf(memberId)},
                "timestamp DESC", String.valueOf(limit));
    }

    public int countForMember(long memberId, String direction) {
        return countWhere("member_id = ? AND direction = ? AND category != 'RELAYED'",
                new String[]{String.valueOf(memberId), direction});
    }

    public int countForMemberSince(long memberId, long sinceTimestamp) {
        return countWhere("member_id = ? AND timestamp >= ? AND category != 'RELAYED'",
                new String[]{String.valueOf(memberId), String.valueOf(sinceTimestamp)});
    }

    public int countInboundForMemberSince(long memberId, long sinceTimestamp) {
        return countWhere("member_id = ? AND direction = 'IN' AND timestamp >= ? AND category != 'RELAYED'",
                new String[]{String.valueOf(memberId), String.valueOf(sinceTimestamp)});
    }

    public long lastActivityForMember(long memberId) {
        try (Cursor c = dbHelper.getReadableDatabase().rawQuery("SELECT MAX(timestamp) FROM " + T +
                " WHERE member_id = ?", new String[]{String.valueOf(memberId)})) {
            return c.moveToFirst() && !c.isNull(0) ? c.getLong(0) : 0;
        }
    }

    // Counts the RELAYED marker (success path only), not the general RELAY category.
    public int countRelayedSince(long sinceTimestamp) {
        return countWhere("category = 'RELAYED' AND direction = 'IN' AND timestamp >= ?",
                new String[]{String.valueOf(sinceTimestamp)});
    }

    public int countRelayedForMemberSince(long memberId, long sinceTimestamp) {
        return countWhere("member_id = ? AND category = 'RELAYED' AND direction = 'IN' AND timestamp >= ?",
                new String[]{String.valueOf(memberId), String.valueOf(sinceTimestamp)});
    }

    public int countFailedSince(long sinceTimestamp) {
        return countWhere("category = 'FAILED' AND timestamp >= ?", new String[]{String.valueOf(sinceTimestamp)});
    }

    public int countSince(long sinceTimestamp) {
        return countWhere("timestamp >= ? AND category != 'RELAYED'", new String[]{String.valueOf(sinceTimestamp)});
    }

    public int countAll() {
        return countWhere("category != 'RELAYED'", null);
    }

    public void deleteAll() {
        dbHelper.getWritableDatabase().delete(T, null, null);
    }

    // Bulk maintenance only; never call from log() or any per-message path.
    public int pruneOlderThan(long cutoffTimestamp) {
        return dbHelper.getWritableDatabase().delete(T, "timestamp < ?", new String[]{String.valueOf(cutoffTimestamp)});
    }

    public long estimateStorageBytes() {
        try (Cursor c = dbHelper.getReadableDatabase().rawQuery(
                "SELECT COUNT(*), COALESCE(SUM(LENGTH(body)), 0) FROM " + T, null)) {
            return c.moveToFirst() ? c.getLong(1) + c.getLong(0) * 40L : 0;
        }
    }

    private int countWhere(String selection, String[] args) {
        try (Cursor c = dbHelper.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM " + T + " WHERE " + selection, args)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    private List<MessageRecord> query(String sel, String[] args, String order, String limit) {
        List<MessageRecord> list = new ArrayList<>();
        try (Cursor c = dbHelper.getReadableDatabase().query(T, null, sel, args, null, null, order, limit)) {
            while (c.moveToNext()) list.add(fromCursor(c));
        }
        return list;
    }

    private static MessageRecord fromCursor(Cursor c) {
        MessageRecord r = new MessageRecord();
        int m = c.getColumnIndexOrThrow("member_id");
        r.memberId = c.isNull(m) ? null : c.getLong(m);
        r.body = c.getString(c.getColumnIndexOrThrow("body"));
        r.timestamp = c.getLong(c.getColumnIndexOrThrow("timestamp"));
        return r;
    }
}
