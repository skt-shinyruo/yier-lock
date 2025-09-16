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
        sample.stop(Timer.builder("lock.held.duration.timer")
            .description("Time a lock was held")
            .tags(Tags.of("lock_name", lockName))
            .register(meterRegistry));
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
}