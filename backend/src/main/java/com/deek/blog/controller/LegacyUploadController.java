package com.deek.blog.controller;

import com.deek.blog.service.storage.LocalStorageStore;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * 兼容旧版文章和头像里保存的 /uploads/<文件名> 地址。
 * 新文件使用 /uploads/images/<文件名> 等分类路径，由静态资源映射直接提供。
 */
@RestController
public class LegacyUploadController {

    private final LocalStorageStore localStore;

    public LegacyUploadController(LocalStorageStore localStore) {
        this.localStore = localStore;
    }

    @GetMapping("/uploads/{filename:.+}")
    public ResponseEntity<Resource> legacy(@PathVariable String filename) {
        Path path = localStore.findByStoredName(filename);
        if (path == null) {
            path = localStore.findLegacyByName(filename);
        }
        if (path == null || !Files.isRegularFile(path)) {
            return ResponseEntity.notFound().build();
        }
        String contentType;
        try {
            contentType = Files.probeContentType(path);
        } catch (Exception e) {
            contentType = null;
        }
        if (contentType == null || contentType.isBlank()) {
            contentType = MediaType.APPLICATION_OCTET_STREAM_VALUE;
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .contentLength(path.toFile().length())
                .cacheControl(CacheControl.maxAge(7, TimeUnit.DAYS).cachePublic())
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .body(new FileSystemResource(path));
    }
}
