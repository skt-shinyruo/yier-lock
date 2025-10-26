# 监控运维指南

## 概述

本指南介绍分布式锁框架的监控体系，包括指标收集、告警配置、可视化展示和运维最佳实践。

## 监控架构

### 指标收集架构

```
应用实例 ── Micrometer ── Prometheus ── Grafana
    │                                        │
    └─ 业务指标                              └─ 仪表板
       │                                        │
       ├─ 锁操作统计                            ├─ 性能图表
       ├─ 错误统计                              ├─ 告警面板
       └─ 健康状态                              └─ 趋势分析
```

### 监控组件

1. **Micrometer**：指标收集和暴露
2. **Prometheus**：指标存储和查询
3. **Grafana**：可视化展示和告警
4. **AlertManager**：告警管理和通知

## 指标体系

### 核心指标

#### 锁操作指标

```prometheus
# 锁操作总数
distributed_lock_operations_total{operation="acquire", result="success"} 1250
distributed_lock_operations_total{operation="acquire", result="failure"} 23
distributed_lock_operations_total{operation="release", result="success"} 1227

# 锁获取时间分布 (直方图)
distributed_lock_acquisition_duration_seconds_bucket{le="0.1"} 850
distributed_lock_acquisition_duration_seconds_bucket{le="1.0"} 1200
distributed_lock_acquisition_duration_seconds_bucket{le="5.0"} 1250
distributed_lock_acquisition_duration_bucket{le="+Inf"} 1250

# 锁持有时间分布
distributed_lock_hold_duration_seconds{quantile="0.5"} 0.85
distributed_lock_hold_duration_seconds{quantile="0.95"} 2.1
distributed_lock_hold_duration_seconds{quantile="0.99"} 4.5
```

#### 性能指标

```prometheus
# 响应时间
distributed_lock_response_time_seconds{quantile="0.95"} 0.023

# 吞吐量
rate(distributed_lock_operations_total[5m]) 42.3

# 错误率
rate(distributed_lock_operations_total{result="failure"}[5m]) / rate(distributed_lock_operations_total[5m]) 0.018
```

#### 资源指标

```prometheus
# 活跃锁数量
distributed_lock_active_count 15

# 连接池状态
distributed_lock_connection_pool_active 8
distributed_lock_connection_pool_idle 12
distributed_lock_connection_pool_waiting 2

# JVM 指标
jvm_memory_used_bytes{area="heap"} 256000000
jvm_gc_pause_seconds{quantile="0.95"} 0.015
```

### 业务指标

```prometheus
# 业务成功率
business_operation_success_total 9876
business_operation_failure_total 123

# 锁竞争率
distributed_lock_contention_rate 0.15

# 死锁检测
distributed_lock_deadlocks_detected_total 0
```

## Prometheus 配置

### 基础配置

```yaml
# prometheus.yml
global:
  scrape_interval: 15s
  evaluation_interval: 15s

rule_files:
  - "alert_rules.yml"

scrape_configs:
  - job_name: 'distributed-lock-app'
    static_configs:
      - targets: ['localhost:8080']
    metrics_path: '/actuator/prometheus'
    scrape_interval: 15s

  - job_name: 'redis'
    static_configs:
      - targets: ['localhost:6379']
    metrics_path: '/metrics'

  - job_name: 'zookeeper'
    static_configs:
      - targets: ['localhost:2181']
    metrics_path: '/metrics'
```

### 高级配置

```yaml
# prometheus.yml (生产环境)
global:
  scrape_interval: 30s
  evaluation_interval: 30s

# 远程存储
remote_write:
  - url: "http://cortex:9009/api/prom/push"
    write_relabel_configs:
      - source_labels: [__name__]
        regex: 'distributed_lock_.*'
        action: keep

# 服务发现
scrape_configs:
  - job_name: 'distributed-lock-k8s'
    kubernetes_sd_configs:
      - role: pod
    relabel_configs:
      - source_labels: [__meta_kubernetes_pod_label_app]
        regex: distributed-lock-app
        action: keep
      - source_labels: [__meta_kubernetes_pod_ip]
        target_label: __address__
        replacement: ${1}:8080
    metrics_path: '/actuator/prometheus'

  - job_name: 'redis-cluster'
    static_configs:
      - targets:
          - redis-1:6379
          - redis-2:6379
          - redis-3:6379
    metrics_path: '/metrics'
    scrape_interval: 60s
```

## Grafana 仪表板

### 核心仪表板

```json
{
  "dashboard": {
    "title": "Distributed Lock Overview",
    "tags": ["distributed-lock", "performance"],
    "timezone": "browser",
    "panels": [
      {
        "title": "Lock Operations Rate",
        "type": "graph",
        "targets": [
          {
            "expr": "rate(distributed_lock_operations_total[5m])",
            "legendFormat": "{{operation}} - {{result}}"
          }
        ]
      },
      {
        "title": "Lock Acquisition Time",
        "type": "heatmap",
        "targets": [
          {
            "expr": "rate(distributed_lock_acquisition_duration_seconds_bucket[5m])",
            "legendFormat": "{{le}}"
          }
        ]
      },
      {
        "title": "Active Locks",
        "type": "singlestat",
        "targets": [
          {
            "expr": "distributed_lock_active_count",
            "legendFormat": "Active Locks"
          }
        ]
      },
      {
        "title": "Error Rate",
        "type": "graph",
        "targets": [
          {
            "expr": "rate(distributed_lock_operations_total{result='failure'}[5m]) / rate(distributed_lock_operations_total[5m]) * 100",
            "legendFormat": "Error Rate %"
          }
        ]
      }
    ]
  }
}
```

### 详细监控面板

```json
{
  "dashboard": {
    "title": "Distributed Lock Details",
    "panels": [
      {
        "title": "Connection Pool Status",
        "type": "table",
        "targets": [
          {
            "expr": "distributed_lock_connection_pool_active",
            "legendFormat": "Active Connections"
          },
          {
            "expr": "distributed_lock_connection_pool_idle",
            "legendFormat": "Idle Connections"
          },
          {
            "expr": "distributed_lock_connection_pool_waiting",
            "legendFormat": "Waiting Threads"
          }
        ]
      },
      {
        "title": "JVM Memory",
        "type": "graph",
        "targets": [
          {
            "expr": "jvm_memory_used_bytes{area='heap'}",
            "legendFormat": "Heap Used"
          },
          {
            "expr": "jvm_memory_committed_bytes{area='heap'}",
            "legendFormat": "Heap Committed"
          }
        ]
      },
      {
        "title": "GC Performance",
        "type": "graph",
        "targets": [
          {
            "expr": "rate(jvm_gc_pause_seconds_sum[5m]) / rate(jvm_gc_pause_seconds_count[5m])",
            "legendFormat": "Avg GC Pause"
          }
        ]
      }
    ]
  }
}
```

## 告警配置

### 基础告警规则

```yaml
# alert_rules.yml
groups:
  - name: distributed_lock
    rules:
      # 高错误率告警
      - alert: HighLockErrorRate
        expr: rate(distributed_lock_operations_total{result="failure"}[5m]) / rate(distributed_lock_operations_total[5m]) > 0.05
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "High distributed lock error rate"
          description: "Lock error rate is {{ $value }}% (threshold: 5%)"

      # 慢操作告警
      - alert: SlowLockOperations
        expr: histogram_quantile(0.95, rate(distributed_lock_acquisition_duration_seconds_bucket[5m])) > 1.0
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Slow distributed lock operations"
          description: "95th percentile acquisition time is {{ $value }}s (threshold: 1s)"

      # 连接池耗尽告警
      - alert: ConnectionPoolExhausted
        expr: distributed_lock_connection_pool_waiting > 10
        for: 2m
        labels:
          severity: critical
        annotations:
          summary: "Connection pool exhausted"
          description: "{{ $value }} threads waiting for connections"

      # 内存使用过高告警
      - alert: HighMemoryUsage
        expr: jvm_memory_used_bytes{area="heap"} / jvm_memory_max_bytes{area="heap"} > 0.9
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "High JVM memory usage"
          description: "Heap usage is {{ $value }}% (threshold: 90%)"
```

### 高级告警规则

```yaml
# advanced_alert_rules.yml
groups:
  - name: distributed_lock_advanced
    rules:
      # 死锁检测
      - alert: DeadlockDetected
        expr: increase(distributed_lock_deadlocks_detected_total[5m]) > 0
        labels:
          severity: critical
        annotations:
          summary: "Deadlock detected in distributed locks"
          description: "{{ $value }} deadlocks detected in the last 5 minutes"

      # 锁竞争激烈
      - alert: HighLockContention
        expr: distributed_lock_contention_rate > 0.8
        for: 10m
        labels:
          severity: warning
        annotations:
          summary: "High lock contention detected"
          description: "Lock contention rate is {{ $value }} (threshold: 0.8)"

      # 业务影响告警
      - alert: BusinessOperationFailure
        expr: rate(business_operation_failure_total[5m]) / rate(business_operation_total[5m]) > 0.1
        for: 5m
        labels:
          severity: critical
        annotations:
          summary: "High business operation failure rate"
          description: "Business failure rate is {{ $value }}% (threshold: 10%)"

      # 容量预警
      - alert: ApproachingCapacityLimit
        expr: distributed_lock_active_count / distributed_lock_max_locks > 0.8
        for: 10m
        labels:
          severity: warning
        annotations:
          summary: "Approaching lock capacity limit"
          description: "Active locks: {{ $value }}% of capacity"
```

## AlertManager 配置

### 基础配置

```yaml
# alertmanager.yml
global:
  smtp_smarthost: 'smtp.company.com:587'
  smtp_from: 'alerts@company.com'
  smtp_auth_username: 'alerts@company.com'
  smtp_auth_password: 'password'

route:
  group_by: ['alertname', 'severity']
  group_wait: 10s
  group_interval: 10s
  repeat_interval: 1h
  receiver: 'team-email'
  routes:
    - match:
        severity: critical
      receiver: 'critical-pager'
      continue: true

receivers:
  - name: 'team-email'
    email_configs:
      - to: 'dev-team@company.com'
        subject: '{{ .GroupLabels.alertname }}'
        body: |
          {{ range .Alerts }}
          Alert: {{ .Annotations.summary }}
          Description: {{ .Annotations.description }}
          {{ end }}

  - name: 'critical-pager'
    pagerduty_configs:
      - service_key: 'your-pagerduty-key'
```

### 高级配置

```yaml
# alertmanager.yml (生产环境)
global:
  resolve_timeout: 5m

route:
  group_by: ['alertname', 'cluster', 'service']
  group_wait: 30s
  group_interval: 5m
  repeat_interval: 4h
  receiver: 'default'
  routes:
    - match:
        severity: critical
      receiver: 'critical'
      continue: true
    - match:
        team: devops
      receiver: 'devops-team'
    - match:
        team: backend
      receiver: 'backend-team'

receivers:
  - name: 'default'
    slack_configs:
      - api_url: 'https://hooks.slack.com/services/YOUR/SLACK/WEBHOOK'
        channel: '#alerts'
        title: '{{ .GroupLabels.alertname }}'
        text: |
          {{ range .Alerts }}
          *{{ .Annotations.summary }}*
          {{ .Annotations.description }}
          {{ end }}

  - name: 'critical'
    pagerduty_configs:
      - service_key: '{{ .CommonLabels.pd_service_key }}'
    slack_configs:
      - api_url: 'https://hooks.slack.com/services/YOUR/SLACK/WEBHOOK'
        channel: '#critical-alerts'
        title: '🚨 CRITICAL: {{ .GroupLabels.alertname }}'

  - name: 'devops-team'
    email_configs:
      - to: 'devops@company.com'
        subject: '[DevOps] {{ .GroupLabels.alertname }}'

  - name: 'backend-team'
    email_configs:
      - to: 'backend@company.com'
        subject: '[Backend] {{ .GroupLabels.alertname }}'
```

## 监控最佳实践

### 指标命名规范

```prometheus
# 好的命名示例
distributed_lock_operations_total{operation="acquire", result="success"}
distributed_lock_acquisition_duration_seconds{quantile="0.95"}
distributed_lock_active_count

# 不好的命名示例
lock_ops  # 过于简略
distributed_lock_total_operations  # 冗余
lock_count_active  # 顺序不当
```

### 标签使用规范

```prometheus
# 标准标签
{operation="acquire", result="success", backend="redis"}

# 业务标签
{service="order-service", method="createOrder"}

# 环境标签
{cluster="prod", region="us-west", instance="app-01"}
```

### 仪表板组织

1. **概览仪表板**：关键指标总览
2. **详细仪表板**：特定组件深入分析
3. **业务仪表板**：业务指标和 SLA
4. **容量仪表板**：资源使用和容量规划
5. **故障排查仪表板**：问题诊断和根本原因分析

## 运维脚本

### 监控检查脚本

```bash
#!/bin/bash
# monitor-check.sh

echo "=== Distributed Lock Health Check ==="

# 检查应用健康状态
echo "Checking application health..."
health_status=$(curl -s http://localhost:8080/actuator/health | jq -r '.status')
if [ "$health_status" != "UP" ]; then
    echo "❌ Application health: $health_status"
    exit 1
else
    echo "✅ Application health: $health_status"
fi

# 检查锁工厂健康状态
echo "Checking lock factory health..."
lock_health=$(curl -s http://localhost:8080/actuator/health | jq -r '.components.distributedLock.status')
if [ "$lock_health" != "UP" ]; then
    echo "❌ Lock factory health: $lock_health"
    exit 1
else
    echo "✅ Lock factory health: $lock_health"
fi

# 检查关键指标
echo "Checking key metrics..."
error_rate=$(curl -s "http://localhost:9090/api/v1/query?query=rate(distributed_lock_operations_total{result='failure'}[5m])%20%2F%20rate(distributed_lock_operations_total[5m])" | jq -r '.data.result[0].value[1]')

if (( $(echo "$error_rate > 0.05" | bc -l) )); then
    echo "❌ High error rate: $error_rate"
    exit 1
else
    echo "✅ Error rate: $error_rate"
fi

echo "=== All checks passed ==="
```

### 性能诊断脚本

```bash
#!/bin/bash
# performance-diagnostic.sh

echo "=== Performance Diagnostic Report ==="
echo "Generated at: $(date)"
echo

# 系统信息
echo "=== System Information ==="
echo "CPU cores: $(nproc)"
echo "Memory: $(free -h | grep '^Mem:' | awk '{print $2}')"
echo "Load average: $(uptime | awk -F'load average:' '{print $2}')"
echo

# JVM 信息
echo "=== JVM Information ==="
jps_output=$(jps -l)
echo "Java processes:"
echo "$jps_output"
echo

# 选择应用进程
app_pid=$(echo "$jps_output" | grep -v Jps | head -1 | awk '{print $1}')

if [ -n "$app_pid" ]; then
    echo "=== JVM Memory Analysis ==="
    jmap -heap "$app_pid" | head -20
    echo

    echo "=== Thread Analysis ==="
    jstack "$app_pid" | grep -E "(BLOCKED|WAITING|TIMED_WAITING)" | wc -l
    echo "threads in blocking/waiting state"
    echo
fi

# 应用指标
echo "=== Application Metrics ==="
echo "Active locks:"
curl -s "http://localhost:9090/api/v1/query?query=distributed_lock_active_count" | jq -r '.data.result[0].value[1]'

echo "Operations per second:"
curl -s "http://localhost:9090/api/v1/query?query=rate(distributed_lock_operations_total[1m])" | jq -r '.data.result[0].value[1]'

echo "95th percentile response time:"
curl -s "http://localhost:9090/api/v1/query?query=histogram_quantile(0.95,%20rate(distributed_lock_acquisition_duration_seconds_bucket[5m]))" | jq -r '.data.result[0].value[1]'
```

### 自动扩容脚本

```bash
#!/bin/bash
# auto-scale.sh

# 配置
MAX_INSTANCES=10
MIN_INSTANCES=2
SCALE_UP_THRESHOLD=80  # CPU 使用率阈值
SCALE_DOWN_THRESHOLD=30

# 获取当前实例数
current_instances=$(kubectl get pods -l app=distributed-lock-app --no-headers | wc -l)

# 获取平均 CPU 使用率
cpu_usage=$(kubectl top pods -l app=distributed-lock-app --no-headers | awk '{sum += $2} END {print sum/NR}')

echo "Current instances: $current_instances"
echo "Average CPU usage: $cpu_usage%"

# 扩容逻辑
if (( $(echo "$cpu_usage > $SCALE_UP_THRESHOLD" | bc -l) )) && (( current_instances < MAX_INSTANCES )); then
    new_instances=$((current_instances + 1))
    echo "Scaling up to $new_instances instances"
    kubectl scale deployment distributed-lock-app --replicas=$new_instances

elif (( $(echo "$cpu_usage < $SCALE_DOWN_THRESHOLD" | bc -l) )) && (( current_instances > MIN_INSTANCES )); then
    new_instances=$((current_instances - 1))
    echo "Scaling down to $new_instances instances"
    kubectl scale deployment distributed-lock-app --replicas=$new_instances

else
    echo "No scaling action needed"
fi
```

## 容量规划

### 资源估算

```bash
# 基于负载估算资源需求
#!/bin/bash
# capacity-planning.sh

# 输入参数
expected_qps=1000
avg_lock_time_ms=500
concurrent_locks=$((expected_qps * avg_lock_time_ms / 1000))

echo "Expected QPS: $expected_qps"
echo "Average lock time: ${avg_lock_time_ms}ms"
echo "Estimated concurrent locks: $concurrent_locks"

# Redis 内存估算 (每个锁约 1KB)
redis_memory_mb=$((concurrent_locks * 1024 / 1024 / 1024))
echo "Estimated Redis memory: ${redis_memory_mb}MB"

# JVM 堆内存估算
jvm_heap_mb=$((concurrent_locks * 2))  # 粗略估算
echo "Estimated JVM heap: ${jvm_heap_mb}MB"

# CPU 核心数估算
cpu_cores=$((expected_qps / 500 + 1))  # 每500 QPS 一个核心
echo "Estimated CPU cores: $cpu_cores"
```

### 监控阈值设置

```yaml
# monitoring-thresholds.yml
thresholds:
  # 性能阈值
  max_response_time_p95: 1000ms
  max_error_rate: 0.05
  min_throughput: 100

  # 资源阈值
  max_cpu_usage: 80
  max_memory_usage: 85
  max_connection_pool_usage: 90

  # 业务阈值
  max_lock_contention_rate: 0.8
  max_deadlocks_per_hour: 5
  min_business_success_rate: 0.95
```

## 总结

完整的监控体系包括：

1. **指标收集**：使用 Micrometer 收集详细的性能和业务指标
2. **存储查询**：使用 Prometheus 进行指标存储和查询
3. **可视化**：使用 Grafana 创建直观的仪表板
4. **告警通知**：使用 AlertManager 进行智能告警和通知
5. **运维脚本**：提供自动化检查和维护脚本
6. **容量规划**：基于监控数据进行容量规划和资源优化

通过完善的监控体系，可以确保分布式锁系统的稳定运行和快速问题定位。