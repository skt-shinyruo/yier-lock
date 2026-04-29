package com.mycorp.distributedlock.api.exception;

import com.mycorp.distributedlock.api.LeasePolicy;
import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.WaitPolicy;

public record LockFailureContext(
        LockKey key,
        LockMode mode,
        WaitPolicy waitPolicy,
        LeasePolicy leasePolicy,
        String backendId,
        String sessionId
) {

    private static final LockFailureContext EMPTY = new LockFailureContext(null, null, null, null, null, null);

    public static LockFailureContext empty() {
        return EMPTY;
    }

    public static LockFailureContext fromRequest(LockRequest request, String backendId, String sessionId) {
        if (request == null) {
            return new LockFailureContext(null, null, null, null, backendId, sessionId);
        }
        return new LockFailureContext(
                request.key(),
                request.mode(),
                request.waitPolicy(),
                request.leasePolicy(),
                backendId,
                sessionId
        );
    }
}
