package com.mycorp.distributedlock.examples.springboot;

import com.mycorp.distributedlock.api.BatchLockOperations;
import com.mycorp.distributedlock.api.DistributedLock;
import com.mycorp.distributedlock.api.DistributedLockFactory;
import com.mycorp.distributedlock.api.exception.BatchLockException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 库存服务示例
 * 演示批量操作和复杂业务逻辑中的锁使用
 */
@Service
public class InventoryService {

    @Autowired
    private DistributedLockFactory lockFactory;

    // 模拟库存数据
    private final Map<String, Integer> inventory = new HashMap<>();

    public InventoryService() {
        // 初始化一些测试数据
        inventory.put("product-1", 100);
        inventory.put("product-2", 50);
        inventory.put("product-3", 75);
        inventory.put("product-4", 30);
        inventory.put("product-5", 90);
    }

    /**
     * 更新单个产品库存
     */
    public synchronized String updateInventory(String productId, int quantity) {
        DistributedLock lock = lockFactory.getLock("inventory:" + productId);

        try {
            boolean acquired = lock.tryLock(5, 10, java.util.concurrent.TimeUnit.SECONDS);
            if (!acquired) {
                return "Failed to acquire lock for product: " + productId;
            }

            // 执行业务逻辑
            int currentStock = inventory.getOrDefault(productId, 0);
            int newStock = currentStock + quantity;

            if (newStock < 0) {
                return "Insufficient inventory for product: " + productId;
            }

            inventory.put(productId, newStock);

            System.out.println("Updated inventory for " + productId + ": " + currentStock + " -> " + newStock);
            return "Inventory updated for product " + productId + ": " + newStock;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Inventory update interrupted for product: " + productId;
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 批量更新库存 - 基础版本
     */
    public Map<String, Object> updateInventoryBatch(List<String> productIds) {
        Map<String, Object> result = new HashMap<>();
        List<String> lockNames = productIds.stream()
            .map(id -> "inventory:" + id)
            .sorted() // 排序避免死锁
            .collect(Collectors.toList());

        BatchLockOperations<DistributedLock> batchOps =
            lockFactory.createBatchLockOperations(lockNames, locks -> {
                Map<String, String> updates = new HashMap<>();

                for (String productId : productIds) {
                    // 模拟库存扣减
                    int currentStock = inventory.getOrDefault(productId, 0);
                    if (currentStock > 0) {
                        int newStock = currentStock - 1; // 扣减1个
                        inventory.put(productId, newStock);
                        updates.put(productId, currentStock + " -> " + newStock);
                    } else {
                        updates.put(productId, "Insufficient stock");
                    }
                }

                return updates;
            });

        try {
            @SuppressWarnings("unchecked")
            Map<String, String> updates = (Map<String, String>) batchOps.execute();

            result.put("success", true);
            result.put("updates", updates);
            result.put("message", "Batch inventory update completed");

        } catch (BatchLockException e) {
            result.put("success", false);
            result.put("error", e.getMessage());
            result.put("message", "Batch inventory update failed");
        }

        return result;
    }

    /**
     * 批量更新库存 - 高级版本（事务性）
     */
    public Map<String, Object> updateInventoryBatchTransactional(List<String> productIds) {
        Map<String, Object> result = new HashMap<>();
        List<String> lockNames = productIds.stream()
            .map(id -> "inventory:" + id)
            .sorted()
            .collect(Collectors.toList());

        BatchLockOperations<DistributedLock> batchOps =
            lockFactory.createBatchLockOperations(lockNames, locks -> {
                Map<String, Integer> originalStocks = new HashMap<>();
                Map<String, String> updates = new HashMap<>();

                try {
                    // 第一阶段：预检和预留
                    for (String productId : productIds) {
                        int currentStock = inventory.getOrDefault(productId, 0);
                        if (currentStock <= 0) {
                            throw new RuntimeException("Insufficient stock for product: " + productId);
                        }
                        originalStocks.put(productId, currentStock);
                        updates.put(productId, "Reserved (was: " + currentStock + ")");
                    }

                    // 模拟一些处理时间
                    Thread.sleep(1000);

                    // 第二阶段：提交更改
                    for (String productId : productIds) {
                        int originalStock = originalStocks.get(productId);
                        int newStock = originalStock - 1;
                        inventory.put(productId, newStock);
                        updates.put(productId, originalStock + " -> " + newStock + " (committed)");
                    }

                    return updates;

                } catch (Exception e) {
                    // 如果是预检失败，不需要回滚
                    if (e.getMessage().contains("Insufficient stock")) {
                        throw e;
                    }

                    // 如果是其他异常，回滚已做的更改
                    System.err.println("Transaction failed, rolling back...");
                    for (Map.Entry<String, Integer> entry : originalStocks.entrySet()) {
                        inventory.put(entry.getKey(), entry.getValue());
                        updates.put(entry.getKey(), "Rolled back to: " + entry.getValue());
                    }
                    throw e;
                }
            });

        try {
            @SuppressWarnings("unchecked")
            Map<String, String> updates = (Map<String, String>) batchOps.execute();

            result.put("success", true);
            result.put("updates", updates);
            result.put("message", "Transactional batch inventory update completed");

        } catch (BatchLockException e) {
            result.put("success", false);
            result.put("error", e.getMessage());
            result.put("message", "Transactional batch inventory update failed");
        }

        return result;
    }

    /**
     * 查询库存
     */
    public synchronized int getInventory(String productId) {
        return inventory.getOrDefault(productId, 0);
    }

    /**
     * 查询所有库存
     */
    public synchronized Map<String, Integer> getAllInventory() {
        return new HashMap<>(inventory);
    }

    /**
     * 初始化产品库存
     */
    public synchronized String initializeProduct(String productId, int initialStock) {
        DistributedLock lock = lockFactory.getLock("inventory:" + productId);

        try {
            boolean acquired = lock.tryLock(5, 10, java.util.concurrent.TimeUnit.SECONDS);
            if (!acquired) {
                return "Failed to acquire lock for product initialization: " + productId;
            }

            inventory.put(productId, initialStock);
            System.out.println("Initialized inventory for " + productId + ": " + initialStock);
            return "Product " + productId + " initialized with stock: " + initialStock;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Product initialization interrupted: " + productId;
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 清理库存数据（测试用）
     */
    public synchronized void clearInventory() {
        inventory.clear();
        System.out.println("Inventory cleared");
    }
}