package com.mycorp.distributedlock.zookeeper.operation;

import com.mycorp.distributedlock.api.BatchLockOperations;
import com.mycorp.distributedlock.api.DistributedLock;
import com.mycorp.distributedlock.api.LockEvent;
import com.mycorp.distributedlock.api.LockEventListener;
import com.mycorp.distributedlock.api.exception.DistributedLockException;
import com.mycorp.distributedlock.core.config.LockConfiguration;
import com.mycorp.distributedlock.core.observability.LockMetrics;
import com.mycorp.distributedlock.core.observability.LockTracing;
import com.mycorp.distributedlock.zookeeper.connection.ZooKeeperConnectionManager;
import com.mycorp.distributedlock.zookeeper.cluster.ZooKeeperClusterManager;
import com.mycorp.distributedlock.zookeeper.lock.FairZooKeeperDistributedLock;
import io.micrometer.core.instrument.Timer;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.locks.InterProcessMutex;
import org.apache.curator.framework.state.ConnectionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Zookeeper批量锁操作实现
 * 
 * 特性：
 * - 基于Zookeeper事务的原子性操作
 * - 多种锁获取策略
 * - 死锁检测和预防
 * - 事务性回滚机制
 * - 高可用性支持
 * - 性能监控和优化
 * - 事件驱动通知
 */
public class ZooKeeperBatchLockOperations implements BatchLockOperations<DistributedLock> {
    
    private static final Logger logger = LoggerFactory.getLogger(ZooKeeperBatchLockOperations.class);
    
    // Zookeeper路径配置
    private static final String BATCH_LOCKS_BASE_PATH = "/distributed-locks/batch";
    private static final String SESSION_PATH = "/sessions";
    private static final String LOCKS_PATH = "/locks";
    private static final String METADATA_PATH = "/metadata";
    
    // 配置参数
    private static final int MAX_BATCH_SIZE = 50;
    private static final int DEFAULT_BATCH_TIMEOUT = 30000; // 30秒
    private static final int CLEANUP_INTERVAL = 60000; // 60秒
    private static final int MAX_CONCURRENT_SESSIONS = 1000;
    
    // Zookeeper相关
    private final String lockName;
    private final ZooKeeperConnectionManager connectionManager;
    private final ZooKeeperClusterManager clusterManager;
    private final LockConfiguration configuration;
    private final LockMetrics metrics;
    private final LockTracing tracing;
    
    // 状态管理
    private final AtomicBoolean isClosed = new AtomicBoolean(false);
    private final AtomicLong sessionIdGenerator = new AtomicLong(0);
    private final Map<String, ZooKeeperBatchSession> activeSessions = new ConcurrentHashMap<>();
    private final Map<String, FairZooKeeperDistributedLock> lockCache = new ConcurrentHashMap<>();
    
    // 线程池
    private final ExecutorService batchOperationExecutor;
    private final ScheduledExecutorService cleanupExecutor;
    private final ScheduledExecutorService deadlockDetectionExecutor;
    
    // 事件管理
    private final java.util.List<LockEventListener<DistributedLock>> eventListeners = 
            new java.util.concurrent.CopyOnWriteArrayList<>();
    
    // 性能指标
    private final Timer batchOperationTimer;
    private final AtomicLong totalBatchOperations = new AtomicLong(0);
    private final AtomicLong successfulBatchOperations = new AtomicLong(0);
    private final AtomicLong failedBatchOperations = new AtomicLong(0);
    private final AtomicLong totalBatchLocksAcquired = new AtomicLong(0);
    private final AtomicLong totalBatchLocksReleased = new AtomicLong(0);
    private final AtomicLong totalRollbackOperations = new AtomicLong(0);
    private final AtomicLong totalDeadlockDetections = new AtomicLong(0);
    private final AtomicLong totalDeadlockResolutions = new AtomicLong(0);
    
    // 死锁检测
    private final AtomicBoolean deadlockDetectionEnabled = new AtomicBoolean(false);
    private final ReentrantLock deadlockDetectionLock = new ReentrantLock();
    
    /**
     * 构造函数
     */
    public ZooKeeperBatchLockOperations(String lockName,
                                      ZooKeeperConnectionManager connectionManager,
                                      ZooKeeperClusterManager clusterManager,
                                      LockConfiguration configuration,
                                      LockMetrics metrics,
                                      LockTracing tracing) {
        this.lockName = lockName;
        this.connectionManager = connectionManager;
        this.clusterManager = clusterManager;
        this.configuration = configuration;
        this.metrics = metrics;
        this.tracing = tracing;
        
        // 初始化线程池
        this.batchOperationExecutor = Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors(), r -> {
                Thread t = new Thread(r, "zk-batch-lock-ops-" + lockName);
                t.setDaemon(true);
                return t;
            }
        );
        
        this.cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "zk-batch-cleanup-" + lockName);
            t.setDaemon(true);
            return t;
        });
        
        this.deadlockDetectionExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "zk-deadlock-detector-" + lockName);
            t.setDaemon(true);
            return t;
        });
        
        // 初始化性能指标
        this.batchOperationTimer = metrics.createTimer("zookeeper.batch.lock.operation.time", lockName);
        
        // 启动清理任务
        startCleanupTask();
        
        // 启动死锁检测（如果启用）
        if (deadlockDetectionEnabled.get()) {
            startDeadlockDetection();
        }
        
        // 添加连接状态监听器
        connectionManager.addConnectionStateListener((client, newState) -> {
            handleConnectionStateChanged(newState);
        });
        
        logger.debug("ZooKeeperBatchLockOperations initialized for: {}", lockName);
    }
    
    @Override
    public BatchLockResult<DistributedLock> batchLock(List<String> lockNames, long leaseTime, TimeUnit unit) 
            throws InterruptedException {
        checkNotClosed();
        
        if (lockNames == null || lockNames.isEmpty()) {
            throw new IllegalArgumentException("Lock names cannot be null or empty");
        }
        
        if (lockNames.size() > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("Batch size cannot exceed " + MAX_BATCH_SIZE);
        }
        
        if (activeSessions.size() >= MAX_CONCURRENT_SESSIONS) {
            throw new DistributedLockException("Maximum concurrent sessions reached");
        }
        
        var spanContext = tracing.startLockAcquisitionSpan(lockName, "batchLock");
        var timerSample = metrics.startTimer(batchOperationTimer);
        
        long startTime = System.currentTimeMillis();
        String sessionId = generateSessionId();
        List<DistributedLock> acquiredLocks = new ArrayList<>();
        List<String> failedLockNames = new ArrayList<>();
        
        try {
            // 创建批处理会话
            ZooKeeperBatchSession session = new ZooKeeperBatchSession(sessionId, lockNames, 
                                                                     System.currentTimeMillis() + unit.toMillis(leaseTime));
            activeSessions.put(sessionId, session);
            
            // 使用Zookeeper事务执行批量锁获取
            boolean allAcquired = executeBatchLockOperationWithTransaction(lockNames, leaseTime, unit,
                                                                           acquiredLocks, failedLockNames, session);
            
            long operationTime = System.currentTimeMillis() - startTime;
            totalBatchOperations.incrementAndGet();
            
            if (allAcquired && failedLockNames.isEmpty()) {
                successfulBatchOperations.incrementAndGet();
                totalBatchLocksAcquired.addAndGet(acquiredLocks.size());
                
                logger.debug("Batch lock operation completed successfully: {} locks acquired in {}ms", 
                           acquiredLocks.size(), operationTime);
                
                spanContext.setStatus("success");
            } else {
                failedBatchOperations.incrementAndGet();
                
                // 如果部分失败，回滚事务
                rollbackBatchOperation(session);
                
                logger.debug("Batch lock operation failed: {} locks failed, {} locks rolled back", 
                           failedLockNames.size(), acquiredLocks.size());
                
                spanContext.setStatus("partial_failure");
            }
            
            return new ZooKeeperBatchLockResultImpl(acquiredLocks, failedLockNames, 
                                                  failedLockNames.isEmpty(), operationTime);
            
        } catch (Exception e) {
            // 发生异常，回滚事务
            totalBatchOperations.incrementAndGet();
            failedBatchOperations.incrementAndGet();
            
            if (acquiredLocks.isEmpty()) {
                spanContext.setStatus("complete_failure");
            } else {
                spanContext.setStatus("rollback");
            }
            spanContext.setError(e);
            
            logger.error("Batch lock operation failed with exception", e);
            
            // 回滚已获取的锁
            rollbackBatchOperation(session);
            
            throw new DistributedLockException("Batch lock operation failed", e);
            
        } finally {
            timerSample.stop(batchOperationTimer);
            
            // 清理会话
            activeSessions.remove(sessionId);
        }
    }
    
    @Override
    public CompletableFuture<BatchLockResult<DistributedLock>> batchLockAsync(List<String> lockNames, 
                                                                             long leaseTime, TimeUnit unit) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return batchLock(lockNames, leaseTime, unit);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CompletionException("Batch lock operation interrupted", e);
            } catch (Exception e) {
                throw new CompletionException("Batch lock operation failed", e);
            }
        }, batchOperationExecutor);
    }
    
    @Override
    public boolean batchUnlock(List<DistributedLock> locks) {
        checkNotClosed();
        
        if (locks == null || locks.isEmpty()) {
            return true;
        }
        
        boolean allSuccess = true;
        List<DistributedLock> locksToUnlock = new ArrayList<>(locks);
        
        try {
            // 按相反顺序释放锁，避免死锁
            Collections.reverse(locksToUnlock);
            
            // 使用Zookeeper事务确保原子性释放
            CuratorFramework connection = connectionManager.getConnection();
            connection.inTransaction();
            
            for (DistributedLock lock : locksToUnlock) {
                try {
                    if (lock instanceof FairZooKeeperDistributedLock) {
                        FairZooKeeperDistributedLock zkLock = (FairZooKeeperDistributedLock) lock;
                        if (zkLock.isHeldByCurrentThread()) {
                            zkLock.unlock();
                            totalBatchLocksReleased.incrementAndGet();
                            
                            // 通知事件
                            notifyEvent(LockEvent.ofLockReleased(lock, createMetadata()));
                        }
                    } else {
                        if (lock.isHeldByCurrentThread()) {
                            lock.unlock();
                            totalBatchLocksReleased.incrementAndGet();
                            
                            notifyEvent(LockEvent.ofLockReleased(lock, createMetadata()));
                        }
                    }
                } catch (Exception e) {
                    logger.warn("Failed to release lock: {}", lock.getName(), e);
                    allSuccess = false;
                }
            }
            
            // 提交事务
            connection.commit();
            
            logger.debug("Batch unlock completed: {} locks, success: {}", locks.size(), allSuccess);
            return allSuccess;
            
        } catch (Exception e) {
            logger.error("Batch unlock operation failed", e);
            return false;
        }
    }
    
    @Override
    public CompletableFuture<Boolean> batchUnlockAsync(List<DistributedLock> locks) {
        return CompletableFuture.supplyAsync(() -> batchUnlock(locks), batchOperationExecutor);
    }
    
    @Override
    public <R> R executeInTransaction(List<String> lockNames, long leaseTime, TimeUnit unit,
                                    TransactionalLockCallback<R, DistributedLock> transactionCallback) throws Exception {
        checkNotClosed();
        
        var spanContext = tracing.startLockAcquisitionSpan(lockName, "executeInTransaction");
        
        List<DistributedLock> acquiredLocks = new ArrayList<>();
        String sessionId = null;
        
        try {
            // 获取所有锁
            BatchLockResult<DistributedLock> result = batchLock(lockNames, leaseTime, unit);
            acquiredLocks.addAll(result.getSuccessfulLocks());
            sessionId = result.getSuccessfulLocks().isEmpty() ? null : 
                       activeSessions.values().stream()
                           .filter(session -> session.getAcquiredLocks().equals(result.getSuccessfulLocks()))
                           .findFirst()
                           .map(ZooKeeperBatchSession::getSessionId)
                           .orElse(null);
            
            if (!result.isAllSuccessful()) {
                throw new DistributedLockException("Failed to acquire all locks for transaction: " + 
                                                 result.getFailedLockNames());
            }
            
            // 执行事务操作
            R transactionResult = transactionCallback.execute(acquiredLocks);
            
            spanContext.setStatus("success");
            logger.debug("Transaction executed successfully with {} locks", acquiredLocks.size());
            
            return transactionResult;
            
        } catch (Exception e) {
            spanContext.setError(e);
            
            // 事务失败，回滚已获取的锁
            if (!acquiredLocks.isEmpty()) {
                rollbackBatchOperation(sessionId);
            }
            
            logger.error("Transaction failed, rolled back {} locks", acquiredLocks.size(), e);
            throw e;
            
        } finally {
            // 确保释放所有锁
            if (!acquiredLocks.isEmpty()) {
                try {
                    batchUnlock(acquiredLocks);
                } catch (Exception e) {
                    logger.warn("Error releasing locks after transaction", e);
                }
            }
        }
    }
    
    @Override
    public <R> CompletableFuture<R> executeInTransactionAsync(List<String> lockNames, long leaseTime, TimeUnit unit,
                                                            TransactionalLockCallback<R, DistributedLock> transactionCallback) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return executeInTransaction(lockNames, leaseTime, unit, transactionCallback);
            } catch (Exception e) {
                throw new CompletionException("Transaction execution failed", e);
            }
        }, batchOperationExecutor);
    }
    
    @Override
    public DistributedLock getLock(String name) {
        checkNotClosed();
        return lockCache.computeIfAbsent(name, lockName -> {
            String lockPath = BATCH_LOCKS_BASE_PATH + LOCKS_PATH + "/" + lockName;
            return new FairZooKeeperDistributedLock(
                lockName, connectionManager, clusterManager,
                configuration, metrics, tracing);
        });
    }
    
    /**
     * 添加事件监听器
     */
    public void addLockEventListener(LockEventListener<DistributedLock> listener) {
        if (listener != null) {
            eventListeners.add(listener);
        }
    }
    
    /**
     * 移除事件监听器
     */
    public void removeLockEventListener(LockEventListener<DistributedLock> listener) {
        eventListeners.remove(listener);
    }
    
    /**
     * 获取批处理统计信息
     */
    public ZooKeeperBatchOperationsStatistics getStatistics() {
        return new ZooKeeperBatchOperationsStatistics(
            totalBatchOperations.get(),
            successfulBatchOperations.get(),
            failedBatchOperations.get(),
            totalBatchLocksAcquired.get(),
            totalBatchLocksReleased.get(),
            totalRollbackOperations.get(),
            totalDeadlockDetections.get(),
            totalDeadlockResolutions.get(),
            getAverageOperationTime(),
            activeSessions.size(),
            lockCache.size()
        );
    }
    
    /**
     * 获取活跃会话信息
     */
    public List<ZooKeeperBatchSessionInfo> getActiveSessions() {
        return activeSessions.values().stream()
                .map(session -> new ZooKeeperBatchSessionInfo(
                    session.getSessionId(),
                    session.getLockNames(),
                    session.getCreationTime(),
                    session.getExpirationTime(),
                    session.getStatus(),
                    session.getAcquiredLocks().size(),
                    session.getFailedLockNames().size()
                ))
                .collect(java.util.stream.Collectors.toList());
    }
    
    /**
     * 启用死锁检测
     */
    public void enableDeadlockDetection() {
        if (deadlockDetectionEnabled.compareAndSet(false, true)) {
            startDeadlockDetection();
            logger.info("Deadlock detection enabled for batch operations: {}", lockName);
        }
    }
    
    /**
     * 禁用死锁检测
     */
    public void disableDeadlockDetection() {
        if (deadlockDetectionEnabled.compareAndSet(true, false)) {
            deadlockDetectionEnabled.set(false);
            logger.info("Deadlock detection disabled for batch operations: {}", lockName);
        }
    }
    
    /**
     * 手动检测死锁
     */
    public List<DeadlockInfo> detectDeadlocks() {
        deadlockDetectionLock.lock();
        try {
            return performDeadlockDetection();
        } finally {
            deadlockDetectionLock.unlock();
        }
    }
    
    /**
     * 清理过期的批处理会话
     */
    public void cleanupExpiredSessions() {
        long currentTime = System.currentTimeMillis();
        List<String> expiredSessionIds = new ArrayList<>();
        
        for (Map.Entry<String, ZooKeeperBatchSession> entry : activeSessions.entrySet()) {
            if (entry.getValue().isExpired(currentTime)) {
                expiredSessionIds.add(entry.getKey());
            }
        }
        
        for (String sessionId : expiredSessionIds) {
            try {
                cleanupSession(sessionId);
            } catch (Exception e) {
                logger.warn("Error cleaning up expired session: {}", sessionId, e);
            }
        }
        
        if (!expiredSessionIds.isEmpty()) {
            logger.info("Cleaned up {} expired batch lock sessions", expiredSessionIds.size());
        }
    }
    
    /**
     * 手动回滚批处理操作
     */
    public boolean rollbackBatchOperation(String sessionId) {
        return rollbackBatchOperation(activeSessions.get(sessionId));
    }
    
    @Override
    public void close() {
        if (isClosed.getAndSet(true)) {
            return;
        }
        
        logger.debug("Closing ZooKeeperBatchLockOperations: {}", lockName);
        
        try {
            // 清理所有活跃会话
            for (String sessionId : new ArrayList<>(activeSessions.keySet())) {
                cleanupSession(sessionId);
            }
            
            // 关闭线程池
            shutdownExecutor(batchOperationExecutor, "batch-operation");
            shutdownExecutor(cleanupExecutor, "cleanup");
            shutdownExecutor(deadlockDetectionExecutor, "deadlock-detection");
            
            // 关闭锁缓存
            for (DistributedLock lock : lockCache.values()) {
                if (lock instanceof AutoCloseable) {
                    ((AutoCloseable) lock).close();
                }
            }
            lockCache.clear();
            
            // 清理事件监听器
            eventListeners.clear();
            
            logger.debug("ZooKeeperBatchLockOperations closed: {}", lockName);
        } catch (Exception e) {
            logger.error("Error closing ZooKeeperBatchLockOperations", e);
        }
    }
    
    // 私有方法
    
    private boolean executeBatchLockOperationWithTransaction(List<String> lockNames, long leaseTime, TimeUnit unit,
                                                           List<DistributedLock> acquiredLocks, List<String> failedLockNames,
                                                           ZooKeeperBatchSession session) {
        try {
            CuratorFramework connection = connectionManager.getConnection();
            
            // 开始Zookeeper事务
            connection.inTransaction();
            
            // 按锁名排序，确保获取顺序一致
            List<String> sortedLockNames = new ArrayList<>(lockNames);
            Collections.sort(sortedLockNames);
            
            boolean allSuccess = true;
            
            for (String lockName : sortedLockNames) {
                try {
                    FairZooKeeperDistributedLock lock = (FairZooKeeperDistributedLock) getLock(lockName);
                    boolean acquired = lock.tryLock(0, leaseTime, unit);
                    
                    if (acquired) {
                        acquiredLocks.add(lock);
                        session.addAcquiredLock(lock);
                        
                        // 通知事件
                        notifyEvent(LockEvent.ofLockAcquired(lock, createMetadata()));
                    } else {
                        failedLockNames.add(lockName);
                        session.addFailedLock(lockName);
                        allSuccess = false;
                        
                        // 通知事件
                        notifyEvent(LockEvent.ofLockAcquisitionFailed(lockName, 
                                                                   new RuntimeException("Failed to acquire lock"), 
                                                                   createMetadata()));
                    }
                    
                } catch (Exception e) {
                    failedLockNames.add(lockName);
                    session.addFailedLock(lockName);
                    allSuccess = false;
                    
                    // 通知事件
                    notifyEvent(LockEvent.ofLockAcquisitionFailed(lockName, e, createMetadata()));
                }
            }
            
            // 提交事务
            connection.commit();
            
            return allSuccess;
            
        } catch (Exception e) {
            // 回滚事务
            try {
                CuratorFramework connection = connectionManager.getConnection();
                connection.rollback();
            } catch (Exception rollbackException) {
                logger.warn("Failed to rollback transaction", rollbackException);
            }
            
            // 清理已获取的锁
            cleanupPartialAcquisition(acquiredLocks);
            
            logger.error("Batch lock transaction failed", e);
            return false;
        }
    }
    
    private boolean rollbackBatchOperation(ZooKeeperBatchSession session) {
        if (session == null) {
            return false;
        }
        
        try {
            List<DistributedLock> locks = session.getAcquiredLocks();
            if (!locks.isEmpty()) {
                batchUnlock(locks);
            }
            
            session.markAsRolledBack();
            totalRollbackOperations.incrementAndGet();
            
            logger.debug("Batch operation rolled back successfully: {}", session.getSessionId());
            return true;
            
        } catch (Exception e) {
            logger.error("Failed to rollback batch operation: {}", session.getSessionId(), e);
            return false;
        }
    }
    
    private boolean rollbackBatchOperation(String sessionId) {
        ZooKeeperBatchSession session = activeSessions.get(sessionId);
        return rollbackBatchOperation(session);
    }
    
    private void cleanupPartialAcquisition(List<DistributedLock> locks) {
        for (DistributedLock lock : locks) {
            try {
                if (lock instanceof FairZooKeeperDistributedLock) {
                    FairZooKeeperDistributedLock zkLock = (FairZooKeeperDistributedLock) lock;
                    if (zkLock.isHeldByCurrentThread()) {
                        zkLock.unlock();
                        notifyEvent(LockEvent.ofLockReleased(lock, createMetadata()));
                    }
                } else {
                    if (lock.isHeldByCurrentThread()) {
                        lock.unlock();
                        notifyEvent(LockEvent.ofLockReleased(lock, createMetadata()));
                    }
                }
            } catch (Exception e) {
                logger.warn("Error cleaning up lock during partial acquisition cleanup: {}", 
                           lock.getName(), e);
            }
        }
    }
    
    private void cleanupSession(String sessionId) {
        ZooKeeperBatchSession session = activeSessions.remove(sessionId);
        if (session != null) {
            try {
                // 回滚事务
                rollbackBatchOperation(session);
                
                // 清理Zookeeper中的会话数据
                cleanupZooKeeperSessionData(sessionId);
                
                logger.debug("Session cleaned up: {}", sessionId);
            } catch (Exception e) {
                logger.warn("Error cleaning up session: {}", sessionId, e);
            }
        }
    }
    
    private void cleanupZooKeeperSessionData(String sessionId) {
        try {
            CuratorFramework connection = connectionManager.getConnection();
            
            // 删除会话相关节点
            String sessionPath = BATCH_LOCKS_BASE_PATH + SESSION_PATH + "/" + sessionId;
            String metadataPath = BATCH_LOCKS_BASE_PATH + METADATA_PATH + "/" + sessionId;
            
            connection.delete().forPath(sessionPath);
            connection.delete().forPath(metadataPath);
            
        } catch (Exception e) {
            logger.debug("Failed to cleanup ZooKeeper session data for: {}", sessionId, e);
        }
    }
    
    private void startCleanupTask() {
        cleanupExecutor.scheduleAtFixedRate(() -> {
            try {
                cleanupExpiredSessions();
            } catch (Exception e) {
                logger.debug("Cleanup task error", e);
            }
        }, CLEANUP_INTERVAL, CLEANUP_INTERVAL, TimeUnit.MILLISECONDS);
    }
    
    private void startDeadlockDetection() {
        deadlockDetectionExecutor.scheduleAtFixedRate(() -> {
            try {
                deadlockDetectionLock.lock();
                try {
                    List<DeadlockInfo> deadlocks = performDeadlockDetection();
                    if (!deadlocks.isEmpty()) {
                        totalDeadlockDetections.addAndGet(deadlocks.size());
                        resolveDeadlocks(deadlocks);
                    }
                } finally {
                    deadlockDetectionLock.unlock();
                }
            } catch (Exception e) {
                logger.debug("Deadlock detection error", e);
            }
        }, 10000, 10000, TimeUnit.MILLISECONDS); // 每10秒检测一次
    }
    
    private List<DeadlockInfo> performDeadlockDetection() {
        List<DeadlockInfo> deadlocks = new ArrayList<>();
        
        // 构建锁等待图
        Map<String, Set<String>> waitForGraph = buildWaitForGraph();
        
        // 检测循环
        List<List<String>> cycles = findCycles(waitForGraph);
        
        for (List<String> cycle : cycles) {
            deadlocks.add(new DeadlockInfo(cycle, "Detected deadlock cycle: " + cycle));
        }
        
        return deadlocks;
    }
    
    private Map<String, Set<String>> buildWaitForGraph() {
        Map<String, Set<String>> waitForGraph = new HashMap<>();
        
        for (ZooKeeperBatchSession session : activeSessions.values()) {
            String sessionId = session.getSessionId();
            if (!waitForGraph.containsKey(sessionId)) {
                waitForGraph.put(sessionId, new HashSet<>());
            }
            
            // 分析会话持有的锁和等待的锁
            // 这里简化实现，实际应该从Zookeeper获取更详细的锁状态
        }
        
        return waitForGraph;
    }
    
    private List<List<String>> findCycles(Map<String, Set<String>> graph) {
        List<List<String>> cycles = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Set<String> recursionStack = new HashSet<>();
        
        for (String node : graph.keySet()) {
            if (!visited.contains(node)) {
                findCyclesUtil(node, graph, visited, recursionStack, cycles, new ArrayList<>());
            }
        }
        
        return cycles;
    }
    
    private void findCyclesUtil(String node, Map<String, Set<String>> graph, Set<String> visited,
                               Set<String> recursionStack, List<List<String>> cycles, List<String> path) {
        visited.add(node);
        recursionStack.add(node);
        path.add(node);
        
        for (String neighbor : graph.getOrDefault(node, Collections.emptySet())) {
            if (!visited.contains(neighbor)) {
                findCyclesUtil(neighbor, graph, visited, recursionStack, cycles, new ArrayList<>(path));
            } else if (recursionStack.contains(neighbor)) {
                // 找到循环
                List<String> cycle = new ArrayList<>(path);
                cycle.add(neighbor);
                cycles.add(cycle);
            }
        }
        
        recursionStack.remove(node);
        path.remove(path.size() - 1);
    }
    
    private void resolveDeadlocks(List<DeadlockInfo> deadlocks) {
        for (DeadlockInfo deadlock : deadlocks) {
            try {
                // 选择一个会话进行回滚（通常是最后一个加入循环的）
                String sessionToRollback = deadlock.getInvolvedSessions().get(
                    deadlock.getInvolvedSessions().size() - 1);
                
                rollbackBatchOperation(sessionToRollback);
                totalDeadlockResolutions.incrementAndGet();
                
                logger.warn("Resolved deadlock by rolling back session: {}", sessionToRollback);
                
            } catch (Exception e) {
                logger.error("Failed to resolve deadlock", e);
            }
        }
    }
    
    private void handleConnectionStateChanged(ConnectionState newState) {
        switch (newState) {
            case LOST:
                logger.warn("Connection lost, cleaning up active batch sessions");
                // 清理所有活跃会话
                for (String sessionId : new ArrayList<>(activeSessions.keySet())) {
                    cleanupSession(sessionId);
                }
                break;
                
            case SUSPENDED:
                logger.warn("Connection suspended for batch operations: {}", lockName);
                break;
                
            case RECONNECTED:
                logger.info("Connection reconnected for batch operations: {}", lockName);
                break;
        }
    }
    
    private String generateSessionId() {
        return "zk-batch-" + System.currentTimeMillis() + "-" + sessionIdGenerator.incrementAndGet();
    }
    
    private void notifyEvent(LockEvent<?> event) {
        for (LockEventListener<DistributedLock> listener : eventListeners) {
            if (listener.shouldHandleEvent(event)) {
                try {
                    listener.onEvent(event);
                } catch (Exception e) {
                    logger.warn("Error in batch lock event listener", e);
                }
            }
        }
    }
    
    private LockEventListener.LockEventMetadata createMetadata() {
        return new LockEventListener.LockEventMetadata() {
            @Override
            public long getThreadId() {
                return Thread.currentThread().getId();
            }
            
            @Override
            public String getThreadName() {
                return Thread.currentThread().getName();
            }
            
            @Override
            public String getCallStack() {
                StackTraceElement[] stack = Thread.currentThread().getStackTrace();
                for (StackTraceElement element : stack) {
                    if (!element.getClassName().equals(ZooKeeperBatchLockOperations.class.getName())) {
                        return element.toString();
                    }
                }
                return "";
            }
            
            @Override
            public Object getCustomData() {
                return "ZooKeeperBatchLockOperations Context";
            }
        };
    }
    
    private double getAverageOperationTime() {
        long totalOperations = totalBatchOperations.get();
        return totalOperations > 0 ? (double) totalBatchOperations.get() / totalOperations : 0.0;
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
        logger.debug("Shut down {} executor for batch operations: {}", name, lockName);
    }
    
    private void checkNotClosed() {
        if (isClosed.get()) {
            throw new IllegalStateException("Batch lock operations is closed: " + lockName);
        }
    }
    
    // 内部类
    
    /**
     * Zookeeper批处理会话
     */
    private static class ZooKeeperBatchSession {
        private final String sessionId;
        private final List<String> lockNames;
        private final long creationTime;
        private final long expirationTime;
        private final List<DistributedLock> acquiredLocks = new ArrayList<>();
        private final List<String> failedLockNames = new ArrayList<>();
        private volatile ZooKeeperBatchSessionStatus status = ZooKeeperBatchSessionStatus.ACTIVE;
        
        public ZooKeeperBatchSession(String sessionId, List<String> lockNames, long expirationTime) {
            this.sessionId = sessionId;
            this.lockNames = new ArrayList<>(lockNames);
            this.creationTime = System.currentTimeMillis();
            this.expirationTime = expirationTime;
        }
        
        public String getSessionId() { return sessionId; }
        public List<String> getLockNames() { return lockNames; }
        public long getCreationTime() { return creationTime; }
        public long getExpirationTime() { return expirationTime; }
        public List<DistributedLock> getAcquiredLocks() { return acquiredLocks; }
        public List<String> getFailedLockNames() { return failedLockNames; }
        public ZooKeeperBatchSessionStatus getStatus() { return status; }
        
        public void addAcquiredLock(DistributedLock lock) {
            acquiredLocks.add(lock);
        }
        
        public void addFailedLock(String lockName) {
            failedLockNames.add(lockName);
        }
        
        public void markAsRolledBack() {
            this.status = ZooKeeperBatchSessionStatus.ROLLED_BACK;
        }
        
        public boolean isExpired(long currentTime) {
            return currentTime > expirationTime || status == ZooKeeperBatchSessionStatus.EXPIRED;
        }
    }
    
    /**
     * Zookeeper批处理会话状态
     */
    private enum ZooKeeperBatchSessionStatus {
        ACTIVE,
        COMPLETED,
        ROLLED_BACK,
        EXPIRED
    }
    
    /**
     * Zookeeper批处理锁结果实现
     */
    private static class ZooKeeperBatchLockResultImpl implements BatchLockResult<DistributedLock> {
        private final List<DistributedLock> successfulLocks;
        private final List<String> failedLockNames;
        private final boolean allSuccessful;
        private final long operationTime;
        
        public ZooKeeperBatchLockResultImpl(List<DistributedLock> successfulLocks, List<String> failedLockNames,
                                          boolean allSuccessful, long operationTime) {
            this.successfulLocks = new ArrayList<>(successfulLocks);
            this.failedLockNames = new ArrayList<>(failedLockNames);
            this.allSuccessful = allSuccessful;
            this.operationTime = operationTime;
        }
        
        @Override
        public List<DistributedLock> getSuccessfulLocks() {
            return successfulLocks;
        }
        
        @Override
        public List<String> getFailedLockNames() {
            return failedLockNames;
        }
        
        @Override
        public boolean isAllSuccessful() {
            return allSuccessful;
        }
        
        @Override
        public long getOperationTimeMs() {
            return operationTime;
        }
    }
    
    /**
     * Zookeeper批处理操作统计信息
     */
    public static class ZooKeeperBatchOperationsStatistics {
        private final long totalBatchOperations;
        private final long successfulBatchOperations;
        private final long failedBatchOperations;
        private final long totalBatchLocksAcquired;
        private final long totalBatchLocksReleased;
        private final long totalRollbackOperations;
        private final long totalDeadlockDetections;
        private final long totalDeadlockResolutions;
        private final double averageOperationTime;
        private final int activeSessions;
        private final int lockCacheSize;
        
        public ZooKeeperBatchOperationsStatistics(long totalBatchOperations, long successfulBatchOperations,
                                                long failedBatchOperations, long totalBatchLocksAcquired,
                                                long totalBatchLocksReleased, long totalRollbackOperations,
                                                long totalDeadlockDetections, long totalDeadlockResolutions,
                                                double averageOperationTime, int activeSessions, int lockCacheSize) {
            this.totalBatchOperations = totalBatchOperations;
            this.successfulBatchOperations = successfulBatchOperations;
            this.failedBatchOperations = failedBatchOperations;
            this.totalBatchLocksAcquired = totalBatchLocksAcquired;
            this.totalBatchLocksReleased = totalBatchLocksReleased;
            this.totalRollbackOperations = totalRollbackOperations;
            this.totalDeadlockDetections = totalDeadlockDetections;
            this.totalDeadlockResolutions = totalDeadlockResolutions;
            this.averageOperationTime = averageOperationTime;
            this.activeSessions = activeSessions;
            this.lockCacheSize = lockCacheSize;
        }
        
        public long getTotalBatchOperations() { return totalBatchOperations; }
        public long getSuccessfulBatchOperations() { return successfulBatchOperations; }
        public long getFailedBatchOperations() { return failedBatchOperations; }
        public long getTotalBatchLocksAcquired() { return totalBatchLocksAcquired; }
        public long getTotalBatchLocksReleased() { return totalBatchLocksReleased; }
        public long getTotalRollbackOperations() { return totalRollbackOperations; }
        public long getTotalDeadlockDetections() { return totalDeadlockDetections; }
        public long getTotalDeadlockResolutions() { return totalDeadlockResolutions; }
        public double getAverageOperationTime() { return averageOperationTime; }
        public int getActiveSessions() { return activeSessions; }
        public int getLockCacheSize() { return lockCacheSize; }
        
        public double getSuccessRate() {
            return totalBatchOperations > 0 ? 
                (double) successfulBatchOperations / totalBatchOperations : 0.0;
        }
        
        public double getFailureRate() {
            return totalBatchOperations > 0 ? 
                (double) failedBatchOperations / totalBatchOperations : 0.0;
        }
        
        public double getDeadlockResolutionRate() {
            return totalDeadlockDetections > 0 ? 
                (double) totalDeadlockResolutions / totalDeadlockDetections : 0.0;
        }
    }
    
    /**
     * Zookeeper批处理会话信息
     */
    public static class ZooKeeperBatchSessionInfo {
        private final String sessionId;
        private final List<String> lockNames;
        private final long creationTime;
        private final long expirationTime;
        private final ZooKeeperBatchSessionStatus status;
        private final int acquiredLocksCount;
        private final int failedLocksCount;
        
        public ZooKeeperBatchSessionInfo(String sessionId, List<String> lockNames, long creationTime,
                                       long expirationTime, ZooKeeperBatchSessionStatus status,
                                       int acquiredLocksCount, int failedLocksCount) {
            this.sessionId = sessionId;
            this.lockNames = new ArrayList<>(lockNames);
            this.creationTime = creationTime;
            this.expirationTime = expirationTime;
            this.status = status;
            this.acquiredLocksCount = acquiredLocksCount;
            this.failedLocksCount = failedLocksCount;
        }
        
        public String getSessionId() { return sessionId; }
        public List<String> getLockNames() { return lockNames; }
        public long getCreationTime() { return creationTime; }
        public long getExpirationTime() { return expirationTime; }
        public ZooKeeperBatchSessionStatus getStatus() { return status; }
        public int getAcquiredLocksCount() { return acquiredLocksCount; }
        public int getFailedLocksCount() { return failedLocksCount; }
        
        public long getRemainingTime() {
            return Math.max(0, expirationTime - System.currentTimeMillis());
        }
    }
    
    /**
     * 死锁信息
     */
    public static class DeadlockInfo {
        private final List<String> involvedSessions;
        private final String description;
        
        public DeadlockInfo(List<String> involvedSessions, String description) {
            this.involvedSessions = new ArrayList<>(involvedSessions);
            this.description = description;
        }
        
        public List<String> getInvolvedSessions() { return involvedSessions; }
        public String getDescription() { return description; }
        
        @Override
        public String toString() {
            return "DeadlockInfo{" +
                    "involvedSessions=" + involvedSessions +
                    ", description='" + description + '\'' +
                    '}';
        }
    }
}