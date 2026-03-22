package com.mycorp.distributedlock.springboot.integration;

import com.mycorp.distributedlock.api.LockManager;
import com.mycorp.distributedlock.api.MutexLock;
import com.mycorp.distributedlock.springboot.config.DistributedLockAutoConfiguration;
import org.apache.curator.test.TestingServer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ZooKeeperStarterIntegrationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AopAutoConfiguration.class, DistributedLockAutoConfiguration.class));

    @Test
    void shouldCreateWorkingZooKeeperBackedLockManager() throws Exception {
        try (TestingServer server = new TestingServer()) {
            contextRunner
                .withPropertyValues(
                    "distributed.lock.enabled=true",
                    "distributed.lock.backend=zookeeper",
                    "distributed.lock.zookeeper.connect-string=" + server.getConnectString()
                )
                .run(context -> {
                    LockManager manager = context.getBean(LockManager.class);
                    MutexLock lock = manager.mutex("zk-starter-test");
                    assertThat(lock.tryLock(Duration.ofSeconds(1))).isTrue();
                    lock.unlock();
                });
        }
    }
}
