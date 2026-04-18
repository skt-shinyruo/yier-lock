package com.mycorp.distributedlock.examples.spring;

import com.mycorp.distributedlock.api.LockExecutor;
import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.WaitPolicy;
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
    CommandLineRunner demoRunner(OrderService orderService, LockExecutor lockExecutor) {
        return args -> {
            orderService.processOrder("42");

            String result = lockExecutor.withLock(
                sampleRequest("example:spring:user-9"),
                () -> "Manual Spring lease acquired"
            );
            System.out.println(result);
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

    private static LockRequest sampleRequest(String key) {
        return new LockRequest(
            new LockKey(key),
            LockMode.MUTEX,
            WaitPolicy.timed(Duration.ofSeconds(2))
        );
    }
}
