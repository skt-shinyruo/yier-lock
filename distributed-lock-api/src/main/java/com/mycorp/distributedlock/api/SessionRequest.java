package com.mycorp.distributedlock.api;

public record SessionRequest(
        String clientId,
        SessionPolicy policy,
        LockCapabilities capabilities
) {

    public SessionRequest {
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalArgumentException("Client id must not be blank");
        }
        if (policy == null) {
            throw new IllegalArgumentException("Session policy is required");
        }
        if (capabilities == null) {
            throw new IllegalArgumentException("Lock capabilities are required");
        }
    }
}
