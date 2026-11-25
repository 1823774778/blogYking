package com.deek.blog.service;

import com.deek.blog.common.BizException;
import com.deek.blog.service.music.LxSourceRuntime;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
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
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LX 音源导入、歌曲搜索与播放地址解析。
 *
 * <p>自定义音源协议只提供 musicUrl，不提供搜索；本站搜索固定使用网易云公开曲库，
 * 再由导入的音源解析播放地址。付费/版权受限歌曲不会绕过平台限制。</p>
 */
@Service
public class MusicSourceService {

    private static final Logger log = LoggerFactory.getLogger(MusicSourceService.class);
    private static final String USER_AGENT = "Mozilla/5.0 (compatible; DeekBlog/1.18; +https://github.com/1823774778/blogYking)";
    private static final int MAX_SCRIPT_BYTES = 2 * 1024 * 1024;
    private static final int MAX_SEARCH_LIMIT = 30;
    private static final long RUNTIME_TTL_MILLIS = 30 * 60 * 1000L;
    private static final long SEARCH_TTL_MILLIS = 2 * 60 * 1000L;
    private static final Set<String> QUALITIES = Set.of("128k", "320k", "flac");
    private static final Map<String, String> PLATFORM_LABELS = Map.of(
            "wy", "网易音乐",
            "tx", "QQ 音乐",
            "kw", "酷我音乐",
            "kg", "酷狗音乐",
            "mg", "咪咕音乐"
    );
    private static final Map<String, String> QUALITY_LABELS = Map.of(
            "128k", "标准",
            "320k", "高品质",
            "flac", "无损"
    );

    private final SiteConfigService siteConfigService;
    private final ObjectMapper objectMapper;
    private final HttpClient client;
    private final Map<String, RuntimeEntry> runtimes = new ConcurrentHashMap<>();
    private final Map<String, SearchEntry> searches = new HashMap<>();
    private final Object runtimeLock = new Object();
    private final Object searchLock = new Object();

    public MusicSourceService(SiteConfigService siteConfigService, ObjectMapper objectMapper) {
        this.siteConfigService = siteConfigService;
        this.objectMapper = objectMapper;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .build();
    }

    public String configuredSourceUrl() {
        return String.valueOf(siteConfigService.get().getOrDefault("musicSourceUrl", "")).trim();
    }

    public Map<String, Object> status() {
        Map<String, String> cfg = siteConfigService.get();
        Map<String, Object> data = new LinkedHashMap<>();
        String url = String.valueOf(cfg.getOrDefault("musicSourceUrl", "")).trim();
        data.put("configured", !url.isBlank());
        data.put("url", url);
        data.put("name", cfg.getOrDefault("musicSourceName", ""));
        data.put("version", cfg.getOrDefault("musicSourceVersion", ""));
        data.put("description", cfg.getOrDefault("musicSourceDescription", ""));
        data.put("platforms", split(cfg.getOrDefault("musicSourcePlatforms", "")));
        data.put("qualities", split(cfg.getOrDefault("musicSourceQualities", "")));
        data.put("platformLabels", platformLabels());
        return data;
    }

    public Map<String, Object> preview(String rawUrl, boolean refresh) {
        String url = normalizeSourceUrl(rawUrl);
        LxSourceRuntime runtime = loadRuntime(url, refresh);
        Map<String, Object> data = describe(runtime, url);
        data.put("tested", false);
        data.put("testMessage", "音源已初始化，尚未测试播放地址");
        if (runtime.platforms().containsKey("wy")) {
            try {
                String testUrl = runtime.resolveMusicUrl("wy", "1883571160", "128k");
                data.put("tested", !testUrl.isBlank());
                data.put("testMessage", "音源播放地址测试通过");
                data.put("testTrack", Map.of(
                        "id", "1883571160",
                        "title", "慢慢喜欢你",
                        "artist", "白青",
                        "url", secureUrl(testUrl)
                ));
            } catch (RuntimeException e) {
                data.put("testMessage", "音源已初始化，但测试曲目未返回地址：" + shortMessage(e));
            }
        }
        return data;
    }

    public Map<String, Object> search(String keyword, int limit) {
        String text = String.valueOf(keyword == null ? "" : keyword).trim();
        if (text.isEmpty()) throw new BizException("请输入要搜索的歌曲或歌手");
        if (text.length() > 50) text = text.substring(0, 50);
        int size = Math.max(1, Math.min(MAX_SEARCH_LIMIT, limit));
        String sourceUrl = requireSourceUrl();
        LxSourceRuntime runtime = loadRuntime(sourceUrl, false);
        if (!runtime.platforms().containsKey("wy")) {
            throw new BizException("当前音源没有提供网易云平台，暂时无法使用站内搜索");
        }

        String cacheKey = text.toLowerCase(Locale.ROOT) + ":" + size;
        synchronized (searches) {
            SearchEntry entry = searches.get(cacheKey);
            if (entry != null && System.currentTimeMillis() - entry.createdAt < SEARCH_TTL_MILLIS) {
                return entry.data;
            }
        }

        try {
            JsonNode root = searchNetease(text, size);
            JsonNode songs = root.path("result").path("songs");
            List<Long> ids = new ArrayList<>();
            List<JsonNode> sourceSongs = new ArrayList<>();
            for (JsonNode song : songs) {
                if (song.path("id").canConvertToLong()) {
                    ids.add(song.path("id").asLong());
                    sourceSongs.add(song);
                }
            }
            Map<Long, JsonNode> details = ids.isEmpty() ? Map.of() : fetchSongDetails(ids);
            List<Map<String, Object>> tracks = new ArrayList<>();
            for (int i = 0; i < sourceSongs.size(); i += 1) {
                JsonNode song = sourceSongs.get(i);
                long id = ids.get(i);
                JsonNode detail = details.getOrDefault(id, song);
                tracks.add(toSearchTrack(id, song, detail));
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("keyword", text);
            data.put("total", root.path("result").path("songCount").asInt(tracks.size()));
            data.put("loaded", tracks.size());
            data.put("platform", "wy");
            data.put("platformLabel", "网易云音乐");
            data.put("source", describe(runtime, sourceUrl));
            data.put("tracks", tracks);
            synchronized (searches) {
                if (searches.size() > 60) searches.clear();
                searches.put(cacheKey, new SearchEntry(System.currentTimeMillis(), data));
            }
            return data;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("音乐搜索失败 keyword={}: {}", text, e.toString());
            throw new BizException("搜索暂时不可用，请稍后重试");
        }
    }

    public Map<String, Object> resolveUrl(String platform, String songId, String quality) {
        String sourceCode = String.valueOf(platform == null ? "" : platform).trim();
        if (sourceCode.isBlank()) sourceCode = "wy";
        if (!PLATFORM_LABELS.containsKey(sourceCode)) throw new BizException("不支持的平台");
        String id = String.valueOf(songId == null ? "" : songId).trim();
        if (!id.matches("[0-9A-Za-z_-]{1,80}")) throw new BizException("歌曲 ID 不合法");
        String qualityCode = String.valueOf(quality == null ? "" : quality).trim().toLowerCase(Locale.ROOT);
        if (!QUALITIES.contains(qualityCode)) qualityCode = "320k";

        String sourceUrl = requireSourceUrl();
        LxSourceRuntime runtime = loadRuntime(sourceUrl, false);
        String audio;
        String message = "";
        try {
            audio = runtime.resolveMusicUrl(sourceCode, id, qualityCode);
        } catch (RuntimeException e) {
            if ("wy".equals(sourceCode)) {
                try {
                    audio = resolveNeteaseFallback(id, qualityCode);
                    message = "音源未返回地址，已使用网易云公开地址";
                } catch (Exception fallbackError) {
                    throw new BizException("该歌曲暂时无法播放，可能受版权限制，换一首试试");
                }
            } else {
                throw new BizException("音源解析失败：" + shortMessage(e));
            }
        }
        if (audio == null || audio.isBlank()) {
            throw new BizException("该歌曲暂时无法播放，可能受版权限制，换一首试试");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("url", secureUrl(audio));
        data.put("source", sourceCode);
        data.put("platform", sourceCode);
        data.put("quality", qualityCode);
        data.put("qualityLabel", QUALITY_LABELS.getOrDefault(qualityCode, qualityCode));
        data.put("message", message);
        return data;
    }

    private Map<String, Object> describe(LxSourceRuntime runtime, String url) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("url", url);
        data.put("name", runtime.meta().name());
        data.put("version", runtime.meta().version());
        data.put("description", runtime.meta().description());
        data.put("author", runtime.meta().author());
        data.put("homepage", runtime.meta().homepage());
        data.put("initAlert", runtime.initAlert());
        List<Map<String, Object>> platforms = new ArrayList<>();
        for (LxSourceRuntime.PlatformInfo info : runtime.platforms().values()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", info.code());
            item.put("label", info.label());
            item.put("name", info.label());
            item.put("actions", info.actions());
            item.put("qualities", info.qualities());
            platforms.add(item);
        }
        data.put("platforms", platforms);
        data.put("platformLabels", platformLabels());
        data.put("qualities", split(siteConfigService.get().getOrDefault("musicSourceQualities",
                "128k,320k,flac")));
        return data;
    }

    private Map<String, Object> toSearchTrack(long id, JsonNode searchSong, JsonNode detail) {
        List<String> artists = new ArrayList<>();
        JsonNode artistNodes = detail.path("artists");
        if (!artistNodes.isArray()) artistNodes = searchSong.path("artists");
        for (JsonNode artist : artistNodes) {
            String name = artist.path("name").asText("").trim();
            if (!name.isBlank()) artists.add(name);
        }
        String cover = secureUrl(detail.path("album").path("picUrl").asText(""));
        if (cover.isBlank()) cover = secureUrl(searchSong.path("album").path("picUrl").asText(""));
        int fee = detail.path("fee").asInt(searchSong.path("fee").asInt(0));
        long durationMs = detail.path("duration").asLong(searchSong.path("duration").asLong(0));
        Map<String, Object> track = new LinkedHashMap<>();
        track.put("id", String.valueOf(id));
        track.put("source", "wy");
        track.put("platform", "wy");
        track.put("title", detail.path("name").asText(searchSong.path("name").asText("未命名歌曲")));
        track.put("artist", artists.isEmpty() ? "未知歌手" : String.join(" / ", artists));
        track.put("album", detail.path("album").path("name").asText(searchSong.path("album").path("name").asText("")));
        track.put("cover", cover);
        track.put("duration", Math.max(0, durationMs / 1000));
        track.put("fee", fee);
        track.put("vip", fee > 0);
        track.put("pageUrl", "https://music.163.com/#/song?id=" + id);
        return track;
    }

    private JsonNode searchNetease(String text, int size) throws Exception {
        String encoded = URLEncoder.encode(text, StandardCharsets.UTF_8);
        String[] endpoints = new String[]{
                "https://music.163.com/api/search/get/web?csrf_token=&s=" + encoded
                        + "&type=1&offset=0&limit=" + size,
                "https://music.163.com/api/cloudsearch/pc?s=" + encoded
                        + "&type=1&offset=0&limit=" + size
        };
        Exception last = null;
        synchronized (searchLock) {
            for (int attempt = 0; attempt < endpoints.length; attempt += 1) {
                try {
                    if (attempt > 0) Thread.sleep(120);
                    return getJson(endpoints[attempt]);
                } catch (Exception e) {
                    last = e;
                    log.debug("网易云搜索接口 {} 尝试失败: {}", attempt + 1, e.toString());
                }
            }
        }
        throw last == null ? new IOException("search unavailable") : last;
    }

    private Map<Long, JsonNode> fetchSongDetails(List<Long> ids) {
        Map<Long, JsonNode> result = new HashMap<>();
        try {
            for (int start = 0; start < ids.size(); start += 100) {
                List<Long> batch = ids.subList(start, Math.min(ids.size(), start + 100));
                String query = URLEncoder.encode(objectMapper.writeValueAsString(batch), StandardCharsets.UTF_8);
                JsonNode root = getJson("https://music.163.com/api/song/detail?ids=" + query);
                for (JsonNode song : root.path("songs")) {
                    if (song.path("id").canConvertToLong()) result.put(song.path("id").asLong(), song);
                }
            }
        } catch (Exception e) {
            log.debug("读取歌曲封面失败: {}", e.toString());
        }
        return result;
    }

    private String resolveNeteaseFallback(String id, String quality) throws Exception {
        long br = switch (quality) {
            case "128k" -> 128000L;
            case "flac" -> 999000L;
            default -> 320000L;
        };
        String query = URLEncoder.encode("[" + id + "]", StandardCharsets.UTF_8);
        JsonNode root = getJson("https://music.163.com/api/song/enhance/player/url?ids=" + query + "&br=" + br);
        JsonNode item = root.path("data").path(0);
        String url = item.path("url").asText("");
        if (url.isBlank()) throw new IOException("play url missing");
        return url;
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
        JsonNode root = objectMapper.readTree(response.body());
        if (root.path("code").asInt(200) != 200) {
            throw new IOException("API code " + root.path("code").asInt());
        }
        return root;
    }

    private String requireSourceUrl() {
        String url = configuredSourceUrl();
        if (url.isBlank()) throw new BizException("站点尚未导入 LX 音源，请先在后台站点音乐中导入音源脚本");
        return normalizeSourceUrl(url);
    }

    private LxSourceRuntime loadRuntime(String url, boolean refresh) {
        synchronized (runtimeLock) {
            RuntimeEntry entry = runtimes.get(url);
            long now = System.currentTimeMillis();
            if (!refresh && entry != null && now - entry.createdAt < RUNTIME_TTL_MILLIS) {
                return entry.runtime;
            }
            try {
                String script = downloadScript(url);
                String hash = sha256(script);
                if (!refresh && entry != null && entry.hash.equals(hash) && now - entry.createdAt < RUNTIME_TTL_MILLIS) {
                    return entry.runtime;
                }
                if (entry != null) entry.runtime.close();
                LxSourceRuntime runtime = createRuntimeWithTimeout(script);
                runtimes.put(url, new RuntimeEntry(hash, runtime, now));
                if (runtimes.size() > 3) {
                    String oldest = runtimes.keySet().iterator().next();
                    if (!oldest.equals(url)) {
                        RuntimeEntry old = runtimes.remove(oldest);
                        if (old != null) old.runtime.close();
                    }
                }
                return runtime;
            } catch (BizException e) {
                throw e;
            } catch (Exception e) {
                log.warn("加载音源失败 url={}: {}", url, e.toString());
                throw new BizException("音源导入失败：" + shortMessage(e));
            }
        }
    }

    private LxSourceRuntime createRuntimeWithTimeout(String script) throws Exception {
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "lx-source-init");
            thread.setDaemon(true);
            return thread;
        });
        try {
            var future = executor.submit(() -> LxSourceRuntime.create(script, objectMapper));
            return future.get(25, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new BizException("音源初始化超时，请检查音源地址或稍后重试");
        } finally {
            executor.shutdownNow();
        }
    }

    private String downloadScript(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/javascript,text/javascript,*/*")
                .GET()
                .build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("HTTP " + response.statusCode());
        }
        byte[] body = response.body();
        if (body.length == 0) throw new IOException("音源脚本为空");
        if (body.length > MAX_SCRIPT_BYTES) throw new IOException("音源脚本超过 2MB 限制");
        String script = new String(body, StandardCharsets.UTF_8);
        if (!script.contains("@name") || !script.contains("@version")) {
            throw new IllegalArgumentException("这个地址返回的内容不像 LX 音源脚本");
        }
        return script;
    }

    private String normalizeSourceUrl(String rawUrl) {
        String value = String.valueOf(rawUrl == null ? "" : rawUrl).trim();
        if (value.length() < 12 || value.length() > 2048) throw new BizException("请填写有效的音源脚本地址");
        if (!value.startsWith("http://") && !value.startsWith("https://")) value = "https://" + value;
        try {
            URI uri = URI.create(value);
            if (uri.getHost() == null || uri.getHost().isBlank()) throw new BizException("音源地址缺少域名");
            if (!uri.getPath().toLowerCase(Locale.ROOT).endsWith(".js")
                    && !uri.getQuery().toLowerCase(Locale.ROOT).contains("js")) {
                // 允许带查询参数的脚本接口，不强制后缀。
            }
            return value;
        } catch (IllegalArgumentException e) {
            throw new BizException("音源地址格式不正确");
        }
    }

    private Map<String, String> platformLabels() {
        Map<String, String> labels = new LinkedHashMap<>();
        PLATFORM_LABELS.forEach(labels::put);
        return labels;
    }

    private List<String> split(String value) {
        List<String> result = new ArrayList<>();
        for (String item : String.valueOf(value == null ? "" : value).split(",")) {
            if (!item.isBlank()) result.add(item.trim());
        }
        return result;
    }

    private String sha256(String text) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] data = digest.digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder builder = new StringBuilder(data.length * 2);
        for (byte item : data) builder.append(String.format("%02x", item));
        return builder.toString();
    }

    private String secureUrl(String url) {
        String value = String.valueOf(url == null ? "" : url).trim();
        if (value.startsWith("//")) return "https:" + value;
        if (value.startsWith("http://")) return "https://" + value.substring(7);
        return value;
    }

    private static String shortMessage(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) return error.getClass().getSimpleName();
        return message.length() > 180 ? message.substring(0, 180) : message;
    }

    @PreDestroy
    public void shutdown() {
        runtimes.values().forEach(entry -> entry.runtime.close());
        runtimes.clear();
    }

    private record RuntimeEntry(String hash, LxSourceRuntime runtime, long createdAt) {
    }

    private record SearchEntry(long createdAt, Map<String, Object> data) {
    }
}
