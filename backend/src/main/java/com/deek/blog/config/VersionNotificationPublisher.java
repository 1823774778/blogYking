package com.deek.blog.config;

import com.deek.blog.dto.NotificationDtos.NotificationReq;
import com.deek.blog.repository.UserRepository;
import com.deek.blog.service.ActivityNoticeService;
import com.deek.blog.service.NotificationService;
import com.deek.blog.service.OtaService;
import com.deek.blog.service.SiteConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 版本变化时自动发布一条站内更新通知，并同步写一条后台互动通知。
 * 用 site_config 中的 lastNotifiedVersion 去重，重复重启不会重复发布。
 */
@Component
@Order(5)
public class VersionNotificationPublisher implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(VersionNotificationPublisher.class);
    private static final String LAST_KEY = "lastNotifiedVersion";

    private final OtaService otaService;
    private final NotificationService notificationService;
    private final ActivityNoticeService activityNoticeService;
    private final SiteConfigService siteConfigService;
    private final UserRepository userRepository;

    public VersionNotificationPublisher(OtaService otaService, NotificationService notificationService,
                                        ActivityNoticeService activityNoticeService,
                                        SiteConfigService siteConfigService, UserRepository userRepository) {
        this.otaService = otaService;
        this.notificationService = notificationService;
        this.activityNoticeService = activityNoticeService;
        this.siteConfigService = siteConfigService;
        this.userRepository = userRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (userRepository.countByRole("ADMIN") == 0) {
            return;
        }
        String version = otaService.getCurrentVersion();
        if (version == null || version.isBlank() || "未知".equals(version)) {
            return;
        }
        String last = siteConfigService.internalValue(LAST_KEY, "");
        if (version.equals(last)) {
            return;
        }
        String summary = releaseSummary(version);
        String content = "站点已更新到 v" + version + "。" + summary;
        try {
            notificationService.create(new NotificationReq(
                    "博客已更新到 v" + version,
                    content,
                    "UPDATE",
                    false,
                    true
            ));
            activityNoticeService.record("UPDATE", "版本更新完成",
                    "站点已更新到 v" + version + "。" + summary,
                    "/admin/settings?tab=update", "系统", null);
            siteConfigService.setInternalValue(LAST_KEY, version);
            log.info("已发布版本更新通知：v{}", version);
        } catch (Exception e) {
            log.warn("版本更新通知发布失败：{}", e.getMessage());
        }
    }

    private String releaseSummary(String version) {
        String text = readReleaseNotes(version);
        if (text.isBlank()) {
            return "本次更新包含界面与体验优化。";
        }
        String cleaned = text.replaceAll("(?m)^#{1,6}\\s*", "")
                .replace("**", "")
                .replace("`", "")
                .replaceAll("(?m)^-\\s*", "· ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
        return cleaned.length() > 800 ? cleaned.substring(0, 800) + "…" : cleaned;
    }

    private String readReleaseNotes(String version) {
        String path = "release-notes/" + version + ".md";
        try {
            ClassPathResource resource = new ClassPathResource(path);
            if (!resource.exists()) {
                return "";
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder out = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null && out.length() < 1600) {
                    out.append(line).append('\n');
                }
                return out.toString();
            }
        } catch (Exception e) {
            return "";
        }
    }
}
