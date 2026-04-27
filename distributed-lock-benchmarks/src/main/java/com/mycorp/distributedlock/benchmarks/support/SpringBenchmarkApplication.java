package com.mycorp.distributedlock.benchmarks.support;

import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.SynchronousLockExecutor;
import com.mycorp.distributedlock.api.WaitPolicy;
import com.mycorp.distributedlock.springboot.annotation.DistributedLock;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.stereotype.Service;

import java.time.Duration;

@SpringBootApplication
public class SpringBenchmarkApplication {

    @Service
    public static class ProgrammaticBenchmarkService {

        private final SynchronousLockExecutor lockExecutor;

        public ProgrammaticBenchmarkService(SynchronousLockExecutor lockExecutor) {
            this.lockExecutor = lockExecutor;
        }

        public int programmatic(String id) throws Exception {
            return lockExecutor.withLock(
                new LockRequest(
                    new LockKey("bench:spring:programmatic:" + id),
                    LockMode.MUTEX,
                    WaitPolicy.timed(Duration.ofMillis(250))
                ),
                lease -> id.hashCode()
            );
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
