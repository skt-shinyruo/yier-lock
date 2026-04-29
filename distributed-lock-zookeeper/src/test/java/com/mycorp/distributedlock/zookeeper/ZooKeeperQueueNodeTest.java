package com.mycorp.distributedlock.zookeeper;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ZooKeeperQueueNodeTest {

    @Test
    void shouldParseSequenceSuffix() {
        ZooKeeperQueueNode node = ZooKeeperQueueNode.parse("write-0000000017");
        assertThat(node.name()).isEqualTo("write-0000000017");
        assertThat(node.sequence()).isEqualTo(17);
    }

    @Test
    void shouldRejectMalformedQueueNode() {
        assertThatThrownBy(() -> ZooKeeperQueueNode.parse("write-nope"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Malformed ZooKeeper queue node");
    }
}
