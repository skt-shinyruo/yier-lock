package com.mycorp.distributedlock.springboot.key;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.ParserContext;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import java.lang.reflect.Method;

public final class SpelLockKeyResolver implements LockKeyResolver {

    private static final ParserContext TEMPLATE_CONTEXT = ParserContext.TEMPLATE_EXPRESSION;

    private final ExpressionParser parser = new SpelExpressionParser();
    private final ParameterNameDiscoverer parameterNameDiscoverer;
    private final java.util.concurrent.ConcurrentMap<ExpressionKey, org.springframework.expression.Expression> expressionCache = new java.util.concurrent.ConcurrentHashMap<>();

    public SpelLockKeyResolver() {
        this(new DefaultParameterNameDiscoverer());
    }

    SpelLockKeyResolver(ParameterNameDiscoverer parameterNameDiscoverer) {
        this.parameterNameDiscoverer = parameterNameDiscoverer;
    }

    private record ExpressionKey(Method method, String expression) {
    }

    @Override
    public String resolveKey(ProceedingJoinPoint joinPoint, String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("Lock key expression cannot be blank");
        }
        if (!expression.contains("#{")) {
            return expression;
        }

        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        Object[] args = joinPoint.getArgs();
        String[] parameterNames = parameterNameDiscoverer.getParameterNames(method);
        if (parameterNames == null) {
            parameterNames = signature.getParameterNames();
        }

        StandardEvaluationContext context = new StandardEvaluationContext();
        context.setVariable("args", args);
        context.setVariable("method", method);
        context.setVariable("target", joinPoint.getTarget());
        context.setVariable("targetClass", joinPoint.getTarget() == null ? method.getDeclaringClass() : joinPoint.getTarget().getClass());
        for (int index = 0; index < args.length; index++) {
            context.setVariable("p" + index, args[index]);
            context.setVariable("a" + index, args[index]);
            if (parameterNames != null && index < parameterNames.length) {
                context.setVariable(parameterNames[index], args[index]);
            }
        }

        Object value = expressionCache.computeIfAbsent(new ExpressionKey(method, expression), key ->
            parser.parseExpression(key.expression(), TEMPLATE_CONTEXT)
        ).getValue(context);
        if (value == null) {
            throw new IllegalArgumentException("Lock key expression resolved to null: " + expression + " on " + method);
        }
        String key = value.toString();
        if (key.isBlank()) {
            throw new IllegalArgumentException("Lock key expression resolved to blank: " + expression + " on " + method);
        }
        return key;
    }
}
