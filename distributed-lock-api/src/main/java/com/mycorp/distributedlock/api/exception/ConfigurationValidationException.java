package com.mycorp.distributedlock.api.exception;

import java.util.List;
import java.util.Map;

/**
 * 配置验证异常
 * 用于处理分布式锁配置验证失败的场景
 */
public class ConfigurationValidationException extends DistributedLockException {

    private final List<ValidationError> validationErrors;
    private final String configurationSource;
    private final String configurationType;

    // 保持向后兼容性的基础构造函数
    public ConfigurationValidationException(String message) {
        this(message, (Throwable) null, (List<ValidationError>) null, null, null);
    }

    public ConfigurationValidationException(String message, Throwable cause) {
        this(message, cause, (List<ValidationError>) null, null, null);
    }

    // 增强的构造函数
    public ConfigurationValidationException(String message, List<ValidationError> validationErrors) {
        this(message, null, validationErrors, null, null);
    }

    public ConfigurationValidationException(String message, List<ValidationError> validationErrors,
                                          String configurationSource) {
        this(message, null, validationErrors, configurationSource, null);
    }

    public ConfigurationValidationException(String message, List<ValidationError> validationErrors,
                                          String configurationSource, String configurationType) {
        this(message, null, validationErrors, configurationSource, configurationType);
    }

    public ConfigurationValidationException(String message, Throwable cause,
                                          List<ValidationError> validationErrors,
                                          String configurationSource, String configurationType) {
        this(message, cause, validationErrors, configurationSource, configurationType, null);
    }

    public ConfigurationValidationException(String message, Throwable cause,
                                          List<ValidationError> validationErrors,
                                          String configurationSource, String configurationType,
                                          String recommendation) {
        super(message, cause, null, ErrorCode.CONFIGURATION_ERROR, Map.of(
                "validationErrors", validationErrors != null ? List.copyOf(validationErrors) : List.of(),
                "configurationSource", configurationSource,
                "configurationType", configurationType
        ), recommendation);
        this.validationErrors = validationErrors != null ? List.copyOf(validationErrors) : List.of();
        this.configurationSource = configurationSource;
        this.configurationType = configurationType;
    }

    /**
     * 获取验证错误列表
     */
    public List<ValidationError> getValidationErrors() {
        return validationErrors;
    }

    /**
     * 获取配置来源
     */
    public String getConfigurationSource() {
        return configurationSource;
    }

    /**
     * 获取配置类型
     */
    public String getConfigurationType() {
        return configurationType;
    }

    /**
     * 检查是否有严重错误
     */
    public boolean hasCriticalErrors() {
        return validationErrors.stream()
                .anyMatch(error -> error.getSeverity() == ValidationSeverity.CRITICAL);
    }

    /**
     * 获取错误统计
     */
    public Map<ValidationSeverity, Long> getErrorStatistics() {
        return validationErrors.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        ValidationError::getSeverity,
                        java.util.stream.Collectors.counting()
                ));
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("ConfigurationValidationException{");
        sb.append("message='").append(getMessage()).append("'");
        if (configurationSource != null) {
            sb.append(", configurationSource='").append(configurationSource).append("'");
        }
        if (configurationType != null) {
            sb.append(", configurationType='").append(configurationType).append("'");
        }
        sb.append(", validationErrors=").append(validationErrors.size()).append(" errors");
        sb.append("}");
        return sb.toString();
    }

    /**
     * 验证错误
     */
    public interface ValidationError {
        /**
         * 获取错误消息
         */
        String getMessage();

        /**
         * 获取错误字段
         */
        String getField();

        /**
         * 获取错误值
         */
        Object getInvalidValue();

        /**
         * 获取错误严重程度
         */
        ValidationSeverity getSeverity();

        /**
         * 获取错误代码
         */
        String getErrorCode();

        /**
         * 获取建议
         */
        String getSuggestion();

        /**
         * 获取错误类别
         */
        ValidationCategory getCategory();
    }

    /**
     * 验证严重程度枚举
     */
    public enum ValidationSeverity {
        /** 信息 */
        INFO,
        /** 警告 */
        WARNING,
        /** 错误 */
        ERROR,
        /** 严重 */
        CRITICAL
    }

    /**
     * 验证类别枚举
     */
    public enum ValidationCategory {
        /** 必需字段缺失 */
        REQUIRED_FIELD_MISSING,
        /** 类型不匹配 */
        TYPE_MISMATCH,
        /** 数值范围错误 */
        VALUE_OUT_OF_RANGE,
        /** 格式错误 */
        FORMAT_ERROR,
        /** 依赖关系错误 */
        DEPENDENCY_ERROR,
        /** 权限错误 */
        PERMISSION_ERROR,
        /** 资源不足 */
        INSUFFICIENT_RESOURCES,
        /** 网络连接错误 */
        NETWORK_CONNECTION_ERROR,
        /** 配置冲突 */
        CONFIGURATION_CONFLICT,
        /** 不支持的值 */
        UNSUPPORTED_VALUE
    }

    /**
     * 默认验证错误实现
     */
    public static class DefaultValidationError implements ValidationError {
        private final String message;
        private final String field;
        private final Object invalidValue;
        private final ValidationSeverity severity;
        private final String errorCode;
        private final String suggestion;
        private final ValidationCategory category;

        public DefaultValidationError(String message, String field, Object invalidValue,
                                    ValidationSeverity severity, String errorCode,
                                    String suggestion, ValidationCategory category) {
            this.message = message;
            this.field = field;
            this.invalidValue = invalidValue;
            this.severity = severity;
            this.errorCode = errorCode;
            this.suggestion = suggestion;
            this.category = category;
        }

        @Override
        public String getMessage() {
            return message;
        }

        @Override
        public String getField() {
            return field;
        }

        @Override
        public Object getInvalidValue() {
            return invalidValue;
        }

        @Override
        public ValidationSeverity getSeverity() {
            return severity;
        }

        @Override
        public String getErrorCode() {
            return errorCode;
        }

        @Override
        public String getSuggestion() {
            return suggestion;
        }

        @Override
        public ValidationCategory getCategory() {
            return category;
        }

        @Override
        public String toString() {
            return "ValidationError{" +
                    "field='" + field + '\'' +
                    ", severity=" + severity +
                    ", message='" + message + '\'' +
                    ", suggestion='" + suggestion + '\'' +
                    '}';
        }
    }

    /**
     * 异常构建器
     */
    public static class Builder {
        private String message;
        private Throwable cause;
        private List<ValidationError> validationErrors;
        private String configurationSource;
        private String configurationType;
        private String recommendation;

        public Builder message(String message) {
            this.message = message;
            return this;
        }

        public Builder cause(Throwable cause) {
            this.cause = cause;
            return this;
        }

        public Builder validationErrors(List<ValidationError> validationErrors) {
            this.validationErrors = validationErrors;
            return this;
        }

        public Builder configurationSource(String configurationSource) {
            this.configurationSource = configurationSource;
            return this;
        }

        public Builder configurationType(String configurationType) {
            this.configurationType = configurationType;
            return this;
        }

        public Builder recommendation(String recommendation) {
            this.recommendation = recommendation;
            return this;
        }

        public ConfigurationValidationException build() {
            return new ConfigurationValidationException(message, cause, validationErrors,
                                                      configurationSource, configurationType, recommendation);
        }

        /**
         * 添加验证错误
         */
        public Builder addValidationError(String field, String message, ValidationSeverity severity) {
            return addValidationError(field, null, message, severity, null, null);
        }

        /**
         * 添加验证错误
         */
        public Builder addValidationError(String field, Object invalidValue, String message,
                                        ValidationSeverity severity) {
            return addValidationError(field, invalidValue, message, severity, null, null);
        }

        /**
         * 添加验证错误
         */
        public Builder addValidationError(String field, Object invalidValue, String message,
                                        ValidationSeverity severity, String errorCode,
                                        String suggestion) {
            return addValidationError(field, invalidValue, message, severity, errorCode,
                                    suggestion, ValidationCategory.FORMAT_ERROR);
        }

        /**
         * 添加验证错误
         */
        public Builder addValidationError(String field, Object invalidValue, String message,
                                        ValidationSeverity severity, String errorCode,
                                        String suggestion, ValidationCategory category) {
            if (validationErrors == null) {
                validationErrors = new java.util.ArrayList<>();
            }
            validationErrors.add(new DefaultValidationError(message, field, invalidValue, severity,
                                                           errorCode, suggestion, category));
            return this;
        }
    }
}