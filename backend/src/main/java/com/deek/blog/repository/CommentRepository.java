package com.deek.blog.repository;

import com.deek.blog.entity.Comment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CommentRepository extends JpaRepository<Comment, Long> {

    List<Comment> findByPostIdOrderByCreatedAtAsc(Long postId);

    List<Comment> findByParentIdOrderByCreatedAtAsc(Long parentId);

    long countByPostId(Long postId);

    Page<Comment> findAllByOrderByCreatedAtDesc(Pageable pageable);

    void deleteByPostId(Long postId);
}
