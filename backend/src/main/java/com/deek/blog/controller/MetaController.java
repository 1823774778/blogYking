package com.deek.blog.controller;

import com.deek.blog.common.ApiResponse;
import com.deek.blog.service.SiteConfigService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/meta")
public class MetaController {

    private final SiteConfigService siteConfigService;

    public MetaController(SiteConfigService siteConfigService) {
        this.siteConfigService = siteConfigService;
    }

    @GetMapping("/site")
    public ApiResponse<Map<String, String>> site() {
        return ApiResponse.ok(siteConfigService.get());
    }
}
