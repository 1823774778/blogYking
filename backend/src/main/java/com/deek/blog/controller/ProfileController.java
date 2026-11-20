package com.deek.blog.controller;

import com.deek.blog.common.ApiResponse;
import com.deek.blog.dto.PostDtos.PostView;
import com.deek.blog.security.SecurityUtils;
import com.deek.blog.service.PostService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/me")
public class ProfileController {

    private final PostService postService;

    public ProfileController(PostService postService) {
        this.postService = postService;
    }

    @GetMapping("/favorites")
    public ApiResponse<List<PostView>> favorites() {
        return ApiResponse.ok(postService.myFavorites(SecurityUtils.requireUserId()));
    }

    @GetMapping("/likes")
    public ApiResponse<List<PostView>> likes() {
        return ApiResponse.ok(postService.myLikes(SecurityUtils.requireUserId()));
    }
}
