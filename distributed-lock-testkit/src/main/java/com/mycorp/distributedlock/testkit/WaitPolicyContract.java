package com.mycorp.distributedlock.testkit;

import com.mycorp.distributedlock.api.LockLease;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockSession;
import com.mycorp.distributedlock.api.WaitPolicy;
import com.mycorp.distributedlock.api.exception.LockAcquisitionTimeoutException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

public abstract class WaitPolicyContract extends LockClientContract {

    @Test
    void tryOnceShouldFailImmediatelyWhenKeyIsHeld() throws Exception {
        runtime = createRuntime();
        try (LockSession holder = runtime.lockClient().openSession();
             LockLease ignored = holder.acquire(request("inventory:try-once", LockMode.MUTEX, WaitPolicy.indefinite()));
             LockSession contender = runtime.lockClient().openSession()) {
            Future<Duration> acquireAttempt = executor.submit(() -> {
                long startedNanos = System.nanoTime();
                try (LockLease lease = contender.acquire(request("inventory:try-once", LockMode.MUTEX, WaitPolicy.tryOnce()))) {
                    fail("TRY_ONCE acquired a lock that was already held by another session");
                    return Duration.ofNanos(System.nanoTime() - startedNanos);
                } catch (LockAcquisitionTimeoutException exception) {
                    return Duration.ofNanos(System.nanoTime() - startedNanos);
                }
            });

            Duration elapsed;
            try {
                elapsed = acquireAttempt.get(2, TimeUnit.SECONDS);
            } catch (TimeoutException exception) {
                acquireAttempt.cancel(true);
                fail("TRY_ONCE acquisition did not complete within 2 seconds while the key was held");
                return;
            }

            assertThat(elapsed).isLessThan(Duration.ofMillis(500));
        }
    }

    @Test
    void timedShouldTimeOutWhenKeyIsHeld() throws Exception {
        runtime = createRuntime();
        try (LockSession holder = runtime.lockClient().openSession();
             LockLease ignored = holder.acquire(request("inventory:timed", LockMode.MUTEX, Duration.ofSeconds(1)))) {
            assertThat(executor.submit(() -> tryAcquire("inventory:timed", LockMode.MUTEX, Duration.ofMillis(100))).get()).isFalse();
        }
    }

    private boolean tryAcquire(String key, LockMode mode, Duration waitTime) throws Exception {
        try (LockSession contender = runtime.lockClient().openSession();
             LockLease ignored = contender.acquire(request(key, mode, waitTime))) {
            return true;
        } catch (LockAcquisitionTimeoutException exception) {
            return false;
        }
    }
}
