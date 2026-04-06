package com.mycorp.distributedlock.api;

public record LockCapabilities(boolean sharedMode, boolean fencingTokens, boolean reentrantSessions) {
}
