package com.sh7411usa.jrelay.util;

import android.content.Context;

import com.sh7411usa.jrelay.db.MemberRepository;
import com.sh7411usa.jrelay.db.MessageRepository;
import com.sh7411usa.jrelay.model.Member;

import java.util.Calendar;

public class DailyLimitManager {

    public static class Status {
        public final boolean enabled;
        public final boolean unlimited;
        public final int used;
        public final int limit;
        public final long resetAtMillis;

        Status(boolean enabled, boolean unlimited, int used, int limit, long resetAtMillis) {
            this.enabled = enabled;
            this.unlimited = unlimited;
            this.used = used;
            this.limit = limit;
            this.resetAtMillis = resetAtMillis;
        }

        public int remaining() {
            return (enabled && !unlimited) ? Math.max(0, limit - used) : Integer.MAX_VALUE;
        }

        public boolean isExhausted() {
            return enabled && !unlimited && remaining() <= 0;
        }
    }

    private final Prefs prefs;
    private final MemberRepository memberRepository;
    private final MessageRepository messageRepository;

    public DailyLimitManager(Context context) {
        Context appContext = context.getApplicationContext();
        prefs = new Prefs(appContext);
        memberRepository = new MemberRepository(appContext);
        messageRepository = new MessageRepository(appContext);
    }

    public long currentWindowStart() {
        Calendar cal = Calendar.getInstance();
        int resetMinute = prefs.getDailyLimitResetMinuteOfDay();
        cal.set(Calendar.HOUR_OF_DAY, resetMinute / 60);
        cal.set(Calendar.MINUTE, resetMinute % 60);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        if (cal.getTimeInMillis() > System.currentTimeMillis()) cal.add(Calendar.DAY_OF_YEAR, -1);
        return cal.getTimeInMillis();
    }

    public long nextResetAtMillis() {
        return currentWindowStart() + 24L * 60 * 60 * 1000;
    }

    public Status groupStatus() {
        long windowStart = currentWindowStart();
        int bonus = lazyGroupBonus(windowStart);
        int used = messageRepository.countRelayedSince(windowStart);
        return new Status(prefs.isGroupDailyLimitEnabled(), false, used,
                prefs.getGroupDailyLimitValue() + bonus, nextResetAtMillis());
    }

    /** A member with a custom cap but no value set (null) is unlimited. */
    public Status memberStatus(Member member) {
        long windowStart = currentWindowStart();
        boolean unlimited = member.dailyLimitCustom && member.dailyLimitValue == null;
        int bonus = lazyMemberBonus(member, windowStart);
        int used = messageRepository.countRelayedForMemberSince(member.id, windowStart);
        int limit = unlimited ? 0 : (member.dailyLimitValue != null ? member.dailyLimitValue : 0) + bonus;
        return new Status(member.dailyLimitCustom, unlimited, used, limit, nextResetAtMillis());
    }

    public void overrideGroup() {
        long windowStart = currentWindowStart();
        prefs.setGroupDailyLimitBonus(lazyGroupBonus(windowStart) + 1);
        prefs.setGroupDailyLimitBonusWindowStart(windowStart);
    }

    public boolean overrideMember(Member member) {
        if (!member.dailyLimitCustom) return false;
        long windowStart = currentWindowStart();
        memberRepository.setDailyLimitBonusState(member.id, lazyMemberBonus(member, windowStart) + 1, windowStart);
        return true;
    }

    public void bulkSetIndividualLimit(int value, boolean onlyNonCustomized) {
        memberRepository.bulkSetDailyLimit(value, onlyNonCustomized);
    }

    private int lazyGroupBonus(long windowStart) {
        if (prefs.getGroupDailyLimitBonusWindowStart() == windowStart) return prefs.getGroupDailyLimitBonus();
        prefs.setGroupDailyLimitBonus(0);
        prefs.setGroupDailyLimitBonusWindowStart(windowStart);
        return 0;
    }

    private int lazyMemberBonus(Member member, long windowStart) {
        if (member.dailyLimitBonusWindowStart == windowStart) return member.dailyLimitBonus;
        memberRepository.setDailyLimitBonusState(member.id, 0, windowStart);
        return 0;
    }
}
