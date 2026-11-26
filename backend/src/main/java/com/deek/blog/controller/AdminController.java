package com.deek.blog.controller;

import com.deek.blog.common.ApiResponse;
import com.deek.blog.common.BizException;
import com.deek.blog.dto.AuthDtos.UserView;
import com.deek.blog.dto.CommentDtos.CommentView;
import com.deek.blog.dto.PostDtos.PageResp;
import com.deek.blog.dto.PostDtos.PostDetail;
import com.deek.blog.dto.PostDtos.PostReq;
import com.deek.blog.dto.PostDtos.PostView;
import com.deek.blog.dto.PostDtos.StatsResp;
import com.deek.blog.entity.Comment;
import com.deek.blog.entity.Post;
import com.deek.blog.entity.Tag;
import com.deek.blog.entity.User;
import com.deek.blog.repository.CommentRepository;
import com.deek.blog.repository.PostFavoriteRepository;
import com.deek.blog.repository.PostLikeRepository;
import com.deek.blog.repository.PostRepository;
import com.deek.blog.repository.StoredFileRepository;
import com.deek.blog.repository.TagRepository;
import com.deek.blog.repository.UserRepository;
import com.deek.blog.security.SecurityUtils;
import com.deek.blog.service.AiService;
import com.deek.blog.service.AuthService;
import com.deek.blog.service.CommentService;
import com.deek.blog.service.MailService;
import com.deek.blog.service.MailTemplateService;
import com.deek.blog.service.PostService;
import com.deek.blog.service.SiteConfigService;
import com.deek.blog.service.StorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    public record StatusReq(boolean enabled) {
    }

    public record RoleReq(String role) {
    }

    public record MuteReq(LocalDateTime mutedUntil, String reason) {
    }

    public record ReviewReq(String status, String note) {
    }

    public record CommentStatusReq(String status) {
    }

    public record CommentBulkReq(java.util.List<Long> ids, String status) {
    }

    public record MailTestReq(String to) {
    }

    public record AiPromptReq(String prompt) {
    }

    private final PostService postService;
    private final CommentService commentService;
    private final AuthService authService;
    private final SiteConfigService siteConfigService;
    private final MailService mailService;
    private final MailTemplateService mailTemplateService;
    private final AiService aiService;
    private final StorageService storageService;
    private final PostRepository postRepository;
    private final CommentRepository commentRepository;
    private final UserRepository userRepository;
    private final PostLikeRepository likeRepository;
    private final PostFavoriteRepository favoriteRepository;
    private final TagRepository tagRepository;
    private final StoredFileRepository storedFileRepository;
    private final ObjectMapper objectMapper;

    public AdminController(PostService postService, CommentService commentService, AuthService authService,
                           SiteConfigService siteConfigService, MailService mailService,
                           MailTemplateService mailTemplateService, AiService aiService,
                           StorageService storageService, PostRepository postRepository, CommentRepository commentRepository,
                           UserRepository userRepository, PostLikeRepository likeRepository,
                           PostFavoriteRepository favoriteRepository, TagRepository tagRepository,
                           StoredFileRepository storedFileRepository, ObjectMapper objectMapper) {
        this.postService = postService;
        this.commentService = commentService;
        this.authService = authService;
        this.siteConfigService = siteConfigService;
        this.mailService = mailService;
        this.mailTemplateService = mailTemplateService;
        this.aiService = aiService;
        this.storageService = storageService;
        this.postRepository = postRepository;
        this.commentRepository = commentRepository;
        this.userRepository = userRepository;
        this.likeRepository = likeRepository;
        this.favoriteRepository = favoriteRepository;
        this.tagRepository = tagRepository;
        this.storedFileRepository = storedFileRepository;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/stats")
    public ApiResponse<Map<String, Object>> stats() {
        Long userId = SecurityUtils.requireUserId();
        boolean fullPosts = SecurityUtils.hasPermission("system:posts");
        boolean myPosts = !fullPosts && SecurityUtils.hasPermission("system:my-posts");
        boolean fullComments = SecurityUtils.hasPermission("system:comments");
        boolean myComments = !fullComments && SecurityUtils.hasPermission("system:my-comments");
        boolean userAdmin = SecurityUtils.hasPermission("system:users");
        Map<String, Object> data = new LinkedHashMap<>();
        StatsResp base = postService.stats();
        if (fullPosts) {
            data.put("posts", base.posts());
            data.put("drafts", postRepository.countByStatus("DRAFT"));
            data.put("pendingPosts", postRepository.countByStatus("PENDING"));
            data.put("rejectedPosts", postRepository.countByStatus("REJECTED"));
            data.put("totalViews", base.totalViews());
            data.put("likes", likeRepository.count());
            data.put("favorites", favoriteRepository.count());
        } else if (myPosts) {
            Map<String, Long> mine = postService.statsForAuthor(userId);
            data.put("posts", mine.get("posts"));
            data.put("drafts", mine.get("drafts"));
            data.put("pendingPosts", mine.get("pendingPosts"));
            data.put("rejectedPosts", mine.get("rejectedPosts"));
            data.put("totalViews", mine.get("totalViews"));
            data.put("likes", mine.get("likes"));
            data.put("favorites", mine.get("favorites"));
        } else {
            data.put("posts", 0);
            data.put("drafts", 0);
            data.put("pendingPosts", 0);
            data.put("rejectedPosts", 0);
            data.put("totalViews", 0);
            data.put("likes", 0);
            data.put("favorites", 0);
        }
        if (fullComments) {
            data.put("comments", base.comments());
            data.put("pendingComments", commentRepository.countByStatus("PENDING"));
            data.put("hiddenComments", commentRepository.countByStatus("HIDDEN"));
        } else if (myComments) {
            data.put("comments", commentRepository.countByUserId(userId));
            data.put("pendingComments", commentRepository.countByUserIdAndStatus(userId, "PENDING"));
            data.put("hiddenComments", commentRepository.countByUserIdAndStatus(userId, "HIDDEN"));
        } else {
            data.put("comments", 0);
            data.put("pendingComments", 0);
            data.put("hiddenComments", 0);
        }
        data.put("users", userAdmin ? base.users() : 0);
        data.put("admins", userAdmin ? userRepository.countByRole("ADMIN") : 0);
        data.put("disabledUsers", userAdmin ? userRepository.countByEnabledFalse() : 0);
        data.put("mutedUsers", userAdmin ? userRepository.countMuted(LocalDateTime.now()) : 0);
        data.put("commentReview", siteConfigService.get().getOrDefault("commentReview", "false"));
        return ApiResponse.ok(data);
    }

    @GetMapping("/posts")
    public ApiResponse<PageResp<PostView>> posts(@RequestParam(defaultValue = "1") int page,
                                                 @RequestParam(defaultValue = "20") int size,
                                                 @RequestParam(required = false) String keyword,
                                                 @RequestParam(required = false) String status) {
        if (SecurityUtils.hasPermission("system:posts")) {
            return ApiResponse.ok(postService.adminList(page, size, keyword, status));
        }
        return ApiResponse.ok(postService.adminListByAuthor(SecurityUtils.requireUserId(), page, size, keyword, status));
    }

    @GetMapping("/posts/{id}")
    public ApiResponse<PostDetail> post(@PathVariable Long id) {
        PostDetail detail = postService.adminDetail(id);
        ensurePostAccess(detail.post());
        return ApiResponse.ok(detail);
    }

    @PostMapping("/posts")
    public ApiResponse<PostView> createPost(@RequestBody PostReq req) {
        User author = authService.requireUser(SecurityUtils.requireUserId());
        return ApiResponse.ok(postService.create(req, author, SecurityUtils.hasPermission("system:posts")));
    }

    @PutMapping("/posts/{id}")
    public ApiResponse<PostView> updatePost(@PathVariable Long id, @RequestBody PostReq req) {
        return ApiResponse.ok(postService.update(id, req, SecurityUtils.requireUserId(),
                SecurityUtils.hasPermission("system:posts")));
    }

    @PutMapping("/posts/{id}/review")
    public ApiResponse<PostView> reviewPost(@PathVariable Long id, @RequestBody ReviewReq req) {
        User reviewer = authService.requireUser(SecurityUtils.requireUserId());
        return ApiResponse.ok(postService.review(id, req.status(), req.note(), reviewer));
    }

    @DeleteMapping("/posts/{id}")
    public ApiResponse<Void> deletePost(@PathVariable Long id) {
        postService.delete(id, SecurityUtils.requireUserId(), SecurityUtils.hasPermission("system:posts"));
        return ApiResponse.ok();
    }

    @PostMapping("/posts/{id}/ai-comment-summary")
    public ApiResponse<PostView> summarizePostComments(@PathVariable Long id,
                                                        @RequestBody(required = false) AiPromptReq req) {
        PostDetail detail = postService.adminDetail(id);
        List<String> comments = commentRepository.findByPostIdAndStatusOrderByCreatedAtAsc(id, "VISIBLE")
                .stream().map(com.deek.blog.entity.Comment::getContent).limit(80).toList();
        String summary = aiService.summarizeComments(detail.post().title(), comments,
                req == null ? null : req.prompt());
        return ApiResponse.ok(postService.saveCommentSummary(id, summary));
    }

    @GetMapping("/comments")
    public ApiResponse<PageResp<CommentView>> comments(@RequestParam(defaultValue = "1") int page,
                                                       @RequestParam(defaultValue = "20") int size,
                                                       @RequestParam(required = false) String keyword,
                                                       @RequestParam(required = false) String status) {
        if (SecurityUtils.hasPermission("system:comments")) {
            return ApiResponse.ok(commentService.adminList(page, size, status, keyword));
        }
        return ApiResponse.ok(commentService.adminListByUser(SecurityUtils.requireUserId(),
                page, size, status, keyword));
    }

    @PutMapping("/comments/{id}/status")
    public ApiResponse<CommentView> moderateComment(@PathVariable Long id,
                                                     @RequestBody CommentStatusReq req) {
        return ApiResponse.ok(commentService.moderate(id, req.status()));
    }

    @PutMapping("/comments/bulk-status")
    public ApiResponse<Map<String, Object>> bulkCommentStatus(@RequestBody CommentBulkReq req) {
        int updated = commentService.moderateBulk(req.ids(), req.status());
        return ApiResponse.ok(Map.of("updated", updated));
    }

    @GetMapping("/users")
    public ApiResponse<PageResp<UserView>> users(@RequestParam(defaultValue = "1") int page,
                                                 @RequestParam(defaultValue = "20") int size,
                                                 @RequestParam(required = false) String keyword,
                                                 @RequestParam(required = false) String role,
                                                 @RequestParam(required = false) String status,
                                                 @RequestParam(required = false) Boolean muted) {
        int p = Math.max(1, page);
        int s = Math.min(100, Math.max(1, size));
        String normalizedRole = role == null || role.isBlank() ? null : role.trim().toUpperCase();
        if (normalizedRole != null && !"USER".equals(normalizedRole) && !"ADMIN".equals(normalizedRole)) {
            throw new BizException("角色筛选不合法");
        }
        Boolean enabled = null;
        if (status != null && !status.isBlank()) {
            if ("active".equalsIgnoreCase(status) || "enabled".equalsIgnoreCase(status)) {
                enabled = true;
            } else if ("disabled".equalsIgnoreCase(status) || "banned".equalsIgnoreCase(status)) {
                enabled = false;
            } else {
                throw new BizException("用户状态筛选不合法");
            }
        }
        Page<User> result = userRepository.adminSearch(
                keyword == null || keyword.isBlank() ? null : keyword.trim(),
                normalizedRole, enabled, muted, LocalDateTime.now(),
                PageRequest.of(p - 1, s, Sort.by(Sort.Order.desc("createdAt"))));
        return ApiResponse.ok(new PageResp<>(result.getContent().stream().map(authService::toView).toList(),
                result.getTotalElements(), p, s));
    }

    @PutMapping("/users/{id}/status")
    public ApiResponse<UserView> updateUserStatus(@PathVariable Long id, @RequestBody StatusReq req) {
        if (id.equals(SecurityUtils.currentUserId())) {
            throw new BizException("不能禁用自己的账号");
        }
        User user = userRepository.findById(id).orElseThrow(() -> BizException.notFound("用户不存在"));
        if (!req.enabled() && "ADMIN".equals(user.getRole()) && userRepository.countByRole("ADMIN") <= 1) {
            throw new BizException("至少保留一名可用管理员，不能封禁最后一名管理员");
        }
        user.setEnabled(req.enabled());
        userRepository.save(user);
        return ApiResponse.ok(authService.toView(user));
    }

    @PutMapping("/users/{id}/role")
    public ApiResponse<UserView> updateUserRole(@PathVariable Long id, @RequestBody RoleReq req) {
        if (id.equals(SecurityUtils.currentUserId())) {
            throw new BizException("不能修改自己的管理员角色");
        }
        String role = req.role() == null ? "" : req.role().toUpperCase();
        if (!"USER".equals(role) && !"ADMIN".equals(role)) {
            throw new BizException("角色只能是 USER 或 ADMIN");
        }
        User user = userRepository.findById(id).orElseThrow(() -> BizException.notFound("用户不存在"));
        if ("ADMIN".equals(user.getRole()) && "USER".equals(role)
                && userRepository.countByRole("ADMIN") <= 1) {
            throw new BizException("至少保留一名管理员，不能降级最后一名管理员");
        }
        user.setRole(role);
        userRepository.save(user);
        return ApiResponse.ok(authService.toView(user));
    }

    @PutMapping("/users/{id}/mute")
    public ApiResponse<UserView> updateUserMute(@PathVariable Long id, @RequestBody MuteReq req) {
        if (id.equals(SecurityUtils.currentUserId())) {
            throw new BizException("不能禁言自己的账号");
        }
        User user = userRepository.findById(id).orElseThrow(() -> BizException.notFound("用户不存在"));
        LocalDateTime until = req.mutedUntil();
        if (until != null && until.isBefore(LocalDateTime.now())) {
            until = null;
        }
        user.setMutedUntil(until);
        String reason = req.reason() == null ? "" : req.reason().trim();
        if (reason.length() > 512) {
            reason = reason.substring(0, 512);
        }
        user.setMuteReason(until == null ? null : reason);
        userRepository.save(user);
        return ApiResponse.ok(authService.toView(user));
    }

    @GetMapping("/config")
    public ApiResponse<Map<String, String>> getConfig() {
        return ApiResponse.ok(maskedConfig(siteConfigService.get()));
    }

    @PutMapping("/config")
    public ApiResponse<Map<String, String>> updateConfig(@RequestBody Map<String, String> body) {
        Map<String, String> values = new LinkedHashMap<>(body == null ? Map.of() : body);
        // 密钥留空表示沿用旧值，避免后台表单无意中把密码清空。
        if (values.getOrDefault("smtpPassword", "").isBlank()) {
            values.remove("smtpPassword");
        }
        if (values.getOrDefault("aiApiKey", "").isBlank()) {
            values.remove("aiApiKey");
        }
        if (values.getOrDefault("storageSecretKey", "").isBlank()) {
            values.remove("storageSecretKey");
        }
        if (values.getOrDefault("storagePassword", "").isBlank()) {
            values.remove("storagePassword");
        }
        return ApiResponse.ok(maskedConfig(siteConfigService.update(values)));
    }

    @GetMapping("/storage")
    public ApiResponse<Map<String, Object>> storageOverview() {
        return ApiResponse.ok(storageService.overview());
    }

    @GetMapping("/storage/files")
    public ApiResponse<Map<String, Object>> storageFiles(@RequestParam(defaultValue = "0") int page,
                                                         @RequestParam(defaultValue = "20") int size,
                                                         @RequestParam(required = false) String keyword,
                                                         @RequestParam(required = false) String category,
                                                         @RequestParam(required = false) String uploader) {
        return ApiResponse.ok(storageService.listLocal(page, size, keyword, category, uploader));
    }

    @DeleteMapping("/storage/files")
    public ApiResponse<Void> deleteStorageFile(@RequestParam(required = false) String path,
                                               @RequestParam(required = false) String name) {
        storageService.deleteLocal(path != null && !path.isBlank() ? path : name);
        return ApiResponse.ok();
    }

    @PostMapping("/storage/test")
    public ApiResponse<Map<String, Object>> testStorage() {
        return ApiResponse.ok(storageService.testOnline());
    }

    @GetMapping("/mail/templates")
    public ApiResponse<Map<String, Object>> mailTemplates() {
        return ApiResponse.ok(Map.of("defaults", mailTemplateService.defaults()));
    }

    @PostMapping("/mail/test")
    public ApiResponse<Void> testMail(@RequestBody MailTestReq req) {
        if (!mailService.isEnabled()) {
            throw new BizException("SMTP 未启用：请先在站点设置里启用并填写 SMTP 参数");
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

    @GetMapping("/maintenance/overview")
    public ApiResponse<Map<String, Object>> maintenanceOverview() {
        Map<String, String> cfg = siteConfigService.get();
        Runtime runtime = Runtime.getRuntime();
        long heapUsed = runtime.totalMemory() - runtime.freeMemory();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("maintenanceEnabled", cfg.getOrDefault("maintenanceEnabled", "false"));
        data.put("readOnlyMode", cfg.getOrDefault("readOnlyMode", "false"));
        data.put("posts", postRepository.count());
        data.put("publishedPosts", postRepository.countByStatus("PUBLISHED"));
        data.put("draftPosts", postRepository.countByStatus("DRAFT"));
        data.put("pendingPosts", postRepository.countByStatus("PENDING"));
        data.put("comments", commentRepository.count());
        data.put("pendingComments", commentRepository.countByStatus("PENDING"));
        data.put("users", userRepository.count());
        data.put("disabledUsers", userRepository.countByEnabledFalse());
        data.put("tags", tagRepository.count());
        data.put("files", storedFileRepository.count());
        data.put("heapUsed", heapUsed);
        data.put("heapMax", runtime.maxMemory());
        data.put("processors", runtime.availableProcessors());
        data.put("uptimeSeconds", java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime() / 1000);
        data.put("javaVersion", System.getProperty("java.version", ""));
        data.put("os", System.getProperty("os.name", "") + " " + System.getProperty("os.arch", ""));
        data.put("workDir", System.getProperty("user.dir", ""));
        try {
            Path root = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath();
            var store = Files.getFileStore(root);
            data.put("diskTotal", store.getTotalSpace());
            data.put("diskUsable", store.getUsableSpace());
        } catch (Exception e) {
            data.put("diskTotal", -1L);
            data.put("diskUsable", -1L);
        }
        return ApiResponse.ok(data);
    }

    @GetMapping("/maintenance/export")
    public ResponseEntity<byte[]> exportData() throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", 1);
        root.put("exportedAt", LocalDateTime.now().toString());
        root.put("site", siteConfigService.publicConfig());
        root.put("posts", postRepository.findAll(Sort.by(Sort.Direction.ASC, "id")).stream()
                .map(this::safePost).toList());
        root.put("comments", commentRepository.findAll(Sort.by(Sort.Direction.ASC, "id")).stream()
                .map(this::safeComment).toList());
        root.put("tags", tagRepository.findAll(Sort.by(Sort.Direction.ASC, "id")).stream()
                .map(this::safeTag).toList());
        List<Map<String, Object>> users = userRepository.findAll(Sort.by(Sort.Direction.ASC, "id")).stream()
                .map(this::safeUser).toList();
        root.put("users", users);
        byte[] bytes = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(root);
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"blog-backup-" + stamp + ".json\"")
                .contentType(MediaType.APPLICATION_JSON)
                .body(bytes);
    }

    private Map<String, Object> safeUser(User user) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", user.getId());
        map.put("username", user.getUsername());
        map.put("email", user.getEmail());
        map.put("nickname", user.getNickname());
        map.put("avatar", user.getAvatar());
        map.put("bio", user.getBio());
        map.put("role", user.getRole());
        map.put("enabled", user.isEnabled());
        map.put("createdAt", String.valueOf(user.getCreatedAt()));
        return map;
    }

    private Map<String, Object> safePost(Post post) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", post.getId());
        map.put("title", post.getTitle());
        map.put("summary", post.getSummary());
        map.put("content", post.getContent());
        map.put("cover", post.getCover());
        map.put("tags", post.getTags());
        map.put("status", post.getStatus());
        map.put("reviewNote", post.getReviewNote());
        map.put("reviewedAt", String.valueOf(post.getReviewedAt()));
        map.put("reviewedBy", post.getReviewedBy());
        map.put("commentSummary", post.getCommentSummary());
        map.put("commentSummaryAt", String.valueOf(post.getCommentSummaryAt()));
        map.put("top", post.isTop());
        map.put("views", post.getViews());
        map.put("likeCount", post.getLikeCount());
        map.put("favoriteCount", post.getFavoriteCount());
        map.put("commentCount", post.getCommentCount());
        map.put("authorId", post.getAuthorId());
        map.put("authorName", post.getAuthorName());
        map.put("authorAvatar", post.getAuthorAvatar());
        map.put("createdAt", String.valueOf(post.getCreatedAt()));
        map.put("updatedAt", String.valueOf(post.getUpdatedAt()));
        return map;
    }

    private Map<String, Object> safeComment(Comment comment) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", comment.getId());
        map.put("postId", comment.getPostId());
        map.put("userId", comment.getUserId());
        map.put("parentId", comment.getParentId());
        map.put("content", comment.getContent());
        map.put("nickname", comment.getNickname());
        map.put("avatar", comment.getAvatar());
        map.put("status", comment.getStatus());
        map.put("createdAt", String.valueOf(comment.getCreatedAt()));
        return map;
    }

    private Map<String, Object> safeTag(Tag tag) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", tag.getId());
        map.put("name", tag.getName());
        map.put("slug", tag.getSlug());
        map.put("description", tag.getDescription());
        map.put("color", tag.getColor());
        map.put("sortOrder", tag.getSortOrder());
        map.put("visible", tag.isVisible());
        map.put("createdAt", String.valueOf(tag.getCreatedAt()));
        map.put("updatedAt", String.valueOf(tag.getUpdatedAt()));
        return map;
    }

    private void ensurePostAccess(PostView post) {
        if (SecurityUtils.hasPermission("system:posts")) return;
        if (post == null || !Objects.equals(post.authorId(), SecurityUtils.requireUserId())) {
            throw BizException.forbidden("只能查看自己的文章");
        }
    }

    private Map<String, String> maskedConfig(Map<String, String> raw) {
        Map<String, String> map = new LinkedHashMap<>(raw);
        map.put("smtpPasswordSet", raw.getOrDefault("smtpPassword", "").isBlank() ? "false" : "true");
        map.put("aiApiKeySet", raw.getOrDefault("aiApiKey", "").isBlank() ? "false" : "true");
        map.put("storageSecretKeySet", raw.getOrDefault("storageSecretKey", "").isBlank() ? "false" : "true");
        map.put("storagePasswordSet", raw.getOrDefault("storagePassword", "").isBlank() ? "false" : "true");
        map.put("smtpPassword", "");
        map.put("aiApiKey", "");
        map.put("storageSecretKey", "");
        map.put("storagePassword", "");
        return map;
    }
}
