package com.example.smartassistant.common.tool.provider;

import com.example.smartassistant.common.gateway.tool.ToolDefinition;
import com.example.smartassistant.common.tool.client.ToolRegistryClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 基于 Spring ApplicationContext 的 {@link ToolProvider} 实现。
 * <p>
 * 通过扫描容器中所有带有 {@code @Tool} 注解方法的 Bean，结合
 * {@link ToolRegistryClient} 的远程注册信息，按标签过滤并返回
 * 匹配的 {@link ToolCallback} 列表。
 * </p>
 *
 * <h3>工作流程</h3>
 * <ol>
 *   <li>调用 {@link #findToolBeans()} 扫描 ApplicationContext 中所有含 @Tool 方法的 Bean</li>
 *   <li>使用 {@link MethodToolCallbackProvider} 将每个 Bean 组装为 ToolCallback 数组</li>
 *   <li>从 {@link ToolRegistryClient#getToolDefinitions(String)} 获取指定 tag 的已注册工具名集合</li>
 *   <li>过滤：只保留名称在注册集合中的 ToolCallback</li>
 *   <li>按工具名排序后返回</li>
 * </ol>
 *
 * @author Yu-hk
 * @since 2026-07-10
 */
@Component
public class SpringToolProvider implements ToolProvider {

    private static final Logger log = LoggerFactory.getLogger(SpringToolProvider.class);

    private final ApplicationContext applicationContext;
    private final ToolRegistryClient registryClient;

    /**
     * 构造 SpringToolProvider。
     *
     * @param applicationContext Spring 应用上下文，用于扫描工具 Bean
     * @param registryClient     Tool Registry 客户端，用于查询已注册工具定义
     */
    public SpringToolProvider(ApplicationContext applicationContext,
                              ToolRegistryClient registryClient) {
        this.applicationContext = applicationContext;
        this.registryClient = registryClient;
    }

    /**
     * 获取指定标签对应的 ToolCallback 列表。
     * <p>
     * 实现步骤：
     * <ol>
     *   <li>扫描所有含 {@code @Tool} 注解方法的 Bean</li>
     *   <li>组装为 ToolCallback 列表</li>
     *   <li>从 Registry 查询指定 tag 的已注册工具名集合</li>
     *   <li>仅返回已注册的工具</li>
     *   <li>按工具名排序</li>
     * </ol>
     *
     * @param tag 域标签，如 "ORDER" / "PRODUCT" / "GENERAL"
     * @return 匹配的 ToolCallback 列表（按工具名排序，保证确定性）
     */
    @Override
    public List<ToolCallback> getToolCallbacks(String tag) {
        // 1. 扫描所有含 @Tool 方法的 Bean
        List<Object> toolBeans = findToolBeans();
        log.debug("[SpringToolProvider] 扫描到 {} 个含 @Tool 方法的 Bean", toolBeans.size());

        // 2. 组装为 ToolCallback 列表
        List<ToolCallback> allCallbacks = new ArrayList<>();
        for (Object bean : toolBeans) {
            try {
                ToolCallback[] callbacks = MethodToolCallbackProvider.builder()
                        .toolObjects(bean)
                        .build()
                        .getToolCallbacks();
                allCallbacks.addAll(Arrays.asList(callbacks));
            } catch (Exception e) {
                log.warn("[SpringToolProvider] 组装 ToolCallback 异常: beanClass={}, error={}",
                        bean.getClass().getSimpleName(), e.getMessage());
            }
        }
        log.debug("[SpringToolProvider] 组装了 {} 个 ToolCallback", allCallbacks.size());

        // 3. 从 Registry 获取已注册的工具名集合并过滤
        Set<String> registeredNames = resolveRegisteredNames(tag, allCallbacks);

        // 4. 过滤：只返回在 Registry 中注册的工具
        List<ToolCallback> filtered = allCallbacks.stream()
                .filter(tc -> registeredNames.contains(tc.getToolDefinition().name()))
                .collect(Collectors.toList());

        // 5. 按工具名排序
        filtered.sort(Comparator.comparing(tc -> tc.getToolDefinition().name()));

        log.info("[SpringToolProvider] getToolCallbacks({}): {} beans → {} callbacks → {} matched (sorted)",
                tag, toolBeans.size(), allCallbacks.size(), filtered.size());
        return filtered;
    }

    /**
     * 扫描 ApplicationContext 中所有包含 {@code @Tool} 注解方法的 Bean。
     * <p>
     * 遍历所有 Bean 定义名称，获取真实用户类，检查其所有声明的方法
     * 是否包含 {@code @Tool} 注解。
     * </p>
     *
     * @return 包含至少一个 @Tool 方法的 Bean 实例列表
     */
    List<Object> findToolBeans() {
        List<Object> toolBeans = new ArrayList<>();
        String[] beanNames = applicationContext.getBeanDefinitionNames();

        for (String beanName : beanNames) {
            try {
                Object bean = applicationContext.getBean(beanName);
                if (bean == null) {
                    continue;
                }

                // 获取真实的用户类（避免 CGLIB 代理等）
                Class<?> userClass = ClassUtils.getUserClass(bean);

                // 检查所有声明的方法是否含 @Tool 注解
                boolean hasToolMethod = false;
                Method[] methods = ReflectionUtils.getAllDeclaredMethods(userClass);
                for (Method method : methods) {
                    if (method.isAnnotationPresent(Tool.class)) {
                        hasToolMethod = true;
                        break;
                    }
                }

                if (hasToolMethod) {
                    toolBeans.add(bean);
                    log.debug("[SpringToolProvider] 发现工具 Bean: name={}, class={}",
                            beanName, userClass.getSimpleName());
                }
            } catch (Exception e) {
                log.warn("[SpringToolProvider] 扫描 Bean 异常: name={}, error={}",
                        beanName, e.getMessage());
            }
        }

        return toolBeans;
    }

    /**
     * 从 Registry 查询已注册的工具名集合。Registry 不可用时降级为返回全部工具名。
     */
    private Set<String> resolveRegisteredNames(String tag, List<ToolCallback> allCallbacks) {
        try {
            List<ToolDefinition> defs = registryClient.getToolDefinitions(tag);
            Set<String> names = defs.stream()
                    .map(ToolDefinition::getName)
                    .collect(Collectors.toSet());
            log.debug("[SpringToolProvider] Registry 中 tag={} 已注册 {} 个工具", tag, names.size());
            return names;
        } catch (Exception e) {
            log.warn("[SpringToolProvider] 查询 Registry 失败: tag={}, error={}", tag, e.getMessage());
            Set<String> fallback = allCallbacks.stream()
                    .map(tc -> tc.getToolDefinition().name())
                    .collect(Collectors.toSet());
            log.warn("[SpringToolProvider] Registry 不可用，降级返回全部 {} 个工具", allCallbacks.size());
            return fallback;
        }
    }
}
