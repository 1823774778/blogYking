package com.deek.blog.service;

import com.deek.blog.common.BizException;
import com.deek.blog.dto.AuthDtos.AuthResp;
import com.deek.blog.dto.AuthDtos.LoginReq;
import com.deek.blog.dto.AuthDtos.MsgResp;
import com.deek.blog.dto.AuthDtos.RegisterReq;
import com.deek.blog.dto.AuthDtos.ResetReq;
import com.deek.blog.dto.AuthDtos.UpdateProfileReq;
import com.deek.blog.dto.AuthDtos.UserView;
import com.deek.blog.entity.ResetToken;
import com.deek.blog.entity.User;
import com.deek.blog.repository.ResetTokenRepository;
import com.deek.blog.repository.UserRepository;
import com.deek.blog.security.JwtUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class AuthService {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+$");
    private static final int RESET_EXPIRE_MINUTES = 30;

    private final UserRepository userRepository;
    private final ResetTokenRepository resetTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final MailService mailService;
    private final RegistrationCodeService registrationCodeService;
    private final CaptchaService captchaService;
    private final SiteConfigService siteConfigService;
    private final String siteUrl;
    private final SecureRandom random = new SecureRandom();

    public AuthService(UserRepository userRepository, ResetTokenRepository resetTokenRepository,
                       PasswordEncoder passwordEncoder, JwtUtil jwtUtil, MailService mailService,
                       RegistrationCodeService registrationCodeService, CaptchaService captchaService,
                       SiteConfigService siteConfigService,
                       @Value("${app.site-url:http://localhost:8080}") String siteUrl) {
        this.userRepository = userRepository;
        this.resetTokenRepository = resetTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.mailService = mailService;
        this.registrationCodeService = registrationCodeService;
        this.captchaService = captchaService;
        this.siteConfigService = siteConfigService;
        this.siteUrl = siteUrl.endsWith("/") ? siteUrl.substring(0, siteUrl.length() - 1) : siteUrl;
    }

    @Transactional
    public AuthResp register(RegisterReq req) {
        if (userRepository.countByRole("ADMIN") == 0) {
            throw new BizException("站点尚未初始化，请先完成初始化设置管理员账号");
        }
        Map<String, String> cfg = siteConfigService.get();
        if (!registrationCodeService.registrationOpen()) {
            throw new BizException(HttpStatus.FORBIDDEN, cfg.getOrDefault("registrationClosedMessage", "站点暂未开放注册"));
        }
        String email = trim(req.email()).toLowerCase();
        if (!EMAIL_PATTERN.matcher(email).matches()) {
            throw new BizException("邮箱格式不正确");
        }
        int maxUsers = intValue(cfg.get("registrationMaxUsers"), 0);
        if (maxUsers > 0 && userRepository.count() >= maxUsers) {
            throw new BizException("站点注册人数已达上限，请联系站长");
        }
        if (Boolean.parseBoolean(cfg.getOrDefault("registrationInviteEnabled", "false"))) {
            String inviteCode = trim(req.inviteCode());
            String requiredCode = trim(cfg.getOrDefault("registrationInviteCode", ""));
            if (requiredCode.isEmpty()) {
                throw new BizException("邀请码尚未配置，请联系站长");
            }
            if (!requiredCode.equalsIgnoreCase(inviteCode)) {
                throw new BizException("邀请码不正确");
            }
        }
        if (!emailDomainAllowed(email, cfg.getOrDefault("registrationEmailAllowlist", ""))) {
            throw new BizException("该邮箱域名不在本站允许的注册范围内");
        }
        checkPassword(req.password(), cfg);
        captchaService.verify(req.captchaId(), req.captchaAnswer());
        if (userRepository.existsByUsername(email)) {
            throw new BizException("该邮箱已注册，可直接登录");
        }
        if (userRepository.existsByEmail(email)) {
            throw new BizException("该邮箱已注册，可直接登录");
        }
        if (registrationCodeService.verifyRequired()) {
            registrationCodeService.verify(email, req.emailCode());
        }
        boolean approval = Boolean.parseBoolean(cfg.getOrDefault("registrationApproval", "false"));
        boolean autoLogin = Boolean.parseBoolean(cfg.getOrDefault("registrationAutoLogin", "true"));
        User user = new User();
        // 新账号不再单独设置用户名：邮箱就是用户名，也是登录凭证。
        user.setUsername(email);
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode(req.password()));
        user.setNickname(defaultNickname(email));
        user.setBio(trim(cfg.getOrDefault("registrationDefaultBio", "")));
        user.setRole("USER");
        user.setEnabled(!approval);
        userRepository.save(user);
        String token = (!approval && autoLogin) ? jwtUtil.generate(user.getId()) : null;
        return new AuthResp(token, toView(user));
    }

    public AuthResp login(LoginReq req) {
        String account = trim(req.account());
        if (account.isEmpty() || req.password() == null || req.password().isEmpty()) {
            throw new BizException("请输入账号和密码");
        }
        User user = userRepository.findByUsernameOrEmail(account, account.toLowerCase()).orElse(null);
        if (user == null) {
            throw new BizException("账号或密码错误");
        }
        if (!user.isEnabled()) {
            throw new BizException(HttpStatus.FORBIDDEN, "账号待审核或已被禁用，请联系站长");
        }
        if (!passwordEncoder.matches(req.password(), user.getPassword())) {
            throw new BizException("账号或密码错误");
        }
        return new AuthResp(jwtUtil.generate(user.getId()), toView(user));
    }

    public UserView me(Long userId) {
        return toView(requireUser(userId));
    }

    @Transactional
    public MsgResp forgotPassword(String email) {
        String normalized = trim(email).toLowerCase();
        if (!EMAIL_PATTERN.matcher(normalized).matches()) {
            throw new BizException("邮箱格式不正确");
        }
        requireEmailFlow();
        User user = userRepository.findByEmail(normalized).orElse(null);
        if (user == null) {
            // 不暴露邮箱是否注册
            return new MsgResp("如果该邮箱已注册，我们已发送重置链接", null);
        }
        resetTokenRepository.deleteByUserId(user.getId());
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        String token = HexFormat.of().formatHex(bytes);

        ResetToken rt = new ResetToken();
        rt.setUserId(user.getId());
        rt.setToken(token);
        rt.setExpiresAt(LocalDateTime.now().plusMinutes(RESET_EXPIRE_MINUTES));
        resetTokenRepository.save(rt);

        String link = siteUrl + "/reset-password?token=" + token;
        mailService.sendResetPassword(user.getEmail(), link, RESET_EXPIRE_MINUTES);
        return new MsgResp("重置链接已发送至邮箱，30 分钟内有效",
                mailService.isEnabled() ? null : link);
    }

    @Transactional
    public MsgResp resetPassword(ResetReq req) {
        requireEmailFlow();
        checkPassword(req.password(), siteConfigService.get());
        ResetToken rt = resetTokenRepository.findByToken(trim(req.token()))
                .orElseThrow(() -> new BizException("重置链接无效，请重新申请"));
        if (rt.isUsed()) {
            throw new BizException("重置链接已使用，请重新申请");
        }
        if (rt.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BizException("重置链接已过期，请重新申请");
        }
        User user = requireUser(rt.getUserId());
        user.setPassword(passwordEncoder.encode(req.password()));
        userRepository.save(user);
        rt.setUsed(true);
        resetTokenRepository.save(rt);
        return new MsgResp("密码已重置，请使用新密码登录", null);
    }

    @Transactional
    public UserView updateProfile(Long userId, UpdateProfileReq req) {
        User user = requireUser(userId);
        if (req.nickname() != null) {
            String nickname = trim(req.nickname());
            if (nickname.isEmpty() || nickname.length() > 32) {
                throw new BizException("昵称需为 1-32 个字符");
            }
            user.setNickname(nickname);
        }
        if (req.bio() != null) {
            if (req.bio().length() > 512) {
                throw new BizException("个人简介最多 512 个字符");
            }
            user.setBio(req.bio().trim());
        }
        if (req.avatar() != null) {
            user.setAvatar(req.avatar().trim());
        }
        if (req.newPassword() != null && !req.newPassword().isEmpty()) {
            checkPassword(req.newPassword(), siteConfigService.get());
            if (req.oldPassword() == null || !passwordEncoder.matches(req.oldPassword(), user.getPassword())) {
                throw new BizException("原密码不正确");
            }
            user.setPassword(passwordEncoder.encode(req.newPassword()));
        }
        userRepository.save(user);
        return toView(user);
    }

    public User requireUser(Long userId) {
        if (userId == null) {
            throw BizException.unauthorized();
        }
        return userRepository.findById(userId)
                .orElseThrow(() -> new BizException(HttpStatus.UNAUTHORIZED, "登录状态已失效，请重新登录"));
    }

    public static UserView toView(User user) {
        return new UserView(user.getId(), user.getUsername(), user.getEmail(), user.getNickname(),
                user.getAvatar(), user.getBio(), user.getRole(), user.isEnabled(), user.getMutedUntil(),
                user.getMuteReason(), user.isMuted(), user.getCreatedAt());
    }

    private void requireEmailFlow() {
        Map<String, String> cfg = siteConfigService.get();
        if (!Boolean.parseBoolean(cfg.getOrDefault("passwordResetEnabled", "true"))) {
            throw new BizException("本站已关闭找回密码功能，请联系站长处理");
        }
        if (!registrationCodeService.emailVerificationEnabled()) {
            throw new BizException("本站未开启邮箱验证，找回密码功能不可用。请携带账号凭证联系管理员更新或找回密码");
        }
    }

    private boolean emailDomainAllowed(String email, String rawAllowlist) {
        if (rawAllowlist == null || rawAllowlist.isBlank()) {
            return true;
        }
        int at = email.indexOf('@');
        if (at < 0) {
            return false;
        }
        String domain = email.substring(at + 1);
        for (String item : rawAllowlist.split("[,;\\s]+")) {
            String rule = item.trim().toLowerCase();
            if (rule.startsWith("@")) {
                rule = rule.substring(1);
            }
            if (rule.isEmpty()) {
                continue;
            }
            if (rule.startsWith("*.")) {
                if (domain.endsWith(rule.substring(1))) {
                    return true;
                }
            } else if (rule.startsWith(".")) {
                if (domain.endsWith(rule)) {
                    return true;
                }
            } else if (domain.equals(rule)) {
                return true;
            }
        }
        return false;
    }

    private int intValue(String raw, int fallback) {
        try {
            return Integer.parseInt(String.valueOf(raw).trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private void checkPassword(String password, Map<String, String> cfg) {
        int minLength = Math.max(6, Math.min(64, intValue(cfg.get("registrationMinPasswordLength"), 6)));
        if (password == null || password.length() < minLength || password.length() > 64) {
            throw new BizException("密码长度需为 " + minLength + "-64 位");
        }
    }

    private void checkPassword(String password) {
        checkPassword(password, siteConfigService.get());
    }

    private String defaultNickname(String email) {
        int at = email.indexOf('@');
        String name = at > 0 ? email.substring(0, at) : email;
        return name.length() > 32 ? name.substring(0, 32) : name;
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
