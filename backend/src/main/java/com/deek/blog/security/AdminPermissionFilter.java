package com.deek.blog.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * 后台接口的模块级权限闸门。
 *
 * <p>进入后台仍由 SecurityConfig 统一要求 {@code system:admin}；本过滤器再按请求路径判断
 * 当前角色是否拥有对应的模块权限，避免普通用户拿到后台入口后访问站点设置、用户管理等接口。</p>
 */
public class AdminPermissionFilter extends OncePerRequestFilter {

    private static final String ADMIN_PREFIX = "/api/admin/";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && path.startsWith(context)) {
            path = path.substring(context.length());
        }
        if (!path.startsWith(ADMIN_PREFIX)) {
            chain.doFilter(request, response);
            return;
        }

        String required = requiredPermission(path, request.getMethod());
        if (required != null && !allowed(required)) {
            writeForbidden(response);
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean allowed(String expression) {
        if (expression == null || expression.isBlank()) return true;
        // 多个权限用 | 分隔，满足任意一个即可。
        for (String permission : expression.split("\\|")) {
            if (SecurityUtils.hasPermission(permission.trim())) return true;
        }
        return false;
    }

    /**
     * 返回需要的权限表达式；null 表示只要求登录/进入后台即可。
     */
    private String requiredPermission(String path, String method) {
        String rest = path.substring(ADMIN_PREFIX.length());
        String first = rest.split("/")[0];

        if ("stats".equals(first)) {
            return String.join("|", "system:dashboard", "system:posts", "system:my-posts",
                    "system:comments", "system:my-comments", "system:users");
        }
        if ("posts".equals(first)) {
            if (rest.contains("/review") || rest.contains("/ai-comment-summary")) return "system:posts";
            return "system:posts|system:my-posts";
        }
        if ("comments".equals(first)) {
            if (rest.contains("/status") || rest.startsWith("comments/bulk")) return "system:comments";
            return "system:comments|system:my-comments";
        }
        if ("users".equals(first) || "user-manage".equals(first)) return "system:users";
        if ("roles".equals(first)) return "system:roles";
        if ("tags".equals(first)) return "system:tags";
        if ("announcements".equals(first)) return "system:announcements";
        if ("navigation".equals(first)) return "system:navigation";
        if ("notifications".equals(first)) return "system:notifications";
        if ("storage".equals(first)) return "system:storage";
        if ("music".equals(first)) return "system:site";
        if ("activity-notices".equals(first)) return "system:dashboard";
        if ("mail".equals(first) || "maintenance".equals(first)) return "system:settings";
        if ("config".equals(first)) {
            if ("GET".equalsIgnoreCase(method)) return null;
            return "system:site|system:settings|system:storage";
        }
        if ("ai".equals(first)) {
            if (rest.startsWith("ai/generate-announcement")) return "system:announcements";
            if (rest.startsWith("ai/review-comment") || rest.startsWith("ai/summarize-comments")) {
                return "system:comments";
            }
            if (rest.startsWith("ai/generate-article") || rest.startsWith("ai/review-article")
                    || rest.startsWith("ai/summarize")) {
                return "system:posts|system:my-posts";
            }
            // 状态、模型、默认提示词只读接口，进入后台即可访问。
            return null;
        }
        // 未知后台接口默认收紧，必须由管理员角色访问。
        return "system:users";
    }

    private void writeForbidden(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":403,\"message\":\"没有权限执行该操作\",\"data\":null}");
    }
}
