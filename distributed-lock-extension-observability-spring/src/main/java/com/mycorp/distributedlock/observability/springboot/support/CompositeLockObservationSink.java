package com.mycorp.distributedlock.observability.springboot.support;

import com.mycorp.distributedlock.observability.LockObservationEvent;
import com.mycorp.distributedlock.observability.LockObservationSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public final class CompositeLockObservationSink implements LockObservationSink {
    private static final Logger logger = LoggerFactory.getLogger(CompositeLockObservationSink.class);

    private final List<LockObservationSink> delegates;

    public CompositeLockObservationSink(List<LockObservationSink> delegates) {
        this.delegates = List.copyOf(delegates);
    }

    @Override
    public void record(LockObservationEvent event) {
        delegates.forEach(delegate -> {
            try {
                delegate.record(event);
            } catch (Throwable throwable) {
                logger.warn(
                    "Lock observation delegate failed surface={} operation={} outcome={} delegate={}",
                    event.surface(),
                    event.operation(),
                    event.outcome(),
                    delegate.getClass().getSimpleName(),
                    throwable
                );
            }
        });
    }
}
