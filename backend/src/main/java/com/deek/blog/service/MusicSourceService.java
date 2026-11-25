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
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
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
    private static final int MAX_CHART_LIMIT = 30;
    private static final long RUNTIME_TTL_MILLIS = 30 * 60 * 1000L;
    private static final long SEARCH_TTL_MILLIS = 2 * 60 * 1000L;
    private static final long CHART_TTL_MILLIS = 10 * 60 * 1000L;
    private static final String CHART_FALLBACK_QUERY = "热门";
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
    private final Map<String, ChartEntry> charts = new HashMap<>();
    private final Object runtimeLock = new Object();
    private final Object searchLock = new Object();
    private final Object chartLock = new Object();

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

    /**
     * 各平台热歌榜。网易云、QQ、酷狗使用平台公开榜单；酷我和咪咕缺少稳定公开榜单接口，
     * 使用平台热门搜索兜底，并在响应中明确标记 fallback。
     */
    public Map<String, Object> charts(String platform, int limit) {
        String code = String.valueOf(platform == null ? "" : platform).trim().toLowerCase(Locale.ROOT);
        if (code.isBlank()) code = "wy";
        if (!PLATFORM_LABELS.containsKey(code)) throw new BizException("不支持的平台");

        int size = Math.max(1, Math.min(MAX_CHART_LIMIT, limit));
        String cacheKey = code + ":" + size;
        synchronized (chartLock) {
            ChartEntry entry = charts.get(cacheKey);
            if (entry != null && System.currentTimeMillis() - entry.createdAt < CHART_TTL_MILLIS) {
                return entry.data;
            }
        }

        String sourceUrl = requireSourceUrl();
        LxSourceRuntime runtime = loadRuntime(sourceUrl, false);
        if (!runtime.platforms().containsKey(code)) {
            throw new BizException(PLATFORM_LABELS.get(code) + "未包含在当前导入的音源中");
        }

        List<Map<String, Object>> tracks;
        boolean fallback = false;
        String chartTitle;
        try {
            switch (code) {
                case "tx" -> {
                    tracks = chartQq(size);
                    chartTitle = "QQ 音乐热歌榜";
                }
                case "kg" -> {
                    tracks = chartKugou(size);
                    chartTitle = "酷狗音乐 TOP500";
                }
                case "kw" -> {
                    tracks = chartKuwo(size);
                    chartTitle = "酷我音乐热歌";
                    fallback = true;
                }
                case "mg" -> {
                    tracks = chartMigu(size);
                    chartTitle = "咪咕音乐热歌";
                    fallback = true;
                }
                default -> {
                    tracks = chartNetease(size);
                    chartTitle = "网易云音乐热歌榜";
                }
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("获取平台热歌榜失败 platform={}: {}", code, e.toString());
            throw new BizException("暂时无法获取" + PLATFORM_LABELS.get(code) + "热歌榜，请稍后重试");
        }
        if (tracks.isEmpty()) {
            throw new BizException(PLATFORM_LABELS.get(code) + "暂时没有返回可用的热歌数据");
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("platform", code);
        data.put("platformLabel", PLATFORM_LABELS.get(code));
        data.put("chartTitle", chartTitle);
        data.put("fallback", fallback);
        data.put("updatedAt", OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        data.put("total", tracks.size());
        data.put("tracks", tracks);
        synchronized (chartLock) {
            if (charts.size() > 40) charts.clear();
            charts.put(cacheKey, new ChartEntry(System.currentTimeMillis(), data));
        }
        return data;
    }

    private List<Map<String, Object>> chartNetease(int size) throws Exception {
        JsonNode root = getJson("https://music.163.com/api/v6/playlist/detail?id=3778678&n=30&s=0");
        JsonNode playlist = root.path("result").isObject() ? root.path("result") : root.path("playlist");
        List<Map<String, Object>> tracks = new ArrayList<>();
        for (JsonNode song : playlist.path("tracks")) {
            if (tracks.size() >= size) break;
            String id = song.path("id").asText("").trim();
            if (id.isBlank()) continue;
            JsonNode artists = song.path("artists").isArray() ? song.path("artists") : song.path("ar");
            JsonNode album = song.path("album").isObject() ? song.path("album") : song.path("al");
            long durationMs = song.path("duration").asLong(song.path("dt").asLong(0));
            tracks.add(chartTrack(
                    "wy", id,
                    song.path("name").asText("未命名歌曲"),
                    joinArtists(artists),
                    album.path("name").asText(""),
                    album.path("picUrl").asText(""),
                    durationMs / 1000,
                    song.path("fee").asInt(0),
                    "https://music.163.com/#/song?id=" + id
            ));
        }
        return tracks;
    }

    private List<Map<String, Object>> chartQq(int size) throws Exception {
        String url = "https://c.y.qq.com/v8/fcg-bin/fcg_v8_toplist_cp.fcg?topid=26&format=json"
                + "&page=detail&type=top&song_begin=0&song_num=" + size + "&platform=yqq.json";
        JsonNode root = getJsonRaw(url, "https://y.qq.com/");
        List<Map<String, Object>> tracks = new ArrayList<>();
        for (JsonNode item : root.path("songlist")) {
            JsonNode song = item.path("data");
            String id = song.path("songmid").asText("").trim();
            if (id.isBlank()) continue;
            String albumMid = song.path("albummid").asText("");
            String cover = albumMid.isBlank() ? "" : "https://y.gtimg.cn/music/photo_new/T002R300x300M000" + albumMid + ".jpg";
            tracks.add(chartTrack(
                    "tx", id,
                    song.path("songname").asText("未命名歌曲"),
                    joinArtists(song.path("singer")),
                    song.path("albumname").asText(""),
                    cover,
                    song.path("interval").asLong(0),
                    song.path("pay").path("payplay").asInt(0),
                    "https://y.qq.com/n/ryqq/songDetail/" + id
            ));
        }
        return tracks;
    }

    private List<Map<String, Object>> chartKugou(int size) throws Exception {
        String url = "https://mobiles.kugou.com/api/v3/rank/song?rankid=8888&page=1&pagesize="
                + size + "&plat=0&version=8000";
        JsonNode root = getJsonRaw(url, "https://www.kugou.com/");
        List<Map<String, Object>> tracks = new ArrayList<>();
        for (JsonNode song : root.path("data").path("info")) {
            String id = song.path("hash").asText("").trim();
            if (id.isBlank()) continue;
            tracks.add(chartTrack(
                    "kg", id,
                    song.path("songname").asText("未命名歌曲"),
                    joinArtists(song.path("authors")),
                    "",
                    "",
                    song.path("duration").asLong(0),
                    song.path("pay_type").asInt(0),
                    "https://www.kugou.com/song/#hash=" + id
            ));
        }
        return tracks;
    }

    private List<Map<String, Object>> chartKuwo(int size) throws Exception {
        String url = "https://search.kuwo.cn/r.s?client=kt&all="
                + URLEncoder.encode("热歌", StandardCharsets.UTF_8)
                + "&pn=0&rn=" + size
                + "&uid=0&ver=kwplayer_ar_9.2.2.1&vipver=1&show_copyright_off=1&newver=1"
                + "&ft=music&cluster=0&strategy=2012&encoding=utf8&rformat=json&vermerge=1&mobi=1";
        JsonNode root = getJsonRaw(url, "https://www.kuwo.cn/");
        List<Map<String, Object>> tracks = new ArrayList<>();
        for (JsonNode song : root.path("abslist")) {
            String rawId = song.path("MUSICRID").asText("").trim();
            if (rawId.isBlank()) continue;
            String id = rawId.replaceFirst("^MUSIC_", "");
            String cover = "";
            String shortCover = song.path("web_albumpic_short").asText("");
            if (!shortCover.isBlank()) cover = "https://img1.kuwo.cn/star/albumcover/" + shortCover;
            tracks.add(chartTrack(
                    "kw", id,
                    cleanKuwoText(song.path("SONGNAME").asText("未命名歌曲")),
                    cleanKuwoText(song.path("ARTIST").asText("未知歌手")),
                    cleanKuwoText(song.path("ALBUM").asText("")),
                    cover,
                    song.path("DURATION").asLong(0),
                    song.path("PAY").asInt(0) > 0 ? 1 : 0,
                    "https://www.kuwo.cn/play_detail/" + id
            ));
        }
        return tracks;
    }

    private List<Map<String, Object>> chartMigu(int size) throws Exception {
        String url = "https://app.c.nf.migu.cn/MIGUM2.0/v1.0/content/search_all.do"
                + "?text=" + URLEncoder.encode(CHART_FALLBACK_QUERY, StandardCharsets.UTF_8)
                + "&pageNo=1&pageSize=30&searchSwitch="
                + URLEncoder.encode("{\"song\":1}", StandardCharsets.UTF_8);
        JsonNode root = getJsonRaw(url, "https://music.migu.cn/");
        List<Map<String, Object>> tracks = new ArrayList<>();
        for (JsonNode song : root.path("songResultData").path("result")) {
            if (tracks.size() >= size) break;
            String title = song.path("name").asText("").trim();
            String artist = joinArtists(song.path("singers"));
            if (title.isBlank() || isMiguFiller(title, artist)) continue;
            String id = song.path("copyrightId").asText("").trim();
            if (id.isBlank()) id = song.path("contentId").asText("").trim();
            if (id.isBlank()) continue;
            String cover = "";
            for (JsonNode image : song.path("imgItems")) {
                if ("03".equals(image.path("imgSizeType").asText())) {
                    cover = image.path("img").asText("");
                    break;
                }
            }
            if (cover.isBlank() && song.path("imgItems").size() > 0) {
                cover = song.path("imgItems").path(0).path("img").asText("");
            }
            tracks.add(chartTrack(
                    "mg", id,
                    title,
                    artist.isBlank() ? "未知歌手" : artist,
                    song.path("albums").path(0).path("name").asText(""),
                    cover,
                    0,
                    song.path("vipType").asInt(0),
                    "https://music.migu.cn/v3/music/song/" + id
            ));
        }
        return tracks;
    }

    private boolean isMiguFiller(String title, String artist) {
        String text = title + " " + artist;
        return text.contains("热歌榜") || text.contains("串烧") || text.contains("钢琴曲")
                || text.contains("伴奏") || text.contains("纯享") || text.contains("DJ")
                || text.contains("热歌推荐") || text.contains("热门歌曲");
    }

    private String cleanKuwoText(String value) {
        String text = String.valueOf(value == null ? "" : value).trim();
        return text.replaceAll("-《[^》]*》[^ ]*主题曲", "")
                .replaceAll("-《[^》]*》[^ ]*片尾曲", "")
                .replaceAll("-《[^》]*》[^ ]*插曲", "")
                .trim();
    }

    private String joinArtists(JsonNode nodes) {
        List<String> names = new ArrayList<>();
        for (JsonNode node : nodes) {
            String name = node.path("name").asText("");
            if (name.isBlank()) name = node.path("author_name").asText("");
            if (!name.isBlank()) names.add(name);
        }
        return names.isEmpty() ? "未知歌手" : String.join(" / ", names);
    }

    private Map<String, Object> chartTrack(String source, String id, String title, String artist,
                                           String album, String cover, long duration, int fee,
                                           String pageUrl) {
        Map<String, Object> track = new LinkedHashMap<>();
        track.put("id", id);
        track.put("source", source);
        track.put("platform", source);
        track.put("title", title);
        track.put("artist", artist);
        track.put("album", album);
        track.put("cover", secureUrl(cover));
        track.put("duration", Math.max(0, duration));
        track.put("fee", Math.max(0, fee));
        track.put("vip", fee > 0);
        track.put("pageUrl", pageUrl);
        return track;
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
        return getJson(url, "https://music.163.com/");
    }

    private JsonNode getJson(String url, String referer) throws IOException, InterruptedException {
        JsonNode root = getJsonRaw(url, referer);
        if (root.path("code").asInt(200) != 200) {
            throw new IOException("API code " + root.path("code").asInt());
        }
        return root;
    }

    private JsonNode getJsonRaw(String url, String referer) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(12))
                .header("User-Agent", USER_AGENT)
                .header("Referer", referer)
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("HTTP " + response.statusCode());
        }
        return objectMapper.readTree(response.body());
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

    private record ChartEntry(long createdAt, Map<String, Object> data) {
    }
}
