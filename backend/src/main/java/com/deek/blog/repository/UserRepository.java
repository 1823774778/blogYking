package com.deek.blog.repository;

import com.deek.blog.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);

    Optional<User> findByEmail(String email);

    Optional<User> findByUsernameOrEmail(String username, String email);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);

    long countByRole(String role);

    long countByEnabledFalse();

    @Query("select count(u) from User u where u.mutedUntil is not null and u.mutedUntil > :now")
    long countMuted(@Param("now") LocalDateTime now);

    @Query("""
            select u from User u
            where (:keyword is null
                   or lower(u.username) like lower(concat('%', :keyword, '%'))
                   or lower(u.email) like lower(concat('%', :keyword, '%'))
                   or lower(coalesce(u.nickname, '')) like lower(concat('%', :keyword, '%')))
              and (:role is null or u.role = :role)
              and (:enabled is null or u.enabled = :enabled)
              and (:muted is null
                   or (:muted = true and u.mutedUntil is not null and u.mutedUntil > :now)
                   or (:muted = false and (u.mutedUntil is null or u.mutedUntil <= :now)))
            """)
    Page<User> adminSearch(@Param("keyword") String keyword,
                           @Param("role") String role,
                           @Param("enabled") Boolean enabled,
                           @Param("muted") Boolean muted,
                           @Param("now") LocalDateTime now,
                           Pageable pageable);
}
