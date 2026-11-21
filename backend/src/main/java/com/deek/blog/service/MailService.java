package com.deek.blog.service;

import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.Year;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

@Service
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final SiteConfigService siteConfigService;
    private final MailTemplateService templateService;
    private final boolean envEnabled;
    private final String envHost;
    private final int envPort;
    private final String envUsername;
    private final String envPassword;
    private final boolean envSsl;
    private final boolean envStarttls;
    private final String siteUrl;

    public MailService(SiteConfigService siteConfigService, MailTemplateService templateService,
                       @Value("${app.mail.enabled:false}") boolean envEnabled,
                       @Value("${spring.mail.host:}") String envHost,
                       @Value("${spring.mail.port:465}") int envPort,
                       @Value("${spring.mail.username:}") String envUsername,
                       @Value("${spring.mail.password:}") String envPassword,
                       @Value("${spring.mail.properties.mail.smtp.ssl.enable:true}") boolean envSsl,
                       @Value("${spring.mail.properties.mail.smtp.starttls.enable:false}") boolean envStarttls,
                       @Value("${app.site-url:http://localhost:8080}") String siteUrl) {
        this.siteConfigService = siteConfigService;
        this.templateService = templateService;
        this.envEnabled = envEnabled;
        this.envHost = envHost == null ? "" : envHost.trim();
        this.envPort = envPort;
        this.envUsername = envUsername == null ? "" : envUsername.trim();
        this.envPassword = envPassword == null ? "" : envPassword;
        this.envSsl = envSsl;
        this.envStarttls = envStarttls;
        this.siteUrl = siteUrl == null ? "" : siteUrl.trim();
    }

    public boolean isEnabled() {
        return settings().enabled();
    }

    public void sendResetPassword(String to, String link, int expireMinutes) {
        MailSettings settings = settings();
        if (!settings.enabled()) {
            log.info("[DEV] SMTP 未启用，重置密码链接（{} 分钟内有效）：{}", expireMinutes, link);
            return;
        }
        Map<String, String> vars = commonVars(MailTemplateService.RESET);
        vars.put("link", link == null ? "" : link);
        vars.put("expireMinutes", String.valueOf(expireMinutes));
        String subject = templateService.subject(MailTemplateService.RESET, vars);
        String html = templateService.html(MailTemplateService.RESET, vars);
        try {
            sendHtml(settings, to, subject, html);
            log.info("重置密码邮件已发送至 {}", to);
        } catch (Exception e) {
            log.error("重置密码邮件发送失败：{}", e.getMessage());
        }
    }

    public void sendRegistrationCode(String to, String code, int expireMinutes) {
        MailSettings settings = settings();
        if (!settings.enabled()) {
            throw new IllegalStateException("SMTP 未启用：请先在站点设置里配置 SMTP");
        }
        Map<String, String> vars = commonVars(MailTemplateService.REGISTRATION);
        vars.put("code", code == null ? "" : code);
        vars.put("expireMinutes", String.valueOf(expireMinutes));
        vars.put("email", to == null ? "" : to);
        String subject = templateService.subject(MailTemplateService.REGISTRATION, vars);
        String html = templateService.html(MailTemplateService.REGISTRATION, vars);
        try {
            sendHtml(settings, to, subject, html);
            log.info("注册验证码邮件已发送至 {}", to);
        } catch (Exception e) {
            throw new IllegalStateException("发送失败：" + e.getMessage(), e);
        }
    }

    public void sendTest(String to) {
        MailSettings settings = settings();
        if (!settings.enabled()) {
            throw new IllegalStateException("SMTP 未启用：请先在站点设置里启用并填写 SMTP 参数");
        }
        Map<String, String> vars = commonVars(MailTemplateService.TEST);
        vars.put("sentAt", LocalDateTime.now().format(DATE_TIME));
        String subject = templateService.subject(MailTemplateService.TEST, vars);
        String html = templateService.html(MailTemplateService.TEST, vars);
        try {
            sendHtml(settings, to, subject, html);
        } catch (Exception e) {
            throw new IllegalStateException("发送失败：" + e.getMessage(), e);
        }
    }

    private void sendHtml(MailSettings settings, String to, String subject, String html) throws Exception {
        JavaMailSenderImpl sender = sender(settings);
        MimeMessage message = sender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
        if (!settings.username().isBlank()) {
            String senderName = settings.senderName().isBlank()
                    ? siteConfigService.get().getOrDefault("siteName", "墨记")
                    : settings.senderName();
            helper.setFrom(new InternetAddress(settings.username(), senderName, "UTF-8"));
        }
        helper.setTo(to);
        helper.setSubject(subject);
        helper.setText(html, true);
        sender.send(message);
    }

    private Map<String, String> commonVars(String kind) {
        Map<String, String> vars = new LinkedHashMap<>(templateService.placeholders(kind));
        Map<String, String> config = siteConfigService.get();
        vars.put("siteName", config.getOrDefault("siteName", "墨记"));
        vars.put("siteUrl", siteUrl);
        vars.put("year", String.valueOf(Year.now().getValue()));
        return vars;
    }

    private MailSettings settings() {
        Map<String, String> config = siteConfigService.get();
        boolean runtimeEnabled = Boolean.parseBoolean(config.getOrDefault("smtpEnabled", "false"));
        String host = config.getOrDefault("smtpHost", "").trim();
        if (runtimeEnabled && !host.isBlank()) {
            int port = parseInt(config.getOrDefault("smtpPort", "465"), 465);
            String username = config.getOrDefault("smtpUsername", "").trim();
            String password = config.getOrDefault("smtpPassword", "");
            String senderName = config.getOrDefault("smtpSenderName", "").trim();
            return new MailSettings(true, host, port, username, password, senderName,
                    Boolean.parseBoolean(config.getOrDefault("smtpSsl", "true")),
                    Boolean.parseBoolean(config.getOrDefault("smtpStarttls", "false")));
        }
        if (envEnabled && !envHost.isBlank()) {
            return new MailSettings(true, envHost, envPort, envUsername, envPassword, "",
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
                                String password, String senderName, boolean ssl, boolean starttls) {
    }
}
