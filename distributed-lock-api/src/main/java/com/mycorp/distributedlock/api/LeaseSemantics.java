package com.mycorp.distributedlock.api;

public enum LeaseSemantics {
    RENEWABLE_WATCHDOG,
    FIXED_TTL,
    SESSION_BOUND
}
