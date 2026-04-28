package com.mycorp.distributedlock.zookeeper.springboot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "distributed.lock.zookeeper")
public class ZooKeeperDistributedLockProperties {

    @jakarta.validation.constraints.NotBlank
    private String connectString;
    @jakarta.validation.constraints.NotBlank
    private String basePath;

    public String getConnectString() {
        return connectString;
    }

    public void setConnectString(String connectString) {
        this.connectString = connectString;
    }

    public String getBasePath() {
        return basePath;
    }

    public void setBasePath(String basePath) {
        this.basePath = basePath;
    }
}
