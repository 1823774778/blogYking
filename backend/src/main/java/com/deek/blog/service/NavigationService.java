package com.deek.blog.service;

import com.deek.blog.common.BizException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 管理前台顶部导航标签。内容存放在站点配置中，避免为少量链接单独增加数据表。 */
@Service
public class NavigationService {

    public record NavItem(String label, String path, Boolean visible, Boolean newTab) {
    }

    private static final int MAX_ITEMS = 8;
    private static final int MAX_JSON_LENGTH = 4000;

    private final SiteConfigService siteConfigService;
    private final ObjectMapper objectMapper;

    public NavigationService(SiteConfigService siteConfigService, ObjectMapper objectMapper) {
        this.siteConfigService = siteConfigService;
        this.objectMapper = objectMapper;
    }

    public List<NavItem> list() {
        String raw = siteConfigService.get().getOrDefault("frontNavItems", "").trim();
        if (raw.isBlank()) return defaults();
        try {
            List<NavItem> items = objectMapper.readValue(raw, new TypeReference<>() {});
            if (items == null || items.isEmpty()) return defaults();
            return normalize(items);
        } catch (Exception e) {
            return defaults();
        }
    }

    public List<NavItem> save(List<NavItem> items) {
        if (items == null || items.isEmpty()) {
            throw new BizException("至少保留一个前台导航标签");
        }
        List<NavItem> normalized = normalize(items);
        if (normalized.stream().noneMatch(item -> Boolean.TRUE.equals(item.visible()))) {
            throw new BizException("至少保留一个在前台显示的导航标签");
        }
        try {
            String json = objectMapper.writeValueAsString(normalized);
            if (json.length() > MAX_JSON_LENGTH) throw new BizException("导航标签内容过长，请精简后再保存");
            siteConfigService.update(Map.of("frontNavItems", json));
            return list();
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("保存导航标签失败");
        }
    }

    private List<NavItem> normalize(List<NavItem> items) {
        if (items.size() > MAX_ITEMS) throw new BizException("前台导航最多支持 8 个标签");
        List<NavItem> result = new ArrayList<>();
        Map<String, Boolean> seenPaths = new LinkedHashMap<>();
        for (NavItem item : items) {
            if (item == null) continue;
            String label = trim(item.label());
            String path = trim(item.path());
            if (label.isBlank()) throw new BizException("导航标签名称不能为空");
            if (label.length() > 24) throw new BizException("导航标签名称不能超过 24 个字符");
            if (path.isBlank()) throw new BizException("导航标签必须填写跳转地址");
            if (path.length() > 300) throw new BizException("导航标签地址不能超过 300 个字符");
            validatePath(path);
            if (seenPaths.put(path, Boolean.TRUE) != null) {
                throw new BizException("导航标签地址不能重复");
            }
            result.add(new NavItem(label, path, item.visible() == null || item.visible(), Boolean.TRUE.equals(item.newTab())));
        }
        if (result.isEmpty()) throw new BizException("至少保留一个前台导航标签");
        return result;
    }

    private void validatePath(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            try {
                URI uri = URI.create(path);
                if (uri.getHost() == null || uri.getHost().isBlank()) {
                    throw new BizException("外链地址格式不正确");
                }
            } catch (IllegalArgumentException e) {
                throw new BizException("外链地址格式不正确");
            }
            return;
        }
        if (!path.startsWith("/") || path.startsWith("//") || path.contains("\\")) {
            throw new BizException("站内地址必须以 / 开头，外链必须以 http:// 或 https:// 开头");
        }
    }

    private List<NavItem> defaults() {
        return List.of(
                new NavItem("首页", "/", true, false),
                new NavItem("关于", "/about", true, false)
        );
    }

    private String trim(String value) {
        return String.valueOf(value == null ? "" : value).trim();
    }
}
