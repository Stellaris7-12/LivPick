package com.livepick.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.livepick.entity.RollbackFailureLog;
import com.livepick.mapper.RollbackFailureLogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class RollbackFailureLogService {

    private final RollbackFailureLogMapper rollbackFailureLogMapper;

    public void save(RollbackFailureLog log) {
        rollbackFailureLogMapper.insert(log);
    }

    public List<RollbackFailureLog> findOpenFailures(int limit) {
        return rollbackFailureLogMapper.selectList(new QueryWrapper<RollbackFailureLog>()
                .eq("status", "OPEN")
                .orderByAsc("created_time")
                .last("LIMIT " + limit));
    }

    public void markResolved(Long id) {
        rollbackFailureLogMapper.updateById(new RollbackFailureLog()
                .setId(id)
                .setStatus("RESOLVED")
                .setUpdateTime(LocalDateTime.now()));
    }

    public void markRetried(Long id, int retryAttempts, String detail) {
        rollbackFailureLogMapper.updateById(new RollbackFailureLog()
                .setId(id)
                .setRetryAttempts(retryAttempts)
                .setDetail(detail)
                .setUpdateTime(LocalDateTime.now()));
    }
}
