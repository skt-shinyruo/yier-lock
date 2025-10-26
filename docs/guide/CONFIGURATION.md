# 配置指南

## 配置概述

分布式锁框架支持多种配置方式：

1. **配置文件**：使用 `application.conf`、`application.yml` 或 `application.properties`
2. **环境变量**：通过系统环境变量配置
3. **编程式配置**：通过 API 直接配置
4. **Spring Boot 配置**：自动配置和属性绑定

## 核心配置参数

### 基础配置

```hocon
distributed-lock {
  # 锁类型：redis 或 zookeeper
  type = "redis"

  # 默认租约时间
  default-lease-time = 30s

  # 默认等待时间
  default-wait-time = 10s

  # 重试间隔
  retry-interval = 100ms

  # 最大重试次数
  max-retries = 3
}
```

### 看门狗配置

```hocon
distributed-lock {
  watchdog {
    # 是否启用看门狗
    enabled = true

    # 续期间隔
    renewal-interval = 10s
  }
}
```

### 监控配置

```hocon
distributed-lock {
  metrics {
    # 是否启用指标收集
    enabled = true
  }

  tracing {
    # 是否启用追踪
    enabled = true
  }
}
```

## Redis 配置

### 单节点配置

```hocon
distributed-lock {
  redis {
    # Redis 主机地址
    hosts = "localhost:6379"

    # 密码（可选）
    password = "your-password"

    # 数据库索引
    database = 0

    # 客户端名称
    client-name = "distributed-lock-client"

    # SSL 配置
    ssl = false
    trust-store-path = "/path/to/truststore"
    trust-store-password = "truststore-password"
  }
}
```

### 集群配置

```hocon
distributed-lock {
  redis {
    # 集群节点
    hosts = "redis1:6379,redis2:6379,redis3:6379"

    # 密码
    password = "cluster-password"

    # 集群模式
    cluster = true
  }
}
```

### 连接池配置

```hocon
distributed-lock {
  redis {
    pool {
      # 最大连接数
      max-total = 8

      # 最大空闲连接数
      max-idle = 8

      # 最小空闲连接数
      min-idle = 0

      # 连接超时
      max-wait-millis = 5000

      # 连接测试
      test-on-borrow = true
      test-on-return = true
      test-while-idle = true
    }
  }
}
```

## ZooKeeper 配置

### 基础配置

```hocon
distributed-lock {
  zookeeper {
    # ZooKeeper 连接字符串
    connect-string = "zk1:2181,zk2:2181,zk3:2181"

    # 基础路径
    base-path = "/distributed-locks"

    # 会话超时
    session-timeout = 60s

    # 连接超时
    connection-timeout = 15s
  }
}
```

### 认证配置

```hocon
distributed-lock {
  zookeeper {
    # 启用认证
    auth-enabled = true

    # 认证方案
    auth-scheme = "digest"

    # 认证信息 (username:password)
    auth-info = "user:password"
  }
}
```

### ACL 配置

```hocon
distributed-lock {
  zookeeper {
    # 启用 ACL
    acl-enabled = true

    # ACL 权限
    acl {
      permissions = "ALL"
      scheme = "digest"
      id = "user:base64-encoded-password"
    }
  }
}
```

## Spring Boot 配置

### application.yml 配置

```yaml
distributed-lock:
  # 后端类型
  backend: redis

  # Redis 配置
  redis:
    cluster:
      nodes:
        - redis://localhost:6379
        - redis://localhost:6380
      password: your-password
    pool:
      max-total: 10
      max-idle: 10
      min-idle: 2

  # ZooKeeper 配置
  zookeeper:
    connect-string: zk1:2181,zk2:2181,zk3:2181
    base-path: /distributed-locks
    session-timeout: 60s
    connection-timeout: 15s

  # 监控配置
  monitoring:
    enabled: true
    metrics:
      enabled: true
    tracing:
      enabled: true

  # 看门狗配置
  watchdog:
    enabled: true
    renewal-interval: 10s

  # 性能配置
  performance:
    retry-count: 3
    retry-interval: 100ms
    connection-timeout: 5s
    operation-timeout: 30s
```

### application.properties 配置

```properties
# 后端类型
distributed-lock.backend=redis

# Redis 配置
distributed-lock.redis.hosts=localhost:6379
distributed-lock.redis.password=your-password
distributed-lock.redis.database=0

# 连接池配置
distributed-lock.redis.pool.max-total=8
distributed-lock.redis.pool.max-idle=8
distributed-lock.redis.pool.min-idle=0

# ZooKeeper 配置
distributed-lock.zookeeper.connect-string=zk1:2181,zk2:2181,zk3:2181
distributed-lock.zookeeper.base-path=/distributed-locks

# 监控配置
distributed-lock.monitoring.enabled=true
distributed-lock.metrics.enabled=true
distributed-lock.tracing.enabled=true

# 看门狗配置
distributed-lock.watchdog.enabled=true
distributed-lock.watchdog.renewal-interval=10s
```

## 环境变量配置

支持通过环境变量覆盖配置文件：

```bash
# 后端类型
export DISTRIBUTED_LOCK_BACKEND=redis

# Redis 配置
export DISTRIBUTED_LOCK_REDIS_HOSTS=redis://localhost:6379
export DISTRIBUTED_LOCK_REDIS_PASSWORD=your-password

# ZooKeeper 配置
export DISTRIBUTED_LOCK_ZOOKEEPER_CONNECT_STRING=zk1:2181,zk2:2181,zk3:2181

# 监控配置
export DISTRIBUTED_LOCK_MONITORING_ENABLED=true
export DISTRIBUTED_LOCK_METRICS_ENABLED=true
```

## 编程式配置

### 使用 LockConfigurationBuilder

```java
@Configuration
public class LockConfig {

    @Bean
    public DistributedLockFactory lockFactory() {
        LockConfiguration config = LockConfigurationBuilder.newBuilder()
            .backend(BackendType.REDIS)
            .redis(RedisConfig.newBuilder()
                .hosts("localhost:6379")
                .password("your-password")
                .database(0)
                .pool(PoolConfig.newBuilder()
                    .maxTotal(10)
                    .maxIdle(10)
                    .minIdle(2)
                    .build())
                .build())
            .watchdog(WatchdogConfig.newBuilder()
                .enabled(true)
                .renewalInterval(10, TimeUnit.SECONDS)
                .build())
            .monitoring(MonitoringConfig.newBuilder()
                .enabled(true)
                .metrics(true)
                .tracing(true)
                .build())
            .build();

        return new ConfiguredDistributedLockFactory(config);
    }
}
```

### 使用 LockConfiguration

```java
@Configuration
public class LockConfig {

    @Bean
    public LockConfiguration lockConfiguration() {
        return new LockConfiguration() {{
            // 直接设置配置
            setBackend(BackendType.REDIS);
            setRedisHosts("localhost:6379");
            setRedisPassword("your-password");
            setWatchdogEnabled(true);
            setMetricsEnabled(true);
        }};
    }

    @Bean
    public DistributedLockFactory lockFactory(LockConfiguration config) {
        return new ConfiguredDistributedLockFactory(config);
    }
}
```

## 配置验证

框架会在启动时自动验证配置的正确性：

```java
@Configuration
public class LockConfig {

    @Bean
    public DistributedLockFactory lockFactory(LockConfiguration config) {
        // 验证配置
        config.validate();

        return new ConfiguredDistributedLockFactory(config);
    }
}
```

配置验证包括：

- 时间参数必须为正数
- 主机地址格式正确
- 连接池参数合理
- 依赖项可用性检查

## 动态配置更新

支持运行时动态更新配置：

```java
@Service
public class LockConfigService {

    @Autowired
    private LockConfiguration lockConfig;

    @Autowired
    private DistributedLockFactory lockFactory;

    public void updateLeaseTime(long newLeaseTime, TimeUnit unit) {
        // 创建新的配置
        Config newConfig = ConfigFactory.parseString(
            "distributed-lock.default-lease-time = " + newLeaseTime + unit.toString().toLowerCase()
        ).withFallback(lockConfig.getConfig());

        // 更新配置
        lockConfig.updateConfiguration(newConfig);
    }

    // 监听配置变化
    @PostConstruct
    public void init() {
        lockConfig.addChangeListener(config -> {
            // 处理配置变化
            System.out.println("Configuration updated: " + config.getDefaultLeaseTime());
        });
    }
}
```

## 多环境配置

### 开发环境

```yaml
distributed-lock:
  backend: redis
  redis:
    hosts: localhost:6379
  monitoring:
    enabled: false
  metrics:
    enabled: false
```

### 测试环境

```yaml
distributed-lock:
  backend: redis
  redis:
    cluster:
      nodes:
        - redis://test-redis-1:6379
        - redis://test-redis-2:6379
  monitoring:
    enabled: true
  metrics:
    enabled: true
```

### 生产环境

```yaml
distributed-lock:
  backend: redis
  redis:
    cluster:
      nodes:
        - redis://prod-redis-1:6379
        - redis://prod-redis-2:6379
        - redis://prod-redis-3:6379
      password: ${REDIS_PASSWORD}
  monitoring:
    enabled: true
  metrics:
    enabled: true
  watchdog:
    enabled: true
  performance:
    retry-count: 5
    connection-timeout: 10s
```

## 配置最佳实践

### 1. 使用外部配置中心

```java
@Configuration
public class ExternalConfigLockConfig {

    @Value("${config.server.url}")
    private String configServerUrl;

    @Bean
    public LockConfiguration lockConfiguration() {
        // 从配置中心加载配置
        return loadFromConfigServer(configServerUrl);
    }
}
```

### 2. 配置分层管理

```yaml
# 基础配置
distributed-lock:
  backend: redis
  monitoring:
    enabled: true

# 环境特定配置
---
spring:
  profiles: development
distributed-lock:
  redis:
    hosts: localhost:6379

---
spring:
  profiles: production
distributed-lock:
  redis:
    cluster:
      nodes:
        - redis://prod-1:6379
        - redis://prod-2:6379
      password: ${REDIS_PASSWORD}
```

### 3. 配置监控和告警

```java
@Service
public class ConfigurationMonitor {

    @Autowired
    private LockConfiguration lockConfig;

    @Scheduled(fixedRate = 300000) // 5分钟检查一次
    public void monitorConfiguration() {
        try {
            lockConfig.validate();

            // 发送健康指标
            Metrics.gauge("config.health", 1.0);

        } catch (Exception e) {
            // 发送告警
            Metrics.gauge("config.health", 0.0);
            alertManager.sendAlert("Configuration validation failed: " + e.getMessage());
        }
    }
}
```

### 4. 配置热更新

```java
@RestController
@RequestMapping("/config")
public class ConfigurationController {

    @Autowired
    private LockConfiguration lockConfig;

    @Autowired
    private DistributedLockFactory lockFactory;

    @PostMapping("/update")
    public ResponseEntity<?> updateConfig(@RequestBody Map<String, Object> newConfig) {
        try {
            Config config = ConfigFactory.parseMap(newConfig);
            lockConfig.updateConfiguration(config);

            return ResponseEntity.ok("Configuration updated successfully");

        } catch (Exception e) {
            return ResponseEntity.badRequest()
                .body("Failed to update configuration: " + e.getMessage());
        }
    }
}
```

## 故障排查

### 配置验证失败

```
ConfigException: default-lease-time must be positive
```

**解决方案**：
- 检查时间单位配置
- 确保数值大于 0

### 连接失败

```
ConnectionException: Unable to connect to Redis
```

**解决方案**：
- 检查主机地址和端口
- 验证密码配置
- 确认网络连通性

### 配置不生效

**检查项**：
- Spring Profile 配置
- 环境变量优先级
- 配置文件位置
- 配置加载顺序