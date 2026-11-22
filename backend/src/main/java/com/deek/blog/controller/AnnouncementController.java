package com.deek.blog.controller;

import com.deek.blog.common.ApiResponse;
import com.deek.blog.service.SiteConfigService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/announcements")
public class AnnouncementController {

    public record AnnouncementReq(String title, String content, String link, Boolean enabled) {}

    private final SiteConfigService siteConfigService;

    public AnnouncementController(SiteConfigService siteConfigService) {
        this.siteConfigService = siteConfigService;
    }

    @GetMapping
    public ApiResponse<Map<String, String>> get() {
        Map<String, String> config = siteConfigService.get();
        Map<String, String> data = new LinkedHashMap<>();
        data.put("title", config.getOrDefault("announcementTitle", "站点公告"));
        data.put("content", config.getOrDefault("announcement", ""));
        data.put("link", config.getOrDefault("announcementLink", ""));
        data.put("enabled", config.getOrDefault("announcementEnabled", "false"));
        return ApiResponse.ok(data);
    }

    @PutMapping
    public ApiResponse<Map<String, String>> save(@RequestBody AnnouncementReq req) {
        String content = req.content() == null ? "" : req.content().trim();
        if (content.length() > 2000) content = content.substring(0, 2000);
        String title = req.title() == null || req.title().isBlank() ? "站点公告" : req.title().trim();
        if (title.length() > 64) title = title.substring(0, 64);
        String link = req.link() == null ? "" : req.link().trim();
        if (link.length() > 512) link = link.substring(0, 512);
        Map<String, String> values = new LinkedHashMap<>();
        values.put("announcement", content);
        values.put("announcementTitle", title);
        values.put("announcementLink", link);
        values.put("announcementEnabled", Boolean.TRUE.equals(req.enabled()) ? "true" : "false");
        siteConfigService.update(values);
        return get();
    }
}
