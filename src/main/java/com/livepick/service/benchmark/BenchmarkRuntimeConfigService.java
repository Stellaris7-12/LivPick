package com.livepick.service.benchmark;

import com.livepick.config.LivPickProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Service
@RequiredArgsConstructor
public class BenchmarkRuntimeConfigService {

    public enum CachePenetrationMode {
        BLOOM_NULL,
        OFF
    }

    public enum TimeoutMode {
        DELAY_QUEUE_FALLBACK,
        FALLBACK_ONLY
    }

    private final LivPickProperties livPickProperties;

    private final AtomicReference<CachePenetrationMode> cachePenetrationMode =
            new AtomicReference<>(CachePenetrationMode.BLOOM_NULL);
    private final AtomicReference<TimeoutMode> timeoutMode =
            new AtomicReference<>(TimeoutMode.DELAY_QUEUE_FALLBACK);
    private final AtomicBoolean consumerPaused = new AtomicBoolean(false);
    private final AtomicLong orderTimeoutSecondsOverride = new AtomicLong(-1L);

    @PostConstruct
    public void init() {
        cachePenetrationMode.set(parseCachePenetrationMode(
                livPickProperties.getBenchmark().getCachePenetrationMode()
        ));
        timeoutMode.set(parseTimeoutMode(livPickProperties.getBenchmark().getTimeoutMode()));
        orderTimeoutSecondsOverride.set(livPickProperties.getBenchmark().getOrderTimeoutSecondsOverride());
    }

    public boolean isBenchmarkEnabled() {
        return livPickProperties.getBenchmark().isEnabled();
    }

    public boolean isAuthBypassEnabled() {
        return isBenchmarkEnabled() && (
                livPickProperties.getBenchmark().isSkipLoginCheck()
                        || livPickProperties.getBenchmark().isAuthBypassEnabled()
        );
    }

    public CachePenetrationMode getCachePenetrationMode() {
        return cachePenetrationMode.get();
    }

    public void setCachePenetrationMode(String mode) {
        cachePenetrationMode.set(parseCachePenetrationMode(mode));
    }

    public boolean isCachePenetrationProtectionEnabled() {
        return getCachePenetrationMode() == CachePenetrationMode.BLOOM_NULL;
    }

    public TimeoutMode getTimeoutMode() {
        return timeoutMode.get();
    }

    public void setTimeoutMode(String mode) {
        timeoutMode.set(parseTimeoutMode(mode));
    }

    public boolean shouldUseDelayQueue() {
        return getTimeoutMode() == TimeoutMode.DELAY_QUEUE_FALLBACK;
    }

    public Duration getOrderTimeoutDuration() {
        long overrideSeconds = orderTimeoutSecondsOverride.get();
        if (overrideSeconds > 0) {
            return Duration.ofSeconds(overrideSeconds);
        }
        return Duration.ofMinutes(livPickProperties.getOrder().getTimeoutMinutes());
    }

    public long getOrderTimeoutSecondsOverride() {
        return orderTimeoutSecondsOverride.get();
    }

    public void setOrderTimeoutSecondsOverride(Long seconds) {
        orderTimeoutSecondsOverride.set(seconds == null || seconds <= 0 ? -1L : seconds);
    }

    public boolean isConsumerPaused() {
        return consumerPaused.get();
    }

    public void setConsumerPaused(boolean paused) {
        consumerPaused.set(paused);
    }

    public void awaitIfConsumerPaused() {
        while (consumerPaused.get()) {
            try {
                Thread.sleep(100L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("benchmarkEnabled", isBenchmarkEnabled());
        data.put("authBypassEnabled", isAuthBypassEnabled());
        data.put("cachePenetrationMode", getCachePenetrationMode().name());
        data.put("timeoutMode", getTimeoutMode().name());
        data.put("orderTimeoutSecondsOverride", getOrderTimeoutSecondsOverride());
        data.put("consumerPaused", isConsumerPaused());
        return data;
    }

    private CachePenetrationMode parseCachePenetrationMode(String mode) {
        String normalized = normalize(mode);
        if ("OFF".equals(normalized) || "DISABLED".equals(normalized)) {
            return CachePenetrationMode.OFF;
        }
        return CachePenetrationMode.BLOOM_NULL;
    }

    private TimeoutMode parseTimeoutMode(String mode) {
        String normalized = normalize(mode);
        if ("FALLBACK_ONLY".equals(normalized) || "SCAN_ONLY".equals(normalized)) {
            return TimeoutMode.FALLBACK_ONLY;
        }
        return TimeoutMode.DELAY_QUEUE_FALLBACK;
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim()
                .toUpperCase(Locale.ROOT)
                .replace('-', '_')
                .replace('+', '_')
                .replace(' ', '_');
    }
}
