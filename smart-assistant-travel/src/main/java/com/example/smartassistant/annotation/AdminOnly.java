package com.example.smartassistant.annotation;

import java.lang.annotation.*;

/**
 * 管理员权限注解
 * 标注此注解的方法需要管理员权限才能访问
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AdminOnly {

    /**
     * 功能描述，用于日志记录
     */
    String value() default "";
}
