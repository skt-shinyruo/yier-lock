package com.mycorp.distributedlock.api;

public record SessionRequest(SessionPolicy sessionPolicy) {

    public SessionRequest {
        if (sessionPolicy == null) {
            throw new IllegalArgumentException("Session policy is required");
        }
    }
}
