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
            "siteName", "siteDesc", "siteKeywords", "announcement", "footer",
            "icp", "github", "email", "about", "avatar", "logo",
            "musicEnabled", "musicUrl", "musicTitle", "commentReview",
            "registrationEnabled", "registrationVerifyEmail");

    private static final Set<String> ALLOWED_KEYS = Set.of(
            "siteName", "siteDesc", "siteKeywords", "announcement", "footer",
            "icp", "github", "email", "about", "avatar", "logo",
            "musicEnabled", "musicUrl", "musicTitle", "commentReview",
            "registrationEnabled", "registrationVerifyEmail",
            "smtpEnabled", "smtpHost", "smtpPort", "smtpUsername", "smtpPassword",
            "smtpFrom", "smtpSsl", "smtpStarttls",
            "aiEnabled", "aiProvider", "aiBaseUrl", "aiModel", "aiApiKey",
            "aiArticleEnabled", "aiCommentEnabled", "aiSummaryEnabled",
            "aiArticleGeneratePrompt", "aiArticleReviewPrompt",
            "aiCommentReviewPrompt", "aiCommentSummaryPrompt", "aiSummaryPrompt",
            "storageProvider", "storageEndpoint", "storageBucket", "storageRegion",
            "storageAccessKey", "storageSecretKey", "storagePublicBaseUrl",
            "storagePathPrefix", "storagePathStyle");

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
        map.put("footer", "用文字抵抗遗忘");
        map.put("icp", "");
        map.put("github", "");
        map.put("email", "");
        map.put("avatar", "");
        map.put("logo", "");
        map.put("musicEnabled", "false");
        map.put("musicUrl", "");
        map.put("musicTitle", "");
        map.put("commentReview", "false");
        map.put("registrationEnabled", "true");
        map.put("registrationVerifyEmail", "true");
        map.put("about", "这里记录我在工程与生活中的一些片段。写下来，是为了把想清楚的事情留给以后的自己。");
        map.put("smtpEnabled", "false");
        map.put("smtpHost", "");
        map.put("smtpPort", "465");
        map.put("smtpUsername", "");
        map.put("smtpPassword", "");
        map.put("smtpFrom", "");
        map.put("smtpSsl", "true");
        map.put("smtpStarttls", "false");
        map.put("aiEnabled", "false");
        map.put("aiProvider", "OpenAI 兼容");
        map.put("aiBaseUrl", "https://api.openai.com/v1");
        map.put("aiModel", "");
        map.put("aiApiKey", "");
        map.put("aiArticleEnabled", "true");
        map.put("aiCommentEnabled", "true");
        map.put("aiSummaryEnabled", "true");
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
        map.put("storageProvider", "local");
        map.put("storageEndpoint", "");
        map.put("storageBucket", "");
        map.put("storageRegion", "auto");
        map.put("storageAccessKey", "");
        map.put("storageSecretKey", "");
        map.put("storagePublicBaseUrl", "");
        map.put("storagePathPrefix", "blog");
        map.put("storagePathStyle", "true");
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
