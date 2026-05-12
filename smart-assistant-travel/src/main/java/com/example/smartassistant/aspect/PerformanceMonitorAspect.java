/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.aspect;

import com.example.smartassistant.util.LogUtils;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

@Slf4j
@Aspect
@Component
public class PerformanceMonitorAspect {

    @Around("execution(* com.example.smartassistant.controller..*(..))")
    public Object monitorController(ProceedingJoinPoint joinPoint) throws Throwable {
        return monitorMethod(joinPoint, "CONTROLLER");
    }

    @Around("execution(* com.example.smartassistant.service..*(..))")
    public Object monitorService(ProceedingJoinPoint joinPoint) throws Throwable {
        return monitorMethod(joinPoint, "SERVICE");
    }

    @Around("execution(* com.example.smartassistant.mapper..*(..))")
    public Object monitorMapper(ProceedingJoinPoint joinPoint) throws Throwable {
        return monitorMethod(joinPoint, "MAPPER");
    }

    private Object monitorMethod(ProceedingJoinPoint joinPoint, String layer) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        String className = signature.getDeclaringTypeName();
        String methodName = signature.getName();
        String fullMethodName = className + "." + methodName;

        long startTime = System.currentTimeMillis();
        try {
            Object result = joinPoint.proceed();
            long duration = System.currentTimeMillis() - startTime;
            
            LogUtils.logPerformance(fullMethodName, duration, "SUCCESS");
            return result;
        } catch (Throwable e) {
            long duration = System.currentTimeMillis() - startTime;
            LogUtils.logPerformance(fullMethodName, duration, "FAILED");
            log.error("[{}] Exception in {} | duration={}ms", layer, fullMethodName, duration, e);
            throw e;
        }
    }
}
