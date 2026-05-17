package com.livepick.mq.message;

import lombok.Data;

@Data
public class CacheDeleteRetryMessage {
    private String cacheKey;
    private String bizType;
    private Long bizId;
    private int retryCount;
    private Long nextRetryAt;
    private String lastError;
}
