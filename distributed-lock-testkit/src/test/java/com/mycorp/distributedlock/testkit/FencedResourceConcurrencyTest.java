package com.mycorp.distributedlock.testkit;

import com.mycorp.distributedlock.api.FencingToken;
import com.mycorp.distributedlock.testkit.support.FencedResource;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class FencedResourceConcurrencyTest {

    @Test
    void concurrentWritesShouldAcceptOnlyMonotonicallyNewerToken() throws Exception {
        FencedResource resource = new FencedResource();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> stale = executor.submit(() -> writeAfterStart(resource, new FencingToken(1L), start));
            Future<Boolean> fresh = executor.submit(() -> writeAfterStart(resource, new FencingToken(2L), start));
            start.countDown();

            int accepted = 0;
            accepted += stale.get() ? 1 : 0;
            accepted += fresh.get() ? 1 : 0;

            assertThat(accepted).isBetween(1, 2);
            resource.write(new FencingToken(3L), "newest");
            assertThat(write(resource, new FencingToken(1L))).isFalse();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void staleConcurrentWriteMustNotOverwriteNewerToken() throws Exception {
        CountDownLatch staleRead = new CountDownLatch(1);
        CountDownLatch freshWritten = new CountDownLatch(1);
        FencedResource resource = new FencedResource(token -> {
            if (token.value() == 1L) {
                staleRead.countDown();
                await(freshWritten);
            }
        });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> stale = executor.submit(() -> write(resource, new FencingToken(1L)));
            assertThat(staleRead.await(1, TimeUnit.SECONDS)).isTrue();

            resource.write(new FencingToken(2L), "fresh");
            freshWritten.countDown();

            assertThat(stale.get(1, TimeUnit.SECONDS)).isFalse();
            assertThat(write(resource, new FencingToken(2L))).isFalse();
        } finally {
            freshWritten.countDown();
            executor.shutdownNow();
        }
    }

    private static boolean writeAfterStart(FencedResource resource, FencingToken token, CountDownLatch start) throws Exception {
        start.await();
        return write(resource, token);
    }

    private static boolean write(FencedResource resource, FencingToken token) {
        try {
            resource.write(token, "value-" + token.value());
            return true;
        } catch (IllegalStateException exception) {
            return false;
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(1, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for concurrent write");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }
}
