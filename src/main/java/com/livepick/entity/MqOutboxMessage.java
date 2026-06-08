package com.livepick.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_mq_outbox_message")
public class MqOutboxMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private String messageId;

    private String bizType;

    private String bizKey;

    private String topic;

    private String messageKey;

    private String payload;

    private String status;

    private Integer retryCount;

    private LocalDateTime nextRetryAt;

    private String lastError;

    private LocalDateTime createdTime;

    private LocalDateTime updateTime;
}
