package com.mycorp.distributedlock.redis;

import com.mycorp.distributedlock.api.DistributedLock;
import com.mycorp.distributedlock.api.DistributedReadWriteLock;
import com.mycorp.distributedlock.api.LockProvider;
import com.mycorp.distributedlock.core.config.LockConfiguration;
import com.mycorp.distributedlock.core.observability.LockMetrics;
import com.mycorp.distributedlock.core.observability.LockTracing;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Redis backed {@link LockProvider}. Provides lazy initialization so it can be discovered
 * via {@link java.util.ServiceLoader} without requiring explicit wiring.
 */
public class RedisLockProvider implements LockProvider {

    private static final Logger logger = LoggerFactory.getLogger(RedisLockProvider.class);
    private static final String PROVIDER_TYPE = "redis";
    private static final int PROVIDER_PRIORITY = 200;

    private final ConcurrentHashMap<String, RedisDistributedLock> locks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RedisDistributedReadWriteLock> readWriteLocks = new ConcurrentHashMap<>();
    private final Object initializationMonitor = new Object();

    private RedisClient redisClient;
    private StatefulRedisConnection<String, String> connection;
    private StatefulRedisPubSubConnection<String, String> pubSubConnection;
    private LockConfiguration configuration;
    private LockMetrics metrics;
    private LockTracing tracing;
    private ScheduledExecutorService watchdogExecutor;

    private volatile boolean initialized = false;
    private volatile boolean shutdown = false;
    private boolean ownsRedisClient = false;

    public RedisLockProvider() {
        // Lazy initialization path
    }

    public RedisLockProvider(RedisClient redisClient,
                             LockConfiguration configuration,
                             MeterRegistry meterRegistry,
                             OpenTelemetry openTelemetry) {
        initialize(redisClient, configuration, meterRegistry, openTelemetry);
    }

    @Override
    public String getType() {
        return PROVIDER_TYPE;
    }

    @Override
    public int getPriority() {
        return PROVIDER_PRIORITY;
    }

    @Override
    public DistributedLock createLock(String key) {
        ensureProviderReady();
        return locks.computeIfAbsent(key, this::createRedisLock);
    }

    @Override
    public DistributedReadWriteLock createReadWriteLock(String key) {
        ensureProviderReady();
        return readWriteLocks.computeIfAbsent(key, this::createReadWriteRedisLock);
    }

    @Override
    public void close() {
        shutdown();
    }

    public void shutdown() {
        if (!initialized && shutdown) {
            return;
        }

        synchronized (initializationMonitor) {
            if (shutdown) {
                return;
            }
            shutdown = true;
        }

        logger.info("Shutting down RedisLockProvider");

        if (watchdogExecutor != null) {
            logger.info("Shutting down Redis watchdog executor");
            watchdogExecutor.shutdown();
            try {
                if (!watchdogExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    logger.warn("Watchdog executor did not terminate gracefully, forcing shutdown");
                    watchdogExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                watchdogExecutor.shutdownNow();
            }
        }

        cleanupHeldLocks();
        closeConnections();

        if (ownsRedisClient && redisClient != null) {
            try {
                redisClient.shutdown();
            } catch (RuntimeException e) {
                logger.warn("Error shutting down Redis client", e);
            }
        }

        locks.clear();
        readWriteLocks.clear();
        initialized = false;

        logger.info("RedisLockProvider shutdown completed");
    }

    private void ensureProviderReady() {
        ensureNotShutdown();
        ensureInitialized();
    }

    private void ensureInitialized() {
        if (initialized) {
            return;
        }
        initialize(null, null, null, null);
    }

    private void initialize(RedisClient client,
                            LockConfiguration config,
                            MeterRegistry meterRegistry,
                            OpenTelemetry openTelemetry) {
        if (initialized) {
            return;
        }

        synchronized (initializationMonitor) {
            if (initialized) {
                return;
            }

            this.configuration = config != null ? config : new LockConfiguration();
            this.redisClient = client != null ? client : createRedisClient(this.configuration);
            this.ownsRedisClient = client == null;
            this.connection = this.redisClient.connect();
            this.pubSubConnection = this.redisClient.connectPubSub();
            this.metrics = new LockMetrics(meterRegistry, this.configuration.isMetricsEnabled());
            this.tracing = new LockTracing(openTelemetry, this.configuration.isTracingEnabled());
            this.watchdogExecutor = this.configuration.isWatchdogEnabled()
                ? Executors.newScheduledThreadPool(2, r -> {
                    Thread thread = new Thread(r, "redis-lock-watchdog");
                    thread.setDaemon(true);
                    return thread;
                })
                : null;

            this.initialized = true;
            this.shutdown = false;

            logger.info("RedisLockProvider initialized with hosts: {}", this.configuration.getRedisHosts());
        }
    }

    private RedisDistributedLock createRedisLock(String name) {
        return new RedisDistributedLock(
            name,
            connection,
            pubSubConnection,
            configuration,
            metrics,
            tracing,
            watchdogExecutor
        );
    }

    private RedisDistributedReadWriteLock createReadWriteRedisLock(String name) {
        return new RedisDistributedReadWriteLock(
            name,
            connection,
            pubSubConnection,
            configuration,
            metrics,
            tracing,
            watchdogExecutor
        );
    }

    private void cleanupHeldLocks() {
        logger.info("Cleaning up {} locks and {} read-write locks", locks.size(), readWriteLocks.size());

        locks.values().forEach(lock -> {
            try {
                if (lock.isHeldByCurrentThread()) {
                    logger.info("Force releasing lock {} during shutdown", lock.getName());
                    lock.unlock();
                }
            } catch (Exception e) {
                logger.error("Error releasing lock {} during shutdown", lock.getName(), e);
            }
        });

        readWriteLocks.values().forEach(rwLock -> {
            try {
                if (rwLock.readLock().isHeldByCurrentThread()) {
                    logger.info("Force releasing read lock {} during shutdown", rwLock.getName());
                    rwLock.readLock().unlock();
                }
                if (rwLock.writeLock().isHeldByCurrentThread()) {
                    logger.info("Force releasing write lock {} during shutdown", rwLock.getName());
                    rwLock.writeLock().unlock();
                }
            } catch (Exception e) {
                logger.error("Error releasing read-write lock {} during shutdown", rwLock.getName(), e);
            }
        });
    }

    private void closeConnections() {
        if (pubSubConnection != null) {
            try {
                pubSubConnection.close();
            } catch (RuntimeException e) {
                logger.warn("Error closing Redis pub-sub connection", e);
            }
        }

        if (connection != null) {
            try {
                connection.close();
            } catch (RuntimeException e) {
                logger.warn("Error closing Redis connection", e);
            }
        }
    }

    private RedisClient createRedisClient(LockConfiguration configuration) {
        String hosts = configuration.getRedisHosts();
        String[] hostEntries = hosts.split(",");
        String primary = hostEntries[0].trim();
        if (hostEntries.length > 1) {
            logger.info("Multiple Redis hosts configured ({}). Using {} as primary for simple client setup.",
                Arrays.toString(hostEntries), primary);
        }

        RedisURI redisURI = buildRedisUri(primary, configuration);
        RedisClient client = RedisClient.create(redisURI);
        logger.info("Created Redis client for {}", redisURI);
        return client;
    }

    private RedisURI buildRedisUri(String hostPort, LockConfiguration configuration) {
        String host = hostPort;
        int port = 6379;
        if (hostPort.contains(":")) {
            String[] parts = hostPort.split(":", 2);
            host = parts[0];
            port = Integer.parseInt(parts[1]);
        }

        RedisURI.Builder builder = RedisURI.builder()
            .withHost(host)
            .withPort(port)
            .withDatabase(configuration.getRedisDatabase());

        if (configuration.isRedisSslEnabled()) {
            builder.withSsl(true);
        }

        String password = configuration.getRedisPassword();
        if (password != null && !password.isEmpty()) {
            builder.withPassword(password);
        }

        String clientName = configuration.getRedisClientName();
        if (clientName != null && !clientName.isEmpty()) {
            builder.withClientName(clientName);
        }

        return builder.build();
    }

    private void ensureNotShutdown() {
        if (shutdown) {
            throw new IllegalStateException("RedisLockProvider already shut down.");
        }
    }
}
