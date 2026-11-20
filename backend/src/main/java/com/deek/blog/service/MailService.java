package com.deek.blog.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    private final JavaMailSender mailSender;
    private final boolean enabled;
    private final String from;

    public MailService(JavaMailSender mailSender,
                       @Value("${app.mail.enabled:false}") boolean enabled,
                       @Value("${spring.mail.username:}") String from) {
        this.mailSender = mailSender;
        this.enabled = enabled;
        this.from = from;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 发送找回密码邮件。SMTP 未启用或发送失败时只记录日志，不阻塞主流程。
     */
    public void sendResetPassword(String to, String link, int expireMinutes) {
        if (!enabled) {
            log.info("[DEV] SMTP 未启用，重置密码链接（{} 分钟内有效）：{}", expireMinutes, link);
            return;
        }
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            if (!from.isBlank()) {
                message.setFrom(from);
            }
            message.setTo(to);
            message.setSubject("重置你的博客密码");
            message.setText("""
                    你好，

                    我们收到了重置密码的请求。请在 %d 分钟内点击下面的链接设置新密码：

                    %s

                    如果这不是你本人的操作，请忽略本邮件，你的密码不会被修改。
                    """.formatted(expireMinutes, link));
            mailSender.send(message);
            log.info("重置密码邮件已发送至 {}", to);
        } catch (Exception e) {
            log.error("重置密码邮件发送失败：{}", e.getMessage());
        }
    }

    /**
     * 后台「测试 SMTP」按钮使用，异常直接抛出由上层返回提示。
     */
    public void sendTest(String to) {
        SimpleMailMessage message = new SimpleMailMessage();
        if (!from.isBlank()) {
            message.setFrom(from);
        }
        message.setTo(to);
        message.setSubject("SMTP 配置测试");
        message.setText("这是一封来自个人博客的测试邮件，收到说明 SMTP 配置正常。");
        mailSender.send(message);
    }
}
