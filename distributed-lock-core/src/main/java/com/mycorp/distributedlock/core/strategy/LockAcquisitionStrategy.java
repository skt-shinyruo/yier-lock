package com.mycorp.distributedlock.core.strategy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Lock acquisition strategies with backoff mechanisms.
 * Inspired by Redisson's retry logic and Google's Exponential Backoff pattern.
 */
public interface LockAcquisitionStrategy {

    Logger logger = LoggerFactory.getLogger(LockAcquisitionStrategy.class);

    /**
     * Attempt to acquire lock using the strategy.
     *
     * @param acquireFunc Function that attempts lock acquisition, returns true if successful
     * @param waitTime Maximum time to wait for lock acquisition
     * @return true if lock was acquired, false if timeout
     * @throws InterruptedException if interrupted while waiting
     */
    boolean acquire(BooleanSupplier acquireFunc, Duration waitTime) throws InterruptedException;

    /**
     * Exponential backoff strategy with jitter.
     * Similar to AWS SDK and Redisson's retry mechanism.
     */
    static LockAcquisitionStrategy exponentialBackoff(Duration baseInterval, double multiplier, Duration maxInterval) {
        return (acquireFunc, waitTime) -> {
            long startTime = System.nanoTime();
            long waitNanos = waitTime != null ? waitTime.toNanos() : Long.MAX_VALUE;
            long currentInterval = baseInterval.toNanos();

            int attempt = 0;
            while (true) {
                // Try to acquire
                if (acquireFunc.getAsBoolean()) {
                    return true;
                }

                // Check if we've exceeded wait time
                long elapsed = System.nanoTime() - startTime;
                if (elapsed >= waitNanos) {
                    logger.trace("Lock acquisition timeout after {} attempts", attempt);
                    return false;
                }

                // Calculate next backoff interval with jitter
                long jitter = ThreadLocalRandom.current().nextLong(0, currentInterval / 10 + 1);
                long sleepTime = Math.min(currentInterval + jitter, maxInterval.toNanos());
                
                // Don't sleep longer than remaining wait time
                long remaining = waitNanos - elapsed;
                sleepTime = Math.min(sleepTime, remaining);

                if (sleepTime > 0) {
                    logger.trace("Backing off for {}ms (attempt {})", 
                        TimeUnit.NANOSECONDS.toMillis(sleepTime), attempt);
                    TimeUnit.NANOSECONDS.sleep(sleepTime);
                }

                // Exponential increase for next iteration
                currentInterval = (long) (currentInterval * multiplier);
                currentInterval = Math.min(currentInterval, maxInterval.toNanos());
                attempt++;
            }
        };
    }

    /**
     * Fixed interval polling strategy.
     * Simple but effective for low-contention scenarios.
     */
    static LockAcquisitionStrategy fixedInterval(Duration interval) {
        return (acquireFunc, waitTime) -> {
            long startTime = System.nanoTime();
            long waitNanos = waitTime != null ? waitTime.toNanos() : Long.MAX_VALUE;
            long intervalNanos = interval.toNanos();

            while (true) {
                if (acquireFunc.getAsBoolean()) {
                    return true;
                }

                long elapsed = System.nanoTime() - startTime;
                if (elapsed >= waitNanos) {
                    return false;
                }

                long remaining = waitNanos - elapsed;
                long sleepTime = Math.min(intervalNanos, remaining);

                if (sleepTime > 0) {
                    TimeUnit.NANOSECONDS.sleep(sleepTime);
                }
            }
        };
    }

    /**
     * Adaptive strategy that combines pub/sub waiting with polling.
     * Most efficient for Redis locks - uses pub/sub notifications primarily,
     * falls back to polling if notifications don't arrive.
     */
    static LockAcquisitionStrategy adaptive(BooleanSupplier pubSubWait, Duration pollInterval) {
        return (acquireFunc, waitTime) -> {
            long startTime = System.nanoTime();
            long waitNanos = waitTime != null ? waitTime.toNanos() : Long.MAX_VALUE;

            while (true) {
                if (acquireFunc.getAsBoolean()) {
                    return true;
                }

                long elapsed = System.nanoTime() - startTime;
                if (elapsed >= waitNanos) {
                    return false;
                }

                long remaining = waitNanos - elapsed;

                // First, try to wait for pub/sub notification
                try {
                    if (pubSubWait.getAsBoolean()) {
                        // Notification received, try to acquire immediately
                        continue;
                    }
                } catch (Exception e) {
                    logger.trace("Pub/sub wait failed, falling back to polling: {}", e.getMessage());
                }

                // Pub/sub didn't help, poll after short interval
                long sleepTime = Math.min(pollInterval.toNanos(), remaining);
                if (sleepTime > 0) {
                    TimeUnit.NANOSECONDS.sleep(sleepTime);
                }
            }
        };
    }

    /**
     * Default strategy with sensible defaults.
     * Exponential backoff: 100ms base, 1.5x multiplier, 5s max.
     */
    static LockAcquisitionStrategy defaultStrategy() {
        return exponentialBackoff(
            Duration.ofMillis(100),
            1.5,
            Duration.ofSeconds(5)
        );
    }

    /**
     * Spin strategy for very short waits (< 1ms).
     * Uses busy-wait spinning without yielding.
     * Only suitable for locks held for microseconds.
     */
    static LockAcquisitionStrategy spin(int maxSpins) {
        return (acquireFunc, waitTime) -> {
            long startTime = System.nanoTime();
            long waitNanos = waitTime != null ? waitTime.toNanos() : Long.MAX_VALUE;

            for (int i = 0; i < maxSpins; i++) {
                if (acquireFunc.getAsBoolean()) {
                    return true;
                }

                if (System.nanoTime() - startTime >= waitNanos) {
                    return false;
                }
                
                // Spin hint to processor
                Thread.onSpinWait();
            }

            // After spinning, fall back to yielding
            while (true) {
                if (acquireFunc.getAsBoolean()) {
                    return true;
                }

                if (System.nanoTime() - startTime >= waitNanos) {
                    return false;
                }

                Thread.yield();
            }
        };
    }
}
