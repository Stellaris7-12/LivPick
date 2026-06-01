package com.livepick.service;

public interface IOrderTimeoutService {

    boolean closeTimeoutOrder(Long orderId);

    boolean closeTimeoutOrder(Long orderId, String triggerSource);

    void scanAndCloseTimeoutOrders();
}
