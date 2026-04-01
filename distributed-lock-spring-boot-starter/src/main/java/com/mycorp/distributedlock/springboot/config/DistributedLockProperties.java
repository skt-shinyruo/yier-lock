package com.mycorp.distributedlock.springboot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "distributed.lock")
public class DistributedLockProperties {

    private boolean enabled = true;
    private String backend;
    private final Spring spring = new Spring();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBackend() {
        return backend;
    }

    public void setBackend(String backend) {
        this.backend = backend;
    }

    public Spring getSpring() {
        return spring;
    }

    public static final class Spring {
        private final Annotation annotation = new Annotation();

        public Annotation getAnnotation() {
            return annotation;
        }
    }

    public static final class Annotation {
        private boolean enabled = true;
        private Duration defaultTimeout;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Duration getDefaultTimeout() {
            return defaultTimeout;
        }

        public void setDefaultTimeout(Duration defaultTimeout) {
            this.defaultTimeout = defaultTimeout;
        }
    }
}
