package com.mycorp.distributedlock.zookeeper.springboot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "distributed.lock.zookeeper")
public class ZooKeeperDistributedLockProperties {

    private String connectString = "127.0.0.1:2181";
    private String basePath = "/distributed-locks";

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
