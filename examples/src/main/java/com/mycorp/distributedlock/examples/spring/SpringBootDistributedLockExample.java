package com.mycorp.distributedlock.examples.spring;

import com.mycorp.distributedlock.api.DistributedLock;
import com.mycorp.distributedlock.api.DistributedLockFactory;
import com.mycorp.distributedlock.api.annotation.DistributedLock;
import com.mycorp.distributedlock.springboot.config.EnableDistributedLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@SpringBootApplication
@EnableDistributedLock
public class SpringBootDistributedLockExample {

    public static void main(String[] args) {
        SpringApplication.run(SpringBootDistributedLockExample.class, args);
        System.out.println("Spring Boot Distributed Lock Example started!");
    }

    /**
     * 示例Service類，展示手動鎖獲取和注解鎖使用。
     * 注意：由於AOP Aspect尚未實現，注解方法將fallback為無鎖執行。
     */
    @Service
    public static class LockService {

        @Autowired
        private DistributedLockFactory lockFactory;

        /**
         * 使用注解的示例方法：處理訂單，鎖鍵為"order:{orderId}"，超時10秒。
         * SpEL表達式支持變數注入。
         */
        @DistributedLock(value = "order:#{orderId}", timeout = Duration.ofSeconds(10))
        public void processOrder(String orderId) {
            // 模擬業務邏輯
            System.out.println("Processing order: " + orderId + " with distributed lock.");
            try {
                Thread.sleep(2000); // 模擬工作
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        /**
         * 手動獲取和釋放鎖的示例。
         */
        public void manualLockExample() {
            String lockKey = "example-key";
            Duration leaseTime = Duration.ofSeconds(30);
            try (DistributedLock lock = lockFactory.getLock(lockKey)) {
                boolean acquired = lock.tryLock(5, leaseTime.getSeconds(), TimeUnit.SECONDS);
                if (!acquired) {
                    System.out.println("Failed to acquire manual lock for key: " + lockKey);
                    return;
                }

                System.out.println("Manual lock acquired for key: " + lockKey);
                try {
                    Thread.sleep(5000); // 模擬工作
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    lock.unlock();
                }
                System.out.println("Manual lock work completed.");
            } catch (Exception e) {
                System.err.println("Error in manual lock example: " + e.getMessage());
            }
        }
    }
}
