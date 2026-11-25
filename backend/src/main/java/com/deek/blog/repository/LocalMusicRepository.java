package com.deek.blog.repository;

import com.deek.blog.entity.LocalMusic;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LocalMusicRepository extends JpaRepository<LocalMusic, Long> {

    List<LocalMusic> findAllByOrderBySortOrderAscCreatedAtDesc();

    List<LocalMusic> findByEnabledTrueOrderBySortOrderAscCreatedAtDesc();
}
