package com.mycorp.distributedlock.api;

public enum BackendCostModel {
    CHEAP_SESSION,
    NETWORK_CLIENT_PER_SESSION,
    POOLED_NETWORK_CLIENT
}
