package com.deek.blog.repository;

import com.deek.blog.entity.Tag;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TagRepository extends JpaRepository<Tag, Long> {

    Optional<Tag> findFirstByNameIgnoreCase(String name);

    List<Tag> findByVisibleTrueOrderBySortOrderAscNameAsc();
}
