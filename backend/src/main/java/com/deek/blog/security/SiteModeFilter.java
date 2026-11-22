package com.deek.blog.security;

import com.deek.blog.service.SiteConfigService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 维护模式 / 只读模式的后端强制开关。
 * 前台遮罩只是提示，真正的写入拦截在这里完成。
 */
public class SiteModeFilter extends OncePerRequestFilter {

    private final SiteConfigService siteConfigService;

    public SiteModeFilter(SiteConfigService siteConfigService) {
        this.siteConfigService = siteConfigService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!path.startsWith("/api/")) {
            chain.doFilter(request, response);
            return;
        }

        Map<String, String> cfg = siteConfigService.get();
        boolean maintenance = Boolean.parseBoolean(cfg.getOrDefault("maintenanceEnabled", "false"));
        if (maintenance && !isAdminPath(path) && !allowedDuringMaintenance(path)) {
            String message = cfg.getOrDefault("maintenanceMessage", "");
            writeJson(response, 503, message.isBlank() ? "站点正在维护升级，请稍后再访问" : message);
            return;
        }

        boolean readOnly = Boolean.parseBoolean(cfg.getOrDefault("readOnlyMode", "false"));
        if (readOnly && !isSafeMethod(request.getMethod()) && !isAdminPath(path)
                && !allowedWriteDuringReadOnly(path)) {
            String message = cfg.getOrDefault("readOnlyMessage", "");
            writeJson(response, 403, message.isBlank() ? "站点当前为只读模式，暂时不能修改内容" : message);
            return;
        }

        chain.doFilter(request, response);
    }

    private boolean isAdminPath(String path) {
        return path.startsWith("/api/admin/") || path.equals("/api/admin");
    }

    private boolean allowedDuringMaintenance(String path) {
        return path.startsWith("/api/meta/")
                || path.startsWith("/api/setup/")
                || path.startsWith("/api/system/")
                || path.equals("/api/auth/login")
                || path.equals("/api/auth/logout")
                || path.equals("/api/auth/me")
                || path.equals("/api/auth/captcha");
    }

    private boolean allowedWriteDuringReadOnly(String path) {
        return path.equals("/api/auth/login")
                || path.equals("/api/auth/logout")
                || path.equals("/api/auth/captcha")
                || path.equals("/api/auth/me");
    }

    private boolean isSafeMethod(String method) {
        return "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method) || "OPTIONS".equalsIgnoreCase(method);
    }

    private void writeJson(HttpServletResponse res, int status, String message) throws IOException {
        res.setStatus(status);
        res.setCharacterEncoding(StandardCharsets.UTF_8.name());
        res.setContentType("application/json;charset=UTF-8");
        res.getWriter().write("{\"code\":" + status + ",\"message\":\"" + escape(message) + "\",\"data\":null}");
    }

    private String escape(String value) {
        return String.valueOf(value)
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
