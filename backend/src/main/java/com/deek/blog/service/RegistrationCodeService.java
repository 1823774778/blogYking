package com.deek.blog.service;

import com.deek.blog.common.BizException;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** 注册邮箱验证码：内存保存，10 分钟有效，同一邮箱 60 秒内不能重复发送。 */
@Service
public class RegistrationCodeService {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+$");
    private static final int EXPIRE_MINUTES = 10;
    private static final int COOLDOWN_SECONDS = 60;
    private static final int MAX_ATTEMPTS = 6;

    private final Map<String, Entry> codes = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final SiteConfigService siteConfigService;
    private final MailService mailService;

    public RegistrationCodeService(SiteConfigService siteConfigService, MailService mailService) {
        this.siteConfigService = siteConfigService;
        this.mailService = mailService;
    }

    public void send(String rawEmail) {
        String email = normalize(rawEmail);
        if (!EMAIL_PATTERN.matcher(email).matches()) {
            throw new BizException("邮箱格式不正确");
        }
        if (!registrationOpen()) {
            throw new BizException("站点暂未开放注册");
        }
        if (!verifyRequired()) {
            throw new BizException("站点未开启注册邮箱验证，无需获取验证码");
        }
        if (!mailService.isEnabled()) {
            throw new BizException("SMTP 邮件服务未启用，无法发送注册验证码");
        }
        Entry old = codes.get(email);
        LocalDateTime now = LocalDateTime.now();
        if (old != null && old.sentAt().plusSeconds(COOLDOWN_SECONDS).isAfter(now)) {
            long wait = java.time.Duration.between(now, old.sentAt().plusSeconds(COOLDOWN_SECONDS)).toSeconds();
            throw new BizException("请 " + Math.max(1, wait) + " 秒后再获取验证码");
        }
        String code = "%06d".formatted(random.nextInt(1_000_000));
        codes.put(email, new Entry(code, now, now.plusMinutes(EXPIRE_MINUTES), 0));
        try {
            mailService.sendRegistrationCode(email, code, EXPIRE_MINUTES);
        } catch (Exception e) {
            codes.remove(email, codes.get(email));
            throw new BizException("验证码发送失败：" + e.getMessage());
        }
    }

    public void verify(String rawEmail, String rawCode) {
        String email = normalize(rawEmail);
        String code = rawCode == null ? "" : rawCode.trim();
        if (code.isBlank()) {
            throw new BizException("请输入邮箱验证码");
        }
        Entry entry = codes.get(email);
        if (entry == null) {
            throw new BizException("请先获取邮箱验证码");
        }
        if (entry.expiresAt().isBefore(LocalDateTime.now())) {
            codes.remove(email);
            throw new BizException("验证码已过期，请重新获取");
        }
        if (entry.attempts() >= MAX_ATTEMPTS) {
            codes.remove(email);
            throw new BizException("验证码尝试次数过多，请重新获取");
        }
        if (!entry.code().equals(code)) {
            codes.put(email, new Entry(entry.code(), entry.sentAt(), entry.expiresAt(), entry.attempts() + 1));
            throw new BizException("邮箱验证码不正确");
        }
        codes.remove(email);
    }

    public boolean registrationOpen() {
        return Boolean.parseBoolean(siteConfigService.get().getOrDefault("registrationEnabled", "true"));
    }

    public boolean verifyRequired() {
        Map<String, String> config = siteConfigService.get();
        return Boolean.parseBoolean(config.getOrDefault("registrationEnabled", "true"))
                && Boolean.parseBoolean(config.getOrDefault("registrationVerifyEmail", "false"));
    }

    private String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }

    private record Entry(String code, LocalDateTime sentAt, LocalDateTime expiresAt, int attempts) {
    }
}
