package com.example.smartassistant.consumer.aspect;

import com.example.smartassistant.consumer.util.LogUtils;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

/**
 * 性能监控切面
 * 自动记录所有 Controller 和 Service 方法的执行时间
 */
@Slf4j
@Aspect
@Component
public class PerformanceMonitorAspect {

    /**
     * 拦截所有 Controller 方法
     */
    @Around("execution(* com.example.smartassistant.consumer.controller..*(..))")
    public Object monitorController(ProceedingJoinPoint joinPoint) throws Throwable {
        return monitorMethod(joinPoint, "CONTROLLER");
    }

    /**
     * 拦截所有 Service 方法
     */
    @Around("execution(* com.example.smartassistant.consumer.service..*(..))")
    public Object monitorService(ProceedingJoinPoint joinPoint) throws Throwable {
        return monitorMethod(joinPoint, "SERVICE");
    }

    /**
     * 拦截所有 Repository/Mapper 方法
     */
    @Around("execution(* com.example.smartassistant.consumer.auth.mapper..*(..))")
    public Object monitorMapper(ProceedingJoinPoint joinPoint) throws Throwable {
        return monitorMethod(joinPoint, "MAPPER");
    }

    /**
     * 通用方法监控
     */
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
