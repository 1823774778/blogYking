package com.deek.blog.controller;

import com.deek.blog.common.ApiResponse;
import com.deek.blog.service.ActivityNoticeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/activity-notices")
public class AdminActivityNoticeController {

    private final ActivityNoticeService service;

    public AdminActivityNoticeController(ActivityNoticeService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> list(@RequestParam(defaultValue = "8") int limit) {
        return ApiResponse.ok(service.overview(limit));
    }

    @PostMapping("/read")
    public ApiResponse<Map<String, Object>> readAll() {
        long changed = service.markAllRead();
        return ApiResponse.ok(Map.of("changed", changed));
    }
}
