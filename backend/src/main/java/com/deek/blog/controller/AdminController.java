package com.deek.blog.controller;

import com.deek.blog.common.ApiResponse;
import com.deek.blog.common.BizException;
import com.deek.blog.dto.AuthDtos.UserView;
import com.deek.blog.dto.CommentDtos.CommentView;
import com.deek.blog.dto.PostDtos.PageResp;
import com.deek.blog.dto.PostDtos.PostReq;
import com.deek.blog.dto.PostDtos.PostView;
import com.deek.blog.dto.PostDtos.StatsResp;
import com.deek.blog.entity.User;
import com.deek.blog.repository.CommentRepository;
import com.deek.blog.repository.PostFavoriteRepository;
import com.deek.blog.repository.PostLikeRepository;
import com.deek.blog.repository.PostRepository;
import com.deek.blog.repository.UserRepository;
import com.deek.blog.security.SecurityUtils;
import com.deek.blog.service.AuthService;
import com.deek.blog.service.CommentService;
import com.deek.blog.service.MailService;
import com.deek.blog.service.PostService;
import com.deek.blog.service.SiteConfigService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    public record StatusReq(boolean enabled) {
    }

    public record RoleReq(String role) {
    }

    public record MailTestReq(String to) {
    }

    private final PostService postService;
    private final CommentService commentService;
    private final AuthService authService;
    private final SiteConfigService siteConfigService;
    private final MailService mailService;
    private final PostRepository postRepository;
    private final CommentRepository commentRepository;
    private final UserRepository userRepository;
    private final PostLikeRepository likeRepository;
    private final PostFavoriteRepository favoriteRepository;

    public AdminController(PostService postService, CommentService commentService, AuthService authService,
                           SiteConfigService siteConfigService, MailService mailService,
                           PostRepository postRepository, CommentRepository commentRepository,
                           UserRepository userRepository, PostLikeRepository likeRepository,
                           PostFavoriteRepository favoriteRepository) {
        this.postService = postService;
        this.commentService = commentService;
        this.authService = authService;
        this.siteConfigService = siteConfigService;
        this.mailService = mailService;
        this.postRepository = postRepository;
        this.commentRepository = commentRepository;
        this.userRepository = userRepository;
        this.likeRepository = likeRepository;
        this.favoriteRepository = favoriteRepository;
    }

    @GetMapping("/stats")
    public ApiResponse<Map<String, Object>> stats() {
        Map<String, Object> data = new LinkedHashMap<>();
        StatsResp base = postService.stats();
        data.put("posts", base.posts());
        data.put("drafts", postRepository.count() - base.posts());
        data.put("users", base.users());
        data.put("comments", base.comments());
        data.put("totalViews", base.totalViews());
        data.put("likes", likeRepository.count());
        data.put("favorites", favoriteRepository.count());
        return ApiResponse.ok(data);
    }

    @GetMapping("/posts")
    public ApiResponse<PageResp<PostView>> posts(@RequestParam(defaultValue = "1") int page,
                                                 @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(postService.adminList(page, size));
    }

    @PostMapping("/posts")
    public ApiResponse<PostView> createPost(@RequestBody PostReq req) {
        User author = authService.requireUser(SecurityUtils.requireUserId());
        return ApiResponse.ok(postService.create(req, author));
    }

    @PutMapping("/posts/{id}")
    public ApiResponse<PostView> updatePost(@PathVariable Long id, @RequestBody PostReq req) {
        return ApiResponse.ok(postService.update(id, req));
    }

    @DeleteMapping("/posts/{id}")
    public ApiResponse<Void> deletePost(@PathVariable Long id) {
        postService.delete(id);
        return ApiResponse.ok();
    }

    @GetMapping("/comments")
    public ApiResponse<PageResp<CommentView>> comments(@RequestParam(defaultValue = "1") int page,
                                                       @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(commentService.adminList(page, size));
    }

    @GetMapping("/users")
    public ApiResponse<PageResp<UserView>> users(@RequestParam(defaultValue = "1") int page,
                                                 @RequestParam(defaultValue = "20") int size) {
        int p = Math.max(1, page);
        int s = Math.min(100, Math.max(1, size));
        Page<User> result = userRepository.findAll(PageRequest.of(p - 1, s, Sort.by(Sort.Order.desc("createdAt"))));
        return ApiResponse.ok(new PageResp<>(result.getContent().stream().map(AuthService::toView).toList(),
                result.getTotalElements(), p, s));
    }

    @PutMapping("/users/{id}/status")
    public ApiResponse<UserView> updateUserStatus(@PathVariable Long id, @RequestBody StatusReq req) {
        if (id.equals(SecurityUtils.currentUserId())) {
            throw new BizException("不能禁用自己的账号");
        }
        User user = userRepository.findById(id).orElseThrow(() -> BizException.notFound("用户不存在"));
        user.setEnabled(req.enabled());
        userRepository.save(user);
        return ApiResponse.ok(AuthService.toView(user));
    }

    @PutMapping("/users/{id}/role")
    public ApiResponse<UserView> updateUserRole(@PathVariable Long id, @RequestBody RoleReq req) {
        String role = req.role() == null ? "" : req.role().toUpperCase();
        if (!"USER".equals(role) && !"ADMIN".equals(role)) {
            throw new BizException("角色只能是 USER 或 ADMIN");
        }
        User user = userRepository.findById(id).orElseThrow(() -> BizException.notFound("用户不存在"));
        user.setRole(role);
        userRepository.save(user);
        return ApiResponse.ok(AuthService.toView(user));
    }

    @GetMapping("/config")
    public ApiResponse<Map<String, String>> getConfig() {
        return ApiResponse.ok(siteConfigService.get());
    }

    @PutMapping("/config")
    public ApiResponse<Map<String, String>> updateConfig(@RequestBody Map<String, String> body) {
        return ApiResponse.ok(siteConfigService.update(body));
    }

    @PostMapping("/mail/test")
    public ApiResponse<Void> testMail(@RequestBody MailTestReq req) {
        if (!mailService.isEnabled()) {
            throw new BizException("SMTP 未启用：请设置环境变量 MAIL_ENABLED=true 并配置 spring.mail.*");
        }
        String to = req.to() == null ? "" : req.to().trim();
        if (to.isEmpty()) {
            throw new BizException("请输入收件邮箱");
        }
        try {
            mailService.sendTest(to);
        } catch (Exception e) {
            throw new BizException("发送失败：" + e.getMessage());
        }
        return ApiResponse.ok();
    }
}
