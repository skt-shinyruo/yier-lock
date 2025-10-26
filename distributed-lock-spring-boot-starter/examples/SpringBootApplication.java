package com.example.distributedlock.demo;

import com.mycorp.distributedlock.api.annotation.DistributedLock;
import com.mycorp.distributedlock.api.DistributedLockFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.CompletableFuture;

/**
 * Spring Boot 3.x 分布式锁使用示例
 * 
 * 此示例展示了如何在Spring Boot应用中使用分布式锁：
 * 1. 自动配置
 * 2. 注解式锁管理
 * 3. 编程式锁使用
 * 4. 健康检查端点
 * 
 * @since Spring Boot 3.x
 */
@SpringBootApplication
public class SpringBootApplication {

    public static void main(String[] args) {
        SpringApplication.run(SpringBootApplication.class, args);
    }

    /**
     * 业务服务类
     */
    @Service
    public static class BusinessService {

        @Autowired
        private DistributedLockFactory distributedLockFactory;

        /**
         * 使用注解方式进行分布式锁管理
         * 
         * @param orderId 订单ID
         * @return 处理结果
         */
        @DistributedLock(
            key = "order-process-#{orderId}",
            leaseTime = "30s",
            waitTime = "10s"
        )
        public String processOrderWithAnnotation(Long orderId) {
            System.out.println("处理订单: " + orderId);
            
            // 模拟业务处理时间
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            
            return "订单 " + orderId + " 处理完成";
        }

        /**
         * 编程式分布式锁使用
         * 
         * @param userId 用户ID
         * @return 处理结果
         */
        public String processUserUpdate(Long userId) {
            String lockKey = "user-update-" + userId;
            
            try (DistributedLock lock = distributedLockFactory.getLock(lockKey)) {
                // 获取分布式锁
                if (lock.tryLock()) {
                    System.out.println("开始更新用户: " + userId);
                    
                    // 模拟业务处理时间
                    Thread.sleep(3000);
                    
                    return "用户 " + userId + " 更新完成";
                } else {
                    return "无法获取用户 " + userId + " 的锁，请稍后重试";
                }
            } catch (Exception e) {
                System.err.println("处理用户更新时发生错误: " + e.getMessage());
                return "处理失败: " + e.getMessage();
            }
        }

        /**
         * 异步操作中的分布式锁使用
         * 
         * @param taskId 任务ID
         * @return 异步结果
         */
        @DistributedLock(
            key = "async-task-#{taskId}",
            leaseTime = "60s",
            waitTime = "5s",
            throwExceptionOnFailure = false
        )
        public CompletableFuture<String> processAsyncTask(Long taskId) {
            return CompletableFuture.supplyAsync(() -> {
                System.out.println("异步处理任务: " + taskId);
                
                // 模拟异步业务处理
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                
                return "异步任务 " + taskId + " 完成";
            });
        }

        /**
         * 批量操作示例
         * 
         * @param batchId 批次ID
         * @return 处理结果
         */
        @DistributedLock(
            key = "batch-process-#{batchId}",
            leaseTime = "120s",
            waitTime = "30s"
        )
        public String processBatchOperations(Long batchId) {
            System.out.println("开始批次处理: " + batchId);
            
            try {
                // 模拟批量操作
                for (int i = 1; i <= 10; i++) {
                    Thread.sleep(500);
                    System.out.println("处理批次 " + batchId + " 中的第 " + i + " 项");
                }
                
                return "批次 " + batchId + " 处理完成";
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "批次 " + batchId + " 处理被中断";
            }
        }
    }

    /**
     * REST控制器
     */
    @RestController
    @RequestMapping("/api")
    public static class LockController {

        @Autowired
        private BusinessService businessService;

        /**
         * 测试注解式锁
         */
        @GetMapping("/order/{orderId}/process")
        public String processOrder(@PathVariable Long orderId) {
            return businessService.processOrderWithAnnotation(orderId);
        }

        /**
         * 测试编程式锁
         */
        @GetMapping("/user/{userId}/update")
        public String updateUser(@PathVariable Long userId) {
            return businessService.processUserUpdate(userId);
        }

        /**
         * 测试异步操作
         */
        @GetMapping("/task/{taskId}/process")
        public CompletableFuture<String> processTask(@PathVariable Long taskId) {
            return businessService.processAsyncTask(taskId);
        }

        /**
         * 测试批量操作
         */
        @GetMapping("/batch/{batchId}/process")
        public String processBatch(@PathVariable Long batchId) {
            return businessService.processBatchOperations(batchId);
        }

        /**
         * 健康检查端点
         */
        @GetMapping("/health")
        public String health() {
            return "Application is running";
        }
    }
}