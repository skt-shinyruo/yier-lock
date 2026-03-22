package com.mycorp.distributedlock.examples.spring;

import com.mycorp.distributedlock.api.LockManager;
import com.mycorp.distributedlock.api.MutexLock;
import com.mycorp.distributedlock.springboot.annotation.DistributedLock;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Service;

import java.time.Duration;

@SpringBootApplication
public class SpringBootRedisExampleApplication {

    public static void main(String[] args) {
        SpringApplication.run(SpringBootRedisExampleApplication.class, args);
    }

    @Bean
    CommandLineRunner demoRunner(OrderService orderService, LockManager lockManager) {
        return args -> {
            orderService.processOrder("42");

            MutexLock lock = lockManager.mutex("example:spring:user-9");
            if (lock.tryLock(Duration.ofSeconds(2))) {
                try (lock) {
                    System.out.println("Manual Spring lock acquired for " + lock.key());
                }
            }
        };
    }

    @Service
    static class OrderService {

        @DistributedLock(key = "order:#{#p0}", waitFor = "2s")
        public void processOrder(String orderId) throws InterruptedException {
            System.out.println("Processing order " + orderId + " under distributed lock");
            Thread.sleep(100);
        }
    }
}
