package com.mycorp.distributedlock.testkit.support;

import com.mycorp.distributedlock.api.FencingToken;

import java.util.concurrent.atomic.AtomicLong;

public final class FencedResource {

    private final AtomicLong latestToken = new AtomicLong();

    public void write(FencingToken token, String value) {
        while (true) {
            long previous = latestToken.get();
            if (token.value() <= previous) {
                throw new IllegalStateException("stale fencing token");
            }
            if (latestToken.compareAndSet(previous, token.value())) {
                return;
            }
        }
    }
}
