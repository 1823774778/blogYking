package com.deek.blog.dto;

import java.time.LocalDateTime;

public class CommentDtos {

    public record CommentReq(String content, Long parentId) {
    }

    public record CommentView(Long id, String content, LocalDateTime createdAt, Long parentId,
                              Long userId, String nickname, String avatar, boolean mine,
                              String status, Long postId, String postTitle) {
    }
}
