package com.livepick.mq.message;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class OrderTimeoutMessage {
    private Long orderId;
    private Long userId;
    private Long voucherId;
    private LocalDateTime expireAt;
}
