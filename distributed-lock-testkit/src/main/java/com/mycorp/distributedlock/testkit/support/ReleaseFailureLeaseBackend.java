package com.mycorp.distributedlock.testkit.support;

import com.mycorp.distributedlock.api.FencingToken;
import com.mycorp.distributedlock.api.LeaseState;
import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.SessionState;
import com.mycorp.distributedlock.api.exception.LockBackendException;
import com.mycorp.distributedlock.spi.BackendClient;
import com.mycorp.distributedlock.spi.BackendLease;
import com.mycorp.distributedlock.spi.BackendSession;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class ReleaseFailureLeaseBackend implements BackendClient {

    private final AtomicBoolean firstRelease = new AtomicBoolean(true);
    private final AtomicLong fencingCounter = new AtomicLong();

    @Override
    public BackendSession openSession() {
        return new BackendSession() {
            @Override
            public BackendLease acquire(LockRequest lockRequest) {
                FencingToken fencingToken = new FencingToken(fencingCounter.incrementAndGet());
                return new BackendLease() {
                    @Override
                    public LockKey key() {
                        return lockRequest.key();
                    }

                    @Override
                    public LockMode mode() {
                        return lockRequest.mode();
                    }

                    @Override
                    public FencingToken fencingToken() {
                        return fencingToken;
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
                        if (firstRelease.compareAndSet(true, false)) {
                            throw new LockBackendException("Synthetic release failure");
                        }
                    }
                };
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

    @Override
    public void close() {
    }
}
