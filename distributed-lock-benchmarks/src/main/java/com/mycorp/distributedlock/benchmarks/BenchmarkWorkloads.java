package com.mycorp.distributedlock.benchmarks;

import com.mycorp.distributedlock.api.LockClient;
import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockLease;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.LockSession;
import com.mycorp.distributedlock.api.WaitPolicy;
import com.mycorp.distributedlock.api.exception.LockAcquisitionTimeoutException;
import com.mycorp.distributedlock.runtime.LockRuntime;
import org.openjdk.jmh.infra.Blackhole;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;

public final class BenchmarkWorkloads {

    private BenchmarkWorkloads() {
    }

    public static void mutexLifecycle(LockRuntime runtime, String key, Blackhole blackhole) throws Exception {
        String result = runtime.lockExecutor().withLock(mutexRequest(key, WaitPolicy.indefinite()), () -> {
            Blackhole.consumeCPU(64);
            return key;
        });
        blackhole.consume(result);
    }

    public static void successfulTryLock(
        LockClient client,
        String key,
        Duration wait,
        Blackhole blackhole
    ) throws Exception {
        try (LockSession session = client.openSession();
             LockLease lease = session.acquire(mutexRequest(key, WaitPolicy.timed(wait)))) {
            blackhole.consume(lease.key().value());
            blackhole.consume(lease.fencingToken().value());
            Blackhole.consumeCPU(64);
        } catch (LockAcquisitionTimeoutException exception) {
            throw new IllegalStateException("Expected successful tryLock for key " + key, exception);
        }
    }

    public static void contendedTryLock(
        LockClient client,
        String key,
        Duration wait,
        Blackhole blackhole
    ) throws Exception {
        try (LockSession session = client.openSession();
             LockLease lease = session.acquire(mutexRequest(key, WaitPolicy.timed(wait)))) {
            blackhole.consume(true);
            blackhole.consume(lease.key().value());
            blackhole.consume(lease.fencingToken().value());
            Blackhole.consumeCPU(64);
        } catch (LockAcquisitionTimeoutException exception) {
            blackhole.consume(false);
        }
    }

    public static void readSection(LockRuntime runtime, String key, Blackhole blackhole) throws Exception {
        String result = runtime.lockExecutor().withLock(readRequest(key, WaitPolicy.indefinite()), () -> {
            Blackhole.consumeCPU(64);
            return key;
        });
        blackhole.consume(result);
    }

    public static void writeSection(LockRuntime runtime, String key, Blackhole blackhole) throws Exception {
        String result = runtime.lockExecutor().withLock(writeRequest(key, WaitPolicy.indefinite()), () -> {
            Blackhole.consumeCPU(64);
            return key;
        });
        blackhole.consume(result);
    }

    public static void runtimeLifecycle(
        Supplier<LockRuntime> runtimeFactory,
        String backend,
        Blackhole blackhole
    ) throws Exception {
        Objects.requireNonNull(runtimeFactory, "runtimeFactory");
        Objects.requireNonNull(backend, "backend");

        try (LockRuntime runtime = runtimeFactory.get();
             LockSession session = runtime.lockClient().openSession()) {
            blackhole.consume(runtime.lockClient());
            blackhole.consume(runtime.lockExecutor());
            blackhole.consume(session.state());
            blackhole.consume(mutexRequest(BenchmarkKeys.unique("runtime-lifecycle-mutex", backend, Thread.currentThread().getId()), WaitPolicy.timed(Duration.ofMillis(10))));
            blackhole.consume(readRequest(BenchmarkKeys.unique("runtime-lifecycle-rw", backend, Thread.currentThread().getId()), WaitPolicy.timed(Duration.ofMillis(10))));
            Blackhole.consumeCPU(64);
        }
    }

    private static LockRequest mutexRequest(String key, WaitPolicy waitPolicy) {
        return new LockRequest(new LockKey(key), LockMode.MUTEX, waitPolicy);
    }

    private static LockRequest readRequest(String key, WaitPolicy waitPolicy) {
        return new LockRequest(new LockKey(key), LockMode.READ, waitPolicy);
    }

    private static LockRequest writeRequest(String key, WaitPolicy waitPolicy) {
        return new LockRequest(new LockKey(key), LockMode.WRITE, waitPolicy);
    }
}
