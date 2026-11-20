package com.deek.blog.repository;

import com.deek.blog.entity.PostLike;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PostLikeRepository extends JpaRepository<PostLike, Long> {

    boolean existsByPostIdAndUserId(Long postId, Long userId);

    Optional<PostLike> findByPostIdAndUserId(Long postId, Long userId);

    long countByPostId(Long postId);

    List<PostLike> findByUserIdOrderByCreatedAtDesc(Long userId);

    void deleteByPostId(Long postId);
}
