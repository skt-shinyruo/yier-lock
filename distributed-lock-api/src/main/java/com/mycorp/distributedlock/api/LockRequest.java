package com.mycorp.distributedlock.api;

public record LockRequest(
        LockKey key,
        LockMode mode,
        WaitPolicy waitPolicy,
        LeasePolicy leasePolicy
) {

    public LockRequest {
        if (key == null) {
            throw new IllegalArgumentException("Lock key is required");
        }
        if (mode == null) {
            throw new IllegalArgumentException("Lock mode is required");
        }
        if (waitPolicy == null) {
            throw new IllegalArgumentException("Wait policy is required");
        }
        if (leasePolicy == null) {
            throw new IllegalArgumentException("Lease policy is required");
        }
    }
}
