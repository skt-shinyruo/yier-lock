package com.mycorp.distributedlock.examples.springboot;

import com.mycorp.distributedlock.api.DistributedLockFactory;
import com.mycorp.distributedlock.api.LockEventListener;
import com.mycorp.distributedlock.api.DistributedLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 分布式锁配置示例
 * 演示如何自定义配置和事件监听
 */
@Configuration
public class Configuration {

    /**
     * 自定义事件监听器
     * 监听锁的各种事件，用于监控和调试
     */
    @Bean
    public LockEventListener<DistributedLock> lockEventListener() {
        return new LockEventListener<DistributedLock>() {
            @Override
            public void onLockEvent(com.mycorp.distributedlock.api.LockEvent<DistributedLock> event) {
                switch (event.getEventType()) {
                    case LOCK_ACQUIRED:
                        System.out.println("[LOCK EVENT] Lock acquired: " + event.getLock().getName() +
                                         " by thread: " + event.getThreadName());
                        break;

                    case LOCK_RELEASED:
                        System.out.println("[LOCK EVENT] Lock released: " + event.getLock().getName() +
                                         " by thread: " + event.getThreadName());
                        break;

                    case LOCK_EXPIRED:
                        System.err.println("[LOCK EVENT] Lock expired: " + event.getLock().getName());
                        break;

                    case LOCK_ACQUISITION_FAILED:
                        System.err.println("[LOCK EVENT] Lock acquisition failed: " + event.getLock().getName() +
                                         " by thread: " + event.getThreadName());
                        break;

                    default:
                        System.out.println("[LOCK EVENT] Unknown event: " + event.getEventType() +
                                         " for lock: " + event.getLock().getName());
                }
            }

            @Override
            public void onDeadlockDetected(com.mycorp.distributedlock.api.LockEventListener.DeadlockCycleInfo deadlockInfo) {
                System.err.println("[DEADLOCK ALERT] Deadlock detected in cycle: " +
                                 deadlockInfo.getCycle().stream()
                                     .map(lock -> lock.getName())
                                     .toList());
            }
        };
    }

    /**
     * 锁性能监控器
     * 定期监控锁的性能指标
     */
    @Bean
    public LockPerformanceMonitor lockPerformanceMonitor(DistributedLockFactory lockFactory) {
        return new LockPerformanceMonitor(lockFactory);
    }

    /**
     * 锁清理器
     * 定期清理过期的锁
     */
    @Bean
    public LockCleanupService lockCleanupService(DistributedLockFactory lockFactory) {
        return new LockCleanupService(lockFactory);
    }
}