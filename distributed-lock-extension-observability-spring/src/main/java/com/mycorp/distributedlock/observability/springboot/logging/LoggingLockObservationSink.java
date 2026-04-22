package com.mycorp.distributedlock.observability.springboot.logging;

import com.mycorp.distributedlock.observability.LockObservationEvent;
import com.mycorp.distributedlock.observability.LockObservationSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LoggingLockObservationSink implements LockObservationSink {
    private static final Logger logger = LoggerFactory.getLogger(LoggingLockObservationSink.class);

    @Override
    public void record(LockObservationEvent event) {
        if ("success".equals(event.outcome())) {
            logger.debug(
                "distributed-lock {} {} outcome={} backend={} mode={} durationMicros={}",
                event.surface(),
                event.operation(),
                event.outcome(),
                event.backendId(),
                event.mode(),
                event.duration().toNanos() / 1_000L
            );
            return;
        }
        if ("timeout".equals(event.outcome()) || "interrupted".equals(event.outcome())) {
            logger.info(
                "distributed-lock {} {} outcome={} backend={} mode={} key={} error={}",
                event.surface(),
                event.operation(),
                event.outcome(),
                event.backendId(),
                event.mode(),
                event.key(),
                event.error() == null ? null : event.error().getClass().getSimpleName()
            );
            return;
        }
        logger.warn(
            "distributed-lock {} {} outcome={} backend={} mode={} key={} error={}",
            event.surface(),
            event.operation(),
            event.outcome(),
            event.backendId(),
            event.mode(),
            event.key(),
            event.error() == null ? null : event.error().getClass().getSimpleName()
        );
    }
}
