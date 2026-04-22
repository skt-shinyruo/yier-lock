# Distributed Lock Observability Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add optional, extension-based lock observability and diagnostics for programmatic and Spring Boot usage without pushing metrics or tracing contracts into the kernel API.

**Architecture:** Create a backend-neutral `distributed-lock-extension-observability` module that decorates `LockRuntime`, `LockClient`, `LockSession`, and `LockExecutor`, and emits structured observation events through a small sink interface. Add a companion `distributed-lock-extension-observability-spring` module that adapts those events to Micrometer meters plus controlled diagnostic logging, while keeping lock-key cardinality out of metrics by default.

**Tech Stack:** Java 17, Maven reactor modules, JUnit 5, AssertJ, Mockito, Spring Boot 3.2, Micrometer, SLF4J

---

## File Map

- Modify: `pom.xml`
  Add the new extension modules to the reactor and dependency management.
- Create: `distributed-lock-extension-observability/pom.xml`
  Define the backend-neutral observability extension module.
- Create: `distributed-lock-extension-observability-spring/pom.xml`
  Define the Spring Boot observability bridge module.
- Create: `distributed-lock-extension-observability/src/main/java/com/mycorp/distributedlock/observability/LockObservationEvent.java`
  Represent one acquisition or execution outcome with low-cardinality dimensions.
- Create: `distributed-lock-extension-observability/src/main/java/com/mycorp/distributedlock/observability/LockObservationSink.java`
  Sink interface for emitting observation events.
- Create: `distributed-lock-extension-observability/src/main/java/com/mycorp/distributedlock/observability/LockObservationSupport.java`
  Shared helpers for clocks, outcome mapping, and key redaction.
- Create: `distributed-lock-extension-observability/src/main/java/com/mycorp/distributedlock/observability/ObservedLockRuntime.java`
  Decorate a `LockRuntime` with observed client and executor instances.
- Create: `distributed-lock-extension-observability/src/main/java/com/mycorp/distributedlock/observability/ObservedLockClient.java`
  Decorate `LockClient` and return observed sessions.
- Create: `distributed-lock-extension-observability/src/main/java/com/mycorp/distributedlock/observability/ObservedLockSession.java`
  Time `acquire(...)` and record success, timeout, interruption, and backend-failure outcomes.
- Create: `distributed-lock-extension-observability/src/main/java/com/mycorp/distributedlock/observability/ObservedLockExecutor.java`
  Time `withLock(...)` scope execution and record success/failure outcomes.
- Create: `distributed-lock-extension-observability/src/test/java/com/mycorp/distributedlock/observability/ObservedLockSessionTest.java`
  Verify acquisition events for success and timeout flows.
- Create: `distributed-lock-extension-observability/src/test/java/com/mycorp/distributedlock/observability/ObservedLockExecutorTest.java`
  Verify execution-scope events for success and failure flows.
- Create: `distributed-lock-extension-observability-spring/src/main/java/com/mycorp/distributedlock/observability/springboot/config/DistributedLockObservabilityProperties.java`
  Define observability-specific Boot properties.
- Create: `distributed-lock-extension-observability-spring/src/main/java/com/mycorp/distributedlock/observability/springboot/config/DistributedLockObservabilityAutoConfiguration.java`
  Auto-configure Micrometer and logging sinks plus observed `LockRuntime`, `LockClient`, and `LockExecutor` beans.
- Create: `distributed-lock-extension-observability-spring/src/main/java/com/mycorp/distributedlock/observability/springboot/metrics/MicrometerLockObservationSink.java`
  Translate observation events into timers and counters.
- Create: `distributed-lock-extension-observability-spring/src/main/java/com/mycorp/distributedlock/observability/springboot/logging/LoggingLockObservationSink.java`
  Emit structured diagnostic logs with optional lock-key inclusion.
- Create: `distributed-lock-extension-observability-spring/src/main/java/com/mycorp/distributedlock/observability/springboot/support/CompositeLockObservationSink.java`
  Fan out events to logging and metrics sinks.
- Create: `distributed-lock-extension-observability-spring/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
  Register the Spring observability bridge with Boot 3 auto-configuration discovery.
- Create: `distributed-lock-extension-observability-spring/src/test/java/com/mycorp/distributedlock/observability/springboot/integration/DistributedLockObservabilityAutoConfigurationTest.java`
  Verify observed beans and meter registration inside an application context.
- Modify: `distributed-lock-spring-boot-starter/README.md`
  Document how the generic starter stays observability-free and how the new extension module is added.
- Modify: `distributed-lock-test-suite/README.md`
  Add the new focused observability verification commands.

### Task 1: Scaffold the Observability Extension Modules

**Files:**
- Modify: `pom.xml`
- Create: `distributed-lock-extension-observability/pom.xml`
- Create: `distributed-lock-extension-observability-spring/pom.xml`

- [ ] **Step 1: Add the extension modules to the root reactor**

Update the `<modules>` section in `pom.xml` to insert the new modules after the starter and backend Spring auto-config modules:

```xml
<modules>
    <module>distributed-lock-api</module>
    <module>distributed-lock-core</module>
    <module>distributed-lock-runtime</module>
    <module>distributed-lock-testkit</module>
    <module>distributed-lock-redis</module>
    <module>distributed-lock-zookeeper</module>
    <module>distributed-lock-spring-boot-starter</module>
    <module>distributed-lock-redis-spring-boot-autoconfigure</module>
    <module>distributed-lock-zookeeper-spring-boot-autoconfigure</module>
    <module>distributed-lock-extension-observability</module>
    <module>distributed-lock-extension-observability-spring</module>
    <module>distributed-lock-examples</module>
</modules>
```

Also add dependency-management entries for:

```xml
<dependency>
    <groupId>com.mycorp</groupId>
    <artifactId>distributed-lock-extension-observability</artifactId>
    <version>${project.version}</version>
</dependency>
<dependency>
    <groupId>com.mycorp</groupId>
    <artifactId>distributed-lock-extension-observability-spring</artifactId>
    <version>${project.version}</version>
</dependency>
```

- [ ] **Step 2: Create the minimal module `pom.xml` files**

Create `distributed-lock-extension-observability/pom.xml`:

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.mycorp</groupId>
        <artifactId>distributed-lock</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </parent>

    <artifactId>distributed-lock-extension-observability</artifactId>
    <name>Distributed Lock Observability Extension</name>
    <description>Backend-neutral observability decorators for distributed lock 2.0</description>

    <dependencies>
        <dependency>
            <groupId>com.mycorp</groupId>
            <artifactId>distributed-lock-api</artifactId>
        </dependency>
        <dependency>
            <groupId>com.mycorp</groupId>
            <artifactId>distributed-lock-core</artifactId>
        </dependency>
        <dependency>
            <groupId>com.mycorp</groupId>
            <artifactId>distributed-lock-runtime</artifactId>
        </dependency>
        <dependency>
            <groupId>org.slf4j</groupId>
            <artifactId>slf4j-api</artifactId>
        </dependency>

        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.mockito</groupId>
            <artifactId>mockito-core</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.mockito</groupId>
            <artifactId>mockito-junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.assertj</groupId>
            <artifactId>assertj-core</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

Create `distributed-lock-extension-observability-spring/pom.xml`:

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.mycorp</groupId>
        <artifactId>distributed-lock</artifactId>
        <version>1.0.0-SNAPSHOT</version>
        <relativePath>../pom.xml</relativePath>
    </parent>

    <artifactId>distributed-lock-extension-observability-spring</artifactId>
    <name>Distributed Lock Observability Spring Extension</name>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-autoconfigure</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-configuration-processor</artifactId>
            <optional>true</optional>
        </dependency>
        <dependency>
            <groupId>io.micrometer</groupId>
            <artifactId>micrometer-core</artifactId>
        </dependency>
        <dependency>
            <groupId>com.mycorp</groupId>
            <artifactId>distributed-lock-extension-observability</artifactId>
        </dependency>
        <dependency>
            <groupId>com.mycorp</groupId>
            <artifactId>distributed-lock-spring-boot-starter</artifactId>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>com.mycorp</groupId>
            <artifactId>distributed-lock-testkit</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

- [ ] **Step 3: Run reactor validation for the new module graph**

Run:

```bash
mvn -q -pl distributed-lock-extension-observability,distributed-lock-extension-observability-spring -am -DskipTests validate
```

Expected: PASS. Maven recognizes the new modules and resolves the declared dependencies.

- [ ] **Step 4: Commit the module scaffolding**

Run:

```bash
git add pom.xml \
        distributed-lock-extension-observability/pom.xml \
        distributed-lock-extension-observability-spring/pom.xml
git commit -m "build: add observability extension modules"
```

### Task 2: Add Backend-Neutral Observation Events and Decorators

**Files:**
- Create: `distributed-lock-extension-observability/src/main/java/com/mycorp/distributedlock/observability/LockObservationEvent.java`
- Create: `distributed-lock-extension-observability/src/main/java/com/mycorp/distributedlock/observability/LockObservationSink.java`
- Create: `distributed-lock-extension-observability/src/main/java/com/mycorp/distributedlock/observability/LockObservationSupport.java`
- Create: `distributed-lock-extension-observability/src/main/java/com/mycorp/distributedlock/observability/ObservedLockRuntime.java`
- Create: `distributed-lock-extension-observability/src/main/java/com/mycorp/distributedlock/observability/ObservedLockClient.java`
- Create: `distributed-lock-extension-observability/src/main/java/com/mycorp/distributedlock/observability/ObservedLockSession.java`
- Create: `distributed-lock-extension-observability/src/main/java/com/mycorp/distributedlock/observability/ObservedLockExecutor.java`
- Create: `distributed-lock-extension-observability/src/test/java/com/mycorp/distributedlock/observability/ObservedLockSessionTest.java`
- Create: `distributed-lock-extension-observability/src/test/java/com/mycorp/distributedlock/observability/ObservedLockExecutorTest.java`

- [ ] **Step 1: Write the failing decorator tests**

Create `distributed-lock-extension-observability/src/test/java/com/mycorp/distributedlock/observability/ObservedLockSessionTest.java`:

```java
package com.mycorp.distributedlock.observability;

import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockLease;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.WaitPolicy;
import com.mycorp.distributedlock.api.exception.LockAcquisitionTimeoutException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ObservedLockSessionTest {

    @Test
    void acquireShouldRecordSuccessWithoutPublishingTheRawKeyByDefault() throws Exception {
        LockRequest request = request("orders:42", LockMode.MUTEX);
        LockLease lease = mock(LockLease.class);
        com.mycorp.distributedlock.api.LockSession delegate = mock(com.mycorp.distributedlock.api.LockSession.class);
        when(delegate.acquire(request)).thenReturn(lease);

        List<LockObservationEvent> events = new ArrayList<>();
        ObservedLockSession session = new ObservedLockSession(delegate, events::add, "redis", false);

        assertThat(session.acquire(request)).isSameAs(lease);
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.surface()).isEqualTo("client");
            assertThat(event.operation()).isEqualTo("acquire");
            assertThat(event.outcome()).isEqualTo("success");
            assertThat(event.backendId()).isEqualTo("redis");
            assertThat(event.mode()).isEqualTo(LockMode.MUTEX);
            assertThat(event.key()).isNull();
        });
    }

    @Test
    void acquireShouldRecordTimeoutOutcome() throws Exception {
        LockRequest request = request("inventory:7", LockMode.WRITE);
        com.mycorp.distributedlock.api.LockSession delegate = mock(com.mycorp.distributedlock.api.LockSession.class);
        when(delegate.acquire(request)).thenThrow(new LockAcquisitionTimeoutException("Timed out"));

        List<LockObservationEvent> events = new ArrayList<>();
        ObservedLockSession session = new ObservedLockSession(delegate, events::add, "zookeeper", true);

        assertThatThrownBy(() -> session.acquire(request))
            .isInstanceOf(LockAcquisitionTimeoutException.class);

        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.outcome()).isEqualTo("timeout");
            assertThat(event.key()).isEqualTo("inventory:7");
            assertThat(event.mode()).isEqualTo(LockMode.WRITE);
        });
    }

    private static LockRequest request(String key, LockMode mode) {
        return new LockRequest(new LockKey(key), mode, WaitPolicy.timed(Duration.ofSeconds(1)));
    }
}
```

Create `distributed-lock-extension-observability/src/test/java/com/mycorp/distributedlock/observability/ObservedLockExecutorTest.java`:

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

class ObservedLockExecutorTest {

    @Test
    void withLockShouldRecordSuccessfulScopeCompletion() throws Exception {
        List<LockObservationEvent> events = new ArrayList<>();
        LockExecutor executor = new ObservedLockExecutor((request, action) -> action.get(), events::add, "redis", false);

        String result = executor.withLock(sampleRequest(), () -> "ok");

        assertThat(result).isEqualTo("ok");
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.surface()).isEqualTo("executor");
            assertThat(event.operation()).isEqualTo("scope");
            assertThat(event.outcome()).isEqualTo("success");
        });
    }

    @Test
    void withLockShouldRecordFailedScopeCompletion() {
        List<LockObservationEvent> events = new ArrayList<>();
        LockExecutor executor = new ObservedLockExecutor((request, action) -> action.get(), events::add, "redis", true);

        assertThatThrownBy(() -> executor.withLock(sampleRequest(), () -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.outcome()).isEqualTo("failure");
            assertThat(event.key()).isEqualTo("orders:42");
            assertThat(event.error()).isInstanceOf(IllegalStateException.class);
        });
    }

    private static LockRequest sampleRequest() {
        return new LockRequest(
            new LockKey("orders:42"),
            LockMode.MUTEX,
            WaitPolicy.timed(Duration.ofSeconds(1))
        );
    }
}
```

- [ ] **Step 2: Run the focused extension tests to verify they fail**

Run:

```bash
mvn -q -pl distributed-lock-extension-observability -am test -Dtest=ObservedLockSessionTest,ObservedLockExecutorTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL because the observability classes do not exist yet.

- [ ] **Step 3: Implement the observation model and wrappers**

Create `distributed-lock-extension-observability/src/main/java/com/mycorp/distributedlock/observability/LockObservationEvent.java`:

```java
package com.mycorp.distributedlock.observability;

import com.mycorp.distributedlock.api.LockMode;

import java.time.Duration;

public record LockObservationEvent(
    String backendId,
    String surface,
    String operation,
    String outcome,
    LockMode mode,
    String key,
    Duration duration,
    Throwable error
) {
}
```

Create `distributed-lock-extension-observability/src/main/java/com/mycorp/distributedlock/observability/LockObservationSink.java`:

```java
package com.mycorp.distributedlock.observability;

@FunctionalInterface
public interface LockObservationSink {

    LockObservationSink NOOP = event -> { };

    void record(LockObservationEvent event);
}
```

Create `distributed-lock-extension-observability/src/main/java/com/mycorp/distributedlock/observability/LockObservationSupport.java`:

```java
package com.mycorp.distributedlock.observability;

import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.exception.LockAcquisitionTimeoutException;

import java.time.Duration;

final class LockObservationSupport {

    private LockObservationSupport() {
    }

    static Duration durationSince(long startedNanos) {
        return Duration.ofNanos(System.nanoTime() - startedNanos);
    }

    static String keyFor(LockRequest request, boolean includeKey) {
        return includeKey ? request.key().value() : null;
    }

    static String outcomeFor(Throwable throwable) {
        if (throwable == null) {
            return "success";
        }
        if (throwable instanceof LockAcquisitionTimeoutException) {
            return "timeout";
        }
        if (throwable instanceof InterruptedException) {
            return "interrupted";
        }
        return "failure";
    }
}
```

Create `distributed-lock-extension-observability/src/main/java/com/mycorp/distributedlock/observability/ObservedLockSession.java`:

```java
package com.mycorp.distributedlock.observability;

import com.mycorp.distributedlock.api.LockLease;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.LockSession;
import com.mycorp.distributedlock.api.SessionState;

import java.util.Objects;

public final class ObservedLockSession implements LockSession {
    private final LockSession delegate;
    private final LockObservationSink sink;
    private final String backendId;
    private final boolean includeKey;

    public ObservedLockSession(LockSession delegate, LockObservationSink sink, String backendId, boolean includeKey) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.backendId = backendId;
        this.includeKey = includeKey;
    }

    @Override
    public LockLease acquire(LockRequest request) throws InterruptedException {
        long startedNanos = System.nanoTime();
        try {
            LockLease lease = delegate.acquire(request);
            sink.record(new LockObservationEvent(
                backendId,
                "client",
                "acquire",
                "success",
                request.mode(),
                LockObservationSupport.keyFor(request, includeKey),
                LockObservationSupport.durationSince(startedNanos),
                null
            ));
            return lease;
        } catch (InterruptedException exception) {
            sink.record(new LockObservationEvent(
                backendId,
                "client",
                "acquire",
                "interrupted",
                request.mode(),
                LockObservationSupport.keyFor(request, includeKey),
                LockObservationSupport.durationSince(startedNanos),
                exception
            ));
            throw exception;
        } catch (RuntimeException exception) {
            sink.record(new LockObservationEvent(
                backendId,
                "client",
                "acquire",
                LockObservationSupport.outcomeFor(exception),
                request.mode(),
                LockObservationSupport.keyFor(request, includeKey),
                LockObservationSupport.durationSince(startedNanos),
                exception
            ));
            throw exception;
        }
    }

    @Override
    public SessionState state() {
        return delegate.state();
    }

    @Override
    public void close() {
        delegate.close();
    }
}
```

Create `distributed-lock-extension-observability/src/main/java/com/mycorp/distributedlock/observability/ObservedLockExecutor.java`:

```java
package com.mycorp.distributedlock.observability;

import com.mycorp.distributedlock.api.LockExecutor;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.LockedSupplier;

import java.util.Objects;

public final class ObservedLockExecutor implements LockExecutor {
    private final LockExecutor delegate;
    private final LockObservationSink sink;
    private final String backendId;
    private final boolean includeKey;

    public ObservedLockExecutor(LockExecutor delegate, LockObservationSink sink, String backendId, boolean includeKey) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.backendId = backendId;
        this.includeKey = includeKey;
    }

    @Override
    public <T> T withLock(LockRequest request, LockedSupplier<T> action) throws Exception {
        long startedNanos = System.nanoTime();
        try {
            T result = delegate.withLock(request, action);
            sink.record(new LockObservationEvent(
                backendId,
                "executor",
                "scope",
                "success",
                request.mode(),
                LockObservationSupport.keyFor(request, includeKey),
                LockObservationSupport.durationSince(startedNanos),
                null
            ));
            return result;
        } catch (Exception exception) {
            sink.record(new LockObservationEvent(
                backendId,
                "executor",
                "scope",
                LockObservationSupport.outcomeFor(exception),
                request.mode(),
                LockObservationSupport.keyFor(request, includeKey),
                LockObservationSupport.durationSince(startedNanos),
                exception
            ));
            throw exception;
        }
    }
}
```

Create `distributed-lock-extension-observability/src/main/java/com/mycorp/distributedlock/observability/ObservedLockClient.java`:

```java
package com.mycorp.distributedlock.observability;

import com.mycorp.distributedlock.api.LockClient;
import com.mycorp.distributedlock.api.LockSession;

import java.util.Objects;

public final class ObservedLockClient implements LockClient {
    private final LockClient delegate;
    private final LockObservationSink sink;
    private final String backendId;
    private final boolean includeKey;

    public ObservedLockClient(LockClient delegate, LockObservationSink sink, String backendId, boolean includeKey) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.backendId = backendId;
        this.includeKey = includeKey;
    }

    @Override
    public LockSession openSession() {
        return new ObservedLockSession(delegate.openSession(), sink, backendId, includeKey);
    }

    @Override
    public void close() {
        delegate.close();
    }
}
```

Create `distributed-lock-extension-observability/src/main/java/com/mycorp/distributedlock/observability/ObservedLockRuntime.java`:

```java
package com.mycorp.distributedlock.observability;

import com.mycorp.distributedlock.api.LockClient;
import com.mycorp.distributedlock.api.LockExecutor;
import com.mycorp.distributedlock.core.client.DefaultLockExecutor;
import com.mycorp.distributedlock.runtime.LockRuntime;

import java.util.Objects;

public final class ObservedLockRuntime implements LockRuntime {
    private final LockRuntime delegate;
    private final LockClient lockClient;
    private final LockExecutor lockExecutor;

    private ObservedLockRuntime(LockRuntime delegate, LockObservationSink sink, String backendId, boolean includeKey) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.lockClient = new ObservedLockClient(delegate.lockClient(), sink, backendId, includeKey);
        this.lockExecutor = new ObservedLockExecutor(new DefaultLockExecutor(lockClient), sink, backendId, includeKey);
    }

    public static ObservedLockRuntime decorate(
        LockRuntime delegate,
        LockObservationSink sink,
        String backendId,
        boolean includeKey
    ) {
        return new ObservedLockRuntime(delegate, sink, backendId, includeKey);
    }

    @Override
    public LockClient lockClient() {
        return lockClient;
    }

    @Override
    public LockExecutor lockExecutor() {
        return lockExecutor;
    }

    @Override
    public void close() {
        delegate.close();
    }
}
```

- [ ] **Step 4: Run the focused extension tests to verify they pass**

Run:

```bash
mvn -q -pl distributed-lock-extension-observability -am test -Dtest=ObservedLockSessionTest,ObservedLockExecutorTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS. The decorators emit stable observation events for success and failure paths.

- [ ] **Step 5: Commit the backend-neutral observability layer**

Run:

```bash
git add distributed-lock-extension-observability/src/main/java/com/mycorp/distributedlock/observability \
        distributed-lock-extension-observability/src/test/java/com/mycorp/distributedlock/observability
git commit -m "feat: add backend-neutral lock observability decorators"
```

### Task 3: Add the Spring Boot Micrometer and Logging Bridge

**Files:**
- Create: `distributed-lock-extension-observability-spring/src/main/java/com/mycorp/distributedlock/observability/springboot/config/DistributedLockObservabilityProperties.java`
- Create: `distributed-lock-extension-observability-spring/src/main/java/com/mycorp/distributedlock/observability/springboot/config/DistributedLockObservabilityAutoConfiguration.java`
- Create: `distributed-lock-extension-observability-spring/src/main/java/com/mycorp/distributedlock/observability/springboot/metrics/MicrometerLockObservationSink.java`
- Create: `distributed-lock-extension-observability-spring/src/main/java/com/mycorp/distributedlock/observability/springboot/logging/LoggingLockObservationSink.java`
- Create: `distributed-lock-extension-observability-spring/src/main/java/com/mycorp/distributedlock/observability/springboot/support/CompositeLockObservationSink.java`
- Create: `distributed-lock-extension-observability-spring/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Create: `distributed-lock-extension-observability-spring/src/test/java/com/mycorp/distributedlock/observability/springboot/integration/DistributedLockObservabilityAutoConfigurationTest.java`

- [ ] **Step 1: Write the failing Spring integration test**

Create `distributed-lock-extension-observability-spring/src/test/java/com/mycorp/distributedlock/observability/springboot/integration/DistributedLockObservabilityAutoConfigurationTest.java`:

```java
package com.mycorp.distributedlock.observability.springboot.integration;

import com.mycorp.distributedlock.api.LockExecutor;
import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.WaitPolicy;
import com.mycorp.distributedlock.observability.springboot.config.DistributedLockObservabilityAutoConfiguration;
import com.mycorp.distributedlock.runtime.spi.BackendModule;
import com.mycorp.distributedlock.springboot.config.DistributedLockAutoConfiguration;
import com.mycorp.distributedlock.testkit.support.InMemoryBackendModule;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class DistributedLockObservabilityAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(
            AopAutoConfiguration.class,
            DistributedLockAutoConfiguration.class,
            DistributedLockObservabilityAutoConfiguration.class
        ))
        .withUserConfiguration(TestConfiguration.class)
        .withPropertyValues(
            "distributed.lock.enabled=true",
            "distributed.lock.backend=in-memory",
            "distributed.lock.observability.enabled=true"
        );

    @Test
    void shouldRecordAcquireAndScopeMeters() {
        contextRunner.run(context -> {
            LockExecutor executor = context.getBean(LockExecutor.class);
            SimpleMeterRegistry registry = context.getBean(SimpleMeterRegistry.class);
            String value;
            try {
                value = executor.withLock(
                    new LockRequest(
                        new LockKey("orders:42"),
                        LockMode.MUTEX,
                        WaitPolicy.timed(Duration.ofSeconds(1))
                    ),
                    () -> "ok"
                );
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }

            assertThat(value).isEqualTo("ok");
            assertThat(registry.find("distributed.lock.acquire").timer()).isNotNull();
            assertThat(registry.find("distributed.lock.scope").timer()).isNotNull();
            assertThat(registry.find("distributed.lock.acquire").tags("outcome", "success").timer().count()).isEqualTo(1);
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class TestConfiguration {
        @Bean
        BackendModule inMemoryBackendModule() {
            return new InMemoryBackendModule("in-memory");
        }

        @Bean
        SimpleMeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }
}
```

- [ ] **Step 2: Run the focused Spring observability test to verify it fails**

Run:

```bash
mvn -q -pl distributed-lock-extension-observability-spring -am test -Dtest=DistributedLockObservabilityAutoConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL because the Spring observability auto-configuration and Micrometer sink classes do not exist yet.

- [ ] **Step 3: Implement the Spring observability bridge**

Create `distributed-lock-extension-observability-spring/src/main/java/com/mycorp/distributedlock/observability/springboot/config/DistributedLockObservabilityProperties.java`:

```java
package com.mycorp.distributedlock.observability.springboot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "distributed.lock.observability")
public class DistributedLockObservabilityProperties {
    private boolean enabled = true;
    private boolean includeLockKeyInLogs;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isIncludeLockKeyInLogs() {
        return includeLockKeyInLogs;
    }

    public void setIncludeLockKeyInLogs(boolean includeLockKeyInLogs) {
        this.includeLockKeyInLogs = includeLockKeyInLogs;
    }
}
```

Create `distributed-lock-extension-observability-spring/src/main/java/com/mycorp/distributedlock/observability/springboot/support/CompositeLockObservationSink.java`:

```java
package com.mycorp.distributedlock.observability.springboot.support;

import com.mycorp.distributedlock.observability.LockObservationEvent;
import com.mycorp.distributedlock.observability.LockObservationSink;

import java.util.List;

public final class CompositeLockObservationSink implements LockObservationSink {
    private final List<LockObservationSink> delegates;

    public CompositeLockObservationSink(List<LockObservationSink> delegates) {
        this.delegates = List.copyOf(delegates);
    }

    @Override
    public void record(LockObservationEvent event) {
        delegates.forEach(delegate -> delegate.record(event));
    }
}
```

Create `distributed-lock-extension-observability-spring/src/main/java/com/mycorp/distributedlock/observability/springboot/metrics/MicrometerLockObservationSink.java`:

```java
package com.mycorp.distributedlock.observability.springboot.metrics;

import com.mycorp.distributedlock.observability.LockObservationEvent;
import com.mycorp.distributedlock.observability.LockObservationSink;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;

import java.util.Objects;

public final class MicrometerLockObservationSink implements LockObservationSink {
    private final MeterRegistry meterRegistry;

    public MicrometerLockObservationSink(MeterRegistry meterRegistry) {
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
    }

    @Override
    public void record(LockObservationEvent event) {
        Tags tags = Tags.of(
            "backend", event.backendId() == null ? "unknown" : event.backendId(),
            "surface", event.surface(),
            "operation", event.operation(),
            "outcome", event.outcome(),
            "mode", event.mode() == null ? "unknown" : event.mode().name().toLowerCase()
        );
        meterRegistry.timer("distributed.lock." + event.operation(), tags)
            .record(event.duration());
    }
}
```

Create `distributed-lock-extension-observability-spring/src/main/java/com/mycorp/distributedlock/observability/springboot/logging/LoggingLockObservationSink.java`:

```java
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
```

Create `distributed-lock-extension-observability-spring/src/main/java/com/mycorp/distributedlock/observability/springboot/config/DistributedLockObservabilityAutoConfiguration.java`:

```java
package com.mycorp.distributedlock.observability.springboot.config;

import com.mycorp.distributedlock.api.LockClient;
import com.mycorp.distributedlock.api.LockExecutor;
import com.mycorp.distributedlock.observability.LockObservationSink;
import com.mycorp.distributedlock.observability.ObservedLockRuntime;
import com.mycorp.distributedlock.observability.springboot.logging.LoggingLockObservationSink;
import com.mycorp.distributedlock.observability.springboot.metrics.MicrometerLockObservationSink;
import com.mycorp.distributedlock.observability.springboot.support.CompositeLockObservationSink;
import com.mycorp.distributedlock.runtime.LockRuntime;
import com.mycorp.distributedlock.springboot.config.DistributedLockProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.ArrayList;
import java.util.List;

@AutoConfiguration(after = com.mycorp.distributedlock.springboot.config.DistributedLockAutoConfiguration.class)
@EnableConfigurationProperties(DistributedLockObservabilityProperties.class)
@ConditionalOnProperty(prefix = "distributed.lock.observability", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DistributedLockObservabilityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public LockObservationSink lockObservationSink(ObjectProvider<MeterRegistry> meterRegistry) {
        List<LockObservationSink> sinks = new ArrayList<>();
        meterRegistry.ifAvailable(registry -> sinks.add(new MicrometerLockObservationSink(registry)));
        sinks.add(new LoggingLockObservationSink());
        return new CompositeLockObservationSink(sinks);
    }

    @Bean("observedLockRuntime")
    @Primary
    @ConditionalOnBean(LockRuntime.class)
    public LockRuntime observedLockRuntime(
        LockRuntime runtime,
        LockObservationSink sink,
        DistributedLockProperties lockProperties,
        DistributedLockObservabilityProperties observabilityProperties
    ) {
        return ObservedLockRuntime.decorate(
            runtime,
            sink,
            lockProperties.getBackend(),
            observabilityProperties.isIncludeLockKeyInLogs()
        );
    }

    @Bean
    @Primary
    @ConditionalOnBean(name = "observedLockRuntime")
    public LockClient observedLockClient(@Qualifier("observedLockRuntime") LockRuntime runtime) {
        return runtime.lockClient();
    }

    @Bean
    @Primary
    @ConditionalOnBean(name = "observedLockRuntime")
    public LockExecutor observedLockExecutor(@Qualifier("observedLockRuntime") LockRuntime runtime) {
        return runtime.lockExecutor();
    }
}
```

Set `distributed-lock-extension-observability-spring/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` to:

```text
com.mycorp.distributedlock.observability.springboot.config.DistributedLockObservabilityAutoConfiguration
```

- [ ] **Step 4: Run the focused Spring observability test to verify it passes**

Run:

```bash
mvn -q -pl distributed-lock-extension-observability-spring -am test -Dtest=DistributedLockObservabilityAutoConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS. The application context exposes observed lock beans and Micrometer timers record successful lock usage.

- [ ] **Step 5: Commit the Spring observability bridge**

Run:

```bash
git add distributed-lock-extension-observability-spring/src/main/java/com/mycorp/distributedlock/observability/springboot \
        distributed-lock-extension-observability-spring/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports \
        distributed-lock-extension-observability-spring/src/test/java/com/mycorp/distributedlock/observability/springboot/integration
git commit -m "feat: add spring lock observability bridge"
```

### Task 4: Document and Verify the New Observability Path

**Files:**
- Modify: `distributed-lock-spring-boot-starter/README.md`
- Modify: `distributed-lock-test-suite/README.md`

- [ ] **Step 1: Update the starter README to keep the core/extension boundary explicit**

Append this section to `distributed-lock-spring-boot-starter/README.md` after the existing scope and dependency sections:

```markdown
## Observability extension

The generic starter intentionally does not define metrics, tracing, or actuator contracts.
If you want lock metrics and diagnostic logs, add the optional Spring observability extension:

~~~xml
<dependency>
  <groupId>com.mycorp</groupId>
  <artifactId>distributed-lock-extension-observability-spring</artifactId>
</dependency>
~~~

This extension decorates `LockRuntime`, `LockClient`, and `LockExecutor` without changing the kernel API.
It records low-cardinality timers such as `distributed.lock.acquire` and `distributed.lock.scope`.
Raw lock keys stay out of metrics by default; `distributed.lock.observability.include-lock-key-in-logs=true` only affects warning logs.
```

- [ ] **Step 2: Add observability-focused verification commands to the test-suite README**

Append this command block to `distributed-lock-test-suite/README.md`:

```markdown
# Observability extension verification
mvn -pl distributed-lock-extension-observability -am test \
  -Dtest=ObservedLockSessionTest,ObservedLockExecutorTest \
  -Dsurefire.failIfNoSpecifiedTests=false

mvn -pl distributed-lock-extension-observability-spring -am test \
  -Dtest=DistributedLockObservabilityAutoConfigurationTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

- [ ] **Step 3: Run the focused verification commands and a reactor validate pass**

Run:

```bash
mvn -q -pl distributed-lock-extension-observability -am test -Dtest=ObservedLockSessionTest,ObservedLockExecutorTest -Dsurefire.failIfNoSpecifiedTests=false
mvn -q -pl distributed-lock-extension-observability-spring -am test -Dtest=DistributedLockObservabilityAutoConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false
mvn -q -pl distributed-lock-spring-boot-starter,distributed-lock-extension-observability,distributed-lock-extension-observability-spring -am -DskipTests validate
```

Expected: PASS. The documentation now points users to the extension, and the focused observability suites stay green.

- [ ] **Step 4: Commit the documentation and verification updates**

Run:

```bash
git add distributed-lock-spring-boot-starter/README.md \
        distributed-lock-test-suite/README.md
git commit -m "docs: document lock observability extension"
```
