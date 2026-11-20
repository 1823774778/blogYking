package com.deek.blog.repository;

import com.deek.blog.entity.PostFavorite;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PostFavoriteRepository extends JpaRepository<PostFavorite, Long> {

    boolean existsByPostIdAndUserId(Long postId, Long userId);

    Optional<PostFavorite> findByPostIdAndUserId(Long postId, Long userId);

    long countByPostId(Long postId);

    List<PostFavorite> findByUserIdOrderByCreatedAtDesc(Long userId);

    void deleteByPostId(Long postId);
}
