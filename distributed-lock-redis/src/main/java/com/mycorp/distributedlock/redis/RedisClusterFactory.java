package com.mycorp.distributedlock.redis;

import com.mycorp.distributedlock.core.config.LockConfiguration;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.resource.ClientResources;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Factory for creating Redis connections with cluster and sentinel support.
 * Based on Redisson's multi-mode Redis connection handling.
 */
public class RedisClusterFactory {

    private static final Logger logger = LoggerFactory.getLogger(RedisClusterFactory.class);

    public enum RedisMode {
        SINGLE,
        CLUSTER,
        SENTINEL
    }

    private final LockConfiguration configuration;
    private final MeterRegistry meterRegistry;
    private final OpenTelemetry openTelemetry;
    RedisMode mode;

    public RedisClusterFactory(LockConfiguration configuration,
                              MeterRegistry meterRegistry,
                              OpenTelemetry openTelemetry) {
        this.configuration = configuration;
        this.meterRegistry = meterRegistry;
        this.openTelemetry = openTelemetry;
        this.mode = detectRedisMode();
    }

    /**
     * Create a Redis distributed lock factory with appropriate connection mode.
     */
    public RedisDistributedLockFactory createLockFactory() {
        switch (mode) {
            case CLUSTER:
                return createClusterLockFactory();
            case SENTINEL:
                return createSentinelLockFactory();
            case SINGLE:
            default:
                return createSingleLockFactory();
        }
    }

    private RedisMode detectRedisMode() {
        String hosts = configuration.getRedisHosts();
        if (hosts == null || hosts.trim().isEmpty()) {
            hosts = "localhost:6379";
        }

        // Simple heuristic: if multiple hosts with same port, likely cluster
        // if multiple hosts with different ports, likely sentinel
        String[] hostPorts = hosts.split(",");
        if (hostPorts.length > 1) {
            boolean samePort = Arrays.stream(hostPorts)
                    .map(hp -> hp.split(":"))
                    .filter(parts -> parts.length == 2)
                    .map(parts -> parts[1])
                    .collect(Collectors.toSet())
                    .size() == 1;

            if (samePort) {
                logger.info("Detected Redis Cluster mode with hosts: {}", hosts);
                return RedisMode.CLUSTER;
            } else {
                logger.info("Detected Redis Sentinel mode with hosts: {}", hosts);
                return RedisMode.SENTINEL;
            }
        }

        logger.info("Using single Redis instance mode");
        return RedisMode.SINGLE;
    }

    private RedisDistributedLockFactory createSingleLockFactory() {
        ClientResources clientResources = ClientResources.builder()
                .ioThreadPoolSize(Runtime.getRuntime().availableProcessors() * 2)
                .computationThreadPoolSize(Runtime.getRuntime().availableProcessors())
                .build();

        RedisClient redisClient = RedisClient.create(clientResources, configuration.getRedisHosts());
        return RedisDistributedLockFactory.createWithCustomResources(configuration, meterRegistry, openTelemetry);
    }

    private RedisDistributedLockFactory createClusterLockFactory() {
        String[] nodes = configuration.getRedisHosts().split(",");
        List<RedisURI> redisURIs = Arrays.stream(nodes)
                .map(node -> {
                    String[] parts = node.split(":");
                    return RedisURI.builder()
                            .withHost(parts[0])
                            .withPort(Integer.parseInt(parts[1]))
                            .build();
                })
                .collect(Collectors.toList());

        ClientResources clientResources = ClientResources.builder()
                .ioThreadPoolSize(Runtime.getRuntime().availableProcessors() * 2)
                .computationThreadPoolSize(Runtime.getRuntime().availableProcessors())
                .build();

        RedisClusterClient clusterClient = RedisClusterClient.create(clientResources, redisURIs);

        // For cluster mode, we need to create a different factory that can handle cluster connections
        // This is a simplified implementation - in production you'd want full cluster support
        try {
            // Use the first node as the primary connection for compatibility
            RedisURI primaryUri = redisURIs.get(0);
            RedisClient redisClient = RedisClient.create(clientResources, primaryUri);
            return new RedisDistributedLockFactory(redisClient, configuration, meterRegistry, openTelemetry);
        } catch (Exception e) {
            logger.error("Failed to create cluster connection", e);
            throw new RuntimeException("Failed to create Redis cluster connection", e);
        }
    }

    private RedisDistributedLockFactory createSentinelLockFactory() {
        // For sentinel, we need master name and sentinel hosts
        // This is a simplified implementation
        String sentinelMaster = System.getProperty("redis.sentinel.master", "mymaster");
        String sentinelHosts = configuration.getRedisHosts();

        ClientResources clientResources = ClientResources.builder()
                .ioThreadPoolSize(Runtime.getRuntime().availableProcessors() * 2)
                .computationThreadPoolSize(Runtime.getRuntime().availableProcessors())
                .build();

        RedisURI.Builder builder = RedisURI.builder()
                .withSentinel(sentinelHosts.split(",")[0].split(":")[0],
                             Integer.parseInt(sentinelHosts.split(",")[0].split(":")[1]))
                .withSentinelMasterId(sentinelMaster);

        RedisClient redisClient = RedisClient.create(clientResources, builder.build());
        return new RedisDistributedLockFactory(redisClient, configuration, meterRegistry, openTelemetry);
    }

    public RedisMode getMode() {
        return mode;
    }
}
