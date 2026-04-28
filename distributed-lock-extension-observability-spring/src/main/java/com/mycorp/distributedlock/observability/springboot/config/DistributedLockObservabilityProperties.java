package com.mycorp.distributedlock.observability.springboot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "distributed.lock.observability")
public class DistributedLockObservabilityProperties {
    private boolean enabled = true;
    private boolean includeLockKeyInLogs;
    private final Logging logging = new Logging();
    private final Metrics metrics = new Metrics();

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

    public Logging getLogging() {
        return logging;
    }

    public Metrics getMetrics() {
        return metrics;
    }

    public static final class Logging {
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static final class Metrics {
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
