package com.deek.blog.controller;

import com.deek.blog.common.ApiResponse;
import com.deek.blog.dto.NotificationDtos.NotificationReq;
import com.deek.blog.dto.NotificationDtos.NotificationView;
import com.deek.blog.service.NotificationService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/notifications")
public class AdminNotificationController {

    private final NotificationService notificationService;

    public AdminNotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping
    public ApiResponse<List<NotificationView>> list() {
        return ApiResponse.ok(notificationService.adminList());
    }

    @PostMapping
    public ApiResponse<NotificationView> create(@RequestBody NotificationReq req) {
        return ApiResponse.ok(notificationService.create(req));
    }

    @PutMapping("/{id}")
    public ApiResponse<NotificationView> update(@PathVariable Long id, @RequestBody NotificationReq req) {
        return ApiResponse.ok(notificationService.update(id, req));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        notificationService.delete(id);
        return ApiResponse.ok();
    }
}
