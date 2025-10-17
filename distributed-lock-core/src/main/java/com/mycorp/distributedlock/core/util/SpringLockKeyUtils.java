package com.mycorp.distributedlock.core.util;


import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * Spring 支持的锁键生成工具类。
 * 
 * 该类提供使用 Spring Expression Language (SpEL) 生成锁键的方法。
 * 支持在注解中定义 SpEL 表达式，如 "#{methodName + ':' + #args[0]}"。
 * 
 * 如果 Spring Expression 不在 classpath 中，会 fallback 到基本键生成逻辑。
 * 
 * @see LockKeyUtils
 */
public class SpringLockKeyUtils {

    /**
     * 使用 SpEL 表达式生成锁键。
     *
     * @param prefix 锁键前缀（可为 SpEL 表达式，如 "#{methodName + ':' + #args[0]}"）
     * @param joinPoint 方法连接点 (Object, 运行时为 ProceedingJoinPoint)
     * @param context 评估上下文（可选，用于自定义变量）
     * @return 生成的锁键（lockName 部分）
     */
    public static String generateKey(String prefix, Object joinPoint, Object context) {
        try {
            return generateKeyWithSpEL(prefix, joinPoint, context);
        } catch (NoClassDefFoundError | ClassNotFoundException e) {
            // Spring 不在 classpath，fallback
            return fallbackGenerateKey(prefix, joinPoint);
        } catch (ReflectiveOperationException e) {
            // 反射失败，fallback
            return fallbackGenerateKey(prefix, joinPoint);
        } catch (Exception e) {
            // SpEL 解析异常，fallback
            return fallbackGenerateKey(prefix, joinPoint);
        }
    }

    private static String generateKeyWithSpEL(String prefix, Object joinPoint, Object providedContext) throws ReflectiveOperationException {
        // 动态加载 SpEL 类
        Class<?> spelExpressionParserClass = Class.forName("org.springframework.expression.spel.standard.SpelExpressionParser");
        Class<?> expressionParserClass = Class.forName("org.springframework.expression.ExpressionParser");
        Class<?> expressionClass = Class.forName("org.springframework.expression.Expression");
        Class<?> standardEvaluationContextClass = Class.forName("org.springframework.expression.spel.support.StandardEvaluationContext");
        Class<?> evaluationContextClass = Class.forName("org.springframework.expression.EvaluationContext");

        // 动态加载 AOP 类
        Class<?> methodSignatureClass = Class.forName("org.springframework.aop.MethodSignature");
        Class<?> proceedingJoinPointClass = Class.forName("org.aspectj.lang.ProceedingJoinPoint");

        // 验证 joinPoint 类型
        if (!proceedingJoinPointClass.isInstance(joinPoint)) {
            throw new IllegalArgumentException("joinPoint must be ProceedingJoinPoint");
        }

        // 获取 signature, method, args 使用反射
        Object signature = proceedingJoinPointClass.getMethod("getSignature").invoke(joinPoint);
        if (!methodSignatureClass.isInstance(signature)) {
            throw new IllegalArgumentException("Signature must be MethodSignature");
        }
        Method method = (Method) methodSignatureClass.getMethod("getMethod").invoke(signature);
        Object[] args = (Object[]) proceedingJoinPointClass.getMethod("getArgs").invoke(joinPoint);

        // 创建 evalContext
        Object evalContext = providedContext != null && standardEvaluationContextClass.isInstance(providedContext)
            ? providedContext
            : standardEvaluationContextClass.getConstructor().newInstance();

        // 设置变量
        evaluationContextClass.getMethod("setVariable", String.class, Object.class).invoke(evalContext, "methodName", method.getName());
        evaluationContextClass.getMethod("setVariable", String.class, Object.class).invoke(evalContext, "args", args);

        String spelExpression = prefix.isEmpty() ? "#{methodName + (args != null && args.length > 0 ? ':' + #args[0] : '')}" : prefix;

        // 解析和评估 SpEL
        Object parser = spelExpressionParserClass.getConstructor().newInstance();
        Object expression = expressionParserClass.getMethod("parseExpression", String.class).invoke(parser, spelExpression);
        Object value = expressionClass.getMethod("getValue", evaluationContextClass).invoke(expression, evalContext);
        return String.valueOf(value);
    }

    private static String fallbackGenerateKey(String prefix, Object joinPoint) {
        try {
            // 动态加载 AOP 类
            Class<?> proceedingJoinPointClass = Class.forName("org.aspectj.lang.ProceedingJoinPoint");
            Class<?> methodSignatureClass = Class.forName("org.springframework.aop.MethodSignature");

            // 验证 joinPoint 类型
            if (!proceedingJoinPointClass.isInstance(joinPoint)) {
                throw new IllegalArgumentException("joinPoint must be ProceedingJoinPoint");
            }

            // 获取 signature, methodName, args 使用反射
            Object signature = proceedingJoinPointClass.getMethod("getSignature").invoke(joinPoint);
            if (!methodSignatureClass.isInstance(signature)) {
                throw new IllegalArgumentException("Signature must be MethodSignature");
            }
            String methodName = (String) methodSignatureClass.getMethod("getName").invoke(signature);
            Object[] args = (Object[]) proceedingJoinPointClass.getMethod("getArgs").invoke(joinPoint);
            String argsKey = generateArgsKey(args);
            String key = prefix.isEmpty() ? methodName + argsKey : prefix + methodName + argsKey;
            return key;
        } catch (ClassNotFoundException e) {
            // AOP 类缺失，fallback to basic method name
            return prefix.isEmpty() ? "method" : prefix;
        } catch (ReflectiveOperationException e) {
            // 反射失败，fallback to basic method name
            return prefix.isEmpty() ? "method" : prefix;
        }
    }

    private static String generateArgsKey(Object[] args) {
        if (args == null || args.length == 0) {
            return "";
        }
        // 简单字符串化参数
        return Arrays.toString(args).replaceAll("[\\[\\] ]", "");
    }
}