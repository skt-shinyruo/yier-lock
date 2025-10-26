package com.mycorp.distributedlock.examples;

import com.mycorp.distributedlock.api.*;
import com.mycorp.distributedlock.api.exception.BatchLockException;
import com.mycorp.distributedlock.redis.RedisClusterFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 批量操作示例
 * 演示如何使用批量锁操作来处理多个资源的事务性操作
 */
public class BatchOperationsExample {

    private final DistributedLockFactory lockFactory;

    public BatchOperationsExample() {
        this.lockFactory = createLockFactory();
    }

    public static void main(String[] args) {
        BatchOperationsExample example = new BatchOperationsExample();

        try {
            System.out.println("=== 演示基本批量操作 ===");
            example.demonstrateBasicBatchOperations();

            System.out.println("\n=== 演示事务性批量操作 ===");
            example.demonstrateTransactionalBatchOperations();

            System.out.println("\n=== 演示异步批量操作 ===");
            example.demonstrateAsyncBatchOperations();

            System.out.println("\n=== 演示依赖关系处理 ===");
            example.demonstrateDependencyHandling();

            System.out.println("\n=== 演示批量操作优化 ===");
            example.demonstrateBatchOptimization();

        } catch (Exception e) {
            System.err.println("Batch operations example failed: " + e.getMessage());
            e.printStackTrace();
        } finally {
            example.shutdown();
        }
    }

    /**
     * 演示基本批量操作
     */
    public void demonstrateBasicBatchOperations() {
        List<String> resourceIds = Arrays.asList("user-1", "user-2", "user-3", "user-4", "user-5");

        // 创建锁名称列表（按字典序排序避免死锁）
        List<String> lockNames = resourceIds.stream()
            .map(id -> "resource:" + id)
            .sorted()
            .collect(Collectors.toList());

        BatchLockOperations<DistributedLock> batchOps =
            lockFactory.createBatchLockOperations(lockNames, locks -> {
                System.out.println("Acquired " + locks.size() + " locks for batch operation");

                // 处理每个资源
                for (int i = 0; i < resourceIds.size(); i++) {
                    String resourceId = resourceIds.get(i);
                    DistributedLock lock = locks.get(i);

                    System.out.println("Processing resource: " + resourceId +
                                     " with lock: " + lock.getName());

                    // 模拟处理时间
                    simulateProcessing(resourceId);
                }

                return resourceIds.size();
            });

        try {
            Integer processedCount = batchOps.execute();
            System.out.println("Basic batch operation completed, processed " + processedCount + " resources");
        } catch (BatchLockException e) {
            System.err.println("Basic batch operation failed: " + e.getMessage());
        }
    }

    /**
     * 演示事务性批量操作
     */
    public void demonstrateTransactionalBatchOperations() {
        List<OrderItem> orderItems = Arrays.asList(
            new OrderItem("order-1", "product-A", 2),
            new OrderItem("order-2", "product-B", 1),
            new OrderItem("order-3", "product-C", 3)
        );

        // 为每个订单项创建锁
        List<String> lockNames = orderItems.stream()
            .map(item -> "inventory:" + item.getProductId())
            .distinct()
            .sorted()
            .collect(Collectors.toList());

        BatchLockOperations<DistributedLock> batchOps =
            lockFactory.createBatchLockOperations(lockNames, locks -> {
                System.out.println("Starting transactional batch operation...");

                List<OrderItem> processedItems = new ArrayList<>();

                try {
                    // 第一阶段：检查库存并预留
                    for (OrderItem item : orderItems) {
                        if (!checkAndReserveInventory(item)) {
                            throw new RuntimeException("Insufficient inventory for product: " + item.getProductId());
                        }
                        processedItems.add(item);
                        System.out.println("Reserved inventory for: " + item);
                    }

                    // 第二阶段：提交订单
                    for (OrderItem item : processedItems) {
                        commitOrder(item);
                        System.out.println("Committed order for: " + item);
                    }

                    return processedItems.size();

                } catch (Exception e) {
                    // 发生异常，回滚已处理的订单
                    System.err.println("Transaction failed, rolling back...");
                    for (OrderItem item : processedItems) {
                        rollbackOrder(item);
                        System.out.println("Rolled back order for: " + item);
                    }
                    throw e; // 重新抛出异常
                }
            });

        try {
            Integer committedCount = batchOps.execute();
            System.out.println("Transactional batch operation completed, committed " + committedCount + " orders");
        } catch (BatchLockException e) {
            System.err.println("Transactional batch operation failed: " + e.getMessage());
        }
    }

    /**
     * 演示异步批量操作
     */
    public void demonstrateAsyncBatchOperations() {
        List<String> resourceIds = Arrays.asList("async-1", "async-2", "async-3", "async-4", "async-5");

        List<String> lockNames = resourceIds.stream()
            .map(id -> "async:" + id)
            .sorted()
            .collect(Collectors.toList());

        // 创建异步锁操作器
        AsyncLockOperations<DistributedLock> asyncOps = lockFactory.createAsyncLockOperations();

        try {
            CompletableFuture<List<String>> result = asyncOps.executeWithLocks(lockNames, locks -> {
                System.out.println("Executing async batch operation with " + locks.size() + " locks");

                // 异步处理每个资源
                List<CompletableFuture<String>> futures = new ArrayList<>();
                for (int i = 0; i < resourceIds.size(); i++) {
                    String resourceId = resourceIds.get(i);
                    DistributedLock lock = locks.get(i);

                    CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
                        try {
                            System.out.println("Async processing: " + resourceId);
                            simulateAsyncProcessing(resourceId);
                            return resourceId + " processed";
                        } catch (Exception e) {
                            throw new RuntimeException("Failed to process " + resourceId, e);
                        }
                    });

                    futures.add(future);
                }

                // 等待所有异步操作完成
                return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .thenApply(v -> futures.stream()
                        .map(CompletableFuture::join)
                        .collect(Collectors.toList()));
            });

            List<String> results = result.get();
            System.out.println("Async batch operation completed: " + results);

        } catch (InterruptedException | ExecutionException e) {
            System.err.println("Async batch operation failed: " + e.getMessage());
        }
    }

    /**
     * 演示依赖关系处理
     */
    public void demonstrateDependencyHandling() {
        // 定义资源及其依赖关系
        Map<String, List<String>> dependencies = Map.of(
            "resource:A", Arrays.asList("resource:B", "resource:C"),
            "resource:B", Arrays.asList("resource:D"),
            "resource:C", Collections.emptyList(),
            "resource:D", Collections.emptyList()
        );

        // 拓扑排序确保正确的锁获取顺序
        List<String> sortedResources = topologicalSort(dependencies.keySet(), dependencies);
        System.out.println("Processing order after topological sort: " + sortedResources);

        List<String> lockNames = sortedResources.stream()
            .map(resource -> "dep:" + resource)
            .collect(Collectors.toList());

        BatchLockOperations<DistributedLock> batchOps =
            lockFactory.createBatchLockOperations(lockNames, locks -> {
                System.out.println("Processing resources with dependencies...");

                // 按拓扑顺序处理资源
                for (String resource : sortedResources) {
                    System.out.println("Processing " + resource + " (dependencies satisfied)");
                    simulateProcessing(resource);

                    // 模拟依赖关系验证
                    List<String> deps = dependencies.get(resource);
                    if (deps != null && !deps.isEmpty()) {
                        System.out.println("  " + resource + " depends on: " + deps);
                    }
                }

                return sortedResources.size();
            });

        try {
            Integer processedCount = batchOps.execute();
            System.out.println("Dependency-aware batch operation completed, processed " + processedCount + " resources");
        } catch (BatchLockException e) {
            System.err.println("Dependency-aware batch operation failed: " + e.getMessage());
        }
    }

    /**
     * 演示批量操作优化
     */
    public void demonstrateBatchOptimization() {
        // 大批量数据
        List<String> largeBatch = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            largeBatch.add("item-" + String.format("%03d", i));
        }

        long startTime = System.currentTimeMillis();

        // 分批处理，避免一次性获取太多锁
        List<List<String>> partitions = partition(largeBatch, 20); // 每批20个
        int totalProcessed = 0;

        for (List<String> partition : partitions) {
            List<String> lockNames = partition.stream()
                .map(item -> "opt:" + item)
                .sorted()
                .collect(Collectors.toList());

            BatchLockOperations<DistributedLock> batchOps =
                lockFactory.createBatchLockOperations(lockNames, locks -> {
                    // 并行处理分区内的项目
                    partition.parallelStream().forEach(item -> {
                        simulateProcessing(item);
                    });
                    return partition.size();
                });

            try {
                Integer processed = batchOps.execute();
                totalProcessed += processed;
                System.out.println("Processed partition of " + processed + " items");
            } catch (BatchLockException e) {
                System.err.println("Partition processing failed: " + e.getMessage());
            }
        }

        long duration = System.currentTimeMillis() - startTime;
        System.out.println("Optimized batch operation completed in " + duration + "ms, " +
                         "processed " + totalProcessed + " items");
    }

    /**
     * 拓扑排序算法
     */
    private List<String> topologicalSort(Set<String> resources, Map<String, List<String>> dependencies) {
        List<String> result = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Set<String> visiting = new HashSet<>();

        for (String resource : resources) {
            if (!visited.contains(resource)) {
                topologicalSortVisit(resource, dependencies, visited, visiting, result);
            }
        }

        Collections.reverse(result); // 反转结果得到正确的处理顺序
        return result;
    }

    private void topologicalSortVisit(String resource, Map<String, List<String>> dependencies,
                                    Set<String> visited, Set<String> visiting, List<String> result) {
        visiting.add(resource);

        List<String> deps = dependencies.get(resource);
        if (deps != null) {
            for (String dep : deps) {
                if (visiting.contains(dep)) {
                    throw new RuntimeException("Circular dependency detected involving " + resource + " and " + dep);
                }
                if (!visited.contains(dep)) {
                    topologicalSortVisit(dep, dependencies, visited, visiting, result);
                }
            }
        }

        visiting.remove(resource);
        visited.add(resource);
        result.add(resource);
    }

    /**
     * 分区列表
     */
    private <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> partitions = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            partitions.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return partitions;
    }

    /**
     * 检查并预留库存
     */
    private boolean checkAndReserveInventory(OrderItem item) {
        // 模拟库存检查和预留
        System.out.println("Checking inventory for " + item);
        return Math.random() > 0.1; // 90% 成功率
    }

    /**
     * 提交订单
     */
    private void commitOrder(OrderItem item) {
        System.out.println("Committing order: " + item);
    }

    /**
     * 回滚订单
     */
    private void rollbackOrder(OrderItem item) {
        System.out.println("Rolling back order: " + item);
    }

    /**
     * 模拟同步处理
     */
    private void simulateProcessing(String resourceId) {
        try {
            Thread.sleep(100); // 模拟处理时间
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 模拟异步处理
     */
    private void simulateAsyncProcessing(String resourceId) throws InterruptedException {
        Thread.sleep(200); // 模拟异步处理时间
    }

    /**
     * 创建锁工厂
     */
    private DistributedLockFactory createLockFactory() {
        return RedisClusterFactory.builder()
            .redisUrl("redis://localhost:6379")
            .build();
    }

    /**
     * 关闭资源
     */
    public void shutdown() {
        if (lockFactory != null) {
            lockFactory.shutdown();
        }
    }

    /**
     * 订单项内部类
     */
    static class OrderItem {
        private final String orderId;
        private final String productId;
        private final int quantity;

        public OrderItem(String orderId, String productId, int quantity) {
            this.orderId = orderId;
            this.productId = productId;
            this.quantity = quantity;
        }

        public String getOrderId() { return orderId; }
        public String getProductId() { return productId; }
        public int getQuantity() { return quantity; }

        @Override
        public String toString() {
            return "OrderItem{" +
                   "orderId='" + orderId + '\'' +
                   ", productId='" + productId + '\'' +
                   ", quantity=" + quantity +
                   '}';
        }
    }
}