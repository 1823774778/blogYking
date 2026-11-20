package com.deek.blog.repository;

import com.deek.blog.entity.Post;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PostRepository extends JpaRepository<Post, Long> {

    Page<Post> findAllByStatus(String status, Pageable pageable);

    long countByStatus(String status);

    @Query("""
            select p from Post p
            where (:status is null or p.status = :status)
              and (:keyword is null
                   or lower(p.title) like lower(concat('%', :keyword, '%'))
                   or lower(coalesce(p.summary, '')) like lower(concat('%', :keyword, '%'))
                   or lower(coalesce(p.tags, '')) like lower(concat('%', :keyword, '%'))
                   or lower(coalesce(p.authorName, '')) like lower(concat('%', :keyword, '%')))
            """)
    Page<Post> adminSearch(@Param("status") String status,
                           @Param("keyword") String keyword,
                           Pageable pageable);

    @Query("select coalesce(sum(p.views), 0) from Post p")
    Long totalViews();

    @Query("select p.tags from Post p where p.status = 'PUBLISHED' and p.tags is not null and p.tags <> ''")
    List<String> allTags();

    @Query("""
            select p from Post p
            where p.status = 'PUBLISHED'
              and (:kw is null
                   or lower(p.title) like lower(concat('%', :kw, '%'))
                   or lower(coalesce(p.summary, '')) like lower(concat('%', :kw, '%'))
                   or lower(coalesce(p.tags, '')) like lower(concat('%', :kw, '%')))
              and (:tag is null or lower(coalesce(p.tags, '')) like lower(concat('%', :tag, '%')))
            """)
    Page<Post> search(@Param("kw") String kw, @Param("tag") String tag, Pageable pageable);

    @Query("select p from Post p where p.status = 'PUBLISHED' order by p.views desc, p.createdAt desc")
    List<Post> findHot(Pageable pageable);

    List<Post> findByIdInAndStatus(List<Long> ids, String status);
}
