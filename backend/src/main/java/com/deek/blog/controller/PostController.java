package com.deek.blog.controller;

import com.deek.blog.common.ApiResponse;
import com.deek.blog.dto.AuthDtos.ToggleResp;
import com.deek.blog.dto.PostDtos.PageResp;
import com.deek.blog.dto.PostDtos.PostDetail;
import com.deek.blog.dto.PostDtos.PostView;
import com.deek.blog.dto.PostDtos.StatsResp;
import com.deek.blog.dto.PostDtos.TagView;
import com.deek.blog.security.SecurityUtils;
import com.deek.blog.service.PostService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/posts")
public class PostController {

    private final PostService postService;

    public PostController(PostService postService) {
        this.postService = postService;
    }

    @GetMapping
    public ApiResponse<PageResp<PostView>> list(@RequestParam(required = false) String keyword,
                                              @RequestParam(required = false) String tag,
                                              @RequestParam(defaultValue = "latest") String sort,
                                              @RequestParam(defaultValue = "1") int page,
                                              @RequestParam(defaultValue = "10") int size) {
        return ApiResponse.ok(postService.list(keyword, tag, sort, page, size));
    }

    @GetMapping("/hot")
    public ApiResponse<List<PostView>> hot(@RequestParam(defaultValue = "5") int limit) {
        return ApiResponse.ok(postService.hot(limit));
    }

    @GetMapping("/tags")
    public ApiResponse<List<TagView>> tags() {
        return ApiResponse.ok(postService.tags());
    }

    @GetMapping("/stats")
    public ApiResponse<StatsResp> stats() {
        return ApiResponse.ok(postService.stats());
    }

    @GetMapping("/{id}")
    public ApiResponse<PostDetail> detail(@PathVariable Long id, HttpServletRequest request) {
        Long userId = SecurityUtils.currentUserId();
        String ip = clientIp(request);
        return ApiResponse.ok(postService.detail(id, userId, ip));
    }

    @PostMapping("/{id}/like")
    public ApiResponse<ToggleResp> like(@PathVariable Long id) {
        return ApiResponse.ok(postService.toggleLike(id, SecurityUtils.requireUserId()));
    }

    @PostMapping("/{id}/favorite")
    public ApiResponse<ToggleResp> favorite(@PathVariable Long id) {
        return ApiResponse.ok(postService.toggleFavorite(id, SecurityUtils.requireUserId()));
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }
}
