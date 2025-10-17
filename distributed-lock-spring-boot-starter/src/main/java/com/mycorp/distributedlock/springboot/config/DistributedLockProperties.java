package com.mycorp.distributedlock.springboot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.env.Environment;

import java.time.Duration;

@ConfigurationProperties(prefix = "spring.distributed-lock")
public class DistributedLockProperties {

    /**
     * 锁类型：redis 或 zookeeper
     */
    private String type;

    /**
     * 默认租约时间
     */
    private Duration defaultLeaseTime = Duration.ofSeconds(30);

    /**
     * 默认等待时间
     */
    private Duration defaultWaitTime = Duration.ofSeconds(10);

    /**
     * 重试间隔
     */
    private Duration retryInterval = Duration.ofMillis(100);

    /**
     * 最大重试次数
     */
    private int maxRetries = 3;

    /**
     * 看门狗是否启用
     */
    private boolean watchdogEnabled = true;

    /**
     * 看门狗续约间隔
     */
    private Duration watchdogRenewalInterval = Duration.ofSeconds(10);

    /**
     * 指标是否启用
     */
    private boolean metricsEnabled = true;

    /**
     * 追踪是否启用
     */
    private boolean tracingEnabled = true;

    /**
     * Redis 配置
     */
    private Redis redis = new Redis();

    /**
     * ZooKeeper 配置
     */
    private Zookeeper zookeeper = new Zookeeper();

    // Getters and Setters
    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Duration getDefaultLeaseTime() {
        return defaultLeaseTime;
    }

    public void setDefaultLeaseTime(Duration defaultLeaseTime) {
        this.defaultLeaseTime = defaultLeaseTime;
    }

    public Duration getDefaultWaitTime() {
        return defaultWaitTime;
    }

    public void setDefaultWaitTime(Duration defaultWaitTime) {
        this.defaultWaitTime = defaultWaitTime;
    }

    public Duration getRetryInterval() {
        return retryInterval;
    }

    public void setRetryInterval(Duration retryInterval) {
        this.retryInterval = retryInterval;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public boolean isWatchdogEnabled() {
        return watchdogEnabled;
    }

    public void setWatchdogEnabled(boolean watchdogEnabled) {
        this.watchdogEnabled = watchdogEnabled;
    }

    public Duration getWatchdogRenewalInterval() {
        return watchdogRenewalInterval;
    }

    public void setWatchdogRenewalInterval(Duration watchdogRenewalInterval) {
        this.watchdogRenewalInterval = watchdogRenewalInterval;
    }

    public boolean isMetricsEnabled() {
        return metricsEnabled;
    }

    public void setMetricsEnabled(boolean metricsEnabled) {
        this.metricsEnabled = metricsEnabled;
    }

    public boolean isTracingEnabled() {
        return tracingEnabled;
    }

    public void setTracingEnabled(boolean tracingEnabled) {
        this.tracingEnabled = tracingEnabled;
    }

    public Redis getRedis() {
        return redis;
    }

    public void setRedis(Redis redis) {
        this.redis = redis;
    }

    public Zookeeper getZookeeper() {
        return zookeeper;
    }

    public void setZookeeper(Zookeeper zookeeper) {
        this.zookeeper = zookeeper;
    }

    public static class Redis {
        /**
         * Redis 主机列表，默认 localhost:6379
         */
        private String hosts = "localhost:6379";

        /**
         * Redis 密码
         */
        private String password;

        /**
         * 是否启用TLS/SSL
         */
        private boolean ssl = false;

        /**
         * TLS 证书路径
         */
        private String trustStorePath;

        /**
         * TLS 证书密码
         */
        private String trustStorePassword;

        /**
         * TLS 密钥库路径
         */
        private String keyStorePath;

        /**
         * TLS 密钥库密码
         */
        private String keyStorePassword;

        /**
         * 连接池配置
         */
        private Pool pool = new Pool();

        public String getHosts() {
            return hosts;
        }

        public void setHosts(String hosts) {
            this.hosts = hosts;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public boolean isSsl() {
            return ssl;
        }

        public void setSsl(boolean ssl) {
            this.ssl = ssl;
        }

        public String getTrustStorePath() {
            return trustStorePath;
        }

        public void setTrustStorePath(String trustStorePath) {
            this.trustStorePath = trustStorePath;
        }

        public String getTrustStorePassword() {
            return trustStorePassword;
        }

        public void setTrustStorePassword(String trustStorePassword) {
            this.trustStorePassword = trustStorePassword;
        }

        public String getKeyStorePath() {
            return keyStorePath;
        }

        public void setKeyStorePath(String keyStorePath) {
            this.keyStorePath = keyStorePath;
        }

        public String getKeyStorePassword() {
            return keyStorePassword;
        }

        public void setKeyStorePassword(String keyStorePassword) {
            this.keyStorePassword = keyStorePassword;
        }

        public Pool getPool() {
            return pool;
        }

        public void setPool(Pool pool) {
            this.pool = pool;
        }

        public static class Pool {
            /**
             * 最大连接数
             */
            private int maxTotal = 8;

            /**
             * 最大空闲连接数
             */
            private int maxIdle = 8;

            /**
             * 最小空闲连接数
             */
            private int minIdle = 0;

            /**
             * 连接最大等待时间（毫秒）
             */
            private Duration maxWait = Duration.ofMillis(-1);

            public int getMaxTotal() {
                return maxTotal;
            }

            public void setMaxTotal(int maxTotal) {
                this.maxTotal = maxTotal;
            }

            public int getMaxIdle() {
                return maxIdle;
            }

            public void setMaxIdle(int maxIdle) {
                this.maxIdle = maxIdle;
            }

            public int getMinIdle() {
                return minIdle;
            }

            public void setMinIdle(int minIdle) {
                this.minIdle = minIdle;
            }

            public Duration getMaxWait() {
                return maxWait;
            }

            public void setMaxWait(Duration maxWait) {
                this.maxWait = maxWait;
            }
        }
    }

    public static class Zookeeper {
        /**
         * ZooKeeper 连接字符串，默认 localhost:2181
         */
        private String connectString = "localhost:2181";

        /**
         * 锁基础路径，默认 /distributed-locks
         */
        private String basePath = "/distributed-locks";

        /**
         * ZooKeeper 会话超时时间
         */
        private Duration sessionTimeout = Duration.ofSeconds(60);

        /**
         * ZooKeeper 连接超时时间
         */
        private Duration connectionTimeout = Duration.ofSeconds(15);

        /**
         * 是否启用认证
         */
        private boolean authEnabled = false;

        /**
         * 认证方案
         */
        private String authScheme = "digest";

        /**
         * 认证信息，格式：username:password
         */
        private String authInfo;

        /**
         * 是否启用ACL
         */
        private boolean aclEnabled = false;

        /**
         * ACL 权限配置
         */
        private Acl acl = new Acl();

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

        public Duration getSessionTimeout() {
            return sessionTimeout;
        }

        public void setSessionTimeout(Duration sessionTimeout) {
            this.sessionTimeout = sessionTimeout;
        }

        public Duration getConnectionTimeout() {
            return connectionTimeout;
        }

        public void setConnectionTimeout(Duration connectionTimeout) {
            this.connectionTimeout = connectionTimeout;
        }

        public boolean isAuthEnabled() {
            return authEnabled;
        }

        public void setAuthEnabled(boolean authEnabled) {
            this.authEnabled = authEnabled;
        }

        public String getAuthScheme() {
            return authScheme;
        }

        public void setAuthScheme(String authScheme) {
            this.authScheme = authScheme;
        }

        public String getAuthInfo() {
            return authInfo;
        }

        public void setAuthInfo(String authInfo) {
            this.authInfo = authInfo;
        }

        public boolean isAclEnabled() {
            return aclEnabled;
        }

        public void setAclEnabled(boolean aclEnabled) {
            this.aclEnabled = aclEnabled;
        }

        public Acl getAcl() {
            return acl;
        }

        public void setAcl(Acl acl) {
            this.acl = acl;
        }

        public static class Acl {
            /**
             * ACL 权限：ALL, READ, WRITE, CREATE, DELETE, ADMIN
             */
            private String permissions = "ALL";

            /**
             * ACL 方案
             */
            private String scheme = "digest";

            /**
             * ACL ID
             */
            private String id;

            public String getPermissions() {
                return permissions;
            }

            public void setPermissions(String permissions) {
                this.permissions = permissions;
            }

            public String getScheme() {
                return scheme;
            }

            public void setScheme(String scheme) {
                this.scheme = scheme;
            }

            public String getId() {
                return id;
            }

            public void setId(String id) {
                this.id = id;
            }
        }
    }
}