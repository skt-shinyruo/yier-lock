package com.mycorp.distributedlock.core.manager;

import com.mycorp.distributedlock.api.MutexLock;
import com.mycorp.distributedlock.core.backend.BackendLockHandle;
import com.mycorp.distributedlock.core.backend.LockBackend;
import com.mycorp.distributedlock.core.backend.LockMode;
import com.mycorp.distributedlock.core.backend.LockResource;
import com.mycorp.distributedlock.core.backend.WaitPolicy;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultLockManagerTest {

    @Test
    void sameThreadReentryShouldBeTrackedByLogicalKeyNotHandleIdentity() throws Exception {
        FakeLockBackend backend = new FakeLockBackend();
        DefaultLockManager manager = new DefaultLockManager(backend);

        MutexLock first = manager.mutex("orders:1");
        MutexLock second = manager.mutex("orders:1");

        first.lock();
        assertThat(second.tryLock(Duration.ZERO)).isTrue();

        second.unlock();
        assertThat(first.isHeldByCurrentThread()).isTrue();
    }

    private static final class FakeLockBackend implements LockBackend {
        private final ConcurrentHashMap<String, AtomicInteger> holds = new ConcurrentHashMap<>();

        @Override
        public BackendLockHandle acquire(LockResource resource, LockMode mode, WaitPolicy waitPolicy) {
            holds.computeIfAbsent(resource.key(), ignored -> new AtomicInteger()).incrementAndGet();
            return () -> resource.key();
        }

        @Override
        public void release(BackendLockHandle handle) {
            holds.computeIfPresent(handle.key(), (ignored, count) -> count.decrementAndGet() == 0 ? null : count);
        }

        @Override
        public boolean isHeldByCurrentExecution(BackendLockHandle handle) {
            return holds.containsKey(handle.key());
        }
    }
}
