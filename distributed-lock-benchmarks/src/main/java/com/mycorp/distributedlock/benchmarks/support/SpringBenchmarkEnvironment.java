package com.mycorp.distributedlock.benchmarks.support;

import com.mycorp.distributedlock.api.LockClient;
import com.mycorp.distributedlock.api.SynchronousLockExecutor;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.Objects;

public final class SpringBenchmarkEnvironment implements AutoCloseable {

    private final RedisBenchmarkEnvironment redisEnvironment;
    private final ConfigurableApplicationContext applicationContext;

    private SpringBenchmarkEnvironment(
        RedisBenchmarkEnvironment redisEnvironment,
        ConfigurableApplicationContext applicationContext
    ) {
        this.redisEnvironment = Objects.requireNonNull(redisEnvironment, "redisEnvironment");
        this.applicationContext = Objects.requireNonNull(applicationContext, "applicationContext");
    }

    public static SpringBenchmarkEnvironment start() {
        RedisBenchmarkEnvironment redisEnvironment = RedisBenchmarkEnvironment.start();
        ConfigurableApplicationContext applicationContext = new SpringApplicationBuilder(SpringBenchmarkApplication.class)
            .properties(
                "spring.main.web-application-type=none",
                "distributed.lock.enabled=true",
                "distributed.lock.backend=redis",
                "distributed.lock.redis.uri=" + redisEnvironment.redisUri(),
                "distributed.lock.redis.lease-time=30s",
                "distributed.lock.spring.annotation.enabled=true",
                "distributed.lock.spring.annotation.default-timeout=250ms"
            )
            .run();
        return new SpringBenchmarkEnvironment(redisEnvironment, applicationContext);
    }

    public LockClient lockClient() {
        return applicationContext.getBean(LockClient.class);
    }

    public SynchronousLockExecutor synchronousLockExecutor() {
        return applicationContext.getBean(SynchronousLockExecutor.class);
    }

    public SpringBenchmarkApplication.ProgrammaticBenchmarkService programmaticService() {
        return applicationContext.getBean(SpringBenchmarkApplication.ProgrammaticBenchmarkService.class);
    }

    public SpringBenchmarkApplication.AnnotatedBenchmarkService annotatedService() {
        return applicationContext.getBean(SpringBenchmarkApplication.AnnotatedBenchmarkService.class);
    }

    @Override
    public void close() throws Exception {
        try {
            applicationContext.close();
        } finally {
            redisEnvironment.close();
        }
    }
}
