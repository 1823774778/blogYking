package com.deek.blog.service;

import com.deek.blog.common.BizException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 读取云端歌单。当前对网易云音乐使用公开页面接口换取曲目和可播放地址；
 * 其他平台保留原链接，由前台使用官方播放器或外部入口打开。
 */
@Service
public class MusicPlaylistService {

    private static final Logger log = LoggerFactory.getLogger(MusicPlaylistService.class);
    private static final int MAX_TRACKS = 200;
    private static final long CACHE_MILLIS = 5 * 60 * 1000L;
    private static final String USER_AGENT = "Mozilla/5.0 (compatible; DeekBlog/1.17; +https://github.com/1823774778/blogYking)";
    private static final Set<String> NETEASE_HOSTS = Set.of("music.163.com", "y.music.163.com", "163cn.tv");
    private static final Set<String> AUDIO_SUFFIXES = Set.of(".mp3", ".m4a", ".aac", ".wav", ".ogg", ".flac", ".opus");

    private final ObjectMapper objectMapper;
    private final Map<String, CacheEntry> cache = new HashMap<>();
    private final HttpClient client;

    public MusicPlaylistService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(6))
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .build();
    }

    public Map<String, Object> resolve(String rawUrl) {
        String url = normalizeUrl(rawUrl);
        String platform = detectPlatform(url);
        if (isDirectAudio(url)) {
            return directAudio(url);
        }
        if (!"netease".equals(platform)) {
            return unsupportedPlatform(platform, url);
        }

        String neteaseUrl = url;
        String id = extractNeteaseId(neteaseUrl);
        if (id.isBlank()) {
            try {
                neteaseUrl = followShortLink(neteaseUrl);
                id = extractNeteaseId(neteaseUrl);
            } catch (Exception ignored) {
                /* 交给下面的统一错误提示 */
            }
        }
        if (id.isBlank()) {
            throw new BizException("无法识别网易云歌单 ID，请粘贴完整歌单分享链接");
        }

        String cacheKey = "netease:" + id;
        synchronized (cache) {
            CacheEntry entry = cache.get(cacheKey);
            if (entry != null && System.currentTimeMillis() - entry.createdAt < CACHE_MILLIS) {
                return entry.data;
            }
        }

        try {
            Map<String, Object> data = fetchNetease(id, neteaseUrl);
            synchronized (cache) {
                if (cache.size() > 24) cache.clear();
                cache.put(cacheKey, new CacheEntry(System.currentTimeMillis(), data));
            }
            return data;
        } catch (Exception e) {
            log.warn("读取网易云歌单失败 id={}: {}", id, e.toString());
            throw new BizException("云端歌单读取失败，请检查链接或稍后重试");
        }
    }

    private Map<String, Object> fetchNetease(String id, String originalUrl) throws IOException, InterruptedException {
        JsonNode playlist = fetchPlaylistDetail(id);
        if (playlist.isMissingNode() || playlist.isNull()) {
            throw new IOException("playlist missing");
        }

        LinkedHashSet<Long> trackIds = new LinkedHashSet<>();
        for (JsonNode node : playlist.path("trackIds")) {
            if (node.path("id").canConvertToLong()) trackIds.add(node.path("id").asLong());
            if (trackIds.size() >= MAX_TRACKS) break;
        }
        if (trackIds.isEmpty()) {
            for (JsonNode node : playlist.path("tracks")) {
                if (node.path("id").canConvertToLong()) trackIds.add(node.path("id").asLong());
                if (trackIds.size() >= MAX_TRACKS) break;
            }
        }

        List<Long> ids = new ArrayList<>(trackIds);
        Map<Long, JsonNode> songs = fetchSongDetails(ids);
        Map<Long, String> audioUrls = fetchAudioUrls(ids);

        String title = playlist.path("name").asText("网易云歌单");
        String cover = secureUrl(playlist.path("coverImgUrl").asText(""));
        int declaredCount = playlist.path("trackCount").asInt(ids.size());

        List<Map<String, Object>> tracks = new ArrayList<>();
        for (Long trackId : ids) {
            JsonNode song = songs.get(trackId);
            if (song == null) continue;
            List<String> artists = new ArrayList<>();
            for (JsonNode artist : song.path("artists")) {
                String name = artist.path("name").asText("").trim();
                if (!name.isBlank()) artists.add(name);
            }
            String songTitle = song.path("name").asText("未命名曲目");
            String albumCover = secureUrl(song.path("album").path("picUrl").asText(""));
            String audio = audioUrls.getOrDefault(trackId, "");
            Map<String, Object> track = new LinkedHashMap<>();
            track.put("id", trackId);
            track.put("title", songTitle);
            track.put("artist", String.join(" / ", artists));
            track.put("album", song.path("album").path("name").asText(""));
            track.put("cover", albumCover.isBlank() ? cover : albumCover);
            track.put("duration", Math.max(0, song.path("duration").asLong(0) / 1000));
            track.put("url", audio);
            track.put("playable", !audio.isBlank());
            track.put("pageUrl", "https://music.163.com/#/song?id=" + trackId);
            tracks.add(track);
        }

        int playableCount = (int) tracks.stream().filter(item -> Boolean.TRUE.equals(item.get("playable"))).count();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("available", playableCount > 0);
        data.put("platform", "netease");
        data.put("platformLabel", "网易云音乐");
        data.put("url", originalUrl);
        data.put("title", title);
        data.put("cover", cover);
        data.put("trackCount", declaredCount);
        data.put("loadedCount", tracks.size());
        data.put("playableCount", playableCount);
        data.put("tracks", tracks);
        data.put("message", playableCount > 0 ? "" : "该歌单暂未读取到可播放曲目");
        return data;
    }

    private JsonNode fetchPlaylistDetail(String id) throws IOException, InterruptedException {
        for (int attempt = 0; attempt < 2; attempt += 1) {
            JsonNode root = getJson("https://music.163.com/api/v6/playlist/detail?id=" + id + "&n=1000&offset=0");
            JsonNode playlist = root.path("playlist");
            if (playlist.isObject()) return playlist;
            if (attempt == 0) Thread.sleep(900);
        }
        return objectMapper.getNodeFactory().missingNode();
    }

    private Map<Long, JsonNode> fetchSongDetails(List<Long> ids) throws IOException, InterruptedException {
        Map<Long, JsonNode> result = new HashMap<>();
        for (int start = 0; start < ids.size(); start += 100) {
            List<Long> batch = ids.subList(start, Math.min(ids.size(), start + 100));
            String query = URLEncoder.encode(objectMapper.writeValueAsString(batch), StandardCharsets.UTF_8);
            JsonNode root = getJson("https://music.163.com/api/song/detail?ids=" + query);
            for (JsonNode song : root.path("songs")) {
                if (song.path("id").canConvertToLong()) result.put(song.path("id").asLong(), song);
            }
        }
        return result;
    }

    private Map<Long, String> fetchAudioUrls(List<Long> ids) throws IOException, InterruptedException {
        Map<Long, String> result = new HashMap<>();
        for (int start = 0; start < ids.size(); start += 50) {
            List<Long> batch = ids.subList(start, Math.min(ids.size(), start + 50));
            String query = URLEncoder.encode(objectMapper.writeValueAsString(batch), StandardCharsets.UTF_8);
            JsonNode root = getJson("https://music.163.com/api/song/enhance/player/url?ids=" + query + "&br=128000");
            for (JsonNode item : root.path("data")) {
                long id = item.path("id").asLong(0);
                String url = item.path("url").asText("");
                if (id > 0 && !url.isBlank()) result.put(id, secureUrl(url));
            }
        }
        return result;
    }

    private JsonNode getJson(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(12))
                .header("User-Agent", USER_AGENT)
                .header("Referer", "https://music.163.com/")
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("HTTP " + response.statusCode());
        }
        return objectMapper.readTree(response.body());
    }

    private String followShortLink(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();
        HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
        URI finalUri = response.uri();
        if (finalUri == null || !NETEASE_HOSTS.contains(finalUri.getHost())) {
            throw new IOException("short link did not lead to NetEase");
        }
        return finalUri.toString();
    }

    private String normalizeUrl(String rawUrl) {
        String value = String.valueOf(rawUrl == null ? "" : rawUrl).trim();
        if (value.length() < 8 || value.length() > 2048) {
            throw new BizException("请填写有效的歌单分享链接");
        }
        if (value.startsWith("/")) return value;
        if (value.startsWith("uploads/") || value.startsWith("images/") || value.startsWith("assets/") || value.startsWith("static/")) {
            return "/" + value;
        }
        if (!value.startsWith("http://") && !value.startsWith("https://")) value = "https://" + value;
        try {
            URI uri = URI.create(value);
            if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
                throw new BizException("仅支持 http 或 https 链接");
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                throw new BizException("请填写有效的歌单分享链接");
            }
            return value;
        } catch (IllegalArgumentException e) {
            throw new BizException("请填写有效的歌单分享链接");
        }
    }

    private String detectPlatform(String url) {
        String text = url.toLowerCase();
        if (text.contains("music.163.com") || text.contains("163cn.tv")) return "netease";
        if (text.contains("y.qq.com") || text.contains("qqmusic")) return "qq";
        if (text.contains("kugou.com")) return "kugou";
        if (text.contains("kuwo.cn") || text.contains("kuwo.com")) return "kuwo";
        return "custom";
    }

    private String extractNeteaseId(String url) {
        String value = String.valueOf(url == null ? "" : url);
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("[?&#](?:id|playlistId)=([0-9]+)")
                .matcher(value);
        if (matcher.find()) return matcher.group(1);
        matcher = java.util.regex.Pattern.compile("/playlist/([0-9]+)").matcher(value);
        return matcher.find() ? matcher.group(1) : "";
    }

    private boolean isDirectAudio(String url) {
        try {
            String path = URI.create(url).getPath().toLowerCase();
            return AUDIO_SUFFIXES.stream().anyMatch(path::endsWith);
        } catch (Exception e) {
            return false;
        }
    }

    private Map<String, Object> directAudio(String url) {
        String name = "云端音频";
        try {
            String path = URI.create(url).getPath();
            String fileName = path.substring(path.lastIndexOf('/') + 1);
            int dot = fileName.lastIndexOf('.');
            if (dot > 0) name = fileName.substring(0, dot);
        } catch (Exception ignored) {
            /* 使用默认名称 */
        }
        Map<String, Object> track = new LinkedHashMap<>();
        track.put("id", url.hashCode());
        track.put("title", name);
        track.put("artist", "音频直链");
        track.put("album", "");
        track.put("cover", "");
        track.put("duration", 0);
        track.put("url", url);
        track.put("playable", true);
        track.put("pageUrl", url);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("available", true);
        data.put("platform", "audio");
        data.put("platformLabel", "云端音频");
        data.put("url", url);
        data.put("title", name);
        data.put("cover", "");
        data.put("trackCount", 1);
        data.put("loadedCount", 1);
        data.put("playableCount", 1);
        data.put("tracks", List.of(track));
        data.put("message", "");
        return data;
    }

    private Map<String, Object> unsupportedPlatform(String platform, String url) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("available", false);
        data.put("platform", platform);
        data.put("platformLabel", platformLabel(platform));
        data.put("url", url);
        data.put("title", "");
        data.put("cover", "");
        data.put("trackCount", 0);
        data.put("loadedCount", 0);
        data.put("playableCount", 0);
        data.put("tracks", List.of());
        data.put("message", "该平台使用官方播放器或原站打开");
        return data;
    }

    private String platformLabel(String platform) {
        return switch (platform) {
            case "qq" -> "QQ 音乐";
            case "kugou" -> "酷狗音乐";
            case "kuwo" -> "酷我音乐";
            default -> "云端歌单";
        };
    }

    private String secureUrl(String url) {
        String value = String.valueOf(url == null ? "" : url).trim();
        if (value.startsWith("//")) return "https:" + value;
        if (value.startsWith("http://")) return "https://" + value.substring(7);
        return value;
    }

    private record CacheEntry(long createdAt, Map<String, Object> data) {
    }
}
