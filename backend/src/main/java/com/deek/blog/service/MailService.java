package com.deek.blog.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Properties;

@Service
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    private final SiteConfigService siteConfigService;
    private final boolean envEnabled;
    private final String envHost;
    private final int envPort;
    private final String envUsername;
    private final String envPassword;
    private final String envFrom;
    private final boolean envSsl;
    private final boolean envStarttls;

    public MailService(SiteConfigService siteConfigService,
                       @Value("${app.mail.enabled:false}") boolean envEnabled,
                       @Value("${spring.mail.host:}") String envHost,
                       @Value("${spring.mail.port:465}") int envPort,
                       @Value("${spring.mail.username:}") String envUsername,
                       @Value("${spring.mail.password:}") String envPassword,
                       @Value("${spring.mail.properties.mail.smtp.ssl.enable:true}") boolean envSsl,
                       @Value("${spring.mail.properties.mail.smtp.starttls.enable:false}") boolean envStarttls) {
        this.siteConfigService = siteConfigService;
        this.envEnabled = envEnabled;
        this.envHost = envHost == null ? "" : envHost.trim();
        this.envPort = envPort;
        this.envUsername = envUsername == null ? "" : envUsername.trim();
        this.envPassword = envPassword == null ? "" : envPassword;
        this.envFrom = envUsername == null ? "" : envUsername.trim();
        this.envSsl = envSsl;
        this.envStarttls = envStarttls;
    }

    public boolean isEnabled() {
        return settings().enabled();
    }

    /**
     * 发送找回密码邮件。SMTP 未启用或发送失败时只记录日志，不阻塞主流程。
     */
    public void sendResetPassword(String to, String link, int expireMinutes) {
        MailSettings settings = settings();
        if (!settings.enabled()) {
            log.info("[DEV] SMTP 未启用，重置密码链接（{} 分钟内有效）：{}", expireMinutes, link);
            return;
        }
        try {
            JavaMailSenderImpl sender = sender(settings);
            SimpleMailMessage message = new SimpleMailMessage();
            if (!settings.from().isBlank()) {
                message.setFrom(settings.from());
            }
            message.setTo(to);
            message.setSubject("重置你的博客密码");
            message.setText("""
                    你好，

                    我们收到了重置密码的请求。请在 %d 分钟内点击下面的链接设置新密码：

                    %s

                    如果这不是你本人的操作，请忽略本邮件，你的密码不会被修改。
                    """.formatted(expireMinutes, link));
            sender.send(message);
            log.info("重置密码邮件已发送至 {}", to);
        } catch (Exception e) {
            log.error("重置密码邮件发送失败：{}", e.getMessage());
        }
    }

    /** 发送注册邮箱验证码；失败时抛出异常，由注册入口返回明确提示。 */
    public void sendRegistrationCode(String to, String code, int expireMinutes) {
        MailSettings settings = settings();
        if (!settings.enabled()) {
            throw new IllegalStateException("SMTP 未启用：请先在站点设置里配置 SMTP");
        }
        JavaMailSenderImpl sender = sender(settings);
        SimpleMailMessage message = new SimpleMailMessage();
        if (!settings.from().isBlank()) {
            message.setFrom(settings.from());
        }
        message.setTo(to);
        message.setSubject("注册验证码");
        message.setText("""
                你好，

                你的博客注册验证码是：%s

                验证码 %d 分钟内有效。如果不是你本人操作，请忽略本邮件。
                """.formatted(code, expireMinutes));
        sender.send(message);
        log.info("注册验证码邮件已发送至 {}", to);
    }

    /**
     * 后台「测试 SMTP」按钮使用，异常直接抛出由上层返回提示。
     */
    public void sendTest(String to) {
        MailSettings settings = settings();
        if (!settings.enabled()) {
            throw new IllegalStateException("SMTP 未启用：请先在站点设置里启用并填写 SMTP 参数");
        }
        JavaMailSenderImpl sender = sender(settings);
        SimpleMailMessage message = new SimpleMailMessage();
        if (!settings.from().isBlank()) {
            message.setFrom(settings.from());
        }
        message.setTo(to);
        message.setSubject("SMTP 配置测试");
        message.setText("这是一封来自个人博客的测试邮件，收到说明 SMTP 配置正常。");
        sender.send(message);
    }

    private MailSettings settings() {
        Map<String, String> config = siteConfigService.get();
        boolean runtimeEnabled = Boolean.parseBoolean(config.getOrDefault("smtpEnabled", "false"));
        String host = config.getOrDefault("smtpHost", "").trim();
        if (runtimeEnabled && !host.isBlank()) {
            int port = parseInt(config.getOrDefault("smtpPort", "465"), 465);
            String username = config.getOrDefault("smtpUsername", "").trim();
            String password = config.getOrDefault("smtpPassword", "");
            String from = config.getOrDefault("smtpFrom", "").trim();
            if (from.isBlank()) {
                from = username;
            }
            return new MailSettings(true, host, port, username, password, from,
                    Boolean.parseBoolean(config.getOrDefault("smtpSsl", "true")),
                    Boolean.parseBoolean(config.getOrDefault("smtpStarttls", "false")));
        }
        if (envEnabled && !envHost.isBlank()) {
            return new MailSettings(true, envHost, envPort, envUsername, envPassword, envFrom,
                    envSsl, envStarttls);
        }
        return new MailSettings(false, "", 465, "", "", "", true, false);
    }

    private JavaMailSenderImpl sender(MailSettings settings) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(settings.host());
        sender.setPort(settings.port());
        sender.setDefaultEncoding("UTF-8");
        if (!settings.username().isBlank()) {
            sender.setUsername(settings.username());
            sender.setPassword(settings.password());
        }
        Properties properties = sender.getJavaMailProperties();
        properties.put("mail.transport.protocol", "smtp");
        properties.put("mail.smtp.auth", String.valueOf(!settings.username().isBlank()));
        properties.put("mail.smtp.ssl.enable", String.valueOf(settings.ssl()));
        properties.put("mail.smtp.starttls.enable", String.valueOf(settings.starttls()));
        properties.put("mail.smtp.connectiontimeout", "8000");
        properties.put("mail.smtp.timeout", "8000");
        properties.put("mail.smtp.writetimeout", "8000");
        return sender;
    }

    private int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value == null ? "" : value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private record MailSettings(boolean enabled, String host, int port, String username,
                                String password, String from, boolean ssl, boolean starttls) {
    }
}
