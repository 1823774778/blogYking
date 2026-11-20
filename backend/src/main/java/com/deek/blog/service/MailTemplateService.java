package com.deek.blog.service;

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
        map.put("mailTemplateRegistrationSubject", "「{{siteName}}」注册验证码：{{code}}");
        map.put("mailTemplateResetSubject", "重置你的「{{siteName}}」密码");
        map.put("mailTemplateTestSubject", "「{{siteName}}」邮件配置测试");
        map.put("mailTemplateRegistrationHtml", """
                <!doctype html>
                <html><body style="margin:0;background:#f2f7f4;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI','PingFang SC','Microsoft YaHei',sans-serif;color:#17382e;">
                  <div style="display:none;max-height:0;overflow:hidden;opacity:0;">你的验证码是 {{code}}，{{expireMinutes}} 分钟内有效。</div>
                  <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background:#f2f7f4;padding:32px 16px;">
                    <tr><td align="center">
                      <div style="width:100%;max-width:560px;border-radius:18px;overflow:hidden;background:#ffffff;box-shadow:0 12px 40px rgba(21,63,52,.10);">
                        <div style="background:#153f34;padding:26px 30px;">
                          <table role="presentation" width="100%"><tr>
                            <td style="font-size:13px;letter-spacing:2px;color:#9fd9c4;text-transform:uppercase;">{{siteName}}</td>
                            <td align="right" style="font-size:12px;color:#c9e9dc;">注册验证</td>
                          </tr></table>
                        </div>
                        <div style="padding:38px 34px 32px;">
                          <p style="margin:0 0 10px;font-size:13px;color:#6c887e;">你好，欢迎来到 {{siteName}}。</p>
                          <h1 style="margin:0 0 22px;font-size:24px;line-height:1.4;color:#143f34;">你的注册验证码</h1>
                          <div style="padding:18px 20px;border:1px solid #cfe7dc;border-radius:14px;background:#f4fbf7;text-align:center;">
                            <span style="font-family:'SFMono-Regular',Consolas,monospace;font-size:34px;font-weight:700;letter-spacing:8px;color:#167d61;">{{code}}</span>
                          </div>
                          <p style="margin:22px 0 0;font-size:14px;line-height:1.8;color:#4d6d62;">验证码 <strong style="color:#17382e;">{{expireMinutes}} 分钟内有效</strong>。请勿将验证码转发给他人。</p>
                          <p style="margin:14px 0 0;font-size:12px;line-height:1.7;color:#8aa39a;">如果不是你本人操作，请直接忽略这封邮件。</p>
                        </div>
                        <div style="padding:18px 34px 24px;border-top:1px solid #edf3f0;background:#fbfdfc;">
                          <p style="margin:0;font-size:12px;line-height:1.7;color:#8aa39a;">此邮件由 {{siteName}} 自动发送，请勿直接回复。<br>© {{year}} {{siteName}}</p>
                        </div>
                      </div>
                      <p style="margin:18px 0 0;font-size:11px;color:#9db1aa;">{{siteUrl}}</p>
                    </td></tr>
                  </table>
                </body></html>
                """);
        map.put("mailTemplateResetHtml", """
                <!doctype html>
                <html><body style="margin:0;background:#f2f7f4;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI','PingFang SC','Microsoft YaHei',sans-serif;color:#17382e;">
                  <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background:#f2f7f4;padding:32px 16px;">
                    <tr><td align="center">
                      <div style="width:100%;max-width:560px;border-radius:18px;overflow:hidden;background:#ffffff;box-shadow:0 12px 40px rgba(21,63,52,.10);">
                        <div style="background:#153f34;padding:26px 30px;">
                          <table role="presentation" width="100%"><tr>
                            <td style="font-size:13px;letter-spacing:2px;color:#9fd9c4;text-transform:uppercase;">{{siteName}}</td>
                            <td align="right" style="font-size:12px;color:#c9e9dc;">密码重置</td>
                          </tr></table>
                        </div>
                        <div style="padding:38px 34px 32px;">
                          <p style="margin:0 0 10px;font-size:13px;color:#6c887e;">你好，</p>
                          <h1 style="margin:0 0 18px;font-size:24px;line-height:1.4;color:#143f34;">需要重置密码吗？</h1>
                          <p style="margin:0 0 26px;font-size:14px;line-height:1.8;color:#4d6d62;">我们收到了重置「{{siteName}}」账号密码的请求。点击下面的按钮设置新密码，链接在 <strong style="color:#17382e;">{{expireMinutes}} 分钟内有效</strong>。</p>
                          <a href="{{link}}" style="display:inline-block;padding:13px 26px;border-radius:10px;background:#167d61;color:#ffffff;text-decoration:none;font-size:14px;font-weight:700;">设置新密码</a>
                          <p style="margin:24px 0 0;font-size:12px;line-height:1.8;color:#8aa39a;">按钮无法点击时，复制此链接到浏览器打开：<br><a href="{{link}}" style="color:#167d61;word-break:break-all;">{{link}}</a></p>
                        </div>
                        <div style="padding:18px 34px 24px;border-top:1px solid #edf3f0;background:#fbfdfc;">
                          <p style="margin:0;font-size:12px;line-height:1.7;color:#8aa39a;">如果这不是你本人的操作，请忽略本邮件，密码不会被修改。<br>© {{year}} {{siteName}}</p>
                        </div>
                      </div>
                    </td></tr>
                  </table>
                </body></html>
                """);
        map.put("mailTemplateTestHtml", """
                <!doctype html>
                <html><body style="margin:0;background:#f2f7f4;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI','PingFang SC','Microsoft YaHei',sans-serif;color:#17382e;">
                  <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background:#f2f7f4;padding:32px 16px;">
                    <tr><td align="center">
                      <div style="width:100%;max-width:560px;border-radius:18px;overflow:hidden;background:#ffffff;box-shadow:0 12px 40px rgba(21,63,52,.10);">
                        <div style="background:#153f34;padding:26px 30px;">
                          <table role="presentation" width="100%"><tr>
                            <td style="font-size:13px;letter-spacing:2px;color:#9fd9c4;text-transform:uppercase;">{{siteName}}</td>
                            <td align="right" style="font-size:12px;color:#c9e9dc;">SMTP TEST</td>
                          </tr></table>
                        </div>
                        <div style="padding:38px 34px 32px;">
                          <div style="width:48px;height:48px;border-radius:14px;background:#e7f7f0;color:#167d61;font-size:24px;line-height:48px;text-align:center;">✓</div>
                          <h1 style="margin:18px 0 12px;font-size:24px;line-height:1.4;color:#143f34;">邮件配置已生效</h1>
                          <p style="margin:0;font-size:14px;line-height:1.8;color:#4d6d62;">这是一封来自「{{siteName}}」的测试邮件。收到它，说明 SMTP 连接、发件人和邮件模板都能正常工作。</p>
                          <div style="margin-top:24px;padding:16px 18px;border-radius:12px;background:#f4fbf7;border:1px solid #d9eee4;">
                            <p style="margin:0;font-size:12px;line-height:1.8;color:#6c887e;">发送时间<br><strong style="font-size:13px;color:#17382e;">{{sentAt}}</strong></p>
                          </div>
                        </div>
                        <div style="padding:18px 34px 24px;border-top:1px solid #edf3f0;background:#fbfdfc;">
                          <p style="margin:0;font-size:12px;line-height:1.7;color:#8aa39a;">此邮件由 {{siteName}} 自动发送。<br>© {{year}} {{siteName}}</p>
                        </div>
                      </div>
                    </td></tr>
                  </table>
                </body></html>
                """);
        return map;
    }
}
