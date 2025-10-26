# 测试指南

## 概述

本指南介绍分布式锁框架的测试策略和方法，包括单元测试、集成测试、性能测试和压力测试。

## 测试策略

### 测试金字塔

```
           /\
          /  \
    E2E Tests  <- 端到端测试 (少量)
         |
    Integration  <- 集成测试 (中等)
         |
   Unit Tests    <- 单元测试 (大量)
         |
   Component     <- 组件测试 (适量)
```

### 测试类型

1. **单元测试**：测试单个类或方法
2. **集成测试**：测试模块间的协作
3. **系统测试**：测试整个系统的功能
4. **性能测试**：测试性能指标和瓶颈
5. **压力测试**：测试极限负载和稳定性

## 单元测试

### 1. 锁实现测试

```java
@SpringBootTest
@ExtendWith(MockitoExtension.class)
public class DistributedLockTest {

    @Mock
    private RedisClient redisClient;

    @Mock
    private LockConfiguration config;

    @InjectMocks
    private RedisDistributedLock lock;

    @BeforeEach
    void setUp() {
        when(config.getDefaultLeaseTime()).thenReturn(Duration.ofSeconds(30));
        when(config.getDefaultWaitTime()).thenReturn(Duration.ofSeconds(10));
        lock = new RedisDistributedLock("test-lock", redisClient, config);
    }

    @Test
    public void testLockAcquisition() throws InterruptedException {
        // Mock Redis SET 操作成功
        when(redisClient.set(anyString(), anyString(), any(SetArgs.class)))
            .thenReturn(true);

        // 执行测试
        lock.lock(30, TimeUnit.SECONDS);

        // 验证 Redis 调用
        verify(redisClient).set(
            eq("distributed:lock:test-lock"),
            anyString(),
            any(SetArgs.class)
        );
    }

    @Test
    public void testLockRelease() {
        // Mock 锁持有状态
        when(redisClient.get("distributed:lock:test-lock"))
            .thenReturn("test-value");

        // 执行释放
        lock.unlock();

        // 验证 Lua 脚本执行
        verify(redisClient).eval(anyString(), any(), any(), any());
    }

    @Test
    public void testTryLockWithTimeout() throws InterruptedException {
        // Mock 第一次失败，第二次成功
        when(redisClient.set(anyString(), anyString(), any(SetArgs.class)))
            .thenReturn(false)  // 第一次失败
            .thenReturn(true);  // 第二次成功

        boolean acquired = lock.tryLock(2, 30, TimeUnit.SECONDS);

        assertTrue(acquired);
        verify(redisClient, times(2)).set(anyString(), anyString(), any(SetArgs.class));
    }
}
```

### 2. 工厂测试

```java
@SpringBootTest
public class DistributedLockFactoryTest {

    @Autowired
    private DistributedLockFactory lockFactory;

    @Test
    public void testGetLock() {
        DistributedLock lock = lockFactory.getLock("test-lock");

        assertNotNull(lock);
        assertEquals("test-lock", lock.getName());
    }

    @Test
    public void testLockUniqueness() {
        DistributedLock lock1 = lockFactory.getLock("unique-lock");
        DistributedLock lock2 = lockFactory.getLock("unique-lock");

        // 验证返回的是不同的实例
        assertNotSame(lock1, lock2);
        // 但它们代表同一个锁
        assertEquals(lock1.getName(), lock2.getName());
    }

    @Test
    public void testHealthCheck() {
        FactoryHealthStatus health = lockFactory.healthCheck();

        assertNotNull(health);
        // 具体的健康检查断言取决于实现
    }

    @Test
    public void testStatistics() {
        FactoryStatistics stats = lockFactory.getStatistics();

        assertNotNull(stats);
        assertTrue(stats.getTotalLocks() >= 0);
        assertTrue(stats.getActiveLocks() >= 0);
    }
}
```

### 3. 配置测试

```java
@SpringBootTest
public class LockConfigurationTest {

    @Autowired
    private LockConfiguration config;

    @Test
    public void testDefaultValues() {
        assertEquals(Duration.ofSeconds(30), config.getDefaultLeaseTime());
        assertEquals(Duration.ofSeconds(10), config.getDefaultWaitTime());
        assertTrue(config.isWatchdogEnabled());
    }

    @Test
    public void testConfigurationValidation() {
        // 测试有效配置
        assertDoesNotThrow(() -> config.validate());

        // 测试无效配置的情况
        // 注意：这需要构造特定的配置对象
    }

    @Test
    public void testDynamicConfigurationUpdate() {
        Duration originalLeaseTime = config.getDefaultLeaseTime();

        // 创建新配置
        Config newConfig = ConfigFactory.parseString(
            "distributed-lock.default-lease-time = 60s"
        );

        // 更新配置
        config.updateConfiguration(newConfig);

        // 验证配置已更新
        assertEquals(Duration.ofSeconds(60), config.getDefaultLeaseTime());
        assertNotEquals(originalLeaseTime, config.getDefaultLeaseTime());
    }
}
```

## 集成测试

### 1. Redis 集成测试

```java
@SpringBootTest
@Testcontainers
public class RedisDistributedLockIntegrationTest {

    @Container
    private static final GenericContainer<?> REDIS =
        new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("distributed-lock.redis.hosts",
            () -> "localhost:" + REDIS.getMappedPort(6379));
    }

    @Autowired
    private DistributedLockFactory lockFactory;

    @Test
    public void testRedisLockIntegration() throws InterruptedException {
        DistributedLock lock = lockFactory.getLock("integration-test");

        // 测试锁获取
        boolean acquired = lock.tryLock(5, 30, TimeUnit.SECONDS);
        assertTrue(acquired);

        // 在另一个线程中尝试获取同一个锁
        AtomicBoolean secondThreadAcquired = new AtomicBoolean(false);
        Thread secondThread = new Thread(() -> {
            try {
                DistributedLock sameLock = lockFactory.getLock("integration-test");
                secondThreadAcquired.set(sameLock.tryLock(2, 5, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        secondThread.start();
        secondThread.join();

        // 验证第二个线程无法获取锁
        assertFalse(secondThreadAcquired.get());

        // 释放锁
        lock.unlock();
    }

    @Test
    public void testRedisLockExpiration() throws InterruptedException {
        DistributedLock lock = lockFactory.getLock("expiration-test");

        // 获取锁，设置短过期时间
        boolean acquired = lock.tryLock(5, 2, TimeUnit.SECONDS);
        assertTrue(acquired);

        // 等待锁过期
        Thread.sleep(3000);

        // 现在应该能够获取锁
        boolean reacquired = lock.tryLock(0, 5, TimeUnit.SECONDS);
        assertTrue(reacquired);

        lock.unlock();
    }
}
```

### 2. ZooKeeper 集成测试

```java
@SpringBootTest
@Testcontainers
public class ZooKeeperDistributedLockIntegrationTest {

    @Container
    private static final GenericContainer<?> ZOOKEEPER =
        new GenericContainer<>(DockerImageName.parse("zookeeper:3.8"))
            .withExposedPorts(2181);

    @DynamicPropertySource
    static void zkProperties(DynamicPropertyRegistry registry) {
        registry.add("distributed-lock.zookeeper.connect-string",
            () -> "localhost:" + ZOOKEEPER.getMappedPort(2181));
    }

    @Autowired
    private DistributedLockFactory lockFactory;

    @Test
    public void testZooKeeperLockIntegration() throws InterruptedException {
        DistributedReadWriteLock rwLock = lockFactory.getReadWriteLock("zk-rw-test");

        // 测试读锁
        DistributedLock readLock = rwLock.readLock();
        boolean readAcquired = readLock.tryLock(5, 30, TimeUnit.SECONDS);
        assertTrue(readAcquired);

        // 在另一个线程中获取另一个读锁（应该成功）
        AtomicBoolean secondReadAcquired = new AtomicBoolean(false);
        Thread secondReadThread = new Thread(() -> {
            try {
                DistributedLock anotherReadLock = lockFactory.getReadWriteLock("zk-rw-test").readLock();
                secondReadAcquired.set(anotherReadLock.tryLock(2, 5, TimeUnit.SECONDS));
                if (secondReadAcquired.get()) {
                    anotherReadLock.unlock();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        secondReadThread.start();
        secondReadThread.join();

        assertTrue(secondReadAcquired.get()); // 多个读锁可以同时持有

        // 尝试获取写锁（应该失败，因为有读锁）
        DistributedLock writeLock = rwLock.writeLock();
        boolean writeAcquired = writeLock.tryLock(1, 1, TimeUnit.SECONDS);
        assertFalse(writeAcquired); // 写锁应该获取失败

        readLock.unlock();
    }
}
```

### 3. Spring Boot 集成测试

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class SpringBootIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private DistributedLockFactory lockFactory;

    @Test
    public void testSpringBootIntegration() {
        // 测试 REST API
        ResponseEntity<String> response = restTemplate.getForEntity(
            "/api/locks/basic/integration-test", String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());

        // 测试锁工厂注入
        assertNotNull(lockFactory);

        // 测试锁创建
        DistributedLock lock = lockFactory.getLock("spring-test");
        assertNotNull(lock);
    }

    @Test
    public void testAopIntegration() {
        // 测试 AOP 切面
        ResponseEntity<String> response = restTemplate.postForEntity(
            "/api/locks/orders/test-order/process", null, String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().contains("processed"));
    }

    @Test
    public void testHealthIndicator() {
        ResponseEntity<String> response = restTemplate.getForEntity(
            "/actuator/health", String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());

        // 解析 JSON 响应
        JsonNode health = new ObjectMapper().readTree(response.getBody());
        assertEquals("UP", health.get("status").asText());
        assertTrue(health.has("components"));
        assertTrue(health.get("components").has("distributedLock"));
    }
}
```

## 性能测试

### 1. JMH 基准测试

```java
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Benchmark)
public class LockBenchmark {

    private DistributedLockFactory lockFactory;
    private String lockName;

    @Setup
    public void setup() {
        // 初始化锁工厂
        lockFactory = createLockFactory();
        lockName = "benchmark-lock-" + Thread.currentThread().getId();
    }

    @TearDown
    public void tearDown() {
        lockFactory.shutdown();
    }

    @Benchmark
    public void testLockAcquireRelease() {
        DistributedLock lock = lockFactory.getLock(lockName);
        try {
            lock.lock(1, TimeUnit.SECONDS);
            // 模拟少量工作
            Blackhole.consumeCPU(10);
        } catch (InterruptedException e) {
            // 处理中断
        } finally {
            lock.unlock();
        }
    }

    @Benchmark
    public void testTryLock() {
        DistributedLock lock = lockFactory.getLock(lockName);
        try {
            boolean acquired = lock.tryLock(0, 1, TimeUnit.SECONDS);
            if (acquired) {
                Blackhole.consumeCPU(5);
            }
        } catch (InterruptedException e) {
            // 处理中断
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Benchmark
    public void testConcurrentLocking() throws InterruptedException {
        int threadCount = 10;
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                DistributedLock lock = lockFactory.getLock(lockName);
                try {
                    boolean acquired = lock.tryLock(5, 10, TimeUnit.SECONDS);
                    if (acquired) {
                        successCount.incrementAndGet();
                        Blackhole.consumeCPU(50);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    if (lock.isHeldByCurrentThread()) {
                        lock.unlock();
                    }
                    latch.countDown();
                }
            }).start();
        }

        latch.await();
        return successCount.get();
    }

    private DistributedLockFactory createLockFactory() {
        // 创建测试用的锁工厂
        return RedisClusterFactory.builder()
            .redisUrl("redis://localhost:6379")
            .build();
    }
}
```

### 2. 负载测试

```java
@SpringBootTest
public class LoadTest {

    @Autowired
    private DistributedLockFactory lockFactory;

    @Test
    public void testHighConcurrencyLoad() throws InterruptedException {
        int threadCount = 100;
        int operationsPerThread = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger totalOperations = new AtomicInteger(0);

        long startTime = System.currentTimeMillis();

        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < operationsPerThread; j++) {
                        String lockName = "load-test-" + threadId + "-" + j;
                        DistributedLock lock = lockFactory.getLock(lockName);

                        try {
                            boolean acquired = lock.tryLock(2, 5, TimeUnit.SECONDS);
                            if (acquired) {
                                // 模拟业务处理
                                Thread.sleep(10);
                                totalOperations.incrementAndGet();
                            }
                        } finally {
                            if (lock.isHeldByCurrentThread()) {
                                lock.unlock();
                            }
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(300, TimeUnit.SECONDS));

        long duration = System.currentTimeMillis() - startTime;
        double throughput = (double) totalOperations.get() / (duration / 1000.0);

        System.out.println("Load test completed:");
        System.out.println("  Total operations: " + totalOperations.get());
        System.out.println("  Duration: " + duration + "ms");
        System.out.println("  Throughput: " + String.format("%.2f ops/sec", throughput));

        // 验证吞吐量
        assertTrue(throughput > 10); // 至少 10 ops/sec
    }
}
```

## 压力测试

### 1. 极限负载测试

```java
public class StressTest {

    private static final int MAX_THREADS = 500;
    private static final int TEST_DURATION_MINUTES = 10;

    public static void main(String[] args) throws Exception {
        DistributedLockFactory lockFactory = createLockFactory();

        ExecutorService executor = Executors.newFixedThreadPool(MAX_THREADS);
        AtomicLong totalOperations = new AtomicLong(0);
        AtomicLong failedOperations = new AtomicLong(0);

        long startTime = System.currentTimeMillis();
        long endTime = startTime + TimeUnit.MINUTES.toMillis(TEST_DURATION_MINUTES);

        System.out.println("Starting stress test with " + MAX_THREADS + " threads...");

        // 启动所有线程
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < MAX_THREADS; i++) {
            Future<?> future = executor.submit(new StressTestWorker(
                lockFactory, totalOperations, failedOperations, endTime));
            futures.add(future);
        }

        // 等待测试完成
        for (Future<?> future : futures) {
            future.get();
        }

        executor.shutdown();

        long duration = System.currentTimeMillis() - startTime;
        double throughput = (double) totalOperations.get() / (duration / 1000.0);
        double errorRate = (double) failedOperations.get() / totalOperations.get() * 100.0;

        System.out.println("Stress test completed:");
        System.out.println("  Duration: " + duration + "ms");
        System.out.println("  Total operations: " + totalOperations.get());
        System.out.println("  Failed operations: " + failedOperations.get());
        System.out.println("  Throughput: " + String.format("%.2f ops/sec", throughput));
        System.out.println("  Error rate: " + String.format("%.2f%%", errorRate));

        lockFactory.shutdown();
    }

    static class StressTestWorker implements Runnable {

        private final DistributedLockFactory lockFactory;
        private final AtomicLong totalOperations;
        private final AtomicLong failedOperations;
        private final long endTime;

        public StressTestWorker(DistributedLockFactory lockFactory,
                              AtomicLong totalOperations,
                              AtomicLong failedOperations,
                              long endTime) {
            this.lockFactory = lockFactory;
            this.totalOperations = totalOperations;
            this.failedOperations = failedOperations;
            this.endTime = endTime;
        }

        @Override
        public void run() {
            Random random = new Random();

            while (System.currentTimeMillis() < endTime) {
                String lockName = "stress-" + random.nextInt(1000);
                DistributedLock lock = lockFactory.getLock(lockName);

                try {
                    boolean acquired = lock.tryLock(1, 2, TimeUnit.SECONDS);
                    totalOperations.incrementAndGet();

                    if (acquired) {
                        // 模拟随机处理时间
                        Thread.sleep(random.nextInt(100) + 10);
                    } else {
                        failedOperations.incrementAndGet();
                    }

                } catch (Exception e) {
                    failedOperations.incrementAndGet();
                } finally {
                    if (lock.isHeldByCurrentThread()) {
                        try {
                            lock.unlock();
                        } catch (Exception e) {
                            // 忽略解锁异常
                        }
                    }
                }
            }
        }
    }

    private static DistributedLockFactory createLockFactory() {
        return RedisClusterFactory.builder()
            .redisUrl("redis://localhost:6379")
            .build();
    }
}
```

## 测试工具和框架

### 1. 测试容器

```java
@Testcontainers
public class ContainerBasedTest {

    @Container
    private static final GenericContainer<?> REDIS =
        new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379)
            .withCommand("redis-server", "--appendonly", "yes");

    @Container
    private static final GenericContainer<?> ZOOKEEPER =
        new GenericContainer<>(DockerImageName.parse("zookeeper:3.8"))
            .withExposedPorts(2181);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.redis.host", () -> REDIS.getHost());
        registry.add("spring.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("distributed-lock.zookeeper.connect-string",
            () -> ZOOKEEPER.getHost() + ":" + ZOOKEEPER.getMappedPort(2181));
    }
}
```

### 2. Mock 测试

```java
@ExtendWith(MockitoExtension.class)
public class MockBasedTest {

    @Mock
    private DistributedLockFactory lockFactory;

    @Mock
    private DistributedLock mockLock;

    @InjectMocks
    private LockService lockService;

    @Test
    public void testLockServiceWithMock() {
        // 设置 Mock 行为
        when(lockFactory.getLock("test")).thenReturn(mockLock);
        when(mockLock.tryLock(anyLong(), anyLong(), any())).thenReturn(true);

        // 执行测试
        boolean result = lockService.tryLockAndExecute("test", () -> "success");

        // 验证结果
        assertTrue(result);
        verify(mockLock).tryLock(10L, 30L, TimeUnit.SECONDS);
        verify(mockLock).unlock();
    }
}
```

### 3. 测试数据管理

```java
public class TestDataManager {

    private final DistributedLockFactory lockFactory;
    private final List<String> createdLocks = new ArrayList<>();

    public TestDataManager(DistributedLockFactory lockFactory) {
        this.lockFactory = lockFactory;
    }

    public DistributedLock createTestLock(String name) {
        DistributedLock lock = lockFactory.getLock(name);
        createdLocks.add(name);
        return lock;
    }

    public void cleanup() {
        for (String lockName : createdLocks) {
            try {
                DistributedLock lock = lockFactory.getLock(lockName);
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            } catch (Exception e) {
                // 忽略清理异常
            }
        }
        createdLocks.clear();
    }
}
```

## CI/CD 集成

### 1. GitHub Actions 配置

```yaml
name: Test

on: [push, pull_request]

jobs:
  test:
    runs-on: ubuntu-latest

    services:
      redis:
        image: redis:7-alpine
        ports:
          - 6379:6379
        options: >-
          --health-cmd "redis-cli ping"
          --health-interval 10s
          --health-timeout 5s
          --health-retries 5

      zookeeper:
        image: zookeeper:3.8
        ports:
          - 2181:2181

    steps:
      - uses: actions/checkout@v3

      - name: Set up JDK 11
        uses: actions/setup-java@v3
        with:
          java-version: '11'
          distribution: 'temurin'

      - name: Cache Maven packages
        uses: actions/cache@v3
        with:
          path: ~/.m2
          key: ${{ runner.os }}-m2-${{ hashFiles('**/pom.xml') }}
          restore-keys: ${{ runner.os }}-m2

      - name: Run tests
        run: mvn test -Dspring.profiles.active=test

      - name: Run integration tests
        run: mvn verify -Dspring.profiles.active=integration-test

      - name: Generate test report
        run: mvn surefire-report:report
```

### 2. 测试报告生成

```xml
<!-- pom.xml -->
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-surefire-plugin</artifactId>
    <version>3.2.5</version>
    <configuration>
        <includes>
            <include>**/*Test.java</include>
            <include>**/*Tests.java</include>
        </includes>
        <excludes>
            <exclude>**/*IntegrationTest.java</exclude>
        </excludes>
        <reportsDirectory>${project.build.directory}/surefire-reports</reportsDirectory>
        <testFailureIgnore>false</testFailureIgnore>
    </configuration>
</plugin>

<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-failsafe-plugin</artifactId>
    <version>3.2.5</version>
    <configuration>
        <includes>
            <include>**/*IntegrationTest.java</include>
        </includes>
        <reportsDirectory>${project.build.directory}/failsafe-reports</reportsDirectory>
    </configuration>
</plugin>
```

## 测试覆盖率

### 1. JaCoCo 配置

```xml
<!-- pom.xml -->
<plugin>
    <groupId>org.jacoco</groupId>
    <artifactId>jacoco-maven-plugin</artifactId>
    <version>0.8.11</version>
    <executions>
        <execution>
            <goals>
                <goal>prepare-agent</goal>
            </goals>
        </execution>
        <execution>
            <id>report</id>
            <phase>test</phase>
            <goals>
                <goal>report</goal>
            </goals>
        </execution>
    </executions>
</plugin>
```

### 2. 覆盖率目标

- **单元测试覆盖率**：> 80%
- **分支覆盖率**：> 70%
- **集成测试覆盖率**：> 60%
- **核心类覆盖率**：> 90%

## 总结

完整的测试策略包括：

1. **单元测试**：测试单个组件，Mock 外部依赖
2. **集成测试**：测试模块协作，使用 Testcontainers
3. **系统测试**：端到端测试完整功能
4. **性能测试**：使用 JMH 进行基准测试
5. **压力测试**：测试极限负载和稳定性
6. **CI/CD 集成**：自动化测试执行和报告生成

通过分层测试和自动化，可以确保分布式锁框架的质量和稳定性。