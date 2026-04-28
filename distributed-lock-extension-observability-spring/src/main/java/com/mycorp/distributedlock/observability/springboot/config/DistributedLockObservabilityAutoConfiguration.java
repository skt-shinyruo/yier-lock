package com.mycorp.distributedlock.observability.springboot.config;

import com.mycorp.distributedlock.observability.LockObservationSink;
import com.mycorp.distributedlock.observability.ObservedLockRuntime;
import com.mycorp.distributedlock.runtime.DefaultLockRuntime;
import com.mycorp.distributedlock.observability.springboot.logging.LoggingLockObservationSink;
import com.mycorp.distributedlock.observability.springboot.support.CompositeLockObservationSink;
import com.mycorp.distributedlock.runtime.LockRuntime;
import com.mycorp.distributedlock.springboot.config.DistributedLockProperties;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
    public static BeanPostProcessor observedLockRuntimeBeanPostProcessor(
        ObjectProvider<LockObservationSink> sinkProvider,
        ObjectProvider<DistributedLockProperties> lockPropertiesProvider,
        ObjectProvider<DistributedLockObservabilityProperties> observabilityPropertiesProvider
    ) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
                if (!(bean instanceof DefaultLockRuntime runtime) || bean instanceof ObservedLockRuntime) {
                    return bean;
                }
                LockObservationSink sink = sinkProvider.getIfAvailable(() -> LockObservationSink.NOOP);
                if (sink == LockObservationSink.NOOP) {
                    return bean;
                }
                DistributedLockProperties lockProperties = lockPropertiesProvider.getIfAvailable();
                DistributedLockObservabilityProperties observabilityProperties = observabilityPropertiesProvider.getIfAvailable();
                return ObservedLockRuntime.decorate(
                    runtime,
                    sink,
                    lockProperties == null ? null : lockProperties.getBackend(),
                    observabilityProperties != null && observabilityProperties.isIncludeLockKeyInLogs()
                );
            }
        };
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
