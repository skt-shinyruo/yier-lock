package com.mycorp.distributedlock.springboot.key;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import java.lang.reflect.Method;

public final class SpelLockKeyResolver implements LockKeyResolver {

    private final ExpressionParser parser = new SpelExpressionParser();
    private final ParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();

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

        StandardEvaluationContext context = new StandardEvaluationContext();
        context.setVariable("args", args);
        for (int index = 0; index < args.length; index++) {
            context.setVariable("p" + index, args[index]);
            context.setVariable("a" + index, args[index]);
            if (parameterNames != null && index < parameterNames.length) {
                context.setVariable(parameterNames[index], args[index]);
            }
        }

        String evaluated = expression;
        while (evaluated.contains("#{")) {
            int start = evaluated.indexOf("#{");
            int end = evaluated.indexOf('}', start);
            if (end < 0) {
                throw new IllegalArgumentException("Invalid lock key expression: " + expression);
            }
            String spel = evaluated.substring(start + 2, end);
            Object value = parser.parseExpression(spel).getValue(context);
            evaluated = evaluated.substring(0, start) + value + evaluated.substring(end + 1);
        }
        return evaluated;
    }
}
