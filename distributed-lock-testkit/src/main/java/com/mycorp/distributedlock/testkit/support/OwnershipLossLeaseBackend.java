package com.mycorp.distributedlock.testkit.support;

import com.mycorp.distributedlock.api.FencingToken;
import com.mycorp.distributedlock.api.LeaseState;
import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.SessionState;
import com.mycorp.distributedlock.api.exception.LockOwnershipLostException;
import com.mycorp.distributedlock.spi.BackendClient;
import com.mycorp.distributedlock.spi.BackendLease;
import com.mycorp.distributedlock.spi.BackendSession;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class OwnershipLossLeaseBackend implements BackendClient {

    private final AtomicBoolean valid = new AtomicBoolean(true);
    private final AtomicLong fencingCounter = new AtomicLong();

    @Override
    public BackendSession openSession() {
        return new BackendSession() {
            @Override
            public BackendLease acquire(LockRequest lockRequest) {
                valid.set(true);
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
                        return valid.get() ? LeaseState.ACTIVE : LeaseState.LOST;
                    }

                    @Override
                    public boolean isValid() {
                        return valid.get();
                    }

                    @Override
                    public void release() {
                        if (!valid.get()) {
                            throw new LockOwnershipLostException(
                                "Synthetic ownership loss for " + lockRequest.key().value()
                            );
                        }
                        valid.set(false);
                    }
                };
            }

            @Override
            public SessionState state() {
                return valid.get() ? SessionState.ACTIVE : SessionState.LOST;
            }

            @Override
            public void close() {
            }
        };
    }

    @Override
    public void close() {
    }

    public void invalidateCurrentLease() {
        valid.set(false);
    }
}
