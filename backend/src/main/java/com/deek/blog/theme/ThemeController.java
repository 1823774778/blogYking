package com.deek.blog.theme;

import com.deek.blog.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/themes")
public class ThemeController {

    private final ThemeService themeService;

    public ThemeController(ThemeService themeService) {
        this.themeService = themeService;
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> list() {
        return ApiResponse.ok(themeService.overview());
    }

    @PostMapping("/{id}/activate")
    public ApiResponse<ThemeService.ThemeView> activate(@PathVariable String id) {
        return ApiResponse.ok(themeService.activate(id));
    }
}
