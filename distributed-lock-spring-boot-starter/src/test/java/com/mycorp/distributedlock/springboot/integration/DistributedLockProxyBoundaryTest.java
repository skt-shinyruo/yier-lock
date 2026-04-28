package com.mycorp.distributedlock.springboot.integration;

import com.mycorp.distributedlock.runtime.spi.BackendModule;
import com.mycorp.distributedlock.springboot.annotation.DistributedLock;
import com.mycorp.distributedlock.springboot.config.DistributedLockAutoConfiguration;
import com.mycorp.distributedlock.testkit.support.InMemoryBackendModule;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class DistributedLockProxyBoundaryTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AopAutoConfiguration.class, DistributedLockAutoConfiguration.class))
        .withUserConfiguration(TestApplication.class)
        .withPropertyValues("distributed.lock.enabled=true", "distributed.lock.backend=in-memory");

    @Test
    void jdkProxyShouldHonorInterfaceAnnotation() {
        contextRunner.withPropertyValues("spring.aop.proxy-target-class=false").run(context -> {
            InterfaceLockedServiceImpl.reset();
            InterfaceLockedService service = context.getBean(InterfaceLockedService.class);
            assertThat(service.process("42")).isEqualTo("interface-42");
            assertThat(InterfaceLockedServiceImpl.invocations()).isEqualTo(1);
        });
    }

    @Test
    void jdkProxyShouldHonorImplementationAnnotation() {
        contextRunner.withPropertyValues("spring.aop.proxy-target-class=false").run(context -> {
            ImplementationLockedServiceImpl.reset();
            ImplementationLockedService service = context.getBean(ImplementationLockedService.class);
            assertThat(service.process("42")).isEqualTo("implementation-42");
            assertThat(ImplementationLockedServiceImpl.invocations()).isEqualTo(1);
        });
    }

    @Test
    void cglibProxyShouldHonorImplementationAnnotation() {
        contextRunner.withPropertyValues("spring.aop.proxy-target-class=true").run(context -> {
            CglibLockedService service = context.getBean(CglibLockedService.class);
            assertThat(service.process("42")).isEqualTo("cglib-42");
            assertThat(service.invocations()).isEqualTo(1);
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class TestApplication {
        @Bean BackendModule inMemoryBackendModule() { return new InMemoryBackendModule("in-memory"); }
        @Bean InterfaceLockedServiceImpl interfaceLockedService() { return new InterfaceLockedServiceImpl(); }
        @Bean ImplementationLockedServiceImpl implementationLockedService() { return new ImplementationLockedServiceImpl(); }
        @Bean CglibLockedService cglibLockedService() { return new CglibLockedService(); }
    }

    interface InterfaceLockedService {
        @DistributedLock(key = "interface:#{#p0}")
        String process(String id);
    }

    static class InterfaceLockedServiceImpl implements InterfaceLockedService {
        private static final AtomicInteger invocations = new AtomicInteger();
        public String process(String id) { invocations.incrementAndGet(); return "interface-" + id; }
        static int invocations() { return invocations.get(); }
        static void reset() { invocations.set(0); }
    }

    interface ImplementationLockedService { String process(String id); }

    static class ImplementationLockedServiceImpl implements ImplementationLockedService {
        private static final AtomicInteger invocations = new AtomicInteger();
        @DistributedLock(key = "implementation:#{#p0}")
        public String process(String id) { invocations.incrementAndGet(); return "implementation-" + id; }
        static int invocations() { return invocations.get(); }
        static void reset() { invocations.set(0); }
    }

    static class CglibLockedService {
        private final AtomicInteger invocations = new AtomicInteger();
        @DistributedLock(key = "cglib:#{#p0}")
        public String process(String id) { invocations.incrementAndGet(); return "cglib-" + id; }
        int invocations() { return invocations.get(); }
    }
}
