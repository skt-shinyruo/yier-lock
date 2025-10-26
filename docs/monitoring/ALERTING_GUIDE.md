# 告警配置指南

## 概述

本指南介绍分布式锁框架的告警系统配置，包括告警规则定义、通知渠道设置、告警升级策略和最佳实践。

## 告警分层

### 告警级别

1. **Information**：信息级告警，仅记录日志
2. **Warning**：警告级告警，需要关注但不紧急
3. **Critical**：严重级告警，需要立即处理
4. **Fatal**：致命级告警，系统不可用

### 告警分类

- **系统告警**：基础设施和系统层面的问题
- **性能告警**：性能指标异常
- **业务告警**：业务逻辑和数据一致性问题
- **安全告警**：安全威胁和违规行为

## Prometheus 告警规则

### 基础告警规则

```yaml
# alert_rules.yml
groups:
  - name: distributed_lock_system
    rules:
      # 应用健康状态
      - alert: ApplicationDown
        expr: up{job="distributed-lock-app"} == 0
        for: 1m
        labels:
          severity: critical
          category: system
        annotations:
          summary: "Application is down"
          description: "Application {{ $labels.instance }} has been down for more than 1 minute"
          runbook_url: "https://docs.company.com/runbooks/application-down"

      # JVM 内存不足
      - alert: JvmMemoryLow
        expr: jvm_memory_used_bytes{area="heap"} / jvm_memory_max_bytes{area="heap"} > 0.9
        for: 5m
        labels:
          severity: warning
          category: system
        annotations:
          summary: "JVM memory usage is high"
          description: "JVM heap usage is {{ $value }}% on {{ $labels.instance }}"

      # 磁盘空间不足
      - alert: DiskSpaceLow
        expr: (1 - node_filesystem_avail_bytes / node_filesystem_size_bytes) > 0.85
        for: 5m
        labels:
          severity: warning
          category: system
        annotations:
          summary: "Disk space is low"
          description: "Disk usage is {{ $value }}% on {{ $labels.instance }}"
```

### 锁操作告警规则

```yaml
# lock_alert_rules.yml
groups:
  - name: distributed_lock_operations
    rules:
      # 锁获取失败率高
      - alert: HighLockAcquisitionFailureRate
        expr: rate(distributed_lock_operations_total{operation="acquire", result="failure"}[5m]) / rate(distributed_lock_operations_total{operation="acquire"}[5m]) > 0.1
        for: 5m
        labels:
          severity: critical
          category: performance
        annotations:
          summary: "High lock acquisition failure rate"
          description: "Lock acquisition failure rate is {{ $value }}% (threshold: 10%) on {{ $labels.instance }}"
          impact: "Business operations may be failing due to lock contention"
          action: "Check for deadlocks, high contention, or backend issues"

      # 锁获取时间过长
      - alert: SlowLockAcquisition
        expr: histogram_quantile(0.95, rate(distributed_lock_acquisition_duration_seconds_bucket[5m])) > 2.0
        for: 5m
        labels:
          severity: warning
          category: performance
        annotations:
          summary: "Slow lock acquisition times"
          description: "95th percentile lock acquisition time is {{ $value }}s (threshold: 2s)"
          impact: "Business operations are experiencing delays"
          action: "Check network latency, backend performance, or connection pool"

      # 锁竞争激烈
      - alert: HighLockContention
        expr: distributed_lock_contention_rate > 0.8
        for: 10m
        labels:
          severity: warning
          category: performance
        annotations:
          summary: "High lock contention detected"
          description: "Lock contention rate is {{ $value }} (threshold: 0.8)"
          impact: "Multiple threads/processes are competing for the same locks"
          action: "Review lock usage patterns, consider lock granularity optimization"

      # 死锁检测
      - alert: DeadlockDetected
        expr: increase(distributed_lock_deadlocks_detected_total[5m]) > 0
        labels:
          severity: critical
          category: business
        annotations:
          summary: "Deadlock detected"
          description: "{{ $value }} deadlocks detected in the last 5 minutes"
          impact: "Business operations are blocked"
          action: "Check application logs for deadlock details, restart affected services if necessary"
```

### 连接池告警规则

```yaml
# connection_pool_alert_rules.yml
groups:
  - name: distributed_lock_connections
    rules:
      # 连接池耗尽
      - alert: ConnectionPoolExhausted
        expr: distributed_lock_connection_pool_waiting > 20
        for: 2m
        labels:
          severity: critical
          category: system
        annotations:
          summary: "Connection pool exhausted"
          description: "{{ $value }} threads waiting for database connections"
          impact: "New requests cannot acquire connections"
          action: "Increase connection pool size or check for connection leaks"

      # 连接池使用率高
      - alert: HighConnectionPoolUsage
        expr: distributed_lock_connection_pool_active / distributed_lock_connection_pool_max_total > 0.9
        for: 5m
        labels:
          severity: warning
          category: system
        annotations:
          summary: "High connection pool usage"
          description: "Connection pool usage is {{ $value }}% (threshold: 90%)"
          impact: "Risk of connection pool exhaustion"
          action: "Monitor connection usage patterns, consider increasing pool size"

      # 连接超时
      - alert: ConnectionTimeouts
        expr: rate(distributed_lock_connection_timeouts_total[5m]) > 5
        for: 2m
        labels:
          severity: warning
          category: system
        annotations:
          summary: "Connection timeouts detected"
          description: "{{ $value }} connection timeouts in the last 5 minutes"
          impact: "Operations are failing due to connection issues"
          action: "Check network connectivity, backend availability, or timeout settings"
```

### 业务影响告警规则

```yaml
# business_impact_alert_rules.yml
groups:
  - name: distributed_lock_business
    rules:
      # 业务操作失败率高
      - alert: HighBusinessFailureRate
        expr: rate(business_operation_failure_total[5m]) / rate(business_operation_total[5m]) > 0.05
        for: 5m
        labels:
          severity: critical
          category: business
        annotations:
          summary: "High business operation failure rate"
          description: "Business operation failure rate is {{ $value }}% (threshold: 5%)"
          impact: "Customers are experiencing failures"
          action: "Check application logs, database connectivity, and lock operations"

      # 订单处理延迟
      - alert: OrderProcessingDelay
        expr: histogram_quantile(0.95, rate(order_processing_duration_seconds_bucket[5m])) > 30
        for: 5m
        labels:
          severity: warning
          category: business
        annotations:
          summary: "Order processing delays detected"
          description: "95th percentile order processing time is {{ $value }}s (threshold: 30s)"
          impact: "Orders are taking longer than expected to process"
          action: "Check lock contention, database performance, or application bottlenecks"

      # 库存不一致
      - alert: InventoryInconsistency
        expr: abs(inventory_calculated_total - inventory_actual_total) > 10
        for: 1m
        labels:
          severity: critical
          category: business
        annotations:
          summary: "Inventory inconsistency detected"
          description: "Inventory discrepancy of {{ $value }} items detected"
          impact: "Inventory data is inconsistent, may lead to overselling"
          action: "Check for concurrent modification issues, review lock usage in inventory operations"
```

## AlertManager 配置

### 基础通知配置

```yaml
# alertmanager.yml
global:
  smtp_smarthost: 'smtp.company.com:587'
  smtp_from: 'alerts@company.com'
  smtp_auth_username: 'alerts@company.com'
  smtp_auth_password: 'alert-password'

  # HTTP 配置用于 webhook
  http_config:
    proxy_url: 'http://proxy.company.com:8080'

route:
  group_by: ['alertname', 'cluster', 'service']
  group_wait: 30s       # 等待组内其他告警的时间
  group_interval: 5m    # 组间发送间隔
  repeat_interval: 4h   # 重复告警间隔
  receiver: 'default'
  routes:
    # 严重告警立即通知
    - match:
        severity: critical
      receiver: 'critical-alerts'
      continue: true

    # 不同团队的告警路由
    - match:
        team: backend
      receiver: 'backend-team'
    - match:
        team: devops
      receiver: 'devops-team'

receivers:
  - name: 'default'
    slack_configs:
      - api_url: 'https://hooks.slack.com/services/YOUR/SLACK/WEBHOOK'
        channel: '#alerts'
        title: '[{{ .Status | toUpper }}{{ if eq .Status "firing" }}:{{ .Alerts.Firing | len }}{{ end }}] {{ .GroupLabels.alertname }}'
        text: |
          {{ range .Alerts }}
          *Alert:* {{ .Annotations.summary }}
          *Description:* {{ .Annotations.description }}
          *Severity:* {{ .Labels.severity }}
          *Instance:* {{ .Labels.instance }}
          {{ if .Annotations.action }}*Action:* {{ .Annotations.action }}{{ end }}
          {{ if .Annotations.impact }}*Impact:* {{ .Annotations.impact }}{{ end }}
          {{ end }}

  - name: 'critical-alerts'
    pagerduty_configs:
      - service_key: 'your-pagerduty-integration-key'
    slack_configs:
      - api_url: 'https://hooks.slack.com/services/YOUR/SLACK/WEBHOOK'
        channel: '#critical-alerts'
        title: '🚨 CRITICAL ALERT: {{ .GroupLabels.alertname }}'
        color: 'danger'
```

### 高级通知配置

```yaml
# advanced-alertmanager.yml
receivers:
  - name: 'backend-team'
    email_configs:
      - to: 'backend-team@company.com'
        subject: '[Backend Alert] {{ .GroupLabels.alertname }}'
        headers:
          From: 'AlertManager <alerts@company.com>'
          To: 'backend-team@company.com'
        html: |
          <h2>{{ .GroupLabels.alertname }}</h2>
          <table border="1">
          {{ range .Alerts }}
          <tr>
            <td>Severity</td>
            <td>{{ .Labels.severity }}</td>
          </tr>
          <tr>
            <td>Summary</td>
            <td>{{ .Annotations.summary }}</td>
          </tr>
          <tr>
            <td>Description</td>
            <td>{{ .Annotations.description }}</td>
          </tr>
          {{ if .Annotations.action }}
          <tr>
            <td>Action Required</td>
            <td>{{ .Annotations.action }}</td>
          </tr>
          {{ end }}
          {{ end }}
          </table>

  - name: 'devops-team'
    webhook_configs:
      - url: 'http://alert-webhook.company.com/webhook'
        http_config:
          bearer_token: 'your-webhook-token'
        send_resolved: true

  - name: 'sms-alerts'
    webhook_configs:
      - url: 'http://sms-gateway.company.com/alert'
        http_config:
          basic_auth:
            username: 'alertmanager'
            password: 'password'
```

## 告警抑制和静默

### 告警抑制规则

```yaml
# inhibit_rules.yml
inhibit_rules:
  # 如果应用宕机，抑制所有相关告警
  - source_match:
      alertname: ApplicationDown
    target_match:
      instance: '{{ $labels.instance }}'
    equal: ['instance']

  # 如果节点不可用，抑制该节点的所有告警
  - source_match:
      alertname: NodeDown
    target_match_re:
      instance: '{{ $labels.instance }}:.+'
    equal: ['instance']

  # 维护期间抑制告警
  - source_match:
      alertname: MaintenanceMode
    target_match:
      region: '{{ $labels.region }}'
    equal: ['region']
```

### 计划内维护静默

```bash
# 创建维护静默
curl -X POST http://alertmanager:9093/api/v2/silences \
  -H "Content-Type: application/json" \
  -d '{
    "matchers": [
      {
        "name": "instance",
        "value": "app-server-01",
        "isRegex": false
      }
    ],
    "startsAt": "2024-01-01T00:00:00Z",
    "endsAt": "2024-01-01T02:00:00Z",
    "createdBy": "maintenance-team",
    "comment": "Scheduled maintenance on app-server-01"
  }'
```

## 告警升级策略

### 时间-based 升级

```yaml
# escalation_rules.yml
groups:
  - name: alert_escalation
    rules:
      # 警告级告警 15 分钟后升级为严重
      - alert: WarningEscalation
        expr: time() - alert_start_time{severity="warning"} > 900
        labels:
          severity: critical
        annotations:
          summary: "Warning alert escalated to critical"
          description: "Alert {{ $labels.alertname }} has been firing for 15+ minutes"

      # 严重告警 30 分钟后升级为致命
      - alert: CriticalEscalation
        expr: time() - alert_start_time{severity="critical"} > 1800
        labels:
          severity: fatal
        annotations:
          summary: "Critical alert escalated to fatal"
          description: "Alert {{ $labels.alertname }} has been firing for 30+ minutes"
```

### 频率-based 升级

```yaml
# frequency_escalation.yml
groups:
  - name: frequency_escalation
    rules:
      # 频繁出现的告警升级
      - alert: FrequentAlertEscalation
        expr: count_over_time(up{job="distributed-lock-app"} == 0 [1h]) > 3
        labels:
          severity: critical
        annotations:
          summary: "Frequent application restarts detected"
          description: "Application has restarted {{ $value }} times in the last hour"
```

## 告警响应流程

### 1. 告警接收

```bash
# 告警接收脚本示例
#!/bin/bash
# alert-handler.sh

# 解析告警数据
severity=$(echo "$ALERT_DATA" | jq -r '.commonLabels.severity')
alertname=$(echo "$ALERT_DATA" | jq -r '.commonLabels.alertname')

echo "Received alert: $alertname (severity: $severity)"

# 根据严重程度分发
case $severity in
    "critical"|"fatal")
        # 立即通知 on-call 工程师
        notify_on_call "$ALERT_DATA"
        ;;
    "warning")
        # 创建工单
        create_ticket "$ALERT_DATA"
        ;;
    "info")
        # 记录日志
        log_alert "$ALERT_DATA"
        ;;
esac
```

### 2. 自动响应

```bash
# auto-remediation.sh

case $alertname in
    "HighConnectionPoolUsage")
        # 自动扩容连接池
        scale_connection_pool
        ;;
    "ApplicationDown")
        # 自动重启应用
        restart_application
        ;;
    "DiskSpaceLow")
        # 自动清理磁盘
        cleanup_disk_space
        ;;
    *)
        echo "No auto-remediation available for $alertname"
        ;;
esac
```

### 3. 人工响应

```bash
# manual-response.sh

# 1. 确认告警
acknowledge_alert "$alert_id"

# 2. 评估影响
assess_impact "$alertname"

# 3. 执行修复
case $alertname in
    "DeadlockDetected")
        # 检查应用日志
        check_application_logs
        # 重启受影响的服务
        restart_affected_services
        ;;
    "SlowLockAcquisition")
        # 检查网络延迟
        check_network_latency
        # 优化连接池配置
        optimize_connection_pool
        ;;
esac

# 4. 验证修复
verify_fix "$alertname"

# 5. 关闭告警
resolve_alert "$alert_id"
```

## 告警仪表板

### 告警概览面板

```json
{
  "dashboard": {
    "title": "Alert Overview",
    "panels": [
      {
        "title": "Active Alerts by Severity",
        "type": "table",
        "targets": [
          {
            "expr": "ALERTS{alertstate='firing'}",
            "legendFormat": "{{ severity }}"
          }
        ]
      },
      {
        "title": "Alert Trends",
        "type": "graph",
        "targets": [
          {
            "expr": "increase(alerts_total[1h])",
            "legendFormat": "Alerts per hour"
          }
        ]
      },
      {
        "title": "Mean Time To Resolution",
        "type": "singlestat",
        "targets": [
          {
            "expr": "avg_over_time(alert_resolution_time_seconds[7d])",
            "legendFormat": "MTTR"
          }
        ]
      }
    ]
  }
}
```

## 告警最佳实践

### 告警设计原则

1. **避免告警疲劳**：只告警真正需要处理的问题
2. **提供上下文**：告警信息要包含足够的问题诊断信息
3. **定义明确的响应**：每个告警都要有明确的处理步骤
4. **分层告警**：不同严重程度有不同的通知渠道
5. **自动恢复**：能自动解决的问题不要产生告警

### 告警维护

1. **定期审查**：每月审查告警规则的有效性
2. **阈值调优**：基于历史数据调整告警阈值
3. **误报处理**：分析和消除误报告警
4. **文档更新**：保持告警处理文档的更新

### 告警监控

```prometheus
# 告警系统自身的监控
alertmanager_alerts{state="active"}  # 活跃告警数
alertmanager_alerts_invalid_total    # 无效告警数
alertmanager_notifications_total     # 发送通知总数
alertmanager_notification_latency_seconds  # 通知延迟
```

## 总结

完善的告警系统包括：

1. **告警规则**：基于指标的智能告警规则
2. **通知渠道**：多渠道的通知系统（邮件、Slack、短信等）
3. **升级策略**：基于时间和频率的告警升级
4. **抑制静默**：避免告警风暴的抑制和静默机制
5. **响应流程**：标准化的告警响应和处理流程
6. **自动修复**：能够自动处理的常见问题的修复脚本

通过完善的告警系统，可以确保问题能够被及时发现和处理，减少系统故障对业务的影响。