/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

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
