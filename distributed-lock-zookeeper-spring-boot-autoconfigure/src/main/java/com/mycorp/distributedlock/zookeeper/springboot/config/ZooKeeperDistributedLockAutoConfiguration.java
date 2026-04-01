package com.mycorp.distributedlock.zookeeper.springboot.config;

import com.mycorp.distributedlock.runtime.spi.BackendModule;
import com.mycorp.distributedlock.zookeeper.ZooKeeperBackendConfiguration;
import com.mycorp.distributedlock.zookeeper.ZooKeeperBackendModule;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(ZooKeeperDistributedLockProperties.class)
@ConditionalOnProperty(prefix = "distributed.lock", name = "backend", havingValue = "zookeeper")
public class ZooKeeperDistributedLockAutoConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "distributed.lock", name = "enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean(name = "zooKeeperBackendModule")
    public BackendModule zooKeeperBackendModule(ZooKeeperDistributedLockProperties properties) {
        ZooKeeperBackendConfiguration configuration = new ZooKeeperBackendConfiguration(
            properties.getConnectString(),
            properties.getBasePath()
        );
        return new ZooKeeperBackendModule(configuration);
    }
}
