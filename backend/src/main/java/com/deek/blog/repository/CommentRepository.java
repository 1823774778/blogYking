package com.deek.blog.repository;

import com.deek.blog.entity.Comment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CommentRepository extends JpaRepository<Comment, Long> {

    List<Comment> findByPostIdOrderByCreatedAtAsc(Long postId);

    List<Comment> findByPostIdAndStatusOrderByCreatedAtAsc(Long postId, String status);

    List<Comment> findByParentIdOrderByCreatedAtAsc(Long parentId);

    long countByPostId(Long postId);

    @Query("""
            select count(c) from Comment c
            where c.postId in (select p.id from Post p where p.authorId = :authorId)
            """)
    long countByPostAuthorId(@Param("authorId") Long authorId);

    @Query("""
            select count(c) from Comment c
            where c.postId in (select p.id from Post p where p.authorId = :authorId)
              and c.status = :status
            """)
    long countByPostAuthorIdAndStatus(@Param("authorId") Long authorId,
                                      @Param("status") String status);

    long countByStatus(String status);

    long countByUserId(Long userId);

    long countByUserIdAndStatus(Long userId, String status);

    Page<Comment> findAllByOrderByCreatedAtDesc(Pageable pageable);

    @Query("""
            select c from Comment c
            where (:status is null or c.status = :status)
              and (:keyword is null
                   or lower(c.content) like lower(concat('%', :keyword, '%'))
                   or lower(coalesce(c.nickname, '')) like lower(concat('%', :keyword, '%')))
            order by c.createdAt desc
            """)
    Page<Comment> adminSearch(@Param("status") String status,
                              @Param("keyword") String keyword,
                              Pageable pageable);

    @Query("""
            select c from Comment c
            where c.userId = :userId
              and (:status is null or c.status = :status)
              and (:keyword is null or lower(c.content) like lower(concat('%', :keyword, '%')))
            order by c.createdAt desc
            """)
    Page<Comment> adminSearchByUser(@Param("userId") Long userId,
                                    @Param("status") String status,
                                    @Param("keyword") String keyword,
                                    Pageable pageable);

    @Query("""
            select c from Comment c
            where c.postId in (select p.id from Post p where p.authorId = :authorId)
              and (:status is null or c.status = :status)
              and (:keyword is null
                   or lower(c.content) like lower(concat('%', :keyword, '%'))
                   or lower(coalesce(c.nickname, '')) like lower(concat('%', :keyword, '%')))
            order by c.createdAt desc
            """)
    Page<Comment> adminSearchByPostAuthor(@Param("authorId") Long authorId,
                                          @Param("status") String status,
                                          @Param("keyword") String keyword,
                                          Pageable pageable);

    void deleteByPostId(Long postId);
}
