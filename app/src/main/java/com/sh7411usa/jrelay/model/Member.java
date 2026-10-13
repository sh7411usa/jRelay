package com.sh7411usa.jrelay.model;

public class Member {
    public long id;
    public String phoneE164;
    public String nickname;
    public boolean isAdmin;
    public boolean isMuted;
    public boolean active;
    public long createdAt;
    public boolean dailyLimitCustom;
    public Integer dailyLimitValue;
    public int dailyLimitBonus;
    public long dailyLimitBonusWindowStart;
    public int failedCount;
    public Long lastPostReceivedId;
    // Null = unassigned (pre-existing rows are NULL); never conflate with 0.
    public Long subgroupId;
}
