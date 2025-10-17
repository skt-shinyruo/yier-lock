package com.mycorp.distributedlock.benchmarks;

import com.mycorp.distributedlock.api.DistributedLock;
import com.mycorp.distributedlock.redis.RedisDistributedLockFactory;
import com.mycorp.distributedlock.springboot.SpringDistributedLockFactory;
import com.mycorp.distributedlock.springboot.config.DistributedLockProperties;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 5)
@Measurement(iterations = 5, time = 10)
@Fork(1)
public class SpringDistributedLockBenchmark {

    private GenericContainer<?> redisContainer;
    private RedisClient redisClient;
    private RedisDistributedLockFactory directFactory;
    private AnnotationConfigApplicationContext springContext;
    private SpringDistributedLockFactory springFactory;

    @Setup(Level.Trial)
    public void setupTrial() throws Exception {
        // Start Redis container
        redisContainer = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                .withExposedPorts(6379);
        redisContainer.start();

        String redisHost = redisContainer.getHost();
        Integer redisPort = redisContainer.getMappedPort(6379);

        redisClient = RedisClient.create(RedisURI.builder()
                .withHost(redisHost)
                .withPort(redisPort)
                .build());

        // Direct (non-Spring) factory for comparison
        directFactory = new RedisDistributedLockFactory(redisClient);

        // Minimal Spring context for SpringDistributedLockFactory
        springContext = new AnnotationConfigApplicationContext();
        springContext.register(TestSpringConfig.class);
        springContext.refresh();

        springFactory = springContext.getBean(SpringDistributedLockFactory.class);
    }

    @TearDown(Level.Trial)
    public void tearDownTrial() {
        if (springContext != null) {
            springContext.close();
        }
        if (directFactory != null) {
            directFactory.shutdown();
        }
        if (redisClient != null) {
            redisClient.shutdown();
        }
        if (redisContainer != null) {
            redisContainer.stop();
        }
    }

    /**
     * Benchmark direct Redis lock acquisition and release (non-Spring baseline)
     */
    @Benchmark
    public void directLockAcquireRelease(Blackhole bh) {
        String lockKey = "bench-direct-" + Thread.currentThread().getId();
        DistributedLock lock = directFactory.getLock(lockKey);

        lock.lock(5, TimeUnit.SECONDS);
        try {
            bh.consume(lock.isHeldByCurrentThread());
        } finally {
            lock.unlock();
        }
    }

    /**
     * Benchmark Spring-managed lock acquisition and release
     * This measures the overhead of Spring factory instantiation and injection
     */
    @Benchmark
    public void springLockAcquireRelease(Blackhole bh) {
        String lockKey = "bench-spring-" + Thread.currentThread().getId();
        DistributedLock lock = springFactory.getLock(lockKey);

        lock.lock(5, TimeUnit.SECONDS);
        try {
            bh.consume(lock.isHeldByCurrentThread());
        } finally {
            lock.unlock();
        }
    }

    /**
     * To measure AOP overhead (from @DistributedLock annotation):
     * 1. Run the above benchmarks with AOP enabled (default).
     * 2. Temporarily disable the AOP Aspect in DistributedLockAutoConfiguration
     *    by commenting out @EnableAspectJAutoProxy or the Aspect bean.
     * 3. Rebuild and rerun benchmarks.
     * 4. Compare springLockAcquireRelease results: difference is AOP proxy overhead.
     * Note: This benchmark uses manual lock calls, so AOP overhead is in factory/AutoConfig,
     * not annotation processing. For full annotation AOP, create a Spring @Service with
     * @DistributedLock method and benchmark its invocation vs direct call.
     */

    @Configuration
    static class TestSpringConfig {

        @Bean
        public DistributedLockProperties distributedLockProperties() {
            DistributedLockProperties properties = new DistributedLockProperties();
            // Default config; customize as needed for benchmark
            return properties;
        }

        @Bean
        public SpringDistributedLockFactory springDistributedLockFactory(
                RedisClient redisClient,
                DistributedLockProperties properties) {
            // Manually wire for minimal context (bypasses full AutoConfiguration for benchmark isolation)
            return new SpringDistributedLockFactory(redisClient, properties);
        }
    }
}