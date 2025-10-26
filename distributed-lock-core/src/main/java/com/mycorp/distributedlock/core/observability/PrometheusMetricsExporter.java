package com.mycorp.distributedlock.core.observability;

import com.mycorp.distributedlock.api.PerformanceMetrics;
import com.mycorp.distributedlock.api.PerformanceMetrics.*;

import io.prometheus.client.*;
import io.prometheus.client.exporter.HTTPServer;
import io.prometheus.client.exporter.PushGateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.InetSocketAddress;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Prometheus指标导出器
 * 支持HTTP拉取和Pushgateway推送模式
 */
public class PrometheusMetricsExporter {
    
    private static final Logger logger = LoggerFactory.getLogger(PrometheusMetricsExporter.class);
    
    private final LockPerformanceMetrics performanceMetrics;
    private final MicrometerMetricsAdapter micrometerAdapter;
    private final LockMetricsCollector metricsCollector;
    
    // Prometheus指标定义
    private final Counter lockOperationsTotal;
    private final Counter lockOperationsSuccess;
    private final Counter lockOperationsFailed;
    private final Histogram lockOperationDuration;
    private final Gauge lockCurrentConcurrency;
    private final Gauge lockPeakConcurrency;
    private final Counter lockAcquisitionTimeouts;
    private final Counter lockRenewalCount;
    private final Gauge lockActiveHolders;
    private final Gauge lockQueueLength;
    private final Counter lockContentionEvents;
    private final Counter lockErrorsTotal;
    
    // Prometheus标签
    private static final String LABEL_LOCK_NAME = "lock_name";
    private static final String LABEL_OPERATION = "operation";
    private static final String LABEL_STATUS = "status";
    private static final String LABEL_INSTANCE = "instance";
    private static final String LABEL_APPLICATION = "application";
    
    // 导出配置
    private final PrometheusExporterConfig config;
    
    // HTTP服务器和Pushgateway
    private HTTPServer httpServer;
    private PushGateway pushGateway;
    
    // 导出状态
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final ScheduledExecutorService scheduler;
    
    // 统计信息
    private final AtomicLong totalExports = new AtomicLong(0);
    private final AtomicLong exportErrors = new AtomicLong(0);
    private final AtomicLong lastExportTime = new AtomicLong(0);
    
    public PrometheusMetricsExporter(LockPerformanceMetrics performanceMetrics) {
        this(performanceMetrics, null, null, new PrometheusExporterConfig());
    }
    
    public PrometheusMetricsExporter(LockPerformanceMetrics performanceMetrics,
                                   MicrometerMetricsAdapter micrometerAdapter,
                                   LockMetricsCollector metricsCollector,
                                   PrometheusExporterConfig config) {
        this.performanceMetrics = performanceMetrics;
        this.micrometerAdapter = micrometerAdapter;
        this.metricsCollector = metricsCollector;
        this.config = config != null ? config : new PrometheusExporterConfig();
        
        this.scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread thread = new Thread(r, "prometheus-exporter");
            thread.setDaemon(true);
            return thread;
        });
        
        // 初始化Prometheus指标
        initializePrometheusMetrics();
        
        logger.info("PrometheusMetricsExporter initialized");
    }
    
    private void initializePrometheusMetrics() {
        // 定义Prometheus指标
        this.lockOperationsTotal = Counter.build()
            .name("lock_operations_total")
            .help("Total number of lock operations")
            .labelNames(LABEL_INSTANCE, LABEL_APPLICATION, LABEL_LOCK_NAME, LABEL_OPERATION, LABEL_STATUS)
            .register();
            
        this.lockOperationsSuccess = Counter.build()
            .name("lock_operations_success_total")
            .help("Number of successful lock operations")
            .labelNames(LABEL_INSTANCE, LABEL_APPLICATION, LABEL_LOCK_NAME, LABEL_OPERATION)
            .register();
            
        this.lockOperationsFailed = Counter.build()
            .name("lock_operations_failed_total")
            .help("Number of failed lock operations")
            .labelNames(LABEL_INSTANCE, LABEL_APPLICATION, LABEL_LOCK_NAME, LABEL_OPERATION, LABEL_STATUS)
            .register();
            
        this.lockOperationDuration = Histogram.build()
            .name("lock_operation_duration_seconds")
            .help("Duration of lock operations in seconds")
            .labelNames(LABEL_INSTANCE, LABEL_APPLICATION, LABEL_LOCK_NAME, LABEL_OPERATION, LABEL_STATUS)
            .buckets(0.001, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0)
            .register();
            
        this.lockCurrentConcurrency = Gauge.build()
            .name("lock_current_concurrency")
            .help("Current number of concurrent lock operations")
            .labelNames(LABEL_INSTANCE, LABEL_APPLICATION)
            .register();
            
        this.lockPeakConcurrency = Gauge.build()
            .name("lock_peak_concurrency")
            .help("Peak number of concurrent lock operations")
            .labelNames(LABEL_INSTANCE, LABEL_APPLICATION)
            .register();
            
        this.lockAcquisitionTimeouts = Counter.build()
            .name("lock_acquisition_timeouts_total")
            .help("Number of lock acquisition timeouts")
            .labelNames(LABEL_INSTANCE, LABEL_APPLICATION, LABEL_LOCK_NAME)
            .register();
            
        this.lockRenewalCount = Counter.build()
            .name("lock_renewals_total")
            .help("Number of lock renewals")
            .labelNames(LABEL_INSTANCE, LABEL_APPLICATION, LABEL_LOCK_NAME)
            .register();
            
        this.lockActiveHolders = Gauge.build()
            .name("lock_active_holders")
            .help("Number of active lock holders")
            .labelNames(LABEL_INSTANCE, LABEL_APPLICATION, LABEL_LOCK_NAME)
            .register();
            
        this.lockQueueLength = Gauge.build()
            .name("lock_queue_length")
            .help("Current lock queue length")
            .labelNames(LABEL_INSTANCE, LABEL_APPLICATION, LABEL_LOCK_NAME)
            .register();
            
        this.lockContentionEvents = Counter.build()
            .name("lock_contention_events_total")
            .help("Number of lock contention events")
            .labelNames(LABEL_INSTANCE, LABEL_APPLICATION, LABEL_LOCK_NAME)
            .register();
            
        this.lockErrorsTotal = Counter.build()
            .name("lock_errors_total")
            .help("Total number of lock errors")
            .labelNames(LABEL_INSTANCE, LABEL_APPLICATION, LABEL_LOCK_NAME, LABEL_STATUS)
            .register();
    }
    
    /**
     * 启动HTTP拉取服务
     */
    public void startHttpServer(int port) throws IOException {
        if (isRunning.get()) {
            logger.warn("HTTP server is already running");
            return;
        }
        
        this.httpServer = new HTTPServer(port);
        isRunning.set(true);
        
        // 启动指标收集任务
        startMetricsCollection();
        
        logger.info("Prometheus HTTP server started on port {}", port);
    }
    
    /**
     * 配置Pushgateway推送
     */
    public void configurePushGateway(String pushGatewayUrl, String jobName) {
        if (pushGatewayUrl == null || jobName == null) {
            throw new IllegalArgumentException("PushGateway URL and job name cannot be null");
        }
        
        try {
            this.pushGateway = new PushGateway(pushGatewayUrl);
            this.config.setPushGatewayJob(jobName);
            logger.info("PushGateway configured: {}", pushGatewayUrl);
        } catch (Exception e) {
            logger.error("Failed to configure PushGateway", e);
            throw new RuntimeException("PushGateway configuration failed", e);
        }
    }
    
    /**
     * 推送到PushGateway
     */
    public void pushToGateway(String jobName, Map<String, String> groupingKey) {
        if (pushGateway == null) {
            logger.warn("PushGateway is not configured");
            return;
        }
        
        try {
            if (jobName != null) {
                this.config.setPushGatewayJob(jobName);
            }
            
            pushGateway.pushAdd(CollectorRegistry.defaultRegistry, 
                              this.config.getPushGatewayJob(), groupingKey);
            
            totalExports.incrementAndGet();
            lastExportTime.set(System.currentTimeMillis());
            
            logger.debug("Metrics pushed to PushGateway");
        } catch (Exception e) {
            exportErrors.incrementAndGet();
            logger.error("Failed to push metrics to PushGateway", e);
        }
    }
    
    /**
     * 拉取模式：导出指标到字符串
     */
    public String exportMetrics() {
        try {
            // 更新Prometheus指标
            updatePrometheusMetrics();
            
            // 生成Prometheus格式文本
            StringWriter writer = new StringWriter();
            io.prometheus.client.exporter.TextFormat.write004(writer, CollectorRegistry.defaultRegistry);
            String metrics = writer.toString();
            
            totalExports.incrementAndGet();
            lastExportTime.set(System.currentTimeMillis());
            
            logger.debug("Metrics exported successfully, size: {} bytes", metrics.length());
            return metrics;
            
        } catch (Exception e) {
            exportErrors.incrementAndGet();
            logger.error("Failed to export metrics", e);
            return "";
        }
    }
    
    /**
     * 批量导出数据
     */
    public String exportData(List<String> lockNames, Duration timeRange, ExportFormat format) {
        if (format == ExportFormat.CSV) {
            return exportToCsv(lockNames, timeRange);
        } else if (format == ExportFormat.JSON) {
            return exportToJson(lockNames, timeRange);
        } else {
            // 默认导出Prometheus格式
            return exportMetrics();
        }
    }
    
    /**
     * 获取导出统计信息
     */
    public ExportStats getExportStats() {
        return new ExportStatsImpl();
    }
    
    /**
     * 停止导出服务
     */
    public void stop() {
        if (httpServer != null) {
            httpServer.stop();
            logger.info("Prometheus HTTP server stopped");
        }
        
        if (isRunning.compareAndSet(true, false)) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(30, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        
        logger.info("PrometheusMetricsExporter stopped");
    }
    
    // 私有方法
    
    private void updatePrometheusMetrics() {
        try {
            // 获取系统指标
            SystemPerformanceMetrics systemMetrics = performanceMetrics.getSystemMetrics();
            
            // 更新系统级指标
            String instance = config.getInstanceId();
            String application = config.getApplicationName();
            
            lockCurrentConcurrency.labels(instance, application)
                .set(systemMetrics.getCurrentConcurrency());
                
            lockPeakConcurrency.labels(instance, application)
                .set(systemMetrics.getPeakConcurrency());
            
            // 获取锁级指标
            if (config.getExportLockMetrics()) {
                Map<String, LockPerformanceMetrics> lockMetrics = 
                    performanceMetrics.getMultipleLockMetrics(lockNames);
                    
                for (Map.Entry<String, LockPerformanceMetrics> entry : lockMetrics.entrySet()) {
                    String lockName = entry.getKey();
                    LockPerformanceMetrics metrics = entry.getValue();
                    
                    // 这里需要根据实际的锁指标更新Prometheus指标
                    // 由于LockPerformanceMetrics是接口，这里使用示例值
                    lockActiveHolders.labels(instance, application, lockName)
                        .set(metrics.getActiveLockCount());
                        
                    // 队列长度需要从锁的内部状态获取
                    // lockQueueLength.labels(instance, application, lockName)
                    //     .set(currentQueueLength);
                }
            }
            
        } catch (Exception e) {
            logger.error("Error updating Prometheus metrics", e);
        }
    }
    
    private void startMetricsCollection() {
        // 定期更新指标
        scheduler.scheduleAtFixedRate(() -> {
            try {
                updatePrometheusMetrics();
                
                // 如果配置了自动推送，推送到PushGateway
                if (pushGateway != null && config.isAutoPushEnabled()) {
                    pushToGateway(null, config.getPushGatewayGroupingKey());
                }
                
            } catch (Exception e) {
                logger.error("Error in metrics collection task", e);
            }
        }, config.getMetricsCollectionInterval().toSeconds(),
           config.getMetricsCollectionInterval().toSeconds(),
           TimeUnit.SECONDS);
    }
    
    private String exportToCsv(List<String> lockNames, Duration timeRange) {
        StringBuilder csv = new StringBuilder();
        csv.append("timestamp,lock_name,operation,duration_ms,status\n");
        
        List<LockMetricsCollector.LockMetricRecord> metrics = metricsCollector != null ?
            metricsCollector.queryMetrics(timeRange) : Collections.emptyList();
            
        for (LockMetricsCollector.LockMetricRecord record : metrics) {
            if (lockNames.isEmpty() || lockNames.contains(record.lockName)) {
                csv.append(record.timestamp.toEpochMilli())
                   .append(",").append(record.lockName)
                   .append(",").append(record.operation)
                   .append(",").append(record.duration.toMillis())
                   .append(",").append(record.success ? "success" : "failure")
                   .append("\n");
            }
        }
        
        return csv.toString();
    }
    
    private String exportToJson(List<String> lockNames, Duration timeRange) {
        StringBuilder json = new StringBuilder();
        json.append("{\"metrics\":[");
        
        List<LockMetricsCollector.LockMetricRecord> metrics = metricsCollector != null ?
            metricsCollector.queryMetrics(timeRange) : Collections.emptyList();
            
        boolean first = true;
        for (LockMetricsCollector.LockMetricRecord record : metrics) {
            if (lockNames.isEmpty() || lockNames.contains(record.lockName)) {
                if (!first) json.append(",");
                first = false;
                
                json.append("{\"timestamp\":").append(record.timestamp.toEpochMilli())
                   .append(",\"lock_name\":\"").append(record.lockName).append("\"")
                   .append(",\"operation\":\"").append(record.operation).append("\"")
                   .append(",\"duration_ms\":").append(record.duration.toMillis())
                   .append(",\"status\":\"").append(record.success ? "success" : "failure").append("\"")
                   .append("}");
            }
        }
        
        json.append("]}");
        return json.toString();
    }
    
    private List<String> getAllLockNames() {
        if (micrometerAdapter != null) {
            return new ArrayList<>(micrometerAdapter.getAllLockMetrics().keySet());
        }
        return Collections.emptyList();
    }
    
    // 配置类
    
    public static class PrometheusExporterConfig {
        private String applicationName = "distributed-lock";
        private String instanceId = "default";
        private String pushGatewayUrl;
        private String pushGatewayJob;
        private Map<String, String> pushGatewayGroupingKey = new HashMap<>();
        private boolean exportLockMetrics = true;
        private Duration metricsCollectionInterval = Duration.ofSeconds(30);
        private boolean autoPushEnabled = false;
        private Duration exportRetentionDuration = Duration.ofDays(7);
        
        // Getters and setters
        public String getApplicationName() { return applicationName; }
        public void setApplicationName(String applicationName) { this.applicationName = applicationName; }
        
        public String getInstanceId() { return instanceId; }
        public void setInstanceId(String instanceId) { this.instanceId = instanceId; }
        
        public String getPushGatewayUrl() { return pushGatewayUrl; }
        public void setPushGatewayUrl(String pushGatewayUrl) { this.pushGatewayUrl = pushGatewayUrl; }
        
        public String getPushGatewayJob() { return pushGatewayJob; }
        public void setPushGatewayJob(String pushGatewayJob) { this.pushGatewayJob = pushGatewayJob; }
        
        public Map<String, String> getPushGatewayGroupingKey() { return pushGatewayGroupingKey; }
        public void setPushGatewayGroupingKey(Map<String, String> pushGatewayGroupingKey) { 
            this.pushGatewayGroupingKey = pushGatewayGroupingKey; 
        }
        
        public boolean isExportLockMetrics() { return exportLockMetrics; }
        public void setExportLockMetrics(boolean exportLockMetrics) { 
            this.exportLockMetrics = exportLockMetrics; 
        }
        
        public Duration getMetricsCollectionInterval() { return metricsCollectionInterval; }
        public void setMetricsCollectionInterval(Duration metricsCollectionInterval) { 
            this.metricsCollectionInterval = metricsCollectionInterval; 
        }
        
        public boolean isAutoPushEnabled() { return autoPushEnabled; }
        public void setAutoPushEnabled(boolean autoPushEnabled) { 
            this.autoPushEnabled = autoPushEnabled; 
        }
        
        public Duration getExportRetentionDuration() { return exportRetentionDuration; }
        public void setExportRetentionDuration(Duration exportRetentionDuration) { 
            this.exportRetentionDuration = exportRetentionDuration; 
        }
    }
    
    /**
     * 导出统计信息
     */
    public interface ExportStats {
        long getTotalExports();
        long getExportErrors();
        long getLastExportTime();
        Duration getUptime();
        double getSuccessRate();
        Map<String, Long> getExportByFormat();
    }
    
    private class ExportStatsImpl implements ExportStats {
        @Override
        public long getTotalExports() {
            return totalExports.get();
        }
        
        @Override
        public long getExportErrors() {
            return exportErrors.get();
        }
        
        @Override
        public long getLastExportTime() {
            return lastExportTime.get();
        }
        
        @Override
        public Duration getUptime() {
            // 这里需要启动时间
            return Duration.ofMinutes(60);
        }
        
        @Override
        public double getSuccessRate() {
            long total = totalExports.get();
            long errors = exportErrors.get();
            return total > 0 ? (double) (total - errors) / total : 1.0;
        }
        
        @Override
        public Map<String, Long> getExportByFormat() {
            Map<String, Long> stats = new HashMap<>();
            stats.put("prometheus", totalExports.get());
            stats.put("json", 0L);
            stats.put("csv", 0L);
            return stats;
        }
    }
}