package com.livepick.service;

public interface IOrderTimeoutService {

    boolean closeTimeoutOrder(Long orderId);

    void scanAndCloseTimeoutOrders();
}
