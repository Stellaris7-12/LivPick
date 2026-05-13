package com.livepick.mq.delay;

import cn.hutool.json.JSONUtil;
import com.livepick.config.LivPickProperties;
import com.livepick.mq.message.OrderTimeoutMessage;
import com.livepick.service.IOrderTimeoutService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBlockingDeque;
import org.redisson.api.RDelayedQueue;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderTimeoutDelayQueueManager {

    private final RedissonClient redissonClient;
    private final IOrderTimeoutService orderTimeoutService;
    private final LivPickProperties livPickProperties;

    private final ExecutorService consumerExecutor = Executors.newSingleThreadExecutor();

    private RBlockingDeque<String> blockingDeque;
    private RDelayedQueue<String> delayedQueue;

    @PostConstruct
    public void init() {
        blockingDeque = redissonClient.getBlockingDeque(livPickProperties.getOrder().getDelayQueueName());
        delayedQueue = redissonClient.getDelayedQueue(blockingDeque);
        consumerExecutor.submit(this::consume);
    }

    public void offer(OrderTimeoutMessage message) {
        delayedQueue.offer(
                JSONUtil.toJsonStr(message),
                livPickProperties.getOrder().getTimeoutMinutes(),
                TimeUnit.MINUTES
        );
    }

    private void consume() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                String messageJson = blockingDeque.take();
                OrderTimeoutMessage message = JSONUtil.toBean(messageJson, OrderTimeoutMessage.class);
                orderTimeoutService.closeTimeoutOrder(message.getOrderId());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.error("consume timeout order message failed", e);
            }
        }
    }

    @PreDestroy
    public void destroy() {
        if (delayedQueue != null) {
            delayedQueue.destroy();
        }
        consumerExecutor.shutdownNow();
    }
}
