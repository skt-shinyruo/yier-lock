package com.mycorp.distributedlock.core.event;

import com.mycorp.distributedlock.api.DistributedLock;
import com.mycorp.distributedlock.api.LockEvent;
import com.mycorp.distributedlock.api.LockEventListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 锁事件管理器单元测试
 * 测试事件发布、监听器管理、事件处理、死锁检测等功能
 */
@ExtendWith(MockitoExtension.class)
class LockEventManagerTest {

    @Mock
    private DistributedLock mockLock;

    @Mock
    private LockEventListener<DistributedLock> mockListener;

    @Mock
    private LockEventManager.DeadlockAwareEventListener mockDeadlockListener;

    @Mock
    private LockEventManager.EventProcessor mockEventProcessor;

    @Mock
    private DeadlockDetector mockDeadlockDetector;

    private LockEventManager eventManager;
    private LockEvent<DistributedLock> testEvent;

    @BeforeEach
    void setUp() {
        when(mockLock.getName()).thenReturn("test-lock");
        
        testEvent = new LockEvent<DistributedLock>() {
            @Override
            public EventType getType() {
                return EventType.ACQUIRED;
            }

            @Override
            public DistributedLock getLock() {
                return mockLock;
            }

            @Override
            public String getLockName() {
                return "test-lock";
            }

            @Override
            public Instant getTimestamp() {
                return Instant.now();
            }

            @Override
            public Map<String, Object> getProperties() {
                return Map.of("test", "value");
            }

            @Override
            public String getDescription() {
                return "Test lock event";
            }
        };
    }

    @Test
    void shouldCreateEventManager() {
        eventManager = new LockEventManager();
        
        assertNotNull(eventManager);
        assertFalse(eventManager.isDeadlockDetectionEnabled());
    }

    @Test
    void shouldCreateEventManagerWithDeadlockDetector() {
        eventManager = new LockEventManager(mockDeadlockDetector);
        
        assertNotNull(eventManager);
        verify(mockDeadlockDetector, never()).start(); // 初始状态不启动
    }

    @Test
    void shouldAddEventListener() {
        eventManager = new LockEventManager();
        
        eventManager.addEventListener(mockListener);
        
        LockEventManager.EventStatistics stats = eventManager.getStatistics();
        assertEquals(1, stats.getEventListenersCount());
    }

    @Test
    void shouldAddEventListenerWithConfig() {
        eventManager = new LockEventManager();
        
        LockEventManager.EventListenerConfig config = new LockEventManager.EventListenerConfig()
            .enabled(true)
            .asyncEnabled(true)
            .priority(1);
            
        eventManager.addEventListener(mockListener, config);
        
        LockEventManager.EventStatistics stats = eventManager.getStatistics();
        assertEquals(1, stats.getEventListenersCount());
    }

    @Test
    void shouldThrowExceptionForNullListener() {
        eventManager = new LockEventManager();
        
        assertThrows(IllegalArgumentException.class, () -> {
            eventManager.addEventListener(null);
        });
    }

    @Test
    void shouldRemoveEventListener() {
        eventManager = new LockEventManager();
        
        eventManager.addEventListener(mockListener);
        eventManager.removeEventListener(mockListener);
        
        LockEventManager.EventStatistics stats = eventManager.getStatistics();
        assertEquals(0, stats.getEventListenersCount());
    }

    @Test
    void shouldAddDeadlockAwareListener() {
        eventManager = new LockEventManager();
        
        eventManager.addEventListener(mockDeadlockListener);
        
        // 验证死锁监听器被注册
        // 这里需要通过事件管理器状态来验证
        LockEventManager.EventStatistics stats = eventManager.getStatistics();
        assertEquals(1, stats.getEventListenersCount());
    }

    @Test
    void shouldRemoveDeadlockAwareListener() {
        eventManager = new LockEventManager();
        
        eventManager.addEventListener(mockDeadlockListener);
        eventManager.removeEventListener(mockDeadlockListener);
        
        LockEventManager.EventStatistics stats = eventManager.getStatistics();
        assertEquals(0, stats.getEventListenersCount());
    }

    @Test
    void shouldPublishEvent() throws InterruptedException {
        eventManager = new LockEventManager();
        eventManager.addEventListener(mockListener);
        
        eventManager.publishEvent(testEvent);
        
        // 等待事件处理
        Thread.sleep(100);
        
        verify(mockListener).shouldHandleEvent(eq(testEvent));
        verify(mockListener).onEvent(eq(testEvent));
    }

    @Test
    void shouldNotPublishNullEvent() {
        eventManager = new LockEventManager();
        
        eventManager.publishEvent(null);
        
        // 验证没有调用监听器
        verify(mockListener, never()).shouldHandleEvent(any());
        verify(mockListener, never()).onEvent(any());
    }

    @Test
    void shouldNotPublishEventWhenManagerClosed() throws InterruptedException {
        eventManager = new LockEventManager();
        eventManager.addEventListener(mockListener);
        eventManager.close();
        
        eventManager.publishEvent(testEvent);
        
        Thread.sleep(100);
        
        verify(mockListener, never()).onEvent(any());
    }

    @Test
    void shouldPublishBatchEvents() throws InterruptedException {
        eventManager = new LockEventManager();
        eventManager.addEventListener(mockListener);
        
        LockEvent<DistributedLock> event1 = createTestEvent(EventType.ACQUIRED);
        LockEvent<DistributedLock> event2 = createTestEvent(EventType.RELEASED);
        
        eventManager.publishEvents(List.of(event1, event2));
        
        Thread.sleep(100);
        
        verify(mockListener, times(2)).shouldHandleEvent(any());
        verify(mockListener, times(2)).onEvent(any());
    }

    @Test
    void shouldNotPublishEmptyEventList() {
        eventManager = new LockEventManager();
        eventManager.addEventListener(mockListener);
        
        eventManager.publishEvents(List.of());
        
        verify(mockListener, never()).onEvent(any());
    }

    @Test
    void shouldNotPublishNullEventList() {
        eventManager = new LockEventManager();
        eventManager.addEventListener(mockListener);
        
        eventManager.publishEvents(null);
        
        verify(mockListener, never()).onEvent(any());
    }

    @Test
    void shouldHandleEventQueueOverflow() throws InterruptedException {
        eventManager = new LockEventManager();
        eventManager.addEventListener(mockListener);
        
        // 发布大量事件来填满队列
        for (int i = 0; i < 10010; i++) { // 超过MAX_EVENT_QUEUE_SIZE (10000)
            eventManager.publishEvent(testEvent);
        }
        
        Thread.sleep(200);
        
        LockEventManager.EventStatistics stats = eventManager.getStatistics();
        assertTrue(stats.getTotalEventsDiscarded() > 0);
    }

    @Test
    void shouldAddEventProcessor() {
        eventManager = new LockEventManager();
        
        eventManager.addEventProcessor(mockEventProcessor);
        
        LockEventManager.EventStatistics stats = eventManager.getStatistics();
        assertEquals(1, stats.getEventProcessorsCount());
    }

    @Test
    void shouldRemoveEventProcessor() {
        eventManager = new LockEventManager();
        
        eventManager.addEventProcessor(mockEventProcessor);
        eventManager.removeEventProcessor(mockEventProcessor);
        
        LockEventManager.EventStatistics stats = eventManager.getStatistics();
        assertEquals(0, stats.getEventProcessorsCount());
    }

    @Test
    void shouldProcessEventsWithProcessor() throws InterruptedException {
        eventManager = new LockEventManager();
        eventManager.addEventProcessor(mockEventProcessor);
        
        eventManager.publishEvent(testEvent);
        
        Thread.sleep(100);
        
        verify(mockEventProcessor).processEvent(eq(testEvent));
    }

    @Test
    void shouldEnableDeadlockDetection() {
        eventManager = new LockEventManager();
        
        eventManager.enableDeadlockDetection();
        
        assertTrue(eventManager.isDeadlockDetectionEnabled());
        verify(mockDeadlockDetector).start();
    }

    @Test
    void shouldDisableDeadlockDetection() {
        eventManager = new LockEventManager();
        
        eventManager.enableDeadlockDetection();
        eventManager.disableDeadlockDetection();
        
        assertFalse(eventManager.isDeadlockDetectionEnabled());
        verify(mockDeadlockDetector).stop();
    }

    @Test
    void shouldNotEnableDeadlockDetectionTwice() {
        eventManager = new LockEventManager();
        
        eventManager.enableDeadlockDetection();
        eventManager.enableDeadlockDetection(); // 第二次调用应该被忽略
        
        assertTrue(eventManager.isDeadlockDetectionEnabled());
        verify(mockDeadlockDetector, times(1)).start();
    }

    @Test
    void shouldNotDisableDeadlockDetectionTwice() {
        eventManager = new LockEventManager();
        
        eventManager.enableDeadlockDetection();
        eventManager.disableDeadlockDetection();
        eventManager.disableDeadlockDetection(); // 第二次调用应该被忽略
        
        assertFalse(eventManager.isDeadlockDetectionEnabled());
        verify(mockDeadlockDetector, times(1)).stop();
    }

    @Test
    void shouldDetectDeadlocks() {
        eventManager = new LockEventManager();
        
        List<LockEventManager.DeadlockInfo> deadlocks = eventManager.detectDeadlocks();
        
        assertNotNull(deadlocks);
        verify(mockDeadlockDetector).detectDeadlocks();
    }

    @Test
    void shouldGetEventStatistics() throws InterruptedException {
        eventManager = new LockEventManager();
        eventManager.addEventListener(mockListener);
        
        eventManager.publishEvent(testEvent);
        Thread.sleep(100);
        
        LockEventManager.EventStatistics stats = eventManager.getStatistics();
        
        assertTrue(stats.getTotalEventsProcessed() > 0);
        assertEquals(0, stats.getTotalEventsDiscarded());
        assertTrue(stats.getCurrentEventQueueSize() >= 0);
        assertTrue(stats.getEventHistorySize() > 0);
        assertEquals(1, stats.getEventListenersCount());
        assertEquals(0, stats.getEventProcessorsCount());
        assertTrue(stats.getAverageProcessingTime() >= 0);
        assertFalse(stats.isDeadlockDetectionEnabled());
        assertTrue(stats.getEventsByType().containsKey(EventType.ACQUIRED));
        assertEquals(1L, stats.getEventsByType().get(EventType.ACQUIRED));
        assertEquals(0.0, stats.getDiscardRate(), 0.001);
        assertTrue(stats.getProcessingRate() > 0);
    }

    @Test
    void shouldGetEventHistory() throws InterruptedException {
        eventManager = new LockEventManager();
        eventManager.addEventListener(mockListener);
        
        eventManager.publishEvent(testEvent);
        Thread.sleep(100);
        
        List<LockEvent<?>> history = eventManager.getEventHistory();
        
        assertNotNull(history);
        assertFalse(history.isEmpty());
        assertEquals(testEvent.getLockName(), history.get(0).getLockName());
    }

    @Test
    void shouldCleanupExpiredEvents() throws InterruptedException {
        eventManager = new LockEventManager();
        eventManager.addEventListener(mockListener);
        
        // 发布多个事件
        for (int i = 0; i < 5; i++) {
            eventManager.publishEvent(testEvent);
            Thread.sleep(10);
        }
        
        Thread.sleep(100);
        
        int initialHistorySize = eventManager.getEventHistory().size();
        
        // 手动触发清理
        eventManager.cleanupExpiredEvents();
        
        // 验证清理不会抛出异常
        assertDoesNotThrow(() -> eventManager.getStatistics());
    }

    @Test
    void shouldResetStatistics() throws InterruptedException {
        eventManager = new LockEventManager();
        eventManager.addEventListener(mockListener);
        
        eventManager.publishEvent(testEvent);
        Thread.sleep(100);
        
        eventManager.resetStatistics();
        
        LockEventManager.EventStatistics stats = eventManager.getStatistics();
        assertEquals(0, stats.getTotalEventsProcessed());
        assertEquals(0, stats.getTotalEventsDiscarded());
        assertEquals(0, stats.getTotalEventProcessingTime());
        assertTrue(stats.getEventsByType().isEmpty());
    }

    @Test
    void shouldHandleListenerException() throws InterruptedException {
        eventManager = new LockEventManager();
        
        doThrow(new RuntimeException("Listener error")).when(mockListener).onEvent(any());
        eventManager.addEventListener(mockListener);
        
        // 应该不会抛出异常
        eventManager.publishEvent(testEvent);
        Thread.sleep(100);
        
        // 验证事件仍然被处理（虽然监听器抛出异常）
        verify(mockListener).onEvent(any());
    }

    @Test
    void shouldHandleProcessorException() throws InterruptedException {
        eventManager = new LockEventManager();
        
        doThrow(new RuntimeException("Processor error")).when(mockEventProcessor).processEvent(any());
        eventManager.addEventProcessor(mockEventProcessor);
        
        // 应该不会抛出异常
        eventManager.publishEvent(testEvent);
        Thread.sleep(100);
        
        // 验证处理器仍然被调用（虽然抛出异常）
        verify(mockEventProcessor).processEvent(any());
    }

    @Test
    void shouldHandleAsyncListener() throws InterruptedException {
        eventManager = new LockEventManager();
        
        LockEventManager.EventListenerConfig config = new LockEventManager.EventListenerConfig()
            .asyncEnabled(true);
        eventManager.addEventListener(mockListener, config);
        
        CountDownLatch latch = new CountDownLatch(1);
        doAnswer(invocation -> {
            latch.countDown();
            return null;
        }).when(mockListener).onEvent(any());
        
        eventManager.publishEvent(testEvent);
        
        // 等待异步处理完成
        assertTrue(latch.await(1, TimeUnit.SECONDS));
        verify(mockListener).onEvent(any());
    }

    @Test
    void shouldFilterEventsByType() throws InterruptedException {
        eventManager = new LockEventManager();
        
        LockEventManager.EventListenerConfig config = new LockEventManager.EventListenerConfig()
            .allowedEventTypes(EventType.ACQUIRED);
        eventManager.addEventListener(mockListener, config);
        
        eventManager.publishEvent(testEvent); // ACQUIRED
        LockEvent<DistributedLock> releasedEvent = createTestEvent(EventType.RELEASED);
        eventManager.publishEvent(releasedEvent); // RELEASED
        
        Thread.sleep(100);
        
        // 只应该处理 ACQUIRED 事件
        verify(mockListener, times(1)).onEvent(eq(testEvent));
        verify(mockListener, never()).onEvent(eq(releasedEvent));
    }

    @Test
    void shouldFilterByDisabledListener() throws InterruptedException {
        eventManager = new LockEventManager();
        
        LockEventManager.EventListenerConfig config = new LockEventManager.EventListenerConfig()
            .enabled(false);
        eventManager.addEventListener(mockListener, config);
        
        eventManager.publishEvent(testEvent);
        
        Thread.sleep(100);
        
        verify(mockListener, never()).onEvent(any());
    }

    @Test
    void shouldCloseEventManager() throws InterruptedException {
        eventManager = new LockEventManager();
        eventManager.addEventListener(mockListener);
        
        eventManager.close();
        
        // 验证线程池关闭
        LockEventManager.EventStatistics stats = eventManager.getStatistics();
        assertEquals(0, stats.getEventListenersCount());
        
        // 验证后续事件不被处理
        eventManager.publishEvent(testEvent);
        Thread.sleep(100);
        
        verify(mockListener, never()).onEvent(any());
    }

    @Test
    void shouldHandleMultipleCloseCalls() {
        eventManager = new LockEventManager();
        
        // 多次关闭应该安全
        eventManager.close();
        eventManager.close(); // 不应该抛出异常
        eventManager.close();
    }

    @Test
    void shouldProvideEventListenerConfig() {
        LockEventManager.EventListenerConfig config = new LockEventManager.EventListenerConfig();
        
        assertTrue(config.isEnabled());
        assertFalse(config.isAsyncEnabled());
        assertTrue(config.getAllowedEventTypes().isEmpty());
        assertEquals(0, config.getPriority());
        
        config.enabled(false);
        assertFalse(config.isEnabled());
        
        config.asyncEnabled(true);
        assertTrue(config.isAsyncEnabled());
        
        config.allowedEventTypes(EventType.ACQUIRED, EventType.RELEASED);
        assertEquals(2, config.getAllowedEventTypes().size());
        
        config.priority(10);
        assertEquals(10, config.getPriority());
    }

    @Test
    void shouldProvideDeadlockInfo() {
        LockEventManager.DeadlockInfo deadlockInfo = new LockEventManager.DeadlockInfo(
            "deadlock-1",
            List.of("thread-1", "thread-2"),
            List.of("lock-1", "lock-2"),
            "Test deadlock",
            Instant.now(),
            LockEventManager.DeadlockSeverity.HIGH
        );
        
        assertEquals("deadlock-1", deadlockInfo.getDeadlockId());
        assertEquals(2, deadlockInfo.getInvolvedThreads().size());
        assertEquals(2, deadlockInfo.getInvolvedLocks().size());
        assertEquals("Test deadlock", deadlockInfo.getDescription());
        assertNotNull(deadlockInfo.getDetectionTime());
        assertEquals(LockEventManager.DeadlockSeverity.HIGH, deadlockInfo.getSeverity());
        
        String toString = deadlockInfo.toString();
        assertTrue(toString.contains("deadlock-1"));
        assertTrue(toString.contains("HIGH"));
    }

    @Test
    void shouldValidateDeadlockSeverity() {
        assertEquals(4, LockEventManager.DeadlockSeverity.values().length);
        
        LockEventManager.DeadlockSeverity[] severities = LockEventManager.DeadlockSeverity.values();
        assertEquals(LockEventManager.DeadlockSeverity.LOW, severities[0]);
        assertEquals(LockEventManager.DeadlockSeverity.MEDIUM, severities[1]);
        assertEquals(LockEventManager.DeadlockSeverity.HIGH, severities[2]);
        assertEquals(LockEventManager.DeadlockSeverity.CRITICAL, severities[3]);
    }

    @Test
    void shouldHandleEventProcessingPerformance() throws InterruptedException {
        eventManager = new LockEventManager();
        eventManager.addEventListener(mockListener);
        
        long startTime = System.currentTimeMillis();
        
        // 发布多个事件
        for (int i = 0; i < 100; i++) {
            eventManager.publishEvent(testEvent);
        }
        
        Thread.sleep(500); // 等待处理完成
        
        long processingTime = System.currentTimeMillis() - startTime;
        LockEventManager.EventStatistics stats = eventManager.getStatistics();
        
        assertTrue(stats.getTotalEventsProcessed() > 0);
        assertTrue(processingTime < 2000); // 应该在2秒内处理完成
        assertTrue(stats.getAverageProcessingTime() < 100); // 平均处理时间应该少于100ms
    }

    @Test
    void shouldMaintainEventHistoryLimit() throws InterruptedException {
        eventManager = new LockEventManager();
        eventManager.addEventListener(mockListener);
        
        // 发布超过历史限制的事件
        for (int i = 0; i < 1010; i++) { // 超过MAX_EVENT_HISTORY_SIZE (1000)
            eventManager.publishEvent(testEvent);
            Thread.sleep(1);
        }
        
        Thread.sleep(200);
        
        List<LockEvent<?>> history = eventManager.getEventHistory();
        assertTrue(history.size() <= 100); // 限制返回的事件数量
    }

    private LockEvent<DistributedLock> createTestEvent(LockEventListener.EventType type) {
        return new LockEvent<DistributedLock>() {
            @Override
            public EventType getType() {
                return type;
            }

            @Override
            public DistributedLock getLock() {
                return mockLock;
            }

            @Override
            public String getLockName() {
                return "test-lock";
            }

            @Override
            public Instant getTimestamp() {
                return Instant.now();
            }

            @Override
            public Map<String, Object> getProperties() {
                return Map.of("test", "value");
            }

            @Override
            public String getDescription() {
                return "Test lock event: " + type;
            }
        };
    }
}