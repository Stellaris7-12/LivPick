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
@TableName("tb_voucher_reconcile_log")
public class VoucherReconcileLog implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private String messageId;

    private Long traceId;

    private Long orderId;

    private Long voucherId;

    private Long userId;

    private String logType;

    private String source;

    private String detail;

    private Integer beforeQty;

    private Integer changeQty;

    private Integer afterQty;

    private String reconciliationStatus;

    private LocalDateTime createdTime;

    private LocalDateTime updateTime;
}
