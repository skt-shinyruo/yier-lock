package com.mycorp.distributedlock.examples.springboot;

import com.mycorp.distributedlock.api.annotation.DistributedLock;
import org.springframework.stereotype.Service;

/**
 * 订单服务示例
 * 演示注解方式使用分布式锁
 */
@Service
public class OrderService {

    /**
     * 处理订单 - 使用注解方式
     */
    @DistributedLock(key = "'order:' + #orderId", leaseTime = 30)
    public String processOrder(String orderId) {
        System.out.println("Processing order: " + orderId + " in thread: " + Thread.currentThread().getName());

        try {
            // 模拟订单处理逻辑
            Thread.sleep(2000); // 2秒处理时间

            // 这里可以是实际的业务逻辑：
            // 1. 检查订单状态
            // 2. 验证库存
            // 3. 处理支付
            // 4. 更新订单状态
            // 5. 发送通知

            System.out.println("Order " + orderId + " processed successfully");
            return "Order " + orderId + " processed successfully";

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Order processing interrupted: " + orderId);
            return "Order processing interrupted: " + orderId;
        }
    }

    /**
     * 批量处理订单
     */
    @DistributedLock(key = "'batch:orders'", leaseTime = 60)
    public String processOrderBatch(String batchId) {
        System.out.println("Processing order batch: " + batchId);

        try {
            // 模拟批量处理
            Thread.sleep(5000);

            System.out.println("Order batch " + batchId + " processed successfully");
            return "Order batch " + batchId + " processed successfully";

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Order batch processing interrupted: " + batchId;
        }
    }

    /**
     * 取消订单
     */
    @DistributedLock(key = "'order:' + #orderId", leaseTime = 15)
    public String cancelOrder(String orderId) {
        System.out.println("Cancelling order: " + orderId);

        try {
            // 模拟取消逻辑
            Thread.sleep(1000);

            System.out.println("Order " + orderId + " cancelled successfully");
            return "Order " + orderId + " cancelled successfully";

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Order cancellation interrupted: " + orderId;
        }
    }

    /**
     * 查询订单状态 - 读操作，不需要锁
     */
    public String getOrderStatus(String orderId) {
        // 读操作通常不需要分布式锁，除非有特殊要求
        System.out.println("Getting status for order: " + orderId);
        return "Order " + orderId + " status: PROCESSING";
    }

    /**
     * 更新订单状态 - 需要锁保护
     */
    @DistributedLock(key = "'order:status:' + #orderId", leaseTime = 10)
    public String updateOrderStatus(String orderId, String newStatus) {
        System.out.println("Updating order " + orderId + " status to: " + newStatus);

        try {
            // 模拟状态更新
            Thread.sleep(500);

            System.out.println("Order " + orderId + " status updated to: " + newStatus);
            return "Order " + orderId + " status updated to: " + newStatus;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Order status update interrupted: " + orderId;
        }
    }
}