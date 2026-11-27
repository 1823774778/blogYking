package com.deek.blog.role;

import com.deek.blog.common.BizException;
import com.deek.blog.entity.Role;
import com.deek.blog.repository.RoleRepository;
import com.deek.blog.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class RoleService {

    public record PermissionView(String code, String label, String group) {}
    public record RoleView(Long id, String code, String name, String description,
                           List<String> permissions, boolean enabled, boolean builtin,
                           long userCount, LocalDateTime createdAt) {}

    private static final List<PermissionView> CATALOG = List.of(
            new PermissionView("system:admin", "进入后台", "系统"),
            new PermissionView("system:dashboard", "仪表盘", "系统"),
            new PermissionView("system:profile", "个人中心", "系统"),
            new PermissionView("system:users", "用户管理", "内容与用户"),
            new PermissionView("system:roles", "角色权限", "内容与用户"),
            new PermissionView("system:posts", "文章管理", "内容与用户"),
            new PermissionView("system:my-posts", "我的文章", "内容与用户"),
            new PermissionView("system:comments", "评论管理", "内容与用户"),
            new PermissionView("system:my-comments", "文章评论", "内容与用户"),
            new PermissionView("system:tags", "标签管理", "内容与用户"),
            new PermissionView("system:announcements", "公告管理", "站点"),
            new PermissionView("system:navigation", "导航管理", "站点"),
            new PermissionView("system:site", "站点信息", "站点"),
            new PermissionView("system:settings", "站点设置", "站点"),
            new PermissionView("system:storage", "存储管理", "站点"),
            new PermissionView("system:notifications", "通知设置", "站点")
    );

    private static final String ADMIN_PERMISSIONS = CATALOG.stream()
            .map(PermissionView::code).collect(Collectors.joining(","));
    private static final String USER_PERMISSIONS = String.join(",",
            "system:admin", "system:dashboard", "system:profile", "system:my-posts", "system:my-comments");
    private static final String EDITOR_PERMISSIONS = String.join(",",
            "system:admin", "system:dashboard", "system:profile", "system:posts", "system:comments",
            "system:tags", "system:my-posts", "system:my-comments");

    private final RoleRepository roleRepository;
    private final UserRepository userRepository;

    public RoleService(RoleRepository roleRepository, UserRepository userRepository) {
        this.roleRepository = roleRepository;
        this.userRepository = userRepository;
    }

    public static List<PermissionView> catalog() {
        return CATALOG;
    }

    public static Set<String> allCodes() {
        return CATALOG.stream().map(PermissionView::code).collect(Collectors.toSet());
    }

    public static List<String> defaultPermissions(String roleCode) {
        if (roleCode == null) return List.of();
        if ("ADMIN".equalsIgnoreCase(roleCode)) return Arrays.asList(ADMIN_PERMISSIONS.split(","));
        if ("USER".equalsIgnoreCase(roleCode)) return Arrays.asList(USER_PERMISSIONS.split(","));
        if ("EDITOR".equalsIgnoreCase(roleCode)) return Arrays.asList(EDITOR_PERMISSIONS.split(","));
        return List.of();
    }

    @Transactional
    public void ensureDefaults() {
        ensure("ADMIN", "管理员", "拥有后台全部权限", ADMIN_PERMISSIONS, true, true);
        // 普通用户已有自定义权限时保留；空权限（旧版本默认）则补上读写自己内容的默认权限。
        ensure("USER", "普通用户", "可阅读、评论、投稿文章，并管理自己的文章与评论", USER_PERMISSIONS, true, false);
        ensure("EDITOR", "编辑", "可管理文章、评论与标签", EDITOR_PERMISSIONS, false, true);
    }

    private void ensure(String code, String name, String description, String permissions,
                        boolean builtin, boolean resetPermissions) {
        Role role = roleRepository.findByCode(code).orElseGet(Role::new);
        if (role.getId() == null) {
            role.setCode(code);
            role.setBuiltin(builtin);
        }
        role.setName(name);
        role.setDescription(description);
        if (resetPermissions || role.getId() == null || role.getPermissions() == null
                || role.getPermissions().isBlank()) {
            role.setPermissions(permissions);
        } else if ("USER".equals(code)) {
            // 老站点里的 USER 角色可能已有少量权限，补齐新版默认入口但保留站长额外授予的权限。
            role.setPermissions(merge(role.getPermissions(), permissions));
        }
        role.setEnabled(true);
        role.setUpdatedAt(LocalDateTime.now());
        roleRepository.save(role);
    }

    public boolean exists(String code) {
        return code != null && roleRepository.existsByCode(code.toUpperCase(Locale.ROOT));
    }

    public List<String> permissionsFor(String code) {
        if (code == null || code.isBlank()) return List.of();
        return roleRepository.findByCode(code.toUpperCase(Locale.ROOT))
                .map(role -> parse(role.getPermissions()))
                .orElseGet(() -> defaultPermissions(code));
    }

    public String nameFor(String code) {
        if (code == null || code.isBlank()) return "普通用户";
        return roleRepository.findByCode(code.toUpperCase(Locale.ROOT))
                .map(Role::getName)
                .orElse("ADMIN".equalsIgnoreCase(code) ? "管理员" : code);
    }

    public List<RoleView> list() {
        return roleRepository.findAllByOrderByBuiltinDescIdAsc().stream()
                .map(role -> toView(role, userRepository.countByRole(role.getCode())))
                .toList();
    }

    public RoleView toView(Role role, long userCount) {
        return new RoleView(role.getId(), role.getCode(), role.getName(), role.getDescription(),
                parse(role.getPermissions()), role.isEnabled(), role.isBuiltin(), userCount,
                role.getCreatedAt());
    }

    @Transactional
    public RoleView create(String code, String name, String description, List<String> permissions, boolean enabled) {
        String normalized = normalizeCode(code);
        if (roleRepository.existsByCode(normalized)) {
            throw new BizException("角色编码已存在");
        }
        if (name == null || name.isBlank()) throw new BizException("角色名称不能为空");
        Role role = new Role();
        role.setCode(normalized);
        role.setName(name.trim());
        role.setDescription(trim(description));
        role.setPermissions(join(permissions));
        role.setEnabled(enabled);
        role.setBuiltin(false);
        roleRepository.save(role);
        return toView(role, 0);
    }

    @Transactional
    public RoleView update(Long id, String code, String name, String description,
                           List<String> permissions, boolean enabled) {
        Role role = require(id);
        String normalized = normalizeCode(code);
        if (!role.getCode().equals(normalized)) {
            if (role.isBuiltin()) throw new BizException("内置角色不能修改编码");
            if (roleRepository.existsByCode(normalized)) throw new BizException("角色编码已存在");
            role.setCode(normalized);
        }
        if (name == null || name.isBlank()) throw new BizException("角色名称不能为空");
        role.setName(name.trim());
        role.setDescription(trim(description));
        if (role.isBuiltin() && "ADMIN".equals(role.getCode())) {
            role.setPermissions(ADMIN_PERMISSIONS);
            role.setEnabled(true);
        } else {
            role.setPermissions(join(permissions));
            role.setEnabled(enabled);
        }
        role.setUpdatedAt(LocalDateTime.now());
        roleRepository.save(role);
        return toView(role, 0);
    }

    @Transactional
    public void delete(Long id) {
        Role role = require(id);
        if (role.isBuiltin()) throw new BizException("内置角色不能删除");
        roleRepository.delete(role);
    }

    private Role require(Long id) {
        return roleRepository.findById(id).orElseThrow(() -> BizException.notFound("角色不存在"));
    }

    private String normalizeCode(String code) {
        String normalized = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("^[A-Z][A-Z0-9_-]{1,31}$")) {
            throw new BizException("角色编码需以字母开头，只能包含大写字母、数字、下划线或连字符");
        }
        return normalized;
    }

    private List<String> parse(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return Arrays.stream(raw.split(",")).map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
    }

    private String join(List<String> permissions) {
        if (permissions == null || permissions.isEmpty()) return "";
        List<String> valid = new ArrayList<>();
        for (String permission : permissions) {
            if (permission != null && allCodes().contains(permission.trim())) valid.add(permission.trim());
        }
        return String.join(",", valid.stream().distinct().toList());
    }

    private String merge(String raw, String defaults) {
        Set<String> merged = new LinkedHashSet<>(parse(raw));
        merged.addAll(parse(defaults));
        return String.join(",", merged);
    }

    private String trim(String value) {
        if (value == null) return "";
        String v = value.trim();
        return v.length() > 255 ? v.substring(0, 255) : v;
    }
}
