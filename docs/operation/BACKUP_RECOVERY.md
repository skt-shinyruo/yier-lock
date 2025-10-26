# 备份恢复指南

## 概述

本指南介绍分布式锁框架的备份和恢复策略，包括数据备份、配置备份、故障恢复和业务连续性保障。

## 备份策略

### 数据备份

#### Redis 数据备份

```bash
#!/bin/bash
# redis-backup.sh

BACKUP_DIR="/opt/backups/redis"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
BACKUP_FILE="$BACKUP_DIR/redis_backup_$TIMESTAMP.rdb"

# 创建备份目录
mkdir -p $BACKUP_DIR

# 执行 RDB 快照
redis-cli -h redis-host -p 6379 --rdb $BACKUP_FILE

# 验证备份文件
if [ -f "$BACKUP_FILE" ] && [ -s "$BACKUP_FILE" ]; then
    echo "Redis backup completed: $BACKUP_FILE"
    echo "Backup size: $(du -h $BACKUP_FILE | cut -f1)"

    # 压缩备份文件
    gzip $BACKUP_FILE
    echo "Backup compressed: ${BACKUP_FILE}.gz"

    # 清理旧备份（保留7天）
    find $BACKUP_DIR -name "redis_backup_*.gz" -mtime +7 -delete
else
    echo "Redis backup failed!"
    exit 1
fi
```

#### ZooKeeper 数据备份

```bash
#!/bin/bash
# zookeeper-backup.sh

ZK_SERVERS="zk1:2181,zk2:2181,zk3:2181"
BACKUP_DIR="/opt/backups/zookeeper"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)

# 创建备份目录
mkdir -p $BACKUP_DIR

# 导出 ZooKeeper 数据
java -cp /opt/zookeeper/lib/*:/opt/zookeeper/conf org.apache.zookeeper.ZooKeeperMain \
    -server $ZK_SERVERS \
    <<EOF > $BACKUP_DIR/zk_backup_$TIMESTAMP.txt
ls /
get /distributed-locks
# 递归导出所有锁数据
EOF

# 验证备份
if [ -s "$BACKUP_DIR/zk_backup_$TIMESTAMP.txt" ]; then
    echo "ZooKeeper backup completed"
    gzip $BACKUP_DIR/zk_backup_$TIMESTAMP.txt
else
    echo "ZooKeeper backup failed!"
    exit 1
fi
```

#### 应用状态备份

```java
@Service
public class ApplicationStateBackupService {

    private final DistributedLockFactory lockFactory;
    private final ObjectMapper objectMapper;

    @Scheduled(cron = "0 0 */4 * * ?") // 每4小时备份一次
    public void backupApplicationState() {
        try {
            // 收集应用状态
            ApplicationState state = collectApplicationState();

            // 保存到备份存储
            String backupFile = "app-state-" + LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + ".json";
            saveToBackupStorage(backupFile, objectMapper.writeValueAsString(state));

            // 清理旧备份
            cleanupOldBackups(7); // 保留7天的备份

        } catch (Exception e) {
            System.err.println("Failed to backup application state: " + e.getMessage());
        }
    }

    private ApplicationState collectApplicationState() {
        return ApplicationState.builder()
            .activeLocks(lockFactory.getActiveLocks())
            .factoryStatistics(lockFactory.getStatistics())
            .configuration(getCurrentConfiguration())
            .timestamp(Instant.now())
            .build();
    }
}
```

### 配置备份

```bash
#!/bin/bash
# config-backup.sh

CONFIG_SOURCES=(
    "/etc/distributed-lock/application.yml"
    "/opt/distributed-lock/config/"
    "/home/app/.distributed-lock/"
)

BACKUP_DIR="/opt/backups/config"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)

# 创建备份目录
mkdir -p $BACKUP_DIR

# 备份配置文件
for config_src in "${CONFIG_SOURCES[@]}"; do
    if [ -e "$config_src" ]; then
        config_name=$(basename "$config_src")
        backup_file="$BACKUP_DIR/${config_name}_$TIMESTAMP.tar.gz"

        # 创建压缩备份
        tar -czf "$backup_file" -C "$(dirname "$config_src")" "$config_name" 2>/dev/null

        if [ $? -eq 0 ]; then
            echo "Config backup created: $backup_file"
        fi
    fi
done

# 备份环境变量
env | grep -E "^DISTRIBUTED_LOCK_" > "$BACKUP_DIR/env_vars_$TIMESTAMP.txt"

# 备份 JVM 参数
ps aux | grep java | grep -oP '(?<=java )[^ ]*' | head -1 | xargs jinfo > "$BACKUP_DIR/jvm_args_$TIMESTAMP.txt" 2>/dev/null

# 清理旧备份
find $BACKUP_DIR -name "*_$TIMESTAMP.*" -mtime +30 -delete
```

### 备份验证

```bash
#!/bin/bash
# backup-verification.sh

BACKUP_FILE=$1

if [ -z "$BACKUP_FILE" ]; then
    echo "Usage: $0 <backup_file>"
    exit 1
fi

echo "Verifying backup: $BACKUP_FILE"

# 检查文件完整性
if [ ! -f "$BACKUP_FILE" ]; then
    echo "❌ Backup file does not exist"
    exit 1
fi

# 检查文件大小
file_size=$(stat -f%z "$BACKUP_FILE" 2>/dev/null || stat -c%s "$BACKUP_FILE")
if [ "$file_size" -eq 0 ]; then
    echo "❌ Backup file is empty"
    exit 1
fi

echo "✅ Backup file exists and has size: $(numfmt --to=iec-i --suffix=B $file_size)"

# 根据文件类型进行特定验证
case "$BACKUP_FILE" in
    *.rdb)
        # Redis RDB 文件验证
        if command -v redis-check-rdb &> /dev/null; then
            if redis-check-rdb "$BACKUP_FILE" &>/dev/null; then
                echo "✅ Redis RDB file is valid"
            else
                echo "❌ Redis RDB file is corrupted"
                exit 1
            fi
        fi
        ;;
    *.gz)
        # 压缩文件完整性检查
        if gzip -t "$BACKUP_FILE" 2>/dev/null; then
            echo "✅ Compressed file is valid"
        else
            echo "❌ Compressed file is corrupted"
            exit 1
        fi
        ;;
    *.json)
        # JSON 文件验证
        if command -v jq &> /dev/null; then
            if jq empty "$BACKUP_FILE" 2>/dev/null; then
                echo "✅ JSON file is valid"
            else
                echo "❌ JSON file is invalid"
                exit 1
            fi
        fi
        ;;
esac

echo "✅ Backup verification completed successfully"
```

## 恢复策略

### 数据恢复

#### Redis 数据恢复

```bash
#!/bin/bash
# redis-restore.sh

BACKUP_FILE=$1
REDIS_HOST=${2:-localhost}
REDIS_PORT=${3:-6379}

if [ -z "$BACKUP_FILE" ]; then
    echo "Usage: $0 <backup_file> [redis_host] [redis_port]"
    exit 1
fi

echo "Restoring Redis data from: $BACKUP_FILE"

# 停止应用（避免数据冲突）
echo "Stopping application..."
systemctl stop distributed-lock-app

# 清理现有数据
echo "Flushing Redis database..."
redis-cli -h $REDIS_HOST -p $REDIS_PORT FLUSHDB

# 恢复数据
if [[ "$BACKUP_FILE" == *.gz ]]; then
    # 解压并恢复
    gunzip -c "$BACKUP_FILE" | redis-cli -h $REDIS_HOST -p $REDIS_PORT --pipe
else
    # 直接恢复
    cat "$BACKUP_FILE" | redis-cli -h $REDIS_HOST -p $REDIS_PORT --pipe
fi

# 验证恢复
key_count=$(redis-cli -h $REDIS_HOST -p $REDIS_PORT DBSIZE)
echo "Restored $key_count keys"

# 重启应用
echo "Starting application..."
systemctl start distributed-lock-app

echo "Redis restore completed"
```

#### ZooKeeper 数据恢复

```bash
#!/bin/bash
# zookeeper-restore.sh

BACKUP_FILE=$1
ZK_SERVERS=${2:-"localhost:2181"}

if [ -z "$BACKUP_FILE" ]; then
    echo "Usage: $0 <backup_file> [zk_servers]"
    exit 1
fi

echo "Restoring ZooKeeper data from: $BACKUP_FILE"

# 停止 ZooKeeper 集群（按顺序）
echo "Stopping ZooKeeper ensemble..."
for server in $(echo $ZK_SERVERS | tr ',' ' '); do
    ssh $server "systemctl stop zookeeper"
done

# 清理现有数据
echo "Cleaning existing data..."
for server in $(echo $ZK_SERVERS | tr ',' ' '); do
    ssh $server "rm -rf /var/lib/zookeeper/version-2/*"
done

# 恢复数据到 leader 节点
LEADER=$(echo $ZK_SERVERS | cut -d',' -f1)
scp $BACKUP_FILE $LEADER:/tmp/zk_backup.txt.gz

ssh $LEADER << EOF
cd /tmp
gunzip zk_backup.txt.gz
# 使用 ZooKeeper 命令恢复数据
# 注意：这需要自定义恢复脚本
EOF

# 重启 ZooKeeper 集群
echo "Starting ZooKeeper ensemble..."
for server in $(echo $ZK_SERVERS | tr ',' ' '); do
    ssh $server "systemctl start zookeeper"
done

echo "ZooKeeper restore completed"
```

#### 应用状态恢复

```java
@Service
public class ApplicationStateRecoveryService {

    private final DistributedLockFactory lockFactory;

    public void recoverApplicationState(String backupFile) {
        try {
            // 加载备份状态
            ApplicationState backupState = loadFromBackupStorage(backupFile);

            // 验证备份数据的时效性
            if (isBackupExpired(backupState)) {
                throw new IllegalStateException("Backup data is too old");
            }

            // 恢复锁状态
            recoverLockStates(backupState.getActiveLocks());

            // 恢复配置
            recoverConfiguration(backupState.getConfiguration());

            System.out.println("Application state recovered from: " + backupFile);

        } catch (Exception e) {
            System.err.println("Failed to recover application state: " + e.getMessage());
            throw new RuntimeException("State recovery failed", e);
        }
    }

    private void recoverLockStates(List<String> activeLocks) {
        // 注意：锁状态恢复比较复杂，需要根据具体业务逻辑实现
        // 这里只是示例，实际实现需要考虑锁的时效性和一致性

        for (String lockName : activeLocks) {
            try {
                DistributedLock lock = lockFactory.getLock(lockName);
                // 检查锁是否仍然有效
                if (isLockStillValid(lockName)) {
                    // 可能需要重新获取锁或标记状态
                    System.out.println("Recovered lock: " + lockName);
                }
            } catch (Exception e) {
                System.err.println("Failed to recover lock " + lockName + ": " + e.getMessage());
            }
        }
    }
}
```

### 配置恢复

```bash
#!/bin/bash
# config-restore.sh

BACKUP_FILE=$1
RESTORE_DIR=${2:-"/etc/distributed-lock"}

if [ -z "$BACKUP_FILE" ]; then
    echo "Usage: $0 <backup_file> [restore_dir]"
    exit 1
fi

echo "Restoring configuration from: $BACKUP_FILE"

# 创建恢复目录
mkdir -p $RESTORE_DIR

# 停止应用
echo "Stopping application..."
systemctl stop distributed-lock-app

# 恢复配置文件
if [[ "$BACKUP_FILE" == *.tar.gz ]]; then
    tar -xzf "$BACKUP_FILE" -C $RESTORE_DIR
elif [[ "$BACKUP_FILE" == *.zip ]]; then
    unzip "$BACKUP_FILE" -d $RESTORE_DIR
else
    cp "$BACKUP_FILE" $RESTORE_DIR/
fi

# 恢复环境变量
if [ -f "$BACKUP_FILE.env" ]; then
    while IFS='=' read -r key value; do
        export "$key=$value"
        echo "Restored environment variable: $key"
    done < "$BACKUP_FILE.env"
fi

# 验证配置
echo "Validating restored configuration..."
# 这里可以调用配置验证逻辑

# 重启应用
echo "Starting application..."
systemctl start distributed-lock-app

echo "Configuration restore completed"
```

## 故障恢复

### 自动故障恢复

```java
@Component
public class AutoRecoveryService {

    private final DistributedLockFactory lockFactory;
    private final HealthCheckService healthCheckService;

    @Scheduled(fixedRate = 30000) // 30秒检查一次
    public void performAutoRecovery() {
        try {
            // 检查系统健康状态
            HealthStatus health = healthCheckService.checkHealth();

            if (!health.isHealthy()) {
                // 执行自动恢复
                performRecoveryActions(health);
            }

        } catch (Exception e) {
            System.err.println("Auto recovery failed: " + e.getMessage());
        }
    }

    private void performRecoveryActions(HealthStatus health) {
        for (HealthIssue issue : health.getIssues()) {
            switch (issue.getType()) {
                case CONNECTION_POOL_EXHAUSTED:
                    scaleConnectionPool();
                    break;
                case HIGH_MEMORY_USAGE:
                    triggerGarbageCollection();
                    break;
                case BACKEND_UNAVAILABLE:
                    switchToBackupBackend();
                    break;
                case DEADLOCK_DETECTED:
                    restartAffectedServices();
                    break;
            }
        }
    }
}
```

### 手动故障恢复

```bash
#!/bin/bash
# manual-recovery.sh

echo "=== Manual Recovery Procedure ==="

# 1. 评估故障影响
echo "Step 1: Assessing failure impact..."
check_system_status
check_data_consistency
assess_business_impact

# 2. 隔离故障组件
echo "Step 2: Isolating failed components..."
isolate_failed_services
redirect_traffic_to_healthy_instances

# 3. 执行数据恢复
echo "Step 3: Performing data recovery..."
select_backup_source
restore_data_from_backup
verify_data_integrity

# 4. 重启服务
echo "Step 4: Restarting services..."
restart_services_in_order
verify_service_health

# 5. 验证恢复
echo "Step 5: Validating recovery..."
run_health_checks
run_integration_tests
validate_business_operations

# 6. 恢复流量
echo "Step 6: Restoring traffic..."
gradually_increase_traffic
monitor_system_performance

echo "=== Recovery completed ==="
```

### 降级恢复

```java
@Service
public class DegradationRecoveryService {

    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final DistributedLockFactory primaryFactory;
    private final DistributedLockFactory fallbackFactory;

    public DistributedLock getLockWithDegradation(String name) {
        try {
            // 尝试使用主工厂
            if (circuitBreakerRegistry.circuitBreaker("primary-backend").getState() == CLOSED) {
                return primaryFactory.getLock(name);
            }
        } catch (Exception e) {
            // 主工厂失败，记录错误
            circuitBreakerRegistry.circuitBreaker("primary-backend").recordFailure(e);
        }

        // 使用降级工厂
        System.out.println("Using fallback lock factory for: " + name);
        return fallbackFactory.getLock(name);
    }

    @Scheduled(fixedRate = 60000) // 1分钟检查一次
    public void attemptRecovery() {
        try {
            // 尝试恢复主工厂
            if (primaryFactory.healthCheck().isHealthy()) {
                circuitBreakerRegistry.circuitBreaker("primary-backend").reset();
                System.out.println("Primary backend recovered");
            }
        } catch (Exception e) {
            // 恢复失败，继续使用降级模式
        }
    }
}
```

## 业务连续性

### 高可用部署

```yaml
# 高可用部署配置
apiVersion: apps/v1
kind: Deployment
metadata:
  name: distributed-lock-app
spec:
  replicas: 3
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxUnavailable: 1
      maxSurge: 1
  template:
    spec:
      affinity:
        podAntiAffinity:
          requiredDuringSchedulingIgnoredDuringExecution:
          - labelSelector:
              matchExpressions:
              - key: app
                operator: In
                values:
                - distributed-lock-app
            topologyKey: kubernetes.io/hostname
      containers:
      - name: app
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

### 多区域部署

```yaml
# 多区域部署
apiVersion: apps/v1
kind: Deployment
metadata:
  name: distributed-lock-app-us-west
spec:
  replicas: 2
  template:
    spec:
      nodeSelector:
        region: us-west
      containers:
      - name: app
        env:
        - name: DISTRIBUTED_LOCK_BACKEND
          value: "redis"
        - name: DISTRIBUTED_LOCK_REDIS_HOSTS
          value: "redis-us-west:6379"
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: distributed-lock-app-us-east
spec:
  replicas: 2
  template:
    spec:
      nodeSelector:
        region: us-east
      containers:
      - name: app
        env:
        - name: DISTRIBUTED_LOCK_BACKEND
          value: "redis"
        - name: DISTRIBUTED_LOCK_REDIS_HOSTS
          value: "redis-us-east:6379"
```

### 灾难恢复计划

```bash
#!/bin/bash
# disaster-recovery.sh

echo "=== Disaster Recovery Plan Execution ==="

# 1. 激活灾难恢复模式
echo "Activating disaster recovery mode..."
enable_disaster_recovery_mode

# 2. 切换到备份数据中心
echo "Switching to backup data center..."
switch_to_backup_datacenter

# 3. 恢复数据从异地备份
echo "Restoring data from offsite backup..."
restore_from_offsite_backup

# 4. 重定向用户流量
echo "Redirecting user traffic..."
update_dns_records
update_load_balancer_config

# 5. 验证系统功能
echo "Validating system functionality..."
run_disaster_recovery_tests

# 6. 通知利益相关者
echo "Notifying stakeholders..."
send_recovery_notification

echo "=== Disaster recovery completed ==="
```

## 备份监控

### 备份成功率监控

```prometheus
# 备份成功率
backup_success_rate = rate(backup_operations_total{result="success"}[7d]) / rate(backup_operations_total[7d])

# 备份失败告警
- alert: BackupFailure
  expr: rate(backup_operations_total{result="failure"}[1h]) > 0
  labels:
    severity: critical
  annotations:
    summary: "Backup operation failed"
    description: "Backup failed for {{ $labels.backup_type }}"

# 备份年龄监控
- alert: BackupTooOld
  expr: time() - backup_last_success_timestamp > 86400  # 24小时
  labels:
    severity: warning
  annotations:
    summary: "Backup is too old"
    description: "Last successful backup was {{ $value }} seconds ago"
```

### 恢复演练

```bash
#!/bin/bash
# recovery-drill.sh

echo "=== Recovery Drill Execution ==="

# 1. 准备测试环境
echo "Setting up test environment..."
create_test_environment

# 2. 执行备份
echo "Performing backup..."
run_backup_procedure

# 3. 模拟故障
echo "Simulating failure..."
simulate_system_failure

# 4. 执行恢复
echo "Executing recovery procedure..."
run_recovery_procedure

# 5. 验证恢复结果
echo "Validating recovery results..."
validate_recovery_success

# 6. 清理测试环境
echo "Cleaning up test environment..."
cleanup_test_environment

# 7. 生成演练报告
echo "Generating drill report..."
generate_drill_report

echo "=== Recovery drill completed ==="
```

## 总结

完整的备份恢复体系包括：

1. **备份策略**：定期自动备份数据、配置和应用状态
2. **恢复策略**：数据恢复、配置恢复和应用状态恢复
3. **故障恢复**：自动恢复、手动恢复和降级恢复
4. **业务连续性**：高可用部署、多区域部署和灾难恢复
5. **监控告警**：备份成功率监控和恢复演练

通过完善的备份恢复体系，可以确保系统在各种故障场景下能够快速恢复，保障业务的连续性。