package com.mycorp.distributedlock.core.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;

public class LockTracing {
    
    private final Tracer tracer;
    private final boolean enabled;
    
    public LockTracing(OpenTelemetry openTelemetry, boolean enabled) {
        this.tracer = enabled && openTelemetry != null 
            ? openTelemetry.getTracer("distributed-lock") 
            : null;
        this.enabled = enabled;
    }
    
    public SpanContext startLockAcquisitionSpan(String lockName, String operation) {
        if (!enabled || tracer == null) {
            return new NoOpSpanContext();
        }
        
        SpanBuilder spanBuilder = tracer.spanBuilder("lock." + operation)
            .setAttribute("lock.name", lockName)
            .setAttribute("lock.operation", operation);
            
        Span span = spanBuilder.startSpan();
        Scope scope = span.makeCurrent();
        
        return new TracingSpanContext(span, scope);
    }
    
    public interface SpanContext extends AutoCloseable {
        void setStatus(String status);
        void setError(Throwable throwable);
        @Override
        void close();
    }
    
    private static class TracingSpanContext implements SpanContext {
        private final Span span;
        private final Scope scope;
        
        public TracingSpanContext(Span span, Scope scope) {
            this.span = span;
            this.scope = scope;
        }
        
        @Override
        public void setStatus(String status) {
            span.setAttribute("lock.status", status);
        }
        
        @Override
        public void setError(Throwable throwable) {
            span.recordException(throwable);
            span.setAttribute("lock.status", "error");
        }
        
        @Override
        public void close() {
            try {
                scope.close();
            } finally {
                span.end();
            }
        }
    }
    
    private static class NoOpSpanContext implements SpanContext {
        @Override
        public void setStatus(String status) {
        }
        
        @Override
        public void setError(Throwable throwable) {
        }
        
        @Override
        public void close() {
        }
    }
}