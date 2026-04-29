package com.mycorp.distributedlock.api;

public record LockRequest(
        LockKey key,
        LockMode mode,
        WaitPolicy waitPolicy,
        LeasePolicy leasePolicy
) {

    public LockRequest(LockKey key, LockMode mode, WaitPolicy waitPolicy) {
        this(key, mode, waitPolicy, LeasePolicy.backendDefault());
    }

    public static LockRequest of(String key, LockMode mode, WaitPolicy waitPolicy) {
        return new LockRequest(new LockKey(key), mode, waitPolicy);
    }

    public static LockRequest of(String key, LockMode mode, WaitPolicy waitPolicy, LeasePolicy leasePolicy) {
        return new LockRequest(new LockKey(key), mode, waitPolicy, leasePolicy);
    }

    public static LockRequest mutex(String key, WaitPolicy waitPolicy) {
        return of(key, LockMode.MUTEX, waitPolicy);
    }

    public static LockRequest read(String key, WaitPolicy waitPolicy) {
        return of(key, LockMode.READ, waitPolicy);
    }

    public static LockRequest write(String key, WaitPolicy waitPolicy) {
        return of(key, LockMode.WRITE, waitPolicy);
    }

    public LockRequest withWaitPolicy(WaitPolicy waitPolicy) {
        return new LockRequest(key, mode, waitPolicy, leasePolicy);
    }

    public LockRequest withLeasePolicy(LeasePolicy leasePolicy) {
        return new LockRequest(key, mode, waitPolicy, leasePolicy);
    }

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
