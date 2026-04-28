package com.mycorp.distributedlock.observability.springboot.config;

import com.mycorp.distributedlock.observability.LockObservationSink;
import com.mycorp.distributedlock.observability.ObservedLockRuntime;
import com.mycorp.distributedlock.runtime.DefaultLockRuntime;
import com.mycorp.distributedlock.observability.springboot.logging.LoggingLockObservationSink;
import com.mycorp.distributedlock.observability.springboot.metrics.MicrometerLockObservationSink;
import com.mycorp.distributedlock.observability.springboot.support.CompositeLockObservationSink;
import com.mycorp.distributedlock.runtime.LockRuntime;
import com.mycorp.distributedlock.springboot.config.DistributedLockProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.ArrayList;
import java.util.List;

@AutoConfiguration(after = com.mycorp.distributedlock.springboot.config.DistributedLockAutoConfiguration.class)
@EnableConfigurationProperties(DistributedLockObservabilityProperties.class)
@ConditionalOnProperty(prefix = "distributed.lock.observability", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DistributedLockObservabilityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public LockObservationSink lockObservationSink(
        ObjectProvider<MeterRegistry> meterRegistry,
        DistributedLockObservabilityProperties properties
    ) {
        List<LockObservationSink> sinks = new ArrayList<>();
        if (properties.getMetrics().isEnabled()) {
            meterRegistry.ifAvailable(registry -> sinks.add(new MicrometerLockObservationSink(registry)));
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
}
