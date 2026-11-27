package com.deek.blog.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public class NotificationDtos {

    public record NotificationReq(String title, String content, String type, Boolean pinned, Boolean published) {
    }

    public record NotificationView(Long id, String title, String content, String type,
                                   boolean pinned, boolean published,
                                   LocalDateTime createdAt, LocalDateTime updatedAt) {
    }

    public record NotificationPageView(List<NotificationView> items, long total, int page, int size,
                                       boolean hasMore, long latestId, LocalDateTime latestAt,
                                       long pinnedCount, Map<String, Long> typeCounts) {
    }
}
