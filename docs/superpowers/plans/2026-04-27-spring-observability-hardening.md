# Spring And Observability Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Spring `@DistributedLock` proxy behavior and observability decoration explicit, safe, and cheap to disable.

**Architecture:** Introduce a small method-resolution helper inside the starter, keep the lock executor synchronous-only, cache SpEL parsing by method/expression, and split observability sinks by configuration. Observability wrappers record `Throwable` while safe-publish continues to isolate sink failures from lock behavior.

**Tech Stack:** Java 17, Spring Boot 3.2, Spring AOP, Spring Expression Language, Micrometer, Maven, JUnit 5, AssertJ, ApplicationContextRunner.

---

## File Map

- Create: `distributed-lock-spring-boot-starter/src/main/java/com/mycorp/distributedlock/springboot/aop/DistributedLockMethodResolver.java`
  Resolves proxied and most-specific methods, merged annotation, return type, and async annotations.
- Modify: `distributed-lock-spring-boot-starter/src/main/java/com/mycorp/distributedlock/springboot/aop/DistributedLockAspect.java`
  Uses method resolver, supports interface/implementation annotations, rejects `@Async`, and exposes explicit order.
- Modify: `distributed-lock-spring-boot-starter/src/main/java/com/mycorp/distributedlock/springboot/key/SpelLockKeyResolver.java`
  Adds expression cache, method metadata variables, and null/blank key rejection.
- Create: `distributed-lock-spring-boot-starter/src/test/java/com/mycorp/distributedlock/springboot/integration/DistributedLockProxyBoundaryTest.java`
  Covers JDK proxy, implementation annotations, CGLIB, and self-invocation documentation behavior.
- Modify: `distributed-lock-spring-boot-starter/src/test/java/com/mycorp/distributedlock/springboot/integration/DistributedLockAsyncGuardTest.java`
  Adds `@Async void`, class-level `@Async`, and `Object` returning `CompletableFuture` cases.
- Modify: `distributed-lock-spring-boot-starter/src/test/java/com/mycorp/distributedlock/springboot/key/SpelLockKeyResolverTest.java`
  Adds cache, metadata variables, null/blank rejection, and method-context error tests.
- Modify: `distributed-lock-extension-observability/src/main/java/com/mycorp/distributedlock/observability/ObservedLockExecutor.java`
  Records `Throwable` and rethrows unchanged.
- Modify: `distributed-lock-extension-observability/src/main/java/com/mycorp/distributedlock/observability/ObservedLockSession.java`
  Records runtime failures and `Error` from acquire.
- Create: `distributed-lock-extension-observability/src/test/java/com/mycorp/distributedlock/observability/ObservedLockThrowableTest.java`
  Covers `AssertionError` observation and original rethrow.
- Modify: `distributed-lock-extension-observability-spring/src/main/java/com/mycorp/distributedlock/observability/springboot/config/DistributedLockObservabilityProperties.java`
  Adds `logging.enabled` and `metrics.enabled` nested properties.
- Modify: `distributed-lock-extension-observability-spring/src/main/java/com/mycorp/distributedlock/observability/springboot/config/DistributedLockObservabilityAutoConfiguration.java`
  Builds sinks conditionally and skips decoration when no sinks are active.
- Modify: `distributed-lock-extension-observability-spring/src/main/java/com/mycorp/distributedlock/observability/springboot/logging/LoggingLockObservationSink.java`
  Guards debug success logging.
- Modify: `distributed-lock-extension-observability-spring/src/test/java/com/mycorp/distributedlock/observability/springboot/integration/DistributedLockObservabilityAutoConfigurationTest.java`
  Adds sink toggle, no-decoration, low-cardinality, and no-double-wrap tests.
- Modify: `distributed-lock-spring-boot-starter/README.md`
  Documents proxy, self-invocation, `@Async`, transaction order, and observability toggles.

## Task 1: Resolve Spring Methods And Reject Async Boundaries

**Files:**
- Create: `distributed-lock-spring-boot-starter/src/main/java/com/mycorp/distributedlock/springboot/aop/DistributedLockMethodResolver.java`
- Modify: `distributed-lock-spring-boot-starter/src/main/java/com/mycorp/distributedlock/springboot/aop/DistributedLockAspect.java`
- Modify: `distributed-lock-spring-boot-starter/src/test/java/com/mycorp/distributedlock/springboot/integration/DistributedLockAsyncGuardTest.java`
- Create: `distributed-lock-spring-boot-starter/src/test/java/com/mycorp/distributedlock/springboot/integration/DistributedLockProxyBoundaryTest.java`

- [ ] **Step 1: Add async guard regression tests**

Extend `DistributedLockAsyncGuardTest.AsyncService` with:

```java
@DistributedLock(key = "job:#{#p0}")
@org.springframework.scheduling.annotation.Async
public void processAsyncVoid(String jobId) {
    invoked.set(true);
}

@DistributedLock(key = "job:#{#p0}")
public Object processObjectAsync(String jobId) {
    invoked.set(true);
    return CompletableFuture.completedFuture("processed-" + jobId);
}
```

Add tests:

```java
@Test
void shouldRejectAsyncVoidBeforeInvocation() {
    contextRunner.run(context -> {
        AsyncService service = context.getBean(AsyncService.class);

        assertThatThrownBy(() -> service.processAsyncVoid("42"))
            .isInstanceOf(LockConfigurationException.class)
            .hasMessageContaining("@Async");
        assertThat(service.wasInvoked()).isFalse();
    });
}

@Test
void objectReturningAsyncValueShouldBeRejectedByExecutorDefenseInDepth() {
    contextRunner.run(context -> {
        AsyncService service = context.getBean(AsyncService.class);

        assertThatThrownBy(() -> service.processObjectAsync("42"))
            .isInstanceOf(LockConfigurationException.class)
            .hasMessageContaining("CompletionStage");
        assertThat(service.wasInvoked()).isTrue();
    });
}
```

- [ ] **Step 2: Add proxy boundary tests**

Create `DistributedLockProxyBoundaryTest.java` with JDK interface and CGLIB services:

```java
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
            InterfaceLockedService service = context.getBean(InterfaceLockedService.class);
            assertThat(service.process("42")).isEqualTo("interface-42");
            assertThat(context.getBean(InterfaceLockedServiceImpl.class).invocations()).isEqualTo(1);
        });
    }

    @Test
    void jdkProxyShouldHonorImplementationAnnotation() {
        contextRunner.withPropertyValues("spring.aop.proxy-target-class=false").run(context -> {
            ImplementationLockedService service = context.getBean(ImplementationLockedService.class);
            assertThat(service.process("42")).isEqualTo("implementation-42");
            assertThat(context.getBean(ImplementationLockedServiceImpl.class).invocations()).isEqualTo(1);
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

    static final class InterfaceLockedServiceImpl implements InterfaceLockedService {
        private final AtomicInteger invocations = new AtomicInteger();
        public String process(String id) { invocations.incrementAndGet(); return "interface-" + id; }
        int invocations() { return invocations.get(); }
    }

    interface ImplementationLockedService { String process(String id); }

    static final class ImplementationLockedServiceImpl implements ImplementationLockedService {
        private final AtomicInteger invocations = new AtomicInteger();
        @DistributedLock(key = "implementation:#{#p0}")
        public String process(String id) { invocations.incrementAndGet(); return "implementation-" + id; }
        int invocations() { return invocations.get(); }
    }

    static class CglibLockedService {
        private final AtomicInteger invocations = new AtomicInteger();
        @DistributedLock(key = "cglib:#{#p0}")
        public String process(String id) { invocations.incrementAndGet(); return "cglib-" + id; }
        int invocations() { return invocations.get(); }
    }
}
```

- [ ] **Step 3: Run starter tests to verify failures**

Run:

```bash
mvn -q -pl distributed-lock-spring-boot-starter -am test -Dtest=DistributedLockAsyncGuardTest,DistributedLockProxyBoundaryTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL for `@Async void` and implementation-annotation JDK proxy support.

- [ ] **Step 4: Implement method resolver**

Create `DistributedLockMethodResolver.java`:

```java
package com.mycorp.distributedlock.springboot.aop;

import com.mycorp.distributedlock.springboot.annotation.DistributedLock;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.BridgeMethodResolver;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.scheduling.annotation.Async;

import java.lang.reflect.Method;

final class DistributedLockMethodResolver {

    ResolvedLockMethod resolve(ProceedingJoinPoint joinPoint, DistributedLock pointcutAnnotation) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method proxiedMethod = signature.getMethod();
        Class<?> targetClass = joinPoint.getTarget() == null ? proxiedMethod.getDeclaringClass() : joinPoint.getTarget().getClass();
        Method specificMethod = BridgeMethodResolver.findBridgedMethod(AopUtils.getMostSpecificMethod(proxiedMethod, targetClass));
        DistributedLock annotation = AnnotatedElementUtils.findMergedAnnotation(specificMethod, DistributedLock.class);
        if (annotation == null) {
            annotation = AnnotatedElementUtils.findMergedAnnotation(proxiedMethod, DistributedLock.class);
        }
        if (annotation == null) {
            annotation = pointcutAnnotation;
        }
        return new ResolvedLockMethod(proxiedMethod, specificMethod, targetClass, annotation, hasAsync(proxiedMethod, specificMethod, targetClass));
    }

    private boolean hasAsync(Method proxiedMethod, Method specificMethod, Class<?> targetClass) {
        return AnnotatedElementUtils.hasAnnotation(proxiedMethod, Async.class)
            || AnnotatedElementUtils.hasAnnotation(proxiedMethod.getDeclaringClass(), Async.class)
            || AnnotatedElementUtils.hasAnnotation(specificMethod, Async.class)
            || AnnotatedElementUtils.hasAnnotation(targetClass, Async.class);
    }

    record ResolvedLockMethod(
        Method proxiedMethod,
        Method specificMethod,
        Class<?> targetClass,
        DistributedLock distributedLock,
        boolean asyncAnnotated
    ) {
    }
}
```

- [ ] **Step 5: Update aspect pointcut, order, and async guard**

Change the aspect pointcut to resolve annotations manually:

```java
@Around("@annotation(com.mycorp.distributedlock.springboot.annotation.DistributedLock) || @within(com.mycorp.distributedlock.springboot.annotation.DistributedLock) || execution(* *(..))")
public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
    DistributedLockMethodResolver.ResolvedLockMethod resolved = methodResolver.resolve(joinPoint, null);
    if (resolved.distributedLock() == null) {
        return joinPoint.proceed();
    }
    ensureSynchronous(resolved);
    LockRequest request = resolveRequest(joinPoint, resolved);
    return lockExecutor.withLock(request, () -> proceed(joinPoint));
}
```

Make `DistributedLockAspect` implement `org.springframework.core.Ordered`:

```java
@Override
public int getOrder() {
    return org.springframework.core.Ordered.HIGHEST_PRECEDENCE + 100;
}
```

Update `ensureSynchronous(...)` to reject `resolved.asyncAnnotated()` and async return types from both proxied and specific methods.

- [ ] **Step 6: Run starter focused tests**

Run:

```bash
mvn -q -pl distributed-lock-spring-boot-starter -am test -Dtest=DistributedLockAsyncGuardTest,DistributedLockProxyBoundaryTest,DistributedLockAspectIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

- [ ] **Step 7: Commit Spring method boundary hardening**

```bash
git add distributed-lock-spring-boot-starter/src/main/java/com/mycorp/distributedlock/springboot/aop/DistributedLockAspect.java distributed-lock-spring-boot-starter/src/main/java/com/mycorp/distributedlock/springboot/aop/DistributedLockMethodResolver.java distributed-lock-spring-boot-starter/src/test/java/com/mycorp/distributedlock/springboot/integration/DistributedLockAsyncGuardTest.java distributed-lock-spring-boot-starter/src/test/java/com/mycorp/distributedlock/springboot/integration/DistributedLockProxyBoundaryTest.java
git commit -m "fix: harden distributed lock spring method boundaries"
```

## Task 2: Cache And Validate SpEL Lock Keys

**Files:**
- Modify: `distributed-lock-spring-boot-starter/src/main/java/com/mycorp/distributedlock/springboot/key/SpelLockKeyResolver.java`
- Modify: `distributed-lock-spring-boot-starter/src/test/java/com/mycorp/distributedlock/springboot/key/SpelLockKeyResolverTest.java`

- [ ] **Step 1: Add SpEL cache and validation tests**

Add these tests to `SpelLockKeyResolverTest`:

```java
@Test
void shouldExposeMethodTargetAndTargetClassVariables() throws Exception {
    ProceedingJoinPoint joinPoint = joinPoint("42", "cn");

    assertThat(resolver.resolveKey(joinPoint, "#{#method.name}:#{#targetClass.simpleName}:#{#target != null}"))
        .isEqualTo("process:TestTarget:true");
}

@Test
void shouldRejectNullTemplateResult() throws Exception {
    ProceedingJoinPoint joinPoint = joinPoint("42", "cn");

    assertThatThrownBy(() -> resolver.resolveKey(joinPoint, "#{null}"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("resolved to null");
}

@Test
void shouldRejectBlankTemplateResult() throws Exception {
    ProceedingJoinPoint joinPoint = joinPoint("42", "cn");

    assertThatThrownBy(() -> resolver.resolveKey(joinPoint, "#{'   '}"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("resolved to blank");
}
```

Add missing import:

```java
import static org.assertj.core.api.Assertions.assertThatThrownBy;
```

Update `joinPoint(...)` to set a target:

```java
TestTarget target = new TestTarget();
Mockito.when(joinPoint.getTarget()).thenReturn(target);
```

- [ ] **Step 2: Run SpEL tests to verify failures**

Run:

```bash
mvn -q -pl distributed-lock-spring-boot-starter -am test -Dtest=SpelLockKeyResolverTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL because metadata variables and null/blank validation are absent.

- [ ] **Step 3: Add method/expression cache and variables**

In `SpelLockKeyResolver`, add:

```java
private final java.util.concurrent.ConcurrentMap<ExpressionKey, org.springframework.expression.Expression> expressionCache = new java.util.concurrent.ConcurrentHashMap<>();

private record ExpressionKey(Method method, String expression) {
}
```

Replace parse/evaluate logic with:

```java
MethodSignature signature = (MethodSignature) joinPoint.getSignature();
Method method = signature.getMethod();
Object[] args = joinPoint.getArgs();
String[] parameterNames = parameterNameDiscoverer.getParameterNames(method);

StandardEvaluationContext context = new StandardEvaluationContext();
context.setVariable("args", args);
context.setVariable("method", method);
context.setVariable("target", joinPoint.getTarget());
context.setVariable("targetClass", joinPoint.getTarget() == null ? method.getDeclaringClass() : joinPoint.getTarget().getClass());
for (int index = 0; index < args.length; index++) {
    context.setVariable("p" + index, args[index]);
    context.setVariable("a" + index, args[index]);
    if (parameterNames != null && index < parameterNames.length) {
        context.setVariable(parameterNames[index], args[index]);
    }
}

Object value = expressionCache.computeIfAbsent(new ExpressionKey(method, expression), key ->
    parser.parseExpression(key.expression(), TEMPLATE_CONTEXT)
).getValue(context);
if (value == null) {
    throw new IllegalArgumentException("Lock key expression resolved to null: " + expression + " on " + method);
}
String key = value.toString();
if (key.isBlank()) {
    throw new IllegalArgumentException("Lock key expression resolved to blank: " + expression + " on " + method);
}
return key;
```

- [ ] **Step 4: Run SpEL tests**

Run:

```bash
mvn -q -pl distributed-lock-spring-boot-starter -am test -Dtest=SpelLockKeyResolverTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

- [ ] **Step 5: Commit SpEL key hardening**

```bash
git add distributed-lock-spring-boot-starter/src/main/java/com/mycorp/distributedlock/springboot/key/SpelLockKeyResolver.java distributed-lock-spring-boot-starter/src/test/java/com/mycorp/distributedlock/springboot/key/SpelLockKeyResolverTest.java
git commit -m "fix: cache and validate spring lock key expressions"
```

## Task 3: Record Throwable In Observability Wrappers

**Files:**
- Modify: `distributed-lock-extension-observability/src/main/java/com/mycorp/distributedlock/observability/ObservedLockExecutor.java`
- Modify: `distributed-lock-extension-observability/src/main/java/com/mycorp/distributedlock/observability/ObservedLockSession.java`
- Create: `distributed-lock-extension-observability/src/test/java/com/mycorp/distributedlock/observability/ObservedLockThrowableTest.java`

- [ ] **Step 1: Add Throwable observation tests**

Create `ObservedLockThrowableTest.java` with:

```java
package com.mycorp.distributedlock.observability;

import com.mycorp.distributedlock.api.LockExecutor;
import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.WaitPolicy;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ObservedLockThrowableTest {

    @Test
    void executorShouldRecordAndRethrowAssertionError() {
        List<LockObservationEvent> events = new ArrayList<>();
        AssertionError error = new AssertionError("boom");
        LockExecutor delegate = (request, action) -> { throw error; };
        ObservedLockExecutor executor = new ObservedLockExecutor(delegate, events::add, "test", false);

        assertThatThrownBy(() -> executor.withLock(request(), () -> "unused"))
            .isSameAs(error);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).error()).isSameAs(error);
    }

    private static LockRequest request() {
        return new LockRequest(new LockKey("observed:error"), LockMode.MUTEX, WaitPolicy.timed(Duration.ofMillis(10)));
    }
}
```

- [ ] **Step 2: Run observability test to verify failure**

Run:

```bash
mvn -q -pl distributed-lock-extension-observability -am test -Dtest=ObservedLockThrowableTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL because `ObservedLockExecutor` only catches `Exception`.

- [ ] **Step 3: Catch and rethrow Throwable in observed executor**

Change `ObservedLockExecutor.withLock(...)` catch block to:

```java
} catch (Throwable throwable) {
    LockObservationSupport.publishSafely(sink, new LockObservationEvent(
        backendId,
        "executor",
        "scope",
        LockObservationSupport.scopeOutcomeFor(throwable),
        request.mode(),
        LockObservationSupport.keyFor(request, includeKey),
        LockObservationSupport.durationSince(startedNanos),
        throwable
    ));
    throw throwable;
}
```

Change `LockObservationSupport.scopeOutcomeFor(...)` parameter type from `Exception` to `Throwable`.

- [ ] **Step 4: Catch and rethrow Throwable in observed session**

In `ObservedLockSession.acquire(...)`, keep the dedicated `InterruptedException` catch, then replace the runtime catch with:

```java
} catch (Throwable throwable) {
    LockObservationSupport.publishSafely(sink, new LockObservationEvent(
        backendId,
        "client",
        "acquire",
        LockObservationSupport.acquireOutcomeFor(throwable),
        request.mode(),
        LockObservationSupport.keyFor(request, includeKey),
        LockObservationSupport.durationSince(startedNanos),
        throwable
    ));
    throw throwable;
}
```

Change `LockObservationSupport.acquireOutcomeFor(...)` parameter type from `RuntimeException` to `Throwable`.

- [ ] **Step 5: Run observability tests**

Run:

```bash
mvn -q -pl distributed-lock-extension-observability -am test -Dtest=ObservedLockSessionTest,ObservedLockExecutorTest,ObservedLockThrowableTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

- [ ] **Step 6: Commit Throwable observation**

```bash
git add distributed-lock-extension-observability/src/main/java/com/mycorp/distributedlock/observability/ObservedLockExecutor.java distributed-lock-extension-observability/src/main/java/com/mycorp/distributedlock/observability/ObservedLockSession.java distributed-lock-extension-observability/src/main/java/com/mycorp/distributedlock/observability/LockObservationSupport.java distributed-lock-extension-observability/src/test/java/com/mycorp/distributedlock/observability/ObservedLockThrowableTest.java
git commit -m "fix: observe throwable lock failures"
```

## Task 4: Add Observability Sink Toggles And No-Decoration Path

**Files:**
- Modify: `distributed-lock-extension-observability-spring/src/main/java/com/mycorp/distributedlock/observability/springboot/config/DistributedLockObservabilityProperties.java`
- Modify: `distributed-lock-extension-observability-spring/src/main/java/com/mycorp/distributedlock/observability/springboot/config/DistributedLockObservabilityAutoConfiguration.java`
- Modify: `distributed-lock-extension-observability-spring/src/main/java/com/mycorp/distributedlock/observability/springboot/logging/LoggingLockObservationSink.java`
- Modify: `distributed-lock-extension-observability-spring/src/test/java/com/mycorp/distributedlock/observability/springboot/integration/DistributedLockObservabilityAutoConfigurationTest.java`

- [ ] **Step 1: Add auto-configuration toggle tests**

In `DistributedLockObservabilityAutoConfigurationTest`, add tests:

```java
@Test
void shouldNotDecorateRuntimeWhenAllSinksAreDisabled() {
    contextRunner
        .withPropertyValues(
            "distributed.lock.observability.logging.enabled=false",
            "distributed.lock.observability.metrics.enabled=false"
        )
        .run(context -> assertThat(context.getBean(LockRuntime.class)).isInstanceOf(DefaultLockRuntime.class));
}

@Test
void shouldKeepMetricsLowCardinalityWhenLockKeyLoggingIsEnabled() {
    contextRunner
        .withPropertyValues("distributed.lock.observability.include-lock-key-in-logs=true")
        .run(context -> {
            io.micrometer.core.instrument.simple.SimpleMeterRegistry registry = context.getBean(io.micrometer.core.instrument.simple.SimpleMeterRegistry.class);
            LockRuntime runtime = context.getBean(LockRuntime.class);
            runtime.lockExecutor().withLock(new LockRequest(new LockKey("secret-key"), LockMode.MUTEX, WaitPolicy.tryOnce()), () -> "ok");

            assertThat(registry.getMeters()).allSatisfy(meter ->
                assertThat(meter.getId().getTags()).noneMatch(tag -> tag.getValue().equals("secret-key"))
            );
        });
}
```

- [ ] **Step 2: Run Spring observability tests to verify failures**

Run:

```bash
mvn -q -pl distributed-lock-extension-observability-spring -am test -Dtest=DistributedLockObservabilityAutoConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL because sink toggles do not exist and runtime is always decorated when observability is enabled.

- [ ] **Step 3: Add nested logging and metrics properties**

Update `DistributedLockObservabilityProperties` with:

```java
private final Logging logging = new Logging();
private final Metrics metrics = new Metrics();

public Logging getLogging() { return logging; }
public Metrics getMetrics() { return metrics; }

public static final class Logging {
    private boolean enabled = true;
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}

public static final class Metrics {
    private boolean enabled = true;
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
```

- [ ] **Step 4: Build sinks conditionally and skip decoration without sinks**

Change `lockObservationSink(...)` to accept properties and return `NOOP` only when no sinks are active:

```java
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
```

In the bean post-processor, before decorating:

```java
if (sink == LockObservationSink.NOOP) {
    return bean;
}
```

- [ ] **Step 5: Guard debug success logging**

In `LoggingLockObservationSink.record(...)`, change success handling to:

```java
if ("success".equals(event.outcome())) {
    if (logger.isDebugEnabled()) {
        logger.debug(
            "distributed-lock {} {} outcome={} backend={} mode={} durationMicros={}",
            event.surface(),
            event.operation(),
            event.outcome(),
            event.backendId(),
            event.mode(),
            event.duration().toNanos() / 1_000L
        );
    }
    return;
}
```

- [ ] **Step 6: Run Spring observability tests**

Run:

```bash
mvn -q -pl distributed-lock-extension-observability-spring -am test -Dtest=DistributedLockObservabilityAutoConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

- [ ] **Step 7: Commit observability toggles**

```bash
git add distributed-lock-extension-observability-spring/src/main/java/com/mycorp/distributedlock/observability/springboot/config/DistributedLockObservabilityProperties.java distributed-lock-extension-observability-spring/src/main/java/com/mycorp/distributedlock/observability/springboot/config/DistributedLockObservabilityAutoConfiguration.java distributed-lock-extension-observability-spring/src/main/java/com/mycorp/distributedlock/observability/springboot/logging/LoggingLockObservationSink.java distributed-lock-extension-observability-spring/src/test/java/com/mycorp/distributedlock/observability/springboot/integration/DistributedLockObservabilityAutoConfigurationTest.java
git commit -m "feat: add observability sink toggles"
```

## Task 5: Update Spring And Observability Documentation

**Files:**
- Modify: `distributed-lock-spring-boot-starter/README.md`
- Modify: `distributed-lock-test-suite/README.md`

- [ ] **Step 1: Document Spring AOP boundaries**

Add a README section under annotation usage:

```markdown
### AOP boundaries

`@DistributedLock` is a Spring AOP interceptor and follows normal proxy rules. Interface-method and implementation-method annotations are supported for JDK and CGLIB proxies. Self-invocation inside the same bean is not intercepted. The interceptor is synchronous-only and rejects methods annotated with `@Async`, class-level `@Async`, `CompletionStage`, `Future`, and reactive `Publisher` return types before invoking user code when those boundaries are visible from the method signature or annotations.
```

- [ ] **Step 2: Document observability toggles**

Add:

```markdown
### Observability toggles

`distributed.lock.observability.enabled=false` disables runtime decoration. When observability is enabled, `distributed.lock.observability.logging.enabled=false` disables diagnostic log events and `distributed.lock.observability.metrics.enabled=false` disables Micrometer timers. `distributed.lock.observability.include-lock-key-in-logs=true` may include raw lock keys in diagnostic logs only; metrics never include raw lock keys as tags.
```

- [ ] **Step 3: Update test-suite README**

Add these maintained tests:

```markdown
- `DistributedLockProxyBoundaryTest`
- updated `DistributedLockAsyncGuardTest`
- updated `SpelLockKeyResolverTest`
- `ObservedLockThrowableTest`
- updated `DistributedLockObservabilityAutoConfigurationTest`
```

- [ ] **Step 4: Run Spring and observability regression**

Run:

```bash
mvn -q -pl distributed-lock-spring-boot-starter,distributed-lock-extension-observability,distributed-lock-extension-observability-spring -am test -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

- [ ] **Step 5: Commit docs**

```bash
git add distributed-lock-spring-boot-starter/README.md distributed-lock-test-suite/README.md
git commit -m "docs: clarify spring lock and observability boundaries"
```
