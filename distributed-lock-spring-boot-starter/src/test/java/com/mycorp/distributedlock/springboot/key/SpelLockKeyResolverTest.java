package com.mycorp.distributedlock.springboot.key;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    void shouldExposeMethodTargetAndTargetClassVariables() throws Exception {
        ProceedingJoinPoint joinPoint = joinPoint("42", "cn");

        assertThat(resolver.resolveKey(joinPoint, "#{#method.name}:#{#targetClass.simpleName}:#{#target != null}"))
            .isEqualTo("process:TestTarget:true");
    }

    @Test
    void shouldRejectNullTemplateResult() throws Exception {
        ProceedingJoinPoint joinPoint = joinPoint("42", "cn");

        assertThatThrownBy(() -> resolver.resolveKey(joinPoint, "#{null}"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("resolved to null");
    }

    @Test
    void shouldRejectBlankTemplateResult() throws Exception {
        ProceedingJoinPoint joinPoint = joinPoint("42", "cn");

        assertThatThrownBy(() -> resolver.resolveKey(joinPoint, "#{'   '}"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("resolved to blank");
    }

    private ProceedingJoinPoint joinPoint(Object... args) throws Exception {
        Method method = TestTarget.class.getDeclaredMethod("process", String.class, String.class);
        MethodSignature signature = Mockito.mock(MethodSignature.class);
        Mockito.when(signature.getMethod()).thenReturn(method);

        TestTarget target = new TestTarget();
        ProceedingJoinPoint joinPoint = Mockito.mock(ProceedingJoinPoint.class);
        Mockito.when(joinPoint.getSignature()).thenReturn(signature);
        Mockito.when(joinPoint.getArgs()).thenReturn(args);
        Mockito.when(joinPoint.getTarget()).thenReturn(target);
        return joinPoint;
    }

    static final class TestTarget {
        String process(String orderId, String region) {
            return orderId + ":" + region;
        }
    }
}
