# 扩容指南

## 概述

本指南介绍分布式锁框架的扩容策略，包括水平扩容、垂直扩容、自动扩容和容量规划。

## 扩容策略

### 水平扩容

#### 应用层扩容

```yaml
# Kubernetes 水平扩容
apiVersion: apps/v1
kind: Deployment
metadata:
  name: distributed-lock-app
spec:
  replicas: 5  # 增加副本数
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxUnavailable: 1
      maxSurge: 2
  template:
    spec:
      containers:
      - name: app
        resources:
          requests:
            memory: "512Mi"
            cpu: "500m"
          limits:
            memory: "1Gi"
            cpu: "1000m"
        # 健康检查
        livenessProbe:
          httpGet:
            path: /actuator/health
            port: 8080
          initialDelaySeconds: 30
          periodSeconds: 10
        readinessProbe:
          httpGet:
            path: /actuator/health
            port: 8080
          initialDelaySeconds: 5
          periodSeconds: 5
```

#### Redis 集群扩容

```bash
#!/bin/bash
# redis-cluster-scale.sh

# 添加新节点到集群
NEW_NODE="redis-new:6379"
EXISTING_NODE="redis-1:6379"

echo "Adding new Redis node: $NEW_NODE"

# 添加节点到集群
redis-cli --cluster add-node $NEW_NODE $EXISTING_NODE

# 为新节点分配槽位
redis-cli --cluster reshard $EXISTING_NODE \
  --cluster-from $(redis-cli --cluster nodes | grep master | awk '{print $1}' | tr '\n' ',') \
  --cluster-to $(redis-cli --cluster nodes | grep $NEW_NODE | awk '{print $1}') \
  --cluster-slots 546 \
  --cluster-yes

# 重新平衡集群
redis-cli --cluster rebalance $EXISTING_NODE --cluster-weight $(redis-cli --cluster nodes | grep master | awk '{print $1"=1"}' | tr '\n' ' ')

echo "Redis cluster scaling completed"
```

#### ZooKeeper 集群扩容

```bash
#!/bin/bash
# zookeeper-cluster-scale.sh

NEW_SERVER="zk4"
ZK_CLUSTER="zk1,zk2,zk3,zk4"

echo "Adding new ZooKeeper server: $NEW_SERVER"

# 更新所有节点的配置
for server in zk1 zk2 zk3 zk4; do
    ssh $server << EOF
    echo "server.4=$NEW_SERVER:2888:3888" >> /opt/zookeeper/conf/zoo.cfg
    systemctl restart zookeeper
EOF
done

# 等待新节点加入集群
sleep 30

# 验证集群状态
echo "stat" | nc zk1 2181
echo "stat" | nc zk4 2181

echo "ZooKeeper cluster scaling completed"
```

### 垂直扩容

#### 资源调整

```yaml
# 增加容器资源限制
apiVersion: v1
kind: Pod
metadata:
  name: distributed-lock-app
spec:
  containers:
  - name: app
    resources:
      requests:
        memory: "1Gi"    # 从 512Mi 增加到 1Gi
        cpu: "1000m"    # 从 500m 增加到 1000m
      limits:
        memory: "2Gi"    # 从 1Gi 增加到 2Gi
        cpu: "2000m"    # 从 1000m 增加到 2000m
```

#### JVM 参数优化

```bash
# JVM 内存优化
JAVA_OPTS="
-Xms2g -Xmx4g
-XX:+UseG1GC
-XX:MaxGCPauseMillis=200
-XX:G1HeapRegionSize=16m
-XX:ParallelGCThreads=4
-XX:ConcGCThreads=2
-XX:+PrintGCDetails
-XX:+PrintGCTimeStamps
-Xloggc:/opt/app/logs/gc.log
"

# 启动应用
java $JAVA_OPTS -jar app.jar
```

#### 连接池优化

```yaml
distributed-lock:
  redis:
    pool:
      max-total: 100      # 增加最大连接数
      max-idle: 50        # 增加最大空闲连接
      min-idle: 20        # 增加最小空闲连接
      max-wait-millis: 10000  # 增加等待超时时间
  zookeeper:
    connection-pool:
      max-active: 50      # 增加活跃连接数
      max-idle: 20        # 增加空闲连接数
```

### 自动扩容

#### Kubernetes HPA

```yaml
# 基于 CPU 使用率的自动扩容
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: distributed-lock-app-hpa
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: distributed-lock-app
  minReplicas: 3
  maxReplicas: 10
  metrics:
  - type: Resource
    resource:
      name: cpu
      target:
        type: Utilization
        averageUtilization: 70
  - type: Resource
    resource:
      name: memory
      target:
        type: Utilization
        averageUtilization: 80
  behavior:
    scaleDown:
      stabilizationWindowSeconds: 300
      policies:
      - type: Percent
        value: 10
        periodSeconds: 60
    scaleUp:
      stabilizationWindowSeconds: 60
      policies:
      - type: Percent
        value: 50
        periodSeconds: 60
```

#### 基于 Prometheus 的扩容

```yaml
# Prometheus 规则驱动的扩容
groups:
  - name: autoscaling
    rules:
      - alert: ScaleUpRequired
        expr: rate(distributed_lock_operations_total[5m]) > 1000
        for: 5m
        labels:
          action: scale_up
        annotations:
          summary: "High load detected, scaling up"
          scale_to: "current_replicas * 1.5"

      - alert: ScaleDownAllowed
        expr: rate(distributed_lock_operations_total[5m]) < 200
        for: 15m
        labels:
          action: scale_down
        annotations:
          summary: "Low load detected, can scale down"
          scale_to: "max(current_replicas * 0.8, min_replicas)"
```

#### 自定义自动扩容服务

```java
@Service
public class AutoScalingService {

    private final KubernetesClient kubernetesClient;
    private final DistributedLockFactory lockFactory;

    @Scheduled(fixedRate = 60000) // 1分钟检查一次
    public void checkAndScale() {
        try {
            // 获取当前指标
            double currentLoad = getCurrentLoad();
            int currentReplicas = getCurrentReplicas();

            // 计算目标副本数
            int targetReplicas = calculateTargetReplicas(currentLoad, currentReplicas);

            if (targetReplicas != currentReplicas) {
                // 执行扩容
                scaleDeployment(targetReplicas);
                System.out.println("Scaled from " + currentReplicas + " to " + targetReplicas + " replicas");
            }

        } catch (Exception e) {
            System.err.println("Auto scaling failed: " + e.getMessage());
        }
    }

    private double getCurrentLoad() {
        // 计算当前负载（基于锁操作率、响应时间等）
        var stats = lockFactory.getStatistics();
        return stats.getTotalLockAcquisitions() / (double) Math.max(stats.getUptime() / 1000, 1);
    }

    private int calculateTargetReplicas(double load, int current) {
        // 简单的扩容算法
        if (load > 1000) { // 高负载
            return Math.min(current * 2, 20); // 最多翻倍，最多20个
        } else if (load < 100) { // 低负载
            return Math.max(current / 2, 2); // 最少减半，最少2个
        }
        return current; // 保持不变
    }

    private void scaleDeployment(int replicas) {
        kubernetesClient.apps().deployments()
            .inNamespace("default")
            .withName("distributed-lock-app")
            .scale(replicas);
    }
}
```

## 容量规划

### 性能基准测试

```java
public class CapacityBenchmark {

    private static final int[] THREAD_COUNTS = {10, 50, 100, 200, 500};
    private static final int TEST_DURATION_SECONDS = 60;

    public static void main(String[] args) throws Exception {
        DistributedLockFactory lockFactory = createLockFactory();

        System.out.println("=== Capacity Benchmark ===");
        System.out.println("Threads\tThroughput\tLatency P95\tLatency P99\tErrors");

        for (int threadCount : THREAD_COUNTS) {
            BenchmarkResult result = runBenchmark(lockFactory, threadCount, TEST_DURATION_SECONDS);
            System.out.printf("%d\t%.0f ops/sec\t%.1fms\t%.1fms\t%d%n",
                threadCount,
                result.getThroughput(),
                result.getLatencyP95(),
                result.getLatencyP99(),
                result.getErrors());
        }

        lockFactory.shutdown();
    }

    private static BenchmarkResult runBenchmark(DistributedLockFactory lockFactory,
                                              int threadCount, int durationSeconds) throws Exception {

        AtomicLong operations = new AtomicLong(0);
        AtomicLong errors = new AtomicLong(0);
        List<Long> latencies = Collections.synchronizedList(new ArrayList<>());

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        long startTime = System.currentTimeMillis();

        // 启动测试线程
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    while (System.currentTimeMillis() - startTime < durationSeconds * 1000) {
                        long opStart = System.nanoTime();

                        try {
                            DistributedLock lock = lockFactory.getLock("bench-" + Thread.currentThread().getId());
                            lock.tryLock(1, 5, TimeUnit.SECONDS);
                            lock.unlock();

                            operations.incrementAndGet();
                            latencies.add(System.nanoTime() - opStart);

                        } catch (Exception e) {
                            errors.incrementAndGet();
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();

        // 计算结果
        long totalTime = System.currentTimeMillis() - startTime;
        double throughput = operations.get() / (totalTime / 1000.0);

        List<Long> sortedLatencies = latencies.stream()
            .mapToLong(l -> l / 1_000_000) // 转换为毫秒
            .sorted()
            .boxed()
            .collect(Collectors.toList());

        long latencyP95 = percentile(sortedLatencies, 95);
        long latencyP99 = percentile(sortedLatencies, 99);

        return new BenchmarkResult(throughput, latencyP95, latencyP99, errors.get());
    }

    private static long percentile(List<Long> sortedList, double percentile) {
        if (sortedList.isEmpty()) return 0;
        int index = (int) Math.ceil(percentile / 100.0 * sortedList.size()) - 1;
        return sortedList.get(Math.max(0, Math.min(index, sortedList.size() - 1)));
    }

    static class BenchmarkResult {
        private final double throughput;
        private final long latencyP95;
        private final long latencyP99;
        private final long errors;

        // constructor and getters...
    }
}
```

### 资源需求估算

```bash
#!/bin/bash
# capacity-planning.sh

# 输入参数
EXPECTED_QPS=1000
AVG_LOCK_TIME_MS=100
CONCURRENT_LOCKS=$((EXPECTED_QPS * AVG_LOCK_TIME_MS / 1000))

echo "=== Capacity Planning ==="
echo "Expected QPS: $EXPECTED_QPS"
echo "Average lock time: ${AVG_LOCK_TIME_MS}ms"
echo "Estimated concurrent locks: $CONCURRENT_LOCKS"

# CPU 估算
CPU_CORES=$((EXPECTED_QPS / 300 + 1))  # 每300 QPS 一个核心
echo "Estimated CPU cores: $CPU_CORES"

# 内存估算
JVM_HEAP_MB=$((CONCURRENT_LOCKS * 2 + 512))  # 基础内存 + 锁开销
REDIS_MEMORY_MB=$((CONCURRENT_LOCKS * 1024 / 1024 / 1024 + 256))  # 锁数据 + 基础内存
echo "Estimated JVM heap: ${JVM_HEAP_MB}MB"
echo "Estimated Redis memory: ${REDIS_MEMORY_MB}MB"

# 网络估算
NETWORK_MBPS=$((EXPECTED_QPS * 2 / 1024))  # 粗略估算
echo "Estimated network: ${NETWORK_MBPS}Mbps"

# 存储估算
STORAGE_GB=$((EXPECTED_QPS * 86400 * 30 / 1000000))  # 30天日志
echo "Estimated storage for logs: ${STORAGE_GB}GB"
```

### 负载测试

```java
@SpringBootTest
public class LoadTest {

    @Autowired
    private DistributedLockFactory lockFactory;

    @Test
    public void testProgressiveLoadIncrease() throws InterruptedException {
        int[] loadLevels = {100, 200, 500, 1000, 2000};

        for (int load : loadLevels) {
            System.out.println("Testing load level: " + load + " ops/sec");

            long startTime = System.currentTimeMillis();
            AtomicInteger operations = new AtomicInteger(0);
            AtomicInteger errors = new AtomicInteger(0);

            // 创建负载线程
            ExecutorService executor = Executors.newFixedThreadPool(50);
            CountDownLatch latch = new CountDownLatch(50);

            for (int i = 0; i < 50; i++) {
                executor.submit(() -> {
                    try {
                        while (System.currentTimeMillis() - startTime < 10000) { // 10秒测试
                            try {
                                DistributedLock lock = lockFactory.getLock("load-test-" + Thread.currentThread().getId());
                                boolean acquired = lock.tryLock(0, 1, TimeUnit.SECONDS);
                                if (acquired) {
                                    operations.incrementAndGet();
                                    lock.unlock();
                                }
                            } catch (Exception e) {
                                errors.incrementAndGet();
                            }
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await();

            double actualQps = operations.get() / 10.0;
            double errorRate = (double) errors.get() / (operations.get() + errors.get()) * 100;

            System.out.printf("  Actual QPS: %.1f%n", actualQps);
            System.out.printf("  Error rate: %.2f%%%n", errorRate);

            // 验证性能
            assertTrue(actualQps >= load * 0.8, "QPS too low for load level " + load);
            assertTrue(errorRate < 5, "Error rate too high for load level " + load);

            Thread.sleep(2000); // 冷却时间
        }
    }
}
```

## 扩容监控

### 扩容指标监控

```prometheus
# 扩容相关指标
deployment_replicas = kube_deployment_spec_replicas{deployment="distributed-lock-app"}
deployment_available_replicas = kube_deployment_status_replicas_available{deployment="distributed-lock-app"}

# 扩容效率
scaling_efficiency = rate(distributed_lock_operations_total[5m]) / deployment_replicas

# 资源利用率
cpu_utilization = rate(container_cpu_usage_seconds_total[5m]) / rate(container_spec_cpu_period[5m])
memory_utilization = container_memory_usage_bytes / container_spec_memory_limit_bytes

# 扩容告警
- alert: ScalingIneffective
  expr: scaling_efficiency < 0.7 and deployment_replicas > 3
  for: 10m
  labels:
    severity: warning
  annotations:
    summary: "Scaling is not effective"
    description: "Adding more replicas is not improving performance"

- alert: OverProvisioned
  expr: cpu_utilization < 0.3 and memory_utilization < 0.5 and deployment_replicas > 3
  for: 30m
  labels:
    severity: info
  annotations:
    summary: "Resources are over-provisioned"
    description: "Consider scaling down to save resources"
```

### 扩容事件跟踪

```java
@Component
public class ScalingEventTracker {

    private final MeterRegistry registry;

    public void trackScalingEvent(String eventType, int fromReplicas, int toReplicas,
                                String reason, Map<String, Object> metadata) {

        // 记录扩容事件
        Counter.builder("scaling_events_total")
            .tag("event_type", eventType)
            .tag("reason", reason)
            .register(registry)
            .increment();

        // 记录扩容幅度
        Gauge.builder("scaling_replicas_change", () -> toReplicas - fromReplicas)
            .register(registry);

        // 记录扩容前后的指标
        metadata.forEach((key, value) -> {
            if (value instanceof Number) {
                Gauge.builder("scaling_metadata_" + key, () -> ((Number) value).doubleValue())
                    .register(registry);
            }
        });

        System.out.println("Scaling event tracked: " + eventType +
                         " from " + fromReplicas + " to " + toReplicas +
                         " reason: " + reason);
    }
}
```

## 扩容最佳实践

### 扩容时机

1. **CPU 使用率 > 70%**：考虑水平扩容
2. **内存使用率 > 80%**：考虑垂直扩容或优化内存使用
3. **响应时间 > 500ms**：检查是否需要扩容
4. **队列长度持续增长**：需要增加处理能力
5. **错误率上升**：可能是资源不足导致

### 扩容策略

1. **渐进式扩容**：每次增加 25-50% 的容量
2. **监控驱动**：基于实际监控指标进行扩容
3. **预热机制**：新实例启动后进行预热再加入服务
4. **回滚计划**：准备扩容失败时的回滚方案
5. **成本考虑**：平衡性能提升和成本增加

### 扩容测试

```bash
#!/bin/bash
# scaling-test.sh

echo "=== Scaling Test ==="

# 记录基准性能
echo "Recording baseline performance..."
baseline_qps=$(measure_qps 60)
echo "Baseline QPS: $baseline_qps"

# 执行扩容
echo "Scaling up deployment..."
kubectl scale deployment distributed-lock-app --replicas=5

# 等待扩容完成
echo "Waiting for scaling to complete..."
kubectl wait --for=condition=available --timeout=300s deployment/distributed-lock-app

# 记录扩容后性能
echo "Recording post-scaling performance..."
scaled_qps=$(measure_qps 60)
echo "Scaled QPS: $scaled_qps"

# 计算提升
improvement=$(echo "scale=2; ($scaled_qps - $baseline_qps) / $baseline_qps * 100" | bc)
echo "Performance improvement: ${improvement}%"

# 验证扩容成功
if (( $(echo "$improvement > 50" | bc -l) )); then
    echo "✅ Scaling test passed"
else
    echo "❌ Scaling test failed"
    exit 1
fi
```

## 总结

完整的扩容体系包括：

1. **水平扩容**：增加应用实例数量
2. **垂直扩容**：增加单个实例的资源
3. **自动扩容**：基于指标的自动扩容
4. **容量规划**：基于负载测试的容量规划
5. **监控告警**：扩容效果和资源使用监控

通过合理的扩容策略，可以确保系统在负载增加时能够保持良好的性能和可用性。