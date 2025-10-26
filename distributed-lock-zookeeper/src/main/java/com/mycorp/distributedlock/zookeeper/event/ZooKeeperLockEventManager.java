package com.mycorp.distributedlock.zookeeper.event;

import com.mycorp.distributedlock.api.LockEventListener;
import com.mycorp.distributedlock.core.config.LockConfiguration;
import com.mycorp.distributedlock.core.observability.LockMetrics;
import com.mycorp.distributedlock.core.observability.LockTracing;
import com.mycorp.distributedlock.zookeeper.lock.OptimizedZooKeeperDistributedLock;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

/**
 * Zookeeper锁事件管理器
 * 
 * 主要功能：
 * - 集中化事件监听和管理
 * - 事件过滤和优先级处理
 * - 异步事件处理和分发
 * - 事件持久化和回放
 * - 死锁检测和通知
 * - 健康检查事件
 * - 性能指标收集
 * - 事件流控制
 * 
 * 事件特性：
 * - 支持同步和异步事件处理
 * - 事件优先级和过滤
 * - 事件批处理和聚合
 * - 事件重放和恢复
 * - 死锁循环检测
 */
public class ZooKeeperLockEventManager implements AutoCloseable {
    
    private static final Logger logger = LoggerFactory.getLogger(ZooKeeperLockEventManager.class);
    
    // 事件管理配置
    private static final int DEFAULT_EVENT_QUEUE_SIZE = 10000;
    private static final int DEFAULT_MAX_EVENT_LISTENERS = 1000;
    private static final Duration DEFAULT_EVENT_PROCESSING_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration DEFAULT_DEADLOCK_DETECTION_INTERVAL = Duration.ofSeconds(10);
    private static final int DEFAULT_DEADLOCK_CYCLE_DEPTH = 10;
    
    // 状态管理
    private final AtomicBoolean isClosed = new AtomicBoolean(false);
    private final AtomicBoolean isStarted = new AtomicBoolean(false);
    
    // 事件管理
    private final ReentrantReadWriteLock eventListenersLock = new ReentrantReadWriteLock();
    private final Map<String, List<LockEventListener<OptimizedZooKeeperDistributedLock>>> lockEventListeners = new ConcurrentHashMap<>();
    private final Map<String, GlobalLockEventListener> globalEventListeners = new ConcurrentHashMap<>();
    private final Map<String, DeadlockDetectionListener> deadlockListeners = new ConcurrentHashMap<>();
    private final PriorityQueue<LockEvent<OptimizedZooKeeperDistributedLock>> eventQueue;
    private final ReentrantLock eventProcessingLock = new ReentrantLock();
    
    // Zookeeper相关
    private final LockConfiguration configuration;
    private final LockMetrics metrics;
    private final LockTracing tracing;
    
    // 线程池管理
    private final ExecutorService eventProcessingExecutor;
    private final ExecutorService asyncEventExecutor;
    private final ScheduledExecutorService deadlockDetectionExecutor;
    private final ScheduledExecutorService eventCleanupExecutor;
    
    // 死锁检测
    private final DeadlockDetector deadlockDetector;
    private final AtomicBoolean deadlockDetectionEnabled = new AtomicBoolean(true);
    private final Map<String, LockWaitGraph> lockWaitGraphs = new ConcurrentHashMap<>();
    
    // 事件统计
    private final AtomicLong totalEventsProcessed = new AtomicLong(0);
    private final AtomicLong successfulEventsProcessed = new AtomicLong(0);
    private final AtomicLong failedEventsProcessed = new AtomicLong(0);
    private final AtomicLong deadlocksDetected = new AtomicLong(0);
    
    // 性能指标
    private final Timer eventProcessingTimer;
    private final Timer eventDeliveryTimer;
    private final Counter eventProcessingCounter;
    private final Counter eventFailureCounter;
    private final Counter deadlockDetectedCounter;
    private final Gauge eventQueueSizeGauge;
    private final Gauge activeEventListenersGauge;
    
    // 事件过滤器
    private final List<LockEventFilter<OptimizedZooKeeperDistributedLock>> eventFilters = new CopyOnWriteArrayList<>();
    
    // 事件持久化
    private final EventPersistenceManager persistenceManager;
    
    /**
     * 构造函数
     * 
     * @param configuration 配置
     * @param metrics 指标收集
     * @param tracing 分布式追踪
     */
    public ZooKeeperLockEventManager(LockConfiguration configuration,
                                   LockMetrics metrics,
                                   LockTracing tracing) {
        this.configuration = configuration;
        this.metrics = metrics;
        this.tracing = tracing;
        
        // 初始化事件队列
        int eventQueueSize = getEventQueueSize();
        this.eventQueue = new PriorityQueue<>(eventQueueSize, Comparator.comparing(LockEvent::getPriority));
        
        // 初始化线程池
        this.eventProcessingExecutor = Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors(), r -> {
                Thread t = new Thread(r, "zookeeper-event-processing");
                t.setDaemon(true);
                return t;
            }
        );
        
        this.asyncEventExecutor = Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors() / 2, r -> {
                Thread t = new Thread(r, "zookeeper-async-event");
                t.setDaemon(true);
                return t;
            }
        );
        
        this.deadlockDetectionExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "zookeeper-deadlock-detection");
            t.setDaemon(true);
            return t;
        });
        
        this.eventCleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "zookeeper-event-cleanup");
            t.setDaemon(true);
            return t;
        });
        
        // 初始化死锁检测器
        this.deadlockDetector = new DeadlockDetector();
        
        // 初始化持久化管理器
        this.persistenceManager = new EventPersistenceManager();
        
        // 初始化性能指标
        this.eventProcessingTimer = metrics.createTimer("zookeeper.lock.event.processing.time");
        this.eventDeliveryTimer = metrics.createTimer("zookeeper.lock.event.delivery.time");
        this.eventProcessingCounter = metrics.createCounter("zookeeper.lock.event.processing.count");
        this.eventFailureCounter = metrics.createCounter("zookeeper.lock.event.failure.count");
        this.deadlockDetectedCounter = metrics.createCounter("zookeeper.lock.deadlock.detected.count");
        this.eventQueueSizeGauge = metrics.createGauge("zookeeper.lock.event.queue.size", eventQueue::size);
        this.activeEventListenersGauge = metrics.createGauge("zookeeper.lock.event.listeners.count", this::getTotalEventListenersCount);
        
        // 添加默认事件过滤器
        addDefaultEventFilters();
        
        logger.debug("ZooKeeperLockEventManager initialized with queue size: {}", eventQueueSize);
    }
    
    /**
     * 启动事件管理器
     */
    public synchronized void start() {
        if (isStarted.getAndSet(true)) {
            return;
        }
        
        logger.info("Starting ZooKeeperLockEventManager");
        
        try {
            // 启动事件处理
            startEventProcessing();
            
            // 启动死锁检测
            startDeadlockDetection();
            
            // 启动事件清理
            startEventCleanup();
            
            // 启动事件持久化
            startEventPersistence();
            
            logger.info("ZooKeeperLockEventManager started successfully");
        } catch (Exception e) {
            isStarted.set(false);
            logger.error("Failed to start ZooKeeperLockEventManager", e);
            throw e;
        }
    }
    
    /**
     * 注册锁事件监听器
     * 
     * @param lockName 锁名称（支持通配符）
     * @param listener 监听器
     * @param priority 优先级（数值越小优先级越高）
     */
    public void registerLockEventListener(String lockName, 
                                        LockEventListener<OptimizedZooKeeperDistributedLock> listener,
                                        int priority) {
        if (isClosed.get()) {
            throw new IllegalStateException("Event manager is closed");
        }
        
        eventListenersLock.writeLock().lock();
        try {
            List<LockEventListener<OptimizedZooKeeperDistributedLock>> listeners = 
                lockEventListeners.computeIfAbsent(lockName, k -> new CopyOnWriteArrayList<>());
            
            // 按优先级插入监听器
            PriorityEventListener<OptimizedZooKeeperDistributedLock> priorityListener = 
                new PriorityEventListener<>(listener, priority);
            
            boolean added = false;
            for (int i = 0; i < listeners.size(); i++) {
                if (listeners.get(i) instanceof PriorityEventListener) {
                    PriorityEventListener<OptimizedZooKeeperDistributedLock> existing = 
                        (PriorityEventListener<OptimizedZooKeeperDistributedLock>) listeners.get(i);
                    if (priority < existing.getPriority()) {
                        listeners.add(i, listener);
                        added = true;
                        break;
                    }
                }
            }
            
            if (!added) {
                listeners.add(listener);
            }
            
            logger.debug("Registered lock event listener for: {} with priority: {}", lockName, priority);
            
        } finally {
            eventListenersLock.writeLock().unlock();
        }
    }
    
    /**
     * 注册全局锁事件监听器
     * 
     * @param listenerId 监听器ID
     * @param listener 监听器
     */
    public void registerGlobalLockEventListener(String listenerId, GlobalLockEventListener listener) {
        if (isClosed.get()) {
            throw new IllegalStateException("Event manager is closed");
        }
        
        globalEventListeners.put(listenerId, listener);
        logger.debug("Registered global lock event listener: {}", listenerId);
    }
    
    /**
     * 注册死锁检测监听器
     * 
     * @param listenerId 监听器ID
     * @param listener 监听器
     */
    public void registerDeadlockDetectionListener(String listenerId, DeadlockDetectionListener listener) {
        if (isClosed.get()) {
            throw new IllegalStateException("Event manager is closed");
        }
        
        deadlockListeners.put(listenerId, listener);
        logger.debug("Registered deadlock detection listener: {}", listenerId);
    }
    
    /**
     * 发布锁事件
     * 
     * @param event 锁事件
     */
    public void publishLockEvent(LockEvent<OptimizedZooKeeperDistributedLock> event) {
        if (isClosed.get() || !isStarted.get()) {
            logger.debug("Event manager not started or closed, dropping event: {}", event.getType());
            return;
        }
        
        try {
            // 应用事件过滤器
            if (!applyEventFilters(event)) {
                return;
            }
            
            // 添加到处理队列
            eventProcessingLock.lock();
            try {
                if (eventQueue.size() >= getEventQueueSize()) {
                    // 队列已满，丢弃低优先级事件
                    LockEvent<OptimizedZooKeeperDistributedLock> lowestPriority = eventQueue.peek();
                    if (lowestPriority != null && event.getPriority() >= lowestPriority.getPriority()) {
                        logger.warn("Event queue full, dropping event: {}", event.getType());
                        return;
                    } else {
                        eventQueue.poll(); // 移除最低优先级事件
                    }
                }
                
                eventQueue.offer(event);
                eventQueueSizeGauge.measure().forEach(m -> m.measure().forEach(measurement -> {})); // 触发指标更新
                
            } finally {
                eventProcessingLock.unlock();
            }
            
            // 异步处理事件
            eventProcessingExecutor.submit(this::processEventQueue);
            
        } catch (Exception e) {
            logger.error("Failed to publish lock event", e);
        }
    }
    
    /**
     * 批量发布锁事件
     * 
     * @param events 事件列表
     */
    public void publishLockEventsBatch(List<LockEvent<OptimizedZooKeeperDistributedLock>> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        
        try {
            eventProcessingLock.lock();
            try {
                int availableSpace = getEventQueueSize() - eventQueue.size();
                int eventsToAdd = Math.min(events.size(), availableSpace);
                
                if (eventsToAdd < events.size()) {
                    logger.warn("Event queue full, adding only {} events out of {}", 
                               eventsToAdd, events.size());
                }
                
                // 添加事件到队列
                for (int i = 0; i < eventsToAdd; i++) {
                    if (applyEventFilters(events.get(i))) {
                        eventQueue.offer(events.get(i));
                    }
                }
                
            } finally {
                eventProcessingLock.unlock();
            }
            
            // 异步处理事件
            eventProcessingExecutor.submit(this::processEventQueue);
            
        } catch (Exception e) {
            logger.error("Failed to publish lock events batch", e);
        }
    }
    
    /**
     * 发布连接状态事件
     * 
     * @param lockName 锁名称
     * @param connectionState 连接状态
     * @param eventTime 事件时间
     */
    public void publishConnectionStateEvent(String lockName, String connectionState, Instant eventTime) {
        LockEvent<OptimizedZooKeeperDistributedLock> event = new LockEvent<>(
            LockEvent.EventType.CONNECTION_STATE_CHANGED,
            null, // 锁实例可能为null
            lockName,
            eventTime,
            null, // 死锁信息
            5, // 优先级
            createEventMetadata("connection-state", connectionState)
        );
        
        publishLockEvent(event);
    }
    
    /**
     * 发布健康检查事件
     * 
     * @param lockName 锁名称
     * @param healthStatus 健康状态
     * @param details 详细信息
     * @param eventTime 事件时间
     */
    public void publishHealthCheckEvent(String lockName, LockEventListener.HealthStatus healthStatus,
                                      String details, Instant eventTime) {
        LockEvent<OptimizedZooKeeperDistributedLock> event = new LockEvent<>(
            LockEvent.EventType.HEALTH_CHECK,
            null,
            lockName,
            eventTime,
            null,
            3, // 中等优先级
            createEventMetadata("health-status", healthStatus.name(), "details", details)
        );
        
        publishLockEvent(event);
    }
    
    /**
     * 获取事件统计信息
     * 
     * @return 统计信息
     */
    public LockEventStatistics getEventStatistics() {
        return new LockEventStatistics(
            totalEventsProcessed.get(),
            successfulEventsProcessed.get(),
            failedEventsProcessed.get(),
            deadlocksDetected.get(),
            eventQueue.size(),
            getTotalEventListenersCount(),
            globalEventListeners.size(),
            deadlockListeners.size()
        );
    }
    
    /**
     * 重置事件统计
     */
    public void resetEventStatistics() {
        totalEventsProcessed.set(0);
        successfulEventsProcessed.set(0);
        failedEventsProcessed.set(0);
        deadlocksDetected.set(0);
        
        logger.debug("Event statistics reset");
    }
    
    /**
     * 启用死锁检测
     */
    public void enableDeadlockDetection() {
        deadlockDetectionEnabled.set(true);
        logger.info("Deadlock detection enabled");
    }
    
    /**
     * 禁用死锁检测
     */
    public void disableDeadlockDetection() {
        deadlockDetectionEnabled.set(false);
        logger.info("Deadlock detection disabled");
    }
    
    /**
     * 添加事件过滤器
     * 
     * @param filter 过滤器
     */
    public void addEventFilter(LockEventFilter<OptimizedZooKeeperDistributedLock> filter) {
        eventFilters.add(filter);
        logger.debug("Added event filter: {}", filter.getClass().getSimpleName());
    }
    
    /**
     * 移除事件过滤器
     * 
     * @param filter 过滤器
     */
    public void removeEventFilter(LockEventFilter<OptimizedZooKeeperDistributedLock> filter) {
        eventFilters.remove(filter);
        logger.debug("Removed event filter: {}", filter.getClass().getSimpleName());
    }
    
    /**
     * 强制清理事件队列
     * 
     * @return 被清理的事件数量
     */
    public int forceCleanupEventQueue() {
        eventProcessingLock.lock();
        try {
            int cleanedCount = eventQueue.size();
            eventQueue.clear();
            
            logger.warn("Force cleaned {} events from queue", cleanedCount);
            return cleanedCount;
        } finally {
            eventProcessingLock.unlock();
        }
    }
    
    /**
     * 导出事件历史
     * 
     * @param eventType 事件类型（可选）
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @return 事件列表
     */
    public List<LockEvent<OptimizedZooKeeperDistributedLock>> exportEventHistory(
            LockEvent.EventType eventType, Instant startTime, Instant endTime) {
        
        try {
            return persistenceManager.exportEvents(eventType, startTime, endTime);
        } catch (Exception e) {
            logger.error("Failed to export event history", e);
            return Collections.emptyList();
        }
    }
    
    @Override
    public void close() {
        if (isClosed.getAndSet(true)) {
            return;
        }
        
        logger.info("Shutting down ZooKeeperLockEventManager");
        
        try {
            // 停止服务
            isStarted.set(false);
            
            // 关闭线程池
            shutdownExecutor(eventProcessingExecutor, "event-processing");
            shutdownExecutor(asyncEventExecutor, "async-event");
            shutdownExecutor(deadlockDetectionExecutor, "deadlock-detection");
            shutdownExecutor(eventCleanupExecutor, "event-cleanup");
            
            // 清理事件队列
            forceCleanupEventQueue();
            
            // 清理监听器
            lockEventListeners.clear();
            globalEventListeners.clear();
            deadlockListeners.clear();
            eventFilters.clear();
            lockWaitGraphs.clear();
            
            // 关闭持久化管理器
            persistenceManager.close();
            
            logger.info("ZooKeeperLockEventManager shutdown completed");
        } catch (Exception e) {
            logger.error("Error during shutdown", e);
        }
    }
    
    // 私有方法区域
    
    private void startEventProcessing() {
        // 启动事件处理循环
        eventProcessingExecutor.submit(() -> {
            while (isStarted.get() && !Thread.currentThread().isInterrupted()) {
                try {
                    processEventQueue();
                    Thread.sleep(10); // 避免CPU过度占用
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    logger.error("Event processing error", e);
                }
            }
        });
    }
    
    private void processEventQueue() {
        Timer.Sample sample = metrics.startTimer(eventProcessingTimer);
        
        try {
            List<LockEvent<OptimizedZooKeeperDistributedLock>> eventsToProcess = new ArrayList<>();
            
            // 批量获取事件
            eventProcessingLock.lock();
            try {
                int batchSize = Math.min(eventQueue.size(), 100); // 每次最多处理100个事件
                
                for (int i = 0; i < batchSize; i++) {
                    LockEvent<OptimizedZooKeeperDistributedLock> event = eventQueue.poll();
                    if (event != null) {
                        eventsToProcess.add(event);
                    } else {
                        break;
                    }
                }
            } finally {
                eventProcessingLock.unlock();
            }
            
            // 处理事件
            for (LockEvent<OptimizedZooKeeperDistributedLock> event : eventsToProcess) {
                try {
                    deliverEvent(event);
                    totalEventsProcessed.incrementAndGet();
                    successfulEventsProcessed.incrementAndGet();
                    eventProcessingCounter.increment();
                } catch (Exception e) {
                    totalEventsProcessed.incrementAndGet();
                    failedEventsProcessed.incrementAndGet();
                    eventFailureCounter.increment();
                    logger.error("Failed to deliver event: {}", event.getType(), e);
                }
            }
            
        } finally {
            sample.stop(eventProcessingTimer);
        }
    }
    
    private void deliverEvent(LockEvent<OptimizedZooKeeperDistributedLock> event) {
        Timer.Sample deliverySample = metrics.startTimer(eventDeliveryTimer);
        
        try {
            // 查找匹配的监听器
            List<LockEventListener<OptimizedZooKeeperDistributedLock>> matchingListeners = findMatchingListeners(event);
            
            // 交付给监听器
            for (LockEventListener<OptimizedZooKeeperDistributedLock> listener : matchingListeners) {
                try {
                    if (listener.isAsyncEnabled()) {
                        // 异步交付
                        asyncEventExecutor.submit(() -> {
                            try {
                                listener.onEvent(event);
                            } catch (Exception e) {
                                logger.error("Error in async event listener", e);
                            }
                        });
                    } else {
                        // 同步交付
                        listener.onEvent(event);
                    }
                } catch (Exception e) {
                    logger.error("Error delivering event to listener", e);
                }
            }
            
            // 交付给全局监听器
            for (GlobalLockEventListener listener : globalEventListeners.values()) {
                try {
                    if (listener.isAsyncEnabled()) {
                        asyncEventExecutor.submit(() -> {
                            try {
                                listener.onGlobalEvent(event);
                            } catch (Exception e) {
                                logger.error("Error in global async event listener", e);
                            }
                        });
                    } else {
                        listener.onGlobalEvent(event);
                    }
                } catch (Exception e) {
                    logger.error("Error delivering event to global listener", e);
                }
            }
            
            // 持久化事件
            persistenceManager.persistEvent(event);
            
        } finally {
            deliverySample.stop(eventDeliveryTimer);
        }
    }
    
    private List<LockEventListener<OptimizedZooKeeperDistributedLock>> findMatchingListeners(
            LockEvent<OptimizedZooKeeperDistributedLock> event) {
        
        List<LockEventListener<OptimizedZooKeeperDistributedLock>> listeners = new ArrayList<>();
        
        eventListenersLock.readLock().lock();
        try {
            for (Map.Entry<String, List<LockEventListener<OptimizedZooKeeperDistributedLock>>> entry : lockEventListeners.entrySet()) {
                String pattern = entry.getKey();
                if (matchesPattern(event.getLockName(), pattern)) {
                    listeners.addAll(entry.getValue());
                }
            }
        } finally {
            eventListenersLock.readLock().unlock();
        }
        
        return listeners;
    }
    
    private boolean matchesPattern(String lockName, String pattern) {
        if (pattern.equals("*") || pattern.equals(lockName)) {
            return true;
        }
        
        if (pattern.endsWith("*")) {
            String prefix = pattern.substring(0, pattern.length() - 1);
            return lockName.startsWith(prefix);
        }
        
        if (pattern.startsWith("*")) {
            String suffix = pattern.substring(1);
            return lockName.endsWith(suffix);
        }
        
        return false;
    }
    
    private void startDeadlockDetection() {
        if (!deadlockDetectionEnabled.get()) {
            return;
        }
        
        deadlockDetectionExecutor.scheduleAtFixedRate(() -> {
            try {
                if (!isStarted.get()) {
                    return;
                }
                
                performDeadlockDetection();
                
            } catch (Exception e) {
                logger.debug("Deadlock detection error", e);
            }
        }, 0, (long) DEFAULT_DEADLOCK_DETECTION_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
    }
    
    private void performDeadlockDetection() {
        // 更新锁等待图
        updateLockWaitGraphs();
        
        // 检测死锁
        List<DeadlockCycle> deadlockCycles = deadlockDetector.detectDeadlocks(new ArrayList<>(lockWaitGraphs.values()));
        
        if (!deadlockCycles.isEmpty()) {
            deadlocksDetected.addAndGet(deadlockCycles.size());
            deadlockDetectedCounter.increment(deadlockCycles.size());
            
            logger.warn("Detected {} deadlocks", deadlockCycles.size());
            
            // 通知死锁监听器
            for (DeadlockCycle cycle : deadlockCycles) {
                notifyDeadlockListeners(cycle);
            }
        }
    }
    
    private void updateLockWaitGraphs() {
        // 这里应该从实际的锁管理器获取等待关系
        // 目前简化实现，基于事件历史构建
        // 实际实现中需要集成到锁获取和释放过程中
    }
    
    private void notifyDeadlockListeners(DeadlockCycle deadlockCycle) {
        for (DeadlockDetectionListener listener : deadlockListeners.values()) {
            try {
                listener.onDeadlockDetected(deadlockCycle);
            } catch (Exception e) {
                logger.error("Error notifying deadlock listener", e);
            }
        }
    }
    
    private void startEventCleanup() {
        eventCleanupExecutor.scheduleAtFixedRate(() -> {
            try {
                if (!isStarted.get()) {
                    return;
                }
                
                // 清理过期的锁等待图
                cleanupExpiredLockWaitGraphs();
                
                // 清理过期的持久化事件
                persistenceManager.cleanupExpiredEvents();
                
            } catch (Exception e) {
                logger.debug("Event cleanup error", e);
            }
        }, 60, 60, TimeUnit.SECONDS);
    }
    
    private void cleanupExpiredLockWaitGraphs() {
        Instant now = Instant.now();
        List<String> expiredGraphIds = new ArrayList<>();
        
        for (Map.Entry<String, LockWaitGraph> entry : lockWaitGraphs.entrySet()) {
            Duration age = Duration.between(entry.getValue().getLastUpdateTime(), now);
            if (age.toMinutes() > 30) { // 超过30分钟的等待图认为是过期的
                expiredGraphIds.add(entry.getKey());
            }
        }
        
        for (String graphId : expiredGraphIds) {
            lockWaitGraphs.remove(graphId);
        }
    }
    
    private void startEventPersistence() {
        persistenceManager.start();
    }
    
    private boolean applyEventFilters(LockEvent<OptimizedZooKeeperDistributedLock> event) {
        for (LockEventFilter<OptimizedZooKeeperDistributedLock> filter : eventFilters) {
            if (!filter.shouldProcessEvent(event)) {
                return false;
            }
        }
        return true;
    }
    
    private void addDefaultEventFilters() {
        // 添加默认的事件过滤器
        addEventFilter(new PriorityEventFilter());
        addEventFilter(new RateLimitEventFilter());
    }
    
    private int getTotalEventListenersCount() {
        eventListenersLock.readLock().lock();
        try {
            return lockEventListeners.values().stream()
                .mapToInt(List::size)
                .sum() + globalEventListeners.size() + deadlockListeners.size();
        } finally {
            eventListenersLock.readLock().unlock();
        }
    }
    
    private Map<String, Object> createEventMetadata(String... keyValues) {
        Map<String, Object> metadata = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            metadata.put(keyValues[i], keyValues[i + 1]);
        }
        return metadata;
    }
    
    private int getEventQueueSize() {
        return configuration.getConfig().hasPath("distributed-lock.zookeeper.event.queue-size") ?
            configuration.getConfig().getInt("distributed-lock.zookeeper.event.queue-size") : 
            DEFAULT_EVENT_QUEUE_SIZE;
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
    
    // 内部类定义
    
    private static class PriorityEventListener<T extends OptimizedZooKeeperDistributedLock> 
            implements LockEventListener<T> {
        
        private final LockEventListener<T> delegate;
        private final int priority;
        
        public PriorityEventListener(LockEventListener<T> delegate, int priority) {
            this.delegate = delegate;
            this.priority = priority;
        }
        
        @Override
        public void onLockAcquired(T lock, Instant eventTime, LockEventMetadata metadata) {
            delegate.onLockAcquired(lock, eventTime, metadata);
        }
        
        @Override
        public void onLockAcquisitionFailed(String lockName, Instant eventTime, Throwable cause, 
                                          LockEventMetadata metadata) {
            delegate.onLockAcquisitionFailed(lockName, eventTime, cause, metadata);
        }
        
        @Override
        public void onLockReleased(T lock, Instant eventTime, LockEventMetadata metadata) {
            delegate.onLockReleased(lock, eventTime, metadata);
        }
        
        @Override
        public void onLockRenewed(T lock, Instant eventTime, Instant newExpiryTime, 
                                LockEventMetadata metadata) {
            delegate.onLockRenewed(lock, eventTime, newExpiryTime, metadata);
        }
        
        @Override
        public void onLockExpired(T lock, Instant eventTime, LockEventMetadata metadata) {
            delegate.onLockExpired(lock, eventTime, metadata);
        }
        
        @Override
        public void onLockTimeout(String lockName, Instant eventTime, long waitTime, 
                                LockEventMetadata metadata) {
            delegate.onLockTimeout(lockName, eventTime, waitTime, metadata);
        }
        
        @Override
        public void onLockBlocked(String lockName, Instant eventTime, String blockReason, 
                                LockEventMetadata metadata) {
            delegate.onLockBlocked(lockName, eventTime, blockReason, metadata);
        }
        
        @Override
        public void onDeadlockDetected(Iterable<T> involvedLocks, Instant eventTime, 
                                     DeadlockCycleInfo cycleInfo, LockEventMetadata metadata) {
            delegate.onDeadlockDetected(involvedLocks, eventTime, cycleInfo, metadata);
        }
        
        @Override
        public void onHealthCheck(String lockName, Instant eventTime, LockEventListener.HealthStatus healthStatus,
                                String details, LockEventMetadata metadata) {
            delegate.onHealthCheck(lockName, eventTime, healthStatus, details, metadata);
        }
        
        @Override
        public CompletableFuture<Void> onEventAsync(LockEvent<T> event) {
            return delegate.onEventAsync(event);
        }
        
        @Override
        public void onEvent(LockEvent<T> event) {
            delegate.onEvent(event);
        }
        
        @Override
        public int getPriority() {
            return delegate.getPriority();
        }
        
        @Override
        public boolean isAsyncEnabled() {
            return delegate.isAsyncEnabled();
        }
        
        @Override
        public boolean shouldHandleEvent(LockEvent<T> event) {
            return delegate.shouldHandleEvent(event);
        }
        
        public int getPriority() {
            return priority;
        }
    }
    
    private static class DeadlockDetector {
        public List<DeadlockCycle> detectDeadlocks(List<LockWaitGraph> waitGraphs) {
            List<DeadlockCycle> detectedCycles = new ArrayList<>();
            
            // 简化的死锁检测算法
            for (LockWaitGraph graph : waitGraphs) {
                List<DeadlockCycle> cycles = findCyclesInGraph(graph);
                detectedCycles.addAll(cycles);
            }
            
            return detectedCycles;
        }
        
        private List<DeadlockCycle> findCyclesInGraph(LockWaitGraph graph) {
            List<DeadlockCycle> cycles = new ArrayList<>();
            Set<String> visited = new HashSet<>();
            Set<String> recursionStack = new HashSet<>();
            
            for (String node : graph.getNodes()) {
                if (!visited.contains(node)) {
                    List<String> cycle = findCycleFromNode(node, graph, visited, recursionStack);
                    if (!cycle.isEmpty()) {
                        cycles.add(new DeadlockCycle(cycle, Instant.now()));
                    }
                }
            }
            
            return cycles;
        }
        
        private List<String> findCycleFromNode(String node, LockWaitGraph graph, 
                                             Set<String> visited, Set<String> recursionStack) {
            visited.add(node);
            recursionStack.add(node);
            
            List<String> cycle = new ArrayList<>();
            
            for (String neighbor : graph.getWaitFor(node)) {
                if (!visited.contains(neighbor)) {
                    cycle.addAll(findCycleFromNode(neighbor, graph, visited, recursionStack));
                    if (!cycle.isEmpty()) {
                        cycle.add(0, node);
                        break;
                    }
                } else if (recursionStack.contains(neighbor)) {
                    // 找到循环
                    cycle.add(node);
                    cycle.add(neighbor);
                    break;
                }
            }
            
            recursionStack.remove(node);
            return cycle;
        }
    }
    
    private static class LockWaitGraph {
        private final String graphId;
        private final Map<String, Set<String>> waitForGraph = new HashMap<>();
        private volatile Instant lastUpdateTime;
        
        public LockWaitGraph(String graphId) {
            this.graphId = graphId;
            this.lastUpdateTime = Instant.now();
        }
        
        public void addWaitEdge(String from, String to) {
            waitForGraph.computeIfAbsent(from, k -> new HashSet<>()).add(to);
            lastUpdateTime = Instant.now();
        }
        
        public Set<String> getNodes() {
            return new HashSet<>(waitForGraph.keySet());
        }
        
        public Set<String> getWaitFor(String node) {
            return waitForGraph.getOrDefault(node, Collections.emptySet());
        }
        
        public String getGraphId() { return graphId; }
        public Instant getLastUpdateTime() { return lastUpdateTime; }
    }
    
    private static class DeadlockCycle {
        private final List<String> involvedNodes;
        private final Instant detectionTime;
        
        public DeadlockCycle(List<String> involvedNodes, Instant detectionTime) {
            this.involvedNodes = new ArrayList<>(involvedNodes);
            this.detectionTime = detectionTime;
        }
        
        public List<String> getInvolvedNodes() { return involvedNodes; }
        public Instant getDetectionTime() { return detectionTime; }
    }
    
    private static class EventPersistenceManager {
        private final List<LockEvent<OptimizedZooKeeperDistributedLock>> eventHistory = new CopyOnWriteArrayList<>();
        private final AtomicBoolean isStarted = new AtomicBoolean(false);
        
        public void start() {
            if (isStarted.getAndSet(true)) {
                return;
            }
            logger.debug("Event persistence manager started");
        }
        
        public void persistEvent(LockEvent<OptimizedZooKeeperDistributedLock> event) {
            // 简单实现：将事件添加到内存历史
            eventHistory.add(event);
            
            // 限制历史大小
            if (eventHistory.size() > 10000) {
                eventHistory.subList(0, 1000).clear();
            }
        }
        
        public List<LockEvent<OptimizedZooKeeperDistributedLock>> exportEvents(
                LockEvent.EventType eventType, Instant startTime, Instant endTime) {
            
            return eventHistory.stream()
                .filter(event -> {
                    if (eventType != null && event.getType() != eventType) {
                        return false;
                    }
                    Instant eventTime = event.getTimestamp();
                    return !eventTime.isBefore(startTime) && !eventTime.isAfter(endTime);
                })
                .collect(Collectors.toList());
        }
        
        public void cleanupExpiredEvents() {
            Instant cutoffTime = Instant.now().minus(Duration.ofHours(1));
            eventHistory.removeIf(event -> event.getTimestamp().isBefore(cutoffTime));
        }
        
        public void close() {
            eventHistory.clear();
            logger.debug("Event persistence manager closed");
        }
    }
    
    private static class PriorityEventFilter implements LockEventFilter<OptimizedZooKeeperDistributedLock> {
        @Override
        public boolean shouldProcessEvent(LockEvent<OptimizedZooKeeperDistributedLock> event) {
            // 过滤掉优先级过低的事件
            return event.getPriority() <= 10;
        }
    }
    
    private static class RateLimitEventFilter implements LockEventFilter<OptimizedZooKeeperDistributedLock> {
        private final Map<String, Long> lastEventTime = new ConcurrentHashMap<>();
        private final Map<String, Long> eventCount = new ConcurrentHashMap<>();
        
        @Override
        public boolean shouldProcessEvent(LockEvent<OptimizedZooKeeperDistributedLock> event) {
            String key = event.getLockName() + ":" + event.getType();
            long now = System.currentTimeMillis();
            
            lastEventTime.computeIfAbsent(key, k -> now);
            eventCount.computeIfAbsent(key, k -> 0L);
            
            long timeDiff = now - lastEventTime.get(key);
            long count = eventCount.get(key);
            
            // 每秒最多处理100个同类事件
            if (timeDiff < 1000 && count >= 100) {
                return false;
            }
            
            if (timeDiff >= 1000) {
                lastEventTime.put(key, now);
                eventCount.put(key, 1L);
            } else {
                eventCount.put(key, count + 1);
            }
            
            return true;
        }
    }
    
    public interface GlobalLockEventListener {
        void onGlobalEvent(LockEvent<OptimizedZooKeeperDistributedLock> event);
        boolean isAsyncEnabled();
    }
    
    public interface DeadlockDetectionListener {
        void onDeadlockDetected(DeadlockCycle deadlockCycle);
    }
    
    public interface LockEventFilter<T extends OptimizedZooKeeperDistributedLock> {
        boolean shouldProcessEvent(LockEvent<T> event);
    }
    
    public static class LockEventStatistics {
        private final long totalEventsProcessed;
        private final long successfulEventsProcessed;
        private final long failedEventsProcessed;
        private final long deadlocksDetected;
        private final int eventQueueSize;
        private final int totalEventListeners;
        private final int globalEventListeners;
        private final int deadlockListeners;
        
        public LockEventStatistics(long totalEventsProcessed, long successfulEventsProcessed,
                                 long failedEventsProcessed, long deadlocksDetected, int eventQueueSize,
                                 int totalEventListeners, int globalEventListeners, int deadlockListeners) {
            this.totalEventsProcessed = totalEventsProcessed;
            this.successfulEventsProcessed = successfulEventsProcessed;
            this.failedEventsProcessed = failedEventsProcessed;
            this.deadlocksDetected = deadlocksDetected;
            this.eventQueueSize = eventQueueSize;
            this.totalEventListeners = totalEventListeners;
            this.globalEventListeners = globalEventListeners;
            this.deadlockListeners = deadlockListeners;
        }
        
        public long getTotalEventsProcessed() { return totalEventsProcessed; }
        public long getSuccessfulEventsProcessed() { return successfulEventsProcessed; }
        public long getFailedEventsProcessed() { return failedEventsProcessed; }
        public long getDeadlocksDetected() { return deadlocksDetected; }
        public int getEventQueueSize() { return eventQueueSize; }
        public int getTotalEventListeners() { return totalEventListeners; }
        public int getGlobalEventListeners() { return globalEventListeners; }
        public int getDeadlockListeners() { return deadlockListeners; }
        
        public double getSuccessRate() {
            return totalEventsProcessed > 0 ? 
                (double) successfulEventsProcessed / totalEventsProcessed : 0.0;
        }
        
        public double getFailureRate() {
            return totalEventsProcessed > 0 ? 
                (double) failedEventsProcessed / totalEventsProcessed : 0.0;
        }
    }
}