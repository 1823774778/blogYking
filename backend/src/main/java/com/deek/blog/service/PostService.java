package com.deek.blog.service;

import com.deek.blog.common.BizException;
import com.deek.blog.dto.PostDtos.PageResp;
import com.deek.blog.dto.PostDtos.PostDetail;
import com.deek.blog.dto.PostDtos.PostReq;
import com.deek.blog.dto.PostDtos.PostView;
import com.deek.blog.dto.PostDtos.StatsResp;
import com.deek.blog.dto.PostDtos.TagView;
import com.deek.blog.dto.AuthDtos.ToggleResp;
import com.deek.blog.entity.Post;
import com.deek.blog.entity.PostFavorite;
import com.deek.blog.entity.PostLike;
import com.deek.blog.entity.User;
import com.deek.blog.repository.CommentRepository;
import com.deek.blog.repository.PostFavoriteRepository;
import com.deek.blog.repository.PostLikeRepository;
import com.deek.blog.repository.PostRepository;
import com.deek.blog.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Sort.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class PostService {

    private static final Logger log = LoggerFactory.getLogger(PostService.class);
    private static final long VIEW_DEDUPE_MILLIS = 30 * 60 * 1000L;

    private final PostRepository postRepository;
    private final PostLikeRepository likeRepository;
    private final PostFavoriteRepository favoriteRepository;
    private final CommentRepository commentRepository;
    private final UserRepository userRepository;
    private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;

    @Value("${app.redis.enabled:false}")
    private boolean redisEnabled;

    /** Redis 不可用时的本地兜底：同一 IP 对同一篇文章 30 分钟内只计一次浏览量 */
    private final Map<String, Long> recentViews = new ConcurrentHashMap<>();

    public PostService(PostRepository postRepository, PostLikeRepository likeRepository,
                       PostFavoriteRepository favoriteRepository, CommentRepository commentRepository,
                       UserRepository userRepository, ObjectProvider<StringRedisTemplate> redisTemplateProvider) {
        this.postRepository = postRepository;
        this.likeRepository = likeRepository;
        this.favoriteRepository = favoriteRepository;
        this.commentRepository = commentRepository;
        this.userRepository = userRepository;
        this.redisTemplateProvider = redisTemplateProvider;
    }

    public PageResp<PostView> list(String keyword, String tag, String sort, int page, int size) {
        int p = Math.max(1, page);
        int s = Math.min(50, Math.max(1, size));
        Sort order = "hot".equals(sort)
                ? Sort.by(Order.desc("views"), Order.desc("createdAt"))
                : Sort.by(Order.desc("top"), Order.desc("createdAt"));
        Pageable pageable = PageRequest.of(p - 1, s, order);
        Page<Post> result = postRepository.search(blankToNull(keyword), blankToNull(tag), pageable);
        return new PageResp<>(result.getContent().stream().map(this::toView).toList(),
                result.getTotalElements(), p, s);
    }

    public List<PostView> hot(int limit) {
        return postRepository.findHot(PageRequest.of(0, Math.min(10, Math.max(1, limit))))
                .stream().map(this::toView).toList();
    }

    public List<TagView> tags() {
        Map<String, Long> counts = new HashMap<>();
        for (String row : postRepository.allTags()) {
            for (String tag : row.split(",")) {
                String t = tag.trim();
                if (!t.isEmpty()) {
                    counts.merge(t, 1L, Long::sum);
                }
            }
        }
        return counts.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(24)
                .map(e -> new TagView(e.getKey(), e.getValue()))
                .toList();
    }

    public StatsResp stats() {
        return new StatsResp(postRepository.countByStatus("PUBLISHED"), userRepository.count(),
                commentRepository.count(), postRepository.totalViews() == null ? 0 : postRepository.totalViews());
    }

    @Transactional
    public PostDetail detail(Long id, Long currentUserId, String ip) {
        Post post = postRepository.findById(id)
                .orElseThrow(() -> BizException.notFound("文章不存在或已删除"));
        if (!"PUBLISHED".equals(post.getStatus())) {
            boolean allowed = currentUserId != null
                    && (Objects.equals(post.getAuthorId(), currentUserId)
                    || userRepository.findById(currentUserId).map(u -> "ADMIN".equals(u.getRole())).orElse(false));
            if (!allowed) {
                throw BizException.notFound("文章不存在或已删除");
            }
        }
        registerView(post, ip);
        boolean liked = currentUserId != null && likeRepository.existsByPostIdAndUserId(id, currentUserId);
        boolean favorited = currentUserId != null && favoriteRepository.existsByPostIdAndUserId(id, currentUserId);
        return new PostDetail(toView(post), post.getContent(), liked, favorited);
    }

    @Transactional
    public PostView create(PostReq req, User author) {
        Post post = new Post();
        apply(post, req);
        post.setAuthorId(author.getId());
        post.setAuthorName(displayName(author));
        post.setAuthorAvatar(author.getAvatar());
        post.setCreatedAt(LocalDateTime.now());
        post.setUpdatedAt(LocalDateTime.now());
        postRepository.save(post);
        return toView(post);
    }

    @Transactional
    public PostView update(Long id, PostReq req) {
        Post post = postRepository.findById(id)
                .orElseThrow(() -> BizException.notFound("文章不存在"));
        apply(post, req);
        post.setUpdatedAt(LocalDateTime.now());
        postRepository.save(post);
        return toView(post);
    }

    @Transactional
    public void delete(Long id) {
        Post post = postRepository.findById(id)
                .orElseThrow(() -> BizException.notFound("文章不存在"));
        commentRepository.deleteByPostId(id);
        likeRepository.deleteByPostId(id);
        favoriteRepository.deleteByPostId(id);
        postRepository.delete(post);
    }

    public PageResp<PostView> adminList(int page, int size, String keyword, String status) {
        int p = Math.max(1, page);
        int s = Math.min(100, Math.max(1, size));
        String normalizedStatus = blankToNull(status);
        if (normalizedStatus != null) {
            normalizedStatus = normalizedStatus.toUpperCase();
            if (!List.of("DRAFT", "PENDING", "PUBLISHED", "REJECTED").contains(normalizedStatus)) {
                throw new BizException("文章状态筛选不合法");
            }
        }
        Page<Post> result = postRepository.adminSearch(normalizedStatus, blankToNull(keyword),
                PageRequest.of(p - 1, s, Sort.by(Order.desc("createdAt"))));
        return new PageResp<>(result.getContent().stream().map(this::toView).toList(),
                result.getTotalElements(), p, s);
    }

    public PostDetail adminDetail(Long id) {
        Post post = postRepository.findById(id)
                .orElseThrow(() -> BizException.notFound("文章不存在"));
        return new PostDetail(toView(post), post.getContent(), false, false);
    }

    @Transactional
    public PostView review(Long id, String status, String note, User reviewer) {
        Post post = postRepository.findById(id)
                .orElseThrow(() -> BizException.notFound("文章不存在"));
        String normalized = status == null ? "" : status.trim().toUpperCase();
        if (!List.of("PUBLISHED", "REJECTED", "DRAFT").contains(normalized)) {
            throw new BizException("审核结果只能是 PUBLISHED、REJECTED 或 DRAFT");
        }
        String reviewNote = note == null ? "" : note.trim();
        if (reviewNote.length() > 512) {
            reviewNote = reviewNote.substring(0, 512);
        }
        if ("REJECTED".equals(normalized) && reviewNote.isEmpty()) {
            throw new BizException("退回文章时请填写修改意见");
        }
        post.setStatus(normalized);
        post.setReviewNote(reviewNote.isEmpty() ? null : reviewNote);
        post.setReviewedAt(LocalDateTime.now());
        post.setReviewedBy(reviewer == null ? null : displayName(reviewer));
        post.setUpdatedAt(LocalDateTime.now());
        postRepository.save(post);
        return toView(post);
    }

    @Transactional
    public ToggleResp toggleLike(Long postId, Long userId) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> BizException.notFound("文章不存在"));
        var existing = likeRepository.findByPostIdAndUserId(postId, userId);
        if (existing.isPresent()) {
            likeRepository.delete(existing.get());
            post.setLikeCount(Math.max(0, post.getLikeCount() - 1));
            postRepository.save(post);
            return new ToggleResp(false, post.getLikeCount());
        }
        PostLike like = new PostLike();
        like.setPostId(postId);
        like.setUserId(userId);
        likeRepository.save(like);
        post.setLikeCount(post.getLikeCount() + 1);
        postRepository.save(post);
        return new ToggleResp(true, post.getLikeCount());
    }

    @Transactional
    public ToggleResp toggleFavorite(Long postId, Long userId) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> BizException.notFound("文章不存在"));
        var existing = favoriteRepository.findByPostIdAndUserId(postId, userId);
        if (existing.isPresent()) {
            favoriteRepository.delete(existing.get());
            post.setFavoriteCount(Math.max(0, post.getFavoriteCount() - 1));
            postRepository.save(post);
            return new ToggleResp(false, post.getFavoriteCount());
        }
        PostFavorite favorite = new PostFavorite();
        favorite.setPostId(postId);
        favorite.setUserId(userId);
        favoriteRepository.save(favorite);
        post.setFavoriteCount(post.getFavoriteCount() + 1);
        postRepository.save(post);
        return new ToggleResp(true, post.getFavoriteCount());
    }

    public List<PostView> myFavorites(Long userId) {
        List<Long> ids = favoriteRepository.findByUserIdOrderByCreatedAtDesc(userId)
                .stream().map(PostFavorite::getPostId).toList();
        return resolvePosts(ids);
    }

    public List<PostView> myLikes(Long userId) {
        List<Long> ids = likeRepository.findByUserIdOrderByCreatedAtDesc(userId)
                .stream().map(PostLike::getPostId).toList();
        return resolvePosts(ids);
    }

    public PostView toView(Post post) {
        return new PostView(post.getId(), post.getTitle(), post.getSummary(), post.getCover(), post.getTags(),
                post.getStatus(), post.getViews(), post.getLikeCount(), post.getFavoriteCount(),
                post.getCommentCount(), post.isTop(), post.getCreatedAt(), post.getUpdatedAt(),
                post.getAuthorId(), post.getAuthorName(), post.getAuthorAvatar(),
                post.getReviewNote(), post.getReviewedAt(), post.getReviewedBy(),
                post.getCommentSummary(), post.getCommentSummaryAt());
    }

    @Transactional
    public PostView saveCommentSummary(Long id, String summary) {
        Post post = postRepository.findById(id)
                .orElseThrow(() -> BizException.notFound("文章不存在"));
        String text = summary == null ? "" : summary.trim();
        if (text.length() > 2000) text = text.substring(0, 2000);
        post.setCommentSummary(text);
        post.setCommentSummaryAt(LocalDateTime.now());
        post.setUpdatedAt(LocalDateTime.now());
        postRepository.save(post);
        return toView(post);
    }

    private List<PostView> resolvePosts(List<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, Post> map = postRepository.findByIdInAndStatus(ids, "PUBLISHED").stream()
                .collect(Collectors.toMap(Post::getId, p -> p));
        List<PostView> result = new ArrayList<>();
        for (Long id : ids) {
            Post post = map.get(id);
            if (post != null) {
                result.add(toView(post));
            }
        }
        return result;
    }

    private void registerView(Post post, String ip) {
        String key = (ip == null ? "unknown" : ip) + ":" + post.getId();
        boolean shouldCount;
        if (redisEnabled) {
            Boolean redisResult = markInRedis(key);
            // Redis 正常时以它为唯一判断；异常返回 null 时回退本地内存
            shouldCount = redisResult != null ? redisResult : markLocally(key);
        } else {
            shouldCount = markLocally(key);
        }
        if (shouldCount) {
            post.setViews(post.getViews() + 1);
            postRepository.save(post);
        }
    }

    /**
     * @return true 首次访问、false 30 分钟内重复、null Redis 不可用
     */
    private Boolean markInRedis(String key) {
        try {
            StringRedisTemplate template = redisTemplateProvider.getIfAvailable();
            if (template == null) {
                return null;
            }
            Boolean first = template.opsForValue()
                    .setIfAbsent("blog:view:" + key, "1", Duration.ofMinutes(30));
            return Boolean.TRUE.equals(first);
        } catch (Exception e) {
            log.debug("Redis 浏览量去重不可用，回退本地内存：{}", e.getMessage());
            return null;
        }
    }

    private boolean markLocally(String key) {
        long now = System.currentTimeMillis();
        Long last = recentViews.get(key);
        if (last != null && now - last <= VIEW_DEDUPE_MILLIS) {
            return false;
        }
        if (recentViews.size() > 20000) {
            recentViews.clear();
        }
        recentViews.put(key, now);
        return true;
    }

    private void apply(Post post, PostReq req) {
        String title = req.title() == null ? "" : req.title().trim();
        String content = req.content() == null ? "" : req.content();
        if (title.isEmpty() || title.length() > 160) {
            throw new BizException("标题不能为空且最多 160 个字符");
        }
        if (content.trim().isEmpty()) {
            throw new BizException("正文不能为空");
        }
        post.setTitle(title);
        post.setContent(content);
        String summary = req.summary() == null ? "" : req.summary().trim();
        if (summary.length() > 200) {
            summary = summary.substring(0, 200);
        }
        post.setSummary(summary.isEmpty() ? autoSummary(content) : summary);
        post.setCover(req.cover() == null ? null : req.cover().trim());
        post.setTags(normalizeTags(req.tags()));
        String status = req.status() == null ? "PUBLISHED" : req.status().toUpperCase();
        if (!List.of("PUBLISHED", "DRAFT", "PENDING", "REJECTED").contains(status)) {
            throw new BizException("文章状态只能是 DRAFT、PENDING、PUBLISHED 或 REJECTED");
        }
        post.setStatus(status);
        post.setTop(Boolean.TRUE.equals(req.top()));
    }

    private String normalizeTags(String tags) {
        if (tags == null || tags.isBlank()) {
            return null;
        }
        return Arrays.stream(tags.split(","))
                .map(String::trim)
                .filter(t -> !t.isEmpty())
                .distinct()
                .limit(8)
                .collect(Collectors.joining(","));
    }

    private String autoSummary(String content) {
        String plain = content
                .replaceAll("```[\\s\\S]*?```", " ")
                .replaceAll("!\\[[^]]*]\\([^)]*\\)", " ")
                .replaceAll("\\[([^]]*)]\\([^)]*\\)", "$1")
                .replaceAll("[#>*_`~-]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return plain.length() > 100 ? plain.substring(0, 100) + "…" : plain;
    }

    public static String displayName(User user) {
        return user.getNickname() == null || user.getNickname().isBlank() ? user.getUsername() : user.getNickname();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
