package com.livepick.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.livepick.entity.VoucherReconcileLog;
import com.livepick.enums.ReconciliationStatus;
import com.livepick.mapper.VoucherReconcileLogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class VoucherReconcileLogService {

    private final VoucherReconcileLogMapper voucherReconcileLogMapper;

    public void save(VoucherReconcileLog log) {
        voucherReconcileLogMapper.insert(log);
    }

    public List<VoucherReconcileLog> findByOrderId(Long orderId) {
        return voucherReconcileLogMapper.selectList(new QueryWrapper<VoucherReconcileLog>()
                .eq("order_id", orderId)
                .orderByAsc("created_time"));
    }

    public VoucherReconcileLog findByTraceId(Long traceId) {
        return voucherReconcileLogMapper.selectOne(new QueryWrapper<VoucherReconcileLog>()
                .eq("trace_id", traceId)
                .last("LIMIT 1"));
    }

    public VoucherReconcileLog findByMessageId(String messageId) {
        return voucherReconcileLogMapper.selectOne(new QueryWrapper<VoucherReconcileLog>()
                .eq("message_id", messageId)
                .last("LIMIT 1"));
    }

    public boolean existsByTraceId(Long traceId) {
        Integer count = voucherReconcileLogMapper.selectCount(new QueryWrapper<VoucherReconcileLog>()
                .eq("trace_id", traceId));
        return count != null && count > 0;
    }

    public List<VoucherReconcileLog> findByStatus(String status, int limit) {
        return voucherReconcileLogMapper.selectList(new QueryWrapper<VoucherReconcileLog>()
                .eq("reconciliation_status", status)
                .orderByAsc("created_time")
                .last("LIMIT " + limit));
    }

    public void markOrderStatus(Long orderId, ReconciliationStatus status) {
        VoucherReconcileLog update = new VoucherReconcileLog()
                .setReconciliationStatus(status.name())
                .setUpdateTime(LocalDateTime.now());
        voucherReconcileLogMapper.update(update, new QueryWrapper<VoucherReconcileLog>()
                .eq("order_id", orderId));
    }
}
