package com.deek.blog.controller;

import com.deek.blog.common.ApiResponse;
import com.deek.blog.service.GithubReleaseService;
import com.deek.blog.service.OtaDownloadService;
import com.deek.blog.service.OtaService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/ota")
public class OtaController {

    private final OtaService otaService;
    private final OtaDownloadService otaDownloadService;
    private final GithubReleaseService githubReleaseService;

    public OtaController(OtaService otaService, OtaDownloadService otaDownloadService,
                         GithubReleaseService githubReleaseService) {
        this.otaService = otaService;
        this.otaDownloadService = otaDownloadService;
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

    public record DownloadReq(String tag, String mode) {
    }

    @PostMapping("/download")
    public ApiResponse<OtaDownloadService.DownloadJobView> download(@RequestBody(required = false) DownloadReq req) {
        String tag = req == null ? "" : req.tag() == null ? "" : req.tag().trim();
        GithubReleaseService.ReleasesView view = githubReleaseService.releases(otaService.getCurrentVersion(), false);
        List<GithubReleaseService.ReleaseInfo> releases = view.releases();
        GithubReleaseService.ReleaseInfo target;
        if (tag.isBlank()) {
            target = view.latest();
        } else {
            target = releases.stream().filter(r -> tag.equals(r.tag()) || tag.equals(r.version()))
                    .findFirst().orElse(null);
        }
        if (target == null) {
            throw new com.deek.blog.common.BizException("没有找到指定的 Release 版本");
        }
        String mode = req == null || req.mode() == null ? "UPDATE" : req.mode().trim();
        return ApiResponse.ok(otaDownloadService.start(target, mode));
    }

    @GetMapping("/download/status")
    public ApiResponse<OtaDownloadService.DownloadJobView> downloadStatus(
            @RequestParam(name = "jobId", required = false) String jobId) {
        return ApiResponse.ok(otaDownloadService.status(jobId));
    }

    @PostMapping("/cache/clear")
    public ApiResponse<Map<String, Object>> clearCache() {
        githubReleaseService.clearCache();
        return ApiResponse.ok(Map.of("cleared", true));
    }

    @PostMapping("/apply")
    public ApiResponse<Map<String, Object>> apply() {
        return ApiResponse.ok(otaService.apply());
    }

    @PostMapping("/restart")
    public ApiResponse<Map<String, Object>> restart() {
        return ApiResponse.ok(otaService.restart());
    }

    @DeleteMapping("/pending")
    public ApiResponse<Void> cancel() {
        otaService.cancelPending();
        return ApiResponse.ok();
    }
}
