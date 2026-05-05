package com.mycorp.distributedlock.springboot.integration;

import com.mycorp.distributedlock.api.FencingToken;
import com.mycorp.distributedlock.spi.BackendProvider;
import com.mycorp.distributedlock.springboot.annotation.DistributedLock;
import com.mycorp.distributedlock.springboot.config.DistributedLockAutoConfiguration;
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
            assertThat(service.process("42", new FencingToken(1))).startsWith("interface-42-token-");
        });
    }

    @Test
    void cglibProxyShouldHonorInterfaceAnnotation() {
        contextRunner.withPropertyValues("spring.aop.proxy-target-class=true").run(context -> {
            InterfaceLockedServiceImpl service = context.getBean(InterfaceLockedServiceImpl.class);
            assertThat(service.process("42", new FencingToken(1))).startsWith("interface-42-token-");
        });
    }

    @Test
    void cglibProxyShouldHonorGenericInterfaceAnnotation() {
        contextRunner.withPropertyValues("spring.aop.proxy-target-class=true").run(context -> {
            GenericInterfaceLockedService service = context.getBean(GenericInterfaceLockedService.class);
            assertThat(service.process("42", new FencingToken(1))).startsWith("generic-interface-42-token-");
        });
    }

    @Test
    void cglibProxyShouldHonorLaterMatchingInterfaceAnnotation() {
        contextRunner.withPropertyValues("spring.aop.proxy-target-class=true").run(context -> {
            MultiInterfaceLockedService service = context.getBean(MultiInterfaceLockedService.class);
            assertThat(service.process("42", new FencingToken(1))).startsWith("multi-interface-42-token-");
        });
    }

    @Test
    void jdkProxyShouldHonorImplementationAnnotation() {
        contextRunner.withPropertyValues("spring.aop.proxy-target-class=false").run(context -> {
            ImplementationLockedService service = context.getBean(ImplementationLockedService.class);
            assertThat(service.process("42", new FencingToken(1))).startsWith("implementation-42-token-");
        });
    }

    @Test
    void cglibProxyShouldHonorImplementationAnnotation() {
        contextRunner.withPropertyValues("spring.aop.proxy-target-class=true").run(context -> {
            CglibLockedService service = context.getBean(CglibLockedService.class);
            assertThat(service.process("42", new FencingToken(1))).startsWith("cglib-42-token-");
        });
    }

    @Test
    void applicationInfrastructureClassShouldStillBeAdvised() {
        contextRunner.withPropertyValues("spring.aop.proxy-target-class=true").run(context -> {
            ApplicationInfrastructureService service = context.getBean(ApplicationInfrastructureService.class);
            assertThat(service.process("42", new FencingToken(1))).startsWith("application-infrastructure-42-token-");
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
        @Bean BackendProvider<TestBackends.Configuration> inMemoryBackendProvider() { return new TestBackends.Provider("in-memory"); }
        @Bean TestBackends.Configuration inMemoryBackendConfiguration() { return new TestBackends.Configuration(); }
        @Bean InterfaceLockedServiceImpl interfaceLockedService() { return new InterfaceLockedServiceImpl(); }
        @Bean GenericInterfaceLockedService genericInterfaceLockedService() { return new GenericInterfaceLockedService(); }
        @Bean MultiInterfaceLockedService multiInterfaceLockedService() { return new MultiInterfaceLockedService(); }
        @Bean ImplementationLockedServiceImpl implementationLockedService() { return new ImplementationLockedServiceImpl(); }
        @Bean CglibLockedService cglibLockedService() { return new CglibLockedService(); }
        @Bean ApplicationInfrastructureService applicationInfrastructureService() { return new ApplicationInfrastructureService(); }
        @Bean UnannotatedFinalApplicationService unannotatedFinalApplicationService() { return new UnannotatedFinalApplicationService(); }
    }

    public interface InterfaceLockedService {
        @DistributedLock(key = "interface:#{#p0}")
        String process(String id, FencingToken fencingToken);
    }

    static class InterfaceLockedServiceImpl implements InterfaceLockedService {
        public String process(String id, FencingToken fencingToken) {
            return "interface-" + id + "-token-" + fencingToken.value();
        }
    }

    public interface GenericLockedService<T> {
        @DistributedLock(key = "generic-interface:#{#p0}")
        String process(T id, FencingToken fencingToken);
    }

    static class GenericInterfaceLockedService implements GenericLockedService<String> {
        public String process(String id, FencingToken fencingToken) {
            return "generic-interface-" + id + "-token-" + fencingToken.value();
        }
    }

    public interface UnannotatedSameSignatureService {
        String process(String id, FencingToken fencingToken);
    }

    public interface AnnotatedSameSignatureService {
        @DistributedLock(key = "multi-interface:#{#p0}")
        String process(String id, FencingToken fencingToken);
    }

    static class MultiInterfaceLockedService implements UnannotatedSameSignatureService, AnnotatedSameSignatureService {
        public String process(String id, FencingToken fencingToken) {
            return "multi-interface-" + id + "-token-" + fencingToken.value();
        }
    }

    public interface ImplementationLockedService { String process(String id, FencingToken fencingToken); }

    static class ImplementationLockedServiceImpl implements ImplementationLockedService {
        @DistributedLock(key = "implementation:#{#p0}")
        public String process(String id, FencingToken fencingToken) {
            return "implementation-" + id + "-token-" + fencingToken.value();
        }
    }

    static class CglibLockedService {
        @DistributedLock(key = "cglib:#{#p0}")
        public String process(String id, FencingToken fencingToken) {
            return "cglib-" + id + "-token-" + fencingToken.value();
        }
    }

    static class ApplicationInfrastructureService {
        @DistributedLock(key = "application-infrastructure:#{#p0}")
        public String process(String id, FencingToken fencingToken) {
            return "application-infrastructure-" + id + "-token-" + fencingToken.value();
        }
    }

    static final class UnannotatedFinalApplicationService {
        public String process(String id) {
            return "unlocked-" + id;
        }
    }
}
