package com.deek.blog.dto;

import java.time.LocalDateTime;
import java.util.List;

public class PostDtos {

    public record PostReq(String title, String summary, String content, String cover,
                          String tags, String status, Boolean top) {
    }

    public record PostView(Long id, String title, String summary, String cover, String tags, String status,
                           long views, long likes, long favorites, long comments, boolean top,
                           LocalDateTime createdAt, LocalDateTime updatedAt,
                           Long authorId, String authorName, String authorAvatar,
                           String reviewNote, LocalDateTime reviewedAt, String reviewedBy,
                           String commentSummary, LocalDateTime commentSummaryAt) {
    }

    public record PostDetail(PostView post, String content, boolean liked, boolean favorited) {
    }

    public record PageResp<T>(List<T> records, long total, int page, int size) {
    }

    public record TagView(String name, long count, String color, String description) {
    }

    public record StatsResp(long posts, long users, long comments, long totalViews) {
    }
}
