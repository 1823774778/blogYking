package com.deek.blog.controller;

import com.deek.blog.common.ApiResponse;
import com.deek.blog.dto.CommentDtos.CommentReq;
import com.deek.blog.dto.CommentDtos.CommentView;
import com.deek.blog.entity.User;
import com.deek.blog.security.SecurityUtils;
import com.deek.blog.service.AuthService;
import com.deek.blog.service.CommentService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class CommentController {

    private final CommentService commentService;
    private final AuthService authService;

    public CommentController(CommentService commentService, AuthService authService) {
        this.commentService = commentService;
        this.authService = authService;
    }

    @GetMapping("/posts/{postId}/comments")
    public ApiResponse<List<CommentView>> list(@PathVariable Long postId) {
        return ApiResponse.ok(commentService.list(postId, SecurityUtils.currentUserId()));
    }

    @PostMapping("/posts/{postId}/comments")
    public ApiResponse<CommentView> add(@PathVariable Long postId, @RequestBody CommentReq req) {
        User user = authService.requireUser(SecurityUtils.requireUserId());
        return ApiResponse.ok(commentService.add(postId, user, req));
    }

    @DeleteMapping("/comments/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        commentService.delete(id, SecurityUtils.requireUserId(),
                SecurityUtils.hasPermission("system:comments"));
        return ApiResponse.ok();
    }
}
