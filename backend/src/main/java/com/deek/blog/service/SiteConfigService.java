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

    private static final Set<String> ALLOWED_KEYS = Set.of(
            "siteName", "siteDesc", "siteKeywords", "announcement", "footer",
            "icp", "github", "email", "about", "avatar");

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
        map.put("about", "这里记录我在工程与生活中的一些片段。写下来，是为了把想清楚的事情留给以后的自己。");
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
