package com.deek.blog.dto;

import java.time.LocalDateTime;

public class TagDtos {

    public record TagReq(String name, String slug, String description, String color,
                         Integer sortOrder, Boolean visible) {
    }

    public record TagAdminView(Long id, String name, String slug, String description, String color,
                               int sortOrder, boolean visible, long count,
                               LocalDateTime createdAt, LocalDateTime updatedAt) {
    }
}
