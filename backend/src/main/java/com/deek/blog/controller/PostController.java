package com.deek.blog.controller;

import com.deek.blog.common.ApiResponse;
import com.deek.blog.dto.AuthDtos.ToggleResp;
import com.deek.blog.dto.PostDtos.PageResp;
import com.deek.blog.dto.PostDtos.PostDetail;
import com.deek.blog.dto.PostDtos.PostView;
import com.deek.blog.dto.PostDtos.StatsResp;
import com.deek.blog.dto.PostDtos.TagView;
import com.deek.blog.entity.Post;
import com.deek.blog.repository.PostRepository;
import com.deek.blog.security.SecurityUtils;
import com.deek.blog.service.AiService;
import com.deek.blog.service.PostService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/posts")
public class PostController {

    private final PostService postService;
    private final PostRepository postRepository;
    private final AiService aiService;
    private final Map<String, Long> overviewCooldown = new ConcurrentHashMap<>();

    public PostController(PostService postService, PostRepository postRepository, AiService aiService) {
        this.postService = postService;
        this.postRepository = postRepository;
        this.aiService = aiService;
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

    @GetMapping("/slug/{slug}")
    public ApiResponse<PostDetail> detailBySlug(@PathVariable String slug, HttpServletRequest request) {
        Long userId = SecurityUtils.currentUserId();
        String ip = clientIp(request);
        return ApiResponse.ok(postService.detailBySlug(slug, userId, ip));
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

    /** 公开文章总览：服务端读取已发布正文，避免前端传入任意长文本。 */
    @PostMapping("/{id}/ai-overview")
    public ApiResponse<Map<String, String>> aiOverview(@PathVariable Long id, HttpServletRequest request) {
        Post post = postRepository.findById(id)
                .filter(p -> "PUBLISHED".equals(p.getStatus()))
                .orElseThrow(() -> com.deek.blog.common.BizException.notFound("文章不存在或已删除"));
        String key = clientIp(request) + ":" + id;
        long now = System.currentTimeMillis();
        Long last = overviewCooldown.get(key);
        if (last != null && now - last < 20000) {
            throw new com.deek.blog.common.BizException("AI 总览请求过于频繁，请稍后再试");
        }
        overviewCooldown.put(key, now);
        if (overviewCooldown.size() > 2000) {
            overviewCooldown.entrySet().removeIf(e -> now - e.getValue() > 120000);
        }
        String prompt = "你是中文博客编辑。请为当前文章生成阅读总览：先用一句话概括主旨，"
                + "再用 3-5 个要点列出核心内容，最后说明适合谁阅读。只依据标题与正文，不要编造，语言简洁。";
        String overview = aiService.summarize(post.getTitle(), post.getContent(), prompt);
        return ApiResponse.ok(Map.of("overview", overview));
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
