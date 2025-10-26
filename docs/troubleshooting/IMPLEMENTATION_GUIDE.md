# 实现指南

## 概述

本指南为开发者提供实现分布式锁框架的详细指导，包括架构设计、核心组件实现、扩展开发等内容。

## 架构设计原则

### 1. 接口分离原则

框架采用接口分离设计，将不同职责分离到不同的接口中：

```java
// 核心锁接口 - 定义锁的基本操作
public interface DistributedLock extends AutoCloseable {
    void lock(long leaseTime, TimeUnit unit);
    boolean tryLock(long waitTime, long leaseTime, TimeUnit unit);
    void unlock();
    // ...
}

// 工厂接口 - 定义锁的创建和管理
public interface DistributedLockFactory extends AutoCloseable {
    DistributedLock getLock(String name);
    DistributedReadWriteLock getReadWriteLock(String name);
    FactoryHealthStatus healthCheck();
    // ...
}

// 配置接口 - 定义配置管理
public interface LockConfiguration {
    Duration getDefaultLeaseTime();
    Duration getDefaultWaitTime();
    boolean isWatchdogEnabled();
    // ...
}
```

### 2. 提供者模式

使用 SPI 机制实现后端扩展：

```java
// 服务提供者接口
public interface LockProvider {
    DistributedLockFactory createFactory(LockConfiguration config);
    String getProviderName();
    boolean supportsBackend(String backendType);
}

// 注册服务提供者
// META-INF/services/com.mycorp.distributedlock.api.LockProvider
com.mycorp.distributedlock.redis.RedisLockProvider
com.mycorp.distributedlock.zookeeper.ZookeeperLockProvider
```

### 3. 建造者模式

配置使用建造者模式提供流式 API：

```java
public interface LockConfigurationBuilder {
    LockConfigurationBuilder leaseTime(long time, TimeUnit unit);
    LockConfigurationBuilder waitTime(long time, TimeUnit unit);
    LockConfigurationBuilder backend(String backend);
    LockConfiguration build();
}

// 使用示例
LockConfiguration config = LockConfigurationBuilder.newBuilder()
    .backend("redis")
    .leaseTime(30, TimeUnit.SECONDS)
    .waitTime(10, TimeUnit.SECONDS)
    .watchdogEnabled(true)
    .build();
```

## 核心组件实现

### 1. 锁实现基类

```java
public abstract class AbstractDistributedLock implements DistributedLock {

    protected final String name;
    protected final LockConfiguration config;
    protected final PerformanceMonitor monitor;

    protected AbstractDistributedLock(String name, LockConfiguration config) {
        this.name = name;
        this.config = config;
        this.monitor = new PerformanceMonitor();
    }

    @Override
    public void lock(long leaseTime, TimeUnit unit) throws InterruptedException {
        long startTime = System.nanoTime();
        try {
            doLock(leaseTime, unit);
            monitor.recordOperation("lock", System.nanoTime() - startTime, true);
        } catch (Exception e) {
            monitor.recordOperation("lock", System.nanoTime() - startTime, false);
            throw e;
        }
    }

    protected abstract void doLock(long leaseTime, TimeUnit unit) throws InterruptedException;

    // 其他方法的实现...
}
```

### 2. 工厂实现基类

```java
public abstract class AbstractDistributedLockFactory implements DistributedLockFactory {

    protected final LockConfiguration config;
    protected final ConnectionManager connectionManager;
    protected final EventManager eventManager;

    protected AbstractDistributedLockFactory(LockConfiguration config) {
        this.config = config;
        this.connectionManager = createConnectionManager();
        this.eventManager = createEventManager();
    }

    @Override
    public DistributedLock getLock(String name) {
        validateLockName(name);
        return createLock(name, config);
    }

    protected abstract DistributedLock createLock(String name, LockConfiguration config);
    protected abstract ConnectionManager createConnectionManager();
    protected abstract EventManager createEventManager();

    private void validateLockName(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Lock name cannot be null or empty");
        }
        if (name.length() > 256) {
            throw new IllegalArgumentException("Lock name too long: " + name.length());
        }
    }
}
```

### 3. 连接管理器

```java
public interface ConnectionManager {
    Connection getConnection();
    void returnConnection(Connection connection);
    void close();
    HealthStatus healthCheck();
}

public class PooledConnectionManager implements ConnectionManager {

    private final GenericObjectPool<Connection> pool;

    public PooledConnectionManager(ConnectionFactory factory, PoolConfig config) {
        this.pool = new GenericObjectPool<>(factory, createPoolConfig(config));
    }

    @Override
    public Connection getConnection() throws Exception {
        return pool.borrowObject();
    }

    @Override
    public void returnConnection(Connection connection) {
        pool.returnObject(connection);
    }

    @Override
    public HealthStatus healthCheck() {
        // 实现健康检查逻辑
        return new HealthStatus(pool.getNumActive(), pool.getNumIdle());
    }

    private GenericObjectPoolConfig createPoolConfig(PoolConfig config) {
        GenericObjectPoolConfig poolConfig = new GenericObjectPoolConfig();
        poolConfig.setMaxTotal(config.getMaxTotal());
        poolConfig.setMaxIdle(config.getMaxIdle());
        poolConfig.setMinIdle(config.getMinIdle());
        poolConfig.setMaxWaitMillis(config.getMaxWaitMillis());
        poolConfig.setTestOnBorrow(true);
        poolConfig.setTestOnReturn(true);
        return poolConfig;
    }
}
```

## Redis 后端实现

### 1. Redis 锁实现

```java
public class RedisDistributedLock extends AbstractDistributedLock {

    private final RedisClient redisClient;
    private final String lockKey;
    private final String lockValue;

    public RedisDistributedLock(String name, RedisClient redisClient, LockConfiguration config) {
        super(name, config);
        this.redisClient = redisClient;
        this.lockKey = "distributed:lock:" + name;
        this.lockValue = UUID.randomUUID().toString();
    }

    @Override
    protected void doLock(long leaseTime, TimeUnit unit) throws InterruptedException {
        long leaseMillis = unit.toMillis(leaseTime);

        while (true) {
            // 尝试获取锁
            Boolean acquired = redisClient.set(lockKey, lockValue, SetArgs.Builder.px(leaseMillis).nx());

            if (Boolean.TRUE.equals(acquired)) {
                // 获取锁成功
                return;
            }

            // 获取锁失败，检查是否超时
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }

            // 等待后重试
            Thread.sleep(100);
        }
    }

    @Override
    public boolean tryLock(long waitTime, long leaseTime, TimeUnit unit) throws InterruptedException {
        long waitMillis = unit.toMillis(waitTime);
        long leaseMillis = unit.toMillis(leaseTime);
        long startTime = System.currentTimeMillis();

        while (System.currentTimeMillis() - startTime < waitMillis) {
            Boolean acquired = redisClient.set(lockKey, lockValue, SetArgs.Builder.px(leaseMillis).nx());

            if (Boolean.TRUE.equals(acquired)) {
                return true;
            }

            if (Thread.interrupted()) {
                throw new InterruptedException();
            }

            Thread.sleep(100);
        }

        return false;
    }

    @Override
    public void unlock() {
        // 使用 Lua 脚本确保原子性
        String script = """
            if redis.call('get', KEYS[1]) == ARGV[1] then
                return redis.call('del', KEYS[1])
            else
                return 0
            end
            """;

        redisClient.eval(script, ScriptOutputType.INTEGER, new String[]{lockKey}, lockValue);
    }

    @Override
    public boolean isLocked() {
        String value = redisClient.get(lockKey);
        return lockValue.equals(value);
    }
}
```

### 2. Redis 工厂实现

```java
public class RedisDistributedLockFactory extends AbstractDistributedLockFactory {

    private final RedisClient redisClient;

    public RedisDistributedLockFactory(LockConfiguration config) {
        super(config);
        this.redisClient = createRedisClient(config);
    }

    @Override
    protected DistributedLock createLock(String name, LockConfiguration config) {
        return new RedisDistributedLock(name, redisClient, config);
    }

    @Override
    protected ConnectionManager createConnectionManager() {
        return new RedisConnectionManager(redisClient);
    }

    private RedisClient createRedisClient(LockConfiguration config) {
        RedisURI redisUri = RedisURI.Builder.redis(config.getRedisHosts())
            .withPassword(config.getRedisPassword())
            .withDatabase(config.getRedisDatabase())
            .build();

        return RedisClient.create(redisUri);
    }
}
```

## ZooKeeper 后端实现

### 1. ZooKeeper 锁实现

```java
public class ZooKeeperDistributedLock extends AbstractDistributedLock {

    private final CuratorFramework client;
    private final InterProcessMutex mutex;

    public ZooKeeperDistributedLock(String name, CuratorFramework client, LockConfiguration config) {
        super(name, config);
        this.client = client;
        this.mutex = new InterProcessMutex(client, "/distributed-locks/" + name);
    }

    @Override
    protected void doLock(long leaseTime, TimeUnit unit) throws InterruptedException {
        try {
            mutex.acquire();
        } catch (Exception e) {
            throw new DistributedLockException("Failed to acquire ZooKeeper lock", e);
        }
    }

    @Override
    public boolean tryLock(long waitTime, long leaseTime, TimeUnit unit) throws InterruptedException {
        try {
            return mutex.acquire(waitTime, unit);
        } catch (Exception e) {
            throw new DistributedLockException("Failed to try acquire ZooKeeper lock", e);
        }
    }

    @Override
    public void unlock() {
        try {
            mutex.release();
        } catch (Exception e) {
            throw new DistributedLockException("Failed to release ZooKeeper lock", e);
        }
    }

    @Override
    public boolean isLocked() {
        return mutex.isAcquiredInThisProcess();
    }
}
```

### 2. ZooKeeper 工厂实现

```java
public class ZooKeeperDistributedLockFactory extends AbstractDistributedLockFactory {

    private final CuratorFramework client;

    public ZooKeeperDistributedLockFactory(LockConfiguration config) {
        super(config);
        this.client = createCuratorClient(config);
    }

    @Override
    protected DistributedLock createLock(String name, LockConfiguration config) {
        return new ZooKeeperDistributedLock(name, client, config);
    }

    @Override
    protected ConnectionManager createConnectionManager() {
        return new ZooKeeperConnectionManager(client);
    }

    private CuratorFramework createCuratorClient(LockConfiguration config) {
        return CuratorFrameworkFactory.newClient(
            config.getZookeeperConnectString(),
            new ExponentialBackoffRetry(1000, 3)
        );
    }
}
```

## Spring Boot 集成实现

### 1. 自动配置类

```java
@Configuration
@ConditionalOnClass(DistributedLockFactory.class)
@EnableConfigurationProperties(DistributedLockProperties.class)
@AutoConfigureAfter({RedisAutoConfiguration.class})
public class DistributedLockAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public DistributedLockFactory distributedLockFactory(
            DistributedLockProperties properties,
            ObjectProvider<RedisClient> redisClient,
            ObjectProvider<CuratorFramework> curatorClient) {

        LockConfiguration config = createLockConfiguration(properties);

        if ("redis".equals(properties.getBackend())) {
            return new RedisDistributedLockFactory(config);
        } else if ("zookeeper".equals(properties.getBackend())) {
            return new ZooKeeperDistributedLockFactory(config);
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

    private LockConfiguration createLockConfiguration(DistributedLockProperties properties) {
        return LockConfigurationBuilder.newBuilder()
            .backend(properties.getBackend())
            .leaseTime(properties.getDefaultLeaseTime().toMillis(), TimeUnit.MILLISECONDS)
            .waitTime(properties.getDefaultWaitTime().toMillis(), TimeUnit.MILLISECONDS)
            .watchdogEnabled(properties.getWatchdog().isEnabled())
            .metricsEnabled(properties.getMetrics().isEnabled())
            .build();
    }
}
```

### 2. AOP 切面实现

```java
@Aspect
@Component
public class DistributedLockAspect {

    private final DistributedLockFactory lockFactory;
    private final ExpressionEvaluator evaluator = new ExpressionEvaluator();

    public DistributedLockAspect(DistributedLockFactory lockFactory) {
        this.lockFactory = lockFactory;
    }

    @Around("@annotation(distributedLock)")
    public Object aroundDistributedLock(ProceedingJoinPoint joinPoint,
                                      DistributedLock distributedLock) throws Throwable {

        // 解析锁键
        String lockKey = parseLockKey(distributedLock.key(), joinPoint);

        // 获取锁
        DistributedLock lock = lockFactory.getLock(lockKey);

        try {
            // 尝试获取锁
            boolean acquired = lock.tryLock(
                distributedLock.waitTime(),
                distributedLock.leaseTime(),
                TimeUnit.SECONDS
            );

            if (!acquired) {
                throw new LockAcquisitionException(lockKey,
                    distributedLock.waitTime(), TimeUnit.SECONDS);
            }

            // 执行目标方法
            return joinPoint.proceed();

        } finally {
            // 确保锁被释放
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private String parseLockKey(String keyExpression, ProceedingJoinPoint joinPoint) {
        if (!keyExpression.contains("#")) {
            return keyExpression; // 静态键
        }

        // SpEL 表达式解析
        EvaluationContext context = createEvaluationContext(joinPoint);
        return evaluator.evaluate(keyExpression, context, String.class);
    }

    private EvaluationContext createEvaluationContext(ProceedingJoinPoint joinPoint) {
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        Object[] args = joinPoint.getArgs();
        String[] paramNames = ((MethodSignature) joinPoint.getSignature()).getParameterNames();

        StandardEvaluationContext context = new StandardEvaluationContext();

        // 添加方法参数
        for (int i = 0; i < paramNames.length; i++) {
            context.setVariable(paramNames[i], args[i]);
        }

        return context;
    }
}
```

## 监控和指标实现

### 1. 性能监控器

```java
@Component
public class PerformanceMonitor {

    private final MeterRegistry registry;
    private final Map<String, Timer.Sample> activeSamples = new ConcurrentHashMap<>();

    public PerformanceMonitor(MeterRegistry registry) {
        this.registry = registry;
    }

    public void startOperation(String operationName, String lockName) {
        String key = operationName + ":" + lockName;
        Timer.Sample sample = Timer.start(registry);
        activeSamples.put(key, sample);
    }

    public void endOperation(String operationName, String lockName, boolean success) {
        String key = operationName + ":" + lockName;
        Timer.Sample sample = activeSamples.remove(key);

        if (sample != null) {
            sample.stop(Timer.builder("distributed_lock.operation")
                .tag("operation", operationName)
                .tag("lock", lockName)
                .tag("success", String.valueOf(success))
                .register(registry));
        }
    }

    public void recordError(String operationName, String lockName, Throwable error) {
        registry.counter("distributed_lock.errors",
            "operation", operationName,
            "lock", lockName,
            "error_type", error.getClass().getSimpleName())
            .increment();
    }
}
```

### 2. 健康检查实现

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
                    .withDetail("backend", "connected")
                    .withDetail("responseTime", status.getPerformanceMetrics().getResponseTimeMs() + "ms")
                    .withDetail("activeLocks", getActiveLocksCount())
                    .build();
            } else {
                return Health.down()
                    .withDetail("error", status.getErrorMessage())
                    .build();
            }

        } catch (Exception e) {
            return Health.down()
                .withDetail("error", e.getMessage())
                .withException(e)
                .build();
        }
    }

    private int getActiveLocksCount() {
        try {
            return lockFactory.getActiveLocks().size();
        } catch (Exception e) {
            return -1; // 无法获取
        }
    }
}
```

## 测试实现

### 1. 单元测试基类

```java
@SpringBootTest
public abstract class AbstractLockTest {

    @Autowired
    protected DistributedLockFactory lockFactory;

    @Autowired
    protected LockConfiguration lockConfig;

    protected DistributedLock createLock(String name) {
        return lockFactory.getLock(name);
    }

    @BeforeEach
    void setUp() {
        // 清理测试数据
        cleanupTestData();
    }

    @AfterEach
    void tearDown() {
        // 清理测试锁
        cleanupTestLocks();
    }

    protected abstract void cleanupTestData();
    protected abstract void cleanupTestLocks();
}
```

### 2. 并发测试

```java
public class ConcurrentLockTest extends AbstractLockTest {

    @Test
    public void testConcurrentLockAcquisition() throws InterruptedException {
        String lockName = "concurrent-test";
        int threadCount = 10;
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                DistributedLock lock = createLock(lockName);
                try {
                    boolean acquired = lock.tryLock(5, 10, TimeUnit.SECONDS);
                    if (acquired) {
                        successCount.incrementAndGet();
                        Thread.sleep(1000); // 模拟工作
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    if (lock.isHeldByCurrentThread()) {
                        lock.unlock();
                    }
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS));
        assertEquals(1, successCount.get()); // 同一时间只有一个线程能获取锁
    }
}
```

## 扩展开发

### 1. 自定义后端实现

```java
public class CustomDistributedLockFactory implements DistributedLockFactory {

    private final CustomClient client;

    @Override
    public DistributedLock getLock(String name) {
        return new CustomDistributedLock(name, client);
    }

    // 实现其他方法...
}

public class CustomLockProvider implements LockProvider {

    @Override
    public DistributedLockFactory createFactory(LockConfiguration config) {
        return new CustomDistributedLockFactory(config);
    }

    @Override
    public String getProviderName() {
        return "custom";
    }

    @Override
    public boolean supportsBackend(String backendType) {
        return "custom".equals(backendType);
    }
}
```

### 2. 自定义注解

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CustomDistributedLock {

    String key();
    long leaseTime() default 30;
    String strategy() default "default"; // 自定义策略
}

@Aspect
@Component
public class CustomDistributedLockAspect {

    @Around("@annotation(customLock)")
    public Object aroundCustomLock(ProceedingJoinPoint joinPoint,
                                 CustomDistributedLock customLock) throws Throwable {

        // 实现自定义逻辑
        String strategy = customLock.strategy();
        LockStrategy lockStrategy = getLockStrategy(strategy);

        return lockStrategy.execute(joinPoint, customLock);
    }
}
```

## 性能优化

### 1. 连接池优化

```java
@Configuration
public class ConnectionPoolOptimizationConfig {

    @Bean
    public ConnectionPoolFactory connectionPoolFactory(LockConfiguration config) {
        return new OptimizedConnectionPoolFactory() {{
            setMaxTotal(calculateOptimalPoolSize(config));
            setMaxIdle(getMaxIdle());
            setMinIdle(getMinIdle());
            setMaxWaitMillis(5000);
            setTestOnBorrow(true);
            setTestOnReturn(false);
            setTestWhileIdle(true);
        }};
    }

    private int calculateOptimalPoolSize(LockConfiguration config) {
        // 基于 CPU 核心数和预期负载计算
        int cpuCores = Runtime.getRuntime().availableProcessors();
        return Math.max(10, cpuCores * 2);
    }
}
```

### 2. 缓存优化

```java
@Component
public class LockCache {

    private final Cache<String, DistributedLock> lockCache;
    private final DistributedLockFactory lockFactory;

    public LockCache(DistributedLockFactory lockFactory) {
        this.lockFactory = lockFactory;
        this.lockCache = Caffeine.newBuilder()
            .maximumSize(10000)
            .expireAfterAccess(30, TimeUnit.MINUTES)
            .removalListener((String key, DistributedLock lock, RemovalCause cause) -> {
                // 清理回调
                System.out.println("Lock evicted: " + key + ", cause: " + cause);
            })
            .build();
    }

    public DistributedLock getLock(String name) {
        return lockCache.get(name, key -> {
            System.out.println("Creating new lock: " + key);
            return lockFactory.getLock(key);
        });
    }
}
```

## 总结

实现分布式锁框架需要考虑：

1. **架构设计**：接口分离、SPI 扩展、建造者模式
2. **核心实现**：锁和工厂的抽象实现
3. **后端支持**：Redis、ZooKeeper 等具体实现
4. **Spring 集成**：自动配置、AOP 切面、健康检查
5. **监控告警**：性能指标收集、健康状态监控
6. **测试验证**：单元测试、集成测试、性能测试
7. **扩展机制**：SPI、注解、拦截器等扩展点
8. **性能优化**：连接池、缓存、异步处理等

通过合理的架构设计和实现，可以构建出高性能、可扩展、易维护的分布式锁框架。