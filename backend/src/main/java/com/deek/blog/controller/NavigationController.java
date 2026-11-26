package com.deek.blog.controller;

import com.deek.blog.common.ApiResponse;
import com.deek.blog.service.NavigationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/navigation")
public class NavigationController {

    private final NavigationService navigationService;

    public NavigationController(NavigationService navigationService) {
        this.navigationService = navigationService;
    }

    @GetMapping
    public ApiResponse<List<NavigationService.NavItem>> list() {
        return ApiResponse.ok(navigationService.list());
    }

    @PutMapping
    public ApiResponse<List<NavigationService.NavItem>> save(
            @RequestBody List<NavigationService.NavItem> items) {
        return ApiResponse.ok(navigationService.save(items));
    }
}
