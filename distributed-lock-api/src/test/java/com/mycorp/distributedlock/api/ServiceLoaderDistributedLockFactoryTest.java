package com.mycorp.distributedlock.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ServiceLoaderDistributedLockFactoryTest {

    @AfterEach
    void clearProperties() {
        System.clearProperty("distributed-lock.type");
    }

    @Test
    void selectsProviderByConfiguredType() {
        System.setProperty("distributed-lock.type", "mock");

        ServiceLoaderDistributedLockFactory factory = new ServiceLoaderDistributedLockFactory();
        assertEquals("mock", factory.getActiveProviderType());
    }
}
