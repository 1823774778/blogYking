package com.deek.blog.repository;

import com.deek.blog.entity.Notification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    List<Notification> findByPublishedTrueOrderByPinnedDescCreatedAtDesc();

    List<Notification> findAllByOrderByPinnedDescCreatedAtDesc();
}
