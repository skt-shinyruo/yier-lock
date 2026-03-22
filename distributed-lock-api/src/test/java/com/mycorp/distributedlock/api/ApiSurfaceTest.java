package com.mycorp.distributedlock.api;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ApiSurfaceTest {

    @Test
    void lockManagerAndMutexLockShouldMatchTheApproved2xShape() throws Exception {
        assertThat(LockManager.class.getInterfaces()).isEmpty();
        assertThat(MutexLock.class.getInterfaces()).containsExactly(AutoCloseable.class);
        assertThat(LockManager.class.getMethod("mutex", String.class).getReturnType()).isEqualTo(MutexLock.class);
        assertThat(LockManager.class.getMethod("readWrite", String.class).getReturnType()).isEqualTo(ReadWriteLock.class);
        assertThat(MutexLock.class.getMethod("lock").getExceptionTypes()).containsExactly(InterruptedException.class);
        assertThat(MutexLock.class.getMethod("tryLock", Duration.class).getReturnType()).isEqualTo(boolean.class);
    }
}
