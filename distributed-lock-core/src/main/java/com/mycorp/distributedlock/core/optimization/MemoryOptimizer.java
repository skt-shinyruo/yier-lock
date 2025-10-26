package com.mycorp.distributedlock.core.optimization;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.atomic.LongAdder;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.lang.ref.WeakReference;
import java.lang.ref.ReferenceQueue;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * 内存优化器
 * 
 * 功能特性：
 * 1. 优化对象创建和垃圾回收
 * 2. 实现对象池和复用机制
 * 3. 减少内存占用和GC压力
 * 4. 添加内存监控和预警
 * 5. 内存泄漏检测
 * 6. 堆内存和非堆内存优化
 */
public class MemoryOptimizer {
    
    private final Logger logger = LoggerFactory.getLogger(MemoryOptimizer.class);
    
    // 配置参数
    private final MemoryConfig config;
    
    // 内存统计
    private final LongAdder totalAllocatedObjects = new LongAdder();
    private final LongAdder totalReusedObjects = new LongAdder();
    private final LongAdder totalGarbageCollections = new LongAdder();
    private final LongAdder totalMemorySaved = new LongAdder();
    private final AtomicLong peakHeapUsage = new AtomicLong(0);
    private final AtomicLong peakNonHeapUsage = new AtomicLong(0);
    
    // GC统计
    private final AtomicLong totalGcTime = new AtomicLong(0);
    private final AtomicLong maxGcTime = new AtomicLong(0);
    private final AtomicInteger gcCount = new AtomicInteger(0);
    
    // 对象池
    private final Map<Class<?>, ObjectPool<?>> objectPools;
    private final ReadWriteLock poolLock = new ReentrantReadWriteLock();
    
    // 内存监控
    private final MemoryMonitor memoryMonitor;
    private final ScheduledExecutorService monitoringExecutor;
    private final ScheduledExecutorService gcExecutor;
    
    // 内存压力监控
    private final AtomicLong memoryPressureLevel = new AtomicLong(0);
    private final AtomicBoolean isMemoryPressureHigh = new AtomicBoolean(false);
    
    // 堆转储相关
    private final String heapDumpDirectory;
    
    public MemoryOptimizer(MemoryConfig config) {
        this.config = config;
        this.objectPools = new ConcurrentHashMap<>();
        this.memoryMonitor = new MemoryMonitor();
        this.heapDumpDirectory = config.getHeapDumpDirectory();
        
        // 创建堆转储目录
        if (heapDumpDirectory != null && !heapDumpDirectory.isEmpty()) {
            try {
                Files.createDirectories(Paths.get(heapDumpDirectory));
            } catch (Exception e) {
                logger.warn("无法创建堆转储目录: {}", heapDumpDirectory, e);
            }
        }
        
        this.monitoringExecutor = Executors.newScheduledThreadPool(2);
        this.gcExecutor = Executors.newScheduledThreadPool(1);
        
        initializeMemoryOptimization();
        startMonitoring();
        
        logger.info("内存优化器初始化完成 - 对象池大小: {}, 内存监控间隔: {}s, GC监控: {}",
                config.getObjectPoolConfigs().size(), config.getMonitoringIntervalSeconds(), config.isGcMonitoringEnabled());
    }
    
    /**
     * 初始化内存优化
     */
    private void initializeMemoryOptimization() {
        // 初始化对象池
        if (config.isObjectPoolingEnabled()) {
            initializeObjectPools();
        }
        
        // 启动GC优化
        if (config.isGcOptimizationEnabled()) {
            startGcOptimization();
        }
        
        // 启动内存压力监控
        startMemoryPressureMonitoring();
    }
    
    /**
     * 初始化对象池
     */
    private void initializeObjectPools() {
        for (ObjectPoolConfig poolConfig : config.getObjectPoolConfigs()) {
            try {
                ObjectPool<?> pool = createObjectPool(poolConfig);
                objectPools.put(poolConfig.getObjectClass(), pool);
                logger.debug("初始化对象池: {} - 容量: {}", poolConfig.getObjectClass().getSimpleName(), poolConfig.getMaxSize());
            } catch (Exception e) {
                logger.warn("初始化对象池失败: {}", poolConfig.getObjectClass().getSimpleName(), e);
            }
        }
    }
    
    /**
     * 创建对象池
     */
    private ObjectPool<?> createObjectPool(ObjectPoolConfig config) {
        return new ObjectPool<>(
                config.getObjectClass(),
                config.getFactory(),
                config.getMaxSize(),
                config.getMinSize(),
                config.getReuseTimeout()
        );
    }
    
    /**
     * 获取对象（从对象池）
     */
    @SuppressWarnings("unchecked")
    public <T> T acquireObject(Class<T> objectClass) {
        poolLock.readLock().lock();
        
        try {
            ObjectPool<T> pool = (ObjectPool<T>) objectPools.get(objectClass);
            if (pool != null) {
                T object = pool.acquire();
                if (object != null) {
                    totalReusedObjects.increment();
                    return object;
                }
            }
            
            // 对象池中没有可用对象，创建新对象
            return createNewObject(objectClass);
            
        } finally {
            poolLock.readLock().unlock();
        }
    }
    
    /**
     * 释放对象（回收到对象池）
     */
    @SuppressWarnings("unchecked")
    public <T> void releaseObject(Class<T> objectClass, T object) {
        if (object == null) {
            return;
        }
        
        poolLock.readLock().lock();
        
        try {
            ObjectPool<T> pool = (ObjectPool<T>) objectPools.get(objectClass);
            if (pool != null && pool.canReuse(object)) {
                pool.release(object);
            } else {
                // 对象池不接受该对象，允许GC回收
                // 这里可以添加自定义的清理逻辑
                cleanupObject(object);
            }
            
        } finally {
            poolLock.readLock().unlock();
        }
    }
    
    /**
     * 创建新对象
     */
    private <T> T createNewObject(Class<T> objectClass) {
        totalAllocatedObjects.increment();
        
        try {
            // 使用配置的对象工厂或默认构造函数
            ObjectFactory<T> factory = getObjectFactory(objectClass);
            return factory.create();
        } catch (Exception e) {
            logger.error("创建对象失败: {}", objectClass.getSimpleName(), e);
            throw new RuntimeException("Failed to create object: " + objectClass.getSimpleName(), e);
        }
    }
    
    /**
     * 获取对象工厂
     */
    @SuppressWarnings("unchecked")
    private <T> ObjectFactory<T> getObjectFactory(Class<T> objectClass) {
        for (ObjectPoolConfig poolConfig : config.getObjectPoolConfigs()) {
            if (poolConfig.getObjectClass().equals(objectClass) && poolConfig.getFactory() != null) {
                return (ObjectFactory<T>) poolConfig.getFactory();
            }
        }
        
        // 使用默认的反射工厂
        return new ReflectiveObjectFactory<>(objectClass);
    }
    
    /**
     * 清理对象
     */
    private void cleanupObject(Object object) {
        if (object instanceof AutoCloseable) {
            try {
                ((AutoCloseable) object).close();
            } catch (Exception e) {
                logger.debug("清理对象时发生异常", e);
            }
        }
    }
    
    /**
     * 启动GC优化
     */
    private void startGcOptimization() {
        // 定期触发GC（如果启用）
        if (config.isPeriodicGcEnabled()) {
            gcExecutor.scheduleWithFixedDelay(() -> {
                try {
                    // 强制GC
                    System.gc();
                    Thread.sleep(100); // 等待GC完成
                    
                    // 检查GC效果
                    recordGcStatistics();
                    
                } catch (Exception e) {
                    logger.error("GC优化执行异常", e);
                }
            }, config.getGcIntervalSeconds(), config.getGcIntervalSeconds(), TimeUnit.SECONDS);
        }
    }
    
    /**
     * 启动内存压力监控
     */
    private void startMemoryPressureMonitoring() {
        monitoringExecutor.scheduleWithFixedDelay(() -> {
            try {
                checkMemoryPressure();
            } catch (Exception e) {
                logger.error("内存压力监控异常", e);
            }
        }, 30, 60, TimeUnit.SECONDS);
    }
    
    /**
     * 检查内存压力
     */
    private void checkMemoryPressure() {
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        MemoryUsage nonHeapUsage = memoryBean.getNonHeapMemoryUsage();
        
        long heapUsed = heapUsage.getUsed();
        long heapMax = heapUsage.getMax();
        long nonHeapUsed = nonHeapUsage.getUsed();
        long nonHeapMax = nonHeapUsage.getMax();
        
        // 计算内存压力级别 (0-100)
        double heapPressure = heapMax > 0 ? (double) heapUsed / heapMax * 100 : 0;
        double nonHeapPressure = nonHeapMax > 0 ? (double) nonHeapUsed / nonHeapMax * 100 : 0;
        double overallPressure = Math.max(heapPressure, nonHeapPressure);
        
        memoryPressureLevel.set((long) overallPressure);
        
        boolean wasHigh = isMemoryPressureHigh.get();
        boolean nowHigh = overallPressure > config.getMemoryPressureThreshold();
        
        if (nowHigh && !wasHigh) {
            logger.warn("内存压力上升至: {:.2f}% (超过阈值: {}%)", overallPressure, config.getMemoryPressureThreshold());
            isMemoryPressureHigh.set(true);
            handleHighMemoryPressure();
        } else if (!nowHigh && wasHigh) {
            logger.info("内存压力恢复: {:.2f}%", overallPressure);
            isMemoryPressureHigh.set(false);
        }
        
        // 更新峰值使用量
        if (heapUsed > peakHeapUsage.get()) {
            peakHeapUsage.set(heapUsed);
        }
        if (nonHeapUsed > peakNonHeapUsage.get()) {
            peakNonHeapUsage.set(nonHeapUsed);
        }
    }
    
    /**
     * 处理高内存压力
     */
    private void handleHighMemoryPressure() {
        try {
            // 1. 清理未使用的对象池
            cleanupUnusedPools();
            
            // 2. 强制GC
            if (config.isForceGcOnHighPressure()) {
                System.gc();
                Thread.sleep(200); // 等待GC完成
            }
            
            // 3. 如果内存压力仍然很高，考虑触发堆转储
            if (shouldTriggerHeapDump()) {
                triggerHeapDump();
            }
            
            // 4. 调整对象池大小
            adjustPoolSizes();
            
        } catch (Exception e) {
            logger.error("处理高内存压力时发生异常", e);
        }
    }
    
    /**
     * 清理未使用的对象池
     */
    private void cleanupUnusedPools() {
        poolLock.writeLock().lock();
        
        try {
            long currentTime = System.currentTimeMillis();
            Set<Class<?>> poolsToRemove = new HashSet<>();
            
            for (Map.Entry<Class<?>, ObjectPool<?>> entry : objectPools.entrySet()) {
                ObjectPool<?> pool = entry.getValue();
                if (pool.getLastAccessTime() < currentTime - config.getPoolCleanupTimeout()) {
                    poolsToRemove.add(entry.getKey());
                }
            }
            
            for (Class<?> objectClass : poolsToRemove) {
                ObjectPool<?> pool = objectPools.remove(objectClass);
                if (pool != null) {
                    pool.shutdown();
                    logger.info("清理未使用的对象池: {}", objectClass.getSimpleName());
                }
            }
            
        } finally {
            poolLock.writeLock().unlock();
        }
    }
    
    /**
     * 检查是否应该触发堆转储
     */
    private boolean shouldTriggerHeapDump() {
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        
        double heapPressure = (double) heapUsage.getUsed() / heapUsage.getMax() * 100;
        return heapPressure > config.getHeapDumpThreshold();
    }
    
    /**
     * 触发堆转储
     */
    private void triggerHeapDump() {
        if (heapDumpDirectory == null || heapDumpDirectory.isEmpty()) {
            logger.warn("无法触发堆转储：堆转储目录未配置");
            return;
        }
        
        try {
            String heapDumpFile = heapDumpDirectory + File.separator + 
                    "heap-dump-" + System.currentTimeMillis() + ".hprof";
            
            HotSpotDiagnosticMXBean hotspotBean = ManagementFactory.getHotSpotDiagnosticMXBean();
            hotspotBean.dumpHeap(heapDumpFile, true);
            
            logger.info("堆转储已保存到: {}", heapDumpFile);
            
            // 清理旧的堆转储文件
            cleanupOldHeapDumps();
            
        } catch (Exception e) {
            logger.error("触发堆转储失败", e);
        }
    }
    
    /**
     * 清理旧的堆转储文件
     */
    private void cleanupOldHeapDumps() {
        if (heapDumpDirectory == null || config.getMaxHeapDumpFiles() <= 0) {
            return;
        }
        
        try {
            File dumpDir = new File(heapDumpDirectory);
            File[] dumpFiles = dumpDir.listFiles((dir, name) -> name.endsWith(".hprof"));
            
            if (dumpFiles != null && dumpFiles.length > config.getMaxHeapDumpFiles()) {
                Arrays.sort(dumpFiles, (f1, f2) -> Long.compare(f2.lastModified(), f1.lastModified()));
                
                for (int i = config.getMaxHeapDumpFiles(); i < dumpFiles.length; i++) {
                    if (dumpFiles[i].delete()) {
                        logger.debug("删除旧的堆转储文件: {}", dumpFiles[i].getName());
                    }
                }
            }
            
        } catch (Exception e) {
            logger.warn("清理旧堆转储文件失败", e);
        }
    }
    
    /**
     * 调整对象池大小
     */
    private void adjustPoolSizes() {
        poolLock.readLock().lock();
        
        try {
            for (ObjectPool<?> pool : objectPools.values()) {
                pool.adjustSize();
            }
        } finally {
            poolLock.readLock().unlock();
        }
    }
    
    /**
     * 记录GC统计信息
     */
    private void recordGcStatistics() {
        List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        
        long totalGcTime = 0;
        int totalGcCount = 0;
        long maxGcTime = 0;
        
        for (GarbageCollectorMXBean gcBean : gcBeans) {
            long count = gcBean.getCollectionCount();
            long time = gcBean.getCollectionTime();
            
            if (count >= 0) { // -1表示不支持
                totalGcCount += count;
                totalGcTime += time;
                maxGcTime = Math.max(maxGcTime, time);
            }
        }
        
        if (totalGcCount > 0) {
            this.totalGcTime.addAndGet(totalGcTime);
            this.maxGcTime.set(Math.max(this.maxGcTime.get(), maxGcTime));
            gcCount.addAndGet(totalGcCount);
            totalGarbageCollections.increment();
        }
    }
    
    /**
     * 启动监控
     */
    private void startMonitoring() {
        if (config.isMonitoringEnabled()) {
            monitoringExecutor.scheduleWithFixedDelay(() -> {
                try {
                    printMemoryStatistics();
                    checkMemoryLeaks();
                } catch (Exception e) {
                    logger.error("内存监控异常", e);
                }
            }, config.getMonitoringIntervalSeconds(), config.getMonitoringIntervalSeconds(), TimeUnit.SECONDS);
        }
    }
    
    /**
     * 打印内存统计信息
     */
    private void printMemoryStatistics() {
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        MemoryUsage nonHeapUsage = memoryBean.getNonHeapMemoryUsage();
        
        double heapPressure = heapUsage.getMax() > 0 ? 
                (double) heapUsage.getUsed() / heapUsage.getMax() * 100 : 0;
        double nonHeapPressure = nonHeapUsage.getMax() > 0 ? 
                (double) nonHeapUsage.getUsed() / nonHeapUsage.getMax() * 100 : 0;
        
        logger.info("内存统计 - 堆内存: {}/{}MB ({:.1f}%), 非堆内存: {}/{}MB ({:.1f}%), 压力级别: {}%",
                heapUsage.getUsed() / 1024 / 1024,
                heapUsage.getMax() / 1024 / 1024,
                heapPressure,
                nonHeapUsage.getUsed() / 1024 / 1024,
                nonHeapUsage.getMax() / 1024 / 1024,
                nonHeapPressure,
                memoryPressureLevel.get());
        
        // 对象池统计
        long totalPools = objectPools.size();
        long totalPooled = 0;
        long totalAvailable = 0;
        
        for (ObjectPool<?> pool : objectPools.values()) {
            totalPooled += pool.getTotalCreated();
            totalAvailable += pool.getAvailableCount();
        }
        
        logger.info("对象池统计 - 池数量: {}, 总创建: {}, 可用: {}, 总复用: {}, 内存节省: {}MB",
                totalPools, totalPooled, totalAvailable, totalReusedObjects.sum(),
                totalMemorySaved.get() / 1024 / 1024);
        
        // GC统计
        long totalGcTime = this.totalGcTime.get();
        int totalGcCount = this.gcCount.get();
        if (totalGcCount > 0) {
            double avgGcTime = (double) totalGcTime / totalGcCount;
            logger.info("GC统计 - 总次数: {}, 总时间: {}ms, 平均时间: {:.2f}ms, 最大时间: {}ms",
                    totalGcCount, totalGcTime, avgGcTime, this.maxGcTime.get());
        }
    }
    
    /**
     * 检查内存泄漏
     */
    private void checkMemoryLeaks() {
        if (!config.isLeakDetectionEnabled()) {
            return;
        }
        
        // 检查对象池是否有过度的对象创建
        for (Map.Entry<Class<?>, ObjectPool<?>> entry : objectPools.entrySet()) {
            ObjectPool<?> pool = entry.getValue();
            
            if (pool.getTotalCreated() > config.getMaxObjectsPerPool()) {
                logger.warn("对象池可能存在泄漏风险: {} - 创建对象数: {}",
                        entry.getKey().getSimpleName(), pool.getTotalCreated());
            }
        }
        
        // 检查内存使用增长趋势
        checkMemoryGrowthTrend();
    }
    
    /**
     * 检查内存增长趋势
     */
    private void checkMemoryGrowthTrend() {
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        
        long currentUsed = heapUsage.getUsed();
        long peakUsed = peakHeapUsage.get();
        
        // 如果当前使用量持续接近峰值且频繁触发GC，可能存在内存泄漏
        if (currentUsed > peakUsed * 0.9 && totalGarbageCollections.sum() > 100) {
            logger.warn("检测到可能的内存泄漏：当前使用量接近峰值且GC频繁");
        }
    }
    
    /**
     * 获取内存统计信息
     */
    public MemoryStatistics getStatistics() {
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        MemoryUsage nonHeapUsage = memoryBean.getNonHeapMemoryUsage();
        
        double heapPressure = heapUsage.getMax() > 0 ? 
                (double) heapUsage.getUsed() / heapUsage.getMax() * 100 : 0;
        double nonHeapPressure = nonHeapUsage.getMax() > 0 ? 
                (double) nonHeapUsage.getUsed() / nonHeapUsage.getMax() * 100 : 0;
        
        long totalAllocated = totalAllocatedObjects.sum();
        long totalReused = totalReusedObjects.sum();
        double reuseRate = totalAllocated > 0 ? (double) totalReused / totalAllocated * 100 : 0;
        
        return new MemoryStatistics(
                heapUsage.getUsed(),
                heapUsage.getMax(),
                nonHeapUsage.getUsed(),
                nonHeapUsage.getMax(),
                heapPressure,
                nonHeapPressure,
                peakHeapUsage.get(),
                peakNonHeapUsage.get(),
                memoryPressureLevel.get(),
                totalAllocated,
                totalReused,
                reuseRate,
                totalMemorySaved.get(),
                totalGarbageCollections.sum(),
                totalGcTime.get(),
                maxGcTime.get(),
                objectPools.size()
        );
    }
    
    /**
     * 关闭内存优化器
     */
    public void shutdown() {
        logger.info("关闭内存优化器");
        
        try {
            // 关闭执行器
            if (monitoringExecutor != null) {
                monitoringExecutor.shutdown();
            }
            if (gcExecutor != null) {
                gcExecutor.shutdown();
            }
            
            // 关闭对象池
            poolLock.writeLock().lock();
            try {
                for (ObjectPool<?> pool : objectPools.values()) {
                    pool.shutdown();
                }
                objectPools.clear();
            } finally {
                poolLock.writeLock().unlock();
            }
            
        } catch (Exception e) {
            logger.error("关闭内存优化器时发生异常", e);
        }
    }
    
    // ==================== 内部类 ====================
    
    /**
     * 内存配置
     */
    public static class MemoryConfig {
        private boolean objectPoolingEnabled = true;
        private boolean gcOptimizationEnabled = true;
        private boolean periodicGcEnabled = false;
        private boolean gcMonitoringEnabled = true;
        private boolean monitoringEnabled = true;
        private boolean leakDetectionEnabled = true;
        private boolean forceGcOnHighPressure = true;
        private int monitoringIntervalSeconds = 60;
        private int gcIntervalSeconds = 300;
        private long memoryPressureThreshold = 80; // 80%
        private long heapDumpThreshold = 90; // 90%
        private int maxHeapDumpFiles = 5;
        private String heapDumpDirectory = "./heap-dumps";
        private long poolCleanupTimeout = 600000; // 10分钟
        private int maxObjectsPerPool = 10000;
        private List<ObjectPoolConfig> objectPoolConfigs = new ArrayList<>();
        
        // Getters and Setters
        public boolean isObjectPoolingEnabled() { return objectPoolingEnabled; }
        public void setObjectPoolingEnabled(boolean objectPoolingEnabled) { this.objectPoolingEnabled = objectPoolingEnabled; }
        public boolean isGcOptimizationEnabled() { return gcOptimizationEnabled; }
        public void setGcOptimizationEnabled(boolean gcOptimizationEnabled) { this.gcOptimizationEnabled = gcOptimizationEnabled; }
        public boolean isPeriodicGcEnabled() { return periodicGcEnabled; }
        public void setPeriodicGcEnabled(boolean periodicGcEnabled) { this.periodicGcEnabled = periodicGcEnabled; }
        public boolean isGcMonitoringEnabled() { return gcMonitoringEnabled; }
        public void setGcMonitoringEnabled(boolean gcMonitoringEnabled) { this.gcMonitoringEnabled = gcMonitoringEnabled; }
        public boolean isMonitoringEnabled() { return monitoringEnabled; }
        public void setMonitoringEnabled(boolean monitoringEnabled) { this.monitoringEnabled = monitoringEnabled; }
        public boolean isLeakDetectionEnabled() { return leakDetectionEnabled; }
        public void setLeakDetectionEnabled(boolean leakDetectionEnabled) { this.leakDetectionEnabled = leakDetectionEnabled; }
        public boolean isForceGcOnHighPressure() { return forceGcOnHighPressure; }
        public void setForceGcOnHighPressure(boolean forceGcOnHighPressure) { this.forceGcOnHighPressure = forceGcOnHighPressure; }
        public int getMonitoringIntervalSeconds() { return monitoringIntervalSeconds; }
        public void setMonitoringIntervalSeconds(int monitoringIntervalSeconds) { this.monitoringIntervalSeconds = monitoringIntervalSeconds; }
        public int getGcIntervalSeconds() { return gcIntervalSeconds; }
        public void setGcIntervalSeconds(int gcIntervalSeconds) { this.gcIntervalSeconds = gcIntervalSeconds; }
        public long getMemoryPressureThreshold() { return memoryPressureThreshold; }
        public void setMemoryPressureThreshold(long memoryPressureThreshold) { this.memoryPressureThreshold = memoryPressureThreshold; }
        public long getHeapDumpThreshold() { return heapDumpThreshold; }
        public void setHeapDumpThreshold(long heapDumpThreshold) { this.heapDumpThreshold = heapDumpThreshold; }
        public int getMaxHeapDumpFiles() { return maxHeapDumpFiles; }
        public void setMaxHeapDumpFiles(int maxHeapDumpFiles) { this.maxHeapDumpFiles = maxHeapDumpFiles; }
        public String getHeapDumpDirectory() { return heapDumpDirectory; }
        public void setHeapDumpDirectory(String heapDumpDirectory) { this.heapDumpDirectory = heapDumpDirectory; }
        public long getPoolCleanupTimeout() { return poolCleanupTimeout; }
        public void setPoolCleanupTimeout(long poolCleanupTimeout) { this.poolCleanupTimeout = poolCleanupTimeout; }
        public int getMaxObjectsPerPool() { return maxObjectsPerPool; }
        public void setMaxObjectsPerPool(int maxObjectsPerPool) { this.maxObjectsPerPool = maxObjectsPerPool; }
        public List<ObjectPoolConfig> getObjectPoolConfigs() { return objectPoolConfigs; }
        public void setObjectPoolConfigs(List<ObjectPoolConfig> objectPoolConfigs) { this.objectPoolConfigs = objectPoolConfigs; }
    }
    
    /**
     * 对象池配置
     */
    public static class ObjectPoolConfig {
        private final Class<?> objectClass;
        private ObjectFactory<?> factory;
        private int maxSize = 100;
        private int minSize = 10;
        private long reuseTimeout = 300000; // 5分钟
        
        public ObjectPoolConfig(Class<?> objectClass) {
            this.objectClass = objectClass;
        }
        
        // Getters and Setters
        public Class<?> getObjectClass() { return objectClass; }
        public ObjectFactory<?> getFactory() { return factory; }
        public void setFactory(ObjectFactory<?> factory) { this.factory = factory; }
        public int getMaxSize() { return maxSize; }
        public void setMaxSize(int maxSize) { this.maxSize = maxSize; }
        public int getMinSize() { return minSize; }
        public void setMinSize(int minSize) { this.minSize = minSize; }
        public long getReuseTimeout() { return reuseTimeout; }
        public void setReuseTimeout(long reuseTimeout) { this.reuseTimeout = reuseTimeout; }
    }
    
    /**
     * 内存统计信息
     */
    public static class MemoryStatistics {
        private final long heapUsed;
        private final long heapMax;
        private final long nonHeapUsed;
        private final long nonHeapMax;
        private final double heapPressure;
        private final double nonHeapPressure;
        private final long peakHeapUsed;
        private final long peakNonHeapUsed;
        private final long memoryPressureLevel;
        private final long totalAllocatedObjects;
        private final long totalReusedObjects;
        private final double reuseRate;
        private final long totalMemorySaved;
        private final long totalGarbageCollections;
        private final long totalGcTime;
        private final long maxGcTime;
        private final int objectPoolCount;
        
        public MemoryStatistics(long heapUsed, long heapMax, long nonHeapUsed, long nonHeapMax,
                              double heapPressure, double nonHeapPressure, long peakHeapUsed,
                              long peakNonHeapUsed, long memoryPressureLevel, long totalAllocatedObjects,
                              long totalReusedObjects, double reuseRate, long totalMemorySaved,
                              long totalGarbageCollections, long totalGcTime, long maxGcTime,
                              int objectPoolCount) {
            this.heapUsed = heapUsed;
            this.heapMax = heapMax;
            this.nonHeapUsed = nonHeapUsed;
            this.nonHeapMax = nonHeapMax;
            this.heapPressure = heapPressure;
            this.nonHeapPressure = nonHeapPressure;
            this.peakHeapUsed = peakHeapUsed;
            this.peakNonHeapUsed = peakNonHeapUsed;
            this.memoryPressureLevel = memoryPressureLevel;
            this.totalAllocatedObjects = totalAllocatedObjects;
            this.totalReusedObjects = totalReusedObjects;
            this.reuseRate = reuseRate;
            this.totalMemorySaved = totalMemorySaved;
            this.totalGarbageCollections = totalGarbageCollections;
            this.totalGcTime = totalGcTime;
            this.maxGcTime = maxGcTime;
            this.objectPoolCount = objectPoolCount;
        }
        
        // Getters
        public long getHeapUsed() { return heapUsed; }
        public long getHeapMax() { return heapMax; }
        public long getNonHeapUsed() { return nonHeapUsed; }
        public long getNonHeapMax() { return nonHeapMax; }
        public double getHeapPressure() { return heapPressure; }
        public double getNonHeapPressure() { return nonHeapPressure; }
        public long getPeakHeapUsed() { return peakHeapUsed; }
        public long getPeakNonHeapUsed() { return peakNonHeapUsed; }
        public long getMemoryPressureLevel() { return memoryPressureLevel; }
        public long getTotalAllocatedObjects() { return totalAllocatedObjects; }
        public long getTotalReusedObjects() { return totalReusedObjects; }
        public double getReuseRate() { return reuseRate; }
        public long getTotalMemorySaved() { return totalMemorySaved; }
        public long getTotalGarbageCollections() { return totalGarbageCollections; }
        public long getTotalGcTime() { return totalGcTime; }
        public long getMaxGcTime() { return maxGcTime; }
        public int getObjectPoolCount() { return objectPoolCount; }
    }
    
    /**
     * 对象池
     */
    private static class ObjectPool<T> {
        private final Class<T> objectClass;
        private final ObjectFactory<T> factory;
        private final int maxSize;
        private final int minSize;
        private final long reuseTimeout;
        
        private final Queue<T> availableObjects;
        private final Set<T> borrowedObjects;
        private final AtomicInteger totalCreated = new AtomicInteger(0);
        private final AtomicInteger totalReused = new AtomicInteger(0);
        private final AtomicLong lastAccessTime = new AtomicLong(System.currentTimeMillis());
        
        public ObjectPool(Class<T> objectClass, ObjectFactory<T> factory, int maxSize, int minSize, long reuseTimeout) {
            this.objectClass = objectClass;
            this.factory = factory;
            this.maxSize = maxSize;
            this.minSize = minSize;
            this.reuseTimeout = reuseTimeout;
            this.availableObjects = new ConcurrentLinkedQueue<>();
            this.borrowedObjects = ConcurrentHashMap.newKeySet();
            
            // 预创建最小数量的对象
            precreateObjects();
        }
        
        private void precreateObjects() {
            for (int i = 0; i < minSize; i++) {
                T object = createObject();
                if (object != null) {
                    availableObjects.offer(object);
                }
            }
        }
        
        private T createObject() {
            try {
                totalCreated.incrementAndGet();
                return factory.create();
            } catch (Exception e) {
                return null;
            }
        }
        
        public T acquire() {
            lastAccessTime.set(System.currentTimeMillis());
            
            T object = availableObjects.poll();
            if (object != null) {
                borrowedObjects.add(object);
                return object;
            }
            
            // 如果池中没有可用对象且未达到最大大小，创建新对象
            if (totalCreated.get() < maxSize) {
                object = createObject();
                if (object != null) {
                    borrowedObjects.add(object);
                    return object;
                }
            }
            
            return null;
        }
        
        public void release(T object) {
            if (object == null || !borrowedObjects.remove(object)) {
                return;
            }
            
            // 清理对象状态
            if (object instanceof AutoCloseable) {
                try {
                    ((AutoCloseable) object).close();
                } catch (Exception e) {
                    // 忽略清理异常
                }
            }
            
            availableObjects.offer(object);
            totalReused.incrementAndGet();
        }
        
        public boolean canReuse(T object) {
            return object != null && !borrowedObjects.contains(object);
        }
        
        public void adjustSize() {
            // 根据使用情况调整池大小
            int currentSize = availableObjects.size() + borrowedObjects.size();
            long currentTime = System.currentTimeMillis();
            
            // 如果长时间未使用且池过大，可以缩减
            if (currentSize > minSize && 
                lastAccessTime.get() < currentTime - 600000) { // 10分钟未使用
                synchronized (this) {
                    while (availableObjects.size() > minSize) {
                        T object = availableObjects.poll();
                        if (object != null) {
                            // 清理对象
                            if (object instanceof AutoCloseable) {
                                try {
                                    ((AutoCloseable) object).close();
                                } catch (Exception e) {
                                    // 忽略
                                }
                            }
                        }
                    }
                }
            }
        }
        
        public void shutdown() {
            // 清理所有对象
            for (T object : availableObjects) {
                if (object instanceof AutoCloseable) {
                    try {
                        ((AutoCloseable) object).close();
                    } catch (Exception e) {
                        // 忽略
                    }
                }
            }
            availableObjects.clear();
            
            for (T object : borrowedObjects) {
                if (object instanceof AutoCloseable) {
                    try {
                        ((AutoCloseable) object).close();
                    } catch (Exception e) {
                        // 忽略
                    }
                }
            }
            borrowedObjects.clear();
        }
        
        public long getLastAccessTime() { return lastAccessTime.get(); }
        public int getTotalCreated() { return totalCreated.get(); }
        public int getAvailableCount() { return availableObjects.size(); }
    }
    
    /**
     * 对象工厂接口
     */
    public interface ObjectFactory<T> {
        T create() throws Exception;
    }
    
    /**
     * 反射对象工厂
     */
    private static class ReflectiveObjectFactory<T> implements ObjectFactory<T> {
        private final Class<T> objectClass;
        
        public ReflectiveObjectFactory(Class<T> objectClass) {
            this.objectClass = objectClass;
        }
        
        @Override
        public T create() throws Exception {
            return objectClass.getDeclaredConstructor().newInstance();
        }
    }
    
    /**
     * 内存监控
     */
    private class MemoryMonitor {
        private volatile boolean running = false;
        
        public void start() {
            running = true;
            logger.info("内存监控已启动");
        }
        
        public void stop() {
            running = false;
            logger.info("内存监控已停止");
        }
        
        public boolean isRunning() {
            return running;
        }
    }
}