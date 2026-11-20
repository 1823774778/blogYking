package com.deek.blog.controller;

import com.deek.blog.common.ApiResponse;
import com.deek.blog.service.AiService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/ai")
public class AiController {

    public record SummarizeReq(String title, String content) {
    }

    public record ArticleGenReq(String topic, String outline, String tone, String length) {
    }

    public record ArticleReviewReq(String title, String content) {
    }

    public record CommentReviewReq(String postTitle, String content) {
    }

    public record CommentSummaryReq(String postTitle, List<String> comments) {
    }

    private final AiService aiService;

    public AiController(AiService aiService) {
        this.aiService = aiService;
    }

    @GetMapping("/status")
    public ApiResponse<Map<String, Object>> status() {
        return ApiResponse.ok(aiService.status());
    }

    @PostMapping("/test")
    public ApiResponse<Map<String, Object>> test() {
        return ApiResponse.ok(aiService.test());
    }

    @GetMapping("/models")
    public ApiResponse<Map<String, Object>> models() {
        return ApiResponse.ok(aiService.models());
    }

    @PostMapping("/generate-article")
    public ApiResponse<Map<String, Object>> generateArticle(@RequestBody ArticleGenReq req) {
        return ApiResponse.ok(aiService.generateArticle(req.topic(), req.outline(), req.tone(), req.length()));
    }

    @PostMapping("/review-article")
    public ApiResponse<Map<String, Object>> reviewArticle(@RequestBody ArticleReviewReq req) {
        return ApiResponse.ok(aiService.reviewArticle(req.title(), req.content()));
    }

    @PostMapping("/review-comment")
    public ApiResponse<Map<String, Object>> reviewComment(@RequestBody CommentReviewReq req) {
        return ApiResponse.ok(aiService.reviewComment(req.postTitle(), req.content()));
    }

    @PostMapping("/summarize-comments")
    public ApiResponse<Map<String, String>> summarizeComments(@RequestBody CommentSummaryReq req) {
        String summary = aiService.summarizeComments(req.postTitle(), req.comments());
        return ApiResponse.ok(Map.of("summary", summary));
    }

    @PostMapping("/summarize")
    public ApiResponse<Map<String, String>> summarize(@RequestBody SummarizeReq req) {
        String summary = aiService.summarize(req.title(), req.content());
        return ApiResponse.ok(Map.of("summary", summary));
    }
}
