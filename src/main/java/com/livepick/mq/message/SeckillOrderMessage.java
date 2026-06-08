package com.livepick.mq.message;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class SeckillOrderMessage {
    private String messageId;
    private Long traceId;
    private Long orderId;
    private Long userId;
    private Long voucherId;
    private Integer beforeQty;
    private Integer changeQty;
    private Integer afterQty;
    private LocalDateTime createTime;
}
