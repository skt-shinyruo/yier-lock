package com.mycorp.distributedlock.observability.springboot.config;

import com.mycorp.distributedlock.api.LockRuntime;
import com.mycorp.distributedlock.observability.LockObservationSink;
import com.mycorp.distributedlock.observability.ObservedLockRuntimeDecorator;
import com.mycorp.distributedlock.observability.springboot.logging.LoggingLockObservationSink;
import com.mycorp.distributedlock.observability.springboot.support.CompositeLockObservationSink;
import com.mycorp.distributedlock.runtime.LockRuntimeDecorator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@AutoConfiguration(after = com.mycorp.distributedlock.springboot.config.DistributedLockAutoConfiguration.class)
@EnableConfigurationProperties(DistributedLockObservabilityProperties.class)
@ConditionalOnProperty(prefix = "distributed.lock.observability", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DistributedLockObservabilityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public LockObservationSink lockObservationSink(
        ObjectProvider<MicrometerLockObservationSinkProvider> micrometerSinkProvider,
        DistributedLockObservabilityProperties properties
    ) {
        List<LockObservationSink> sinks = new ArrayList<>();
        if (properties.getMetrics().isEnabled()) {
            micrometerSinkProvider.ifAvailable(provider -> provider.getIfAvailable().ifPresent(sinks::add));
        }
        if (properties.getLogging().isEnabled()) {
            sinks.add(new LoggingLockObservationSink());
        }
        return sinks.isEmpty() ? LockObservationSink.NOOP : new CompositeLockObservationSink(sinks);
    }

    @Bean
    public LockRuntimeDecorator observedLockRuntimeDecorator(
        ObjectProvider<LockObservationSink> sinkProvider,
        ObjectProvider<DistributedLockObservabilityProperties> observabilityPropertiesProvider
    ) {
        return new ObservedLockRuntimeDecoratorFactory(sinkProvider, observabilityPropertiesProvider);
    }

    private static final class ObservedLockRuntimeDecoratorFactory implements LockRuntimeDecorator, Ordered {
        private final ObjectProvider<LockObservationSink> sinkProvider;
        private final ObjectProvider<DistributedLockObservabilityProperties> observabilityPropertiesProvider;

        private ObservedLockRuntimeDecoratorFactory(
            ObjectProvider<LockObservationSink> sinkProvider,
            ObjectProvider<DistributedLockObservabilityProperties> observabilityPropertiesProvider
        ) {
            this.sinkProvider = sinkProvider;
            this.observabilityPropertiesProvider = observabilityPropertiesProvider;
        }

        @Override
        public LockRuntime decorate(LockRuntime runtime) {
            LockObservationSink sink = sinkProvider.getIfAvailable(() -> LockObservationSink.NOOP);
            if (sink == LockObservationSink.NOOP) {
                return runtime;
            }
            DistributedLockObservabilityProperties observabilityProperties = observabilityPropertiesProvider.getIfAvailable();
            return new ObservedLockRuntimeDecorator(
                sink,
                observabilityProperties != null && observabilityProperties.isIncludeLockKeyInLogs()
            ).decorate(runtime);
        }

        @Override
        public int getOrder() {
            return Ordered.HIGHEST_PRECEDENCE;
        }
    }

    interface MicrometerLockObservationSinkProvider {
        Optional<LockObservationSink> getIfAvailable();
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(io.micrometer.core.instrument.MeterRegistry.class)
    static class MicrometerLockObservationConfiguration {
        @Bean
        MicrometerLockObservationSinkProvider micrometerLockObservationSinkProvider(
            ObjectProvider<io.micrometer.core.instrument.MeterRegistry> meterRegistry
        ) {
            return () -> meterRegistry.stream()
                .findFirst()
                .map(com.mycorp.distributedlock.observability.springboot.metrics.MicrometerLockObservationSink::new);
        }
    }
}
