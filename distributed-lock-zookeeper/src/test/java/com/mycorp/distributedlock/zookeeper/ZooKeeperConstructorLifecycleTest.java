package com.mycorp.distributedlock.zookeeper;

import com.mycorp.distributedlock.api.exception.LockBackendException;
import org.apache.curator.framework.CuratorFramework;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ZooKeeperConstructorLifecycleTest {

    @Test
    void constructorShouldNotConnectToZooKeeper() {
        ZooKeeperClientFactory clientFactory = mock(ZooKeeperClientFactory.class);

        new ZooKeeperLockBackend(
            new ZooKeeperBackendConfiguration("zk.example.test:2181", "/locks"),
            clientFactory
        );

        verifyNoInteractions(clientFactory);
    }

    @Test
    void openSessionShouldReportConnectionFailure() {
        ZooKeeperClientFactory clientFactory = mock(ZooKeeperClientFactory.class);
        LockBackendException failure = new LockBackendException("Failed to connect to ZooKeeper");
        when(clientFactory.connect()).thenThrow(failure);
        ZooKeeperLockBackend backend = new ZooKeeperLockBackend(
            new ZooKeeperBackendConfiguration("127.0.0.1:1", "/locks"),
            clientFactory
        );

        assertThatThrownBy(backend::openSession)
            .isSameAs(failure);

        verify(clientFactory).connect();
    }

    @Test
    void connectShouldCloseClientWhenConnectionTimesOut() throws Exception {
        CuratorFramework curatorFramework = mock(CuratorFramework.class);
        when(curatorFramework.blockUntilConnected(10, TimeUnit.SECONDS)).thenReturn(false);
        ZooKeeperClientFactory factory = new ZooKeeperClientFactory(
            new ZooKeeperBackendConfiguration("127.0.0.1:1", "/locks"),
            () -> curatorFramework
        );

        assertThatThrownBy(factory::connect)
            .isInstanceOf(LockBackendException.class)
            .hasMessageContaining("Failed to connect to ZooKeeper within 10 seconds: 127.0.0.1:1");

        verify(curatorFramework).start();
        verify(curatorFramework).close();
    }

    @Test
    void connectShouldCloseClientAndRestoreInterruptWhenConnectionIsInterrupted() throws Exception {
        CuratorFramework curatorFramework = mock(CuratorFramework.class);
        InterruptedException interruption = new InterruptedException("interrupted");
        doThrow(interruption).when(curatorFramework).blockUntilConnected(10, TimeUnit.SECONDS);
        ZooKeeperClientFactory factory = new ZooKeeperClientFactory(
            new ZooKeeperBackendConfiguration("127.0.0.1:1", "/locks"),
            () -> curatorFramework
        );

        try {
            assertThatThrownBy(factory::connect)
                .isInstanceOf(LockBackendException.class)
                .hasMessage("Interrupted while connecting to ZooKeeper")
                .hasCauseInstanceOf(InterruptedException.class);

            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        verify(curatorFramework).start();
        verify(curatorFramework).close();
    }

    @Test
    void connectShouldCloseClientWhenStartFailsAtRuntime() {
        CuratorFramework curatorFramework = mock(CuratorFramework.class);
        RuntimeException failure = new RuntimeException("boom");
        doThrow(failure).when(curatorFramework).start();
        ZooKeeperClientFactory factory = new ZooKeeperClientFactory(
            new ZooKeeperBackendConfiguration("127.0.0.1:1", "/locks"),
            () -> curatorFramework
        );

        assertThatThrownBy(factory::connect)
            .isSameAs(failure);

        verify(curatorFramework).start();
        verify(curatorFramework).close();
    }

    @Test
    void connectShouldCloseClientWhenConnectionCheckFailsAtRuntime() throws Exception {
        CuratorFramework curatorFramework = mock(CuratorFramework.class);
        RuntimeException failure = new RuntimeException("boom");
        doThrow(failure).when(curatorFramework).blockUntilConnected(10, TimeUnit.SECONDS);
        ZooKeeperClientFactory factory = new ZooKeeperClientFactory(
            new ZooKeeperBackendConfiguration("127.0.0.1:1", "/locks"),
            () -> curatorFramework
        );

        assertThatThrownBy(factory::connect)
            .isSameAs(failure);

        verify(curatorFramework).start();
        verify(curatorFramework).close();
    }
}
