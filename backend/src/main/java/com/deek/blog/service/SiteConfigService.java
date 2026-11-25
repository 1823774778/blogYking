package com.deek.blog.service;

import com.deek.blog.entity.SiteConfig;
import com.deek.blog.repository.SiteConfigRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Service
public class SiteConfigService {

    private static final Set<String> PUBLIC_KEYS = Set.of(
            "siteName", "siteDesc", "siteKeywords", "announcement", "announcementEnabled",
            "announcementTitle", "announcementLink", "heroTitle", "heroSubtitle",
            "policeRecord", "customHead", "customFooter", "footer",
            "icp", "github", "email", "about", "avatar", "logo",
            "musicEnabled", "musicSource", "musicUrl", "musicTitle", "musicArtist", "musicCover",
            "musicPlaylistUrl", "musicPlaylistTitle", "musicPlaylistPlatform",
            "musicSourceUrl", "musicSourceName", "musicSourceVersion", "musicSourceDescription",
            "musicSourcePlatforms", "musicSourceQualities", "musicSourceCheckedAt", "commentReview",
            "registrationEnabled", "registrationVerifyEmail", "passwordResetEnabled",
            "registrationInviteEnabled", "registrationEmailAllowlist",
            "registrationMinPasswordLength", "registrationMaxUsers",
            "registrationApproval", "registrationAutoLogin",
            "registrationNotice", "registrationSuccessMessage", "registrationClosedMessage",
            "maintenanceEnabled", "readOnlyMode", "maintenanceMessage", "readOnlyMessage");

    private static final Set<String> ALLOWED_KEYS = Set.of(
            "siteName", "siteDesc", "siteKeywords", "announcement", "announcementEnabled",
            "announcementTitle", "announcementLink", "heroTitle", "heroSubtitle",
            "policeRecord", "customHead", "customFooter", "footer",
            "icp", "github", "email", "about", "avatar", "logo",
            "musicEnabled", "musicSource", "musicUrl", "musicTitle", "musicArtist", "musicCover",
            "musicPlaylistUrl", "musicPlaylistTitle", "musicPlaylistPlatform",
            "musicSourceUrl", "musicSourceName", "musicSourceVersion", "musicSourceDescription",
            "musicSourcePlatforms", "musicSourceQualities", "musicSourceCheckedAt", "commentReview",
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
            "aiCommentReviewPrompt", "aiCommentSummaryPrompt", "aiSummaryPrompt",
            "aiAnnouncementPrompt",
            "storageProvider", "storageEndpoint", "storageBucket", "storageRegion",
            "storageAccessKey", "storageSecretKey", "storagePublicBaseUrl",
            "storagePathPrefix", "storagePathStyle", "storageUsername", "storagePassword",
            "otaDownloadProxyPrefix");

    private final SiteConfigRepository repository;

    public SiteConfigService(SiteConfigRepository repository) {
        this.repository = repository;
    }

    public Map<String, String> defaults() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("siteName", "墨记");
        map.put("siteDesc", "一个写字的地方：代码、生活与漫长的思考。");
        map.put("siteKeywords", "个人博客,Java,Spring Boot,Vue,技术随笔");
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
        map.put("commentReview", "false");
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
        map.put("about", "这里记录我在工程与生活中的一些片段。写下来，是为了把想清楚的事情留给以后的自己。");
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
        map.put("aiCommentSummaryPrompt",
                "你是中文社区编辑，总结要克制、客观、不臆造评论中没有的信息，保留读者真正关心和有分歧的点。");
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
            if (v.length() > 4000) {
                v = v.substring(0, 4000);
            }
            repository.save(new SiteConfig(key, v));
        });
        return get();
    }
}
