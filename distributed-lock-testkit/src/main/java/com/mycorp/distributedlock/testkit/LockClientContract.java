package com.mycorp.distributedlock.testkit;

import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.WaitPolicy;
import com.mycorp.distributedlock.runtime.LockRuntime;
import org.junit.jupiter.api.AfterEach;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public abstract class LockClientContract {

    protected final ExecutorService executor = Executors.newSingleThreadExecutor();
    protected LockRuntime runtime;

    protected abstract LockRuntime createRuntime() throws Exception;

    @AfterEach
    protected void tearDown() throws Exception {
        executor.shutdownNow();
        if (runtime != null) {
            runtime.close();
        }
    }

    protected LockRequest request(String key, LockMode mode, Duration waitTime) {
        return request(key, mode, WaitPolicy.timed(waitTime));
    }

    protected LockRequest request(String key, LockMode mode, WaitPolicy waitPolicy) {
        return new LockRequest(new LockKey(key), mode, waitPolicy);
    }
}
