package com.deek.blog.dto;

import java.time.LocalDateTime;

public class NotificationDtos {

    public record NotificationReq(String title, String content, String type, Boolean pinned, Boolean published) {
    }

    public record NotificationView(Long id, String title, String content, String type,
                                   boolean pinned, boolean published,
                                   LocalDateTime createdAt, LocalDateTime updatedAt) {
    }
}
