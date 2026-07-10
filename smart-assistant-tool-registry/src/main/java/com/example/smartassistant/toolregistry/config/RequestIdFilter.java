package com.example.smartassistant.toolregistry.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 请求 ID 过滤器。
 * <p>
 * 从 {@code X-Request-Id} 请求头提取 trace ID，若不存在则自动生成。
 * 响应头也会返回 {@code X-Request-Id}。
 * 执行完毕后自动清理 {@link RequestIdHolder}。
 * </p>
 *
 * @author Yu-hk
 * @since 2026-07-10
 */
@Component
@Order(0)
public class RequestIdFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(RequestIdFilter.class);

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpReq = (HttpServletRequest) request;
        HttpServletResponse httpResp = (HttpServletResponse) response;

        try {
            // 从请求头提取或自动生成
            String headerId = httpReq.getHeader(RequestIdHolder.HEADER_NAME);
            String requestId = RequestIdHolder.set(headerId);

            // 设置响应头
            httpResp.setHeader(RequestIdHolder.HEADER_NAME, requestId);

            log.debug("[RequestIdFilter] {} {} -> requestId={}",
                    httpReq.getMethod(), httpReq.getRequestURI(), requestId);

            chain.doFilter(request, response);

        } finally {
            RequestIdHolder.clear();
        }
    }
}
