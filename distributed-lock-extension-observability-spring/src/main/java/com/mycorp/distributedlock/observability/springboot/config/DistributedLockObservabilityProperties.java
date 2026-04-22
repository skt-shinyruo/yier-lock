package com.mycorp.distributedlock.observability.springboot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "distributed.lock.observability")
public class DistributedLockObservabilityProperties {
    private boolean enabled = true;
    private boolean includeLockKeyInLogs;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isIncludeLockKeyInLogs() {
        return includeLockKeyInLogs;
    }

    public void setIncludeLockKeyInLogs(boolean includeLockKeyInLogs) {
        this.includeLockKeyInLogs = includeLockKeyInLogs;
    }
}
