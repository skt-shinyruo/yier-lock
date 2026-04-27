package com.mycorp.distributedlock.redis;

import com.mycorp.distributedlock.api.LockMode;
import io.lettuce.core.cluster.SlotHash;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RedisKeyStrategyTest {

    @Test
    void legacyStaticOwnerKeyShouldRemainUnchanged() {
        assertThat(RedisLockBackend.ownerKey("orders:42", LockMode.MUTEX))
            .isEqualTo("lock:orders:42:mutex:owner");
    }

    @Test
    void hashTaggedKeysForSameResourceShouldUseOneRedisClusterSlot() {
        RedisLockBackend.RedisKeys keys = RedisLockBackend.RedisKeys.forKey("orders:42", RedisKeyStrategy.HASH_TAGGED);

        int ownerSlot = SlotHash.getSlot(keys.ownerKey(LockMode.MUTEX));
        assertThat(SlotHash.getSlot(keys.ownerKey(LockMode.WRITE))).isEqualTo(ownerSlot);
        assertThat(SlotHash.getSlot(keys.readersKey())).isEqualTo(ownerSlot);
        assertThat(SlotHash.getSlot(keys.fenceKey())).isEqualTo(ownerSlot);
        assertThat(SlotHash.getSlot(keys.pendingWritersKey())).isEqualTo(ownerSlot);
    }
}
