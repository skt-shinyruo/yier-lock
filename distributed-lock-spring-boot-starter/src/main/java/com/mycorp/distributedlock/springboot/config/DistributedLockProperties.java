package com.mycorp.distributedlock.springboot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Validated
@ConfigurationProperties(prefix = "distributed.lock")
public class DistributedLockProperties {

    private boolean enabled = true;
    private String backend;
    private final Redis redis = new Redis();
    private final Zookeeper zookeeper = new Zookeeper();
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

    public Redis getRedis() {
        return redis;
    }

    public Zookeeper getZookeeper() {
        return zookeeper;
    }

    public Spring getSpring() {
        return spring;
    }

    public Object toBackendConfiguration() {
        if ("redis".equalsIgnoreCase(backend)) {
            Map<String, Object> configuration = new LinkedHashMap<>();
            configuration.put("uri", redis.getUri());
            configuration.put("lease-seconds", redis.getLeaseTime().toSeconds());
            return configuration;
        }
        if ("zookeeper".equalsIgnoreCase(backend)) {
            Map<String, Object> configuration = new LinkedHashMap<>();
            configuration.put("connect-string", zookeeper.getConnectString());
            configuration.put("base-path", zookeeper.getBasePath());
            return configuration;
        }
        return null;
    }

    public static final class Redis {
        private String uri = "redis://localhost:6379";
        private Duration leaseTime = Duration.ofSeconds(30);

        public String getUri() {
            return uri;
        }

        public void setUri(String uri) {
            this.uri = uri;
        }

        public Duration getLeaseTime() {
            return leaseTime;
        }

        public void setLeaseTime(Duration leaseTime) {
            this.leaseTime = leaseTime;
        }
    }

    public static final class Zookeeper {
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
