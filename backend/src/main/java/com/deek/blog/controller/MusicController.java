package com.deek.blog.controller;

import com.deek.blog.common.ApiResponse;
import com.deek.blog.service.LocalMusicService;
import com.deek.blog.service.MusicAudioProxyService;
import com.deek.blog.service.MusicPlaylistService;
import com.deek.blog.service.MusicLyricService;
import com.deek.blog.service.MusicSourceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.InputStream;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/music")
public class MusicController {

    private final MusicPlaylistService musicPlaylistService;
    private final MusicSourceService musicSourceService;
    private final MusicLyricService musicLyricService;
    private final LocalMusicService localMusicService;
    private final MusicAudioProxyService audioProxyService;

    public MusicController(MusicPlaylistService musicPlaylistService,
                           MusicSourceService musicSourceService,
                           MusicLyricService musicLyricService,
                           LocalMusicService localMusicService,
                           MusicAudioProxyService audioProxyService) {
        this.musicPlaylistService = musicPlaylistService;
        this.musicSourceService = musicSourceService;
        this.musicLyricService = musicLyricService;
        this.localMusicService = localMusicService;
        this.audioProxyService = audioProxyService;
    }

    /** 旧版歌单接口保留；带 platform/id 时读取当前音源平台的真实歌单。 */
    @GetMapping("/playlist")
    public ApiResponse<Map<String, Object>> playlist(
            @RequestParam(name = "url", required = false) String url,
            @RequestParam(name = "platform", required = false) String platform,
            @RequestParam(name = "id", required = false) String id) {
        if (platform != null && !platform.isBlank() && id != null && !id.isBlank()) {
            return ApiResponse.ok(musicSourceService.playlistDetail(platform, id));
        }
        if (url == null || url.isBlank()) throw new IllegalArgumentException("缺少歌单地址");
        return ApiResponse.ok(musicPlaylistService.resolve(url));
    }

    /** 当前音源各平台推荐歌单，按平台交错返回。 */
    @GetMapping("/playlists")
    public ApiResponse<Map<String, Object>> playlists(
            @RequestParam(name = "limit", defaultValue = "30") int limit,
            @RequestParam(name = "platform", defaultValue = "") String platform) {
        return ApiResponse.ok(musicSourceService.playlists(limit, platform));
    }

    @GetMapping("/source")
    public ApiResponse<Map<String, Object>> source() {
        return ApiResponse.ok(musicSourceService.status());
    }

    /** 当前音源声明的平台与站点可用的前台能力，不返回固定平台列表。 */
    @GetMapping("/platforms")
    public ApiResponse<Map<String, Object>> platforms() {
        return ApiResponse.ok(musicSourceService.platformCapabilities());
    }

    @GetMapping("/local")
    public ApiResponse<List<Map<String, Object>>> localTracks() {
        return ApiResponse.ok(localMusicService.publicTracks());
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

    @GetMapping("/fallback")
    public ApiResponse<Map<String, Object>> fallback(@RequestParam(name = "title") String title,
                                                     @RequestParam(name = "artist", defaultValue = "") String artist,
                                                     @RequestParam(name = "source", defaultValue = "") String source) {
        return ApiResponse.ok(musicSourceService.fallbackCandidates(title, artist, source));
    }

    /** 歌词：优先网易云公开歌词，回退 LRCLIB；返回可直接驱动歌词动画的时间轴。 */
    @GetMapping("/lyric")
    public ApiResponse<Map<String, Object>> lyric(@RequestParam(name = "id", defaultValue = "") String id,
                                                   @RequestParam(name = "source", defaultValue = "") String source,
                                                   @RequestParam(name = "title", defaultValue = "") String title,
                                                   @RequestParam(name = "artist", defaultValue = "") String artist,
                                                   @RequestParam(name = "album", defaultValue = "") String album,
                                                   @RequestParam(name = "duration", defaultValue = "") String duration,
                                                   @RequestParam(name = "refresh", defaultValue = "false") boolean refresh) {
        return ApiResponse.ok(musicLyricService.lookup(source, id, title, artist, album, duration, refresh));
    }

    @GetMapping("/url")
    public ApiResponse<Map<String, Object>> url(@RequestParam(name = "id") String id,
                                                @RequestParam(name = "source", defaultValue = "wy") String source,
                                                @RequestParam(name = "quality", defaultValue = "320k") String quality,
                                                @RequestParam(name = "title", defaultValue = "") String title,
                                                @RequestParam(name = "artist", defaultValue = "") String artist,
                                                @RequestParam(name = "album", defaultValue = "") String album,
                                                @RequestParam(name = "duration", defaultValue = "") String duration) {
        Map<String, Object> data = musicSourceService.resolveUrl(source, id, quality, title, artist, album, duration);
        Object audio = data.get("url");
        if (audio != null && !String.valueOf(audio).isBlank()) {
            Object resolvedSource = data.get("source");
            String proxySource = resolvedSource == null ? source : String.valueOf(resolvedSource);
            data.put("proxyUrl", audioProxyService.createProxyUrl(String.valueOf(audio), proxySource));
        }
        return ApiResponse.ok(data);
    }

    /** 同源音频代理：解决平台防盗链、http 混合内容与短时 token 失效。 */
    @GetMapping("/audio")
    public ResponseEntity<StreamingResponseBody> audio(
            @RequestParam(name = "token") String token,
            @org.springframework.web.bind.annotation.RequestHeader(name = "Range", required = false) String range) {
        MusicAudioProxyService.ProxyStream stream;
        try {
            stream = audioProxyService.open(token, range);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
        }
        if (stream == null) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        HttpHeaders headers = new HttpHeaders();
        String contentType = stream.headers().firstValue("Content-Type").orElse("audio/mpeg");
        if (contentType.isBlank() || contentType.startsWith("application/octet-stream")) contentType = "audio/mpeg";
        headers.set(HttpHeaders.CONTENT_TYPE, contentType);
        headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
        headers.set(HttpHeaders.CACHE_CONTROL, "private, max-age=3600");
        stream.headers().firstValue("Content-Length")
                .ifPresent(length -> headers.set(HttpHeaders.CONTENT_LENGTH, length));
        stream.headers().firstValue("Content-Range")
                .ifPresent(value -> headers.set(HttpHeaders.CONTENT_RANGE, value));
        StreamingResponseBody body = output -> {
            try (InputStream input = stream.body()) {
                input.transferTo(output);
            }
        };
        return ResponseEntity.status(stream.status()).headers(headers).body(body);
    }
}
