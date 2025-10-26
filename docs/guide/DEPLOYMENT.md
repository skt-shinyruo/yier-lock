# 部署运维指南

## 部署架构

### 单机部署

适用于开发环境和小型生产环境：

```yaml
# docker-compose.yml
version: '3.8'
services:
  app:
    image: your-app:latest
    environment:
      - DISTRIBUTED_LOCK_BACKEND=redis
      - DISTRIBUTED_LOCK_REDIS_HOSTS=redis:6379
    depends_on:
      - redis

  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"
```

### 集群部署

适用于高可用生产环境：

```yaml
# docker-compose.yml
version: '3.8'
services:
  app1:
    image: your-app:latest
    environment:
      - DISTRIBUTED_LOCK_BACKEND=redis
      - DISTRIBUTED_LOCK_REDIS_HOSTS=redis-cluster:6379
    depends_on:
      - redis-cluster

  app2:
    image: your-app:latest
    environment:
      - DISTRIBUTED_LOCK_BACKEND=redis
      - DISTRIBUTED_LOCK_REDIS_HOSTS=redis-cluster:6379
    depends_on:
      - redis-cluster

  redis-cluster:
    image: redis:7-alpine
    ports:
      - "6379:6379"
      - "6380:6380"
      - "6381:6381"
    command: redis-server --cluster-enabled yes --cluster-config-file nodes.conf --cluster-node-timeout 5000 --appendonly yes
```

### Kubernetes 部署

```yaml
# deployment.yml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: distributed-lock-app
spec:
  replicas: 3
  selector:
    matchLabels:
      app: distributed-lock-app
  template:
    metadata:
      labels:
        app: distributed-lock-app
    spec:
      containers:
      - name: app
        image: your-app:latest
        env:
        - name: DISTRIBUTED_LOCK_BACKEND
          value: "redis"
        - name: DISTRIBUTED_LOCK_REDIS_HOSTS
          value: "redis-cluster:6379"
        - name: DISTRIBUTED_LOCK_MONITORING_ENABLED
          value: "true"
        resources:
          requests:
            memory: "256Mi"
            cpu: "250m"
          limits:
            memory: "512Mi"
            cpu: "500m"
        livenessProbe:
          httpGet:
            path: /actuator/health
            port: 8080
          initialDelaySeconds: 60
          periodSeconds: 30
        readinessProbe:
          httpGet:
            path: /actuator/health
            port: 8080
          initialDelaySeconds: 30
          periodSeconds: 10
```

```yaml
# redis-cluster.yml
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: redis-cluster
spec:
  serviceName: redis-cluster
  replicas: 6
  selector:
    matchLabels:
      app: redis-cluster
  template:
    metadata:
      labels:
        app: redis-cluster
    spec:
      containers:
      - name: redis
        image: redis:7-alpine
        ports:
        - containerPort: 6379
          name: redis
        command:
        - redis-server
        args:
        - --cluster-enabled
        - "yes"
        - --cluster-config-file
        - nodes.conf
        - --cluster-node-timeout
        - "5000"
        - --appendonly
        - "yes"
        volumeMounts:
        - name: redis-data
          mountPath: /data
  volumeClaimTemplates:
  - metadata:
    name: redis-data
    spec:
      accessModes: ["ReadWriteOnce"]
      resources:
        requests:
          storage: 10Gi
```

## 环境准备

### 系统要求

- **JDK**: 11 或更高版本
- **内存**: 至少 256MB 堆内存
- **磁盘**: 至少 1GB 可用空间
- **网络**: 稳定的网络连接

### 依赖服务

#### Redis 部署

```bash
# 单节点 Redis
docker run -d --name redis -p 6379:6379 redis:7-alpine

# Redis 集群
docker run -d --name redis-cluster \
  -p 6379:6379 -p 6380:6380 -p 6381:6381 \
  redis:7-alpine redis-server --cluster-enabled yes \
  --cluster-config-file nodes.conf \
  --cluster-node-timeout 5000 \
  --appendonly yes
```

#### ZooKeeper 部署

```bash
# 单节点 ZooKeeper
docker run -d --name zookeeper -p 2181:2181 zookeeper:3.8

# ZooKeeper 集群
# zoo1.cfg
tickTime=2000
dataDir=/var/lib/zookeeper
clientPort=2181
initLimit=5
syncLimit=2
server.1=zoo1:2888:3888
server.2=zoo2:2888:3888
server.3=zoo3:2888:3888
```

## 配置管理

### 配置中心集成

#### Spring Cloud Config

```yaml
# bootstrap.yml
spring:
  application:
    name: distributed-lock-app
  cloud:
    config:
      uri: http://config-server:8888
      profile: production
```

```yaml
# config-repo/distributed-lock-app-production.yml
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
```

#### Apollo 配置中心

```java
@Configuration
public class ApolloConfig {

    @ApolloConfig
    private Config config;

    @Bean
    public LockConfiguration lockConfiguration() {
        return new LockConfiguration() {{
            setBackend(BackendType.valueOf(
                config.getProperty("distributed.lock.backend", "redis").toUpperCase()));
            setRedisHosts(config.getProperty("distributed.lock.redis.hosts", "localhost:6379"));
            // 其他配置...
        }};
    }
}
```

### 环境变量配置

```bash
# Redis 配置
export DISTRIBUTED_LOCK_BACKEND=redis
export DISTRIBUTED_LOCK_REDIS_HOSTS=redis-cluster:6379
export DISTRIBUTED_LOCK_REDIS_PASSWORD=your-secure-password

# ZooKeeper 配置
export DISTRIBUTED_LOCK_BACKEND=zookeeper
export DISTRIBUTED_LOCK_ZOOKEEPER_CONNECT_STRING=zk1:2181,zk2:2181,zk3:2181

# 监控配置
export DISTRIBUTED_LOCK_MONITORING_ENABLED=true
export DISTRIBUTED_LOCK_METRICS_ENABLED=true

# 性能配置
export DISTRIBUTED_LOCK_WATCHDOG_ENABLED=true
export DISTRIBUTED_LOCK_DEFAULT_LEASE_TIME=30s
export DISTRIBUTED_LOCK_DEFAULT_WAIT_TIME=10s
```

## 启动和停止

### 应用启动

```bash
# JAR 包启动
java -jar distributed-lock-app.jar \
  --spring.profiles.active=production \
  --distributed-lock.backend=redis \
  --distributed-lock.redis.hosts=redis-cluster:6379

# Docker 启动
docker run -d \
  --name distributed-lock-app \
  -e SPRING_PROFILES_ACTIVE=production \
  -e DISTRIBUTED_LOCK_BACKEND=redis \
  -e DISTRIBUTED_LOCK_REDIS_HOSTS=redis-cluster:6379 \
  your-app:latest
```

### 优雅关闭

```java
@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(Application.class);
        app.addListeners(new ApplicationPidFileWriter());
        app.run(args);
    }

    @PreDestroy
    public void onShutdown() {
        // 优雅关闭逻辑
        lockFactory.gracefulShutdown(30, TimeUnit.SECONDS);
    }
}
```

### 健康检查

```yaml
# application.yml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics
  endpoint:
    health:
      show-details: when-authorized
  health:
    distributed-lock:
      enabled: true
```

## 监控配置

### Prometheus 监控

```yaml
# prometheus.yml
global:
  scrape_interval: 15s

scrape_configs:
  - job_name: 'distributed-lock-app'
    static_configs:
      - targets: ['app:8080']
    metrics_path: '/actuator/prometheus'
```

### Grafana 仪表板

```json
{
  "dashboard": {
    "title": "Distributed Lock Metrics",
    "panels": [
      {
        "title": "Lock Operations Rate",
        "type": "graph",
        "targets": [
          {
            "expr": "rate(distributed_lock_operations_total[5m])",
            "legendFormat": "{{operation}}"
          }
        ]
      },
      {
        "title": "Lock Acquisition Time",
        "type": "heatmap",
        "targets": [
          {
            "expr": "distributed_lock_operation_duration_seconds",
            "legendFormat": "{{operation}}"
          }
        ]
      }
    ]
  }
}
```

### 告警规则

```yaml
# alert-rules.yml
groups:
  - name: distributed-lock
    rules:
      - alert: HighLockErrorRate
        expr: rate(distributed_lock_operations_total{status="error"}[5m]) / rate(distributed_lock_operations_total[5m]) > 0.05
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "High distributed lock error rate"
          description: "Lock error rate is {{ $value }}%"

      - alert: SlowLockOperations
        expr: histogram_quantile(0.95, rate(distributed_lock_operation_duration_seconds_bucket[5m])) > 1.0
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Slow distributed lock operations"
          description: "95th percentile operation time is {{ $value }}s"
```

## 日志配置

### Logback 配置

```xml
<!-- logback-spring.xml -->
<configuration>
    <include resource="org/springframework/boot/logging/logback/defaults.xml"/>

    <appender name="LOCK_AUDIT" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>logs/distributed-lock-audit.log</file>
        <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
            <fileNamePattern>logs/distributed-lock-audit.%d{yyyy-MM-dd}.%i.gz</fileNamePattern>
            <maxFileSize>100MB</maxFileSize>
            <maxHistory>30</maxHistory>
            <totalSizeCap>1GB</totalSizeCap>
        </rollingPolicy>
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>

    <logger name="com.mycorp.distributedlock" level="INFO" additivity="false">
        <appender-ref ref="CONSOLE"/>
        <appender-ref ref="LOCK_AUDIT"/>
    </logger>

    <logger name="com.mycorp.distributedlock.audit" level="INFO" additivity="false">
        <appender-ref ref="LOCK_AUDIT"/>
    </logger>

    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
    </root>
</configuration>
```

### 结构化日志

```java
@Service
public class LockAuditService {

    private static final Logger auditLogger = LoggerFactory.getLogger("com.mycorp.distributedlock.audit");

    public void logLockOperation(String operation, String lockName, boolean success, long duration) {
        auditLogger.info("Lock operation: operation={}, lockName={}, success={}, duration={}ms",
            operation, lockName, success, duration);
    }
}
```

## 备份和恢复

### Redis 数据备份

```bash
# 创建备份
redis-cli -h redis-host -p 6379 --rdb /path/to/backup.rdb

# 从备份恢复
redis-server --dbfilename backup.rdb --dir /path/to/backup/
```

### 配置备份

```bash
# 备份配置文件
tar -czf config-backup-$(date +%Y%m%d).tar.gz \
  /etc/distributed-lock/ \
  /opt/distributed-lock/config/

# 恢复配置
tar -xzf config-backup-20231201.tar.gz -C /
```

### 应用状态备份

```java
@Service
public class BackupService {

    @Autowired
    private DistributedLockFactory lockFactory;

    @Scheduled(cron = "0 0 2 * * ?") // 每天凌晨2点
    public void createBackup() {
        // 导出锁状态
        Map<String, LockStateInfo> lockStates = exportLockStates();

        // 保存到备份存储
        backupStorage.save("lock-states-" + LocalDate.now(), lockStates);
    }

    private Map<String, LockStateInfo> exportLockStates() {
        // 实现锁状态导出逻辑
        return lockFactory.getStatistics().getActiveLocks().stream()
            .collect(Collectors.toMap(
                name -> name,
                name -> lockFactory.getLock(name).getLockStateInfo()
            ));
    }
}
```

## 扩容指南

### 水平扩容

```yaml
# 增加应用实例
apiVersion: apps/v1
kind: Deployment
metadata:
  name: distributed-lock-app
spec:
  replicas: 5  # 从 3 增加到 5
  # ... 其他配置保持不变
```

### Redis 集群扩容

```bash
# 添加新节点到集群
redis-cli --cluster add-node new-node:6379 existing-node:6379

# 重新平衡集群
redis-cli --cluster rebalance existing-node:6379
```

### 监控扩容

```yaml
# 增加 Prometheus 副本
apiVersion: apps/v1
kind: Deployment
metadata:
  name: prometheus
spec:
  replicas: 2  # 高可用部署
```

## 安全配置

### 网络安全

```yaml
# Kubernetes NetworkPolicy
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: distributed-lock-network-policy
spec:
  podSelector:
    matchLabels:
      app: distributed-lock-app
  policyTypes:
  - Ingress
  - Egress
  ingress:
  - from:
    - podSelector:
        matchLabels:
          app: distributed-lock-app
    ports:
    - protocol: TCP
      port: 8080
  egress:
  - to:
    - podSelector:
        matchLabels:
          app: redis-cluster
    ports:
    - protocol: TCP
      port: 6379
```

### 认证授权

```java
@Configuration
@EnableWebSecurity
public class SecurityConfig extends WebSecurityConfigurerAdapter {

    @Override
    protected void configure(HttpSecurity http) throws Exception {
        http.authorizeRequests()
            .antMatchers("/actuator/health").permitAll()
            .antMatchers("/actuator/**").hasRole("ADMIN")
            .anyRequest().authenticated()
            .and()
            .httpBasic();
    }
}
```

### 数据加密

```yaml
# 加密敏感配置
distributed-lock:
  redis:
    password: "{cipher}encrypted-password-here"
  zookeeper:
    auth-info: "{cipher}encrypted-auth-info"
```

## 故障排查

### 常见问题

#### 连接失败

```bash
# 检查网络连通性
telnet redis-host 6379

# 检查防火墙
iptables -L

# 检查 DNS 解析
nslookup redis-host
```

#### 性能问题

```bash
# 查看系统负载
top -H

# 查看网络连接
netstat -antp | grep :6379

# 查看磁盘 I/O
iostat -x 1
```

#### 内存泄漏

```bash
# 查看 JVM 内存使用
jmap -heap <pid>

# 生成堆转储
jmap -dump:live,format=b,file=heap.hprof <pid>

# 分析堆转储
jhat heap.hprof
```

### 日志分析

```bash
# 搜索错误日志
grep "ERROR" logs/distributed-lock-app.log | tail -20

# 分析慢查询
grep "operation_duration" logs/distributed-lock-app.log | \
  awk '{print $NF}' | sort -n | tail -10
```

### 监控指标

```bash
# 查看 Prometheus 指标
curl http://localhost:9090/api/v1/query?query=distributed_lock_operations_total

# 查看应用健康状态
curl http://localhost:8080/actuator/health
```

## 升级指南

### 滚动升级

```yaml
# Kubernetes 滚动更新
kubectl set image deployment/distributed-lock-app \
  app=your-app:v2.0.0

kubectl rollout status deployment/distributed-lock-app
```

### 蓝绿部署

```yaml
# 创建新版本部署
kubectl apply -f deployment-v2.yml

# 切换服务流量
kubectl patch service distributed-lock-service \
  -p '{"spec":{"selector":{"version":"v2.0.0"}}}'

# 验证新版本
kubectl logs -l version=v2.0.0

# 删除旧版本
kubectl delete deployment distributed-lock-app-v1
```

### 数据库迁移

```java
@Component
public class DatabaseMigration implements CommandLineRunner {

    @Override
    public void run(String... args) {
        // 执行数据迁移逻辑
        migrateLockData();
        migrateConfiguration();
    }
}
```

## 最佳实践

### 生产环境配置

1. **使用外部配置中心**：集中管理配置，支持动态更新
2. **启用监控和告警**：实时监控系统状态，及时发现问题
3. **配置日志轮转**：防止日志文件过大影响性能
4. **设置资源限制**：避免单个应用占用过多资源
5. **启用健康检查**：确保服务可用性
6. **配置备份策略**：定期备份重要数据和配置

### 性能优化

1. **调整连接池大小**：根据并发量调整连接池配置
2. **启用压缩**：减少网络传输数据量
3. **使用批量操作**：减少网络往返次数
4. **配置缓存**：缓存频繁使用的锁对象
5. **监控性能指标**：定期分析性能瓶颈

### 高可用保障

1. **部署多个实例**：避免单点故障
2. **使用集群后端**：Redis 集群或 ZooKeeper 集群
3. **配置故障转移**：自动切换到健康节点
4. **设置超时时间**：防止长时间等待
5. **启用重试机制**：自动重试失败的操作