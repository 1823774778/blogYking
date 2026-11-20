package com.deek.blog.controller;

import com.deek.blog.common.ApiResponse;
import com.deek.blog.common.BizException;
import com.deek.blog.security.SecurityUtils;
import com.deek.blog.service.StorageService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/upload")
public class UploadController {

    private final StorageService storageService;

    public UploadController(StorageService storageService) {
        this.storageService = storageService;
    }

    @PostMapping
    public ApiResponse<Map<String, String>> upload(@RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(Map.of("url",
                storageService.store(file, SecurityUtils.currentUserId())));
    }

    @PostMapping("/audio")
    public ApiResponse<Map<String, String>> uploadAudio(@RequestParam("file") MultipartFile file) {
        if (!SecurityUtils.isAdmin()) {
            throw BizException.forbidden("只有管理员可以上传站点音乐");
        }
        return ApiResponse.ok(Map.of("url",
                storageService.storeAudio(file, SecurityUtils.currentUserId())));
    }
}
