package com.livepick.task;

import com.livepick.config.LivPickProperties;
import com.livepick.mq.message.CacheDeleteRetryMessage;
import com.livepick.service.CacheDeleteRetryScheduleService;
import com.livepick.utils.CacheClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class CacheDeleteRetryTask {

    private final CacheDeleteRetryScheduleService cacheDeleteRetryScheduleService;
    private final CacheClient cacheClient;
    private final LivPickProperties livPickProperties;

    @Scheduled(fixedDelayString = "${livpick.cache.delete-retry-scan-interval-ms}")
    public void retryDeleteCache() {
        while (true) {
            List<CacheDeleteRetryMessage> dueMessages = cacheDeleteRetryScheduleService.pollDueMessages();
            if (dueMessages.isEmpty()) {
                return;
            }
            dueMessages.forEach(this::retrySingleMessage);
        }
    }

    private void retrySingleMessage(CacheDeleteRetryMessage message) {
        try {
            cacheClient.delete(message.getCacheKey());
            cacheDeleteRetryScheduleService.clear(message.getCacheKey());
        } catch (Exception e) {
            int nextRetryCount = message.getRetryCount() + 1;
            if (nextRetryCount > livPickProperties.getCache().getDeleteRetryMaxAttempts()) {
                cacheDeleteRetryScheduleService.clear(message.getCacheKey());
                log.error("cache delete retry exhausted, key={}", message.getCacheKey(), e);
                return;
            }
            message.setRetryCount(nextRetryCount);
            message.setLastError(e.getMessage());
            cacheDeleteRetryScheduleService.schedule(message);
            log.warn("cache delete retry rescheduled, key={}, retryCount={}",
                    message.getCacheKey(), message.getRetryCount(), e);
        }
    }
}
