package com.mycorp.distributedlock.zookeeper.connection;

import com.mycorp.distributedlock.core.config.LockConfiguration;
import com.mycorp.distributedlock.core.observability.LockMetrics;
import com.mycorp.distributedlock.core.observability.LockTracing;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Timer;
import io.opentelemetry.api.OpenTelemetry;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.retry.ExponentialBackoffRetry;
import org.apache.curator.framework.retry.RetryOneTime;
import org.apache.curator.framework.state.ConnectionState;
import org.apache.curator.framework.state.ConnectionStateListener;
import org.apache.curator.utils.CloseableUtils;
import org.apache.zookeeper.ZooKeeper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Zookeeper连接管理器
 * 提供高质量的连接池管理、会话管理、健康检查和集群支持
 * 
 * 主要特性：
 * - 连接池管理和监控
 * - 自动会话管理和重连
 * - 健康检查和故障检测
 * - 集群节点管理
 * - 性能指标收集
 * - 优雅关闭机制
 */
public class ZooKeeperConnectionManager implements AutoCloseable {
    
    private static final Logger logger = LoggerFactory.getLogger(ZooKeeperConnectionManager.class);
    
    // 连接池配置
    private static final int DEFAULT_POOL_SIZE = 10;
    private static final int DEFAULT_MIN_POOL_SIZE = 2;
    private static final int DEFAULT_MAX_POOL_SIZE = 50;
    private static final Duration DEFAULT_CONNECTION_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration DEFAULT_SESSION_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration DEFAULT_RETRY_INTERVAL = Duration.ofSeconds(1);
    
    // 连接状态监控
    private final AtomicBoolean isConnected = new AtomicBoolean(false);
    private final AtomicBoolean isShutdown = new AtomicBoolean(false);
    private final AtomicInteger activeConnections = new AtomicInteger(0);
    private final AtomicLong totalConnectionsCreated = new AtomicLong(0);
    private final AtomicLong totalConnectionsDestroyed = new AtomicLong(0);
    
    // Zookeeper相关
    private final CuratorFramework curatorFramework;
    private final LockConfiguration configuration;
    private final LockMetrics metrics;
    private final LockTracing tracing;
    
    // 线程池
    private final ScheduledExecutorService healthCheckExecutor;
    private final ScheduledExecutorService connectionMonitorExecutor;
    private final ExecutorService asyncOperationExecutor;
    
    // 连接池管理
    private final BlockingQueue<CuratorFramework> connectionPool;
    private final ReadWriteLock poolLock = new ReentrantReadWriteLock();
    private final AtomicInteger poolSize = new AtomicInteger(0);
    
    // 监听器
    private final ConnectionStateListener connectionStateListener;
    private final java.util.List<ConnectionStateListener> customListeners = new CopyOnWriteArrayList<>();
    
    // 指标收集
    private final Timer connectionCreateTimer;
    private final Timer connectionHealthCheckTimer;
    private final Counter connectionFailureCounter;
    private final Counter connectionRetryCounter;
    private final Gauge connectionPoolSizeGauge;
    private final Gauge activeConnectionsGauge;
    
    private volatile Instant lastConnectionTime;
    private volatile Duration connectionLatency;
    
    /**
     * 构造函数
     * 
     * @param connectString Zookeeper连接字符串
     * @param configuration 锁配置
     * @param meterRegistry 指标注册器
     * @param openTelemetry 分布式追踪
     */
    public ZooKeeperConnectionManager(String connectString,
                                    LockConfiguration configuration,
                                    io.micrometer.core.instrument.MeterRegistry meterRegistry,
                                    OpenTelemetry openTelemetry) {
        this.configuration = configuration != null ? configuration : new LockConfiguration();
        this.metrics = new LockMetrics(meterRegistry, this.configuration.isMetricsEnabled());
        this.tracing = new LockTracing(openTelemetry, this.configuration.isTracingEnabled());
        
        // 创建连接池
        int poolSize = getPoolSize();
        this.connectionPool = new LinkedBlockingQueue<>(poolSize);
        
        // 创建CuratorFramework实例
        this.curatorFramework = createCuratorFramework(connectString, this.configuration);
        
        // 启动CuratorFramework
        this.curatorFramework.start();
        
        // 初始化线程池
        this.healthCheckExecutor = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "zookeeper-health-check");
            t.setDaemon(true);
            return t;
        });
        
        this.connectionMonitorExecutor = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "zookeeper-connection-monitor");
            t.setDaemon(true);
            return t;
        });
        
        this.asyncOperationExecutor = Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors(), r -> {
                Thread t = new Thread(r, "zookeeper-async-ops");
                t.setDaemon(true);
                return t;
            }
        );
        
        // 初始化指标
        this.connectionCreateTimer = metrics.createTimer("zookeeper.connection.create.time");
        this.connectionHealthCheckTimer = metrics.createTimer("zookeeper.connection.health.check.time");
        this.connectionFailureCounter = metrics.createCounter("zookeeper.connection.failure.count");
        this.connectionRetryCounter = metrics.createCounter("zookeeper.connection.retry.count");
        this.connectionPoolSizeGauge = metrics.createGauge("zookeeper.connection.pool.size", () -> poolSize.get());
        this.activeConnectionsGauge = metrics.createGauge("zookeeper.connection.active.count", activeConnections::get);
        
        // 设置连接状态监听器
        this.connectionStateListener = new ConnectionStateListener() {
            @Override
            public void stateChanged(CuratorFramework client, ConnectionState newState) {
                handleConnectionStateChanged(newState);
            }
        };
        
        this.curatorFramework.getConnectionStateListenable().addListener(connectionStateListener);
        
        // 等待连接建立
        waitForConnection();
        
        // 启动健康检查
        startHealthCheck();
        
        // 启动连接监控
        startConnectionMonitoring();
        
        logger.info("ZooKeeperConnectionManager initialized with pool size: {}", poolSize);
    }
    
    /**
     * 获取连接池中的连接
     * 
     * @return CuratorFramework实例
     * @throws Exception 连接获取失败
     */
    public CuratorFramework getConnection() throws Exception {
        checkNotShutdown();
        
        Timer.Sample sample = metrics.startTimer(connectionCreateTimer);
        
        try {
            CuratorFramework connection = tryGetConnectionFromPool();
            if (connection != null) {
                return connection;
            }
            
            // 如果池中没有可用连接，创建新的连接
            return createNewConnection();
        } finally {
            sample.stop(connectionCreateTimer);
        }
    }
    
    /**
     * 检查连接是否健康
     * 
     * @param connection 连接实例
     * @return 是否健康
     */
    public boolean isConnectionHealthy(CuratorFramework connection) {
        if (connection == null || isShutdown.get()) {
            return false;
        }
        
        Timer.Sample sample = metrics.startTimer(connectionHealthCheckTimer);
        try {
            ZooKeeper zooKeeper = connection.getZookeeperClient().getZooKeeper();
            return zooKeeper != null && zooKeeper.getState() == ZooKeeper.States.CONNECTED;
        } catch (Exception e) {
            logger.debug("Connection health check failed", e);
            return false;
        } finally {
            sample.stop(connectionHealthCheckTimer);
        }
    }
    
    /**
     * 执行健康检查
     * 
     * @return 健康检查结果
     */
    public ConnectionHealthResult performHealthCheck() {
        Instant startTime = Instant.now();
        
        try {
            boolean connected = isConnected.get();
            boolean curatorHealthy = curatorFramework != null && 
                                   curatorFramework.getZookeeperClient().isConnected();
            
            // 检查连接延迟
            Duration responseTime = Duration.between(startTime, Instant.now());
            
            // 获取连接统计
            int activeConnectionsCount = activeConnections.get();
            int poolSizeCount = poolSize.get();
            
            HealthStatus status = determineHealthStatus(connected, curatorHealthy, responseTime);
            
            return new ConnectionHealthResult(
                status,
                responseTime.toMillis(),
                connected && curatorHealthy,
                activeConnectionsCount,
                poolSizeCount,
                lastConnectionTime,
                connectionLatency,
                null
            );
        } catch (Exception e) {
            return new ConnectionHealthResult(
                HealthStatus.UNHEALTHY,
                Duration.between(startTime, Instant.now()).toMillis(),
                false,
                activeConnections.get(),
                poolSize.get(),
                lastConnectionTime,
                connectionLatency,
                e.getMessage()
            );
        }
    }
    
    /**
     * 添加自定义连接状态监听器
     * 
     * @param listener 监听器
     */
    public void addConnectionStateListener(ConnectionStateListener listener) {
        if (listener != null) {
            customListeners.add(listener);
            curatorFramework.getConnectionStateListenable().addListener(listener);
        }
    }
    
    /**
     * 移除连接状态监听器
     * 
     * @param listener 监听器
     */
    public void removeConnectionStateListener(ConnectionStateListener listener) {
        customListeners.remove(listener);
        curatorFramework.getConnectionStateListenable().removeListener(listener);
    }
    
    /**
     * 异步获取连接
     * 
     * @return 异步连接获取结果
     */
    public CompletableFuture<CuratorFramework> getConnectionAsync() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return getConnection();
            } catch (Exception e) {
                throw new CompletionException("Failed to get connection", e);
            }
        }, asyncOperationExecutor);
    }
    
    /**
     * 获取连接池统计信息
     * 
     * @return 连接池统计
     */
    public ConnectionPoolStatistics getConnectionPoolStatistics() {
        return new ConnectionPoolStatistics(
            poolSize.get(),
            activeConnections.get(),
            connectionPool.size(),
            totalConnectionsCreated.get(),
            totalConnectionsDestroyed.get(),
            isConnected.get(),
            lastConnectionTime
        );
    }
    
    /**
     * 重置连接池统计
     */
    public void resetConnectionPoolStatistics() {
        totalConnectionsCreated.set(0);
        totalConnectionsDestroyed.set(0);
    }
    
    /**
     * 强制重新连接
     * 
     * @return 是否重连成功
     */
    public boolean forceReconnection() {
        logger.info("Forcing Zookeeper reconnection");
        
        try {
            curatorFramework.getZookeeperClient().restart();
            waitForConnection();
            return isConnected.get();
        } catch (Exception e) {
            logger.error("Failed to force reconnection", e);
            return false;
        }
    }
    
    /**
     * 获取Zookeeper集群信息
     * 
     * @return 集群信息
     */
    public ZookeeperClusterInfo getClusterInfo() {
        try {
            ZooKeeper zooKeeper = curatorFramework.getZookeeperClient().getZooKeeper();
            String ensemble = curatorFramework.getZookeeperClient().getEnsembleProvider().getConnectionString();
            
            return new ZookeeperClusterInfo(
                ensemble,
                zooKeeper.getSessionId(),
                zooKeeper.getState(),
                curatorFramework.getZookeeperClient().getConnectionTimeoutMs(),
                curatorFramework.getZookeeperClient().getSessionTimeoutMs()
            );
        } catch (Exception e) {
            logger.error("Failed to get cluster info", e);
            return null;
        }
    }
    
    @Override
    public void close() {
        if (isShutdown.getAndSet(true)) {
            return;
        }
        
        logger.info("Shutting down ZooKeeperConnectionManager");
        
        try {
            // 关闭连接池中的所有连接
            closeConnectionPool();
            
            // 关闭主要的CuratorFramework实例
            CloseableUtils.closeQuietly(curatorFramework);
            
            // 关闭线程池
            shutdownExecutor(healthCheckExecutor, "health-check");
            shutdownExecutor(connectionMonitorExecutor, "connection-monitor");
            shutdownExecutor(asyncOperationExecutor, "async-operation");
            
            logger.info("ZooKeeperConnectionManager shutdown completed");
        } catch (Exception e) {
            logger.error("Error during shutdown", e);
        }
    }
    
    // 私有方法区域
    
    private CuratorFramework createCuratorFramework(String connectString, LockConfiguration config) {
        // 配置重试策略
        org.apache.curator.retry.RetryPolicy retryPolicy;
        if (config.getMaxRetries() > 0) {
            retryPolicy = new ExponentialBackoffRetry(
                (int) config.getRetryInterval().toMillis(),
                config.getMaxRetries());
        } else {
            retryPolicy = new RetryOneTime((int) config.getRetryInterval().toMillis());
        }
        
        // 构建CuratorFramework
        CuratorFrameworkFactory.Builder builder = CuratorFrameworkFactory.builder()
            .connectString(connectString)
            .sessionTimeoutMs((int) config.getZookeeperSessionTimeout().toMillis())
            .connectionTimeoutMs((int) config.getZookeeperConnectionTimeout().toMillis())
            .retryPolicy(retryPolicy);
        
        // 添加认证信息
        if (config.isZookeeperAuthEnabled() && config.getZookeeperAuthInfo() != null) {
            builder.authorization(
                config.getZookeeperAuthScheme(),
                config.getZookeeperAuthInfo().getBytes(StandardCharsets.UTF_8));
        }
        
        // 设置Zookeeper特性
        builder.canBeReadOnly(config.isZookeeperReadOnlyEnabled());
        
        return builder.build();
    }
    
    private void waitForConnection() {
        int timeoutMs = (int) configuration.getZookeeperConnectionTimeout().toMillis();
        
        try {
            curatorFramework.blockUntilConnected(timeoutMs, TimeUnit.MILLISECONDS);
            
            if (curatorFramework.getZookeeperClient().isConnected()) {
                isConnected.set(true);
                lastConnectionTime = Instant.now();
                logger.info("Successfully connected to Zookeeper ensemble");
            } else {
                throw new RuntimeException("Failed to connect to Zookeeper within timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for Zookeeper connection", e);
        }
    }
    
    private CuratorFramework tryGetConnectionFromPool() {
        poolLock.readLock().lock();
        try {
            CuratorFramework connection = connectionPool.poll();
            if (connection != null && isConnectionHealthy(connection)) {
                activeConnections.incrementAndGet();
                return connection;
            } else if (connection != null) {
                // 连接不健康，需要销毁
                destroyConnection(connection);
            }
        } finally {
            poolLock.readLock().unlock();
        }
        return null;
    }
    
    private CuratorFramework createNewConnection() throws Exception {
        poolLock.writeLock().lock();
        try {
            // 再次检查池中是否有可用连接
            CuratorFramework connection = connectionPool.poll();
            if (connection != null && isConnectionHealthy(connection)) {
                activeConnections.incrementAndGet();
                return connection;
            }
            
            // 创建新连接
            if (poolSize.get() < getMaxPoolSize()) {
                CuratorFramework newConnection = createCuratorFramework(
                    curatorFramework.getZookeeperClient().getEnsembleProvider().getConnectionString(),
                    configuration);
                newConnection.start();
                
                poolSize.incrementAndGet();
                totalConnectionsCreated.incrementAndGet();
                activeConnections.incrementAndGet();
                
                logger.debug("Created new connection, pool size: {}", poolSize.get());
                return newConnection;
            } else {
                throw new RuntimeException("Connection pool is at maximum capacity");
            }
        } finally {
            poolLock.writeLock().unlock();
        }
    }
    
    private void handleConnectionStateChanged(ConnectionState newState) {
        Instant now = Instant.now();
        Duration latency = lastConnectionTime != null ? 
            Duration.between(lastConnectionTime, now) : null;
        
        logger.info("Zookeeper connection state changed to: {}", newState);
        
        switch (newState) {
            case CONNECTED:
                isConnected.set(true);
                lastConnectionTime = now;
                connectionLatency = latency;
                metrics.incrementCounter("zookeeper.connection.connected.count");
                break;
                
            case SUSPENDED:
                metrics.incrementCounter("zookeeper.connection.suspended.count");
                logger.warn("Zookeeper connection suspended");
                break;
                
            case RECONNECTED:
                isConnected.set(true);
                lastConnectionTime = now;
                connectionLatency = latency;
                metrics.incrementCounter("zookeeper.connection.reconnected.count");
                logger.info("Zookeeper connection reestablished");
                break;
                
            case LOST:
                isConnected.set(false);
                metrics.incrementCounter("zookeeper.connection.lost.count");
                logger.error("Zookeeper connection lost");
                // 触发连接恢复逻辑
                triggerConnectionRecovery();
                break;
        }
        
        // 通知自定义监听器
        for (ConnectionStateListener listener : customListeners) {
            try {
                listener.stateChanged(curatorFramework, newState);
            } catch (Exception e) {
                logger.warn("Error in custom connection state listener", e);
            }
        }
    }
    
    private void triggerConnectionRecovery() {
        connectionRetryCounter.increment();
        
        try {
            // 尝试自动恢复连接
            curatorFramework.getZookeeperClient().restart();
            waitForConnection();
        } catch (Exception e) {
            connectionFailureCounter.increment();
            logger.error("Failed to recover Zookeeper connection", e);
        }
    }
    
    private void startHealthCheck() {
        healthCheckExecutor.scheduleAtFixedRate(() -> {
            try {
                if (!isShutdown.get()) {
                    performHealthCheck();
                }
            } catch (Exception e) {
                logger.debug("Health check error", e);
            }
        }, 30, 30, TimeUnit.SECONDS);
    }
    
    private void startConnectionMonitoring() {
        connectionMonitorExecutor.scheduleAtFixedRate(() -> {
            try {
                if (!isShutdown.get()) {
                    // 清理不健康的连接
                    cleanupUnhealthyConnections();
                    
                    // 检查池大小调整需求
                    adjustConnectionPoolSize();
                }
            } catch (Exception e) {
                logger.debug("Connection monitoring error", e);
            }
        }, 10, 10, TimeUnit.SECONDS);
    }
    
    private void cleanupUnhealthyConnections() {
        poolLock.writeLock().lock();
        try {
            connectionPool.removeIf(connection -> {
                if (!isConnectionHealthy(connection)) {
                    destroyConnection(connection);
                    return true;
                }
                return false;
            });
        } finally {
            poolLock.writeLock().unlock();
        }
    }
    
    private void adjustConnectionPoolSize() {
        int currentPoolSize = poolSize.get();
        int maxPoolSize = getMaxPoolSize();
        int minPoolSize = getMinPoolSize();
        int activeCount = activeConnections.get();
        
        // 如果活跃连接数接近池大小上限且池未满，尝试扩展
        if (activeCount > currentPoolSize * 0.8 && currentPoolSize < maxPoolSize) {
            // 异步创建新连接
            asyncOperationExecutor.submit(() -> {
                try {
                    createNewConnection();
                } catch (Exception e) {
                    logger.debug("Failed to expand connection pool", e);
                }
            });
        }
        
        // 如果活跃连接数很低且池超过最小大小，考虑收缩
        if (activeCount < currentPoolSize * 0.3 && currentPoolSize > minPoolSize) {
            // 暂时不收缩，等待更长时间的数据
        }
    }
    
    private void destroyConnection(CuratorFramework connection) {
        try {
            CloseableUtils.closeQuietly(connection);
            activeConnections.decrementAndGet();
            totalConnectionsDestroyed.incrementAndGet();
            poolSize.decrementAndGet();
            logger.debug("Destroyed unhealthy connection");
        } catch (Exception e) {
            logger.warn("Error destroying connection", e);
        }
    }
    
    private void closeConnectionPool() {
        poolLock.writeLock().lock();
        try {
            connectionPool.removeIf(connection -> {
                destroyConnection(connection);
                return true;
            });
        } finally {
            poolLock.writeLock().unlock();
        }
    }
    
    private void shutdownExecutor(ExecutorService executor, String name) {
        try {
            executor.shutdown();
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        logger.debug("Shut down {} executor", name);
    }
    
    private int getPoolSize() {
        return configuration.getConfig().hasPath("distributed-lock.zookeeper.connection.pool-size") ?
            configuration.getConfig().getInt("distributed-lock.zookeeper.connection.pool-size") : 
            DEFAULT_POOL_SIZE;
    }
    
    private int getMinPoolSize() {
        return configuration.getConfig().hasPath("distributed-lock.zookeeper.connection.min-pool-size") ?
            configuration.getConfig().getInt("distributed-lock.zookeeper.connection.min-pool-size") : 
            DEFAULT_MIN_POOL_SIZE;
    }
    
    private int getMaxPoolSize() {
        return configuration.getConfig().hasPath("distributed-lock.zookeeper.connection.max-pool-size") ?
            configuration.getConfig().getInt("distributed-lock.zookeeper.connection.max-pool-size") : 
            DEFAULT_MAX_POOL_SIZE;
    }
    
    private HealthStatus determineHealthStatus(boolean connected, boolean curatorHealthy, Duration responseTime) {
        if (!connected || !curatorHealthy) {
            return HealthStatus.UNHEALTHY;
        }
        
        if (responseTime.toMillis() > 5000) {
            return HealthStatus.DEGRADED;
        }
        
        return HealthStatus.HEALTHY;
    }
    
    private void checkNotShutdown() {
        if (isShutdown.get()) {
            throw new IllegalStateException("ZooKeeperConnectionManager is already shutdown");
        }
    }
    
    // 内部类定义
    
    public static class ConnectionHealthResult {
        private final HealthStatus status;
        private final long responseTimeMs;
        private final boolean isHealthy;
        private final int activeConnections;
        private final int poolSize;
        private final Instant lastConnectionTime;
        private final Duration connectionLatency;
        private final String errorMessage;
        
        public ConnectionHealthResult(HealthStatus status, long responseTimeMs, boolean isHealthy,
                                    int activeConnections, int poolSize, Instant lastConnectionTime,
                                    Duration connectionLatency, String errorMessage) {
            this.status = status;
            this.responseTimeMs = responseTimeMs;
            this.isHealthy = isHealthy;
            this.activeConnections = activeConnections;
            this.poolSize = poolSize;
            this.lastConnectionTime = lastConnectionTime;
            this.connectionLatency = connectionLatency;
            this.errorMessage = errorMessage;
        }
        
        public HealthStatus getStatus() { return status; }
        public long getResponseTimeMs() { return responseTimeMs; }
        public boolean isHealthy() { return isHealthy; }
        public int getActiveConnections() { return activeConnections; }
        public int getPoolSize() { return poolSize; }
        public Instant getLastConnectionTime() { return lastConnectionTime; }
        public Duration getConnectionLatency() { return connectionLatency; }
        public String getErrorMessage() { return errorMessage; }
    }
    
    public static class ConnectionPoolStatistics {
        private final int poolSize;
        private final int activeConnections;
        private final int availableConnections;
        private final long totalConnectionsCreated;
        private final long totalConnectionsDestroyed;
        private final boolean connected;
        private final Instant lastConnectionTime;
        
        public ConnectionPoolStatistics(int poolSize, int activeConnections, int availableConnections,
                                      long totalConnectionsCreated, long totalConnectionsDestroyed,
                                      boolean connected, Instant lastConnectionTime) {
            this.poolSize = poolSize;
            this.activeConnections = activeConnections;
            this.availableConnections = availableConnections;
            this.totalConnectionsCreated = totalConnectionsCreated;
            this.totalConnectionsDestroyed = totalConnectionsDestroyed;
            this.connected = connected;
            this.lastConnectionTime = lastConnectionTime;
        }
        
        public int getPoolSize() { return poolSize; }
        public int getActiveConnections() { return activeConnections; }
        public int getAvailableConnections() { return availableConnections; }
        public long getTotalConnectionsCreated() { return totalConnectionsCreated; }
        public long getTotalConnectionsDestroyed() { return totalConnectionsDestroyed; }
        public boolean isConnected() { return connected; }
        public Instant getLastConnectionTime() { return lastConnectionTime; }
    }
    
    public static class ZookeeperClusterInfo {
        private final String ensemble;
        private final long sessionId;
        private final ZooKeeper.States state;
        private final int connectionTimeoutMs;
        private final int sessionTimeoutMs;
        
        public ZookeeperClusterInfo(String ensemble, long sessionId, ZooKeeper.States state,
                                  int connectionTimeoutMs, int sessionTimeoutMs) {
            this.ensemble = ensemble;
            this.sessionId = sessionId;
            this.state = state;
            this.connectionTimeoutMs = connectionTimeoutMs;
            this.sessionTimeoutMs = sessionTimeoutMs;
        }
        
        public String getEnsemble() { return ensemble; }
        public long getSessionId() { return sessionId; }
        public ZooKeeper.States getState() { return state; }
        public int getConnectionTimeoutMs() { return connectionTimeoutMs; }
        public int getSessionTimeoutMs() { return sessionTimeoutMs; }
    }
    
    public enum HealthStatus {
        HEALTHY, DEGRADED, UNHEALTHY, UNKNOWN
    }
}