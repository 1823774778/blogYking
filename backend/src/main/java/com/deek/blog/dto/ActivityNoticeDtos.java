package com.deek.blog.dto;

import java.time.LocalDateTime;

public class ActivityNoticeDtos {

    public record ActivityNoticeView(Long id, String type, String title, String content,
                                     String link, String actorName, Long postId,
                                     boolean read, LocalDateTime createdAt) {
    }
}
