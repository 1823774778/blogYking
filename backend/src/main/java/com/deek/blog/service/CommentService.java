package com.deek.blog.service;

import com.deek.blog.common.BizException;
import com.deek.blog.dto.CommentDtos.CommentReq;
import com.deek.blog.dto.CommentDtos.CommentView;
import com.deek.blog.dto.PostDtos.PageResp;
import com.deek.blog.entity.Comment;
import com.deek.blog.entity.Post;
import com.deek.blog.entity.User;
import com.deek.blog.repository.CommentRepository;
import com.deek.blog.repository.PostRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class CommentService {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final CommentRepository commentRepository;
    private final PostRepository postRepository;
    private final SiteConfigService siteConfigService;

    public CommentService(CommentRepository commentRepository, PostRepository postRepository,
                          SiteConfigService siteConfigService) {
        this.commentRepository = commentRepository;
        this.postRepository = postRepository;
        this.siteConfigService = siteConfigService;
    }

    public List<CommentView> list(Long postId, Long currentUserId) {
        String postTitle = postRepository.findById(postId).map(Post::getTitle).orElse(null);
        return commentRepository.findByPostIdAndStatusOrderByCreatedAtAsc(postId, "VISIBLE").stream()
                .map(c -> toView(c, currentUserId, postTitle))
                .toList();
    }

    @Transactional
    public CommentView add(Long postId, User user, CommentReq req) {
        if (user.isMuted()) {
            throw BizException.forbidden("你已被禁言至 "
                    + user.getMutedUntil().format(DATE_TIME) + "，暂时不能发表评论");
        }
        String content = req.content() == null ? "" : req.content().trim();
        if (content.isEmpty() || content.length() > 1000) {
            throw new BizException("评论内容需为 1-1000 个字符");
        }
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> BizException.notFound("文章不存在"));
        if (!"PUBLISHED".equals(post.getStatus())) {
            throw new BizException("文章未发布，无法评论");
        }
        Long parentId = req.parentId();
        if (parentId != null) {
            Comment parent = commentRepository.findById(parentId)
                    .orElseThrow(() -> BizException.notFound("要回复的评论不存在"));
            if (!parent.getPostId().equals(postId)) {
                throw new BizException("评论与文章不匹配");
            }
            // 只支持一级回复，回复的回复挂到顶层
            parentId = parent.getParentId() == null ? parent.getId() : parent.getParentId();
        }
        boolean reviewRequired = "true".equalsIgnoreCase(
                siteConfigService.get().getOrDefault("commentReview", "false"));
        Comment comment = new Comment();
        comment.setPostId(postId);
        comment.setUserId(user.getId());
        comment.setParentId(parentId);
        comment.setContent(content);
        comment.setNickname(PostService.displayName(user));
        comment.setAvatar(user.getAvatar());
        comment.setStatus(reviewRequired ? "PENDING" : "VISIBLE");
        commentRepository.save(comment);

        if (!reviewRequired) {
            post.setCommentCount(post.getCommentCount() + 1);
            postRepository.save(post);
        }
        return toView(comment, user.getId(), post.getTitle());
    }

    @Transactional
    public void delete(Long commentId, Long currentUserId, boolean admin) {
        Comment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> BizException.notFound("评论不存在"));
        if (!admin && !comment.getUserId().equals(currentUserId)) {
            throw BizException.forbidden("只能删除自己的评论");
        }
        List<Comment> children = commentRepository.findByParentIdOrderByCreatedAtAsc(commentId);
        long removedVisible = (isVisible(comment) ? 1 : 0)
                + children.stream().filter(this::isVisible).count();
        commentRepository.deleteAll(children);
        commentRepository.delete(comment);

        postRepository.findById(comment.getPostId()).ifPresent(post -> {
            post.setCommentCount(Math.max(0, post.getCommentCount() - removedVisible));
            postRepository.save(post);
        });
    }

    @Transactional
    public CommentView moderate(Long commentId, String status) {
        String normalized = status == null ? "" : status.trim().toUpperCase();
        if (!List.of("VISIBLE", "PENDING", "HIDDEN").contains(normalized)) {
            throw new BizException("评论状态只能是 VISIBLE、PENDING 或 HIDDEN");
        }
        Comment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> BizException.notFound("评论不存在"));
        boolean wasVisible = isVisible(comment);
        boolean willVisible = "VISIBLE".equals(normalized);
        comment.setStatus(normalized);
        commentRepository.save(comment);

        if (wasVisible != willVisible) {
            postRepository.findById(comment.getPostId()).ifPresent(post -> {
                post.setCommentCount(Math.max(0, post.getCommentCount() + (willVisible ? 1 : -1)));
                postRepository.save(post);
            });
        }
        String title = postRepository.findById(comment.getPostId()).map(Post::getTitle).orElse(null);
        return toView(comment, null, title);
    }

    public PageResp<CommentView> adminList(int page, int size, String status, String keyword) {
        int p = Math.max(1, page);
        int s = Math.min(100, Math.max(1, size));
        String normalizedStatus = status == null || status.isBlank() ? null : status.trim().toUpperCase();
        if (normalizedStatus != null
                && !List.of("VISIBLE", "PENDING", "HIDDEN").contains(normalizedStatus)) {
            throw new BizException("评论状态筛选不合法");
        }
        String normalizedKeyword = keyword == null || keyword.isBlank() ? null : keyword.trim();
        Page<Comment> result = commentRepository.adminSearch(normalizedStatus, normalizedKeyword,
                PageRequest.of(p - 1, s, Sort.by(Sort.Order.desc("createdAt"))));
        Map<Long, String> titles = result.getContent().stream()
                .map(Comment::getPostId)
                .distinct()
                .map(id -> postRepository.findById(id).orElse(null))
                .filter(post -> post != null)
                .collect(Collectors.toMap(Post::getId, Post::getTitle, (a, b) -> a));
        return new PageResp<>(result.getContent().stream()
                .map(c -> toView(c, null, titles.get(c.getPostId())))
                .toList(), result.getTotalElements(), p, s);
    }

    private boolean isVisible(Comment comment) {
        return "VISIBLE".equals(comment.getStatus());
    }

    private CommentView toView(Comment c, Long currentUserId, String postTitle) {
        boolean mine = currentUserId != null && c.getUserId().equals(currentUserId);
        return new CommentView(c.getId(), c.getContent(), c.getCreatedAt(), c.getParentId(),
                c.getUserId(), c.getNickname(), c.getAvatar(), mine, c.getStatus(), c.getPostId(), postTitle);
    }
}
