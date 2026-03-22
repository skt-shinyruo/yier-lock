package com.mycorp.distributedlock.benchmarks.support;

import com.mycorp.distributedlock.api.LockManager;
import com.mycorp.distributedlock.api.MutexLock;
import com.mycorp.distributedlock.springboot.annotation.DistributedLock;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.stereotype.Service;

import java.time.Duration;

@SpringBootApplication
public class SpringBenchmarkApplication {

    @Service
    public static class ProgrammaticBenchmarkService {

        private final LockManager lockManager;

        public ProgrammaticBenchmarkService(LockManager lockManager) {
            this.lockManager = lockManager;
        }

        public int programmatic(String id) throws InterruptedException {
            MutexLock lock = lockManager.mutex("bench:spring:programmatic:" + id);
            if (!lock.tryLock(Duration.ofMillis(250))) {
                throw new IllegalStateException("Failed to acquire programmatic benchmark lock");
            }
            try (lock) {
                return id.hashCode();
            }
        }
    }

    @Service
    public static class AnnotatedBenchmarkService {

        @DistributedLock(key = "bench:spring:annotated:#{#p0}", waitFor = "250ms")
        public int annotated(String id) {
            return id.hashCode();
        }
    }
}
