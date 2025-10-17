package com.mycorp.distributedlock.springboot;

import com.mycorp.distributedlock.api.DistributedLock;
import com.mycorp.distributedlock.api.DistributedLockFactory;
import com.mycorp.distributedlock.api.DistributedReadWriteLock;
import com.mycorp.distributedlock.api.LockProvider;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Spring 集成分布式锁工厂，基于注入的 LockProvider 委托创建锁。
 */
public class SpringDistributedLockFactory implements DistributedLockFactory {

    private static final Logger logger = LoggerFactory.getLogger(SpringDistributedLockFactory.class);

    private final LockProvider delegate;
    private final MeterRegistry meterRegistry;
    private final OpenTelemetry openTelemetry;

    /**
     * 构造函数。
     *
     * @param delegate 实际的 LockProvider
     * @param meterRegistry 可选的计量注册表
     * @param openTelemetry 可选的 OpenTelemetry 实例
     */
    public SpringDistributedLockFactory(LockProvider delegate,
                                        MeterRegistry meterRegistry,
                                        OpenTelemetry openTelemetry) {
        this.delegate = delegate;
        this.meterRegistry = meterRegistry;
        this.openTelemetry = openTelemetry;
        logger.info("SpringDistributedLockFactory initialized with delegate: {}", delegate.getClass().getSimpleName());
    }

    @Override
    public DistributedLock getLock(String name) {
        return delegate.createLock(name);
    }

    @Override
    public DistributedReadWriteLock getReadWriteLock(String name) {
        return delegate.createReadWriteLock(name);
    }

    @Override
    public void shutdown() {
        logger.info("Shutting down SpringDistributedLockFactory");
        try {
            delegate.close();
        } catch (RuntimeException e) {
            logger.warn("Error closing delegate", e);
        }
    }

    // 提供 getter 以供测试或进一步集成
    public LockProvider getDelegate() {
        return delegate;
    }

    public Optional<MeterRegistry> getMeterRegistry() {
        return Optional.ofNullable(meterRegistry);
    }

    public Optional<OpenTelemetry> getOpenTelemetry() {
        return Optional.ofNullable(openTelemetry);
    }
}
