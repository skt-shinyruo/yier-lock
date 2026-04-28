package com.mycorp.distributedlock.springboot.integration;

import com.mycorp.distributedlock.api.LockContext;
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

import static org.assertj.core.api.Assertions.assertThat;

public class DistributedLockProxyBoundaryTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AopAutoConfiguration.class, DistributedLockAutoConfiguration.class))
        .withUserConfiguration(TestApplication.class)
        .withPropertyValues("distributed.lock.enabled=true", "distributed.lock.backend=in-memory");

    @Test
    void jdkProxyShouldHonorInterfaceAnnotation() {
        contextRunner.withPropertyValues("spring.aop.proxy-target-class=false").run(context -> {
            InterfaceLockedService service = context.getBean(InterfaceLockedService.class);
            assertThat(service.process("42")).startsWith("interface-42-token-");
        });
    }

    @Test
    void cglibProxyShouldHonorInterfaceAnnotation() {
        contextRunner.withPropertyValues("spring.aop.proxy-target-class=true").run(context -> {
            InterfaceLockedServiceImpl service = context.getBean(InterfaceLockedServiceImpl.class);
            assertThat(service.process("42")).startsWith("interface-42-token-");
        });
    }

    @Test
    void jdkProxyShouldHonorImplementationAnnotation() {
        contextRunner.withPropertyValues("spring.aop.proxy-target-class=false").run(context -> {
            ImplementationLockedService service = context.getBean(ImplementationLockedService.class);
            assertThat(service.process("42")).startsWith("implementation-42-token-");
        });
    }

    @Test
    void cglibProxyShouldHonorImplementationAnnotation() {
        contextRunner.withPropertyValues("spring.aop.proxy-target-class=true").run(context -> {
            CglibLockedService service = context.getBean(CglibLockedService.class);
            assertThat(service.process("42")).startsWith("cglib-42-token-");
        });
    }

    @Test
    void applicationClassNamedBackendModuleShouldStillBeAdvised() {
        contextRunner.withPropertyValues("spring.aop.proxy-target-class=true").run(context -> {
            ApplicationBackendModule service = context.getBean(ApplicationBackendModule.class);
            assertThat(service.process("42")).startsWith("application-backend-module-42-token-");
        });
    }

    @Test
    void unannotatedFinalApplicationBeanShouldNotBecomeProxyCandidate() {
        contextRunner.withPropertyValues("spring.aop.proxy-target-class=true").run(context -> {
            UnannotatedFinalApplicationService service = context.getBean(UnannotatedFinalApplicationService.class);
            assertThat(service.process("42")).isEqualTo("unlocked-42");
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class TestApplication {
        @Bean BackendModule inMemoryBackendModule() { return new InMemoryBackendModule("in-memory"); }
        @Bean InterfaceLockedServiceImpl interfaceLockedService() { return new InterfaceLockedServiceImpl(); }
        @Bean ImplementationLockedServiceImpl implementationLockedService() { return new ImplementationLockedServiceImpl(); }
        @Bean CglibLockedService cglibLockedService() { return new CglibLockedService(); }
        @Bean ApplicationBackendModule applicationBackendModule() { return new ApplicationBackendModule(); }
        @Bean UnannotatedFinalApplicationService unannotatedFinalApplicationService() { return new UnannotatedFinalApplicationService(); }
    }

    public interface InterfaceLockedService {
        @DistributedLock(key = "interface:#{#p0}")
        String process(String id);
    }

    static class InterfaceLockedServiceImpl implements InterfaceLockedService {
        public String process(String id) {
            return "interface-" + id + "-token-" + LockContext.requireCurrentFencingToken().value();
        }
    }

    public interface ImplementationLockedService { String process(String id); }

    static class ImplementationLockedServiceImpl implements ImplementationLockedService {
        @DistributedLock(key = "implementation:#{#p0}")
        public String process(String id) {
            return "implementation-" + id + "-token-" + LockContext.requireCurrentFencingToken().value();
        }
    }

    static class CglibLockedService {
        @DistributedLock(key = "cglib:#{#p0}")
        public String process(String id) {
            return "cglib-" + id + "-token-" + LockContext.requireCurrentFencingToken().value();
        }
    }

    static class ApplicationBackendModule {
        @DistributedLock(key = "application-backend-module:#{#p0}")
        public String process(String id) {
            return "application-backend-module-" + id + "-token-" + LockContext.requireCurrentFencingToken().value();
        }
    }

    static final class UnannotatedFinalApplicationService {
        public String process(String id) {
            return "unlocked-" + id;
        }
    }
}
