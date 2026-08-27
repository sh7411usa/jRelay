package com.sh7411usa.jrelay.model;

public class MessageRecord {
    public long id;
    public Long memberId;
    public String direction;
    public String category;
    public String body;
    public long timestamp;
    public String deliveryStatus;
    public Integer deliveryErrorCode;
    public String deliveryPhone;
    public Long enqueuedAt;
    public Long submittedAt;
    public Long deliveredAt;
    public Integer partsTotal;
    public Integer partsSent;
    public Integer partsDelivered;
    public Long outboxId;
    public Integer attemptCount;
    public Long lastAttemptAt;
    public Long nextRetryAt;
}
