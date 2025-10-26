package com.mycorp.distributedlock.core.event;

import com.mycorp.distributedlock.api.DistributedLock;
import com.mycorp.distributedlock.api.LockEvent;
import com.mycorp.distributedlock.api.LockEventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 锁事件管理器
 * 
 * 功能：
 * - 统一的事件管理和分发
 * - 事件过滤和路由
 * - 异步事件处理
 * - 事件统计和监控
 * - 死锁检测集成
 */
public class LockEventManager {
    
    private static final Logger logger = LoggerFactory.getLogger(LockEventManager.class);
    
    // 事件配置
    private static final int MAX_EVENT_QUEUE_SIZE = 10000;
    private static final long EVENT_CLEANUP_INTERVAL = 60000; // 60秒
    private static final int MAX_EVENT_HISTORY_SIZE = 1000;
    
    // 状态管理
    private final AtomicBoolean isClosed = new AtomicBoolean(false);
    private final AtomicLong eventIdGenerator = new AtomicLong(0);
    
    // 线程池
    private final ExecutorService eventProcessingExecutor;
    private final ScheduledExecutorService eventCleanupExecutor;
    
    // 事件管理
    private final Map<LockEventListener<?>, EventListenerConfig> eventListeners = new ConcurrentHashMap<>();
    private final BlockingQueue<LockEvent<?>> eventQueue = new LinkedBlockingQueue<>();
    private final Map<String, LockEvent<?>> eventHistory = new ConcurrentHashMap<>();
    
    // 锁管理
    private final ReentrantLock eventProcessingLock = new ReentrantLock();
    private final ReentrantLock historyLock = new ReentrantLock();
    
    // 事件统计
    private final AtomicLong totalEventsProcessed = new AtomicLong(0);
    private final AtomicLong totalEventsDiscarded = new AtomicLong(0);
    private final AtomicLong totalEventProcessingTime = new AtomicLong(0);
    private final Map<LockEventListener.EventType, AtomicLong> eventsByType = 
            new ConcurrentHashMap<>();
    
    // 事件处理器
    private final List<EventProcessor> eventProcessors = new CopyOnWriteArrayList<>();
    
    // 死锁检测器
    private final DeadlockDetector deadlockDetector;
    private final AtomicBoolean deadlockDetectionEnabled = new AtomicBoolean(false);
    
    public LockEventManager() {
        this(new DeadlockDetector());
    }
    
    public LockEventManager(DeadlockDetector deadlockDetector) {
        this.deadlockDetector = deadlockDetector;
        
        // 初始化线程池
        this.eventProcessingExecutor = Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors(), r -> {
                Thread t = new Thread(r, "lock-event-processor");
                t.setDaemon(true);
                return t;
            }
        );
        
        this.eventCleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "lock-event-cleanup");
            t.setDaemon(true);
            return t;
        });
        
        // 启动事件处理
        startEventProcessing();
        
        // 启动事件清理
        startEventCleanup();
        
        logger.debug("LockEventManager initialized");
    }
    
    /**
     * 注册事件监听器
     */
    public <T extends DistributedLock> void addEventListener(LockEventListener<T> listener) {
        addEventListener(listener, EventListenerConfig.DEFAULT);
    }
    
    /**
     * 注册事件监听器（带配置）
     */
    public <T extends DistributedLock> void addEventListener(LockEventListener<T> listener, 
                                                            EventListenerConfig config) {
        if (listener == null) {
            throw new IllegalArgumentException("Event listener cannot be null");
        }
        
        eventListeners.put(listener, config != null ? config : EventListenerConfig.DEFAULT);
        
        // 如果启用了死锁检测，添加死锁监听器
        if (deadlockDetectionEnabled.get() && listener instanceof DeadlockAwareEventListener) {
            deadlockDetector.addDeadlockListener((DeadlockAwareEventListener) listener);
        }
        
        logger.debug("Event listener added: {}", listener.getClass().getSimpleName());
    }
    
    /**
     * 移除事件监听器
     */
    public void removeEventListener(LockEventListener<?> listener) {
        EventListenerConfig config = eventListeners.remove(listener);
        if (config != null) {
            // 从死锁检测器中移除
            if (listener instanceof DeadlockAwareEventListener) {
                deadlockDetector.removeDeadlockListener((DeadlockAwareEventListener) listener);
            }
            
            logger.debug("Event listener removed: {}", listener.getClass().getSimpleName());
        }
    }
    
    /**
     * 发布事件
     */
    public void publishEvent(LockEvent<?> event) {
        if (isClosed.get()) {
            logger.warn("Attempting to publish event while manager is closed");
            return;
        }
        
        if (event == null) {
            return;
        }
        
        try {
            // 检查队列大小
            if (eventQueue.size() >= MAX_EVENT_QUEUE_SIZE) {
                totalEventsDiscarded.incrementAndGet();
                logger.warn("Event queue is full, discarding event: {}", event.getType());
                return;
            }
            
            // 设置事件ID
            setEventId(event);
            
            // 添加到处理队列
            if (!eventQueue.offer(event)) {
                totalEventsDiscarded.incrementAndGet();
                logger.warn("Failed to queue event: {}", event.getType());
                return;
            }
            
            // 更新统计
            eventsByType.computeIfAbsent(event.getType(), k -> new AtomicLong(0)).incrementAndGet();
            
            logger.debug("Event queued: {} for lock {}", event.getType(), event.getLockName());
            
        } catch (Exception e) {
            totalEventsDiscarded.incrementAndGet();
            logger.error("Error publishing event", e);
        }
    }
    
    /**
     * 批量发布事件
     */
    public void publishEvents(List<LockEvent<?>> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        
        for (LockEvent<?> event : events) {
            publishEvent(event);
        }
    }
    
    /**
     * 添加事件处理器
     */
    public void addEventProcessor(EventProcessor processor) {
        if (processor != null) {
            eventProcessors.add(processor);
            logger.debug("Event processor added: {}", processor.getClass().getSimpleName());
        }
    }
    
    /**
     * 移除事件处理器
     */
    public void removeEventProcessor(EventProcessor processor) {
        eventProcessors.remove(processor);
        logger.debug("Event processor removed: {}", processor.getClass().getSimpleName());
    }
    
    /**
     * 启用死锁检测
     */
    public void enableDeadlockDetection() {
        if (deadlockDetectionEnabled.compareAndSet(false, true)) {
            deadlockDetector.start();
            logger.info("Deadlock detection enabled");
        }
    }
    
    /**
     * 禁用死锁检测
     */
    public void disableDeadlockDetection() {
        if (deadlockDetectionEnabled.compareAndSet(true, false)) {
            deadlockDetector.stop();
            logger.info("Deadlock detection disabled");
        }
    }
    
    /**
     * 手动检测死锁
     */
    public List<DeadlockInfo> detectDeadlocks() {
        return deadlockDetector.detectDeadlocks();
    }
    
    /**
     * 获取事件统计信息
     */
    public EventStatistics getStatistics() {
        return new EventStatistics(
            totalEventsProcessed.get(),
            totalEventsDiscarded.get(),
            totalEventProcessingTime.get(),
            eventQueue.size(),
            eventHistory.size(),
            eventListeners.size(),
            eventProcessors.size(),
            getEventsByTypeMap(),
            getAverageProcessingTime(),
            isDeadlockDetectionEnabled()
        );
    }
    
    /**
     * 获取事件历史
     */
    public List<LockEvent<?>> getEventHistory() {
        historyLock.lock();
        try {
            return eventHistory.values().stream()
                    .sorted(Comparator.comparing(LockEvent::getTimestamp).reversed())
                    .limit(100)
                    .collect(Collectors.toList());
        } finally {
            historyLock.unlock();
        }
    }
    
    /**
     * 清理过期事件
     */
    public void cleanupExpiredEvents() {
        long cutoffTime = System.currentTimeMillis() - 300000; // 5分钟前
        
        historyLock.lock();
        try {
            List<String> expiredKeys = new ArrayList<>();
            
            for (Map.Entry<String, LockEvent<?>> entry : eventHistory.entrySet()) {
                if (entry.getValue().getTimestamp().toEpochMilli() < cutoffTime) {
                    expiredKeys.add(entry.getKey());
                }
            }
            
            for (String key : expiredKeys) {
                eventHistory.remove(key);
            }
            
            if (!expiredKeys.isEmpty()) {
                logger.debug("Cleaned up {} expired events", expiredKeys.size());
            }
            
        } finally {
            historyLock.unlock();
        }
    }
    
    /**
     * 重置统计信息
     */
    public void resetStatistics() {
        totalEventsProcessed.set(0);
        totalEventsDiscarded.set(0);
        totalEventProcessingTime.set(0);
        eventsByType.clear();
        
        logger.debug("Event statistics reset");
    }
    
    /**
     * 检查死锁检测是否启用
     */
    public boolean isDeadlockDetectionEnabled() {
        return deadlockDetectionEnabled.get();
    }
    
    /**
     * 获取死锁检测器
     */
    public DeadlockDetector getDeadlockDetector() {
        return deadlockDetector;
    }
    
    @Override
    public void close() {
        if (isClosed.getAndSet(true)) {
            return;
        }
        
        logger.debug("Closing LockEventManager");
        
        try {
            // 停止死锁检测器
            deadlockDetector.stop();
            
            // 关闭线程池
            eventProcessingExecutor.shutdown();
            eventCleanupExecutor.shutdown();
            
            if (!eventProcessingExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                eventProcessingExecutor.shutdownNow();
            }
            if (!eventCleanupExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                eventCleanupExecutor.shutdownNow();
            }
            
            // 清理事件监听器
            eventListeners.clear();
            
            // 清理事件处理器
            eventProcessors.clear();
            
            // 清空事件队列
            eventQueue.clear();
            
            logger.debug("LockEventManager closed");
        } catch (Exception e) {
            logger.error("Error closing LockEventManager", e);
        }
    }
    
    // 私有方法
    
    private void setEventId(LockEvent<?> event) {
        try {
            long eventId = eventIdGenerator.incrementAndGet();
            // 这里可以通过反射设置事件ID（如果LockEvent有ID字段）
            // 或者扩展LockEvent接口来支持ID
        } catch (Exception e) {
            logger.debug("Could not set event ID", e);
        }
    }
    
    private void startEventProcessing() {
        // 启动事件处理线程
        for (int i = 0; i < Runtime.getRuntime().availableProcessors(); i++) {
            eventProcessingExecutor.submit(this::processEvents);
        }
    }
    
    private void processEvents() {
        while (!isClosed.get()) {
            try {
                LockEvent<?> event = eventQueue.poll(1, TimeUnit.SECONDS);
                if (event != null) {
                    processEvent(event);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logger.error("Error processing event", e);
            }
        }
    }
    
    private void processEvent(LockEvent<?> event) {
        long startTime = System.currentTimeMillis();
        
        try {
            eventProcessingLock.lock();
            
            // 添加到历史记录
            addToHistory(event);
            
            // 通知事件处理器
            for (EventProcessor processor : eventProcessors) {
                try {
                    processor.processEvent(event);
                } catch (Exception e) {
                    logger.warn("Error in event processor", e);
                }
            }
            
            // 通知监听器
            notifyListeners(event);
            
            // 统计处理时间
            long processingTime = System.currentTimeMillis() - startTime;
            totalEventProcessingTime.addAndGet(processingTime);
            totalEventsProcessed.incrementAndGet();
            
            logger.debug("Event processed in {}ms: {}", processingTime, event.getType());
            
        } finally {
            eventProcessingLock.unlock();
        }
    }
    
    private void notifyListeners(LockEvent<?> event) {
        for (Map.Entry<LockEventListener<?>, EventListenerConfig> entry : eventListeners.entrySet()) {
            LockEventListener<?> listener = entry.getKey();
            EventListenerConfig config = entry.getValue();
            
            try {
                // 检查是否应该处理此事件
                if (config.isEnabled() && listener.shouldHandleEvent(event)) {
                    
                    // 检查事件类型过滤
                    if (config.getAllowedEventTypes().isEmpty() || 
                        config.getAllowedEventTypes().contains(event.getType())) {
                        
                        if (config.isAsyncEnabled() && listener.isAsyncEnabled()) {
                            // 异步处理
                            CompletableFuture.runAsync(() -> {
                                try {
                                    listener.onEvent(event);
                                } catch (Exception e) {
                                    logger.warn("Error in async event listener", e);
                                }
                            }, eventProcessingExecutor);
                        } else {
                            // 同步处理
                            listener.onEvent(event);
                        }
                    }
                }
            } catch (Exception e) {
                logger.warn("Error notifying event listener", e);
            }
        }
    }
    
    private void addToHistory(LockEvent<?> event) {
        historyLock.lock();
        try {
            String eventKey = generateEventKey(event);
            eventHistory.put(eventKey, event);
            
            // 限制历史记录大小
            if (eventHistory.size() > MAX_EVENT_HISTORY_SIZE) {
                String oldestKey = eventHistory.keySet().iterator().next();
                eventHistory.remove(oldestKey);
            }
        } finally {
            historyLock.unlock();
        }
    }
    
    private String generateEventKey(LockEvent<?> event) {
        return event.getLockName() + "-" + event.getTimestamp().toEpochMilli() + "-" + 
               eventIdGenerator.get();
    }
    
    private void startEventCleanup() {
        eventCleanupExecutor.scheduleAtFixedRate(() -> {
            try {
                cleanupExpiredEvents();
            } catch (Exception e) {
                logger.debug("Event cleanup error", e);
            }
        }, EVENT_CLEANUP_INTERVAL, EVENT_CLEANUP_INTERVAL, TimeUnit.MILLISECONDS);
    }
    
    private Map<LockEventListener.EventType, Long> getEventsByTypeMap() {
        return eventsByType.entrySet().stream()
                .collect(Collectors.toMap(
                    Map.Entry::getKey,
                    entry -> entry.getValue().get()
                ));
    }
    
    private double getAverageProcessingTime() {
        long totalProcessed = totalEventsProcessed.get();
        return totalProcessed > 0 ? (double) totalEventProcessingTime.get() / totalProcessed : 0.0;
    }
    
    // 内部类
    
    /**
     * 事件监听器配置
     */
    public static class EventListenerConfig {
        public static final EventListenerConfig DEFAULT = new EventListenerConfig();
        
        private boolean enabled = true;
        private boolean asyncEnabled = false;
        private Set<LockEventListener.EventType> allowedEventTypes = new HashSet<>();
        private int priority = 0;
        
        public EventListenerConfig() {}
        
        public EventListenerConfig enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }
        
        public EventListenerConfig asyncEnabled(boolean asyncEnabled) {
            this.asyncEnabled = asyncEnabled;
            return this;
        }
        
        public EventListenerConfig allowedEventTypes(LockEventListener.EventType... types) {
            this.allowedEventTypes = new HashSet<>(Arrays.asList(types));
            return this;
        }
        
        public EventListenerConfig priority(int priority) {
            this.priority = priority;
            return this;
        }
        
        public boolean isEnabled() { return enabled; }
        public boolean isAsyncEnabled() { return asyncEnabled; }
        public Set<LockEventListener.EventType> getAllowedEventTypes() { return allowedEventTypes; }
        public int getPriority() { return priority; }
    }
    
    /**
     * 事件处理器接口
     */
    public interface EventProcessor {
        void processEvent(LockEvent<?> event);
    }
    
    /**
     * 死锁感知事件监听器接口
     */
    public interface DeadlockAwareEventListener extends LockEventListener<DistributedLock> {
        void onDeadlockDetected(DeadlockInfo deadlockInfo);
        void onDeadlockResolved(DeadlockInfo deadlockInfo);
    }
    
    /**
     * 死锁信息
     */
    public static class DeadlockInfo {
        private final String deadlockId;
        private final List<String> involvedThreads;
        private final List<String> involvedLocks;
        private final String description;
        private final Instant detectionTime;
        private final DeadlockSeverity severity;
        
        public DeadlockInfo(String deadlockId, List<String> involvedThreads, List<String> involvedLocks,
                          String description, Instant detectionTime, DeadlockSeverity severity) {
            this.deadlockId = deadlockId;
            this.involvedThreads = new ArrayList<>(involvedThreads);
            this.involvedLocks = new ArrayList<>(involvedLocks);
            this.description = description;
            this.detectionTime = detectionTime;
            this.severity = severity;
        }
        
        public String getDeadlockId() { return deadlockId; }
        public List<String> getInvolvedThreads() { return involvedThreads; }
        public List<String> getInvolvedLocks() { return involvedLocks; }
        public String getDescription() { return description; }
        public Instant getDetectionTime() { return detectionTime; }
        public DeadlockSeverity getSeverity() { return severity; }
        
        @Override
        public String toString() {
            return "DeadlockInfo{" +
                    "deadlockId='" + deadlockId + '\'' +
                    ", involvedThreads=" + involvedThreads +
                    ", involvedLocks=" + involvedLocks +
                    ", description='" + description + '\'' +
                    ", detectionTime=" + detectionTime +
                    ", severity=" + severity +
                    '}';
        }
    }
    
    /**
     * 死锁严重性级别
     */
    public enum DeadlockSeverity {
        LOW,      // 潜在死锁
        MEDIUM,   // 检测到死锁循环
        HIGH,     // 严重死锁，需要立即处理
        CRITICAL  // 系统级死锁
    }
    
    /**
     * 事件统计信息
     */
    public static class EventStatistics {
        private final long totalEventsProcessed;
        private final long totalEventsDiscarded;
        private final long totalEventProcessingTime;
        private final int currentEventQueueSize;
        private final int eventHistorySize;
        private final int eventListenersCount;
        private final int eventProcessorsCount;
        private final Map<LockEventListener.EventType, Long> eventsByType;
        private final double averageProcessingTime;
        private final boolean deadlockDetectionEnabled;
        
        public EventStatistics(long totalEventsProcessed, long totalEventsDiscarded,
                             long totalEventProcessingTime, int currentEventQueueSize,
                             int eventHistorySize, int eventListenersCount, int eventProcessorsCount,
                             Map<LockEventListener.EventType, Long> eventsByType,
                             double averageProcessingTime, boolean deadlockDetectionEnabled) {
            this.totalEventsProcessed = totalEventsProcessed;
            this.totalEventsDiscarded = totalEventsDiscarded;
            this.totalEventProcessingTime = totalEventProcessingTime;
            this.currentEventQueueSize = currentEventQueueSize;
            this.eventHistorySize = eventHistorySize;
            this.eventListenersCount = eventListenersCount;
            this.eventProcessorsCount = eventProcessorsCount;
            this.eventsByType = new HashMap<>(eventsByType);
            this.averageProcessingTime = averageProcessingTime;
            this.deadlockDetectionEnabled = deadlockDetectionEnabled;
        }
        
        public long getTotalEventsProcessed() { return totalEventsProcessed; }
        public long getTotalEventsDiscarded() { return totalEventsDiscarded; }
        public long getTotalEventProcessingTime() { return totalEventProcessingTime; }
        public int getCurrentEventQueueSize() { return currentEventQueueSize; }
        public int getEventHistorySize() { return eventHistorySize; }
        public int getEventListenersCount() { return eventListenersCount; }
        public int getEventProcessorsCount() { return eventProcessorsCount; }
        public Map<LockEventListener.EventType, Long> getEventsByType() { return eventsByType; }
        public double getAverageProcessingTime() { return averageProcessingTime; }
        public boolean isDeadlockDetectionEnabled() { return deadlockDetectionEnabled; }
        
        public double getDiscardRate() {
            long total = totalEventsProcessed + totalEventsDiscarded;
            return total > 0 ? (double) totalEventsDiscarded / total : 0.0;
        }
        
        public double getProcessingRate() {
            return totalEventProcessingTime > 0 ? 
                (double) totalEventsProcessed / totalEventProcessingTime : 0.0;
        }
    }
}