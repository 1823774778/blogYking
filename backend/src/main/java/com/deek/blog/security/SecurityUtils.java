package com.deek.blog.security;

import com.deek.blog.common.BizException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class SecurityUtils {

    private SecurityUtils() {
    }

    public static Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        Object principal = auth.getPrincipal();
        return principal instanceof Long id ? id : null;
    }

    public static Long requireUserId() {
        Long id = currentUserId();
        if (id == null) {
            throw BizException.unauthorized();
        }
        return id;
    }

    public static boolean isAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return false;
        }
        return auth.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }

    public static boolean hasPermission(String permission) {
        if (permission == null || permission.isBlank()) return false;
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return false;
        String authority = "PERM_" + permission;
        return auth.getAuthorities().stream().anyMatch(a -> authority.equals(a.getAuthority()));
    }

    public static boolean hasAnyPermission(String... permissions) {
        if (permissions == null) return false;
        for (String permission : permissions) {
            if (hasPermission(permission)) return true;
        }
        return false;
    }
}
