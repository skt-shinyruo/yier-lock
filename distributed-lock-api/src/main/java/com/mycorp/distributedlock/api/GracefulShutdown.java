package com.mycorp.distributedlock.api;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 优雅关闭接口
 * 提供分布式锁系统的优雅关闭能力，确保所有操作安全完成
 */
public interface GracefulShutdown {

    /**
     * 执行优雅关闭
     * 
     * @param timeout 超时时间
     * @param unit 时间单位
     * @return 关闭结果
     */
    ShutdownResult gracefulShutdown(long timeout, TimeUnit unit);

    /**
     * 异步执行优雅关闭
     * 
     * @param timeout 超时时间
     * @param unit 时间单位
     * @return 异步关闭结果
     */
    CompletableFuture<ShutdownResult> gracefulShutdownAsync(long timeout, TimeUnit unit);

    /**
     * 注册关闭回调
     * 
     * @param callback 关闭回调
     */
    void registerShutdownCallback(ShutdownCallback callback);

    /**
     * 注销关闭回调
     * 
     * @param callback 关闭回调
     */
    void unregisterShutdownCallback(ShutdownCallback callback);

    /**
     * 检查是否正在关闭
     * 
     * @return 是否正在关闭
     */
    boolean isShuttingDown();

    /**
     * 检查是否已关闭
     * 
     * @return 是否已关闭
     */
    boolean isShutdown();

    /**
     * 获取关闭进度
     * 
     * @return 关闭进度信息
     */
    ShutdownProgress getShutdownProgress();

    /**
     * 强制关闭
     * 
     * @param timeout 超时时间
     * @param unit 时间单位
     * @return 是否成功强制关闭
     */
    boolean forceShutdown(long timeout, TimeUnit unit);

    /**
     * 暂停新的锁操作
     */
    void pauseNewLockOperations();

    /**
     * 恢复新的锁操作
     */
    void resumeNewLockOperations();

    /**
     * 获取等待关闭的任务列表
     * 
     * @return 等待关闭的任务列表
     */
    java.util.List<ShutdownTask> getPendingTasks();

    /**
     * 取消指定的关闭任务
     * 
     * @param taskId 任务ID
     * @return 是否成功取消
     */
    boolean cancelShutdownTask(String taskId);

    /**
     * 设置关闭钩子
     * 
     * @param hook 关闭钩子
     */
    void setShutdownHook(Consumer<GracefulShutdown> hook);

    /**
     * 关闭结果
     */
    interface ShutdownResult {
        /**
         * 是否成功关闭
         */
        boolean isSuccess();

        /**
         * 获取关闭状态
         */
        ShutdownStatus getStatus();

        /**
         * 获取关闭时间
         */
        long getShutdownTime();

        /**
         * 获取处理的关闭任务数
         */
        int getProcessedTasks();

        /**
         * 获取跳过的任务数
         */
        int getSkippedTasks();

        /**
         * 获取失败的任务数
         */
        int getFailedTasks();

        /**
         * 获取错误信息
         */
        String getErrorMessage();

        /**
         * 获取详细报告
         */
        String getDetailedReport();
    }

    /**
     * 关闭进度信息
     */
    interface ShutdownProgress {
        /**
         * 获取关闭进度百分比
         */
        double getProgressPercentage();

        /**
         * 获取当前阶段
         */
        ShutdownPhase getCurrentPhase();

        /**
         * 获取已处理的任务数
         */
        int getProcessedTasks();

        /**
         * 获取剩余任务数
         */
        int getRemainingTasks();

        /**
         * 获取预计剩余时间
         */
        long getEstimatedRemainingTime();

        /**
         * 获取开始时间
         */
        long getStartTime();

        /**
         * 获取当前时间
         */
        long getCurrentTime();
    }

    /**
     * 关闭状态枚举
     */
    enum ShutdownStatus {
        /** 未开始 */
        NOT_STARTED,
        /** 进行中 */
        IN_PROGRESS,
        /** 成功完成 */
        SUCCESS,
        /** 超时 */
        TIMEOUT,
        /** 失败 */
        FAILED,
        /** 强制关闭 */
        FORCED
    }

    /**
     * 关闭阶段枚举
     */
    enum ShutdownPhase {
        /** 初始化 */
        INITIALIZING,
        /** 暂停新操作 */
        PAUSING_NEW_OPERATIONS,
        /** 等待活跃任务 */
        WAITING_ACTIVE_TASKS,
        /** 处理关闭回调 */
        PROCESSING_CALLBACKS,
        /** 清理资源 */
        CLEANING_RESOURCES,
        /** 完成 */
        COMPLETING,
        /** 强制关闭 */
        FORCED_SHUTDOWN
    }

    /**
     * 关闭任务信息
     */
    interface ShutdownTask {
        /**
         * 获取任务ID
         */
        String getTaskId();

        /**
         * 获取任务名称
         */
        String getTaskName();

        /**
         * 获取任务描述
         */
        String getDescription();

        /**
         * 获取任务优先级
         */
        int getPriority();

        /**
         * 获取超时时间
         */
        long getTimeout();

        /**
         * 获取超时时间单位
         */
        TimeUnit getTimeoutUnit();

        /**
         * 获取创建时间
         */
        long getCreationTime();

        /**
         * 获取任务状态
         */
        ShutdownTaskStatus getStatus();

        /**
         * 是否可强制执行
         */
        boolean isForceable();

        /**
         * 获取失败原因
         */
        String getFailureReason();

        /**
         * 执行任务
         */
        CompletableFuture<Void> execute();
    }

    /**
     * 关闭任务状态枚举
     */
    enum ShutdownTaskStatus {
        /** 等待中 */
        PENDING,
        /** 执行中 */
        RUNNING,
        /** 完成 */
        COMPLETED,
        /** 失败 */
        FAILED,
        /** 跳过 */
        SKIPPED,
        /** 取消 */
        CANCELLED
    }

    /**
     * 关闭回调接口
     */
    @FunctionalInterface
    interface ShutdownCallback {
        /**
         * 执行关闭回调
         * 
         * @param context 关闭上下文
         * @return 异步执行结果
         */
        CompletableFuture<Void> onShutdown(ShutdownContext context);
    }

    /**
     * 关闭上下文
     */
    interface ShutdownContext {
        /**
         * 获取关闭原因
         */
        ShutdownReason getReason();

        /**
         * 获取关闭时间
         */
        long getShutdownTime();

        /**
         * 是否为强制关闭
         */
        boolean isForced();

        /**
         * 获取超时时间
         */
        long getTimeout();

        /**
         * 获取超时时间单位
         */
        TimeUnit getTimeoutUnit();

        /**
         * 获取已用时间
         */
        long getElapsedTime();

        /**
         * 获取剩余时间
         */
        long getRemainingTime();

        /**
         * 获取关闭阶段
         */
        ShutdownPhase getPhase();
    }

    /**
     * 关闭原因枚举
     */
    enum ShutdownReason {
        /** 正常关闭 */
        NORMAL,
        /** 系统关闭 */
        SYSTEM,
        /** 应用关闭 */
        APPLICATION,
        /** 错误关闭 */
        ERROR,
        /** 用户请求 */
        USER_REQUEST
    }

    /**
     * 默认关闭结果实现
     */
    class DefaultShutdownResult implements ShutdownResult {
        private final boolean success;
        private final ShutdownStatus status;
        private final long shutdownTime;
        private final int processedTasks;
        private final int skippedTasks;
        private final int failedTasks;
        private final String errorMessage;
        private final String detailedReport;

        public DefaultShutdownResult(boolean success, ShutdownStatus status, long shutdownTime,
                                   int processedTasks, int skippedTasks, int failedTasks,
                                   String errorMessage, String detailedReport) {
            this.success = success;
            this.status = status;
            this.shutdownTime = shutdownTime;
            this.processedTasks = processedTasks;
            this.skippedTasks = skippedTasks;
            this.failedTasks = failedTasks;
            this.errorMessage = errorMessage;
            this.detailedReport = detailedReport;
        }

        @Override
        public boolean isSuccess() {
            return success;
        }

        @Override
        public ShutdownStatus getStatus() {
            return status;
        }

        @Override
        public long getShutdownTime() {
            return shutdownTime;
        }

        @Override
        public int getProcessedTasks() {
            return processedTasks;
        }

        @Override
        public int getSkippedTasks() {
            return skippedTasks;
        }

        @Override
        public int getFailedTasks() {
            return failedTasks;
        }

        @Override
        public String getErrorMessage() {
            return errorMessage;
        }

        @Override
        public String getDetailedReport() {
            return detailedReport;
        }
    }

    /**
     * 默认关闭进度实现
     */
    class DefaultShutdownProgress implements ShutdownProgress {
        private final double progressPercentage;
        private final ShutdownPhase currentPhase;
        private final int processedTasks;
        private final int remainingTasks;
        private final long estimatedRemainingTime;
        private final long startTime;
        private final long currentTime;

        public DefaultShutdownProgress(double progressPercentage, ShutdownPhase currentPhase,
                                     int processedTasks, int remainingTasks,
                                     long estimatedRemainingTime, long startTime, long currentTime) {
            this.progressPercentage = progressPercentage;
            this.currentPhase = currentPhase;
            this.processedTasks = processedTasks;
            this.remainingTasks = remainingTasks;
            this.estimatedRemainingTime = estimatedRemainingTime;
            this.startTime = startTime;
            this.currentTime = currentTime;
        }

        @Override
        public double getProgressPercentage() {
            return progressPercentage;
        }

        @Override
        public ShutdownPhase getCurrentPhase() {
            return currentPhase;
        }

        @Override
        public int getProcessedTasks() {
            return processedTasks;
        }

        @Override
        public int getRemainingTasks() {
            return remainingTasks;
        }

        @Override
        public long getEstimatedRemainingTime() {
            return estimatedRemainingTime;
        }

        @Override
        public long getStartTime() {
            return startTime;
        }

        @Override
        public long getCurrentTime() {
            return currentTime;
        }
    }
}