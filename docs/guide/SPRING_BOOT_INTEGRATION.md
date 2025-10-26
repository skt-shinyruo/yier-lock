# Spring Boot 集成指南

## 概述

分布式锁框架提供了完整的 Spring Boot 自动配置支持，包括自动配置类、AOP 切面、健康检查端点等。

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.mycorp</groupId>
    <artifactId>distributed-lock-spring-boot-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### 2. 基础配置

```yaml
# application.yml
distributed-lock:
  backend: redis
  redis:
    hosts: localhost:6379
  monitoring:
    enabled: true
```

### 3. 使用注解

```java
@Service
public class OrderService {

    @DistributedLock(key = "'order:' + #orderId")
    public void processOrder(String orderId) {
        // 业务逻辑
    }
}
```

## 自动配置详解

### 核心自动配置类

```java
@Configuration
@ConditionalOnClass(DistributedLockFactory.class)
@EnableConfigurationProperties(DistributedLockProperties.class)
@AutoConfigureAfter({RedisAutoConfiguration.class, ZookeeperAutoConfiguration.class})
public class DistributedLockAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public DistributedLockFactory distributedLockFactory(
            DistributedLockProperties properties,
            ObjectProvider<RedisClient> redisClient,
            ObjectProvider<CuratorFramework> curatorClient) {

        // 根据配置创建相应的工厂
        if ("redis".equals(properties.getBackend())) {
            return new RedisDistributedLockFactory(redisClient.getIfAvailable());
        } else if ("zookeeper".equals(properties.getBackend())) {
            return new ZooKeeperDistributedLockFactory(curatorClient.getIfAvailable());
        }

        throw new IllegalArgumentException("Unsupported backend: " + properties.getBackend());
    }

    @Bean
    @ConditionalOnMissingBean
    public DistributedLockAspect distributedLockAspect(DistributedLockFactory lockFactory) {
        return new DistributedLockAspect(lockFactory);
    }

    @Bean
    @ConditionalOnMissingBean
    public DistributedLockHealthIndicator distributedLockHealthIndicator(
            DistributedLockFactory lockFactory) {
        return new DistributedLockHealthIndicator(lockFactory);
    }
}
```

### 配置属性类

```java
@ConfigurationProperties("distributed-lock")
public class DistributedLockProperties {

    /**
     * 后端类型：redis 或 zookeeper
     */
    private String backend = "redis";

    /**
     * Redis 配置
     */
    private RedisProperties redis = new RedisProperties();

    /**
     * ZooKeeper 配置
     */
    private ZookeeperProperties zookeeper = new ZookeeperProperties();

    /**
     * 监控配置
     */
    private MonitoringProperties monitoring = new MonitoringProperties();

    // Getters and setters...

    public static class RedisProperties {
        private String hosts = "localhost:6379";
        private String password;
        private int database = 0;
        private PoolProperties pool = new PoolProperties();

        // Getters and setters...
    }

    public static class ZookeeperProperties {
        private String connectString = "localhost:2181";
        private String basePath = "/distributed-locks";
        private Duration sessionTimeout = Duration.ofSeconds(60);
        private Duration connectionTimeout = Duration.ofSeconds(15);

        // Getters and setters...
    }

    public static class MonitoringProperties {
        private boolean enabled = true;
        private MetricsProperties metrics = new MetricsProperties();
        private TracingProperties tracing = new TracingProperties();

        // Getters and setters...
    }
}
```

## 注解使用

### @DistributedLock 注解

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface DistributedLock {

    /**
     * 锁键表达式，支持 SpEL
     */
    String key();

    /**
     * 租约时间
     */
    long leaseTime() default 30;

    /**
     * 时间单位
     */
    TimeUnit timeUnit() default TimeUnit.SECONDS;

    /**
     * 等待时间
     */
    long waitTime() default 10;

    /**
     * 等待时间单位
     */
    TimeUnit waitTimeUnit() default TimeUnit.SECONDS;

    /**
     * 锁类型
     */
    LockType lockType() default LockType.MUTEX;

    /**
     * 是否自动续期
     */
    boolean autoRenewal() default false;

    /**
     * 续期比例
     */
    double renewalRatio() default 0.7;
}
```

### 使用示例

```java
@Service
public class OrderService {

    @DistributedLock(key = "'order:' + #orderId")
    public void processOrder(String orderId) {
        // 处理订单
        System.out.println("Processing order: " + orderId);
    }

    @DistributedLock(key = "'user:' + #userId", leaseTime = 60)
    public User getUserProfile(String userId) {
        // 获取用户资料
        return userRepository.findById(userId);
    }

    @DistributedLock(key = "'inventory:' + #productId",
                   waitTime = 5,
                   autoRenewal = true)
    public void updateInventory(String productId, int quantity) {
        // 更新库存
        inventoryRepository.updateStock(productId, quantity);
    }
}
```

### SpEL 表达式支持

```java
@Service
public class AdvancedService {

    @DistributedLock(key = "'service:' + #request.serviceName + ':user:' + #request.userId")
    public void processRequest(Request request) {
        // 处理请求
    }

    @DistributedLock(key = "'batch:' + T(java.util.UUID).randomUUID()")
    public void processBatch(List<Item> items) {
        // 批量处理
    }

    @DistributedLock(key = "'config:' + #configId + ':' + #T(java.time.LocalDate).now().toString()")
    public void updateConfig(String configId, ConfigData data) {
        // 更新配置
    }
}
```

## AOP 切面实现

### DistributedLockAspect

```java
@Aspect
@Component
public class DistributedLockAspect {

    private final DistributedLockFactory lockFactory;
    private final ExpressionParser parser = new SpelExpressionParser();

    public DistributedLockAspect(DistributedLockFactory lockFactory) {
        this.lockFactory = lockFactory;
    }

    @Around("@annotation(distributedLock)")
    public Object around(ProceedingJoinPoint joinPoint, DistributedLock distributedLock) throws Throwable {
        // 解析锁键
        String lockKey = parseLockKey(distributedLock.key(), joinPoint);

        // 获取锁
        DistributedLock lock = lockFactory.getLock(lockKey);

        try {
            // 尝试获取锁
            boolean acquired = lock.tryLock(
                distributedLock.waitTime(),
                distributedLock.leaseTime(),
                distributedLock.timeUnit()
            );

            if (!acquired) {
                throw new LockAcquisitionException(lockKey,
                    distributedLock.waitTime(), distributedLock.waitTimeUnit());
            }

            // 设置自动续期
            ScheduledFuture<?> renewalTask = null;
            if (distributedLock.autoRenewal()) {
                renewalTask = lock.scheduleAutoRenewal(
                    calculateRenewalInterval(distributedLock),
                    distributedLock.timeUnit()
                );
            }

            try {
                // 执行目标方法
                return joinPoint.proceed();
            } finally {
                // 取消自动续期
                if (renewalTask != null) {
                    lock.cancelAutoRenewal(renewalTask);
                }
            }

        } finally {
            // 释放锁
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private String parseLockKey(String keyExpression, ProceedingJoinPoint joinPoint) {
        EvaluationContext context = createEvaluationContext(joinPoint);
        Expression expression = parser.parseExpression(keyExpression);
        return expression.getValue(context, String.class);
    }

    private EvaluationContext createEvaluationContext(ProceedingJoinPoint joinPoint) {
        StandardEvaluationContext context = new StandardEvaluationContext();

        // 添加方法参数
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        String[] parameterNames = signature.getParameterNames();
        Object[] args = joinPoint.getArgs();

        for (int i = 0; i < parameterNames.length; i++) {
            context.setVariable(parameterNames[i], args[i]);
        }

        // 添加目标对象
        context.setVariable("target", joinPoint.getTarget());

        return context;
    }

    private long calculateRenewalInterval(DistributedLock distributedLock) {
        return (long) (distributedLock.leaseTime() * distributedLock.renewalRatio());
    }
}
```

## 健康检查集成

### DistributedLockHealthIndicator

```java
@Component
public class DistributedLockHealthIndicator implements HealthIndicator {

    private final DistributedLockFactory lockFactory;

    public DistributedLockHealthIndicator(DistributedLockFactory lockFactory) {
        this.lockFactory = lockFactory;
    }

    @Override
    public Health health() {
        try {
            // 执行健康检查
            FactoryHealthStatus status = lockFactory.healthCheck();

            if (status.isHealthy()) {
                return Health.up()
                    .withDetail("backend", getBackendType())
                    .withDetail("responseTime", status.getPerformanceMetrics().getResponseTimeMs() + "ms")
                    .withDetail("activeLocks", lockFactory.getStatistics().getActiveLocks())
                    .withDetail("totalOperations", lockFactory.getStatistics().getTotalLockAcquisitions())
                    .build();
            } else {
                return Health.down()
                    .withDetail("error", status.getErrorMessage())
                    .withDetail("backend", getBackendType())
                    .build();
            }

        } catch (Exception e) {
            return Health.down()
                .withDetail("exception", e.getClass().getSimpleName())
                .withDetail("message", e.getMessage())
                .withDetail("backend", getBackendType())
                .build();
        }
    }

    private String getBackendType() {
        return lockFactory.getClass().getSimpleName().replace("DistributedLockFactory", "").toLowerCase();
    }
}
```

### 健康检查端点

```bash
# 查看健康状态
curl http://localhost:8080/actuator/health

# 响应示例
{
  "status": "UP",
  "components": {
    "distributedLock": {
      "status": "UP",
      "details": {
        "backend": "redis",
        "responseTime": "5ms",
        "activeLocks": 2,
        "totalOperations": 150
      }
    }
  }
}
```

## 监控指标集成

### Micrometer 指标收集

```java
@Configuration
@ConditionalOnClass(MeterRegistry.class)
public class MetricsConfiguration {

    @Bean
    public LockMetricsCollector lockMetricsCollector(
            DistributedLockFactory lockFactory,
            MeterRegistry meterRegistry) {

        return new LockMetricsCollector(lockFactory, meterRegistry);
    }
}

public class LockMetricsCollector {

    private final MeterRegistry registry;
    private final DistributedLockFactory lockFactory;

    public LockMetricsCollector(DistributedLockFactory lockFactory, MeterRegistry registry) {
        this.lockFactory = lockFactory;
        this.registry = registry;

        // 注册指标
        registerMetrics();
    }

    private void registerMetrics() {
        // 活跃锁数量
        Gauge.builder("distributed_lock.active", lockFactory.getStatistics()::getActiveLocks)
            .description("Number of currently active locks")
            .register(registry);

        // 锁获取总次数
        Counter.builder("distributed_lock.acquisitions_total")
            .description("Total number of lock acquisitions")
            .register(registry);

        // 锁获取失败次数
        Counter.builder("distributed_lock.acquisition_failures_total")
            .description("Total number of failed lock acquisitions")
            .register(registry);

        // 锁持有时间分布
        Timer.builder("distributed_lock.hold_duration")
            .description("Time locks are held")
            .register(registry);
    }

    public void recordLockAcquisition(String lockName, boolean success, long duration) {
        registry.counter("distributed_lock.acquisitions_total",
            "success", String.valueOf(success),
            "lock_name", lockName)
            .increment();

        if (success) {
            registry.timer("distributed_lock.hold_duration",
                "lock_name", lockName)
                .record(duration, TimeUnit.MILLISECONDS);
        }
    }
}
```

### Prometheus 端点

```yaml
# application.yml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  metrics:
    export:
      prometheus:
        enabled: true
```

```bash
# 查看 Prometheus 格式的指标
curl http://localhost:8080/actuator/prometheus | grep distributed_lock

# 输出示例
# HELP distributed_lock_active Number of currently active locks
# TYPE distributed_lock_active gauge
distributed_lock_active 3.0

# HELP distributed_lock_acquisitions_total_total Total number of lock acquisitions
# TYPE distributed_lock_acquisitions_total_total counter
distributed_lock_acquisitions_total_total{success="true",lock_name="order:123"} 45.0
```

## 条件配置

### 条件注解使用

```java
@Configuration
public class ConditionalConfiguration {

    @Bean
    @ConditionalOnProperty(name = "distributed-lock.backend", havingValue = "redis")
    public RedisDistributedLockFactory redisLockFactory(RedisClient redisClient) {
        return new RedisDistributedLockFactory(redisClient);
    }

    @Bean
    @ConditionalOnProperty(name = "distributed-lock.backend", havingValue = "zookeeper")
    public ZooKeeperDistributedLockFactory zookeeperLockFactory(CuratorFramework curator) {
        return new ZooKeeperDistributedLockFactory(curator);
    }

    @Bean
    @ConditionalOnBean(DistributedLockFactory.class)
    public DistributedLockAspect distributedLockAspect(DistributedLockFactory lockFactory) {
        return new DistributedLockAspect(lockFactory);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "distributed-lock.monitoring.enabled", havingValue = "true", matchIfMissing = true)
    public LockMonitoringService lockMonitoringService(DistributedLockFactory lockFactory) {
        return new LockMonitoringService(lockFactory);
    }
}
```

## 自定义配置

### 扩展配置属性

```java
@ConfigurationProperties("distributed-lock")
public class ExtendedDistributedLockProperties extends DistributedLockProperties {

    /**
     * 自定义重试配置
     */
    private RetryProperties retry = new RetryProperties();

    /**
     * 自定义缓存配置
     */
    private CacheProperties cache = new CacheProperties();

    // Getters and setters...

    public static class RetryProperties {
        private int maxAttempts = 3;
        private long initialDelay = 100;
        private double multiplier = 2.0;
        private long maxDelay = 5000;

        // Getters and setters...
    }

    public static class CacheProperties {
        private boolean enabled = true;
        private int maximumSize = 1000;
        private Duration expireAfterWrite = Duration.ofMinutes(10);

        // Getters and setters...
    }
}
```

### 自定义自动配置

```java
@Configuration
@AutoConfigureAfter(DistributedLockAutoConfiguration.class)
@EnableConfigurationProperties(ExtendedDistributedLockProperties.class)
public class ExtendedDistributedLockAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public RetryableDistributedLockAspect retryableLockAspect(
            DistributedLockFactory lockFactory,
            ExtendedDistributedLockProperties properties) {

        return new RetryableDistributedLockAspect(
            lockFactory,
            properties.getRetry().getMaxAttempts(),
            properties.getRetry().getInitialDelay(),
            properties.getRetry().getMultiplier(),
            properties.getRetry().getMaxDelay()
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public LockCacheManager lockCacheManager(ExtendedDistributedLockProperties properties) {
        if (properties.getCache().isEnabled()) {
            return new CaffeineLockCacheManager(
                properties.getCache().getMaximumSize(),
                properties.getCache().getExpireAfterWrite()
            );
        }
        return new NoOpLockCacheManager();
    }
}
```

## 事件监听集成

### Spring 事件发布

```java
@Component
public class SpringEventPublisher implements LockEventListener<DistributedLock> {

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Override
    public void onLockEvent(LockEvent<DistributedLock> event) {
        // 转换为 Spring 事件
        DistributedLockEvent springEvent = new DistributedLockEvent(
            event.getLock(),
            event.getEventType(),
            event.getTimestamp(),
            event.getThreadName()
        );

        eventPublisher.publishEvent(springEvent);
    }
}

// Spring 事件类
public class DistributedLockEvent extends ApplicationEvent {

    private final DistributedLock lock;
    private final LockEventType eventType;
    private final long timestamp;
    private final String threadName;

    // Constructor, getters...
}
```

### 事件监听器

```java
@Component
public class LockEventHandler {

    @EventListener
    public void handleLockAcquired(DistributedLockEvent event) {
        if (event.getEventType() == LockEventType.LOCK_ACQUIRED) {
            System.out.println("Lock acquired: " + event.getLock().getName() +
                             " by thread: " + event.getThreadName());
        }
    }

    @EventListener
    public void handleLockReleased(DistributedLockEvent event) {
        if (event.getEventType() == LockEventType.LOCK_RELEASED) {
            System.out.println("Lock released: " + event.getLock().getName());
        }
    }
}
```

## 测试集成

### 测试配置

```java
@SpringBootTest
@ActiveProfiles("test")
public class DistributedLockIntegrationTest {

    @Autowired
    private DistributedLockFactory lockFactory;

    @Autowired
    private OrderService orderService;

    @Test
    public void testDistributedLockAnnotation() {
        // 测试注解生效
        orderService.processOrder("test-order-123");

        // 验证锁被正确获取和释放
        DistributedLock lock = lockFactory.getLock("order:test-order-123");
        assertFalse(lock.isLocked());
    }

    @Test
    public void testConcurrentAccess() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(2);
        AtomicInteger counter = new AtomicInteger(0);

        // 启动两个线程同时处理同一个订单
        ExecutorService executor = Executors.newFixedThreadPool(2);

        for (int i = 0; i < 2; i++) {
            executor.submit(() -> {
                try {
                    orderService.processOrder("concurrent-order");
                    counter.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS));
        assertEquals(2, counter.get()); // 两个请求都应该成功，但顺序执行
    }
}
```

### 测试配置文件

```yaml
# application-test.yml
distributed-lock:
  backend: redis
  redis:
    hosts: localhost:6379
  monitoring:
    enabled: false  # 测试环境禁用监控

spring:
  redis:
    host: localhost
    port: 6379
```

## 最佳实践

### 1. 配置外部化

```yaml
# 按环境分离配置
distributed-lock:
  backend: ${LOCK_BACKEND:redis}
  redis:
    hosts: ${REDIS_HOSTS:localhost:6379}
    password: ${REDIS_PASSWORD:}
  zookeeper:
    connect-string: ${ZK_CONNECT_STRING:localhost:2181}
```

### 2. 条件配置

```java
@Configuration
public class EnvironmentSpecificConfiguration {

    @Bean
    @Profile("production")
    public DistributedLockFactory productionLockFactory() {
        // 生产环境配置
        return createLockFactory("redis-cluster:6379", "prod-password");
    }

    @Bean
    @Profile("staging")
    public DistributedLockFactory stagingLockFactory() {
        // 预发环境配置
        return createLockFactory("redis-staging:6379", "staging-password");
    }

    @Bean
    @Profile({"development", "test"})
    public DistributedLockFactory developmentLockFactory() {
        // 开发/测试环境配置
        return createLockFactory("localhost:6379", null);
    }

    private DistributedLockFactory createLockFactory(String hosts, String password) {
        // 创建工厂的逻辑
        return new RedisDistributedLockFactory(hosts, password);
    }
}
```

### 3. 监控和告警

```java
@Service
public class LockHealthMonitor {

    @Autowired
    private HealthEndpoint healthEndpoint;

    @Scheduled(fixedRate = 30000) // 30秒检查一次
    public void monitorLockHealth() {
        Health health = healthEndpoint.health();

        HealthComponent distributedLock = health.getComponents().get("distributedLock");
        if (distributedLock != null && distributedLock.getStatus() != Status.UP) {
            // 发送告警
            alertService.sendAlert("Distributed lock service is unhealthy: " +
                                 distributedLock.getDetails());
        }
    }
}
```

### 4. 优雅关闭

```java
@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(Application.class);

        // 添加关闭钩子
        app.addListeners(new ApplicationListener<ContextClosedEvent>() {
            @Override
            public void onApplicationEvent(ContextClosedEvent event) {
                DistributedLockFactory lockFactory =
                    event.getApplicationContext().getBean(DistributedLockFactory.class);

                // 优雅关闭
                lockFactory.gracefulShutdown(30, TimeUnit.SECONDS);
            }
        });

        app.run(args);
    }
}
```

## 总结

Spring Boot 集成提供了以下特性：

1. **自动配置**：开箱即用的配置和组件初始化
2. **注解驱动**：通过注解简化锁的使用
3. **健康检查**：集成 Spring Boot Actuator 健康检查
4. **监控指标**：Micrometer 指标收集和 Prometheus 导出
5. **条件配置**：基于环境的灵活配置
6. **事件集成**：与 Spring 事件系统的集成
7. **测试支持**：完整的测试集成和配置

通过 Spring Boot 集成，开发者可以快速将分布式锁功能集成到现有的 Spring Boot 应用中，享受声明式编程和自动配置带来的便利。