package com.livepick.utils;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class RedisData {
    /**
     * 是否存在业务数据：
     * true 代表正常数据或空列表，
     * false 代表空值缓存。
     */
    private Boolean present;
    private LocalDateTime logicalExpireTime;
    // 兼容旧的缓存结构
    private LocalDateTime expireTime;
    private Object data;
}
