package com.deek.blog.controller;

import com.deek.blog.common.ApiResponse;
import com.deek.blog.service.MusicPlaylistService;
import com.deek.blog.service.MusicSourceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/music")
public class AdminMusicController {

    private final MusicPlaylistService musicPlaylistService;
    private final MusicSourceService musicSourceService;

    public AdminMusicController(MusicPlaylistService musicPlaylistService, MusicSourceService musicSourceService) {
        this.musicPlaylistService = musicPlaylistService;
        this.musicSourceService = musicSourceService;
    }

    /** 旧版歌单预览。 */
    @GetMapping("/preview")
    public ApiResponse<Map<String, Object>> preview(@RequestParam(name = "url") String url) {
        return ApiResponse.ok(musicPlaylistService.resolve(url));
    }

    @GetMapping("/source/preview")
    public ApiResponse<Map<String, Object>> sourcePreview(@RequestParam(name = "url") String url,
                                                          @RequestParam(name = "refresh", defaultValue = "false") boolean refresh) {
        return ApiResponse.ok(musicSourceService.preview(url, refresh));
    }
}
