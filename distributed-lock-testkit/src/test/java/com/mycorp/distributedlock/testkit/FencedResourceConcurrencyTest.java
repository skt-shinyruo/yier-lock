package com.mycorp.distributedlock.testkit;

import com.mycorp.distributedlock.api.FencingToken;
import com.mycorp.distributedlock.testkit.support.FencedResource;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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
}
