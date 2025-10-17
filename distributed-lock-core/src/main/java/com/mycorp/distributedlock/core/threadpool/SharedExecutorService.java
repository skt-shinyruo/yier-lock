package com.mycorp.distributedlock.core.threadpool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared executor service pool for distributed lock operations.
 * Inspired by Redisson's executor management pattern.
 * Prevents resource exhaustion by reusing thread pools across lock instances.
 */
public class SharedExecutorService {

    private static final Logger logger = LoggerFactory.getLogger(SharedExecutorService.class);

    private static volatile SharedExecutorService instance;
    private static final Object lock = new Object();

    private final ScheduledExecutorService watchdogExecutor;
    private final ExecutorService asyncExecutor;
    private final ScheduledExecutorService timeoutScheduler;
    private final AtomicInteger referenceCount;

    private SharedExecutorService() {
        this.referenceCount = new AtomicInteger(0);
        
        // Watchdog executor: fixed pool for lock renewal operations
        // Size based on expected concurrent locks, similar to Redisson's approach
        int watchdogThreads = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
        this.watchdogExecutor = Executors.newScheduledThreadPool(
            watchdogThreads,
            new NamedThreadFactory("distlock-watchdog")
        );
        logger.info("Initialized watchdog executor with {} threads", watchdogThreads);

        // Async executor: cached pool for non-blocking operations
        // Uses cached pool like Redisson for elastic scaling
        this.asyncExecutor = new ThreadPoolExecutor(
            0,
            Integer.MAX_VALUE,
            60L, TimeUnit.SECONDS,
            new SynchronousQueue<>(),
            new NamedThreadFactory("distlock-async"),
            new ThreadPoolExecutor.CallerRunsPolicy()
        );
        logger.info("Initialized async executor with cached thread pool");

        // Timeout scheduler: small fixed pool for timeout management
        this.timeoutScheduler = Executors.newScheduledThreadPool(
            2,
            new NamedThreadFactory("distlock-timeout")
        );
        logger.info("Initialized timeout scheduler with 2 threads");
    }

    /**
     * Get the singleton instance.
     */
    public static SharedExecutorService getInstance() {
        if (instance == null) {
            synchronized (lock) {
                if (instance == null) {
                    instance = new SharedExecutorService();
                }
            }
        }
        instance.referenceCount.incrementAndGet();
        return instance;
    }

    /**
     * Release a reference to the shared executor.
     * When reference count reaches zero, executors are shut down.
     */
    public void release() {
        int count = referenceCount.decrementAndGet();
        if (count <= 0) {
            synchronized (lock) {
                if (referenceCount.get() <= 0 && instance != null) {
                    shutdown();
                    instance = null;
                }
            }
        }
    }

    public ScheduledExecutorService getWatchdogExecutor() {
        return watchdogExecutor;
    }

    public ExecutorService getAsyncExecutor() {
        return asyncExecutor;
    }

    public ScheduledExecutorService getTimeoutScheduler() {
        return timeoutScheduler;
    }

    private void shutdown() {
        logger.info("Shutting down shared executor services");

        shutdownExecutor(watchdogExecutor, "watchdog");
        shutdownExecutor(asyncExecutor, "async");
        shutdownExecutor(timeoutScheduler, "timeout");

        logger.info("All shared executor services shut down");
    }

    private void shutdownExecutor(ExecutorService executor, String name) {
        if (executor == null || executor.isShutdown()) {
            return;
        }

        try {
            executor.shutdown();
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                logger.warn("{} executor did not terminate gracefully, forcing shutdown", name);
                executor.shutdownNow();
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    logger.error("{} executor did not terminate after forced shutdown", name);
                }
            }
        } catch (InterruptedException e) {
            logger.warn("Interrupted while shutting down {} executor", name);
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Named thread factory for better debugging and monitoring.
     */
    private static class NamedThreadFactory implements ThreadFactory {
        private final AtomicInteger threadNumber = new AtomicInteger(1);
        private final String namePrefix;

        NamedThreadFactory(String namePrefix) {
            this.namePrefix = namePrefix;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, namePrefix + "-" + threadNumber.getAndIncrement());
            t.setDaemon(true);
            if (t.getPriority() != Thread.NORM_PRIORITY) {
                t.setPriority(Thread.NORM_PRIORITY);
            }
            return t;
        }
    }

    /**
     * Get current metrics for monitoring.
     */
    public ExecutorMetrics getMetrics() {
        ExecutorMetrics metrics = new ExecutorMetrics();
        
        if (asyncExecutor instanceof ThreadPoolExecutor) {
            ThreadPoolExecutor tpe = (ThreadPoolExecutor) asyncExecutor;
            metrics.asyncPoolSize = tpe.getPoolSize();
            metrics.asyncActiveCount = tpe.getActiveCount();
            metrics.asyncQueueSize = tpe.getQueue().size();
            metrics.asyncCompletedTaskCount = tpe.getCompletedTaskCount();
        }

        metrics.referenceCount = referenceCount.get();
        return metrics;
    }

    public static class ExecutorMetrics {
        public int asyncPoolSize;
        public int asyncActiveCount;
        public int asyncQueueSize;
        public long asyncCompletedTaskCount;
        public int referenceCount;

        @Override
        public String toString() {
            return String.format(
                "ExecutorMetrics{asyncPool=%d, asyncActive=%d, asyncQueue=%d, asyncCompleted=%d, refs=%d}",
                asyncPoolSize, asyncActiveCount, asyncQueueSize, asyncCompletedTaskCount, referenceCount
            );
        }
    }
}
