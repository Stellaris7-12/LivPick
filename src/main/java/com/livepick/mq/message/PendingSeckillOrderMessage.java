package com.livepick.mq.message;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class PendingSeckillOrderMessage {
    private Long orderId;
    private Long userId;
    private Long voucherId;
    private LocalDateTime createTime;
    private int retryCount;
    private Long nextRetryAt;
}
