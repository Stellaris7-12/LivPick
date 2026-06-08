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
@TableName("tb_rollback_failure_log")
public class RollbackFailureLog implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private Long traceId;

    private Long orderId;

    private Long voucherId;

    private Long userId;

    private Integer resultCode;

    private Integer retryAttempts;

    private String source;

    private String detail;

    private String status;

    private LocalDateTime createdTime;

    private LocalDateTime updateTime;
}
