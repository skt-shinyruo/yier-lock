package com.mycorp.distributedlock.zookeeper.springboot.config;

import com.mycorp.distributedlock.runtime.LockRuntime;
import com.mycorp.distributedlock.runtime.spi.BackendModule;
import com.mycorp.distributedlock.zookeeper.ZooKeeperBackendConfiguration;
import com.mycorp.distributedlock.zookeeper.ZooKeeperBackendModule;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@AutoConfiguration
@AutoConfigureBefore(name = "com.mycorp.distributedlock.springboot.config.DistributedLockAutoConfiguration")
@ConditionalOnProperty(prefix = "distributed.lock", name = "backend", havingValue = "zookeeper")
public class ZooKeeperDistributedLockAutoConfiguration {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ZooKeeperDistributedLockProperties.class)
    @ConditionalOnProperty(prefix = "distributed.lock", name = "enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean({ BackendModule.class, LockRuntime.class })
    static class DefaultZooKeeperBackendConfiguration {

        @Bean
        BackendModule zooKeeperBackendModule(ZooKeeperDistributedLockProperties properties) {
            ZooKeeperBackendConfiguration configuration = new ZooKeeperBackendConfiguration(
                requireConnectString(properties.getConnectString()),
                requireBasePath(properties.getBasePath())
            );
            return new ZooKeeperBackendModule(configuration);
        }

        private String requireConnectString(String connectString) {
            if (connectString == null || connectString.isBlank()) {
                throw new IllegalArgumentException("distributed.lock.zookeeper.connect-string must be configured");
            }
            return connectString;
        }

        private String requireBasePath(String basePath) {
            if (basePath == null || basePath.isBlank()) {
                throw new IllegalArgumentException("distributed.lock.zookeeper.base-path must be configured");
            }
            return basePath;
        }
    }
}
