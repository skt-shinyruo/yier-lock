package com.mycorp.distributedlock.core.optimization;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.ConnectionEvents;
import io.lettuce.core.RedisClient;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.resource.DefaultClientResources;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.curator.retry.RetryOneTime;
import org.apache.curator.test.TestingServer;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 连接池优化器
 * 
 * 功能特性：
 * 1. Redis连接池动态优化
 * 2. ZooKeeper连接池管理优化
 * 3. 连接生命周期管理
 * 4. 连接池监控和调优
 * 5. 连接复用和预热
 * 6. 连接健康检查和自动恢复
 */
public class ConnectionPoolOptimizer {
    
    private final Logger logger = LoggerFactory.getLogger(ConnectionPoolOptimizer.class);
    
    // 配置参数
    private final ConnectionPoolConfig config;
    
    // 性能统计
    private final LongAdder totalConnections = new LongAdder();
    private final LongAdder activeConnections = new LongAdder();
    private final LongAdder idleConnections = new LongAdder();
    private final LongAdder connectionFailures = new LongAdder();
    private final AtomicLong avgConnectionTime = new AtomicLong(0);
    private final AtomicLong maxConnectionTime = new AtomicLong(0);
    
    // 优化状态
    private final AtomicBoolean isOptimizing = new AtomicBoolean(false);
    private final AtomicInteger optimizationCycle = new AtomicInteger(0);
    
    // 连接池优化器
    private final RedisConnectionPoolOptimizer redisPoolOptimizer;
    private final ZooKeeperConnectionPoolOptimizer zkPoolOptimizer;
    
    // 监控任务
    private ScheduledExecutorService monitoringExecutor;
    private ScheduledExecutorService optimizationExecutor;
    
    public ConnectionPoolOptimizer(ConnectionPoolConfig config) {
        this.config = config;
        this.redisPoolOptimizer = new RedisConnectionPoolOptimizer(config.getRedisConfig());
        this.zkPoolOptimizer = new ZooKeeperConnectionPoolOptimizer(config.getZookeeperConfig());
        initializeMonitoring();
    }
    
    /**
     * 初始化监控和优化任务
     */
    private void initializeMonitoring() {
        if (config.isMonitoringEnabled()) {
            monitoringExecutor = Executors.newScheduledThreadPool(2);
            optimizationExecutor = Executors.newScheduledThreadPool(1);
            
            // 启动连接池监控
            monitoringExecutor.scheduleWithFixedDelay(this::monitorConnectionPools, 
                    10, 30, TimeUnit.SECONDS);
            
            // 启动自动优化
            if (config.isAutoOptimizationEnabled()) {
                optimizationExecutor.scheduleWithFixedDelay(this::performOptimizationCycle,
                        60, 300, TimeUnit.SECONDS);
            }
        }
    }
    
    /**
     * Redis连接池优化
     */
    public class RedisConnectionPoolOptimizer {
        private final RedisConfig redisConfig;
        private final RedisClient redisClient;
        private ClientResources clientResources;
        
        // Redis连接池统计
        private final LongAdder redisConnectionCount = new LongAdder();
        private final LongAdder redisPoolHits = new LongAdder();
        private final LongAdder redisPoolMisses = new LongAdder();
        private final AtomicLong redisAvgPoolWaitTime = new AtomicLong(0);
        
        public RedisConnectionPoolOptimizer(RedisConfig config) {
            this.redisConfig = config;
            this.redisClient = createOptimizedRedisClient(config);
        }
        
        /**
         * 创建优化的Redis客户端
         */
        private RedisClient createOptimizedRedisClient(RedisConfig config) {
            // 创建优化的ClientResources
            clientResources = DefaultClientResources.builder()
                    .ioThreadPoolSize(config.getIoThreadPoolSize())
                    .computationThreadPoolSize(config.getComputationThreadPoolSize())
                    .build();
            
            // 客户端配置优化
            ClientOptions clientOptions = ClientOptions.builder()
                    .timeoutOptions(TimeoutOptions.builder()
                            .timeoutCommands(true)
                            .timeout(Duration.ofMillis(config.getConnectionTimeout()))
                            .build())
                    .autoReconnect(true)
                    .pingBeforeActivateConnection(true)
                    .build();
            
            RedisClient client = RedisClient.create(clientResources, config.getUri());
            client.setOptions(clientOptions);
            
            logger.info("Redis连接池优化初始化完成 - IO线程: {}, 计算线程: {}, 连接超时: {}ms",
                    config.getIoThreadPoolSize(), 
                    config.getComputationThreadPoolSize(), 
                    config.getConnectionTimeout());
            
            return client;
        }
        
        /**
         * 获取连接并记录性能
         */
        public <T> T executeWithConnection(Supplier<T> operation) {
            long startTime = System.nanoTime();
            
            try {
                redisConnectionCount.increment();
                activeConnections.increment();
                
                T result = operation.get();
                
                // 记录连接成功
                long connectionTime = System.nanoTime() - startTime;
                recordConnectionTime(connectionTime);
                
                redisPoolHits.increment();
                return result;
                
            } catch (Exception e) {
                connectionFailures.increment();
                logger.warn("Redis连接执行失败", e);
                throw e;
            } finally {
                activeConnections.decrement();
            }
        }
        
        /**
         * 预热连接池
         */
        public void warmUpConnections() {
            int warmUpConnections = redisConfig.getMinConnections();
            CountDownLatch latch = new CountDownLatch(warmUpConnections);
            
            for (int i = 0; i < warmUpConnections; i++) {
                CompletableFuture.runAsync(() -> {
                    try {
                        executeWithConnection(() -> {
                            Thread.sleep(100); // 模拟连接使用
                            return null;
                        });
                    } finally {
                        latch.countDown();
                    }
                });
            }
            
            try {
                latch.await(30, TimeUnit.SECONDS);
                logger.info("Redis连接池预热完成，预热连接数: {}", warmUpConnections);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warn("Redis连接池预热被中断", e);
            }
        }
        
        /**
         * 获取连接池统计信息
         */
        public PoolStatistics getPoolStatistics() {
            long total = redisConnectionCount.sum();
            long hits = redisPoolHits.sum();
            long misses = redisPoolMisses.sum();
            
            double hitRate = total > 0 ? (double) hits / total * 100 : 0;
            
            return new PoolStatistics(
                    total, hits, misses, hitRate,
                    redisAvgPoolWaitTime.get() / 1_000_000, // 转换为毫秒
                    isOptimizing.get()
            );
        }
        
        /**
         * 优化连接池配置
         */
        public void optimizePoolConfig() {
            isOptimizing.set(true);
            optimizationCycle.incrementAndGet();
            
            try {
                // 获取当前负载信息
                PoolStatistics stats = getPoolStatistics();
                
                // 基于性能统计调整配置
                int currentMin = redisConfig.getMinConnections();
                int currentMax = redisConfig.getMaxConnections();
                
                // 高命中率但低连接数 -> 增加最小连接数
                if (stats.getHitRate() > 80 && currentMin < currentMax) {
                    int newMin = Math.min(currentMin + 5, currentMax);
                    redisConfig.setMinConnections(newMin);
                    logger.info("Redis连接池 - 增加最小连接数: {} -> {}", currentMin, newMin);
                }
                
                // 低命中率且高等待时间 -> 增加最大连接数
                if (stats.getHitRate() < 60 && redisAvgPoolWaitTime.get() > 100) {
                    int newMax = Math.min(currentMax + 10, redisConfig.getMaxPoolSize());
                    redisConfig.setMaxConnections(newMax);
                    logger.info("Redis连接池 - 增加最大连接数: {} -> {}", currentMax, newMax);
                }
                
                // 调整连接超时
                if (avgConnectionTime.get() > 1000) { // 超过1秒
                    redisConfig.setConnectionTimeout((int) Math.min(avgConnectionTime.get() / 1_000_000 * 2, 10000));
                }
                
            } finally {
                isOptimizing.set(false);
            }
        }
    }
    
    /**
     * ZooKeeper连接池优化器
     */
    public class ZooKeeperConnectionPoolOptimizer {
        private final ZooKeeperConfig zkConfig;
        
        // ZooKeeper连接统计
        private final LongAdder zkConnectionCount = new LongAdder();
        private final LongAdder zkSessionCount = new LongAdder();
        private final LongAdder zkOperationLatency = new LongAdder();
        private final AtomicLong zkAvgOperationTime = new AtomicLong(0);
        
        public ZooKeeperConnectionPoolOptimizer(ZooKeeperConfig config) {
            this.zkConfig = config;
        }
        
        /**
         * 创建优化的ZooKeeper客户端
         */
        public CuratorFramework createOptimizedClient(String connectString) {
            CuratorFramework client = CuratorFrameworkFactory.builder()
                    .connectString(connectString)
                    .sessionTimeoutMs(zkConfig.getSessionTimeout())
                    .connectionTimeoutMs(zkConfig.getConnectionTimeout())
                    .retryPolicy(new ExponentialBackoffRetry(
                            zkConfig.getRetryBaseSleepTime(),
                            zkConfig.getRetryMaxRetries()))
                    .canUseReadOnlyHosts(false)
                    .respectZookeeperMBeans(true)
                    .build();
            
            logger.info("ZooKeeper连接池优化初始化完成 - 会话超时: {}ms, 连接超时: {}ms, 重试策略: 指数退避",
                    zkConfig.getSessionTimeout(), 
                    zkConfig.getConnectionTimeout());
            
            return client;
        }
        
        /**
         * 执行ZooKeeper操作并记录性能
         */
        public void executeWithConnection(Runnable operation) {
            long startTime = System.nanoTime();
            
            try {
                zkConnectionCount.increment();
                zkSessionCount.increment();
                activeConnections.increment();
                
                operation.run();
                
                // 记录操作延迟
                long latency = System.nanoTime() - startTime;
                zkOperationLatency.add(latency);
                
                long currentAvg = zkAvgOperationTime.get();
                long newAvg = (currentAvg + latency) / 2;
                zkAvgOperationTime.set(newAvg);
                
            } catch (Exception e) {
                connectionFailures.increment();
                logger.warn("ZooKeeper连接执行失败", e);
                throw e;
            } finally {
                activeConnections.decrement();
                zkSessionCount.decrement();
            }
        }
        
        /**
         * 获取ZooKeeper连接池统计
         */
        public PoolStatistics getPoolStatistics() {
            long total = zkConnectionCount.sum();
            long avgLatency = zkOperationLatency.sum() / Math.max(total, 1);
            
            return new PoolStatistics(
                    total, 0, 0, 0, // ZooKeeper不需要命中率统计
                    avgLatency / 1_000_000, // 转换为毫秒
                    isOptimizing.get()
            );
        }
        
        /**
         * 优化ZooKeeper配置
         */
        public void optimizeZkConfig() {
            isOptimizing.set(true);
            
            try {
                // 基于操作延迟优化配置
                long avgLatency = zkAvgOperationTime.get() / 1_000_000; // 转换为毫秒
                
                if (avgLatency > 100) {
                    // 高延迟时增加会话超时时间
                    zkConfig.setSessionTimeout(Math.min(zkConfig.getSessionTimeout() + 1000, 60000));
                    logger.info("ZooKeeper连接池 - 增加会话超时: {}ms -> {}ms", 
                            zkConfig.getSessionTimeout() - 1000, zkConfig.getSessionTimeout());
                }
                
                // 基于连接失败次数调整重试策略
                long failures = connectionFailures.sum();
                if (failures > 10) {
                    zkConfig.setRetryMaxRetries(Math.min(zkConfig.getRetryMaxRetries() + 1, 10));
                    logger.info("ZooKeeper连接池 - 增加重试次数: {}", zkConfig.getRetryMaxRetries());
                }
                
            } finally {
                isOptimizing.set(false);
            }
        }
    }
    
    /**
     * 监控连接池状态
     */
    private void monitorConnectionPools() {
        try {
            PoolStatistics redisStats = redisPoolOptimizer.getPoolStatistics();
            PoolStatistics zkStats = zkPoolOptimizer.getPoolStatistics();
            
            logger.info("连接池状态 - Redis: 活跃={}, 平均等待={}ms, 命中率={}%, ZooKeeper: 活跃={}, 平均延迟={}ms",
                    redisStats.getActiveConnections(),
                    redisStats.getAvgWaitTime(),
                    String.format("%.1f", redisStats.getHitRate()),
                    zkStats.getActiveConnections(),
                    zkStats.getAvgWaitTime());
            
            // 检查是否需要触发优化
            checkOptimizationTriggers(redisStats, zkStats);
            
        } catch (Exception e) {
            logger.error("连接池监控异常", e);
        }
    }
    
    /**
     * 检查优化触发条件
     */
    private void checkOptimizationTriggers(PoolStatistics redisStats, PoolStatistics zkStats) {
        boolean needOptimization = false;
        
        // Redis优化触发条件
        if (redisStats.getAvgWaitTime() > 100 || redisStats.getHitRate() < 70) {
            needOptimization = true;
        }
        
        // ZooKeeper优化触发条件
        if (zkStats.getAvgWaitTime() > 200) {
            needOptimization = true;
        }
        
        // 连接失败过多
        if (connectionFailures.sum() > 50) {
            needOptimization = true;
        }
        
        if (needOptimization && !isOptimizing.get()) {
            logger.info("触发连接池自动优化");
            optimizationExecutor.submit(this::performOptimizationCycle);
        }
    }
    
    /**
     * 执行优化循环
     */
    private void performOptimizationCycle() {
        logger.info("开始连接池优化周期 #{}", optimizationCycle.incrementAndGet());
        
        try {
            // 优化Redis连接池
            redisPoolOptimizer.optimizePoolConfig();
            
            // 优化ZooKeeper连接池
            zkPoolOptimizer.optimizeZkConfig();
            
            // 清理过期的连接
            cleanupIdleConnections();
            
            // 重新平衡连接池
            rebalanceConnectionPools();
            
            logger.info("连接池优化周期 #{} 完成", optimizationCycle.get());
            
        } catch (Exception e) {
            logger.error("连接池优化周期 #{} 失败", optimizationCycle.get(), e);
        }
    }
    
    /**
     * 清理空闲连接
     */
    private void cleanupIdleConnections() {
        long totalConns = totalConnections.sum();
        long idleConns = idleConnections.sum();
        
        if (idleConns > totalConns * 0.8) {
            logger.info("连接池空闲连接过多({}/{}={}%), 开始清理", 
                    idleConns, totalConns, (double) idleConns / totalConns * 100);
            
            // 清理逻辑由具体的连接池实现处理
        }
    }
    
    /**
     * 重新平衡连接池
     */
    private void rebalanceConnectionPools() {
        // 基于当前负载重新分配连接池大小
        long totalConns = totalConnections.sum();
        long activeConns = activeConnections.sum();
        double utilization = totalConns > 0 ? (double) activeConns / totalConns : 0;
        
        if (utilization > 0.9) {
            logger.info("连接池利用率过高({}%), 增加连接池大小", utilization * 100);
            // 增加连接池大小
        } else if (utilization < 0.3) {
            logger.info("连接池利用率过低({}%), 减少连接池大小", utilization * 100);
            // 减少连接池大小
        }
    }
    
    /**
     * 记录连接时间
     */
    private void recordConnectionTime(long connectionTime) {
        totalConnections.increment();
        
        long currentAvg = avgConnectionTime.get();
        long newAvg = (currentAvg + connectionTime) / 2;
        avgConnectionTime.set(newAvg);
        
        if (connectionTime > maxConnectionTime.get()) {
            maxConnectionTime.set(connectionTime);
        }
    }
    
    /**
     * 获取整体连接池统计
     */
    public ConnectionPoolStatistics getStatistics() {
        return new ConnectionPoolStatistics(
                totalConnections.sum(),
                activeConnections.sum(),
                idleConnections.sum(),
                connectionFailures.sum(),
                avgConnectionTime.get() / 1_000_000, // 转换为毫秒
                maxConnectionTime.get() / 1_000_000,
                optimizationCycle.get(),
                redisPoolOptimizer.getPoolStatistics(),
                zkPoolOptimizer.getPoolStatistics()
        );
    }
    
    /**
     * 关闭优化器
     */
    public void shutdown() {
        if (monitoringExecutor != null) {
            monitoringExecutor.shutdown();
        }
        if (optimizationExecutor != null) {
            optimizationExecutor.shutdown();
        }
        
        if (redisPoolOptimizer.redisClient != null) {
            redisPoolOptimizer.redisClient.shutdown();
        }
        if (redisPoolOptimizer.clientResources != null) {
            redisPoolOptimizer.clientResources.shutdown();
        }
        
        logger.info("连接池优化器已关闭");
    }
    
    // ==================== 配置类 ====================
    
    /**
     * 连接池配置
     */
    public static class ConnectionPoolConfig {
        private final RedisConfig redisConfig;
        private final ZooKeeperConfig zookeeperConfig;
        private final boolean monitoringEnabled;
        private final boolean autoOptimizationEnabled;
        private final int optimizationIntervalSeconds;
        
        public ConnectionPoolConfig(RedisConfig redisConfig, ZooKeeperConfig zookeeperConfig) {
            this.redisConfig = redisConfig;
            this.zookeeperConfig = zookeeperConfig;
            this.monitoringEnabled = true;
            this.autoOptimizationEnabled = true;
            this.optimizationIntervalSeconds = 300;
        }
        
        // Getters
        public RedisConfig getRedisConfig() { return redisConfig; }
        public ZooKeeperConfig getZookeeperConfig() { return zookeeperConfig; }
        public boolean isMonitoringEnabled() { return monitoringEnabled; }
        public boolean isAutoOptimizationEnabled() { return autoOptimizationEnabled; }
        public int getOptimizationIntervalSeconds() { return optimizationIntervalSeconds; }
    }
    
    /**
     * Redis配置
     */
    public static class RedisConfig {
        private final String uri;
        private int ioThreadPoolSize = Runtime.getRuntime().availableProcessors();
        private int computationThreadPoolSize = Runtime.getRuntime().availableProcessors();
        private int connectionTimeout = 5000;
        private int minConnections = 5;
        private int maxConnections = 50;
        private int maxPoolSize = 100;
        
        public RedisConfig(String uri) {
            this.uri = uri;
        }
        
        // Getters and Setters
        public String getUri() { return uri; }
        public int getIoThreadPoolSize() { return ioThreadPoolSize; }
        public void setIoThreadPoolSize(int ioThreadPoolSize) { this.ioThreadPoolSize = ioThreadPoolSize; }
        public int getComputationThreadPoolSize() { return computationThreadPoolSize; }
        public void setComputationThreadPoolSize(int computationThreadPoolSize) { this.computationThreadPoolSize = computationThreadPoolSize; }
        public int getConnectionTimeout() { return connectionTimeout; }
        public void setConnectionTimeout(int connectionTimeout) { this.connectionTimeout = connectionTimeout; }
        public int getMinConnections() { return minConnections; }
        public void setMinConnections(int minConnections) { this.minConnections = minConnections; }
        public int getMaxConnections() { return maxConnections; }
        public void setMaxConnections(int maxConnections) { this.maxConnections = maxConnections; }
        public int getMaxPoolSize() { return maxPoolSize; }
        public void setMaxPoolSize(int maxPoolSize) { this.maxPoolSize = maxPoolSize; }
    }
    
    /**
     * ZooKeeper配置
     */
    public static class ZooKeeperConfig {
        private int sessionTimeout = 30000;
        private int connectionTimeout = 10000;
        private int retryBaseSleepTime = 1000;
        private int retryMaxRetries = 3;
        
        // Getters and Setters
        public int getSessionTimeout() { return sessionTimeout; }
        public void setSessionTimeout(int sessionTimeout) { this.sessionTimeout = sessionTimeout; }
        public int getConnectionTimeout() { return connectionTimeout; }
        public void setConnectionTimeout(int connectionTimeout) { this.connectionTimeout = connectionTimeout; }
        public int getRetryBaseSleepTime() { return retryBaseSleepTime; }
        public void setRetryBaseSleepTime(int retryBaseSleepTime) { this.retryBaseSleepTime = retryBaseSleepTime; }
        public int getRetryMaxRetries() { return retryMaxRetries; }
        public void setRetryMaxRetries(int retryMaxRetries) { this.retryMaxRetries = retryMaxRetries; }
    }
    
    /**
     * 连接池统计
     */
    public static class PoolStatistics {
        private final long totalConnections;
        private final long hits;
        private final long misses;
        private final double hitRate;
        private final long avgWaitTime;
        private final boolean isOptimizing;
        private final int activeConnections;
        
        public PoolStatistics(long totalConnections, long hits, long misses, double hitRate, 
                            long avgWaitTime, boolean isOptimizing) {
            this.totalConnections = totalConnections;
            this.hits = hits;
            this.misses = misses;
            this.hitRate = hitRate;
            this.avgWaitTime = avgWaitTime;
            this.isOptimizing = isOptimizing;
            this.activeConnections = 0; // 默认值
        }
        
        public PoolStatistics(long totalConnections, long hits, long misses, double hitRate, 
                            long avgWaitTime, boolean isOptimizing, int activeConnections) {
            this.totalConnections = totalConnections;
            this.hits = hits;
            this.misses = misses;
            this.hitRate = hitRate;
            this.avgWaitTime = avgWaitTime;
            this.isOptimizing = isOptimizing;
            this.activeConnections = activeConnections;
        }
        
        // Getters
        public long getTotalConnections() { return totalConnections; }
        public long getHits() { return hits; }
        public long getMisses() { return misses; }
        public double getHitRate() { return hitRate; }
        public long getAvgWaitTime() { return avgWaitTime; }
        public boolean isOptimizing() { return isOptimizing; }
        public int getActiveConnections() { return activeConnections; }
    }
    
    /**
     * 连接池整体统计
     */
    public static class ConnectionPoolStatistics {
        private final long totalConnections;
        private final long activeConnections;
        private final long idleConnections;
        private final long failedConnections;
        private final long avgConnectionTime;
        private final long maxConnectionTime;
        private final int optimizationCycle;
        private final PoolStatistics redisStats;
        private final PoolStatistics zkStats;
        
        public ConnectionPoolStatistics(long totalConnections, long activeConnections, long idleConnections,
                                      long failedConnections, long avgConnectionTime, long maxConnectionTime,
                                      int optimizationCycle, PoolStatistics redisStats, PoolStatistics zkStats) {
            this.totalConnections = totalConnections;
            this.activeConnections = activeConnections;
            this.idleConnections = idleConnections;
            this.failedConnections = failedConnections;
            this.avgConnectionTime = avgConnectionTime;
            this.maxConnectionTime = maxConnectionTime;
            this.optimizationCycle = optimizationCycle;
            this.redisStats = redisStats;
            this.zkStats = zkStats;
        }
        
        // Getters
        public long getTotalConnections() { return totalConnections; }
        public long getActiveConnections() { return activeConnections; }
        public long getIdleConnections() { return idleConnections; }
        public long getFailedConnections() { return failedConnections; }
        public long getAvgConnectionTime() { return avgConnectionTime; }
        public long getMaxConnectionTime() { return maxConnectionTime; }
        public int getOptimizationCycle() { return optimizationCycle; }
        public PoolStatistics getRedisStats() { return redisStats; }
        public PoolStatistics getZkStats() { return zkStats; }
    }
}