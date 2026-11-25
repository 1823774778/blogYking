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
@RequestMapping("/api/music")
public class MusicController {

    private final MusicPlaylistService musicPlaylistService;
    private final MusicSourceService musicSourceService;

    public MusicController(MusicPlaylistService musicPlaylistService, MusicSourceService musicSourceService) {
        this.musicPlaylistService = musicPlaylistService;
        this.musicSourceService = musicSourceService;
    }

    /** 旧版歌单接口保留，兼容已保存旧配置的站点。 */
    @GetMapping("/playlist")
    public ApiResponse<Map<String, Object>> playlist(@RequestParam(name = "url") String url) {
        return ApiResponse.ok(musicPlaylistService.resolve(url));
    }

    @GetMapping("/source")
    public ApiResponse<Map<String, Object>> source() {
        return ApiResponse.ok(musicSourceService.status());
    }

    @GetMapping("/search")
    public ApiResponse<Map<String, Object>> search(@RequestParam(name = "keyword") String keyword,
                                                   @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return ApiResponse.ok(musicSourceService.search(keyword, limit));
    }

    @GetMapping("/charts")
    public ApiResponse<Map<String, Object>> charts(@RequestParam(name = "platform", defaultValue = "wy") String platform,
                                                   @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return ApiResponse.ok(musicSourceService.charts(platform, limit));
    }

    @GetMapping("/url")
    public ApiResponse<Map<String, Object>> url(@RequestParam(name = "id") String id,
                                                @RequestParam(name = "source", defaultValue = "wy") String source,
                                                @RequestParam(name = "quality", defaultValue = "320k") String quality) {
        return ApiResponse.ok(musicSourceService.resolveUrl(source, id, quality));
    }
}
