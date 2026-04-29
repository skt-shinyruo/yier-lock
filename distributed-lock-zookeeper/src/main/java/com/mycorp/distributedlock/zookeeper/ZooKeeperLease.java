package com.mycorp.distributedlock.zookeeper;

import com.mycorp.distributedlock.api.FencingToken;
import com.mycorp.distributedlock.api.LeaseState;
import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.SessionState;
import com.mycorp.distributedlock.api.exception.LockBackendException;
import com.mycorp.distributedlock.api.exception.LockFailureContext;
import com.mycorp.distributedlock.api.exception.LockOwnershipLostException;
import com.mycorp.distributedlock.core.backend.BackendLockLease;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.data.Stat;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

final class ZooKeeperLease implements BackendLockLease {

    private final LockKey key;
    private final LockMode mode;
    private final FencingToken fencingToken;
    private final String ownerPath;
    private final byte[] ownerData;
    private final ZooKeeperBackendSession session;
    private final AtomicReference<LeaseState> state = new AtomicReference<>(LeaseState.ACTIVE);

    ZooKeeperLease(
        LockKey key,
        LockMode mode,
        FencingToken fencingToken,
        String ownerPath,
        byte[] ownerData,
        ZooKeeperBackendSession session
    ) {
        this.key = key;
        this.mode = mode;
        this.fencingToken = fencingToken;
        this.ownerPath = ownerPath;
        this.ownerData = ownerData;
        this.session = session;
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
        if (state.get() != LeaseState.ACTIVE) {
            return false;
        }
        if (session.state() != SessionState.ACTIVE) {
            markLost();
            return false;
        }
        if (!ownerNodeStillBelongsToSession()) {
            markLost();
            return false;
        }
        return true;
    }

    @Override
    public void release() {
        release(true);
    }

    void releaseDuringSessionClose() {
        release(false);
    }

    private void release(boolean requireActiveSession) {
        LeaseState current = state.get();
        if (current == LeaseState.RELEASED) {
            return;
        }
        if (current == LeaseState.LOST) {
            session.forgetLease(this);
            throw ownershipLostException();
        }
        if (requireActiveSession && session.state() != SessionState.ACTIVE) {
            markLost();
            throw ownershipLostException();
        }

        try {
            Stat stat = session.curatorFramework().checkExists().forPath(ownerPath);
            if (stat == null || !ownerNodeStillBelongsToSession()) {
                markLost();
                throw ownershipLostException();
            }
            session.curatorFramework().delete().forPath(ownerPath);
            state.set(LeaseState.RELEASED);
            session.forgetLease(this);
        } catch (LockOwnershipLostException exception) {
            throw exception;
        } catch (KeeperException.NoNodeException exception) {
            markLost();
            throw ownershipLostException();
        } catch (Exception exception) {
            throw new LockBackendException(
                "Failed to release ZooKeeper lock for key " + key.value(),
                exception,
                failureContext()
            );
        }
    }

    private boolean ownerNodeStillBelongsToSession() {
        try {
            byte[] currentData = session.curatorFramework().getData().forPath(ownerPath);
            return Arrays.equals(currentData, ownerData);
        } catch (KeeperException.NoNodeException exception) {
            return false;
        } catch (Exception exception) {
            throw new LockBackendException(
                "Failed to inspect ZooKeeper lock for key " + key.value(),
                exception,
                failureContext()
            );
        }
    }

    void markLost() {
        state.compareAndSet(LeaseState.ACTIVE, LeaseState.LOST);
        session.forgetLease(this);
    }

    private LockOwnershipLostException ownershipLostException() {
        return new LockOwnershipLostException(
            "ZooKeeper lock ownership lost for key " + key.value(),
            null,
            failureContext()
        );
    }

    private LockFailureContext failureContext() {
        return new LockFailureContext(key, mode, null, null, "zookeeper", session.sessionId());
    }

    String ownerPath() {
        return ownerPath;
    }
}
