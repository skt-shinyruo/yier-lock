package com.mycorp.distributedlock.springboot.key;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class SpelLockKeyResolverTest {

    private final SpelLockKeyResolver resolver = new SpelLockKeyResolver();

    @Test
    void shouldReturnLiteralKeyWhenNoTemplateMarkersExist() throws Exception {
        ProceedingJoinPoint joinPoint = joinPoint("42", "cn");

        assertThat(resolver.resolveKey(joinPoint, "order:42")).isEqualTo("order:42");
    }

    @Test
    void shouldResolveSimpleTemplateExpression() throws Exception {
        ProceedingJoinPoint joinPoint = joinPoint("42", "cn");

        assertThat(resolver.resolveKey(joinPoint, "order:#{#p0}")).isEqualTo("order:42");
    }

    @Test
    void shouldResolveStructuredTemplateExpression() throws Exception {
        ProceedingJoinPoint joinPoint = joinPoint("42", "cn");

        assertThat(resolver.resolveKey(
            joinPoint,
            "order:#{ {'id': #p0, 'region': #p1.toUpperCase()}['id'] }-#{#p1.toUpperCase()}"
        )).isEqualTo("order:42-CN");
    }

    private ProceedingJoinPoint joinPoint(Object... args) throws Exception {
        Method method = TestTarget.class.getDeclaredMethod("process", String.class, String.class);
        MethodSignature signature = Mockito.mock(MethodSignature.class);
        Mockito.when(signature.getMethod()).thenReturn(method);

        ProceedingJoinPoint joinPoint = Mockito.mock(ProceedingJoinPoint.class);
        Mockito.when(joinPoint.getSignature()).thenReturn(signature);
        Mockito.when(joinPoint.getArgs()).thenReturn(args);
        return joinPoint;
    }

    static final class TestTarget {
        String process(String orderId, String region) {
            return orderId + ":" + region;
        }
    }
}
