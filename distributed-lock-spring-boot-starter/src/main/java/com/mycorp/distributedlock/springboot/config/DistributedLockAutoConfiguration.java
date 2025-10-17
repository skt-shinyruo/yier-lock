package com.mycorp.distributedlock.springboot.config;

import com.mycorp.distributedlock.api.DistributedLockFactory;
import com.mycorp.distributedlock.api.LockProvider;
import com.mycorp.distributedlock.core.config.LockConfiguration;
import com.mycorp.distributedlock.core.util.LockKeyUtils;
import com.mycorp.distributedlock.springboot.SpringDistributedLockFactory;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.config.AopConfigUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 分布式锁 Spring Boot 自动配置类。
 * 提供 SpringDistributedLockFactory Bean 和相关配置。
 */
@Configuration
@ConditionalOnProperty(name = "spring.distributed-lock.enabled", matchIfMissing = true)
@EnableConfigurationProperties(DistributedLockProperties.class)

@Import({LockConfiguration.class})  // 导入 core 配置，但实际 LockConfiguration 是 POJO
public class DistributedLockAutoConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(DistributedLockAutoConfiguration.class);

    @Autowired
    private DistributedLockProperties properties;

    @Autowired(required = false)
    private Optional<MeterRegistry> meterRegistry = Optional.empty();

    @Autowired(required = false)
    private Optional<OpenTelemetry> openTelemetry = Optional.empty();

    @Autowired
    private org.springframework.core.env.Environment environment;

    /**
     * 构建统一的 LockConfiguration。
     */
    private LockConfiguration buildLockConfiguration() {
        return new LockConfiguration(environment);
    }

    private String formatDuration(Duration duration) {
        long seconds = duration.getSeconds();
        long nanos = duration.getNano();
        if (nanos > 0) {
            return seconds + "." + (nanos / 100_000) + "s";
        }
        return seconds + "s";
    }

    /**
     * 创建 RedisLockProvider Bean（仅当 type=redis 时）。
     */
    @Bean
    @ConditionalOnClass(name = "com.mycorp.distributedlock.redis.RedisLockProvider")
    @ConditionalOnProperty(name = "spring.distributed-lock.type", havingValue = "redis")
    @Primary
    public LockProvider redisLockProvider() {
        LockConfiguration lockConfig = buildLockConfiguration();
        RedisURI redisUri = createRedisUri();
        RedisClient redisClient = createRedisClient(redisUri);
        MeterRegistry mr = meterRegistry.orElse(null);
        OpenTelemetry ot = openTelemetry.orElse(null);
        return new com.mycorp.distributedlock.redis.RedisLockProvider(redisClient, lockConfig, mr, ot);
    }

    private RedisURI createRedisUri() {
        LockConfiguration lockConfig = buildLockConfiguration();
        String[] hosts = lockConfig.getRedisHosts().split(",");
        RedisURI.Builder builder;

        if (hosts.length == 1) {
            String hostPort = hosts[0].trim();
            if (lockConfig.isRedisSslEnabled()) {
                builder = RedisURI.Builder.redis(hostPort.split(":")[0],
                    Integer.parseInt(hostPort.split(":")[1]));
                builder.withSsl(true);
            } else {
                builder = RedisURI.Builder.redis(hostPort);
            }
        } else {
            RedisURI[] redisURIs = new RedisURI[hosts.length];
            for (int i = 0; i < hosts.length; i++) {
                String hostPort = hosts[i].trim();
                if (lockConfig.isRedisSslEnabled()) {
                    redisURIs[i] = RedisURI.Builder.redis(hostPort.split(":")[0],
                        Integer.parseInt(hostPort.split(":")[1])).withSsl(true).build();
                } else {
                    redisURIs[i] = RedisURI.create("redis://" + hostPort);
                }
            }
            builder = RedisURI.Builder.cluster(redisURIs[0]);
            for (int i = 1; i < redisURIs.length; i++) {
                builder.withHost(redisURIs[i].getHost(), redisURIs[i].getPort());
            }
        }

        // 添加认证信息
        String password = lockConfig.getRedisPassword();
        if (password != null && !password.isEmpty()) {
            builder.withPassword(password);
        }

        return builder.build();
    }

    private RedisClient createRedisClient(RedisURI redisUri) {
        RedisClient client = RedisClient.create(redisUri);

        // 配置连接池支持
        // Lettuce 默认启用连接池，我们可以通过 ClientResources 进行更细粒度的配置
        io.lettuce.core.resource.ClientResources.Builder resourcesBuilder =
            io.lettuce.core.resource.DefaultClientResources.builder();

        // 配置线程池大小（影响异步操作性能）
        if (properties.getRedis().getPool().getMaxTotal() > 0) {
            resourcesBuilder.ioThreadPoolSize(properties.getRedis().getPool().getMaxTotal());
            resourcesBuilder.computationThreadPoolSize(Runtime.getRuntime().availableProcessors() * 2);
        }

        client.setResources(resourcesBuilder.build());

        logger.info("Redis client created with enhanced connection pool configuration: maxTotal={}, maxIdle={}",
            properties.getRedis().getPool().getMaxTotal(), properties.getRedis().getPool().getMaxIdle());

        return client;
    }

    /**
     * 创建 ZooKeeperLockProvider Bean（仅当 type=zookeeper 时）。
     */
    @Bean
    @ConditionalOnClass(name = "com.mycorp.distributedlock.zookeeper.ZooKeeperLockProvider")
    @ConditionalOnProperty(name = "spring.distributed-lock.type", havingValue = "zookeeper")
    @Primary
    public LockProvider zookeeperLockProvider() {
        LockConfiguration lockConfig = buildLockConfiguration();
        CuratorFramework curator = createCuratorFramework();
        MeterRegistry mr = meterRegistry.orElse(null);
        OpenTelemetry ot = openTelemetry.orElse(null);
        return new com.mycorp.distributedlock.zookeeper.ZooKeeperLockProvider(curator, lockConfig, mr, ot);
    }

    private CuratorFramework createCuratorFramework() {
        String connectString = properties.getZookeeper().getConnectString();

        CuratorFrameworkFactory.Builder builder = CuratorFrameworkFactory.builder()
                .connectString(connectString)
                .sessionTimeoutMs((int) properties.getZookeeper().getSessionTimeout().toMillis())
                .connectionTimeoutMs((int) properties.getZookeeper().getConnectionTimeout().toMillis())
                .retryPolicy(new ExponentialBackoffRetry(
                    (int) properties.getRetryInterval().toMillis(),
                    properties.getMaxRetries()));

        // 添加认证
        if (properties.getZookeeper().isAuthEnabled() &&
            properties.getZookeeper().getAuthInfo() != null) {
            String[] authParts = properties.getZookeeper().getAuthInfo().split(":");
            if (authParts.length == 2) {
                builder.authorization(properties.getZookeeper().getAuthScheme(),
                    (authParts[0] + ":" + authParts[1]).getBytes());
            }
        }

        // 创建并启动 CuratorFramework
        CuratorFramework curator = builder.build();
        curator.start();

        try {
            curator.blockUntilConnected((int) properties.getZookeeper().getConnectionTimeout().toMillis(),
                java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Interrupted while waiting for ZooKeeper connection", e);
        }

        return curator;
    }

    /**
     * 创建 SpringDistributedLockFactory Bean。
     * 委托到注入的 LockProvider。
     */
    @Bean
    @ConditionalOnBean(LockProvider.class)
    @ConditionalOnMissingBean(DistributedLockFactory.class)
    public SpringDistributedLockFactory springDistributedLockFactory(LockProvider delegate) {
        MeterRegistry mr = meterRegistry.orElse(null);
        OpenTelemetry ot = openTelemetry.orElse(null);

        logger.info("SpringDistributedLockFactory created with provider: {}", delegate.getClass().getSimpleName());
        return new SpringDistributedLockFactory(delegate, mr, ot);
    }

    /**
     * 启用 AspectJ AOP 支持，为后续 AOP 切面准备。
     */
    @Bean
    public static AopConfigUtils enableAspectJAutoProxy() {
        AopConfigUtils.forceAutoProxyCreatorAdviceClasses();
        return null;  // 不返回 Bean
    }
}