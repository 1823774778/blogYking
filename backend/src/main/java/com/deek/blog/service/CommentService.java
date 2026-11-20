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

import java.util.List;

@Service
public class CommentService {

    private final CommentRepository commentRepository;
    private final PostRepository postRepository;

    public CommentService(CommentRepository commentRepository, PostRepository postRepository) {
        this.commentRepository = commentRepository;
        this.postRepository = postRepository;
    }

    public List<CommentView> list(Long postId, Long currentUserId) {
        return commentRepository.findByPostIdOrderByCreatedAtAsc(postId).stream()
                .map(c -> toView(c, currentUserId))
                .toList();
    }

    @Transactional
    public CommentView add(Long postId, User user, CommentReq req) {
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
        Comment comment = new Comment();
        comment.setPostId(postId);
        comment.setUserId(user.getId());
        comment.setParentId(parentId);
        comment.setContent(content);
        comment.setNickname(PostService.displayName(user));
        comment.setAvatar(user.getAvatar());
        commentRepository.save(comment);

        post.setCommentCount(post.getCommentCount() + 1);
        postRepository.save(post);
        return toView(comment, user.getId());
    }

    @Transactional
    public void delete(Long commentId, Long currentUserId, boolean admin) {
        Comment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> BizException.notFound("评论不存在"));
        if (!admin && !comment.getUserId().equals(currentUserId)) {
            throw BizException.forbidden("只能删除自己的评论");
        }
        List<Comment> children = commentRepository.findByParentIdOrderByCreatedAtAsc(commentId);
        long removed = 1 + children.size();
        commentRepository.deleteAll(children);
        commentRepository.delete(comment);

        postRepository.findById(comment.getPostId()).ifPresent(post -> {
            post.setCommentCount(Math.max(0, post.getCommentCount() - removed));
            postRepository.save(post);
        });
    }

    public PageResp<CommentView> adminList(int page, int size) {
        int p = Math.max(1, page);
        int s = Math.min(100, Math.max(1, size));
        Page<Comment> result = commentRepository.findAll(
                PageRequest.of(p - 1, s, Sort.by(Sort.Order.desc("createdAt"))));
        return new PageResp<>(result.getContent().stream().map(c -> toView(c, null)).toList(),
                result.getTotalElements(), p, s);
    }

    private CommentView toView(Comment c, Long currentUserId) {
        boolean mine = currentUserId != null && c.getUserId().equals(currentUserId);
        return new CommentView(c.getId(), c.getContent(), c.getCreatedAt(), c.getParentId(),
                c.getUserId(), c.getNickname(), c.getAvatar(), mine);
    }
}
