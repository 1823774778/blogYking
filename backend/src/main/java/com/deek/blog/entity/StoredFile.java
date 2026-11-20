package com.deek.blog.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;

/** 上传文件元数据：用于本地存储显示上传人、分类与搜索，不替代文件本身。 */
@Entity
@Table(name = "stored_files",
        uniqueConstraints = @UniqueConstraint(name = "uk_stored_file_provider_key",
                columnNames = {"provider", "object_key"}),
        indexes = {
                @Index(name = "idx_stored_file_provider", columnList = "provider"),
                @Index(name = "idx_stored_file_uploader", columnList = "uploader_id")
        })
public class StoredFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 16)
    private String provider = "local";

    /** 相对存储根目录的路径，例如 images/2026-cover.jpg。 */
    @Column(name = "object_key", nullable = false, length = 512)
    private String objectKey;

    @Column(length = 512)
    private String originalName;

    @Column(nullable = false, length = 16)
    private String category = "other";

    @Column(length = 128)
    private String contentType;

    @Column(nullable = false)
    private long size;

    @Column(length = 2000)
    private String url;

    private Long uploaderId;

    @Column(length = 64)
    private String uploaderUsername;

    @Column(length = 100)
    private String uploaderName;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getObjectKey() { return objectKey; }
    public void setObjectKey(String objectKey) { this.objectKey = objectKey; }
    public String getOriginalName() { return originalName; }
    public void setOriginalName(String originalName) { this.originalName = originalName; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public long getSize() { return size; }
    public void setSize(long size) { this.size = size; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public Long getUploaderId() { return uploaderId; }
    public void setUploaderId(Long uploaderId) { this.uploaderId = uploaderId; }
    public String getUploaderUsername() { return uploaderUsername; }
    public void setUploaderUsername(String uploaderUsername) { this.uploaderUsername = uploaderUsername; }
    public String getUploaderName() { return uploaderName; }
    public void setUploaderName(String uploaderName) { this.uploaderName = uploaderName; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
