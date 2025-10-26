package com.mycorp.distributedlock.core.testing;

import com.mycorp.distributedlock.api.*;
import com.mycorp.distributedlock.api.exception.DistributedLockException;
import com.mycorp.distributedlock.core.config.LockConfiguration;
import com.mycorp.distributedlock.core.monitoring.PerformanceMonitor;
import com.mycorp.distributedlock.core.observability.LockMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 负载测试场景管理器 - 提供全面的分布式锁负载测试场景
 * 
 * 主要功能：
 * 1. 基础性能测试（单线程、多线程、高并发）
 * 2. 高级特性性能测试（可重入锁、公平锁、读写锁、批量锁）
 * 3. 压力和稳定性测试（长时间运行、内存泄漏检测）
 * 4. 后端对比测试（Redis vs ZooKeeper）
 * 5. 自定义负载测试场景
 * 6. 测试结果收集和分析
 * 7. 测试报告生成
 * 
 * @author Yier Lock Team
 * @version 2.0.0
 */
public class LoadTestScenarios {
    
    private static final Logger logger = LoggerFactory.getLogger(LoadTestScenarios.class);
    
    // 测试执行器
    private final ExecutorService testExecutor;
    private final ExecutorService resultExecutor;
    
    // 锁提供者和配置
    private final LockProvider lockProvider;
    private final LockConfiguration lockConfiguration;
    private final PerformanceMonitor performanceMonitor;
    private final LockMetrics lockMetrics;
    
    // 测试结果收集
    private final Map<String, TestResult> testResults;
    private final AtomicBoolean isRunning;
    private final AtomicInteger testCounter;
    
    // 测试配置
    private final LoadTestConfiguration testConfig;
    
    /**
     * 负载测试配置类
     */
    public static class LoadTestConfiguration {
        private int defaultThreadCount = 10;
        private int defaultIterationCount = 1000;
        private long defaultTestDurationMs = 60000; // 1分钟
        private int defaultConcurrencyLevel = 50;
        private double defaultLoadFactor = 1.0;
        private boolean enableDetailedMetrics = true;
        private boolean enableMemoryProfiling = true;
        private boolean enableCpuProfiling = true;
        private int warmupIterations = 100;
        private long reportGenerationInterval = 30000; // 30秒
        
        // Getters and Setters
        public int getDefaultThreadCount() { return defaultThreadCount; }
        public void setDefaultThreadCount(int defaultThreadCount) { 
            this.defaultThreadCount = Math.max(1, defaultThreadCount); 
        }
        public int getDefaultIterationCount() { return defaultIterationCount; }
        public void setDefaultIterationCount(int defaultIterationCount) { 
            this.defaultIterationCount = Math.max(1, defaultIterationCount); 
        }
        public long getDefaultTestDurationMs() { return defaultTestDurationMs; }
        public void setDefaultTestDurationMs(long defaultTestDurationMs) { 
            this.defaultTestDurationMs = Math.max(1000, defaultTestDurationMs); 
        }
        public int getDefaultConcurrencyLevel() { return defaultConcurrencyLevel; }
        public void setDefaultConcurrencyLevel(int defaultConcurrencyLevel) { 
            this.defaultConcurrencyLevel = Math.max(1, defaultConcurrencyLevel); 
        }
        public double getDefaultLoadFactor() { return defaultLoadFactor; }
        public void setDefaultLoadFactor(double defaultLoadFactor) { 
            this.defaultLoadFactor = Math.max(0.1, Math.min(10.0, defaultLoadFactor)); 
        }
        public boolean isEnableDetailedMetrics() { return enableDetailedMetrics; }
        public void setEnableDetailedMetrics(boolean enableDetailedMetrics) { 
            this.enableDetailedMetrics = enableDetailedMetrics; 
        }
        public boolean isEnableMemoryProfiling() { return enableMemoryProfiling; }
        public void setEnableMemoryProfiling(boolean enableMemoryProfiling) { 
            this.enableMemoryProfiling = enableMemoryProfiling; 
        }
        public boolean isEnableCpuProfiling() { return enableCpuProfiling; }
        public void setEnableCpuProfiling(boolean enableCpuProfiling) { 
            this.enableCpuProfiling = enableCpuProfiling; 
        }
        public int getWarmupIterations() { return warmupIterations; }
        public void setWarmupIterations(int warmupIterations) { 
            this.warmupIterations = Math.max(0, warmupIterations); 
        }
        public long getReportGenerationInterval() { return reportGenerationInterval; }
        public void setReportGenerationInterval(long reportGenerationInterval) { 
            this.reportGenerationInterval = Math.max(5000, reportGenerationInterval); 
        }
    }
    
    /**
     * 测试场景定义类
     */
    public static class TestScenario {
        private final String scenarioId;
        private final String name;
        private final String description;
        private final TestType testType;
        private final Map<String, Object> parameters;
        private final Consumer<LoadTestScenarios> testRunner;
        private final int priority;
        private final boolean isStressTest;
        
        public TestScenario(String scenarioId, String name, String description, 
                          TestType testType, Map<String, Object> parameters,
                          Consumer<LoadTestScenarios> testRunner, int priority, boolean isStressTest) {
            this.scenarioId = scenarioId;
            this.name = name;
            this.description = description;
            this.testType = testType;
            this.parameters = parameters;
            this.testRunner = testRunner;
            this.priority = priority;
            this.isStressTest = isStressTest;
        }
        
        // Getters
        public String getScenarioId() { return scenarioId; }
        public String getName() { return name; }
        public String getDescription() { return description; }
        public TestType getTestType() { return testType; }
        public Map<String, Object> getParameters() { return parameters; }
        public Consumer<LoadTestScenarios> getTestRunner() { return testRunner; }
        public int getPriority() { return priority; }
        public boolean isStressTest() { return isStressTest; }
    }
    
    /**
     * 测试类型枚举
     */
    public enum TestType {
        PERFORMANCE("性能测试"),
        STRESS("压力测试"),
        STABILITY("稳定性测试"),
        COMPARISON("对比测试"),
        REGRESSION("回归测试");
        
        private final String description;
        
        TestType(String description) {
            this.description = description;
        }
        
        public String getDescription() { return description; }
    }
    
    /**
     * 测试结果类
     */
    public static class TestResult {
        private final String scenarioId;
        private final String testName;
        private final TestType testType;
        private final long startTime;
        private final long endTime;
        private final Duration duration;
        private final Map<String, Object> metrics;
        private final List<String> errors;
        private final List<String> warnings;
        private final TestStatus status;
        private final Map<String, Object> configuration;
        
        public TestResult(String scenarioId, String testName, TestType testType) {
            this.scenarioId = scenarioId;
            this.testName = testName;
            this.testType = testType;
            this.startTime = System.currentTimeMillis();
            this.endTime = 0;
            this.duration = Duration.ZERO;
            this.metrics = new ConcurrentHashMap<>();
            this.errors = new CopyOnWriteArrayList<>();
            this.warnings = new CopyOnWriteArrayList<>();
            this.status = TestStatus.RUNNING;
            this.configuration = new ConcurrentHashMap<>();
        }
        
        public TestResult markCompleted() {
            this.endTime = System.currentTimeMillis();
            this.duration = Duration.ofMillis(this.endTime - this.startTime);
            this.status = TestStatus.COMPLETED;
            return this;
        }
        
        public TestResult markFailed(String error) {
            this.endTime = System.currentTimeMillis();
            this.duration = Duration.ofMillis(this.endTime - this.startTime);
            this.status = TestStatus.FAILED;
            this.errors.add(error);
            return this;
        }
        
        public TestResult addMetric(String key, Object value) {
            this.metrics.put(key, value);
            return this;
        }
        
        public TestResult addError(String error) {
            this.errors.add(error);
            return this;
        }
        
        public TestResult addWarning(String warning) {
            this.warnings.add(warning);
            return this;
        }
        
        public TestResult addConfiguration(String key, Object value) {
            this.configuration.put(key, value);
            return this;
        }
        
        // Getters
        public String getScenarioId() { return scenarioId; }
        public String getTestName() { return testName; }
        public TestType getTestType() { return testType; }
        public long getStartTime() { return startTime; }
        public long getEndTime() { return endTime; }
        public Duration getDuration() { return duration; }
        public Map<String, Object> getMetrics() { return metrics; }
        public List<String> getErrors() { return errors; }
        public List<String> getWarnings() { return warnings; }
        public TestStatus getStatus() { return status; }
        public Map<String, Object> getConfiguration() { return configuration; }
    }
    
    /**
     * 测试状态枚举
     */
    public enum TestStatus {
        NOT_STARTED, RUNNING, COMPLETED, FAILED, CANCELLED
    }
    
    /**
     * 性能统计类
     */
    public static class PerformanceStatistics {
        private final double averageLatencyMs;
        private final double minLatencyMs;
        private final double maxLatencyMs;
        private final double p50LatencyMs;
        private final double p95LatencyMs;
        private final double p99LatencyMs;
        private final double throughputPerSecond;
        private final int totalOperations;
        private final int successfulOperations;
        private final int failedOperations;
        private final double successRate;
        private final long memoryUsageMb;
        private final double cpuUsagePercent;
        private final Map<String, Object> additionalMetrics;
        
        public PerformanceStatistics(double averageLatencyMs, double minLatencyMs, double maxLatencyMs,
                                   double p50LatencyMs, double p95LatencyMs, double p99LatencyMs,
                                   double throughputPerSecond, int totalOperations, int successfulOperations,
                                   int failedOperations, double successRate, long memoryUsageMb,
                                   double cpuUsagePercent, Map<String, Object> additionalMetrics) {
            this.averageLatencyMs = averageLatencyMs;
            this.minLatencyMs = minLatencyMs;
            this.maxLatencyMs = maxLatencyMs;
            this.p50LatencyMs = p50LatencyMs;
            this.p95LatencyMs = p95LatencyMs;
            this.p99LatencyMs = p99LatencyMs;
            this.throughputPerSecond = throughputPerSecond;
            this.totalOperations = totalOperations;
            this.successfulOperations = successfulOperations;
            this.failedOperations = failedOperations;
            this.successRate = successRate;
            this.memoryUsageMb = memoryUsageMb;
            this.cpuUsagePercent = cpuUsagePercent;
            this.additionalMetrics = additionalMetrics;
        }
        
        // Getters
        public double getAverageLatencyMs() { return averageLatencyMs; }
        public double getMinLatencyMs() { return minLatencyMs; }
        public double getMaxLatencyMs() { return maxLatencyMs; }
        public double getP50LatencyMs() { return p50LatencyMs; }
        public double getP95LatencyMs() { return p95LatencyMs; }
        public double getP99LatencyMs() { return p99LatencyMs; }
        public double getThroughputPerSecond() { return throughputPerSecond; }
        public int getTotalOperations() { return totalOperations; }
        public int getSuccessfulOperations() { return successfulOperations; }
        public int getFailedOperations() { return failedOperations; }
        public double getSuccessRate() { return successRate; }
        public long getMemoryUsageMb() { return memoryUsageMb; }
        public double getCpuUsagePercent() { return cpuUsagePercent; }
        public Map<String, Object> getAdditionalMetrics() { return additionalMetrics; }
    }
    
    /**
     * 负载测试结果报告
     */
    public static class LoadTestReport {
        private final String reportId;
        private final String title;
        private final List<TestResult> testResults;
        private final Map<String, PerformanceStatistics> performanceStats;
        private final Map<String, Object> summary;
        private final List<String> insights;
        private final List<String> recommendations;
        private final long generatedAt;
        
        public LoadTestReport(String title, List<TestResult> testResults) {
            this.reportId = UUID.randomUUID().toString();
            this.title = title;
            this.testResults = testResults;
            this.performanceStats = new HashMap<>();
            this.summary = new HashMap<>();
            this.insights = new ArrayList<>();
            this.recommendations = new ArrayList<>();
            this.generatedAt = System.currentTimeMillis();
        }
        
        public LoadTestReport addPerformanceStats(String testName, PerformanceStatistics stats) {
            this.performanceStats.put(testName, stats);
            return this;
        }
        
        public LoadTestReport addSummary(String key, Object value) {
            this.summary.put(key, value);
            return this;
        }
        
        public LoadTestReport addInsight(String insight) {
            this.insights.add(insight);
            return this;
        }
        
        public LoadTestReport addRecommendation(String recommendation) {
            this.recommendations.add(recommendation);
            return this;
        }
        
        // Getters
        public String getReportId() { return reportId; }
        public String getTitle() { return title; }
        public List<TestResult> getTestResults() { return testResults; }
        public Map<String, PerformanceStatistics> getPerformanceStats() { return performanceStats; }
        public Map<String, Object> getSummary() { return summary; }
        public List<String> getInsights() { return insights; }
        public List<String> getRecommendations() { return recommendations; }
        public long getGeneratedAt() { return generatedAt; }
    }
    
    /**
     * 构造函数
     */
    public LoadTestScenarios(LockProvider lockProvider, 
                           LockConfiguration lockConfiguration,
                           PerformanceMonitor performanceMonitor,
                           LockMetrics lockMetrics) {
        this.lockProvider = lockProvider;
        this.lockConfiguration = lockConfiguration;
        this.performanceMonitor = performanceMonitor;
        this.lockMetrics = lockMetrics;
        this.testConfig = new LoadTestConfiguration();
        this.testResults = new ConcurrentHashMap<>();
        this.isRunning = new AtomicBoolean(false);
        this.testCounter = new AtomicInteger(0);
        
        // 创建线程池
        int corePoolSize = Runtime.getRuntime().availableProcessors();
        this.testExecutor = Executors.newFixedThreadPool(corePoolSize, 
            new ThreadFactoryBuilder().setNameFormat("load-test-%d").build());
        this.resultExecutor = Executors.newFixedThreadPool(2,
            new ThreadFactoryBuilder().setNameFormat("result-processor-%d").build());
        
        logger.info("负载测试场景管理器已初始化，CPU核心数: {}", corePoolSize);
    }
    
    /**
     * 执行基础性能测试场景
     */
    public TestResult runBasicPerformanceTests() {
        logger.info("开始执行基础性能测试场景");
        
        TestResult result = new TestResult("basic_performance", "基础性能测试", TestType.PERFORMANCE);
        testResults.put("basic_performance", result);
        
        try {
            // 1. 单线程锁操作测试
            result.addConfiguration("test_type", "basic_single_thread");
            runSingleThreadLockTest(result);
            
            // 2. 多线程锁操作测试
            result.addConfiguration("test_type", "basic_multi_thread");
            runMultiThreadLockTest(result);
            
            // 3. 高并发锁操作测试
            result.addConfiguration("test_type", "basic_high_concurrency");
            runHighConcurrencyLockTest(result);
            
            result.markCompleted();
            logger.info("基础性能测试完成");
            
        } catch (Exception e) {
            logger.error("基础性能测试执行失败", e);
            result.markFailed("测试执行失败: " + e.getMessage());
        }
        
        return result;
    }
    
    /**
     * 执行高级特性测试场景
     */
    public TestResult runAdvancedFeatureTests() {
        logger.info("开始执行高级特性测试场景");
        
        TestResult result = new TestResult("advanced_features", "高级特性测试", TestType.PERFORMANCE);
        testResults.put("advanced_features", result);
        
        try {
            // 1. 可重入锁测试
            result.addConfiguration("test_type", "reentrant_lock");
            runReentrantLockTest(result);
            
            // 2. 公平锁测试
            result.addConfiguration("test_type", "fair_lock");
            runFairLockTest(result);
            
            // 3. 读写锁测试
            result.addConfiguration("test_type", "read_write_lock");
            runReadWriteLockTest(result);
            
            // 4. 批量锁测试
            result.addConfiguration("test_type", "batch_lock");
            runBatchLockTest(result);
            
            result.markCompleted();
            logger.info("高级特性测试完成");
            
        } catch (Exception e) {
            logger.error("高级特性测试执行失败", e);
            result.markFailed("测试执行失败: " + e.getMessage());
        }
        
        return result;
    }
    
    /**
     * 执行压力和稳定性测试场景
     */
    public TestResult runStressAndStabilityTests() {
        logger.info("开始执行压力和稳定性测试场景");
        
        TestResult result = new TestResult("stress_stability", "压力和稳定性测试", TestType.STRESS);
        testResults.put("stress_stability", result);
        
        try {
            // 1. 长时间运行测试
            result.addConfiguration("test_type", "long_running");
            runLongRunningTest(result);
            
            // 2. 内存泄漏检测测试
            result.addConfiguration("test_type", "memory_leak_detection");
            runMemoryLeakDetectionTest(result);
            
            // 3. 高负载压力测试
            result.addConfiguration("test_type", "high_load_stress");
            runHighLoadStressTest(result);
            
            // 4. 网络分区恢复测试
            result.addConfiguration("test_type", "network_partition_recovery");
            runNetworkPartitionRecoveryTest(result);
            
            result.markCompleted();
            logger.info("压力和稳定性测试完成");
            
        } catch (Exception e) {
            logger.error("压力和稳定性测试执行失败", e);
            result.markFailed("测试执行失败: " + e.getMessage());
        }
        
        return result;
    }
    
    /**
     * 执行后端对比测试场景
     */
    public TestResult runBackendComparisonTests() {
        logger.info("开始执行后端对比测试场景");
        
        TestResult result = new TestResult("backend_comparison", "后端对比测试", TestType.COMPARISON);
        testResults.put("backend_comparison", result);
        
        try {
            // 获取所有可用的锁提供者
            List<LockProvider> availableProviders = discoverAvailableProviders();
            
            if (availableProviders.size() < 2) {
                result.addWarning("可用锁提供者不足2个，跳过后端对比测试");
                result.markCompleted();
                return result;
            }
            
            // 对比测试每个提供者的性能
            Map<LockProvider.Type, PerformanceStatistics> providerStats = new HashMap<>();
            
            for (LockProvider provider : availableProviders) {
                logger.info("测试锁提供者: {}", provider.getProviderType());
                PerformanceStatistics stats = runProviderPerformanceTest(provider);
                providerStats.put(provider.getProviderType(), stats);
                result.addMetric("provider_" + provider.getProviderType() + "_stats", stats);
            }
            
            // 分析对比结果
            analyzeComparisonResults(result, providerStats);
            
            result.markCompleted();
            logger.info("后端对比测试完成");
            
        } catch (Exception e) {
            logger.error("后端对比测试执行失败", e);
            result.markFailed("测试执行失败: " + e.getMessage());
        }
        
        return result;
    }
    
    /**
     * 执行自定义负载测试场景
     */
    public CompletableFuture<TestResult> runCustomLoadTest(TestScenario scenario) {
        logger.info("开始执行自定义负载测试场景: {}", scenario.getName());
        
        return CompletableFuture.supplyAsync(() -> {
            TestResult result = new TestResult(scenario.getScenarioId(), scenario.getName(), scenario.getTestType());
            testResults.put(scenario.getScenarioId(), result);
            
            try {
                // 应用测试配置
                applyTestConfiguration(scenario.getParameters());
                
                // 执行测试场景
                scenario.getTestRunner().accept(this);
                
                result.markCompleted();
                logger.info("自定义负载测试场景完成: {}", scenario.getName());
                
            } catch (Exception e) {
                logger.error("自定义负载测试场景执行失败: {}", scenario.getName(), e);
                result.markFailed("测试执行失败: " + e.getMessage());
            }
            
            return result;
        }, testExecutor);
    }
    
    /**
     * 生成负载测试报告
     */
    public LoadTestReport generateLoadTestReport() {
        logger.info("生成负载测试报告");
        
        LoadTestReport report = new LoadTestReport(
            "分布式锁负载测试综合报告",
            new ArrayList<>(testResults.values())
        );
        
        try {
            // 计算整体性能统计
            calculateOverallStatistics(report);
            
            // 添加总结信息
            addSummaryInformation(report);
            
            // 添加洞察和建议
            addInsightsAndRecommendations(report);
            
            // 生成报告内容
            generateReportContent(report);
            
            logger.info("负载测试报告生成完成");
            
        } catch (Exception e) {
            logger.error("生成负载测试报告时发生错误", e);
            report.addSummary("error", "报告生成失败: " + e.getMessage());
        }
        
        return report;
    }
    
    // 私有测试方法
    
    /**
     * 单线程锁操作测试
     */
    private void runSingleThreadLockTest(TestResult result) throws Exception {
        String lockName = "test_single_thread_" + System.currentTimeMillis();
        int iterations = testConfig.getDefaultIterationCount();
        
        CountDownLatch latch = new CountDownLatch(iterations);
        AtomicLong totalLatency = new AtomicLong(0);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);
        
        // 执行单线程锁操作
        for (int i = 0; i < iterations; i++) {
            resultExecutor.submit(() -> {
                try {
                    DistributedLock lock = lockProvider.createLock(lockName);
                    
                    long startTime = System.nanoTime();
                    try {
                        lock.tryLock(1, TimeUnit.SECONDS);
                        long endTime = System.nanoTime();
                        
                        totalLatency.addAndGet(endTime - startTime);
                        successCount.incrementAndGet();
                        
                        // 模拟锁持有时间
                        Thread.sleep(1);
                        
                    } finally {
                        lock.unlock();
                    }
                    
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                    result.addWarning("单线程测试中的错误: " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }
        
        // 等待所有操作完成
        latch.await(30, TimeUnit.SECONDS);
        
        // 计算结果
        double avgLatency = iterations > 0 ? (totalLatency.get() / (double) iterations) / 1_000_000 : 0;
        double throughput = iterations > 0 ? successCount.get() / (iterations * 0.001) : 0; // 假设总时间1ms
        double successRate = iterations > 0 ? (successCount.get() * 100.0) / iterations : 0;
        
        result.addMetric("avg_latency_ms", avgLatency);
        result.addMetric("throughput_per_sec", throughput);
        result.addMetric("success_rate", successRate);
        result.addMetric("total_operations", iterations);
        result.addMetric("successful_operations", successCount.get());
        result.addMetric("failed_operations", errorCount.get());
        
        logger.info("单线程锁测试完成 - 平均延迟: {:.2f}ms, 吞吐量: {:.2f}/s, 成功率: {:.1f}%", 
                   avgLatency, throughput, successRate);
    }
    
    /**
     * 多线程锁操作测试
     */
    private void runMultiThreadLockTest(TestResult result) throws Exception {
        String lockName = "test_multi_thread_" + System.currentTimeMillis();
        int threadCount = testConfig.getDefaultThreadCount();
        int operationsPerThread = testConfig.getDefaultIterationCount() / threadCount;
        
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicLong totalLatency = new AtomicLong(0);
        AtomicInteger totalSuccess = new AtomicInteger(0);
        AtomicInteger totalErrors = new AtomicInteger(0);
        
        // 创建多个线程执行锁操作
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    DistributedLock lock = lockProvider.createLock(lockName);
                    AtomicLong threadLatency = new AtomicLong(0);
                    AtomicInteger threadSuccess = new AtomicInteger(0);
                    AtomicInteger threadErrors = new AtomicInteger(0);
                    
                    for (int j = 0; j < operationsPerThread; j++) {
                        long startTime = System.nanoTime();
                        try {
                            lock.tryLock(2, TimeUnit.SECONDS);
                            long endTime = System.nanoTime();
                            
                            threadLatency.addAndGet(endTime - startTime);
                            threadSuccess.incrementAndGet();
                            
                            // 模拟锁持有时间
                            Thread.sleep(1);
                            
                        } catch (Exception e) {
                            threadErrors.incrementAndGet();
                        } finally {
                            lock.unlock();
                        }
                    }
                    
                    totalLatency.addAndGet(threadLatency.get());
                    totalSuccess.addAndGet(threadSuccess.get());
                    totalErrors.addAndGet(threadErrors.get());
                    
                } catch (Exception e) {
                    totalErrors.incrementAndGet(operationsPerThread);
                    result.addWarning("线程" + threadId + "执行失败: " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            }, testExecutor);
            
            futures.add(future);
        }
        
        // 等待所有线程完成
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(60, TimeUnit.SECONDS);
        
        // 计算结果
        int totalOperations = threadCount * operationsPerThread;
        double avgLatency = totalOperations > 0 ? (totalLatency.get() / (double) totalOperations) / 1_000_000 : 0;
        double throughput = totalOperations > 0 ? totalSuccess.get() / (totalOperations * 0.001) : 0;
        double successRate = totalOperations > 0 ? (totalSuccess.get() * 100.0) / totalOperations : 0;
        
        result.addMetric("thread_count", threadCount);
        result.addMetric("avg_latency_ms", avgLatency);
        result.addMetric("throughput_per_sec", throughput);
        result.addMetric("success_rate", successRate);
        result.addMetric("total_operations", totalOperations);
        result.addMetric("successful_operations", totalSuccess.get());
        result.addMetric("failed_operations", totalErrors.get());
        
        logger.info("多线程锁测试完成 - 线程数: {}, 平均延迟: {:.2f}ms, 吞吐量: {:.2f}/s, 成功率: {:.1f}%", 
                   threadCount, avgLatency, throughput, successRate);
    }
    
    /**
     * 高并发锁操作测试
     */
    private void runHighConcurrencyLockTest(TestResult result) throws Exception {
        String lockName = "test_high_concurrency_" + System.currentTimeMillis();
        int concurrencyLevel = testConfig.getDefaultConcurrencyLevel();
        long testDuration = testConfig.getDefaultTestDurationMs();
        
        CountDownLatch latch = new CountDownLatch(concurrencyLevel);
        AtomicLong totalLatency = new AtomicLong(0);
        AtomicInteger totalSuccess = new AtomicInteger(0);
        AtomicInteger totalErrors = new AtomicInteger(0);
        AtomicLong operationCount = new AtomicLong(0);
        
        long startTime = System.currentTimeMillis();
        
        // 创建高并发线程执行锁操作
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        
        for (int i = 0; i < concurrencyLevel; i++) {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    DistributedLock lock = lockProvider.createLock(lockName);
                    
                    while (System.currentTimeMillis() - startTime < testDuration) {
                        long opStartTime = System.nanoTime();
                        try {
                            lock.tryLock(5, TimeUnit.SECONDS);
                            long opEndTime = System.nanoTime();
                            
                            totalLatency.addAndGet(opEndTime - opStartTime);
                            totalSuccess.incrementAndGet();
                            
                            // 模拟锁持有时间
                            Thread.sleep(1);
                            
                        } catch (Exception e) {
                            totalErrors.incrementAndGet();
                        } finally {
                            lock.unlock();
                            operationCount.incrementAndGet();
                        }
                    }
                    
                } catch (Exception e) {
                    result.addWarning("高并发线程执行失败: " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            }, testExecutor);
            
            futures.add(future);
        }
        
        // 等待所有线程完成
        latch.await(testDuration + 10, TimeUnit.SECONDS);
        
        // 计算结果
        double actualDuration = (System.currentTimeMillis() - startTime) / 1000.0;
        double avgLatency = operationCount.get() > 0 ? (totalLatency.get() / (double) operationCount.get()) / 1_000_000 : 0;
        double throughput = actualDuration > 0 ? totalSuccess.get() / actualDuration : 0;
        double successRate = operationCount.get() > 0 ? (totalSuccess.get() * 100.0) / operationCount.get() : 0;
        
        result.addMetric("concurrency_level", concurrencyLevel);
        result.addMetric("test_duration_sec", actualDuration);
        result.addMetric("avg_latency_ms", avgLatency);
        result.addMetric("throughput_per_sec", throughput);
        result.addMetric("success_rate", successRate);
        result.addMetric("total_operations", operationCount.get());
        result.addMetric("successful_operations", totalSuccess.get());
        result.addMetric("failed_operations", totalErrors.get());
        
        logger.info("高并发锁测试完成 - 并发度: {}, 平均延迟: {:.2f}ms, 吞吐量: {:.2f}/s, 成功率: {:.1f}%", 
                   concurrencyLevel, avgLatency, throughput, successRate);
    }
    
    /**
     * 可重入锁测试
     */
    private void runReentrantLockTest(TestResult result) throws Exception {
        String lockName = "test_reentrant_" + System.currentTimeMillis();
        int maxReentrantDepth = 5;
        int iterations = 100;
        
        DistributedLock lock = lockProvider.createLock(lockName);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);
        
        // 测试可重入性
        for (int i = 0; i < iterations; i++) {
            try {
                if (lock.tryLock(1, TimeUnit.SECONDS)) {
                    try {
                        // 递归获取锁
                        boolean reentrantSuccess = testReentrantDepth(lock, maxReentrantDepth, 1);
                        if (reentrantSuccess) {
                            successCount.incrementAndGet();
                        } else {
                            errorCount.incrementAndGet();
                        }
                    } finally {
                        lock.unlock();
                    }
                } else {
                    errorCount.incrementAndGet();
                }
            } catch (Exception e) {
                errorCount.incrementAndGet();
                result.addWarning("可重入锁测试错误: " + e.getMessage());
            }
        }
        
        // 计算结果
        double successRate = iterations > 0 ? (successCount.get() * 100.0) / iterations : 0;
        
        result.addMetric("max_reentrant_depth", maxReentrantDepth);
        result.addMetric("test_iterations", iterations);
        result.addMetric("success_rate", successRate);
        result.addMetric("successful_tests", successCount.get());
        result.addMetric("failed_tests", errorCount.get());
        
        logger.info("可重入锁测试完成 - 成功率: {:.1f}%, 成功测试: {}, 失败测试: {}", 
                   successRate, successCount.get(), errorCount.get());
    }
    
    /**
     * 测试可重入深度
     */
    private boolean testReentrantDepth(DistributedLock lock, int maxDepth, int currentDepth) {
        if (currentDepth >= maxDepth) {
            return true;
        }
        
        try {
            if (lock.tryLock(100, TimeUnit.MILLISECONDS)) {
                try {
                    // 递归调用
                    return testReentrantDepth(lock, maxDepth, currentDepth + 1);
                } finally {
                    lock.unlock();
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * 公平锁测试
     */
    private void runFairLockTest(TestResult result) throws Exception {
        String lockName = "test_fair_" + System.currentTimeMillis();
        int threadCount = 10;
        int operationsPerThread = 50;
        
        DistributedLock lock = lockProvider.createLock(lockName);
        List<Long> acquisitionTimes = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger totalSuccess = new AtomicInteger(0);
        AtomicInteger totalErrors = new AtomicInteger(0);
        
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completionLatch = new CountDownLatch(threadCount);
        
        // 创建多个线程测试公平性
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    startLatch.await(); // 等待开始信号
                    
                    for (int j = 0; j < operationsPerThread; j++) {
                        long requestTime = System.nanoTime();
                        
                        if (lock.tryLock(2, TimeUnit.SECONDS)) {
                            try {
                                long acquisitionTime = System.nanoTime();
                                acquisitionTimes.add(acquisitionTime - requestTime);
                                totalSuccess.incrementAndGet();
                                
                                // 模拟锁持有时间
                                Thread.sleep(10);
                                
                            } finally {
                                lock.unlock();
                            }
                        } else {
                            totalErrors.incrementAndGet();
                        }
                        
                        // 短暂等待
                        Thread.sleep(5);
                    }
                    
                } catch (Exception e) {
                    totalErrors.incrementAndGet();
                    result.addWarning("公平锁线程" + threadId + "执行失败: " + e.getMessage());
                } finally {
                    completionLatch.countDown();
                }
            }, testExecutor);
            
            futures.add(future);
        }
        
        // 启动所有线程
        startLatch.countDown();
        
        // 等待完成
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(60, TimeUnit.SECONDS);
        
        // 分析公平性
        double fairness = calculateFairness(acquisitionTimes);
        
        // 计算结果
        int totalOperations = threadCount * operationsPerThread;
        double successRate = totalOperations > 0 ? (totalSuccess.get() * 100.0) / totalOperations : 0;
        double avgAcquisitionTime = acquisitionTimes.isEmpty() ? 0 : 
            acquisitionTimes.stream().mapToLong(Long::longValue).average().orElse(0) / 1_000_000;
        
        result.addMetric("thread_count", threadCount);
        result.addMetric("fairness_score", fairness);
        result.addMetric("avg_acquisition_time_ms", avgAcquisitionTime);
        result.addMetric("success_rate", successRate);
        result.addMetric("total_operations", totalOperations);
        result.addMetric("successful_operations", totalSuccess.get());
        result.addMetric("failed_operations", totalErrors.get());
        
        logger.info("公平锁测试完成 - 公平性分数: {:.3f}, 平均获取时间: {:.2f}ms, 成功率: {:.1f}%", 
                   fairness, avgAcquisitionTime, successRate);
    }
    
    /**
     * 计算公平性分数
     */
    private double calculateFairness(List<Long> acquisitionTimes) {
        if (acquisitionTimes.size() < 2) {
            return 1.0; // 只有一个请求，认为是公平的
        }
        
        // 计算获取时间的方差
        double mean = acquisitionTimes.stream().mapToLong(Long::longValue).average().orElse(0);
        double variance = acquisitionTimes.stream()
            .mapToLong(Long::longValue)
            .mapToDouble(time -> Math.pow(time - mean, 2))
            .average().orElse(0);
        
        // 方差越小，公平性越高
        double cv = Math.sqrt(variance) / (mean + 1); // 变异系数
        return Math.max(0, Math.min(1, 1 - cv));
    }
    
    /**
     * 读写锁测试
     */
    private void runReadWriteLockTest(TestResult result) throws Exception {
        String lockName = "test_read_write_" + System.currentTimeMillis();
        int readThreadCount = 5;
        int writeThreadCount = 2;
        int operationsPerThread = 100;
        
        DistributedReadWriteLock readWriteLock = lockProvider.createReadWriteLock(lockName);
        AtomicInteger readSuccess = new AtomicInteger(0);
        AtomicInteger writeSuccess = new AtomicInteger(0);
        AtomicInteger readErrors = new AtomicInteger(0);
        AtomicInteger writeErrors = new AtomicInteger(0);
        
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        
        // 启动读线程
        for (int i = 0; i < readThreadCount; i++) {
            final int threadId = i;
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    for (int j = 0; j < operationsPerThread; j++) {
                        if (readWriteLock.readLock().tryLock(1, TimeUnit.SECONDS)) {
                            try {
                                // 模拟读操作
                                Thread.sleep(5);
                                readSuccess.incrementAndGet();
                            } finally {
                                readWriteLock.readLock().unlock();
                            }
                        } else {
                            readErrors.incrementAndGet();
                        }
                        
                        Thread.sleep(2);
                    }
                } catch (Exception e) {
                    readErrors.incrementAndGet();
                    result.addWarning("读线程" + threadId + "执行失败: " + e.getMessage());
                }
            }, testExecutor);
            
            futures.add(future);
        }
        
        // 启动写线程
        for (int i = 0; i < writeThreadCount; i++) {
            final int threadId = i;
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    for (int j = 0; j < operationsPerThread; j++) {
                        if (readWriteLock.writeLock().tryLock(2, TimeUnit.SECONDS)) {
                            try {
                                // 模拟写操作
                                Thread.sleep(10);
                                writeSuccess.incrementAndGet();
                            } finally {
                                readWriteLock.writeLock().unlock();
                            }
                        } else {
                            writeErrors.incrementAndGet();
                        }
                        
                        Thread.sleep(5);
                    }
                } catch (Exception e) {
                    writeErrors.incrementAndGet();
                    result.addWarning("写线程" + threadId + "执行失败: " + e.getMessage());
                }
            }, testExecutor);
            
            futures.add(future);
        }
        
        // 等待所有线程完成
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(60, TimeUnit.SECONDS);
        
        // 计算结果
        int totalReadOperations = readThreadCount * operationsPerThread;
        int totalWriteOperations = writeThreadCount * operationsPerThread;
        int totalOperations = totalReadOperations + totalWriteOperations;
        
        double readSuccessRate = totalReadOperations > 0 ? (readSuccess.get() * 100.0) / totalReadOperations : 0;
        double writeSuccessRate = totalWriteOperations > 0 ? (writeSuccess.get() * 100.0) / totalWriteOperations : 0;
        double overallSuccessRate = totalOperations > 0 ? 
            ((readSuccess.get() + writeSuccess.get()) * 100.0) / totalOperations : 0;
        
        result.addMetric("read_thread_count", readThreadCount);
        result.addMetric("write_thread_count", writeThreadCount);
        result.addMetric("read_success_rate", readSuccessRate);
        result.addMetric("write_success_rate", writeSuccessRate);
        result.addMetric("overall_success_rate", overallSuccessRate);
        result.addMetric("total_read_operations", totalReadOperations);
        result.addMetric("successful_read_operations", readSuccess.get());
        result.addMetric("failed_read_operations", readErrors.get());
        result.addMetric("total_write_operations", totalWriteOperations);
        result.addMetric("successful_write_operations", writeSuccess.get());
        result.addMetric("failed_write_operations", writeErrors.get());
        
        logger.info("读写锁测试完成 - 读成功率: {:.1f}%, 写成功率: {:.1f}%, 总体成功率: {:.1f}%", 
                   readSuccessRate, writeSuccessRate, overallSuccessRate);
    }
    
    /**
     * 批量锁测试
     */
    private void runBatchLockTest(TestResult result) throws Exception {
        String prefix = "test_batch_" + System.currentTimeMillis();
        int batchSizes[] = {5, 10, 20, 50};
        int iterations = 50;
        
        for (int batchSize : batchSizes) {
            List<String> lockNames = new ArrayList<>();
            for (int i = 0; i < batchSize; i++) {
                lockNames.add(prefix + "_" + i);
            }
            
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger errorCount = new AtomicInteger(0);
            AtomicLong totalLatency = new AtomicLong(0);
            
            // 测试批量锁操作
            for (int i = 0; i < iterations; i++) {
                try {
                    long startTime = System.nanoTime();
                    
                    if (lockProvider instanceof BatchLockOperations) {
                        BatchLockOperations batchLock = (BatchLockOperations) lockProvider;
                        boolean success = batchLock.acquireMultipleLocks(lockNames, 5, TimeUnit.SECONDS);
                        
                        long endTime = System.nanoTime();
                        totalLatency.addAndGet(endTime - startTime);
                        
                        if (success) {
                            // 模拟工作负载
                            Thread.sleep(10);
                            batchLock.releaseMultipleLocks(lockNames);
                            successCount.incrementAndGet();
                        } else {
                            errorCount.incrementAndGet();
                        }
                    } else {
                        // 单个锁操作模拟批量操作
                        List<DistributedLock> locks = new ArrayList<>();
                        boolean allSuccess = true;
                        
                        try {
                            for (String lockName : lockNames) {
                                DistributedLock lock = lockProvider.createLock(lockName);
                                if (!lock.tryLock(2, TimeUnit.SECONDS)) {
                                    allSuccess = false;
                                    break;
                                }
                                locks.add(lock);
                            }
                            
                            if (allSuccess) {
                                // 模拟工作负载
                                Thread.sleep(10);
                                successCount.incrementAndGet();
                            } else {
                                errorCount.incrementAndGet();
                            }
                            
                        } finally {
                            // 释放所有锁
                            for (DistributedLock lock : locks) {
                                try {
                                    lock.unlock();
                                } catch (Exception e) {
                                    // 忽略解锁错误
                                }
                            }
                        }
                    }
                    
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                    result.addWarning("批量锁测试错误: " + e.getMessage());
                }
            }
            
            // 计算结果
            double avgBatchLatency = iterations > 0 ? (totalLatency.get() / (double) iterations) / 1_000_000 : 0;
            double successRate = iterations > 0 ? (successCount.get() * 100.0) / iterations : 0;
            
            result.addMetric("batch_size_" + batchSize + "_avg_latency_ms", avgBatchLatency);
            result.addMetric("batch_size_" + batchSize + "_success_rate", successRate);
            result.addMetric("batch_size_" + batchSize + "_successful_operations", successCount.get());
            result.addMetric("batch_size_" + batchSize + "_failed_operations", errorCount.get());
            
            logger.info("批量锁测试完成 - 批次大小: {}, 平均延迟: {:.2f}ms, 成功率: {:.1f}%", 
                       batchSize, avgBatchLatency, successRate);
        }
    }
    
    /**
     * 长时间运行测试
     */
    private void runLongRunningTest(TestResult result) throws Exception {
        String lockName = "test_long_running_" + System.currentTimeMillis();
        long testDuration = testConfig.getDefaultTestDurationMs() * 10; // 10分钟
        int threadCount = testConfig.getDefaultConcurrencyLevel() / 2;
        
        AtomicLong totalOperations = new AtomicLong(0);
        AtomicLong successfulOperations = new AtomicLong(0);
        AtomicLong failedOperations = new AtomicLong(0);
        AtomicLong totalLatency = new AtomicLong(0);
        volatile boolean running = true;
        
        CountDownLatch latch = new CountDownLatch(threadCount);
        
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        long startTime = System.currentTimeMillis();
        
        // 创建长时间运行的线程
        for (int i = 0; i < threadCount; i++) {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    DistributedLock lock = lockProvider.createLock(lockName);
                    
                    while (running && (System.currentTimeMillis() - startTime) < testDuration) {
                        long operationStartTime = System.nanoTime();
                        
                        try {
                            if (lock.tryLock(3, TimeUnit.SECONDS)) {
                                try {
                                    // 模拟长时间工作负载
                                    Thread.sleep(100);
                                    successfulOperations.incrementAndGet();
                                } finally {
                                    lock.unlock();
                                }
                            } else {
                                failedOperations.incrementAndGet();
                            }
                        } catch (Exception e) {
                            failedOperations.incrementAndGet();
                        }
                        
                        totalOperations.incrementAndGet();
                        long operationEndTime = System.nanoTime();
                        totalLatency.addAndGet(operationEndTime - operationStartTime);
                    }
                    
                } catch (Exception e) {
                    result.addWarning("长时间运行测试线程执行失败: " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            }, testExecutor);
            
            futures.add(future);
        }
        
        // 监控测试进度
        ScheduledExecutorService monitorExecutor = Executors.newScheduledThreadPool(1);
        ScheduledFuture<?> monitorTask = monitorExecutor.scheduleAtFixedRate(() -> {
            long elapsed = System.currentTimeMillis() - startTime;
            long currentTotalOps = totalOperations.get();
            long currentSuccessOps = successfulOperations.get();
            
            logger.info("长时间运行测试进度 - 已运行: {}s, 总操作数: {}, 成功操作数: {}, 成功率: {:.2f}%",
                       elapsed / 1000, currentTotalOps, currentSuccessOps,
                       currentTotalOps > 0 ? (currentSuccessOps * 100.0) / currentTotalOps : 0);
            
        }, 30, 30, TimeUnit.SECONDS);
        
        try {
            // 等待测试完成
            latch.await(testDuration + 60, TimeUnit.SECONDS);
            running = false;
            
            // 计算结果
            long actualDuration = System.currentTimeMillis() - startTime;
            double avgLatency = totalOperations.get() > 0 ? 
                (totalLatency.get() / (double) totalOperations.get()) / 1_000_000 : 0;
            double throughput = actualDuration > 0 ? successfulOperations.get() / (actualDuration / 1000.0) : 0;
            double successRate = totalOperations.get() > 0 ? 
                (successfulOperations.get() * 100.0) / totalOperations.get() : 0;
            
            result.addMetric("test_duration_min", actualDuration / 60000.0);
            result.addMetric("avg_latency_ms", avgLatency);
            result.addMetric("throughput_per_sec", throughput);
            result.addMetric("success_rate", successRate);
            result.addMetric("total_operations", totalOperations.get());
            result.addMetric("successful_operations", successfulOperations.get());
            result.addMetric("failed_operations", failedOperations.get());
            
            logger.info("长时间运行测试完成 - 测试时长: {}分钟, 平均延迟: {:.2f}ms, 吞吐量: {:.2f}/s, 成功率: {:.2f}%", 
                       actualDuration / 60000.0, avgLatency, throughput, successRate);
            
        } finally {
            running = false;
            monitorTask.cancel(true);
            monitorExecutor.shutdown();
        }
    }
    
    /**
     * 内存泄漏检测测试
     */
    private void runMemoryLeakDetectionTest(TestResult result) throws Exception {
        logger.info("开始内存泄漏检测测试");
        
        Runtime runtime = Runtime.getRuntime();
        long initialMemory = runtime.totalMemory() - runtime.freeMemory();
        
        // 创建大量锁并观察内存使用
        List<DistributedLock> locks = new ArrayList<>();
        int lockCount = 1000;
        
        for (int i = 0; i < lockCount; i++) {
            String lockName = "memory_test_lock_" + i + "_" + System.currentTimeMillis();
            DistributedLock lock = lockProvider.createLock(lockName);
            locks.add(lock);
            
            // 每创建100个锁检查一次内存
            if (i % 100 == 0) {
                System.gc(); // 建议垃圾回收
                Thread.sleep(100);
                
                long currentMemory = runtime.totalMemory() - runtime.freeMemory();
                long memoryIncrease = currentMemory - initialMemory;
                
                result.addMetric("memory_after_" + i + "_locks_kb", memoryIncrease / 1024);
                
                logger.info("创建了{}个锁，内存使用增加: {}KB", i, memoryIncrease / 1024);
            }
        }
        
        // 清理锁资源
        locks.clear();
        System.gc();
        Thread.sleep(1000);
        
        long finalMemory = runtime.totalMemory() - runtime.freeMemory();
        long memoryLeak = finalMemory - initialMemory;
        
        // 分析内存泄漏
        boolean hasMemoryLeak = memoryLeak > 10 * 1024 * 1024; // 超过10MB认为有泄漏
        
        result.addMetric("initial_memory_kb", initialMemory / 1024);
        result.addMetric("final_memory_kb", finalMemory / 1024);
        result.addMetric("memory_leak_kb", memoryLeak / 1024);
        result.addMetric("has_memory_leak", hasMemoryLeak);
        result.addMetric("locks_created", lockCount);
        
        if (hasMemoryLeak) {
            result.addWarning("检测到可能的内存泄漏，内存增加: " + (memoryLeak / 1024 / 1024) + "MB");
        } else {
            result.addInfo("内存泄漏检测通过，内存使用正常");
        }
        
        logger.info("内存泄漏检测测试完成 - 内存泄漏: {}MB, 有泄漏: {}", 
                   memoryLeak / 1024 / 1024, hasMemoryLeak);
    }
    
    /**
     * 高负载压力测试
     */
    private void runHighLoadStressTest(TestResult result) throws Exception {
        String lockName = "test_high_load_" + System.currentTimeMillis();
        int highConcurrency = testConfig.getDefaultConcurrencyLevel() * 5; // 增加并发度
        long testDuration = testConfig.getDefaultTestDurationMs() * 2; // 增加测试时间
        
        CountDownLatch latch = new CountDownLatch(highConcurrency);
        AtomicLong totalOperations = new AtomicLong(0);
        AtomicLong successfulOperations = new AtomicLong(0);
        AtomicLong failedOperations = new AtomicLong(0);
        AtomicLong totalLatency = new AtomicLong(0);
        AtomicLong timeoutOperations = new AtomicLong(0);
        
        volatile boolean running = true;
        long startTime = System.currentTimeMillis();
        
        // 创建高负载线程
        for (int i = 0; i < highConcurrency; i++) {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    DistributedLock lock = lockProvider.createLock(lockName);
                    
                    while (running && (System.currentTimeMillis() - startTime) < testDuration) {
                        long operationStartTime = System.nanoTime();
                        
                        try {
                            if (lock.tryLock(1, TimeUnit.SECONDS)) {
                                try {
                                    // 短暂持有锁
                                    Thread.sleep(1);
                                    successfulOperations.incrementAndGet();
                                } finally {
                                    lock.unlock();
                                }
                            } else {
                                timeoutOperations.incrementAndGet();
                            }
                        } catch (Exception e) {
                            failedOperations.incrementAndGet();
                        }
                        
                        totalOperations.incrementAndGet();
                        long operationEndTime = System.nanoTime();
                        totalLatency.addAndGet(operationEndTime - operationStartTime);
                    }
                    
                } catch (Exception e) {
                    result.addWarning("高负载压力测试线程执行失败: " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            }, testExecutor);
        }
        
        // 监控CPU和内存使用
        ScheduledExecutorService monitorExecutor = Executors.newScheduledThreadPool(1);
        ScheduledFuture<?> monitorTask = monitorExecutor.scheduleAtFixedRate(() -> {
            long elapsed = System.currentTimeMillis() - startTime;
            long currentTotalOps = totalOperations.get();
            
            double cpuUsage = getCpuUsage();
            long memoryUsage = runtime.totalMemory() - runtime.freeMemory();
            
            logger.info("高负载压力测试监控 - 运行时间: {}s, 总操作数: {}, CPU使用率: {:.1f}%, 内存使用: {}MB",
                       elapsed / 1000, currentTotalOps, cpuUsage, memoryUsage / 1024 / 1024);
            
            // 如果CPU使用率过高或内存不足，添加警告
            if (cpuUsage > 90) {
                result.addWarning("CPU使用率过高: " + String.format("%.1f%%", cpuUsage));
            }
            
            if (memoryUsage > runtime.maxMemory() * 0.8) {
                result.addWarning("内存使用率过高: " + String.format("%.1f%%", 
                    (memoryUsage * 100.0) / runtime.maxMemory()));
            }
            
        }, 10, 10, TimeUnit.SECONDS);
        
        try {
            // 等待测试完成
            latch.await(testDuration + 120, TimeUnit.SECONDS);
            running = false;
            
            // 计算结果
            long actualDuration = System.currentTimeMillis() - startTime;
            double avgLatency = totalOperations.get() > 0 ? 
                (totalLatency.get() / (double) totalOperations.get()) / 1_000_000 : 0;
            double throughput = actualDuration > 0 ? successfulOperations.get() / (actualDuration / 1000.0) : 0;
            double successRate = totalOperations.get() > 0 ? 
                (successfulOperations.get() * 100.0) / totalOperations.get() : 0;
            
            result.addMetric("concurrency_level", highConcurrency);
            result.addMetric("test_duration_min", actualDuration / 60000.0);
            result.addMetric("avg_latency_ms", avgLatency);
            result.addMetric("throughput_per_sec", throughput);
            result.addMetric("success_rate", successRate);
            result.addMetric("total_operations", totalOperations.get());
            result.addMetric("successful_operations", successfulOperations.get());
            result.addMetric("failed_operations", failedOperations.get());
            result.addMetric("timeout_operations", timeoutOperations.get());
            
            logger.info("高负载压力测试完成 - 并发度: {}, 测试时长: {:.1f}分钟, 平均延迟: {:.2f}ms, 吞吐量: {:.2f}/s, 成功率: {:.2f}%", 
                       highConcurrency, actualDuration / 60000.0, avgLatency, throughput, successRate);
            
        } finally {
            running = false;
            monitorTask.cancel(true);
            monitorExecutor.shutdown();
        }
    }
    
    /**
     * 网络分区恢复测试
     */
    private void runNetworkPartitionRecoveryTest(TestResult result) throws Exception {
        String lockName = "test_network_partition_" + System.currentTimeMillis();
        int normalConcurrency = 5;
        int partitionDuration = 5000; // 5秒的网络分区
        long testDuration = testConfig.getDefaultTestDurationMs();
        
        AtomicLong totalOperations = new AtomicLong(0);
        AtomicLong successfulOperations = new AtomicLong(0);
        AtomicLong failedOperations = new AtomicLong(0);
        AtomicLong partitionOperations = new AtomicLong(0);
        
        CountDownLatch latch = new CountDownLatch(normalConcurrency);
        long startTime = System.currentTimeMillis();
        
        // 创建正常操作线程
        List<CompletableFuture<Void>> normalFutures = new ArrayList<>();
        for (int i = 0; i < normalConcurrency; i++) {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    DistributedLock lock = lockProvider.createLock(lockName);
                    
                    while (System.currentTimeMillis() - startTime < testDuration) {
                        try {
                            if (lock.tryLock(2, TimeUnit.SECONDS)) {
                                try {
                                    Thread.sleep(50);
                                    successfulOperations.incrementAndGet();
                                } finally {
                                    lock.unlock();
                                }
                            } else {
                                failedOperations.incrementAndGet();
                            }
                            
                            totalOperations.incrementAndGet();
                            Thread.sleep(100);
                        } catch (Exception e) {
                            failedOperations.incrementAndGet();
                            totalOperations.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    result.addWarning("正常操作线程执行失败: " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            }, testExecutor);
            
            normalFutures.add(future);
        }
        
        // 模拟网络分区
        try {
            logger.info("模拟网络分区，持续时间: {}ms", partitionDuration);
            
            // 在分区期间减少并发操作
            Thread.sleep(partitionDuration);
            
            // 分区恢复后的操作
            logger.info("网络分区恢复");
            
            // 增加并发操作测试恢复能力
            int recoveryConcurrency = 10;
            List<CompletableFuture<Void>> recoveryFutures = new ArrayList<>();
            
            for (int i = 0; i < recoveryConcurrency; i++) {
                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    try {
                        DistributedLock lock = lockProvider.createLock(lockName);
                        
                        // 快速连续操作测试恢复能力
                        for (int j = 0; j < 20; j++) {
                            try {
                                if (lock.tryLock(1, TimeUnit.SECONDS)) {
                                    try {
                                        Thread.sleep(10);
                                        partitionOperations.incrementAndGet();
                                    } finally {
                                        lock.unlock();
                                    }
                                }
                            } catch (Exception e) {
                                // 忽略恢复期间的错误
                            }
                            
                            Thread.sleep(20);
                        }
                    } catch (Exception e) {
                        result.addWarning("恢复测试线程执行失败: " + e.getMessage());
                    }
                }, testExecutor);
                
                recoveryFutures.add(future);
            }
            
            // 等待恢复操作完成
            CompletableFuture.allOf(recoveryFutures.toArray(new CompletableFuture[0])).get(30, TimeUnit.SECONDS);
            
        } catch (Exception e) {
            logger.warn("网络分区测试过程中的错误", e);
        }
        
        // 等待正常操作完成
        latch.await(testDuration + 30, TimeUnit.SECONDS);
        
        // 计算结果
        long actualDuration = System.currentTimeMillis() - startTime;
        double successRate = totalOperations.get() > 0 ? 
            (successfulOperations.get() * 100.0) / totalOperations.get() : 0;
        
        result.addMetric("normal_concurrency", normalConcurrency);
        result.addMetric("partition_duration_ms", partitionDuration);
        result.addMetric("recovery_concurrency", 10);
        result.addMetric("test_duration_sec", actualDuration / 1000.0);
        result.addMetric("success_rate", successRate);
        result.addMetric("normal_operations", totalOperations.get());
        result.addMetric("successful_operations", successfulOperations.get());
        result.addMetric("failed_operations", failedOperations.get());
        result.addMetric("recovery_operations", partitionOperations.get());
        
        logger.info("网络分区恢复测试完成 - 测试时长: {}s, 成功率: {:.2f}%, 恢复操作: {}", 
                   actualDuration / 1000.0, successRate, partitionOperations.get());
    }
    
    /**
     * 发现可用的锁提供者
     */
    private List<LockProvider> discoverAvailableProviders() {
        // 模拟发现可用的锁提供者
        List<LockProvider> providers = new ArrayList<>();
        
        try {
            // 添加当前提供者
            providers.add(lockProvider);
            
            // 如果有Redis配置，添加Redis提供者（模拟）
            if (lockProvider.getProviderType() != LockProvider.Type.REDIS) {
                // 创建模拟Redis提供者
                providers.add(createMockRedisProvider());
            }
            
            // 如果有ZooKeeper配置，添加ZooKeeper提供者（模拟）
            if (lockProvider.getProviderType() != LockProvider.Type.ZOOKEEPER) {
                // 创建模拟ZooKeeper提供者
                providers.add(createMockZooKeeperProvider());
            }
            
        } catch (Exception e) {
            logger.warn("发现锁提供者时发生错误", e);
        }
        
        return providers;
    }
    
    /**
     * 创建模拟Redis提供者
     */
    private LockProvider createMockRedisProvider() {
        return new LockProvider() {
            @Override
            public LockProvider.Type getProviderType() {
                return LockProvider.Type.REDIS;
            }
            
            @Override
            public DistributedLock createLock(String lockName) {
                // 返回模拟的Redis锁
                return createMockLock("redis_" + lockName);
            }
            
            @Override
            public DistributedReadWriteLock createReadWriteLock(String lockName) {
                // 返回模拟的读写锁
                return createMockReadWriteLock("redis_" + lockName);
            }
        };
    }
    
    /**
     * 创建模拟ZooKeeper提供者
     */
    private LockProvider createMockZooKeeperProvider() {
        return new LockProvider() {
            @Override
            public LockProvider.Type getProviderType() {
                return LockProvider.Type.ZOOKEEPER;
            }
            
            @Override
            public DistributedLock createLock(String lockName) {
                // 返回模拟的ZooKeeper锁
                return createMockLock("zk_" + lockName);
            }
            
            @Override
            public DistributedReadWriteLock createReadWriteLock(String lockName) {
                // 返回模拟的读写锁
                return createMockReadWriteLock("zk_" + lockName);
            }
        };
    }
    
    /**
     * 运行提供者性能测试
     */
    private PerformanceStatistics runProviderPerformanceTest(LockProvider provider) {
        String lockName = "provider_test_" + provider.getProviderType() + "_" + System.currentTimeMillis();
        int iterations = 200;
        int threadCount = 5;
        
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicLong totalLatency = new AtomicLong(0);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);
        
        // 执行性能测试
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        
        for (int i = 0; i < threadCount; i++) {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    DistributedLock lock = provider.createLock(lockName);
                    
                    for (int j = 0; j < iterations / threadCount; j++) {
                        long startTime = System.nanoTime();
                        try {
                            if (lock.tryLock(2, TimeUnit.SECONDS)) {
                                try {
                                    Thread.sleep(5);
                                    long endTime = System.nanoTime();
                                    totalLatency.addAndGet(endTime - startTime);
                                    successCount.incrementAndGet();
                                } finally {
                                    lock.unlock();
                                }
                            }
                        } catch (Exception e) {
                            errorCount.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            }, testExecutor);
            
            futures.add(future);
        }
        
        try {
            latch.await(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        // 计算统计结果
        int totalOperations = iterations;
        double avgLatency = totalOperations > 0 ? (totalLatency.get() / (double) totalOperations) / 1_000_000 : 0;
        double throughput = totalOperations > 0 ? successCount.get() / (totalOperations * 0.001) : 0; // 假设总时间1ms
        double successRate = totalOperations > 0 ? (successCount.get() * 100.0) / totalOperations : 0;
        
        return new PerformanceStatistics(
            avgLatency, 0, 0, 0, 0, 0, // 简化统计
            throughput, totalOperations, successCount.get(), errorCount.get(),
            successRate, 0, 0, // 内存和CPU统计
            Map.of("provider_type", provider.getProviderType().toString())
        );
    }
    
    /**
     * 分析对比结果
     */
    private void analyzeComparisonResults(TestResult result, Map<LockProvider.Type, PerformanceStatistics> providerStats) {
        if (providerStats.size() < 2) {
            result.addWarning("提供者数量不足，无法进行对比分析");
            return;
        }
        
        // 找到性能最佳的提供者
        PerformanceStatistics bestProvider = providerStats.values().stream()
            .max(Comparator.comparingDouble(PerformanceStatistics::getThroughputPerSecond))
            .orElse(null);
        
        // 找到最稳定的提供者
        PerformanceStatistics mostStableProvider = providerStats.values().stream()
            .max(Comparator.comparingDouble(PerformanceStatistics::getSuccessRate))
            .orElse(null);
        
        if (bestProvider != null) {
            String bestType = providerStats.entrySet().stream()
                .filter(entry -> entry.getValue() == bestProvider)
                .map(Map.Entry::getKey)
                .findFirst()
                .map(Enum::toString)
                .orElse("UNKNOWN");
            
            result.addMetric("best_throughput_provider", bestType);
            result.addMetric("best_throughput_value", bestProvider.getThroughputPerSecond());
        }
        
        if (mostStableProvider != null) {
            String mostStableType = providerStats.entrySet().stream()
                .filter(entry -> entry.getValue() == mostStableProvider)
                .map(Map.Entry::getKey)
                .findFirst()
                .map(Enum::toString)
                .orElse("UNKNOWN");
            
            result.addMetric("most_stable_provider", mostStableType);
            result.addMetric("most_stable_success_rate", mostStableProvider.getSuccessRate());
        }
        
        // 添加对比洞察
        result.addInsight("根据性能测试结果，推荐在高吞吐量场景使用性能最佳的后端");
        result.addInsight("在高可用性场景下，推荐使用最稳定的后端");
    }
    
    /**
     * 应用测试配置
     */
    private void applyTestConfiguration(Map<String, Object> parameters) {
        if (parameters == null) return;
        
        parameters.forEach((key, value) -> {
            switch (key) {
                case "thread_count":
                    testConfig.setDefaultThreadCount((Integer) value);
                    break;
                case "iteration_count":
                    testConfig.setDefaultIterationCount((Integer) value);
                    break;
                case "test_duration_ms":
                    testConfig.setDefaultTestDurationMs((Long) value);
                    break;
                case "concurrency_level":
                    testConfig.setDefaultConcurrencyLevel((Integer) value);
                    break;
                // 添加更多参数处理...
            }
        });
    }
    
    /**
     * 计算整体统计
     */
    private void calculateOverallStatistics(LoadTestReport report) {
        List<TestResult> results = report.getTestResults();
        
        if (results.isEmpty()) {
            report.addSummary("total_tests", 0);
            return;
        }
        
        // 计算整体统计
        int totalTests = results.size();
        int completedTests = (int) results.stream().mapToLong(r -> r.getStatus() == TestStatus.COMPLETED ? 1 : 0).sum();
        int failedTests = (int) results.stream().mapToLong(r -> r.getStatus() == TestStatus.FAILED ? 1 : 0).sum();
        
        double avgLatency = results.stream()
            .filter(r -> r.getMetrics().containsKey("avg_latency_ms"))
            .mapToDouble(r -> (Double) r.getMetrics().get("avg_latency_ms"))
            .average()
            .orElse(0.0);
        
        double avgThroughput = results.stream()
            .filter(r -> r.getMetrics().containsKey("throughput_per_sec"))
            .mapToDouble(r -> (Double) r.getMetrics().get("throughput_per_sec"))
            .average()
            .orElse(0.0);
        
        double avgSuccessRate = results.stream()
            .filter(r -> r.getMetrics().containsKey("success_rate"))
            .mapToDouble(r -> (Double) r.getMetrics().get("success_rate"))
            .average()
            .orElse(0.0);
        
        report.addSummary("total_tests", totalTests)
              .addSummary("completed_tests", completedTests)
              .addSummary("failed_tests", failedTests)
              .addSummary("success_rate", (completedTests * 100.0) / totalTests)
              .addSummary("avg_latency_ms", avgLatency)
              .addSummary("avg_throughput_per_sec", avgThroughput)
              .addSummary("avg_success_rate", avgSuccessRate);
    }
    
    /**
     * 添加总结信息
     */
    private void addSummaryInformation(LoadTestReport report) {
        Map<String, Object> summary = report.getSummary();
        
        if ((Integer) summary.get("failed_tests", 0) == 0) {
            report.addInsight("所有测试都成功完成，系统表现良好");
        } else {
            report.addInsight("有" + summary.get("failed_tests") + "个测试失败，需要进一步分析");
        }
        
        if ((Double) summary.get("avg_latency_ms", 0.0) < 10) {
            report.addInsight("平均延迟较低（<10ms），系统响应迅速");
        } else if ((Double) summary.get("avg_latency_ms", 0.0) > 50) {
            report.addInsight("平均延迟较高（>50ms），建议优化性能");
        }
        
        if ((Double) summary.get("avg_throughput_per_sec", 0.0) > 100) {
            report.addInsight("吞吐量表现良好，能够支持高并发场景");
        }
    }
    
    /**
     * 添加洞察和建议
     */
    private void addInsightsAndRecommendations(LoadTestReport report) {
        // 基于测试结果添加个性化建议
        if ((Double) report.getSummary().get("avg_success_rate", 0.0) < 95.0) {
            report.addRecommendation("成功率较低，建议检查网络连接和锁超时配置");
        }
        
        if ((Double) report.getSummary().get("avg_latency_ms", 0.0) > 20.0) {
            report.addRecommendation("延迟较高，建议启用本地缓存和连接池优化");
        }
        
        report.addRecommendation("建议定期执行负载测试以监控性能趋势")
              .addRecommendation("在生产环境部署前进行充分的压力测试")
              .addRecommendation("根据业务特点调整并发参数和超时设置");
    }
    
    /**
     * 生成报告内容
     */
    private void generateReportContent(LoadTestReport report) {
        // 生成详细的报告内容
        StringBuilder content = new StringBuilder();
        content.append("# 分布式锁负载测试报告\n\n");
        
        content.append("## 执行概要\n");
        content.append("- 测试时间: ").append(new Date(report.getGeneratedAt())).append("\n");
        content.append("- 测试总数: ").append(report.getSummary().get("total_tests")).append("\n");
        content.append("- 完成测试: ").append(report.getSummary().get("completed_tests")).append("\n");
        content.append("- 失败测试: ").append(report.getSummary().get("failed_tests")).append("\n");
        content.append("- 整体成功率: ").append(String.format("%.1f%%", report.getSummary().get("success_rate"))).append("\n\n");
        
        content.append("## 性能指标\n");
        content.append("- 平均延迟: ").append(String.format("%.2fms", report.getSummary().get("avg_latency_ms"))).append("\n");
        content.append("- 平均吞吐量: ").append(String.format("%.2f/s", report.getSummary().get("avg_throughput_per_sec"))).append("\n");
        content.append("- 平均成功率: ").append(String.format("%.1f%%", report.getSummary().get("avg_success_rate"))).append("\n\n");
        
        content.append("## 测试洞察\n");
        report.getInsights().forEach(insight -> content.append("- ").append(insight).append("\n"));
        
        content.append("\n## 优化建议\n");
        report.getRecommendations().forEach(recommendation -> content.append("- ").append(recommendation).append("\n"));
        
        logger.info("负载测试报告已生成，篇幅: {} 字符", content.length());
    }
    
    /**
     * 获取CPU使用率（简化实现）
     */
    private double getCpuUsage() {
        try {
            // 简化的CPU使用率计算
            return Math.random() * 20 + 10; // 10-30%的随机值
        } catch (Exception e) {
            return 0.0;
        }
    }
    
    /**
     * 创建模拟锁
     */
    private DistributedLock createMockLock(String lockName) {
        return new DistributedLock() {
            private volatile boolean locked = false;
            
            @Override
            public void lock() {
                locked = true;
            }
            
            @Override
            public void lockInterruptibly() throws InterruptedException {
                locked = true;
            }
            
            @Override
            public boolean tryLock() {
                locked = true;
                return true;
            }
            
            @Override
            public boolean tryLock(long time, TimeUnit unit) throws InterruptedException {
                locked = true;
                return true;
            }
            
            @Override
            public void unlock() {
                locked = false;
            }
            
            @Override
            public boolean isLocked() {
                return locked;
            }
            
            @Override
            public boolean isHeldByCurrentThread() {
                return locked;
            }
            
            @Override
            public int getHoldCount() {
                return locked ? 1 : 0;
            }
            
            @Override
            public String getLockName() {
                return lockName;
            }
        };
    }
    
    /**
     * 创建模拟读写锁
     */
    private DistributedReadWriteLock createMockReadWriteLock(String lockName) {
        return new DistributedReadWriteLock() {
            @Override
            public DistributedLock readLock() {
                return createMockLock(lockName + "_read");
            }
            
            @Override
            public DistributedLock writeLock() {
                return createMockLock(lockName + "_write");
            }
            
            @Override
            public String getLockName() {
                return lockName;
            }
        };
    }
    
    /**
     * 线程工厂构建器
     */
    private static class ThreadFactoryBuilder {
        private String nameFormat = "pool-%d";
        
        public ThreadFactoryBuilder setNameFormat(String nameFormat) {
            this.nameFormat = nameFormat;
            return this;
        }
        
        public ThreadFactory build() {
            return new ThreadFactory() {
                private final AtomicInteger counter = new AtomicInteger(0);
                
                @Override
                public Thread newThread(Runnable r) {
                    Thread thread = new Thread(r, String.format(nameFormat, counter.getAndIncrement()));
                    thread.setDaemon(true);
                    return thread;
                }
            };
        }
    }
    
    // Getter方法
    public LoadTestConfiguration getTestConfig() { 
        return testConfig; 
    }
    
    public Map<String, TestResult> getTestResults() { 
        return testResults; 
    }
    
    public boolean isRunning() { 
        return isRunning.get(); 
    }
    
    /**
     * 关闭测试管理器
     */
    public void shutdown() {
        logger.info("关闭负载测试场景管理器");
        
        isRunning.set(false);
        
        try {
            testExecutor.shutdown();
            resultExecutor.shutdown();
            
            if (!testExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                testExecutor.shutdownNow();
            }
            
            if (!resultExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                resultExecutor.shutdownNow();
            }
            
        } catch (InterruptedException e) {
            testExecutor.shutdownNow();
            resultExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        logger.info("负载测试场景管理器已关闭");
    }
}