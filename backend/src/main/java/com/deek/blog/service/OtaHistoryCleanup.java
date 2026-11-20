package com.deek.blog.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 更新成功后的自动清理：只在当前运行的 jar 与 OTA 记录的目标 SHA 一致时触发。
 * 保留 GitHub 历史 Release 供回档下载，清理本地备份、staging 和下载任务历史。
 */
@Component
public class OtaHistoryCleanup {

    private static final Logger log = LoggerFactory.getLogger(OtaHistoryCleanup.class);

    private final OtaService otaService;
    private final OtaDownloadService otaDownloadService;

    public OtaHistoryCleanup(OtaService otaService, OtaDownloadService otaDownloadService) {
        this.otaService = otaService;
        this.otaDownloadService = otaDownloadService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void cleanupAfterUpdate() {
        try {
            Map<String, Object> ota = otaService.cleanupHistoryAfterSuccessfulUpdate();
            if (Boolean.TRUE.equals(ota.get("skipped"))) {
                return;
            }
            Map<String, Object> downloads = otaDownloadService.clearHistoryAfterUpdate();
            log.info("更新后历史清理完成：{}, {}", ota, downloads);
        } catch (Exception e) {
            log.warn("更新后历史清理失败: {}", e.getMessage());
        }
    }
}
