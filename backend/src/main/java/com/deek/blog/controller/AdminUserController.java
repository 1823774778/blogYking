package com.deek.blog.controller;

import com.deek.blog.common.ApiResponse;
import com.deek.blog.common.BizException;
import com.deek.blog.dto.AuthDtos.UserView;
import com.deek.blog.entity.User;
import com.deek.blog.repository.UserRepository;
import com.deek.blog.role.RoleService;
import com.deek.blog.security.SecurityUtils;
import com.deek.blog.service.AuthService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/user-manage")
public class AdminUserController {

    public record CreateReq(String email, String password, String nickname, String role,
                            Boolean enabled, String avatar, String phone, String gender, LocalDate birthday) {}

    public record UpdateReq(String email, String password, String nickname, String role,
                            Boolean enabled, String avatar, String phone, String gender, LocalDate birthday) {}

    private final UserRepository userRepository;
    private final RoleService roleService;
    private final AuthService authService;
    private final PasswordEncoder passwordEncoder;

    public AdminUserController(UserRepository userRepository, RoleService roleService,
                               AuthService authService, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.roleService = roleService;
        this.authService = authService;
        this.passwordEncoder = passwordEncoder;
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> list(@RequestParam(defaultValue = "1") int page,
                                                 @RequestParam(defaultValue = "20") int size,
                                                 @RequestParam(required = false) String keyword,
                                                 @RequestParam(required = false) String role,
                                                 @RequestParam(required = false) String status,
                                                 @RequestParam(required = false) Boolean muted) {
        int p = Math.max(1, page);
        int s = Math.min(100, Math.max(1, size));
        String normalizedRole = role == null || role.isBlank() ? null : role.trim().toUpperCase();
        if (normalizedRole != null && !roleService.exists(normalizedRole)) {
            throw new BizException("角色筛选不存在");
        }
        Boolean enabled = null;
        if (status != null && !status.isBlank()) {
            if ("active".equalsIgnoreCase(status) || "enabled".equalsIgnoreCase(status)) enabled = true;
            else if ("disabled".equalsIgnoreCase(status) || "banned".equalsIgnoreCase(status)) enabled = false;
            else throw new BizException("用户状态筛选不合法");
        }
        Page<User> result = userRepository.adminSearch(
                keyword == null || keyword.isBlank() ? null : keyword.trim(), normalizedRole, enabled,
                muted, LocalDateTime.now(), PageRequest.of(p - 1, s, Sort.by(Sort.Order.desc("createdAt"))));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("records", result.getContent().stream().map(authService::toView).toList());
        data.put("total", result.getTotalElements());
        data.put("page", p);
        data.put("size", s);
        data.put("roles", roleService.list());
        data.put("stats", userStats());
        return ApiResponse.ok(data);
    }

    @GetMapping("/roles")
    public ApiResponse<Object> roles() {
        return ApiResponse.ok(roleService.list());
    }

    @PostMapping
    public ApiResponse<UserView> create(@RequestBody CreateReq req) {
        String email = normalizeEmail(req.email());
        if (userRepository.existsByEmail(email)) throw new BizException("邮箱已被注册");
        String password = req.password() == null ? "" : req.password();
        if (password.length() < 6) throw new BizException("密码至少 6 位");
        String role = normalizeRole(req.role(), "USER");
        User user = new User();
        user.setUsername(email);
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode(password));
        user.setNickname(blankToDefault(req.nickname(), email.substring(0, email.indexOf('@'))));
        user.setRole(role);
        user.setEnabled(req.enabled() == null || req.enabled());
        user.setAvatar(trimToNull(req.avatar()));
        user.setPhone(trimToNull(req.phone()));
        user.setGender(trimToNull(req.gender()));
        user.setBirthday(req.birthday());
        userRepository.save(user);
        return ApiResponse.ok(authService.toView(user));
    }

    @PutMapping("/{id}")
    public ApiResponse<UserView> update(@PathVariable Long id, @RequestBody UpdateReq req) {
        User user = userRepository.findById(id).orElseThrow(() -> BizException.notFound("用户不存在"));
        Long currentId = SecurityUtils.currentUserId();
        String nextRole = req.role() == null ? user.getRole() : normalizeRole(req.role(), user.getRole());
        boolean nextEnabled = req.enabled() == null ? user.isEnabled() : req.enabled();
        if (id.equals(currentId) && !nextEnabled) throw new BizException("不能禁用自己当前登录的账号");
        if ("ADMIN".equals(user.getRole()) && (!"ADMIN".equals(nextRole) || !nextEnabled)
                && userRepository.countByRole("ADMIN") <= 1) {
            throw new BizException("至少需要保留一个启用的管理员账号");
        }
        if (req.email() != null && !req.email().isBlank()) {
            String email = normalizeEmail(req.email());
            if (!email.equals(user.getEmail())) {
                if (userRepository.existsByEmail(email)) throw new BizException("邮箱已被其他账号使用");
                user.setEmail(email);
                user.setUsername(email);
            }
        }
        if (req.nickname() != null) {
            String nickname = req.nickname().trim();
            if (nickname.isEmpty() || nickname.length() > 32) throw new BizException("昵称需为 1-32 个字符");
            user.setNickname(nickname);
        }
        if (req.password() != null && !req.password().isEmpty()) {
            if (req.password().length() < 6) throw new BizException("新密码至少 6 位");
            user.setPassword(passwordEncoder.encode(req.password()));
        }
        user.setRole(nextRole);
        user.setEnabled(nextEnabled);
        if (req.avatar() != null) user.setAvatar(trimToNull(req.avatar()));
        if (req.phone() != null) user.setPhone(trimToNull(req.phone()));
        if (req.gender() != null) user.setGender(trimToNull(req.gender()));
        user.setBirthday(req.birthday());
        userRepository.save(user);
        return ApiResponse.ok(authService.toView(user));
    }

    private Map<String, Object> userStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("users", userRepository.count());
        stats.put("admins", userRepository.countByRole("ADMIN"));
        stats.put("disabled", userRepository.countByEnabledFalse());
        stats.put("muted", userRepository.countMuted(LocalDateTime.now()));
        return stats;
    }

    private String normalizeRole(String role, String fallback) {
        String normalized = role == null || role.isBlank() ? fallback : role.trim().toUpperCase();
        if (!roleService.exists(normalized)) throw new BizException("选择的角色不存在");
        return normalized;
    }

    private String normalizeEmail(String value) {
        String email = value == null ? "" : value.trim().toLowerCase();
        if (!email.matches("^[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+$")) throw new BizException("邮箱格式不正确");
        return email;
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String blankToDefault(String value, String fallback) {
        String trimmed = trimToNull(value);
        return trimmed == null ? fallback : trimmed;
    }
}
