package com.mycorp.distributedlock.zookeeper.springboot.integration;

import com.mycorp.distributedlock.api.LockClient;
import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.SynchronousLockExecutor;
import com.mycorp.distributedlock.api.WaitPolicy;
import com.mycorp.distributedlock.springboot.config.DistributedLockAutoConfiguration;
import com.mycorp.distributedlock.zookeeper.springboot.config.ZooKeeperDistributedLockAutoConfiguration;
import org.apache.curator.test.TestingServer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ZooKeeperStarterIntegrationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(
            AopAutoConfiguration.class,
            DistributedLockAutoConfiguration.class,
            ZooKeeperDistributedLockAutoConfiguration.class
        ));

    @Test
    void shouldCreateWorkingZooKeeperBackedLockRuntime() throws Exception {
        try (TestingServer server = new TestingServer()) {
            contextRunner
                .withPropertyValues(
                    "distributed.lock.enabled=true",
                    "distributed.lock.backend=zookeeper",
                    "distributed.lock.zookeeper.connect-string=" + server.getConnectString(),
                    "distributed.lock.zookeeper.base-path=/distributed-locks"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(LockClient.class);
                    assertThat(context).hasSingleBean(SynchronousLockExecutor.class);

                    SynchronousLockExecutor executor = context.getBean(SynchronousLockExecutor.class);
                    String result = executor.withLock(sampleRequest("zk-starter-test"), lease -> "ok");

                    assertThat(result).isEqualTo("ok");
                });
        }
    }

    private static LockRequest sampleRequest(String key) {
        return new LockRequest(
            new LockKey(key),
            LockMode.MUTEX,
            WaitPolicy.timed(Duration.ofSeconds(1))
        );
    }
}
