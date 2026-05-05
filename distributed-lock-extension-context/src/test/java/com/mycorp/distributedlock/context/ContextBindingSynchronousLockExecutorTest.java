package com.mycorp.distributedlock.context;

import com.mycorp.distributedlock.api.FencingToken;
import com.mycorp.distributedlock.api.LeaseState;
import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockLease;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.LockedAction;
import com.mycorp.distributedlock.api.SynchronousLockExecutor;
import com.mycorp.distributedlock.api.WaitPolicy;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContextBindingSynchronousLockExecutorTest {

    @Test
    void withLockShouldBindLeaseOnlyForSynchronousScope() throws Exception {
        LockLease lease = lease("orders:42", 7L);
        ContextBindingSynchronousLockExecutor executor = new ContextBindingSynchronousLockExecutor(
            new SynchronousLockExecutor() {
                @Override
                public <T> T withLock(LockRequest request, LockedAction<T> action) throws Exception {
                    return action.execute(lease);
                }
            }
        );

        assertThat(LockScopeContext.currentLease()).isEmpty();

        FencingToken token = executor.withLock(sampleRequest(), ignored -> {
            assertThat(LockScopeContext.currentLease()).containsSame(lease);
            return LockScopeContext.requireCurrentFencingToken();
        });

        assertThat(token).isEqualTo(new FencingToken(7L));
        assertThat(LockScopeContext.currentLease()).isEmpty();
    }

    @Test
    void requireCurrentFencingTokenShouldFailOutsideScope() {
        assertThatThrownBy(LockScopeContext::requireCurrentFencingToken)
            .isInstanceOf(IllegalStateException.class);
    }

    private static LockRequest sampleRequest() {
        return new LockRequest(
            new LockKey("orders:42"),
            LockMode.MUTEX,
            WaitPolicy.timed(Duration.ofSeconds(1))
        );
    }

    private static LockLease lease(String key, long fencingToken) {
        return new LockLease() {
            @Override
            public LockKey key() {
                return new LockKey(key);
            }

            @Override
            public LockMode mode() {
                return LockMode.MUTEX;
            }

            @Override
            public FencingToken fencingToken() {
                return new FencingToken(fencingToken);
            }

            @Override
            public LeaseState state() {
                return LeaseState.ACTIVE;
            }

            @Override
            public boolean isValid() {
                return true;
            }

            @Override
            public void release() {
            }
        };
    }
}
