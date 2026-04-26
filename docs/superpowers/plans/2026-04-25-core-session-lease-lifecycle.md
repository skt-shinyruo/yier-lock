# Core Session Lease Lifecycle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `DefaultLockSession.close()` release every unreleased lease acquired through that public session, even when the backend session does not do its own lease cleanup.

**Architecture:** `distributed-lock-core` will wrap every `BackendLockLease` in a small `SessionBoundLockLease` that coordinates lifecycle with its owning `DefaultLockSession`. `DefaultLockSession` will maintain a concurrent set of active wrapped leases, release them best-effort before closing the backend session, and keep Redis/ZooKeeper backend-local `activeLeases` as backend-specific cleanup and renewal state.

**Tech Stack:** Java 17, JUnit 5, AssertJ, Maven multi-module build, existing `LockClient` / `LockSession` / `LockLease` API and `LockBackend` / `BackendSession` / `BackendLockLease` SPI.

---

## File Map

- Create: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/client/SessionBoundLockLease.java`
  - Package-private wrapper around `BackendLockLease`.
  - Delegates metadata and validity to backend lease.
  - Coordinates `release()` with `DefaultLockSession` registration.

- Modify: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/client/DefaultLockSession.java`
  - Register wrapped leases returned from `acquire(...)`.
  - Release registered leases on `close()` before closing `BackendSession`.
  - Handle acquire/close races by releasing a backend lease acquired after session close.
  - Aggregate release and backend close failures with suppressed exceptions.

- Create: `distributed-lock-core/src/test/java/com/mycorp/distributedlock/core/client/DefaultLockSessionTest.java`
  - Focused unit tests proving core owns public session-level lease cleanup.
  - Uses a backend whose `BackendSession.close()` deliberately does not release leases.

- Modify: `distributed-lock-testkit/src/main/java/com/mycorp/distributedlock/testkit/LockClientContract.java`
  - Add shared public API contract: closing a session without closing its lease releases the lock.
  - This proves the in-memory backend passes through `DefaultLockClient` without duplicating session lease tracking.

- Do not modify: `distributed-lock-redis/src/main/java/com/mycorp/distributedlock/redis/RedisLockBackend.java`
  - Redis keeps backend-local `activeLeases` for renewal, loss propagation, and direct backend SPI cleanup.

- Do not modify: `distributed-lock-zookeeper/src/main/java/com/mycorp/distributedlock/zookeeper/ZooKeeperLockBackend.java`
  - ZooKeeper keeps backend-local `activeLeases` for session loss propagation and direct backend SPI cleanup.

## Task 1: Add Failing Lifecycle Tests

**Files:**
- Create: `distributed-lock-core/src/test/java/com/mycorp/distributedlock/core/client/DefaultLockSessionTest.java`
- Modify: `distributed-lock-testkit/src/main/java/com/mycorp/distributedlock/testkit/LockClientContract.java`

- [ ] **Step 1: Create focused failing core tests**

Create `distributed-lock-core/src/test/java/com/mycorp/distributedlock/core/client/DefaultLockSessionTest.java` with this complete content:

```java
package com.mycorp.distributedlock.core.client;

import com.mycorp.distributedlock.api.FencingToken;
import com.mycorp.distributedlock.api.LeaseState;
import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockLease;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.LockSession;
import com.mycorp.distributedlock.api.SessionState;
import com.mycorp.distributedlock.api.WaitPolicy;
import com.mycorp.distributedlock.api.exception.LockBackendException;
import com.mycorp.distributedlock.core.backend.BackendLockLease;
import com.mycorp.distributedlock.core.backend.BackendSession;
import com.mycorp.distributedlock.core.backend.LockBackend;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultLockSessionTest {

    @Test
    void closeShouldReleaseUnclosedLeaseBeforeBackendSessionClose() throws Exception {
        TrackingBackend backend = new TrackingBackend();
        DefaultLockClient client = new DefaultLockClient(backend, new SupportedLockModes(true, true));
        LockSession session = client.openSession();

        session.acquire(sampleRequest("orders:close-one"));
        session.close();

        assertThat(backend.lease(0).releaseCount()).isEqualTo(1);
        assertThat(backend.lease(0).state()).isEqualTo(LeaseState.RELEASED);
        assertThat(backend.backendCloseCount()).isEqualTo(1);
    }

    @Test
    void closeShouldReleaseEveryUnclosedLease() throws Exception {
        TrackingBackend backend = new TrackingBackend();
        DefaultLockClient client = new DefaultLockClient(backend, new SupportedLockModes(true, true));
        LockSession session = client.openSession();

        session.acquire(sampleRequest("orders:close-first"));
        session.acquire(sampleRequest("orders:close-second"));
        session.close();

        assertThat(backend.lease(0).releaseCount()).isEqualTo(1);
        assertThat(backend.lease(1).releaseCount()).isEqualTo(1);
        assertThat(backend.backendCloseCount()).isEqualTo(1);
    }

    @Test
    void manuallyReleasedLeaseShouldNotBeReleasedAgainWhenSessionCloses() throws Exception {
        TrackingBackend backend = new TrackingBackend();
        DefaultLockClient client = new DefaultLockClient(backend, new SupportedLockModes(true, true));
        LockSession session = client.openSession();
        LockLease lease = session.acquire(sampleRequest("orders:manual-release"));

        lease.release();
        session.close();

        assertThat(backend.lease(0).releaseCount()).isEqualTo(1);
        assertThat(backend.backendCloseCount()).isEqualTo(1);
    }

    @Test
    void closeShouldContinueAfterLeaseReleaseFailureAndCloseBackendSession() throws Exception {
        TrackingBackend backend = new TrackingBackend();
        DefaultLockClient client = new DefaultLockClient(backend, new SupportedLockModes(true, true));
        LockSession session = client.openSession();
        RuntimeException releaseFailure = new LockBackendException("first release failed");

        session.acquire(sampleRequest("orders:release-failure"));
        session.acquire(sampleRequest("orders:release-after-failure"));
        backend.lease(0).failRelease(releaseFailure);

        assertThatThrownBy(session::close)
            .isSameAs(releaseFailure);
        assertThat(backend.lease(0).releaseCount()).isEqualTo(1);
        assertThat(backend.lease(1).releaseCount()).isEqualTo(1);
        assertThat(backend.backendCloseCount()).isEqualTo(1);
    }

    @Test
    void closeShouldSuppressBackendCloseFailureWhenLeaseReleaseAlreadyFailed() throws Exception {
        TrackingBackend backend = new TrackingBackend();
        DefaultLockClient client = new DefaultLockClient(backend, new SupportedLockModes(true, true));
        LockSession session = client.openSession();
        RuntimeException releaseFailure = new LockBackendException("release failed");
        RuntimeException backendCloseFailure = new LockBackendException("backend close failed");

        session.acquire(sampleRequest("orders:release-and-close-fail"));
        backend.lease(0).failRelease(releaseFailure);
        backend.failBackendClose(backendCloseFailure);

        assertThatThrownBy(session::close)
            .isSameAs(releaseFailure)
            .satisfies(exception -> assertThat(exception.getSuppressed()).containsExactly(backendCloseFailure));
        assertThat(backend.backendCloseCount()).isEqualTo(1);
    }

    @Test
    void backendCloseFailureShouldSurfaceAfterLeaseCleanupSucceeds() throws Exception {
        TrackingBackend backend = new TrackingBackend();
        DefaultLockClient client = new DefaultLockClient(backend, new SupportedLockModes(true, true));
        LockSession session = client.openSession();
        RuntimeException backendCloseFailure = new LockBackendException("backend close failed");

        session.acquire(sampleRequest("orders:backend-close-failure"));
        backend.failBackendClose(backendCloseFailure);

        assertThatThrownBy(session::close)
            .isSameAs(backendCloseFailure);
        assertThat(backend.lease(0).releaseCount()).isEqualTo(1);
        assertThat(backend.backendCloseCount()).isEqualTo(1);
    }

    @Test
    void acquireShouldReleaseLateBackendLeaseWhenSessionClosesDuringAcquire() throws Exception {
        BlockingAcquireBackend backend = new BlockingAcquireBackend();
        DefaultLockClient client = new DefaultLockClient(backend, new SupportedLockModes(true, true));
        LockSession session = client.openSession();
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<Throwable> acquired = executor.submit(() -> {
                try {
                    session.acquire(sampleRequest("orders:late-acquire"));
                    return null;
                } catch (Throwable exception) {
                    return exception;
                }
            });

            assertThat(backend.awaitAcquireStarted()).isTrue();
            session.close();
            backend.completeAcquire();

            Throwable thrown = acquired.get(1, TimeUnit.SECONDS);
            assertThat(thrown).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Lock session is already closed");
            assertThat(backend.lease().releaseCount()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void acquireAfterSessionCloseShouldFail() {
        TrackingBackend backend = new TrackingBackend();
        DefaultLockClient client = new DefaultLockClient(backend, new SupportedLockModes(true, true));
        LockSession session = client.openSession();

        session.close();

        assertThatThrownBy(() -> session.acquire(sampleRequest("orders:closed")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Lock session is already closed");
    }

    private static LockRequest sampleRequest(String key) {
        return new LockRequest(
            new LockKey(key),
            LockMode.MUTEX,
            WaitPolicy.timed(Duration.ofSeconds(1))
        );
    }

    private static final class TrackingBackend implements LockBackend {
        private final List<TrackingLease> leases = new CopyOnWriteArrayList<>();
        private final AtomicInteger backendCloseCount = new AtomicInteger();
        private final AtomicReference<RuntimeException> backendCloseFailure = new AtomicReference<>();

        @Override
        public BackendSession openSession() {
            return new BackendSession() {
                @Override
                public BackendLockLease acquire(LockRequest lockRequest) {
                    TrackingLease lease = new TrackingLease(lockRequest.key(), lockRequest.mode(), new FencingToken(leases.size() + 1L));
                    leases.add(lease);
                    return lease;
                }

                @Override
                public SessionState state() {
                    return SessionState.ACTIVE;
                }

                @Override
                public void close() {
                    backendCloseCount.incrementAndGet();
                    RuntimeException failure = backendCloseFailure.get();
                    if (failure != null) {
                        throw failure;
                    }
                }
            };
        }

        TrackingLease lease(int index) {
            return leases.get(index);
        }

        int backendCloseCount() {
            return backendCloseCount.get();
        }

        void failBackendClose(RuntimeException failure) {
            backendCloseFailure.set(failure);
        }

        @Override
        public void close() {
        }
    }

    private static final class BlockingAcquireBackend implements LockBackend {
        private final CountDownLatch acquireStarted = new CountDownLatch(1);
        private final CountDownLatch completeAcquire = new CountDownLatch(1);
        private final AtomicReference<TrackingLease> lease = new AtomicReference<>();

        @Override
        public BackendSession openSession() {
            return new BackendSession() {
                @Override
                public BackendLockLease acquire(LockRequest lockRequest) throws InterruptedException {
                    acquireStarted.countDown();
                    assertThat(completeAcquire.await(1, TimeUnit.SECONDS)).isTrue();
                    TrackingLease acquired = new TrackingLease(lockRequest.key(), lockRequest.mode(), new FencingToken(1L));
                    lease.set(acquired);
                    return acquired;
                }

                @Override
                public SessionState state() {
                    return SessionState.ACTIVE;
                }

                @Override
                public void close() {
                }
            };
        }

        boolean awaitAcquireStarted() throws InterruptedException {
            return acquireStarted.await(1, TimeUnit.SECONDS);
        }

        void completeAcquire() {
            completeAcquire.countDown();
        }

        TrackingLease lease() {
            return lease.get();
        }

        @Override
        public void close() {
        }
    }

    private static final class TrackingLease implements BackendLockLease {
        private final LockKey key;
        private final LockMode mode;
        private final FencingToken fencingToken;
        private final AtomicInteger releaseCount = new AtomicInteger();
        private final AtomicReference<LeaseState> state = new AtomicReference<>(LeaseState.ACTIVE);
        private final AtomicReference<RuntimeException> releaseFailure = new AtomicReference<>();

        private TrackingLease(LockKey key, LockMode mode, FencingToken fencingToken) {
            this.key = key;
            this.mode = mode;
            this.fencingToken = fencingToken;
        }

        @Override
        public LockKey key() {
            return key;
        }

        @Override
        public LockMode mode() {
            return mode;
        }

        @Override
        public FencingToken fencingToken() {
            return fencingToken;
        }

        @Override
        public LeaseState state() {
            return state.get();
        }

        @Override
        public boolean isValid() {
            return state.get() == LeaseState.ACTIVE;
        }

        @Override
        public void release() {
            releaseCount.incrementAndGet();
            RuntimeException failure = releaseFailure.get();
            if (failure != null) {
                throw failure;
            }
            state.set(LeaseState.RELEASED);
        }

        int releaseCount() {
            return releaseCount.get();
        }

        void failRelease(RuntimeException failure) {
            releaseFailure.set(failure);
        }
    }
}
```

- [ ] **Step 2: Add the shared public contract test**

In `distributed-lock-testkit/src/main/java/com/mycorp/distributedlock/testkit/LockClientContract.java`, insert this test after `mutexShouldExcludeConcurrentSessions()`:

```java
    @Test
    void closingSessionShouldReleaseUnclosedLease() throws Exception {
        runtime = createRuntime();
        LockSession holder = runtime.lockClient().openSession();

        try {
            holder.acquire(request("inventory:session-close", LockMode.MUTEX, Duration.ofSeconds(1)));
        } finally {
            holder.close();
        }

        assertThat(tryAcquire("inventory:session-close", LockMode.MUTEX, Duration.ofMillis(200))).isTrue();
    }
```

- [ ] **Step 3: Run core tests and verify they fail before implementation**

Run:

```bash
mvn -q -pl distributed-lock-core -am test -Dtest=DefaultLockSessionTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL. At least the session close cleanup tests should fail because `DefaultLockSession.close()` only calls `backendSession.close()` and does not release leases.

- [ ] **Step 4: Run in-memory contract and verify it fails before implementation**

Run:

```bash
mvn -q -pl distributed-lock-testkit -am test -Dtest=InMemoryLockClientContractTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL in `closingSessionShouldReleaseUnclosedLease` because `InMemoryBackendSession.close()` only marks the session closed and the unclosed in-memory lease remains held.

- [ ] **Step 5: Leave the failing tests uncommitted**

Run:

```bash
git status --short distributed-lock-core/src/test/java/com/mycorp/distributedlock/core/client/DefaultLockSessionTest.java \
  distributed-lock-testkit/src/main/java/com/mycorp/distributedlock/testkit/LockClientContract.java
```

Expected: `DefaultLockSessionTest.java` is untracked and `LockClientContract.java` is modified. Do not commit yet; the core test is committed with the implementation in Task 2, and the shared contract is committed after it passes in Task 3.

## Task 2: Implement Core Session-Bound Lease Tracking

**Files:**
- Create: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/client/SessionBoundLockLease.java`
- Modify: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/client/DefaultLockSession.java`
- Test: `distributed-lock-core/src/test/java/com/mycorp/distributedlock/core/client/DefaultLockSessionTest.java`

- [ ] **Step 1: Add the lease wrapper**

Create `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/client/SessionBoundLockLease.java` with this complete content:

```java
package com.mycorp.distributedlock.core.client;

import com.mycorp.distributedlock.api.FencingToken;
import com.mycorp.distributedlock.api.LeaseState;
import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockLease;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.exception.LockOwnershipLostException;
import com.mycorp.distributedlock.core.backend.BackendLockLease;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

final class SessionBoundLockLease implements LockLease {

    private enum LifecycleState {
        ACTIVE,
        RELEASING,
        TERMINAL
    }

    private final BackendLockLease delegate;
    private final Consumer<SessionBoundLockLease> unregister;
    private final AtomicReference<LifecycleState> lifecycle = new AtomicReference<>(LifecycleState.ACTIVE);

    SessionBoundLockLease(BackendLockLease delegate, Consumer<SessionBoundLockLease> unregister) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.unregister = Objects.requireNonNull(unregister, "unregister");
    }

    @Override
    public LockKey key() {
        return delegate.key();
    }

    @Override
    public LockMode mode() {
        return delegate.mode();
    }

    @Override
    public FencingToken fencingToken() {
        return delegate.fencingToken();
    }

    @Override
    public LeaseState state() {
        return delegate.state();
    }

    @Override
    public boolean isValid() {
        return delegate.isValid();
    }

    @Override
    public void release() {
        if (!beginRelease()) {
            return;
        }

        boolean terminal = false;
        try {
            delegate.release();
            terminal = true;
        } catch (LockOwnershipLostException exception) {
            terminal = true;
            throw exception;
        } catch (RuntimeException exception) {
            terminal = delegateIsNoLongerActive();
            throw exception;
        } finally {
            finishRelease(terminal);
        }
    }

    private boolean beginRelease() {
        while (true) {
            LifecycleState current = lifecycle.get();
            if (current == LifecycleState.TERMINAL) {
                return false;
            }
            if (current == LifecycleState.RELEASING) {
                Thread.onSpinWait();
                continue;
            }
            if (lifecycle.compareAndSet(LifecycleState.ACTIVE, LifecycleState.RELEASING)) {
                return true;
            }
        }
    }

    private boolean delegateIsNoLongerActive() {
        try {
            return delegate.state() != LeaseState.ACTIVE;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private void finishRelease(boolean terminal) {
        if (terminal) {
            lifecycle.set(LifecycleState.TERMINAL);
            unregister.accept(this);
            return;
        }
        lifecycle.compareAndSet(LifecycleState.RELEASING, LifecycleState.ACTIVE);
    }
}
```

- [ ] **Step 2: Replace `DefaultLockSession` with session-level tracking**

Update `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/client/DefaultLockSession.java` to this complete content:

```java
package com.mycorp.distributedlock.core.client;

import com.mycorp.distributedlock.api.LockLease;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.LockSession;
import com.mycorp.distributedlock.api.SessionState;
import com.mycorp.distributedlock.api.exception.LockBackendException;
import com.mycorp.distributedlock.core.backend.BackendLockLease;
import com.mycorp.distributedlock.core.backend.BackendSession;

import java.util.ArrayList;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DefaultLockSession implements LockSession {

    private final SupportedLockModes supportedLockModes;
    private final BackendSession backendSession;
    private final LockRequestValidator validator;
    private final Set<SessionBoundLockLease> activeLeases = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();

    public DefaultLockSession(
        SupportedLockModes supportedLockModes,
        BackendSession backendSession,
        LockRequestValidator validator
    ) {
        this.supportedLockModes = Objects.requireNonNull(supportedLockModes, "supportedLockModes");
        this.backendSession = Objects.requireNonNull(backendSession, "backendSession");
        this.validator = Objects.requireNonNull(validator, "validator");
    }

    @Override
    public LockLease acquire(LockRequest request) throws InterruptedException {
        if (closed.get()) {
            throw new IllegalStateException("Lock session is already closed");
        }
        validator.validate(supportedLockModes, request);
        BackendLockLease backendLease = backendSession.acquire(request);
        SessionBoundLockLease lease = new SessionBoundLockLease(backendLease, this::forgetLease);
        if (!registerLease(lease)) {
            throw closeLateAcquiredLease(lease);
        }
        return lease;
    }

    @Override
    public SessionState state() {
        if (closed.get()) {
            return SessionState.CLOSED;
        }
        return backendSession.state();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        RuntimeException failure = null;
        for (SessionBoundLockLease lease : new ArrayList<>(activeLeases)) {
            failure = releaseLease(lease, failure);
        }
        failure = closeBackendSession(failure);
        if (failure != null) {
            throw failure;
        }
    }

    private boolean registerLease(SessionBoundLockLease lease) {
        if (closed.get()) {
            return false;
        }
        activeLeases.add(lease);
        if (closed.get()) {
            activeLeases.remove(lease);
            return false;
        }
        return true;
    }

    private void forgetLease(SessionBoundLockLease lease) {
        activeLeases.remove(lease);
    }

    private RuntimeException closeLateAcquiredLease(SessionBoundLockLease lease) {
        RuntimeException closedException = new IllegalStateException("Lock session is already closed");
        try {
            lease.release();
        } catch (RuntimeException exception) {
            exception.addSuppressed(closedException);
            return exception;
        }
        return closedException;
    }

    private RuntimeException releaseLease(SessionBoundLockLease lease, RuntimeException failure) {
        try {
            lease.release();
            return failure;
        } catch (RuntimeException exception) {
            return recordFailure(failure, exception);
        }
    }

    private RuntimeException closeBackendSession(RuntimeException failure) {
        try {
            backendSession.close();
            return failure;
        } catch (RuntimeException exception) {
            return recordFailure(failure, exception);
        } catch (Exception exception) {
            return recordFailure(failure, new LockBackendException("Failed to close lock session", exception));
        }
    }

    private static RuntimeException recordFailure(RuntimeException current, RuntimeException next) {
        if (current == null) {
            return next;
        }
        current.addSuppressed(next);
        return current;
    }
}
```

- [ ] **Step 3: Run the focused core tests**

Run:

```bash
mvn -q -pl distributed-lock-core -am test -Dtest=DefaultLockSessionTest,DefaultLockClientTest,DefaultLockExecutorTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS. The new `DefaultLockSessionTest` should pass, and existing client/executor tests should keep passing with wrapped leases.

- [ ] **Step 4: Commit the core implementation**

Run:

```bash
git add distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/client/DefaultLockSession.java \
  distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/client/SessionBoundLockLease.java \
  distributed-lock-core/src/test/java/com/mycorp/distributedlock/core/client/DefaultLockSessionTest.java
git commit -m "fix: release session leases in core"
```

Expected: commit succeeds with only the core implementation and core test files staged. Do not stage `distributed-lock-testkit/src/main/java/com/mycorp/distributedlock/testkit/LockClientContract.java` in this commit.

## Task 3: Verify Shared Contract and Backend Compatibility

**Files:**
- Modify: `distributed-lock-testkit/src/main/java/com/mycorp/distributedlock/testkit/LockClientContract.java`
- Test: `distributed-lock-testkit/src/test/java/com/mycorp/distributedlock/testkit/InMemoryLockClientContractTest.java`
- Test: `distributed-lock-redis/src/test/java/com/mycorp/distributedlock/redis/RedisLockBackendContractTest.java`
- Test: `distributed-lock-zookeeper/src/test/java/com/mycorp/distributedlock/zookeeper/ZooKeeperLockBackendContractTest.java`

- [ ] **Step 1: Run the in-memory public contract**

Run:

```bash
mvn -q -pl distributed-lock-testkit -am test -Dtest=InMemoryLockClientContractTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS. `closingSessionShouldReleaseUnclosedLease` should pass even though `InMemoryBackendSession.close()` does not release leases itself.

- [ ] **Step 2: Run ZooKeeper public contract regression**

Run:

```bash
mvn -q -pl distributed-lock-zookeeper -am test -Dtest=ZooKeeperLockBackendContractTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS. ZooKeeper should still pass with backend-local `activeLeases` intact and public session close behavior supplied by core.

- [ ] **Step 3: Run Redis public contract regression**

Run:

```bash
mvn -q -pl distributed-lock-redis -am test -Dtest=RedisLockBackendContractTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS when Docker is available because `RedisTestSupport` starts `redis:7-alpine` with `docker run`. If this fails because Docker is unavailable, capture the Docker error and continue only after confirming the core and in-memory contract commands passed.

- [ ] **Step 4: Run the combined focused verification**

Run:

```bash
mvn -q -pl distributed-lock-core,distributed-lock-testkit -am test -Dtest=DefaultLockSessionTest,DefaultLockClientTest,DefaultLockExecutorTest,InMemoryLockClientContractTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS. This is the required non-Docker verification for the core lifecycle change.

- [ ] **Step 5: Commit the shared contract test**

Run:

```bash
git add distributed-lock-testkit/src/main/java/com/mycorp/distributedlock/testkit/LockClientContract.java
git commit -m "test: cover session close lease release contract"
```

Expected: commit succeeds with only `LockClientContract.java` staged.

## Task 4: Final Review

**Files:**
- Review: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/client/DefaultLockSession.java`
- Review: `distributed-lock-core/src/main/java/com/mycorp/distributedlock/core/client/SessionBoundLockLease.java`
- Review: `distributed-lock-core/src/test/java/com/mycorp/distributedlock/core/client/DefaultLockSessionTest.java`
- Review: `distributed-lock-testkit/src/main/java/com/mycorp/distributedlock/testkit/LockClientContract.java`

- [ ] **Step 1: Check that only intended files changed**

Run:

```bash
git status --short
```

Expected: only unrelated pre-existing worktree changes remain. The lifecycle implementation files should be committed.

- [ ] **Step 2: Inspect the implementation diff**

Run:

```bash
git show --stat --oneline HEAD~1..HEAD
```

Expected: the recent commits show `DefaultLockSession`, `SessionBoundLockLease`, `DefaultLockSessionTest`, and `LockClientContract` changes.

- [ ] **Step 3: Run whitespace check**

Run:

```bash
git diff --check HEAD~2..HEAD
```

Expected: no whitespace errors.

- [ ] **Step 4: Confirm spec coverage**

Verify these statements against the code:

```text
DefaultLockSession.acquire wraps BackendLockLease in SessionBoundLockLease.
DefaultLockSession.close releases active SessionBoundLockLease instances before backendSession.close.
SessionBoundLockLease unregisters after successful release or ownership loss.
SessionBoundLockLease allows retry after non-terminal backend release failure.
LockClientContract covers close-without-lease-close through the public API.
RedisLockBackend and ZooKeeperLockBackend backend-local activeLeases were not removed.
```

Expected: every statement is true.
