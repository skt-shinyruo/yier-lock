package com.mycorp.distributedlock.testkit.support;

import com.mycorp.distributedlock.api.FencingToken;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public final class FencedResource {

    private final AtomicLong latestToken = new AtomicLong();
    private final Consumer<FencingToken> afterRead;

    public FencedResource() {
        this(ignored -> { });
    }

    public FencedResource(Consumer<FencingToken> afterRead) {
        this.afterRead = afterRead;
    }

    public void write(FencingToken token, String value) {
        while (true) {
            long previous = latestToken.get();
            afterRead.accept(token);
            if (token.value() <= previous) {
                throw new IllegalStateException("stale fencing token");
            }
            if (latestToken.compareAndSet(previous, token.value())) {
                return;
            }
        }
    }
}
