package com.deek.blog.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 邮件模板：后台可编辑主题与 HTML，发送时统一替换 {{placeholder}}。
 */
@Service
public class MailTemplateService {

    private static final Logger log = LoggerFactory.getLogger(MailTemplateService.class);

    public static final String REGISTRATION = "registration";
    public static final String RESET = "reset";
    public static final String TEST = "test";

    private static final Pattern TOKEN = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_]+)\\s*\\}\\}");
    private static final Map<String, String> SUBJECT_KEYS = Map.of(
            REGISTRATION, "mailTemplateRegistrationSubject",
            RESET, "mailTemplateResetSubject",
            TEST, "mailTemplateTestSubject"
    );
    private static final Map<String, String> HTML_KEYS = Map.of(
            REGISTRATION, "mailTemplateRegistrationHtml",
            RESET, "mailTemplateResetHtml",
            TEST, "mailTemplateTestHtml"
    );

    private static final Map<String, String> DEFAULTS = templateDefaults();

    private final SiteConfigService siteConfigService;

    public MailTemplateService(SiteConfigService siteConfigService) {
        this.siteConfigService = siteConfigService;
    }

    public Map<String, String> defaults() {
        return new LinkedHashMap<>(DEFAULTS);
    }

    public String subject(String kind, Map<String, String> vars) {
        String key = SUBJECT_KEYS.getOrDefault(kind, SUBJECT_KEYS.get(TEST));
        String raw = config(key, "站点邮件通知");
        return renderTokens(raw, vars);
    }

    public String html(String kind, Map<String, String> vars) {
        String key = HTML_KEYS.getOrDefault(kind, HTML_KEYS.get(TEST));
        String raw = config(key, DEFAULTS.get(key));
        return renderTokens(raw, vars);
    }

    public Map<String, String> placeholders(String kind) {
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("siteName", "站点名");
        vars.put("siteUrl", "站点地址");
        vars.put("year", "2026");
        if (REGISTRATION.equals(kind)) {
            vars.put("code", "123456");
            vars.put("expireMinutes", "10");
            vars.put("email", "you@example.com");
        } else if (RESET.equals(kind)) {
            vars.put("link", "https://example.com/reset-password?token=xxxx");
            vars.put("expireMinutes", "30");
        } else {
            vars.put("sentAt", "2026-11-20 18:30");
        }
        return vars;
    }

    public String renderTokens(String raw, Map<String, String> vars) {
        if (raw == null) {
            return "";
        }
        Matcher matcher = TOKEN.matcher(raw);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            String value = vars.getOrDefault(key, "");
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private String config(String key, String fallback) {
        String value = siteConfigService.get().get(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static Map<String, String> templateDefaults() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("mailTemplateRegistrationSubject", "{{siteName}} 的注册验证码");
        map.put("mailTemplateResetSubject", "重置 {{siteName}} 账户密码");
        map.put("mailTemplateTestSubject", "{{siteName}} 邮件配置测试");
        map.put("mailTemplateRegistrationHtml", """
                <!doctype html>
                <html><body style="margin:0;padding:0;background:#f4f7fb;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI','PingFang SC','Microsoft YaHei',sans-serif;color:#1b211d;">
                  <div style="display:none;max-height:0;overflow:hidden;opacity:0;">你的注册验证码是 {{code}}，{{expireMinutes}} 分钟内有效。</div>
                  <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background:#f4f7fb;padding:36px 16px;">
                    <tr><td align="center">
                      <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="max-width:560px;background:#ffffff;border:1px solid #dbe6f3;border-radius:10px;overflow:hidden;">
                        <tr><td style="height:4px;background:#24589f;font-size:0;line-height:0;">&nbsp;</td></tr>
                        <tr><td style="padding:26px 32px 0;">
                          <table role="presentation" width="100%"><tr>
                            <td style="font-size:20px;font-weight:700;letter-spacing:-.4px;color:#10213a;">{{siteName}}</td>
                            <td align="right" style="font-size:10px;letter-spacing:1.6px;color:#7f91a8;">SECURE MAIL</td>
                          </tr></table>
                        </tr></td>
                        <tr><td style="padding:30px 32px 34px;">
                          <p style="margin:0 0 10px;font-size:10px;letter-spacing:1.8px;color:#24589f;font-weight:700;">VERIFY YOUR EMAIL</p>
                          <h1 style="margin:0 0 12px;font-size:25px;line-height:1.35;font-weight:700;letter-spacing:-.5px;color:#10213a;">确认你的注册邮箱</h1>
                          <p style="margin:0 0 24px;font-size:14px;line-height:1.8;color:#465a76;">你好，欢迎加入 {{siteName}}。请在注册页面输入下面的验证码，完成邮箱验证。</p>
                          <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background:#f2f7fd;border:1px solid #e0e9f4;border-radius:8px;">
                            <tr><td align="center" style="padding:22px 16px 20px;">
                              <div style="font-size:10px;letter-spacing:1.5px;color:#7f91a8;margin-bottom:8px;">验证码</div>
                              <div style="font-family:'SFMono-Regular',Consolas,monospace;font-size:32px;line-height:1.2;font-weight:700;letter-spacing:9px;color:#10213a;">{{code}}</div>
                            </td></tr>
                          </table>
                          <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="margin-top:18px;border-top:1px solid #e4edf7;">
                            <tr>
                              <td style="padding:14px 0 0;font-size:11px;color:#7f91a8;">有效期<br><strong style="display:block;margin-top:3px;font-size:12px;color:#29415f;">{{expireMinutes}} 分钟</strong></td>
                              <td align="right" style="padding:14px 0 0;font-size:11px;color:#7f91a8;">收件邮箱<br><strong style="display:block;margin-top:3px;font-size:12px;color:#29415f;">{{email}}</strong></td>
                            </tr>
                          </table>
                          <p style="margin:22px 0 0;font-size:12px;line-height:1.7;color:#7f91a8;">如果不是你本人操作，请忽略这封邮件，验证码会自动失效。</p>
                        </td></tr>
                        <tr><td style="padding:18px 32px 22px;border-top:1px solid #e4edf7;background:#f8fbff;">
                          <p style="margin:0;font-size:11px;line-height:1.7;color:#7f91a8;">此邮件由 {{siteName}} 自动发送，请勿直接回复。<br>© {{year}} {{siteName}} · <a href="{{siteUrl}}" style="color:#24589f;text-decoration:none;">{{siteUrl}}</a></p>
                        </td></tr>
                      </table>
                    </td></tr>
                  </table>
                </body></html>
                """);
        map.put("mailTemplateResetHtml", """
                <!doctype html>
                <html><body style="margin:0;padding:0;background:#f4f7fb;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI','PingFang SC','Microsoft YaHei',sans-serif;color:#1b211d;">
                  <div style="display:none;max-height:0;overflow:hidden;opacity:0;">重置 {{siteName}} 密码，链接 {{expireMinutes}} 分钟内有效。</div>
                  <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background:#f4f7fb;padding:36px 16px;">
                    <tr><td align="center">
                      <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="max-width:560px;background:#ffffff;border:1px solid #dbe6f3;border-radius:10px;overflow:hidden;">
                        <tr><td style="height:4px;background:#24589f;font-size:0;line-height:0;">&nbsp;</td></tr>
                        <tr><td style="padding:26px 32px 0;">
                          <table role="presentation" width="100%"><tr>
                            <td style="font-size:20px;font-weight:700;letter-spacing:-.4px;color:#10213a;">{{siteName}}</td>
                            <td align="right" style="font-size:10px;letter-spacing:1.6px;color:#7f91a8;">ACCOUNT SECURITY</td>
                          </tr></table>
                        </td></tr>
                        <tr><td style="padding:30px 32px 34px;">
                          <p style="margin:0 0 10px;font-size:10px;letter-spacing:1.8px;color:#24589f;font-weight:700;">RESET PASSWORD</p>
                          <h1 style="margin:0 0 12px;font-size:25px;line-height:1.35;font-weight:700;letter-spacing:-.5px;color:#10213a;">重新设置你的密码</h1>
                          <p style="margin:0 0 26px;font-size:14px;line-height:1.8;color:#465a76;">我们收到了「{{siteName}}」账号的密码重置请求。点击下面的按钮继续，链接在 <strong style="color:#1b211d;">{{expireMinutes}} 分钟内有效</strong>。</p>
                          <a href="{{link}}" style="display:inline-block;padding:13px 24px;border-radius:6px;background:#173f78;color:#ffffff;text-decoration:none;font-size:14px;font-weight:600;">设置新密码</a>
                          <p style="margin:26px 0 0;padding-top:18px;border-top:1px solid #e4edf7;font-size:11px;line-height:1.8;color:#7f91a8;">按钮无法点击时，复制下面的链接到浏览器打开：<br><a href="{{link}}" style="color:#24589f;text-decoration:none;word-break:break-all;">{{link}}</a></p>
                        </td></tr>
                        <tr><td style="padding:18px 32px 22px;border-top:1px solid #e4edf7;background:#f8fbff;">
                          <p style="margin:0;font-size:11px;line-height:1.7;color:#7f91a8;">如果这不是你本人的操作，请忽略本邮件，密码不会被修改。<br>© {{year}} {{siteName}} · <a href="{{siteUrl}}" style="color:#24589f;text-decoration:none;">{{siteUrl}}</a></p>
                        </td></tr>
                      </table>
                    </td></tr>
                  </table>
                </body></html>
                """);
        map.put("mailTemplateTestHtml", """
                <!doctype html>
                <html><body style="margin:0;padding:0;background:#f4f7fb;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI','PingFang SC','Microsoft YaHei',sans-serif;color:#1b211d;">
                  <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background:#f4f7fb;padding:36px 16px;">
                    <tr><td align="center">
                      <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="max-width:560px;background:#ffffff;border:1px solid #dbe6f3;border-radius:10px;overflow:hidden;">
                        <tr><td style="height:4px;background:#24589f;font-size:0;line-height:0;">&nbsp;</td></tr>
                        <tr><td style="padding:26px 32px 0;">
                          <table role="presentation" width="100%"><tr>
                            <td style="font-size:20px;font-weight:700;letter-spacing:-.4px;color:#10213a;">{{siteName}}</td>
                            <td align="right" style="font-size:10px;letter-spacing:1.6px;color:#7f91a8;">SMTP TEST</td>
                          </tr></table>
                        </td></tr>
                        <tr><td style="padding:30px 32px 34px;">
                          <div style="width:44px;height:44px;border:1px solid #d6e4f4;border-radius:50%;background:#eff6ff;color:#24589f;font-size:22px;line-height:44px;text-align:center;">✓</div>
                          <h1 style="margin:18px 0 12px;font-size:25px;line-height:1.35;font-weight:700;letter-spacing:-.5px;color:#10213a;">邮件配置已经生效</h1>
                          <p style="margin:0;font-size:14px;line-height:1.8;color:#465a76;">这是一封来自「{{siteName}}」的测试邮件。收到它，说明 SMTP 连接、发件地址与邮件模板都能正常工作。</p>
                          <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="margin-top:24px;background:#f2f7fd;border:1px solid #e0e9f4;border-radius:8px;">
                            <tr><td style="padding:16px 18px;font-size:11px;line-height:1.7;color:#7f91a8;">发送时间<br><strong style="display:block;margin-top:3px;font-size:12px;color:#29415f;">{{sentAt}}</strong></td></tr>
                          </table>
                        </td></tr>
                        <tr><td style="padding:18px 32px 22px;border-top:1px solid #e4edf7;background:#f8fbff;">
                          <p style="margin:0;font-size:11px;line-height:1.7;color:#7f91a8;">此邮件由 {{siteName}} 自动发送。<br>© {{year}} {{siteName}} · <a href="{{siteUrl}}" style="color:#24589f;text-decoration:none;">{{siteUrl}}</a></p>
                        </td></tr>
                      </table>
                    </td></tr>
                  </table>
                </body></html>
                """);
        return map;
    }

    /** 1.10 起替换旧版默认模板：1.8 米白底与 1.9.1 墨绿底都会迁移到蓝色。 */
    @EventListener(ApplicationReadyEvent.class)
    public void migrateLegacyTemplates() {
        Map<String, String> current = siteConfigService.get();
        String registration = current.getOrDefault("mailTemplateRegistrationHtml", "");
        boolean legacy18 = registration.contains("#f2f7f4");
        boolean legacy19 = registration.contains("#f5f6f4") && registration.contains("#2f6a5a");
        if (!legacy18 && !legacy19) {
            return;
        }
        Map<String, String> next = new LinkedHashMap<>();
        next.put("mailTemplateRegistrationSubject", DEFAULTS.get("mailTemplateRegistrationSubject"));
        next.put("mailTemplateResetSubject", DEFAULTS.get("mailTemplateResetSubject"));
        next.put("mailTemplateTestSubject", DEFAULTS.get("mailTemplateTestSubject"));
        next.put("mailTemplateRegistrationHtml", DEFAULTS.get("mailTemplateRegistrationHtml"));
        next.put("mailTemplateResetHtml", DEFAULTS.get("mailTemplateResetHtml"));
        next.put("mailTemplateTestHtml", DEFAULTS.get("mailTemplateTestHtml"));
        siteConfigService.update(next);
        log.info(legacy18
                ? "已将 1.8 旧邮件模板替换为 1.10 蓝色模板"
                : "已将 1.9.1 墨绿邮件模板替换为 1.10 蓝色模板");
    }
}
