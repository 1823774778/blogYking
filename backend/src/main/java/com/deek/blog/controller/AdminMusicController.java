package com.deek.blog.controller;

import com.deek.blog.common.ApiResponse;
import com.deek.blog.security.SecurityUtils;
import com.deek.blog.service.LocalMusicService;
import com.deek.blog.service.MusicPlaylistService;
import com.deek.blog.service.MusicSourceService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/music")
public class AdminMusicController {

    private final MusicPlaylistService musicPlaylistService;
    private final MusicSourceService musicSourceService;
    private final LocalMusicService localMusicService;

    public AdminMusicController(MusicPlaylistService musicPlaylistService,
                                MusicSourceService musicSourceService,
                                LocalMusicService localMusicService) {
        this.musicPlaylistService = musicPlaylistService;
        this.musicSourceService = musicSourceService;
        this.localMusicService = localMusicService;
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

    @PostMapping("/source/upload")
    public ApiResponse<Map<String, Object>> sourceUpload(@RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(musicSourceService.previewUpload(file));
    }

    @GetMapping("/local")
    public ApiResponse<List<Map<String, Object>>> localTracks() {
        return ApiResponse.ok(localMusicService.adminTracks());
    }

    @PostMapping("/local/upload")
    public ApiResponse<Map<String, Object>> uploadLocal(@RequestParam("file") MultipartFile file,
                                                        @RequestParam(name = "title", defaultValue = "") String title,
                                                        @RequestParam(name = "artist", defaultValue = "") String artist) {
        return ApiResponse.ok(localMusicService.upload(file, title, artist, SecurityUtils.currentUserId()));
    }

    @PutMapping("/local/{id}")
    public ApiResponse<Map<String, Object>> updateLocal(@PathVariable("id") Long id,
                                                        @RequestBody Map<String, String> body) {
        return ApiResponse.ok(localMusicService.update(id, body));
    }

    @DeleteMapping("/local/{id}")
    public ApiResponse<Void> deleteLocal(@PathVariable("id") Long id) {
        localMusicService.delete(id);
        return ApiResponse.ok();
    }
}
