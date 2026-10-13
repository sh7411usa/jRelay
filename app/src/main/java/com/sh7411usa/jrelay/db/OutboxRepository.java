package com.sh7411usa.jrelay.db;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;
import android.os.SystemClock;
import android.util.Log;

import com.sh7411usa.jrelay.util.MessageSalt;
import com.sh7411usa.jrelay.util.Prefs;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

// Row shape is decided by subgroup_id (null = individual, set = group MMS); a group row's
// phone_e164 is an "" sentinel for the NOT NULL column - never infer shape from the phone.
public class OutboxRepository {

    private static final String TAG = "OutboxRepository";
    public static final String CATEGORY_RELAY = "RELAY";
    private static final long AWAITING_RESULT_STALE_MILLIS = 10 * 60 * 1000L;
    // Matches android.app.Activity.RESULT_OK.
    private static final int SEND_RESULT_OK = -1;
    // Parked here between a failed result and retry-vs-fail: invisible to takeBurst and resetOrphanedSending.
    private static final String STATUS_SEND_FAILED = "SEND_FAILED";
    private static final String UNSENT_WHERE = "status IN ('PENDING', 'SENDING', '" + STATUS_SEND_FAILED + "')";
    // SENDING excluded: those rows are with the radio; count and delete must share this clause.
    private static final String DISCARD_ON_STOP_WHERE = "status IN ('PENDING', '" + STATUS_SEND_FAILED + "')";
    private static final String TOKEN_GUARD_WHERE =
            "id = ? AND handed_off_at = ? AND status IN ('" + STATUS_SEND_FAILED + "', 'SENDING')";
    private static final String AWAITING_WHERE = "id = ? AND status = ? AND parts_pending > 0 AND handed_off_at = ?";

    public static class OutboxItem {
        public long id;
        public Long memberId;
        public String phoneE164;
        public String body;
        public int attempts;
        public boolean applySalt;
        public Long subgroupId;
    }

    private final DbHelper dbHelper;
    private final Prefs prefs;

    public OutboxRepository(Context context) {
        dbHelper = DbHelper.getInstance(context);
        prefs = new Prefs(context);
    }

    public boolean enqueue(Long memberId, String phoneE164, String body) {
        return enqueue(memberId, phoneE164, body, null, 0L, false);
    }

    // The stop check lives only in the two insert chokepoints so every sender is covered; stopped = dropped, not held.
    public boolean enqueue(Long memberId, String phoneE164, String body, String category,
                         long holdUntilMillis, boolean applySalt) {
        if (prefs.isStopped()) {
            Log.i(TAG, "Stopped; dropped 1 outbound row, category=" + category);
            return false;
        }
        ContentValues cv = newRow(phoneE164, body, category, holdUntilMillis, applySalt);
        if (memberId != null) cv.put("member_id", memberId);
        dbHelper.getWritableDatabase().insert(DbHelper.TABLE_OUTBOX, null, cv);
        return true;
    }

    public boolean enqueueGroup(long subgroupId, String body, String category,
                              long holdUntilMillis, boolean applySalt) {
        if (prefs.isStopped()) {
            Log.i(TAG, "Stopped; dropped 1 outbound group row, category=" + category);
            return false;
        }
        ContentValues cv = newRow("", body, category, holdUntilMillis, applySalt);
        cv.put("subgroup_id", subgroupId);
        dbHelper.getWritableDatabase().insert(DbHelper.TABLE_OUTBOX, null, cv);
        return true;
    }

    private static ContentValues newRow(String phone, String body, String category, long holdUntil, boolean applySalt) {
        ContentValues cv = new ContentValues();
        cv.put("phone_e164", phone);
        cv.put("body", body);
        cv.put("enqueued_at", System.currentTimeMillis());
        cv.put("status", "PENDING");
        cv.put("category", category);
        cv.put("hold_until", holdUntil);
        cv.put("apply_salt", applySalt ? 1 : 0);
        return cv;
    }

    public List<OutboxItem> takeBurst(int limit, boolean shuffle) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        List<OutboxItem> selected;
        db.beginTransaction();
        try {
            List<OutboxItem> pending = readItems(db.query(DbHelper.TABLE_OUTBOX, null, "status = ? AND hold_until <= ?",
                    new String[]{"PENDING", String.valueOf(System.currentTimeMillis())},
                    null, null, "enqueued_at ASC"));
            if (shuffle) Collections.shuffle(pending);
            selected = new ArrayList<>(pending.subList(0, Math.min(limit, pending.size())));
            for (OutboxItem item : selected) {
                db.update(DbHelper.TABLE_OUTBOX, status("SENDING"), "id = ?", new String[]{String.valueOf(item.id)});
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return selected;
    }

    // Only safe while no drain can hold claimed rows (just won DRAINING CAS, or paused/stopped).
    // parts_pending=0 rows never reached the radio (no attempt charged); parts_pending>0 rows are
    // reset only when stale or handed off before this boot. A recent hand-off is left alone.
    public int resetOrphanedSending() {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        long staleBefore = System.currentTimeMillis() - AWAITING_RESULT_STALE_MILLIS;
        long bootTime = System.currentTimeMillis() - SystemClock.elapsedRealtime();
        int neverHandedOff = db.update(DbHelper.TABLE_OUTBOX, status("PENDING"),
                "status = ? AND parts_pending = 0", new String[]{"SENDING"});
        // Zero parts_pending too, so a late result from the abandoned attempt can't match after reclaim.
        try (SQLiteStatement stmt = db.compileStatement("UPDATE " + DbHelper.TABLE_OUTBOX
                + " SET status = 'PENDING', attempts = attempts + 1, parts_pending = 0"
                + " WHERE status = 'SENDING' AND parts_pending > 0"
                + " AND (handed_off_at <= ? OR handed_off_at < ?)")) {
            stmt.bindLong(1, staleBefore);
            stmt.bindLong(2, bootTime);
            return neverHandedOff + stmt.executeUpdateDelete();
        }
    }

    public int countUnsent() {
        return (int) queryScalar("COUNT(*)", UNSENT_WHERE, null);
    }

    public int countDiscardable() {
        return (int) queryScalar("COUNT(*)", DISCARD_ON_STOP_WHERE, null);
    }

    public int discardUnsent() {
        return dbHelper.getWritableDatabase().delete(DbHelper.TABLE_OUTBOX, DISCARD_ON_STOP_WHERE, null);
    }

    // Gates sub-group merges: group rows resolve recipients at send time.
    public int countUnsentForSubgroups(Collection<Long> subgroupIds) {
        if (subgroupIds == null || subgroupIds.isEmpty()) return 0;
        StringBuilder placeholders = new StringBuilder();
        List<String> args = new ArrayList<>();
        for (Long id : subgroupIds) {
            placeholders.append(placeholders.length() == 0 ? "?" : ",?");
            args.add(String.valueOf(id));
        }
        return (int) queryScalar("COUNT(*)", UNSENT_WHERE + " AND subgroup_id IN (" + placeholders + ")",
                args.toArray(new String[0]));
    }

    // PENDING only: a SENDING row is with the radio.
    public int deletePendingRosters(long subgroupId) {
        return dbHelper.getWritableDatabase().delete(DbHelper.TABLE_OUTBOX,
                "status = 'PENDING' AND category = 'SYSTEM' AND subgroup_id = ?",
                new String[]{String.valueOf(subgroupId)});
    }

    public int countPending() {
        return (int) queryScalar("COUNT(*)", "status = 'PENDING'", null);
    }

    // Bounded by the same staleness window resetOrphanedSending uses.
    public int countAwaitingResults() {
        return (int) queryScalar("COUNT(*)", "status = ? AND parts_pending > 0 AND handed_off_at > ?",
                new String[]{"SENDING", String.valueOf(System.currentTimeMillis() - AWAITING_RESULT_STALE_MILLIS)});
    }

    public int countHolding() {
        return (int) queryScalar("COUNT(*)", "status = ? AND hold_until > ?", heldArgs());
    }

    public long earliestHoldUntil() {
        return queryScalar("MIN(hold_until)", "status = ? AND hold_until > ?", heldArgs());
    }

    private static String[] heldArgs() {
        return new String[]{"PENDING", String.valueOf(System.currentTimeMillis())};
    }

    public void markFailed(long id, long token) {
        dbHelper.getWritableDatabase().update(DbHelper.TABLE_OUTBOX, status("FAILED"), TOKEN_GUARD_WHERE, idToken(id, token));
    }

    public void markHandedOff(long id, int parts, long token) {
        ContentValues cv = new ContentValues();
        cv.put("parts_pending", parts);
        cv.put("handed_off_at", token);
        dbHelper.getWritableDatabase().update(DbHelper.TABLE_OUTBOX, cv, "id = ?", new String[]{String.valueOf(id)});
    }

    // SENT is written in the same transaction as the final decrement so no one observes SENDING with parts_pending=0.
    public int recordPartSent(long id, long token) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues cv = new ContentValues();
            cv.put("last_result", SEND_RESULT_OK);
            if (db.update(DbHelper.TABLE_OUTBOX, cv, AWAITING_WHERE, awaitingArgs(id, token)) == 0) {
                db.setTransactionSuccessful();
                return -1;
            }
            db.execSQL("UPDATE " + DbHelper.TABLE_OUTBOX + " SET parts_pending = parts_pending - 1 WHERE id = ?",
                    new Object[]{id});
            int remaining = -1;
            try (Cursor c = db.rawQuery("SELECT parts_pending FROM " + DbHelper.TABLE_OUTBOX + " WHERE id = ?",
                    new String[]{String.valueOf(id)})) {
                if (c.moveToFirst()) remaining = c.getInt(0);
            }
            if (remaining == 0) {
                db.update(DbHelper.TABLE_OUTBOX, status("SENT"), "id = ?", new String[]{String.valueOf(id)});
            }
            db.setTransactionSuccessful();
            return remaining;
        } finally {
            db.endTransaction();
        }
    }

    // Status moves to SEND_FAILED in the same write that clears parts_pending, so resetOrphanedSending can't reclaim it.
    public boolean recordSendFailure(long id, long token, int resultCode) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues cv = status(STATUS_SEND_FAILED);
            cv.put("parts_pending", 0);
            // last_result is write-only on purpose (for DB-level diagnosis); do not remove.
            cv.put("last_result", resultCode);
            int rows = db.update(DbHelper.TABLE_OUTBOX, cv, AWAITING_WHERE, awaitingArgs(id, token));
            db.setTransactionSuccessful();
            return rows > 0;
        } finally {
            db.endTransaction();
        }
    }

    // All-or-nothing: any row no longer PENDING abandons the whole merge (rolled back).
    public boolean applyMerge(long keepRowId, String mergedBody, List<Long> mergedRowIds) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues keepCv = new ContentValues();
            keepCv.put("body", mergedBody);
            keepCv.put("hold_until", 0);
            if (db.update(DbHelper.TABLE_OUTBOX, keepCv, "id = ? AND status = ?",
                    new String[]{String.valueOf(keepRowId), "PENDING"}) == 0) {
                return false;
            }
            for (Long mergedId : mergedRowIds) {
                if (db.update(DbHelper.TABLE_OUTBOX, status("MERGED"), "id = ? AND status = ?",
                        new String[]{String.valueOf(mergedId), "PENDING"}) == 0) {
                    return false;
                }
            }
            db.setTransactionSuccessful();
            return true;
        } finally {
            db.endTransaction();
        }
    }

    public OutboxItem findById(long id) {
        List<OutboxItem> items = readItems(dbHelper.getReadableDatabase().query(DbHelper.TABLE_OUTBOX, null, "id = ?",
                new String[]{String.valueOf(id)}, null, null, null));
        return items.isEmpty() ? null : items.get(0);
    }

    // Excludes group rows: merging is keyed on phone_e164, which is a sentinel for them.
    public List<OutboxItem> takeReleasedRelayRows() {
        return readItems(dbHelper.getReadableDatabase().query(DbHelper.TABLE_OUTBOX, null,
                "status = ? AND category = ? AND hold_until <= ? AND subgroup_id IS NULL",
                new String[]{"PENDING", CATEGORY_RELAY, String.valueOf(System.currentTimeMillis())},
                null, null, "enqueued_at ASC"));
    }

    // Token-guarded so a late failure for a superseded attempt can't requeue a live one (duplicate send).
    public void requeueForRetry(long id, long token, int attempts, long holdUntilMillis) {
        ContentValues cv = status("PENDING");
        cv.put("attempts", attempts);
        cv.put("hold_until", holdUntilMillis);
        dbHelper.getWritableDatabase().update(DbHelper.TABLE_OUTBOX, cv, TOKEN_GUARD_WHERE, idToken(id, token));
    }

    public boolean sentSameBodyRecently(String phoneE164, String normalizedBody,
                                        long windowStartMillis, long windowEndMillis) {
        if (phoneE164 == null || phoneE164.isEmpty() || normalizedBody == null || normalizedBody.isEmpty()) {
            return false;
        }
        // The SQL only narrows by the lower bound; withinEchoWindow is the actual decision.
        String since = String.valueOf(windowStartMillis);
        try (Cursor c = dbHelper.getReadableDatabase().rawQuery("SELECT body, enqueued_at, handed_off_at FROM "
                        + DbHelper.TABLE_OUTBOX + " WHERE phone_e164 = ? AND subgroup_id IS NULL"
                        + " AND status IN ('SENDING', 'SENT') AND (enqueued_at >= ? OR handed_off_at >= ?)",
                new String[]{phoneE164, since, since})) {
            while (c.moveToNext()) {
                if (withinEchoWindow(c.getLong(1), c.getLong(2), windowStartMillis, windowEndMillis)
                        && MessageSalt.isEchoOf(c.getString(0), normalizedBody)) {
                    return true;
                }
            }
            return false;
        }
    }

    // Echo returns at send time, not enqueue time; handed_off_at 0 means "never handed off" and is ignored.
    public static boolean withinEchoWindow(long enqueuedAt, long handedOffAt,
                                           long windowStart, long windowEnd) {
        if (enqueuedAt >= windowStart && enqueuedAt <= windowEnd) return true;
        return handedOffAt > 0 && handedOffAt >= windowStart && handedOffAt <= windowEnd;
    }

    private static ContentValues status(String status) {
        ContentValues cv = new ContentValues();
        cv.put("status", status);
        return cv;
    }

    private static String[] idToken(long id, long token) {
        return new String[]{String.valueOf(id), String.valueOf(token)};
    }

    private static String[] awaitingArgs(long id, long token) {
        return new String[]{String.valueOf(id), "SENDING", String.valueOf(token)};
    }

    private long queryScalar(String selectExpr, String selection, String[] args) {
        try (Cursor c = dbHelper.getReadableDatabase().rawQuery("SELECT " + selectExpr + " FROM "
                + DbHelper.TABLE_OUTBOX + " WHERE " + selection, args)) {
            return c.moveToFirst() && !c.isNull(0) ? c.getLong(0) : 0;
        }
    }

    private List<OutboxItem> readItems(Cursor cursor) {
        List<OutboxItem> items = new ArrayList<>();
        try (Cursor c = cursor) {
            while (c.moveToNext()) items.add(mapCursor(c));
        }
        return items;
    }

    private OutboxItem mapCursor(Cursor c) {
        OutboxItem item = new OutboxItem();
        item.id = c.getLong(c.getColumnIndexOrThrow("id"));
        int memberIdx = c.getColumnIndexOrThrow("member_id");
        item.memberId = c.isNull(memberIdx) ? null : c.getLong(memberIdx);
        item.phoneE164 = c.getString(c.getColumnIndexOrThrow("phone_e164"));
        item.body = c.getString(c.getColumnIndexOrThrow("body"));
        item.attempts = c.getInt(c.getColumnIndexOrThrow("attempts"));
        item.applySalt = c.getInt(c.getColumnIndexOrThrow("apply_salt")) != 0;
        int subgroupIdx = c.getColumnIndexOrThrow("subgroup_id");
        item.subgroupId = c.isNull(subgroupIdx) ? null : c.getLong(subgroupIdx);
        return item;
    }
}
