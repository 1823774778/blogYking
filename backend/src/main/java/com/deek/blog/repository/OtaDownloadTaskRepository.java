package com.deek.blog.repository;

import com.deek.blog.entity.OtaDownloadTask;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OtaDownloadTaskRepository extends JpaRepository<OtaDownloadTask, String> {

    OtaDownloadTask findFirstByOrderByCreatedAtDesc();

    List<OtaDownloadTask> findByStatus(String status);
}
