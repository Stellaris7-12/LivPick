package com.livepick.enums;

public enum OutboxStatus {
    PENDING,
    SENDING,
    SENT,
    ACKED,
    FAILED,
    DLQ
}
