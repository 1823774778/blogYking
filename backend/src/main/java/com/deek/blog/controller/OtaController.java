package com.deek.blog.controller;

import com.deek.blog.common.ApiResponse;
import com.deek.blog.service.GithubReleaseService;
import com.deek.blog.service.OtaService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/ota")
public class OtaController {

    private final OtaService otaService;
    private final GithubReleaseService githubReleaseService;

    public OtaController(OtaService otaService, GithubReleaseService githubReleaseService) {
        this.otaService = otaService;
        this.githubReleaseService = githubReleaseService;
    }

    @GetMapping("/status")
    public ApiResponse<OtaService.StatusView> status() {
        return ApiResponse.ok(otaService.status());
    }

    @GetMapping("/releases")
    public ApiResponse<GithubReleaseService.ReleasesView> releases(
            @RequestParam(name = "refresh", defaultValue = "false") boolean refresh) {
        return ApiResponse.ok(githubReleaseService.releases(otaService.getCurrentVersion(), refresh));
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<OtaService.PendingInfo> upload(@RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(otaService.upload(file));
    }

    @PostMapping("/apply")
    public ApiResponse<Map<String, Object>> apply() {
        return ApiResponse.ok(otaService.apply());
    }

    @DeleteMapping("/pending")
    public ApiResponse<Void> cancel() {
        otaService.cancelPending();
        return ApiResponse.ok();
    }
}
