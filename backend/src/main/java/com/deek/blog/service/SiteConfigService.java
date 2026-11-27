package com.deek.blog.service;

import com.deek.blog.common.BizException;
import com.deek.blog.entity.SiteConfig;
import com.deek.blog.repository.SiteConfigRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class SiteConfigService {

    private static final Set<String> PUBLIC_KEYS = Set.of(
            "siteName", "siteDesc", "siteKeywords", "siteUrl", "announcement", "announcementEnabled",
            "announcementTitle", "announcementLink", "heroTitle", "heroSubtitle",
            "policeRecord", "customHead", "customFooter", "footer",
            "icp", "github", "email", "about", "avatar", "logo",
            "frontNavItems",
            "musicEnabled", "musicSource", "musicUrl", "musicTitle", "musicArtist", "musicCover",
            "musicPlaylistUrl", "musicPlaylistTitle", "musicPlaylistPlatform",
            "musicSourceUrl", "musicSourceName", "musicSourceVersion", "musicSourceDescription",
            "musicSourcePlatforms", "musicSourceQualities", "musicSourceCheckedAt",
            "musicSourceType", "musicSourceStatus", "musicSourceVerifiedAt", "musicSourceLocalFileName",
            "musicContentMode",
            "commentReview", "commentEnabled",
            "registrationEnabled", "registrationVerifyEmail", "passwordResetEnabled",
            "registrationInviteEnabled", "registrationEmailAllowlist",
            "registrationMinPasswordLength", "registrationMaxUsers",
            "registrationApproval", "registrationAutoLogin",
            "registrationNotice", "registrationSuccessMessage", "registrationClosedMessage",
            "maintenanceEnabled", "readOnlyMode", "maintenanceMessage", "readOnlyMessage");

    private static final Set<String> ALLOWED_KEYS = Set.of(
            "siteName", "siteDesc", "siteKeywords", "siteUrl", "announcement", "announcementEnabled",
            "announcementTitle", "announcementLink", "heroTitle", "heroSubtitle",
            "policeRecord", "customHead", "customFooter", "footer",
            "icp", "github", "email", "about", "avatar", "logo",
            "frontNavItems",
            "musicEnabled", "musicSource", "musicUrl", "musicTitle", "musicArtist", "musicCover",
            "musicPlaylistUrl", "musicPlaylistTitle", "musicPlaylistPlatform",
            "musicSourceUrl", "musicSourceName", "musicSourceVersion", "musicSourceDescription",
            "musicSourcePlatforms", "musicSourceQualities", "musicSourceCheckedAt",
            "musicSourceType", "musicSourceStatus", "musicSourceVerifiedAt", "musicSourceLocalFileName",
            "musicSourceScriptPath", "musicSourceScriptHash",
            "musicContentMode",
            "commentReview", "commentEnabled",
            "notifyCommentsEnabled", "notifyLikesEnabled", "notifyFavoritesEnabled",
            "notifyUpdatesEnabled", "notifyNewUsersEnabled", "notifyPendingCommentsOnly",
            "registrationEnabled", "registrationVerifyEmail", "passwordResetEnabled",
            "registrationInviteEnabled", "registrationInviteCode",
            "registrationEmailAllowlist", "registrationMinPasswordLength", "registrationMaxUsers",
            "registrationApproval", "registrationAutoLogin", "registrationNotice",
            "registrationSuccessMessage", "registrationClosedMessage", "registrationDefaultBio",
            "maintenanceEnabled", "readOnlyMode", "maintenanceMessage", "readOnlyMessage",
            "smtpEnabled", "smtpHost", "smtpPort", "smtpUsername", "smtpPassword",
            "smtpSenderName", "smtpSsl", "smtpStarttls",
            "mailTemplateRegistrationSubject", "mailTemplateRegistrationHtml",
            "mailTemplateResetSubject", "mailTemplateResetHtml",
            "mailTemplateTestSubject", "mailTemplateTestHtml",
            "aiEnabled", "aiProvider", "aiBaseUrl", "aiModel", "aiApiKey",
            "aiArticleEnabled", "aiCommentEnabled", "aiSummaryEnabled", "aiAnnouncementEnabled",
            "aiArticleGeneratePrompt", "aiArticleReviewPrompt",
            "aiCommentReviewPrompt", "aiSummaryPrompt",
            "aiAnnouncementPrompt",
            "storageProvider", "storageEndpoint", "storageBucket", "storageRegion",
            "storageAccessKey", "storageSecretKey", "storagePublicBaseUrl",
            "storagePathPrefix", "storagePathStyle", "storageUsername", "storagePassword",
            "otaDownloadProxyPrefix");

    private final SiteConfigRepository repository;
    private final String fallbackSiteUrl;

    public SiteConfigService(SiteConfigRepository repository,
                             @Value("${app.site-url:http://localhost:8080}") String fallbackSiteUrl) {
        this.repository = repository;
        this.fallbackSiteUrl = String.valueOf(fallbackSiteUrl == null ? "" : fallbackSiteUrl).trim();
    }

    public Map<String, String> defaults() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("siteName", "墨记");
        map.put("siteDesc", "一个写字的地方：代码、生活与漫长的思考。");
        map.put("siteKeywords", "个人博客,Java,Spring Boot,Vue,技术随笔");
        map.put("siteUrl", "");
        map.put("announcement", "");
        map.put("announcementEnabled", "false");
        map.put("announcementTitle", "站点公告");
        map.put("announcementLink", "");
        map.put("heroTitle", "");
        map.put("heroSubtitle", "");
        map.put("policeRecord", "");
        map.put("customHead", "");
        map.put("customFooter", "");
        map.put("footer", "用文字抵抗遗忘");
        map.put("icp", "");
        map.put("github", "");
        map.put("email", "");
        map.put("avatar", "");
        map.put("logo", "");
        map.put("frontNavItems", "[{\"label\":\"首页\",\"path\":\"/\",\"visible\":true,\"newTab\":false},"
                + "{\"label\":\"关于\",\"path\":\"/about\",\"visible\":true,\"newTab\":false}]");
        map.put("musicEnabled", "false");
        map.put("musicSource", "audio");
        map.put("musicUrl", "");
        map.put("musicTitle", "");
        map.put("musicArtist", "");
        map.put("musicCover", "");
        map.put("musicPlaylistUrl", "");
        map.put("musicPlaylistTitle", "");
        map.put("musicPlaylistPlatform", "netease");
        map.put("musicSourceUrl", "");
        map.put("musicSourceName", "");
        map.put("musicSourceVersion", "");
        map.put("musicSourceDescription", "");
        map.put("musicSourcePlatforms", "");
        map.put("musicSourceQualities", "128k,320k,flac");
        map.put("musicSourceCheckedAt", "");
        map.put("musicSourceType", "");
        map.put("musicSourceStatus", "");
        map.put("musicSourceVerifiedAt", "");
        map.put("musicSourceLocalFileName", "");
        map.put("musicSourceScriptPath", "");
        map.put("musicSourceScriptHash", "");
        map.put("musicContentMode", "source");
        map.put("commentReview", "false");
        map.put("commentEnabled", "true");
        map.put("notifyCommentsEnabled", "true");
        map.put("notifyLikesEnabled", "true");
        map.put("notifyFavoritesEnabled", "true");
        map.put("notifyUpdatesEnabled", "true");
        map.put("notifyNewUsersEnabled", "true");
        map.put("notifyPendingCommentsOnly", "false");
        map.put("registrationEnabled", "true");
        map.put("registrationVerifyEmail", "true");
        map.put("passwordResetEnabled", "true");
        map.put("registrationInviteEnabled", "false");
        map.put("registrationInviteCode", "");
        map.put("registrationEmailAllowlist", "");
        map.put("registrationMinPasswordLength", "6");
        map.put("registrationMaxUsers", "0");
        map.put("registrationApproval", "false");
        map.put("registrationAutoLogin", "true");
        map.put("registrationNotice", "");
        map.put("registrationSuccessMessage", "");
        map.put("registrationClosedMessage", "站长暂时关闭了新用户注册。你仍然可以登录已有账号，或稍后再来看看。");
        map.put("registrationDefaultBio", "");
        map.put("maintenanceEnabled", "false");
        map.put("readOnlyMode", "false");
        map.put("maintenanceMessage", "站点正在维护升级，预计很快恢复。请稍后刷新页面。");
        map.put("readOnlyMessage", "站点当前为只读模式，浏览不受影响，暂时不能发布内容、评论或修改资料。");
        map.put("about", """
                这里记录我在工程与生活中的一些片段，也记录这套博客本身的生长过程。

                ## 关于这个项目

                「墨记」是一个持续迭代的个人博客项目，目标是把写作、阅读、评论和站点管理放进一套轻量、可自托管的系统里。它不依赖大型内容平台，文章、站点配置和文件都保存在自己的数据库与存储里。

                ### 技术栈

                - 后端：Spring Boot 4、Java 17、Spring Security、JPA
                - 前端：Vue 3、Vite、原生 CSS
                - 数据：默认 H2，可切换 MySQL
                - 部署：单 jar 运行，支持后台 OTA 更新与 GitHub Release

                ### 已经具备

                文章与标签、评论审核、通知铃铛、站点音乐（LX 音源与本地音乐）、本地与 WebDAV/OpenList 存储、SMTP 邮件、找回密码、AI 写作助手、版本更新提示，以及可配置的前台导航和访问域名。

                ### 为什么写

                把想清楚的事情留给以后的自己，也让每一次改动都有迹可循。这里没有固定的更新频率，只有持续把事情做完整的耐心。
                """);
        map.put("smtpEnabled", "false");
        map.put("smtpHost", "");
        map.put("smtpPort", "465");
        map.put("smtpUsername", "");
        map.put("smtpPassword", "");
        map.put("smtpSenderName", "墨记");
        map.put("smtpSsl", "true");
        map.put("smtpStarttls", "false");
        map.put("mailTemplateRegistrationSubject", "{{siteName}} 的注册验证码");
        map.put("mailTemplateRegistrationHtml", "");
        map.put("mailTemplateResetSubject", "重置 {{siteName}} 账户密码");
        map.put("mailTemplateResetHtml", "");
        map.put("mailTemplateTestSubject", "{{siteName}} 邮件配置测试");
        map.put("mailTemplateTestHtml", "");
        map.put("aiEnabled", "false");
        map.put("aiProvider", "OpenAI 兼容");
        map.put("aiBaseUrl", "https://api.openai.com/v1");
        map.put("aiModel", "");
        map.put("aiApiKey", "");
        map.put("aiArticleEnabled", "true");
        map.put("aiCommentEnabled", "true");
        map.put("aiSummaryEnabled", "true");
        map.put("aiAnnouncementEnabled", "true");
        map.put("aiArticleGeneratePrompt",
                "你是中文博客作者，擅长写结构清晰、可读性强的技术或生活文章。"
                        + "标题要准确，正文要有小标题和自然段落，代码示例必须能运行，不要编造事实。");
        map.put("aiArticleReviewPrompt",
                "你是严格但友善的中文博客编辑，审核意见要具体、可执行。"
                        + "重点检查标题与正文是否一致、结构是否清晰、事实与代码风险、表达是否冗余，以及是否有不适合公开发布的内容。");
        map.put("aiCommentReviewPrompt",
                "你是中文博客评论审核员，只处理广告、攻击、隐私泄露、违法和明显离题内容，不因观点不同而拒绝。");
        map.put("aiSummaryPrompt",
                "你是中文博客编辑。摘要要准确概括正文，不添加原文没有的信息，语言自然、克制。");
        map.put("aiAnnouncementPrompt",
                "你是博客站长的文字助理。公告要短、清楚、有行动指向，不制造焦虑，不编造活动中没有的信息。");
        map.put("storageProvider", "local");
        map.put("storageEndpoint", "");
        map.put("storageBucket", "");
        map.put("storageRegion", "auto");
        map.put("storageAccessKey", "");
        map.put("storageSecretKey", "");
        map.put("storagePublicBaseUrl", "");
        map.put("storagePathPrefix", "blog");
        map.put("storagePathStyle", "true");
        map.put("storageUsername", "");
        map.put("storagePassword", "");
        map.put("otaDownloadProxyPrefix", "");
        return map;
    }

    public Map<String, String> get() {
        Map<String, String> map = defaults();
        repository.findAll().forEach(c -> {
            if (c.getCfgKey() != null && ALLOWED_KEYS.contains(c.getCfgKey()) && c.getCfgValue() != null) {
                map.put(c.getCfgKey(), c.getCfgValue());
            }
        });
        return map;
    }

    /** 只返回前台可见的站点配置，绝不把 SMTP 密码、AI 密钥暴露给公共接口。 */
    public Map<String, String> publicConfig() {
        Map<String, String> all = get();
        Map<String, String> visible = new LinkedHashMap<>();
        for (String key : PUBLIC_KEYS) {
            visible.put(key, all.getOrDefault(key, ""));
        }
        return visible;
    }

    /** 站点对外访问地址。后台配置优先，未配置时回退到启动参数 app.site-url。 */
    public String publicSiteUrl() {
        String configured = get().getOrDefault("siteUrl", "").trim();
        try {
            if (!configured.isBlank()) return normalizeSiteUrl(configured);
            return normalizeSiteUrl(fallbackSiteUrl);
        } catch (BizException e) {
            return normalizeFallbackSiteUrl();
        }
    }

    private String normalizeFallbackSiteUrl() {
        try {
            return normalizeSiteUrl(fallbackSiteUrl);
        } catch (BizException e) {
            return "http://localhost:8080";
        }
    }

    /**
     * 访问域名允许只填域名，也允许带 http(s) 协议和子路径；统一去掉末尾斜杠。
     * 不接受账号、查询参数、锚点和非 HTTP 协议，避免生成邮件链接时夹带意外内容。
     */
    private String normalizeSiteUrl(String raw) {
        String value = String.valueOf(raw == null ? "" : raw).trim();
        if (value.isBlank()) return "";
        if (value.contains(" ") || value.contains("\n") || value.contains("\r")) {
            throw new BizException("访问域名不能包含空白字符");
        }
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.matches("^[a-z][a-z0-9+.-]*://.*")
                && !lower.startsWith("http://")
                && !lower.startsWith("https://")) {
            throw new BizException("访问域名只支持 http:// 或 https://");
        }
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            value = "https://" + value;
        }
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException e) {
            throw new BizException("访问域名格式不正确");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new BizException("访问域名只支持 http:// 或 https://");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new BizException("访问域名缺少有效主机名");
        }
        if (uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new BizException("访问域名不能包含账号、查询参数或锚点");
        }
        String host = uri.getHost();
        String authority = host;
        if (uri.getPort() > 0) authority += ":" + uri.getPort();
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        while (path.endsWith("/") && path.length() > 1) {
            path = path.substring(0, path.length() - 1);
        }
        if (path.equals("/")) path = "";
        return scheme + "://" + authority + path;
    }

    /** 仅供后端内部状态使用，不进入后台配置与公开接口。 */
    public String internalValue(String key, String fallback) {
        if (key == null || key.isBlank()) {
            return fallback;
        }
        return repository.findById(key)
                .map(SiteConfig::getCfgValue)
                .filter(v -> v != null && !v.isBlank())
                .orElse(fallback);
    }

    @Transactional
    public void setInternalValue(String key, String value) {
        if (key == null || key.isBlank()) {
            return;
        }
        String v = value == null ? "" : value;
        if (v.length() > 4000) {
            v = v.substring(0, 4000);
        }
        repository.save(new SiteConfig(key, v));
    }

    @Transactional
    public Map<String, String> update(Map<String, String> values) {
        if (values == null) {
            return get();
        }
        values.forEach((key, value) -> {
            if (key == null || !ALLOWED_KEYS.contains(key)) {
                return;
            }
            String v = value == null ? "" : value;
            if ("siteUrl".equals(key)) {
                v = normalizeSiteUrl(v);
            }
            if (v.length() > 4000) {
                v = v.substring(0, 4000);
            }
            repository.save(new SiteConfig(key, v));
        });
        return get();
    }
}
