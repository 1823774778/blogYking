package com.deek.blog.controller;

import com.deek.blog.common.ApiResponse;
import com.deek.blog.service.MusicPlaylistService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/music")
public class AdminMusicController {

    private final MusicPlaylistService musicPlaylistService;

    public AdminMusicController(MusicPlaylistService musicPlaylistService) {
        this.musicPlaylistService = musicPlaylistService;
    }

    @GetMapping("/preview")
    public ApiResponse<Map<String, Object>> preview(@RequestParam(name = "url") String url) {
        return ApiResponse.ok(musicPlaylistService.resolve(url));
    }
}
