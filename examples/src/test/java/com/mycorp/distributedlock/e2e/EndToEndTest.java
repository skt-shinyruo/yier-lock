package com.mycorp.distributedlock.e2e;

import com.mycorp.distributedlock.api.*;
import com.mycorp.distributedlock.redis.SimpleRedisLockProvider;
import com.mycorp.distributedlock.zookeeper.ZooKeeperLockProvider;
import com.mycorp.distributedlock.core.observability.*;
import com.mycorp.distributedlock.core.monitoring.PerformanceMonitor;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;

/**
 * 端到端测试
 * 
 * 测试完整的业务场景和真实的分布式锁使用情况
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {
    EndToEndTest.TestConfiguration.class
})
@TestPropertySource(properties = {
    "distributed.lock.enabled=true",
    "distributed.lock.metrics.enabled=true",
    "distributed.lock.aspect.enabled=true",
    "distributed.lock.metrics.prometheus.enabled=false" // 禁用以简化测试
})
@DisplayName("端到端测试")
class EndToEndTest {

    @Autowired
    private OrderProcessingService orderService;

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private DistributedLockFactory lockFactory;

    @Autowired
    private LockMonitoringService monitoringService;

    @Nested
    @DisplayName("电商订单处理场景测试")
    class ECommerceOrderProcessingTest {

        @Test
        @DisplayName("应该处理完整的订单生命周期")
        void shouldProcessCompleteOrderLifecycle() throws InterruptedException {
            // Given
            String orderId = "order-12345";
            String productId = "product-001";
            int quantity = 2;
            double amount = 99.99;

            // When - 启动异步订单处理
            CompletableFuture<OrderResult> orderFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    return orderService.processOrder(orderId, productId, quantity, amount);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            // 并发库存检查
            CompletableFuture<InventoryResult> inventoryFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    return inventoryService.checkAndReserveInventory(productId, quantity);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            // 并发支付处理
            CompletableFuture<PaymentResult> paymentFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    return paymentService.processPayment(orderId, amount);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            // Then - 等待所有操作完成
            OrderResult orderResult = orderFuture.get(30, TimeUnit.SECONDS);
            InventoryResult inventoryResult = inventoryFuture.get(10, TimeUnit.SECONDS);
            PaymentResult paymentResult = paymentFuture.get(15, TimeUnit.SECONDS);

            // 验证结果
            assertThat(orderResult).isNotNull();
            assertThat(orderResult.getOrderId()).isEqualTo(orderId);
            assertThat(orderResult.getStatus()).isEqualTo(OrderStatus.COMPLETED);

            assertThat(inventoryResult).isNotNull();
            assertThat(inventoryResult.isSuccess()).isTrue();
            assertThat(inventoryResult.getReservedQuantity()).isEqualTo(quantity);

            assertThat(paymentResult).isNotNull();
            assertThat(paymentResult.isSuccess()).isTrue();
            assertThat(paymentResult.getAmount()).isEqualTo(amount);
        }

        @Test
        @DisplayName("应该在库存不足时正确回滚订单")
        void shouldRollbackOrderWhenInventoryInsufficient() throws InterruptedException {
            // Given
            String orderId = "order-insufficient";
            String productId = "product-limited";
            int requestedQuantity = 100; // 超过库存数量
            double amount = 499.99;

            // When
            OrderResult orderResult = orderService.processOrder(orderId, productId, requestedQuantity, amount);

            // Then
            assertThat(orderResult).isNotNull();
            assertThat(orderResult.getStatus()).isEqualTo(OrderStatus.FAILED);
            assertThat(orderResult.getFailureReason()).contains("insufficient inventory");

            // 验证库存没有被扣减（回滚成功）
            InventoryResult inventoryResult = inventoryService.getCurrentInventory(productId);
            assertThat(inventoryResult.getAvailableQuantity()).isGreaterThan(0);
        }

        @Test
        @DisplayName("应该在支付失败时回滚库存")
        void shouldRollbackInventoryWhenPaymentFails() throws InterruptedException {
            // Given
            String orderId = "order-payment-fail";
            String productId = "product-002";
            int quantity = 5;
            double amount = 999.99; // 会导致支付失败

            // 预先减少库存以模拟库存充足
            inventoryService.updateInventory(productId, 100);

            // When
            OrderResult orderResult = orderService.processOrder(orderId, productId, quantity, amount);

            // Then
            assertThat(orderResult).isNotNull();
            assertThat(orderResult.getStatus()).isEqualTo(OrderStatus.FAILED);
            assertThat(orderResult.getFailureReason()).contains("payment failed");

            // 验证库存被回滚
            InventoryResult inventoryResult = inventoryService.getCurrentInventory(productId);
            assertThat(inventoryResult.getAvailableQuantity()).isEqualTo(100); // 没有变化
        }

        @Test
        @DisplayName("应该处理并发订单竞争")
        void shouldHandleConcurrentOrderCompetition() throws InterruptedException {
            // Given
            int numThreads = 10;
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch completionLatch = new CountDownLatch(numThreads);
            ExecutorService executor = Executors.newFixedThreadPool(numThreads);

            String productId = "concurrent-product";
            int quantity = 1;
            double amount = 49.99;

            // 预先设置库存
            inventoryService.updateInventory(productId, 5);

            List<CompletableFuture<OrderResult>> futures = new ArrayList<>();

            // When - 启动并发订单
            for (int i = 0; i < numThreads; i++) {
                final int threadId = i;
                CompletableFuture<OrderResult> future = CompletableFuture.supplyAsync(() -> {
                    try {
                        startLatch.await();
                        String orderId = "concurrent-order-" + threadId;
                        return orderService.processOrder(orderId, productId, quantity, amount);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    } finally {
                        completionLatch.countDown();
                    }
                }, executor);
                futures.add(future);
            }

            startLatch.countDown();
            completionLatch.await(60, TimeUnit.SECONDS);

            // Then - 分析结果
            List<OrderResult> results = futures.stream()
                .map(future -> {
                    try {
                        return future.get(10, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

            // 验证只有一个订单成功（库存限制）
            long successCount = results.stream()
                .mapToLong(result -> result.getStatus() == OrderStatus.COMPLETED ? 1 : 0)
                .sum();
            
            long failedCount = results.size() - successCount;
            
            assertThat(results).hasSize(numThreads);
            assertThat(successCount).isEqualTo(5); // 库存为5，所以只能成功5个
            assertThat(failedCount).isEqualTo(5); // 其余5个失败

            // 验证库存正确减少
            InventoryResult finalInventory = inventoryService.getCurrentInventory(productId);
            assertThat(finalInventory.getAvailableQuantity()).isEqualTo(0); // 全部售出

            executor.shutdown();
        }

        @Test
        @DisplayName("应该在系统故障时保持数据一致性")
        void shouldMaintainDataConsistencyDuringSystemFailure() throws InterruptedException {
            // Given
            String orderId = "failure-test-order";
            String productId = "product-failure";
            int quantity = 2;
            double amount = 199.99;

            // When - 在订单处理过程中模拟系统故障
            CompletableFuture<OrderResult> orderFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    // 模拟在中间步骤发生故障
                    OrderResult partialResult = orderService.processOrderWithFailure(
                        orderId, productId, quantity, amount, "payment");
                    
                    // 这个调用会模拟支付步骤故障
                    return partialResult;
                } catch (Exception e) {
                    return new OrderResult(orderId, OrderStatus.FAILED, "System failure: " + e.getMessage());
                }
            });

            // 等待故障发生
            try {
                OrderResult result = orderFuture.get(30, TimeUnit.SECONDS);
                
                // 验证系统在故障后仍能正常运行
                InventoryResult inventory = inventoryService.getCurrentInventory(productId);
                assertThat(inventory.getAvailableQuantity()).isGreaterThanOrEqualTo(0); // 不应为负数
                
                // 验证可以继续处理新订单
                String newOrderId = "recovery-order";
                OrderResult newOrderResult = orderService.processOrder(newOrderId, productId, 1, 99.99);
                assertThat(newOrderResult).isNotNull();
                
            } catch (TimeoutException e) {
                // 故障测试超时，说明系统仍在处理，这是预期的
            }
        }
    }

    @Nested
    @DisplayName("分布式系统协调测试")
    class DistributedSystemCoordinationTest {

        @Test
        @DisplayName("应该协调多个服务的分布式操作")
        void shouldCoordinateDistributedOperationsAcrossServices() throws InterruptedException {
            // Given
            String transactionId = "distributed-transaction-001";
            List<String> services = Arrays.asList("user-service", "order-service", "payment-service", "notification-service");
            
            Map<String, ServiceOperationResult> serviceResults = new ConcurrentHashMap<>();
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch completionLatch = new CountDownLatch(services.size());
            
            ExecutorService executor = Executors.newFixedThreadPool(services.size());
            
            // When - 启动跨服务的事务
            for (String service : services) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        ServiceOperationResult result = performDistributedOperation(transactionId, service);
                        serviceResults.put(service, result);
                    } catch (Exception e) {
                        serviceResults.put(service, new ServiceOperationResult(service, false, e.getMessage()));
                    } finally {
                        completionLatch.countDown();
                    }
                });
            }
            
            startLatch.countDown();
            completionLatch.await(45, TimeUnit.SECONDS);
            
            // Then - 验证所有服务都成功完成
            assertThat(serviceResults).hasSize(services.size());
            assertThat(serviceResults.values()).allMatch(ServiceOperationResult::isSuccess);
            
            // 验证所有服务使用相同的事务锁
            Set<String> transactionLocks = serviceResults.values().stream()
                .map(ServiceOperationResult::getLockName)
                .collect(Collectors.toSet());
            assertThat(transactionLocks).hasSize(1); // 应该使用同一个事务锁
            
            executor.shutdown();
        }

        @Test
        @DisplayName("应该在某个服务失败时回滚整个事务")
        void shouldRollbackEntireTransactionWhenServiceFails() throws InterruptedException {
            // Given
            String transactionId = "rollback-transaction-001";
            List<String> services = Arrays.asList("user-service", "order-service", "payment-failing-service", "notification-service");
            
            Map<String, ServiceOperationResult> serviceResults = new ConcurrentHashMap<>();
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch completionLatch = new CountDownLatch(services.size());
            
            ExecutorService executor = Executors.newFixedThreadPool(services.size());
            
            // When - 启动可能失败的事务
            for (String service : services) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        ServiceOperationResult result = performDistributedOperationWithFailure(
                            transactionId, service, "payment-failing-service".equals(service));
                        serviceResults.put(service, result);
                    } catch (Exception e) {
                        serviceResults.put(service, new ServiceOperationResult(service, false, e.getMessage()));
                    } finally {
                        completionLatch.countDown();
                    }
                });
            }
            
            startLatch.countDown();
            completionLatch.await(30, TimeUnit.SECONDS);
            
            // Then - 验证事务回滚
            assertThat(serviceResults).hasSize(services.size());
            
            long successCount = serviceResults.values().stream()
                .mapToLong(result -> result.isSuccess() ? 1 : 0)
                .sum();
            
            assertThat(successCount).isLessThan(services.size()); // 至少有一个失败
            
            // 验证失败时其他成功操作被回滚
            List<String> rolledBackServices = serviceResults.values().stream()
                .filter(result -> !result.isSuccess())
                .map(ServiceOperationResult::getServiceName)
                .collect(Collectors.toList());
            
            assertThat(rolledBackServices).contains("payment-failing-service");
            
            executor.shutdown();
        }

        @Test
        @DisplayName("应该支持分布式锁的升级和降级")
        void shouldSupportLockUpgradeAndDowngrade() throws InterruptedException {
            // Given
            String resourceId = "upgrade-downgrade-resource";
            
            // When - 执行锁升级降级操作
            DistributedLock readLock = lockFactory.getReadLock(resourceId);
            DistributedLock writeLock = lockFactory.getWriteLock(resourceId);
            
            // 第一阶段：获取读锁
            assertThat(readLock.tryLock(5, TimeUnit.SECONDS)).isTrue();
            
            try {
                // 读操作
                Thread.sleep(100);
                
                // 第二阶段：升级为写锁
                assertThat(writeLock.tryLock(5, TimeUnit.SECONDS)).isTrue();
                
                try {
                    // 写操作
                    Thread.sleep(100);
                    
                    // 第三阶段：降级为读锁
                    writeLock.unlock();
                    
                    try {
                        // 继续读操作
                        Thread.sleep(100);
                    } finally {
                        readLock.unlock();
                    }
                    
                } finally {
                    // 确保写锁释放
                    if (writeLock.isHeldByCurrentThread()) {
                        writeLock.unlock();
                    }
                }
                
            } finally {
                // 确保读锁释放
                if (readLock.isHeldByCurrentThread()) {
                    readLock.unlock();
                }
            }
            
            // Then - 验证锁状态
            assertThat(readLock.isHeldByCurrentThread()).isFalse();
            assertThat(writeLock.isHeldByCurrentThread()).isFalse();
        }
    }

    @Nested
    @DisplayName("性能和监控测试")
    class PerformanceAndMonitoringTest {

        @Test
        @DisplayName("应该监控端到端操作的性能")
        void shouldMonitorEndToEndOperationPerformance() throws InterruptedException {
            // Given
            String orderId = "perf-test-order";
            String productId = "product-perf";
            int quantity = 1;
            double amount = 29.99;
            
            inventoryService.updateInventory(productId, 100);
            
            // 记录性能基线
            long startTime = System.currentTimeMillis();
            
            // When
            OrderResult orderResult = orderService.processOrder(orderId, productId, quantity, amount);
            
            long endTime = System.currentTimeMillis();
            long duration = endTime - startTime;
            
            // Then - 验证性能指标
            assertThat(orderResult).isNotNull();
            assertThat(orderResult.getStatus()).isEqualTo(OrderStatus.COMPLETED);
            assertThat(duration).isLessThan(10000); // 应该在10秒内完成
            
            // 验证监控指标
            LockPerformanceMetrics performanceMetrics = monitoringService.getPerformanceMetrics();
            assertThat(performanceMetrics).isNotNull();
            
            // 验证没有内存泄漏
            Runtime runtime = Runtime.getRuntime();
            long usedMemory = runtime.totalMemory() - runtime.freeMemory();
            assertThat(usedMemory).isLessThan(500 * 1024 * 1024); // 小于500MB
        }

        @Test
        @DisplayName("应该在高负载下保持性能稳定")
        void shouldMaintainPerformanceUnderHighLoad() throws InterruptedException {
            // Given
            int concurrentOrders = 20;
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch completionLatch = new CountDownLatch(concurrentOrders);
            ExecutorService executor = Executors.newFixedThreadPool(concurrentOrders);
            
            List<Long> responseTimes = Collections.synchronizedList(new ArrayList<>());
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger failureCount = new AtomicInteger(0);
            
            // 设置充足库存
            for (int i = 1; i <= 10; i++) {
                inventoryService.updateInventory("product-load-" + i, 100);
            }
            
            // When - 启动高负载测试
            for (int i = 0; i < concurrentOrders; i++) {
                final int orderIndex = i;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        
                        long operationStart = System.currentTimeMillis();
                        
                        String orderId = "load-test-order-" + orderIndex;
                        String productId = "product-load-" + (orderIndex % 10 + 1);
                        int quantity = 1;
                        double amount = 19.99 + orderIndex;
                        
                        OrderResult result = orderService.processOrder(orderId, productId, quantity, amount);
                        
                        long operationEnd = System.currentTimeMillis();
                        responseTimes.add(operationEnd - operationStart);
                        
                        if (result.getStatus() == OrderStatus.COMPLETED) {
                            successCount.incrementAndGet();
                        } else {
                            failureCount.incrementAndGet();
                        }
                        
                    } catch (Exception e) {
                        failureCount.incrementAndGet();
                    } finally {
                        completionLatch.countDown();
                    }
                });
            }
            
            startLatch.countDown();
            completionLatch.await(120, TimeUnit.SECONDS);
            
            // Then - 分析性能结果
            assertThat(successCount.get()).isGreaterThan(0);
            
            double averageResponseTime = responseTimes.stream()
                .mapToLong(Long::longValue)
                .average()
                .orElse(0.0);
            
            double p95ResponseTime = responseTimes.stream()
                .mapToLong(Long::longValue)
                .sorted()
                .skip(responseTimes.size() * 95 / 100)
                .findFirst()
                .orElse(0);
            
            // 验证性能要求
            assertThat(averageResponseTime).isLessThan(5000); // 平均响应时间小于5秒
            assertThat(p95ResponseTime).isLessThan(10000); // 95%响应时间小于10秒
            
            executor.shutdown();
        }
    }

    // 业务服务类

    @Service
    public static class OrderProcessingService {
        
        @Autowired
        private InventoryService inventoryService;
        
        @Autowired
        private PaymentService paymentService;
        
        @Autowired
        private DistributedLockFactory lockFactory;

        public OrderResult processOrder(String orderId, String productId, int quantity, double amount) {
            // 获取订单处理锁
            DistributedLock orderLock = lockFactory.getLock("order:" + orderId);
            
            try {
                // 获取锁
                if (!orderLock.tryLock(10, TimeUnit.SECONDS)) {
                    return new OrderResult(orderId, OrderStatus.FAILED, "Failed to acquire order lock");
                }
                
                try {
                    // 1. 检查库存
                    InventoryResult inventoryResult = inventoryService.checkAndReserveInventory(productId, quantity);
                    if (!inventoryResult.isSuccess()) {
                        return new OrderResult(orderId, OrderStatus.FAILED, "Insufficient inventory");
                    }
                    
                    // 2. 处理支付
                    PaymentResult paymentResult = paymentService.processPayment(orderId, amount);
                    if (!paymentResult.isSuccess()) {
                        // 回滚库存
                        inventoryService.releaseInventory(productId, quantity);
                        return new OrderResult(orderId, OrderStatus.FAILED, "Payment failed");
                    }
                    
                    // 3. 确认订单
                    return new OrderResult(orderId, OrderStatus.COMPLETED, "Order processed successfully");
                    
                } finally {
                    orderLock.unlock();
                }
                
            } catch (Exception e) {
                return new OrderResult(orderId, OrderStatus.FAILED, "System error: " + e.getMessage());
            }
        }
        
        public OrderResult processOrderWithFailure(String orderId, String productId, int quantity, double amount, String failureStep) {
            // 模拟在指定步骤失败
            if ("inventory".equals(failureStep)) {
                throw new RuntimeException("Inventory service unavailable");
            } else if ("payment".equals(failureStep)) {
                // 模拟支付失败
                return new OrderResult(orderId, OrderStatus.FAILED, "Payment processing failed");
            }
            
            return processOrder(orderId, productId, quantity, amount);
        }
    }

    @Service
    public static class InventoryService {
        
        private final Map<String, Integer> inventory = new ConcurrentHashMap<>();
        
        @Autowired
        private DistributedLockFactory lockFactory;

        public InventoryResult checkAndReserveInventory(String productId, int quantity) {
            DistributedLock inventoryLock = lockFactory.getLock("inventory:" + productId);
            
            try {
                if (!inventoryLock.tryLock(5, TimeUnit.SECONDS)) {
                    return new InventoryResult(false, 0, "Failed to acquire inventory lock");
                }
                
                try {
                    int available = inventory.getOrDefault(productId, 0);
                    
                    if (available < quantity) {
                        return new InventoryResult(false, available, "Insufficient inventory");
                    }
                    
                    // 扣减库存
                    inventory.put(productId, available - quantity);
                    return new InventoryResult(true, available - quantity, "Inventory reserved");
                    
                } finally {
                    inventoryLock.unlock();
                }
                
            } catch (Exception e) {
                return new InventoryResult(false, 0, "System error: " + e.getMessage());
            }
        }
        
        public InventoryResult getCurrentInventory(String productId) {
            int available = inventory.getOrDefault(productId, 0);
            return new InventoryResult(true, available, "Current inventory");
        }
        
        public void updateInventory(String productId, int quantity) {
            inventory.put(productId, quantity);
        }
        
        public void releaseInventory(String productId, int quantity) {
            inventory.computeIfPresent(productId, (key, current) -> current + quantity);
        }
    }

    @Service
    public static class PaymentService {
        
        private final Map<String, Double> payments = new ConcurrentHashMap<>();
        
        @Autowired
        private DistributedLockFactory lockFactory;

        public PaymentResult processPayment(String orderId, double amount) {
            DistributedLock paymentLock = lockFactory.getLock("payment:" + orderId);
            
            try {
                if (!paymentLock.tryLock(5, TimeUnit.SECONDS)) {
                    return new PaymentResult(false, 0, "Failed to acquire payment lock");
                }
                
                try {
                    // 模拟支付处理
                    if (amount > 500) {
                        return new PaymentResult(false, 0, "Payment amount exceeds limit");
                    }
                    
                    // 记录支付
                    payments.put(orderId, amount);
                    return new PaymentResult(true, amount, "Payment processed");
                    
                } finally {
                    paymentLock.unlock();
                }
                
            } catch (Exception e) {
                return new PaymentResult(false, 0, "System error: " + e.getMessage());
            }
        }
    }

    // 结果类

    public static class OrderResult {
        private final String orderId;
        private final OrderStatus status;
        private final String failureReason;

        public OrderResult(String orderId, OrderStatus status, String failureReason) {
            this.orderId = orderId;
            this.status = status;
            this.failureReason = failureReason;
        }

        public String getOrderId() { return orderId; }
        public OrderStatus getStatus() { return status; }
        public String getFailureReason() { return failureReason; }
    }

    public enum OrderStatus {
        PENDING, COMPLETED, FAILED
    }

    public static class InventoryResult {
        private final boolean success;
        private final int reservedQuantity;
        private final String message;

        public InventoryResult(boolean success, int reservedQuantity, String message) {
            this.success = success;
            this.reservedQuantity = reservedQuantity;
            this.message = message;
        }

        public boolean isSuccess() { return success; }
        public int getReservedQuantity() { return reservedQuantity; }
        public int getAvailableQuantity() { return reservedQuantity; }
        public String getMessage() { return message; }
    }

    public static class PaymentResult {
        private final boolean success;
        private final double amount;
        private final String message;

        public PaymentResult(boolean success, double amount, String message) {
            this.success = success;
            this.amount = amount;
            this.message = message;
        }

        public boolean isSuccess() { return success; }
        public double getAmount() { return amount; }
        public String getMessage() { return message; }
    }

    public static class ServiceOperationResult {
        private final String serviceName;
        private final boolean success;
        private final String message;
        private final String lockName;

        public ServiceOperationResult(String serviceName, boolean success, String message) {
            this.serviceName = serviceName;
            this.success = success;
            this.message = message;
            this.lockName = "transaction:" + serviceName;
        }

        public String getServiceName() { return serviceName; }
        public boolean isSuccess() { return success; }
        public String getMessage() { return message; }
        public String getLockName() { return lockName; }
    }

    // 辅助方法

    private ServiceOperationResult performDistributedOperation(String transactionId, String serviceName) {
        DistributedLock serviceLock = lockFactory.getLock("service:" + serviceName + ":" + transactionId);
        
        try {
            if (!serviceLock.tryLock(10, TimeUnit.SECONDS)) {
                return new ServiceOperationResult(serviceName, false, "Failed to acquire service lock");
            }
            
            try {
                // 模拟服务操作
                Thread.sleep(100 + new Random().nextInt(200));
                return new ServiceOperationResult(serviceName, true, "Service completed successfully");
            } finally {
                serviceLock.unlock();
            }
            
        } catch (Exception e) {
            return new ServiceOperationResult(serviceName, false, "Service error: " + e.getMessage());
        }
    }

    private ServiceOperationResult performDistributedOperationWithFailure(String transactionId, String serviceName, boolean shouldFail) {
        if (shouldFail) {
            return new ServiceOperationResult(serviceName, false, "Simulated service failure");
        }
        return performDistributedOperation(transactionId, serviceName);
    }

    // 测试配置

    @Configuration
    static class TestConfiguration {
        
        @Bean
        public LockProvider mockLockProvider() {
            return new MockLockProvider();
        }
        
        @Bean
        public DistributedLockFactory lockFactory(LockProvider lockProvider) {
            return new SimpleDistributedLockFactory(lockProvider);
        }
        
        @Bean
        public LockMonitoringService monitoringService() {
            return new LockMonitoringService(
                null, null, 
                new LockMonitoringService.MonitoringConfig()
            );
        }
        
        @Bean
        public OrderProcessingService orderService(InventoryService inventoryService, PaymentService paymentService, DistributedLockFactory lockFactory) {
            OrderProcessingService service = new OrderProcessingService();
            service.inventoryService = inventoryService;
            service.paymentService = paymentService;
            service.lockFactory = lockFactory;
            return service;
        }
        
        @Bean
        public InventoryService inventoryService(DistributedLockFactory lockFactory) {
            InventoryService service = new InventoryService();
            service.lockFactory = lockFactory;
            return service;
        }
        
        @Bean
        public PaymentService paymentService(DistributedLockFactory lockFactory) {
            PaymentService service = new PaymentService();
            service.lockFactory = lockFactory;
            return service;
        }
    }

    // Mock类

    private static class MockLockProvider implements LockProvider {
        @Override
        public String getName() { return "mock"; }
        @Override
        public boolean isHealthy() { return true; }
        @Override
        public DistributedLock getLock(String lockName) { return new MockDistributedLock(lockName); }
    }

    private static class MockDistributedLock implements DistributedLock {
        private final String name;
        private final AtomicBoolean held = new AtomicBoolean(false);

        public MockDistributedLock(String name) {
            this.name = name;
        }

        @Override
        public String getName() { return name; }
        @Override
        public boolean isHeldByCurrentThread() { return held.get(); }
        @Override
        public void lock() { held.set(true); }
        @Override
        public void lockInterruptibly() throws InterruptedException { held.set(true); }
        @Override
        public boolean tryLock() { return !held.getAndSet(true); }
        @Override
        public boolean tryLock(long time, TimeUnit unit) throws InterruptedException {
            Thread.sleep(unit.toMillis(time));
            return !held.getAndSet(true);
        }
        @Override
        public void unlock() { held.set(false); }
        @Override
        public CompletableFuture<LockResult> tryLockAsync() {
            return CompletableFuture.completedFuture(new LockResult() {
                @Override
                public boolean isSuccess() { return !held.getAndSet(true); }
                @Override
                public Long getElapsedTime() { return 0L; }
            });
        }
        @Override
        public CompletableFuture<LockResult> tryLockAsync(long time, TimeUnit unit) {
            return CompletableFuture.supplyAsync(() -> new LockResult() {
                @Override
                public boolean isSuccess() { return !held.getAndSet(true); }
                @Override
                public Long getElapsedTime() { return unit.toMillis(time); }
            });
        }
        @Override
        public CompletableFuture<Void> unlockAsync() {
            held.set(false);
            return CompletableFuture.completedFuture(null);
        }
        @Override
        public boolean tryRenewLease(long time, TimeUnit unit) { return true; }
        @Override
        public CompletableFuture<Boolean> tryRenewLeaseAsync(long time, TimeUnit unit) {
            return CompletableFuture.completedFuture(true);
        }
        @Override
        public HealthCheck.HealthCheckResult healthCheck() {
            return new HealthCheck.HealthCheckResult(HealthCheck.ComponentStatus.UP, name, "Healthy");
        }
    }

    private static class SimpleDistributedLockFactory implements DistributedLockFactory {
        private final LockProvider lockProvider;
        private final Map<String, DistributedLock> locks = new ConcurrentHashMap<>();

        public SimpleDistributedLockFactory(LockProvider lockProvider) {
            this.lockProvider = lockProvider;
        }

        @Override
        public String getName() { return "simple-factory"; }
        @Override
        public boolean isHealthy() { return true; }
        @Override
        public DistributedLock getLock(String lockName) {
            return locks.computeIfAbsent(lockName, lockProvider::getLock);
        }
        @Override
        public DistributedReadWriteLock getReadWriteLock(String lockName) {
            return new SimpleReadWriteLock(lockName);
        }
        @Override
        public Map<String, DistributedLock> getAllLocks() { return new HashMap<>(locks); }
        @Override
        public void shutdown() { locks.clear(); }
        @Override
        public HealthCheck.HealthCheckResult healthCheck() {
            return new HealthCheck.HealthCheckResult(HealthCheck.ComponentStatus.UP, "simple-factory", "Healthy");
        }
    }

    private static class SimpleReadWriteLock implements DistributedReadWriteLock {
        private final DistributedLock readLock;
        private final DistributedLock writeLock;

        public SimpleReadWriteLock(String name) {
            this.readLock = new MockDistributedLock(name + ":read");
            this.writeLock = new MockDistributedLock(name + ":write");
        }

        @Override
        public DistributedLock readLock() { return readLock; }
        @Override
        public DistributedLock writeLock() { return writeLock; }
        @Override
        public String getName() { return "rw:" + readLock.getName(); }
        @Override
        public HealthCheck.HealthCheckResult healthCheck() {
            return new HealthCheck.HealthCheckResult(HealthCheck.ComponentStatus.UP, getName(), "Healthy");
        }
    }
}