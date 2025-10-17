package com.mycorp.distributedlock.core.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.Tags;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

public class LockMetrics {
    
    private final MeterRegistry meterRegistry;
    private final boolean enabled;
    
    public LockMetrics(MeterRegistry meterRegistry, boolean enabled) {
        this.meterRegistry = meterRegistry;
        this.enabled = enabled;
    }
    
    public Timer.Sample startAcquisitionTimer(String lockName) {
        if (!enabled || meterRegistry == null) {
            return Timer.start();
        }
        return Timer.start(meterRegistry);
    }
    
    public void recordAcquisitionTime(Timer.Sample sample, String lockName, String status) {
        if (!enabled || meterRegistry == null) {
            return;
        }
        sample.stop(Timer.builder("lock.acquisition.timer")
            .description("Time taken to acquire a lock")
            .tags(Tags.of("lock_name", lockName, "status", status))
            .register(meterRegistry));
    }
    
    public Timer.Sample startHeldTimer(String lockName) {
        if (!enabled || meterRegistry == null) {
            return Timer.start();
        }
        return Timer.start(meterRegistry);
    }
    
    public void recordHeldTime(Timer.Sample sample, String lockName) {
        if (!enabled || meterRegistry == null) {
            return;
        }
        Timer timer = Timer.builder("lock.held.duration.timer")
            .description("Time a lock was held")
            .tags(Tags.of("lock_name", lockName))
            .register(meterRegistry);
        sample.stop(timer);
    }
    
    public void incrementContentionCounter(String lockName) {
        if (!enabled || meterRegistry == null) {
            return;
        }
        Counter.builder("lock.contention.counter")
            .description("Number of lock contention failures")
            .tags(Tags.of("lock_name", lockName))
            .register(meterRegistry)
            .increment();
    }
    
    public void incrementWatchdogRenewalCounter(String lockName) {
        if (!enabled || meterRegistry == null) {
            return;
        }
        Counter.builder("lock.watchdog.renewal.counter")
            .description("Number of watchdog renewals")
            .tags(Tags.of("lock_name", lockName))
            .register(meterRegistry)
            .increment();
    }
    
    public void incrementLockAcquisitionCounter(String lockName, String status) {
        if (!enabled || meterRegistry == null) {
            return;
        }
        Counter.builder("lock.acquisition.counter")
            .description("Number of lock acquisition attempts")
            .tags(Tags.of("lock_name", lockName, "status", status))
            .register(meterRegistry)
            .increment();
    }
    
    public void incrementLockReleaseCounter(String lockName, String status) {
        if (!enabled || meterRegistry == null) {
            return;
        }
        Counter.builder("lock.release.counter")
            .description("Number of lock release attempts")
            .tags(Tags.of("lock_name", lockName, "status", status))
            .register(meterRegistry)
            .increment();
    }

    /**
     * 记录锁队列长度
     */
    public void recordQueueLength(String lockName, int queueLength) {
        if (!enabled || meterRegistry == null) {
            return;
        }
        io.micrometer.core.instrument.Gauge.builder("lock.queue.length.gauge", () -> queueLength)
            .description("Current lock queue length")
            .tags(Tags.of("lock_name", lockName))
            .register(meterRegistry);
    }

    /**
     * 记录锁竞争率（竞争失败次数/总获取尝试次数）
     */
    public void recordContentionRate(String lockName, double contentionRate) {
        if (!enabled || meterRegistry == null) {
            return;
        }
        io.micrometer.core.instrument.Gauge.builder("lock.contention.rate.gauge", () -> contentionRate)
            .description("Lock contention rate (failures/total attempts)")
            .tags(Tags.of("lock_name", lockName))
            .register(meterRegistry);
    }

    /**
     * 记录锁持有者数量
     */
    public void recordHolderCount(String lockName, int holderCount) {
        if (!enabled || meterRegistry == null) {
            return;
        }
        io.micrometer.core.instrument.Gauge.builder("lock.holder.count.gauge", () -> holderCount)
            .description("Current number of lock holders")
            .tags(Tags.of("lock_name", lockName))
            .register(meterRegistry);
    }

    /**
     * 记录锁获取等待时间分布
     */
    public void recordWaitTimeDistribution(String lockName, Duration waitTime) {
        if (!enabled || meterRegistry == null) {
            return;
        }
        Timer.builder("lock.wait.time.distribution.timer")
            .description("Distribution of lock wait times")
            .tags(Tags.of("lock_name", lockName))
            .register(meterRegistry)
            .record(waitTime.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * 记录锁过期事件
     */
    public void incrementLockExpiryCounter(String lockName) {
        if (!enabled || meterRegistry == null) {
            return;
        }
        Counter.builder("lock.expiry.counter")
            .description("Number of lock expiries")
            .tags(Tags.of("lock_name", lockName))
            .register(meterRegistry)
            .increment();
    }

    /**
     * 记录锁重入深度
     */
    public void recordReentrantDepth(String lockName, int depth) {
        if (!enabled || meterRegistry == null) {
            return;
        }
        io.micrometer.core.instrument.Gauge.builder("lock.reentrant.depth.gauge", () -> depth)
            .description("Current reentrant depth of lock")
            .tags(Tags.of("lock_name", lockName))
            .register(meterRegistry);
    }
}