package com.mycorp.distributedlock.springboot.key;

import org.aspectj.lang.ProceedingJoinPoint;

public interface LockKeyResolver {

    String resolveKey(ProceedingJoinPoint joinPoint, String expression);
}
