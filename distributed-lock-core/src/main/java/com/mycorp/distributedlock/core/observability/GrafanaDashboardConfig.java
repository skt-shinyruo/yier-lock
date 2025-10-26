package com.mycorp.distributedlock.core.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Grafana仪表板配置
 * 提供分布式锁系统的Grafana监控仪表板配置
 */
public class GrafanaDashboardConfig {
    
    private static final Logger logger = LoggerFactory.getLogger(GrafanaDashboardConfig.class);
    
    private final DashboardConfig config;
    private final Map<String, DashboardPanel> panels;
    private final Map<String, String> globalVariables;
    
    public GrafanaDashboardConfig() {
        this(new DashboardConfig());
    }
    
    public GrafanaDashboardConfig(DashboardConfig config) {
        this.config = config != null ? config : new DashboardConfig();
        this.panels = new ConcurrentHashMap<>();
        this.globalVariables = new ConcurrentHashMap<>();
        
        initializeDefaultPanels();
        initializeGlobalVariables();
        
        logger.info("GrafanaDashboardConfig initialized");
    }
    
    /**
     * 生成完整仪表板配置
     */
    public Dashboard generateDashboard() {
        return new Dashboard(
            config.getDashboardTitle(),
            config.getDashboardUid(),
            config.getTags(),
            generatePanels(),
            generateAnnotations(),
            globalVariables,
            config.getRefreshInterval(),
            config.getTimeRange()
        );
    }
    
    /**
     * 生成Prometheus查询配置
     */
    public String generatePrometheusQueries() {
        StringBuilder queries = new StringBuilder();
        queries.append("# 分布式锁Prometheus查询配置\n\n");
        
        queries.append("# 总体指标\n");
        queries.append("lock_operations_total\n");
        queries.append("lock_operations_success_total\n");
        queries.append("lock_operations_failed_total\n");
        queries.append("lock_concurrency_current\n");
        queries.append("lock_concurrency_peak\n\n");
        
        queries.append("# 操作性能指标\n");
        queries.append("lock_operation_duration_seconds{quantile=\"0.5\"}\n");
        queries.append("lock_operation_duration_seconds{quantile=\"0.95\"}\n");
        queries.append("lock_operation_duration_seconds{quantile=\"0.99\"}\n\n");
        
        queries.append("# 错误率指标\n");
        queries.append("rate(lock_operations_failed_total[5m]) / rate(lock_operations_total[5m])\n\n");
        
        queries.append("# 监控告警指标\n");
        queries.append("lock_alerts_total\n");
        queries.append("lock_alerts_triggered_total\n");
        queries.append("lock_health_overall_status\n\n");
        
        return queries.toString();
    }
    
    /**
     * 生成仪表板JSON配置
     */
    public String generateDashboardJson() {
        Dashboard dashboard = generateDashboard();
        return convertToJson(dashboard);
    }
    
    /**
     * 添加自定义面板
     */
    public void addPanel(String panelId, DashboardPanel panel) {
        panels.put(panelId, panel);
        logger.info("Added custom panel: {}", panelId);
    }
    
    /**
     * 移除面板
     */
    public void removePanel(String panelId) {
        panels.remove(panelId);
        logger.info("Removed panel: {}", panelId);
    }
    
    /**
     * 更新全局变量
     */
    public void updateGlobalVariable(String name, String value) {
        globalVariables.put(name, value);
        logger.info("Updated global variable: {} = {}", name, value);
    }
    
    /**
     * 获取配置信息
     */
    public DashboardConfig getConfig() {
        return config;
    }
    
    // 私有方法
    
    private void initializeDefaultPanels() {
        // 总体概览面板
        panels.put("overview", new DashboardPanel("总体概览", PanelType.STAT, 0, 0, 12, 4)
            .addQuery("lock_operations_total", "总操作数")
            .addQuery("lock_operations_success_total", "成功操作数")
            .addQuery("lock_concurrency_current", "当前并发数"));
            
        // 性能指标面板
        panels.put("performance", new DashboardPanel("性能指标", PanelType.GRAPH, 0, 4, 12, 6)
            .addQuery("rate(lock_operations_total[5m])", "操作速率")
            .addQuery("lock_operation_duration_seconds{quantile=\"0.95\"}", "95分位响应时间")
            .addQuery("lock_operation_duration_seconds{quantile=\"0.99\"}", "99分位响应时间"));
            
        // 错误率面板
        panels.put("errors", new DashboardPanel("错误率", PanelType.GRAPH, 0, 10, 6, 6)
            .addQuery("rate(lock_operations_failed_total[5m]) / rate(lock_operations_total[5m])", "错误率")
            .addQuery("lock_operations_failed_total", "错误总数"));
            
        // 并发数面板
        panels.put("concurrency", new DashboardPanel("并发数", PanelType.GRAPH, 6, 10, 6, 6)
            .addQuery("lock_concurrency_current", "当前并发数")
            .addQuery("lock_concurrency_peak", "峰值并发数"));
            
        // 锁级指标面板
        panels.put("locks", new DashboardPanel("锁级指标", PanelType.TABLE, 0, 16, 12, 8)
            .addQuery("lock_acquisition_count", "获取次数")
            .addQuery("lock_acquisition_time", "获取时间")
            .addQuery("lock_hold_time", "持有时间"));
            
        // 健康状态面板
        panels.put("health", new DashboardPanel("健康状态", PanelType.STAT, 0, 24, 12, 4)
            .addQuery("lock_health_overall_status", "整体健康状态"));
            
        // 告警面板
        panels.put("alerts", new DashboardPanel("告警", PanelType.GRAPH, 0, 28, 12, 6)
            .addQuery("lock_alerts_total", "告警总数")
            .addQuery("rate(lock_alerts_triggered_total[5m])", "告警触发率"));
    }
    
    private void initializeGlobalVariables() {
        globalVariables.put("instance", "label_values(instance)");
        globalVariables.put("application", "label_values(application)");
        globalVariables.put("lock_name", "label_values(lock_name)");
        globalVariables.put("time_range", "5m");
    }
    
    private List<DashboardPanel> generatePanels() {
        return new ArrayList<>(panels.values());
    }
    
    private List<DashboardAnnotation> generateAnnotations() {
        List<DashboardAnnotation> annotations = new ArrayList<>();
        
        // 部署事件注释
        annotations.add(new DashboardAnnotation("deployments", "部署事件", "deployments"));
        
        // 告警事件注释
        annotations.add(new DashboardAnnotation("alerts", "告警事件", "alerts"));
        
        return annotations;
    }
    
    private String convertToJson(Dashboard dashboard) {
        // 简化实现，实际应该使用JSON序列化库
        return String.format(
            "{\n" +
            "  \"title\": \"%s\",\n" +
            "  \"uid\": \"%s\",\n" +
            "  \"tags\": %s,\n" +
            "  \"refresh\": \"%s\",\n" +
            "  \"time\": %s,\n" +
            "  \"panels\": %d panels\n" +
            "}",
            dashboard.getTitle(),
            dashboard.getUid(),
            Arrays.toString(dashboard.getTags()),
            dashboard.getRefreshInterval(),
            dashboard.getTimeRange(),
            dashboard.getPanels().size()
        );
    }
    
    // 内部类
    
    /**
     * 仪表板配置
     */
    public static class DashboardConfig {
        private String dashboardTitle = "分布式锁监控仪表板";
        private String dashboardUid = "distributed-lock-dashboard";
        private String[] tags = {"distributed-lock", "microservices", "monitoring"};
        private String refreshInterval = "30s";
        private String timeRange = "1h";
        private boolean enableAnnotations = true;
        private boolean enableVariables = true;
        private boolean enableAlerts = true;
        
        // Getters and setters
        public String getDashboardTitle() { return dashboardTitle; }
        public void setDashboardTitle(String dashboardTitle) { 
            this.dashboardTitle = dashboardTitle; 
        }
        
        public String getDashboardUid() { return dashboardUid; }
        public void setDashboardUid(String dashboardUid) { 
            this.dashboardUid = dashboardUid; 
        }
        
        public String[] getTags() { return tags; }
        public void setTags(String[] tags) { 
            this.tags = tags; 
        }
        
        public String getRefreshInterval() { return refreshInterval; }
        public void setRefreshInterval(String refreshInterval) { 
            this.refreshInterval = refreshInterval; 
        }
        
        public String getTimeRange() { return timeRange; }
        public void setTimeRange(String timeRange) { 
            this.timeRange = timeRange; 
        }
        
        public boolean isEnableAnnotations() { return enableAnnotations; }
        public void setEnableAnnotations(boolean enableAnnotations) { 
            this.enableAnnotations = enableAnnotations; 
        }
        
        public boolean isEnableVariables() { return enableVariables; }
        public void setEnableVariables(boolean enableVariables) { 
            this.enableVariables = enableVariables; 
        }
        
        public boolean isEnableAlerts() { return enableAlerts; }
        public void setEnableAlerts(boolean enableAlerts) { 
            this.enableAlerts = enableAlerts; 
        }
    }
    
    /**
     * 仪表板
     */
    public static class Dashboard {
        private final String title;
        private final String uid;
        private final String[] tags;
        private final List<DashboardPanel> panels;
        private final List<DashboardAnnotation> annotations;
        private final Map<String, String> variables;
        private final String refreshInterval;
        private final String timeRange;
        
        public Dashboard(String title, String uid, String[] tags,
                       List<DashboardPanel> panels, List<DashboardAnnotation> annotations,
                       Map<String, String> variables, String refreshInterval, String timeRange) {
            this.title = title;
            this.uid = uid;
            this.tags = tags;
            this.panels = panels;
            this.annotations = annotations;
            this.variables = variables;
            this.refreshInterval = refreshInterval;
            this.timeRange = timeRange;
        }
        
        public String getTitle() { return title; }
        public String getUid() { return uid; }
        public String[] getTags() { return tags; }
        public List<DashboardPanel> getPanels() { return panels; }
        public List<DashboardAnnotation> getAnnotations() { return annotations; }
        public Map<String, String> getVariables() { return variables; }
        public String getRefreshInterval() { return refreshInterval; }
        public String getTimeRange() { return timeRange; }
    }
    
    /**
     * 仪表板面板
     */
    public static class DashboardPanel {
        private final String title;
        private final PanelType type;
        private final int x;
        private final int y;
        private final int width;
        private final int height;
        private final List<PanelQuery> queries;
        private final Map<String, String> options;
        
        public DashboardPanel(String title, PanelType type, int x, int y, int width, int height) {
            this.title = title;
            this.type = type;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.queries = new ArrayList<>();
            this.options = new HashMap<>();
        }
        
        public DashboardPanel addQuery(String expression, String legend) {
            queries.add(new PanelQuery(expression, legend));
            return this;
        }
        
        public DashboardPanel addOption(String key, String value) {
            options.put(key, value);
            return this;
        }
        
        public String getTitle() { return title; }
        public PanelType getType() { return type; }
        public int getX() { return x; }
        public int getY() { return y; }
        public int getWidth() { return width; }
        public int getHeight() { return height; }
        public List<PanelQuery> getQueries() { return queries; }
        public Map<String, String> getOptions() { return options; }
    }
    
    /**
     * 面板类型枚举
     */
    public enum PanelType {
        GRAPH, STAT, TABLE, GAUGE, HEATMAP
    }
    
    /**
     * 面板查询
     */
    public static class PanelQuery {
        private final String expression;
        private final String legend;
        
        public PanelQuery(String expression, String legend) {
            this.expression = expression;
            this.legend = legend;
        }
        
        public String getExpression() { return expression; }
        public String getLegend() { return legend; }
    }
    
    /**
     * 仪表板注释
     */
    public static class DashboardAnnotation {
        private final String name;
        private final String title;
        private final String datasource;
        
        public DashboardAnnotation(String name, String title, String datasource) {
            this.name = name;
            this.title = title;
            this.datasource = datasource;
        }
        
        public String getName() { return name; }
        public String getTitle() { return title; }
        public String getDatasource() { return datasource; }
    }
}