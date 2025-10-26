package com.mycorp.distributedlock.core.config;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigException;
import com.typesafe.config.ConfigFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 锁配置单元测试
 * 测试配置管理、验证、动态更新等功能
 */
class LockConfigurationTest {

    @Mock
    private Environment mockEnvironment;

    private LockConfiguration config;
    private Config rawConfig;

    @BeforeEach
    void setUp() {
        // 创建基本配置
        rawConfig = ConfigFactory.parseString("""
            distributed-lock {
                type = "redis"
                default-lease-time = 30s
                default-wait-time = 10s
                watchdog {
                    enabled = true
                    renewal-interval = 10s
                }
                retry-interval = 100ms
                max-retries = 3
                metrics {
                    enabled = true
                    tracing-enabled = true
                }
                redis {
                    hosts = "localhost:6379"
                    password = "password123"
                    database = 0
                    ssl = false
                    pool {
                        max-total = 8
                        max-idle = 8
                        min-idle = 0
                    }
                }
                zookeeper {
                    connect-string = "localhost:2181"
                    base-path = "/distributed-locks"
                    session-timeout = 60s
                    connection-timeout = 15s
                    auth-enabled = false
                }
            }
            """);
    }

    @Test
    void shouldCreateLockConfigurationWithConfig() {
        config = new LockConfiguration(rawConfig);
        
        assertNotNull(config.getConfig());
        assertEquals("redis", config.getLockType().orElse(null));
        assertEquals(Duration.ofSeconds(30), config.getDefaultLeaseTime());
        assertEquals(Duration.ofSeconds(10), config.getDefaultWaitTime());
    }

    @Test
    void shouldCreateLockConfigurationWithEnvironment() {
        when(mockEnvironment.getProperty("distributed.lock.type")).thenReturn("zookeeper");
        when(mockEnvironment.getProperty("distributed.lock.default-lease-time", "30s")).thenReturn("60s");
        when(mockEnvironment.getProperty("distributed.lock.default-wait-time", "10s")).thenReturn("20s");
        
        config = new LockConfiguration(mockEnvironment);
        
        assertNotNull(config.getUnifiedConfig());
        assertEquals("zookeeper", config.getLockType().orElse(null));
        assertEquals(Duration.ofSeconds(60), config.getDefaultLeaseTime());
        assertEquals(Duration.ofSeconds(20), config.getDefaultWaitTime());
    }

    @Test
    void shouldCreateLockConfigurationWithDefaultValues() {
        config = new LockConfiguration();
        
        assertTrue(config.getLockType().isEmpty());
        assertEquals(Duration.ofSeconds(30), config.getDefaultLeaseTime());
        assertEquals(Duration.ofSeconds(10), config.getDefaultWaitTime());
        assertTrue(config.isWatchdogEnabled());
        assertEquals(Duration.ofSeconds(10), config.getWatchdogRenewalInterval());
        assertEquals(Duration.ofMillis(100), config.getRetryInterval());
        assertEquals(3, config.getMaxRetries());
        assertTrue(config.isMetricsEnabled());
        assertTrue(config.isTracingEnabled());
    }

    @Test
    void shouldGetLockTypeFromConfig() {
        config = new LockConfiguration(rawConfig);
        
        assertTrue(config.getLockType().isPresent());
        assertEquals("redis", config.getLockType().get());
    }

    @Test
    void shouldGetEmptyLockTypeWhenNotConfigured() {
        Config emptyConfig = ConfigFactory.empty();
        config = new LockConfiguration(emptyConfig);
        
        assertTrue(config.getLockType().isEmpty());
    }

    @Test
    void shouldGetLockTypeFromBlankValue() {
        Config configWithBlankType = ConfigFactory.parseString("""
            distributed-lock {
                type = "   "
            }
            """);
        config = new LockConfiguration(configWithBlankType);
        
        assertTrue(config.getLockType().isEmpty());
    }

    @Test
    void shouldGetDefaultLeaseTime() {
        config = new LockConfiguration(rawConfig);
        
        assertEquals(Duration.ofSeconds(30), config.getDefaultLeaseTime());
    }

    @Test
    void shouldGetDefaultWaitTime() {
        config = new LockConfiguration(rawConfig);
        
        assertEquals(Duration.ofSeconds(10), config.getDefaultWaitTime());
    }

    @Test
    void shouldGetWatchdogSettings() {
        config = new LockConfiguration(rawConfig);
        
        assertTrue(config.isWatchdogEnabled());
        assertEquals(Duration.ofSeconds(10), config.getWatchdogRenewalInterval());
    }

    @Test
    void shouldGetRetrySettings() {
        config = new LockConfiguration(rawConfig);
        
        assertEquals(Duration.ofMillis(100), config.getRetryInterval());
        assertEquals(3, config.getMaxRetries());
    }

    @Test
    void shouldGetMetricsSettings() {
        config = new LockConfiguration(rawConfig);
        
        assertTrue(config.isMetricsEnabled());
        assertTrue(config.isTracingEnabled());
    }

    @Test
    void shouldGetRedisConfiguration() {
        config = new LockConfiguration(rawConfig);
        
        assertEquals("localhost:6379", config.getRedisHosts());
        assertEquals("password123", config.getRedisPassword());
        assertEquals(0, config.getRedisDatabase());
        assertEquals("distributed-lock", config.getRedisClientName());
        assertFalse(config.isRedisSslEnabled());
        assertNull(config.getRedisTrustStorePath());
        assertNull(config.getRedisTrustStorePassword());
    }

    @Test
    void shouldGetRedisPoolConfiguration() {
        config = new LockConfiguration(rawConfig);
        
        assertEquals(8, config.getRedisPoolMaxTotal());
        assertEquals(8, config.getRedisPoolMaxIdle());
        assertEquals(0, config.getRedisPoolMinIdle());
    }

    @Test
    void shouldGetZookeeperConfiguration() {
        config = new LockConfiguration(rawConfig);
        
        assertEquals("localhost:2181", config.getZookeeperConnectString());
        assertEquals("/distributed-locks", config.getZookeeperBasePath());
        assertEquals(Duration.ofSeconds(60), config.getZookeeperSessionTimeout());
        assertEquals(Duration.ofSeconds(15), config.getZookeeperConnectionTimeout());
        assertFalse(config.isZookeeperAuthEnabled());
        assertEquals("digest", config.getZookeeperAuthScheme());
        assertNull(config.getZookeeperAuthInfo());
        assertFalse(config.isZookeeperAclEnabled());
        assertEquals("ALL", config.getZookeeperAclPermissions());
        assertEquals("digest", config.getZookeeperAclScheme());
        assertNull(config.getZookeeperAclId());
    }

    @Test
    void shouldValidateConfigurationSuccessfully() {
        config = new LockConfiguration(rawConfig);
        
        assertDoesNotThrow(() -> config.validate());
    }

    @Test
    void shouldFailValidationWithNegativeLeaseTime() {
        Config invalidConfig = ConfigFactory.parseString("""
            distributed-lock {
                default-lease-time = -1s
            }
            """);
        config = new LockConfiguration(invalidConfig);
        
        ConfigException exception = assertThrows(ConfigException.class, () -> config.validate());
        assertTrue(exception.getMessage().contains("default-lease-time must be positive"));
    }

    @Test
    void shouldFailValidationWithZeroLeaseTime() {
        Config invalidConfig = ConfigFactory.parseString("""
            distributed-lock {
                default-lease-time = 0s
            }
            """);
        config = new LockConfiguration(invalidConfig);
        
        ConfigException exception = assertThrows(ConfigException.class, () -> config.validate());
        assertTrue(exception.getMessage().contains("default-lease-time must be positive"));
    }

    @Test
    void shouldFailValidationWithNegativeWaitTime() {
        Config invalidConfig = ConfigFactory.parseString("""
            distributed-lock {
                default-wait-time = -5s
            }
            """);
        config = new LockConfiguration(invalidConfig);
        
        ConfigException exception = assertThrows(ConfigException.class, () -> config.validate());
        assertTrue(exception.getMessage().contains("default-wait-time cannot be negative"));
    }

    @Test
    void shouldFailValidationWithInvalidWatchdogInterval() {
        Config invalidConfig = ConfigFactory.parseString("""
            distributed-lock {
                watchdog {
                    enabled = true
                    renewal-interval = 0s
                }
            }
            """);
        config = new LockConfiguration(invalidConfig);
        
        ConfigException exception = assertThrows(ConfigException.class, () -> config.validate());
        assertTrue(exception.getMessage().contains("watchdog.renewal-interval must be positive"));
    }

    @Test
    void shouldFailValidationWithNegativeRetryInterval() {
        Config invalidConfig = ConfigFactory.parseString("""
            distributed-lock {
                retry-interval = -100ms
            }
            """);
        config = new LockConfiguration(invalidConfig);
        
        ConfigException exception = assertThrows(ConfigException.class, () -> config.validate());
        assertTrue(exception.getMessage().contains("retry-interval must be positive"));
    }

    @Test
    void shouldFailValidationWithNegativeMaxRetries() {
        Config invalidConfig = ConfigFactory.parseString("""
            distributed-lock {
                max-retries = -1
            }
            """);
        config = new LockConfiguration(invalidConfig);
        
        ConfigException exception = assertThrows(ConfigException.class, () -> config.validate());
        assertTrue(exception.getMessage().contains("max-retries cannot be negative"));
    }

    @Test
    void shouldFailValidationWithInvalidRedisHostFormat() {
        Config invalidConfig = ConfigFactory.parseString("""
            distributed-lock {
                redis {
                    hosts = "localhost"
                }
            }
            """);
        config = new LockConfiguration(invalidConfig);
        
        ConfigException exception = assertThrows(ConfigException.class, () -> config.validate());
        assertTrue(exception.getMessage().contains("Redis host 'localhost' must be in format host:port"));
    }

    @Test
    void shouldValidateMultipleErrors() {
        Config invalidConfig = ConfigFactory.parseString("""
            distributed-lock {
                default-lease-time = -1s
                max-retries = -1
                redis {
                    hosts = "localhost"
                }
            }
            """);
        config = new LockConfiguration(invalidConfig);
        
        ConfigException exception = assertThrows(ConfigException.class, () -> config.validate());
        String message = exception.getMessage();
        assertTrue(message.contains("default-lease-time must be positive"));
        assertTrue(message.contains("max-retries cannot be negative"));
        assertTrue(message.contains("Redis host 'localhost' must be in format host:port"));
    }

    @Test
    void shouldUpdateConfiguration() {
        config = new LockConfiguration(rawConfig);
        
        Config newConfig = ConfigFactory.parseString("""
            distributed-lock {
                type = "zookeeper"
                default-lease-time = 60s
            }
            """);
        
        config.updateConfiguration(newConfig);
        
        assertEquals("zookeeper", config.getLockType().orElse(null));
        assertEquals(Duration.ofSeconds(60), config.getDefaultLeaseTime());
    }

    @Test
    void shouldNotifyChangeListeners() {
        config = new LockConfiguration(rawConfig);
        
        boolean[] notified = {false};
        Consumer<LockConfiguration> listener = lockConfig -> {
            notified[0] = true;
        };
        
        config.addChangeListener(listener);
        
        Config newConfig = ConfigFactory.parseString("distributed-lock.type = \"zookeeper\"");
        config.updateConfiguration(newConfig);
        
        assertTrue(notified[0]);
    }

    @Test
    void shouldRemoveChangeListener() {
        config = new LockConfiguration(rawConfig);
        
        boolean[] notified = {false};
        Consumer<LockConfiguration> listener = lockConfig -> {
            notified[0] = true;
        };
        
        config.addChangeListener(listener);
        config.removeChangeListener(listener);
        
        Config newConfig = ConfigFactory.parseString("distributed-lock.type = \"zookeeper\"");
        config.updateConfiguration(newConfig);
        
        assertFalse(notified[0]);
    }

    @Test
    void shouldHandleListenerException() {
        config = new LockConfiguration(rawConfig);
        
        Consumer<LockConfiguration> failingListener = lockConfig -> {
            throw new RuntimeException("Listener failed");
        };
        
        Consumer<LockConfiguration> workingListener = lockConfig -> {};
        
        config.addChangeListener(failingListener);
        config.addChangeListener(workingListener);
        
        // 应该不会抛出异常
        assertDoesNotThrow(() -> {
            Config newConfig = ConfigFactory.parseString("distributed-lock.type = \"zookeeper\"");
            config.updateConfiguration(newConfig);
        });
    }

    @Test
    void shouldHandleEmptyRedisHosts() {
        Config configWithEmptyRedis = ConfigFactory.parseString("""
            distributed-lock {
                redis {
                    hosts = ""
                }
            }
            """);
        config = new LockConfiguration(configWithEmptyRedis);
        
        assertDoesNotThrow(() -> config.validate());
        assertEquals("", config.getRedisHosts());
    }

    @Test
    void shouldHandleMultipleRedisHosts() {
        Config configWithMultipleHosts = ConfigFactory.parseString("""
            distributed-lock {
                redis {
                    hosts = "redis1:6379,redis2:6379,redis3:6380"
                }
            }
            """);
        config = new LockConfiguration(configWithMultipleHosts);
        
        assertDoesNotThrow(() -> config.validate());
        assertEquals("redis1:6379,redis2:6379,redis3:6380", config.getRedisHosts());
    }

    @Test
    void shouldHandleInvalidRedisHostInMultiple() {
        Config configWithInvalidMultiple = ConfigFactory.parseString("""
            distributed-lock {
                redis {
                    hosts = "redis1:6379,redis2,redis3:6380"
                }
            }
            """);
        config = new LockConfiguration(configWithInvalidMultiple);
        
        ConfigException exception = assertThrows(ConfigException.class, () -> config.validate());
        assertTrue(exception.getMessage().contains("Redis host 'redis2' must be in format host:port"));
    }

    @Test
    void shouldReturnNullForMissingOptionalFields() {
        Config minimalConfig = ConfigFactory.parseString("distributed-lock.type = \"redis\"");
        config = new LockConfiguration(minimalConfig);
        
        assertNull(config.getRedisPassword());
        assertNull(config.getRedisClientName());
        assertNull(config.getRedisTrustStorePath());
        assertNull(config.getRedisTrustStorePassword());
        assertNull(config.getZookeeperAuthInfo());
        assertNull(config.getZookeeperAclId());
    }

    @Test
    void shouldReturnDefaultValuesForMissingFields() {
        config = new LockConfiguration();
        
        assertEquals("localhost:6379", config.getRedisHosts());
        assertEquals(0, config.getRedisDatabase());
        assertFalse(config.isRedisSslEnabled());
        assertEquals(8, config.getRedisPoolMaxTotal());
        assertEquals(8, config.getRedisPoolMaxIdle());
        assertEquals(0, config.getRedisPoolMinIdle());
        assertEquals("localhost:2181", config.getZookeeperConnectString());
        assertEquals("/distributed-locks", config.getZookeeperBasePath());
        assertEquals(Duration.ofSeconds(60), config.getZookeeperSessionTimeout());
        assertEquals(Duration.ofSeconds(15), config.getZookeeperConnectionTimeout());
        assertFalse(config.isZookeeperAuthEnabled());
        assertEquals("digest", config.getZookeeperAuthScheme());
        assertFalse(config.isZookeeperAclEnabled());
        assertEquals("ALL", config.getZookeeperAclPermissions());
        assertEquals("digest", config.getZookeeperAclScheme());
    }

    @Test
    void shouldHandleEnvironmentProperties() {
        when(mockEnvironment.getProperty("distributed.lock.type", "")).thenReturn("environment-type");
        when(mockEnvironment.getProperty("distributed.lock.redis.password", "")).thenReturn("env-password");
        when(mockEnvironment.getProperty("distributed.lock.redis.hosts", "localhost:6379")).thenReturn("env-host:6379");
        
        config = new LockConfiguration(rawConfig, mockEnvironment);
        
        assertEquals("environment-type", config.getLockType().orElse(null));
        assertEquals("env-password", config.getRedisPassword());
        assertEquals("env-host:6379", config.getRedisHosts());
    }
}