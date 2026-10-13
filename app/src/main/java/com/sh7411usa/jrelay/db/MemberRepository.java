package com.sh7411usa.jrelay.db;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;

import com.sh7411usa.jrelay.model.Member;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MemberRepository {

    private static final String ORDER = "created_at ASC";
    private final DbHelper dbHelper;

    public MemberRepository(Context context) {
        dbHelper = DbHelper.getInstance(context);
    }

    public long insert(String phoneE164, String nickname, boolean isAdmin, String addedBy) {
        ContentValues cv = new ContentValues();
        cv.put("phone_e164", phoneE164);
        cv.put("nickname", nickname);
        cv.put("is_admin", isAdmin ? 1 : 0);
        cv.put("is_muted", 0);
        cv.put("active", 1);
        cv.put("added_by", addedBy);
        cv.put("created_at", System.currentTimeMillis());
        return dbHelper.getWritableDatabase().insertOrThrow(DbHelper.TABLE_MEMBERS, null, cv);
    }

    public Member findByPhone(String phoneE164) {
        return first("phone_e164 = ?", phoneE164);
    }

    public Member findById(long id) {
        return first("id = ?", String.valueOf(id));
    }

    public Member findActiveByNickname(String nickname) {
        return first("active = 1 AND nickname = ? COLLATE NOCASE", nickname);
    }

    public List<Member> getActiveMembers() {
        return query("active = 1", null);
    }

    public List<Member> getActiveAdmins() {
        return query("active = 1 AND is_admin = 1", null);
    }

    public int countActiveAdmins() {
        return count("active = 1 AND is_admin = 1");
    }

    public int countActiveMembers() {
        return count("active = 1");
    }

    /** The one group-capacity rule. maxMembers <= 0 means no limit. */
    public boolean isFull(int maxMembers) {
        return maxMembers > 0 && countActiveMembers() >= maxMembers;
    }

    // Every query feeding a send path must include is_muted = 0 (getUnassignedActiveMembers is admin-only).
    public List<Member> getActiveRecipientsExcept(long excludeId) {
        return query("active = 1 AND is_muted = 0 AND id != ?", new String[]{String.valueOf(excludeId)});
    }

    // Only way to grant admin; callers must check countActiveAdmins() before demoting.
    public void setAdmin(long id, boolean isAdmin) {
        updateColumn(id, "is_admin", isAdmin ? 1 : 0);
    }

    public void setMuted(long id, boolean muted) {
        updateColumn(id, "is_muted", muted ? 1 : 0);
    }

    public void setNickname(long id, String nickname) {
        ContentValues cv = new ContentValues();
        cv.put("nickname", nickname);
        update(id, cv);
    }

    public void setPhone(long id, String phoneE164) {
        ContentValues cv = new ContentValues();
        cv.put("phone_e164", phoneE164);
        update(id, cv);
    }

    // Does NOT guard against zero active admins; callers must check countActiveAdmins() first.
    public void softRemove(long id) {
        ContentValues cv = new ContentValues();
        cv.put("active", 0);
        cv.put("removed_at", System.currentTimeMillis());
        cv.put("is_admin", 0);
        update(id, cv);
    }

    public void reactivate(long id, String nickname, String addedBy) {
        ContentValues cv = new ContentValues();
        cv.put("nickname", nickname);
        cv.put("added_by", addedBy);
        cv.put("is_admin", 0);
        cv.put("is_muted", 0);
        cv.put("active", 1);
        cv.putNull("removed_at");
        cv.putNull("last_post_received_id");
        // Rejoin comes back unassigned: the old sub-group may be full or merged away.
        cv.putNull("subgroup_id");
        update(id, cv);
    }

    public void setDailyLimitOverride(long id, Integer value) {
        ContentValues cv = new ContentValues();
        cv.put("daily_limit_custom", 1);
        cv.put("daily_limit_value", value);
        update(id, cv);
    }

    public void clearDailyLimitOverride(long id) {
        ContentValues cv = new ContentValues();
        cv.put("daily_limit_custom", 0);
        cv.putNull("daily_limit_value");
        update(id, cv);
    }

    public void bulkSetDailyLimit(int value, boolean onlyNonCustomized) {
        ContentValues cv = new ContentValues();
        cv.put("daily_limit_custom", 1);
        cv.put("daily_limit_value", value);
        dbHelper.getWritableDatabase().update(DbHelper.TABLE_MEMBERS, cv,
                onlyNonCustomized ? "active = 1 AND daily_limit_custom = 0" : "active = 1", null);
    }

    public void setDailyLimitBonusState(long id, int bonus, long windowStart) {
        ContentValues cv = new ContentValues();
        cv.put("daily_limit_bonus", bonus);
        cv.put("daily_limit_bonus_window_start", windowStart);
        update(id, cv);
    }

    public int incrementFailedCount(long id) {
        dbHelper.getWritableDatabase().execSQL("UPDATE " + DbHelper.TABLE_MEMBERS
                + " SET failed_count = failed_count + 1 WHERE id = ?", new Object[]{id});
        Member updated = findById(id);
        return updated != null ? updated.failedCount : 0;
    }

    public void resetFailedCount(long id) {
        updateColumn(id, "failed_count", 0);
    }

    public void setLastPostReceivedIdForAll(List<Long> memberIds, Long postLogId) {
        if (memberIds == null || memberIds.isEmpty()) return;
        ContentValues cv = new ContentValues();
        cv.put("last_post_received_id", postLogId);
        int chunkSize = 500;
        for (int start = 0; start < memberIds.size(); start += chunkSize) {
            List<Long> chunk = memberIds.subList(start, Math.min(start + chunkSize, memberIds.size()));
            StringBuilder placeholders = new StringBuilder();
            String[] args = new String[chunk.size()];
            for (int i = 0; i < chunk.size(); i++) {
                placeholders.append(i > 0 ? ",?" : "?");
                args[i] = String.valueOf(chunk.get(i));
            }
            dbHelper.getWritableDatabase().update(DbHelper.TABLE_MEMBERS, cv, "id IN (" + placeholders + ")", args);
        }
    }

    public void assignSubgroup(long id, Long subgroupId) {
        ContentValues cv = new ContentValues();
        cv.put("subgroup_id", subgroupId);
        update(id, cv);
    }

    public List<Member> getSubgroupMembers(long subgroupId) {
        return query("active = 1 AND is_muted = 0 AND subgroup_id = ?", new String[]{String.valueOf(subgroupId)});
    }

    // Includes muted members: answers "who is in this MMS thread", used for rosters and merge sizing.
    public List<Member> getActiveSubgroupMembers(long subgroupId) {
        return query("active = 1 AND subgroup_id = ?", new String[]{String.valueOf(subgroupId)});
    }

    public List<Long> getDistinctSubgroupIds() {
        List<Long> ids = new ArrayList<>();
        try (Cursor c = dbHelper.getReadableDatabase().rawQuery("SELECT DISTINCT subgroup_id FROM " + DbHelper.TABLE_MEMBERS
                + " WHERE active = 1 AND subgroup_id IS NOT NULL ORDER BY subgroup_id ASC", null)) {
            while (c.moveToNext()) ids.add(c.getLong(0));
        }
        return ids;
    }

    public Map<Long, Integer> countMembersPerSubgroup() {
        Map<Long, Integer> counts = new LinkedHashMap<>();
        try (Cursor c = dbHelper.getReadableDatabase().rawQuery("SELECT subgroup_id, COUNT(*) FROM " + DbHelper.TABLE_MEMBERS
                + " WHERE active = 1 AND subgroup_id IS NOT NULL GROUP BY subgroup_id", null)) {
            while (c.moveToNext()) counts.put(c.getLong(0), c.getInt(1));
        }
        return counts;
    }

    // Admin-only (includes muted members so they stay assignable); NOT for sending.
    public List<Member> getUnassignedActiveMembers() {
        return query("active = 1 AND subgroup_id IS NULL", null);
    }

    public List<Member> getUnassignedActiveUnmutedMembers() {
        return query("active = 1 AND is_muted = 0 AND subgroup_id IS NULL", null);
    }

    private List<Member> query(String selection, String[] args) {
        List<Member> list = new ArrayList<>();
        try (Cursor c = dbHelper.getReadableDatabase().query(DbHelper.TABLE_MEMBERS, null, selection, args, null, null, ORDER)) {
            while (c.moveToNext()) list.add(fromCursor(c));
        }
        return list;
    }

    private Member first(String selection, String arg) {
        try (Cursor c = dbHelper.getReadableDatabase().query(DbHelper.TABLE_MEMBERS, null, selection, new String[]{arg}, null, null, null)) {
            return c.moveToFirst() ? fromCursor(c) : null;
        }
    }

    private int count(String where) {
        try (Cursor c = dbHelper.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM " + DbHelper.TABLE_MEMBERS + " WHERE " + where, null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    private void update(long id, ContentValues cv) {
        dbHelper.getWritableDatabase().update(DbHelper.TABLE_MEMBERS, cv, "id = ?", new String[]{String.valueOf(id)});
    }

    private void updateColumn(long id, String column, int value) {
        ContentValues cv = new ContentValues();
        cv.put(column, value);
        update(id, cv);
    }

    private static Member fromCursor(Cursor c) {
        Member m = new Member();
        m.id = c.getLong(c.getColumnIndexOrThrow("id"));
        m.phoneE164 = c.getString(c.getColumnIndexOrThrow("phone_e164"));
        m.nickname = c.getString(c.getColumnIndexOrThrow("nickname"));
        m.isAdmin = c.getInt(c.getColumnIndexOrThrow("is_admin")) != 0;
        m.isMuted = c.getInt(c.getColumnIndexOrThrow("is_muted")) != 0;
        m.active = c.getInt(c.getColumnIndexOrThrow("active")) != 0;
        m.createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"));
        m.dailyLimitCustom = c.getInt(c.getColumnIndexOrThrow("daily_limit_custom")) != 0;
        int idx = c.getColumnIndexOrThrow("daily_limit_value");
        m.dailyLimitValue = c.isNull(idx) ? null : c.getInt(idx);
        m.dailyLimitBonus = c.getInt(c.getColumnIndexOrThrow("daily_limit_bonus"));
        m.dailyLimitBonusWindowStart = c.getLong(c.getColumnIndexOrThrow("daily_limit_bonus_window_start"));
        m.failedCount = c.getInt(c.getColumnIndexOrThrow("failed_count"));
        m.lastPostReceivedId = nullableLong(c, "last_post_received_id");
        m.subgroupId = nullableLong(c, "subgroup_id");
        return m;
    }

    private static Long nullableLong(Cursor c, String column) {
        int idx = c.getColumnIndexOrThrow(column);
        return c.isNull(idx) ? null : c.getLong(idx);
    }
}
