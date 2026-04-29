package com.mycorp.distributedlock.zookeeper;

import com.mycorp.distributedlock.api.LockKey;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ZooKeeperPathLayoutTest {

    @Test
    void shouldJoinRootAndNestedBasePathsWithoutDoubleSlashes() {
        assertThat(new ZooKeeperPathLayout("/").queuePath(new LockKey("orders:42"))).startsWith("/rw/");
        assertThat(new ZooKeeperPathLayout("/locks/").queuePath(new LockKey("orders:42"))).startsWith("/locks/rw/");
    }
}
