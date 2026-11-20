package com.deek.blog.controller;

import com.deek.blog.common.ApiResponse;
import com.deek.blog.dto.TagDtos.TagAdminView;
import com.deek.blog.dto.TagDtos.TagReq;
import com.deek.blog.service.TagService;
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
@RequestMapping("/api/admin/tags")
public class AdminTagController {

    private final TagService tagService;

    public AdminTagController(TagService tagService) {
        this.tagService = tagService;
    }

    @GetMapping
    public ApiResponse<List<TagAdminView>> list() {
        return ApiResponse.ok(tagService.adminList());
    }

    @PostMapping
    public ApiResponse<TagAdminView> create(@RequestBody TagReq req) {
        return ApiResponse.ok(tagService.create(req));
    }

    @PutMapping("/{id}")
    public ApiResponse<TagAdminView> update(@PathVariable Long id, @RequestBody TagReq req) {
        return ApiResponse.ok(tagService.update(id, req));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        tagService.delete(id);
        return ApiResponse.ok();
    }
}
