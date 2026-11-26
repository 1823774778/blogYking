package com.deek.blog.service;

import com.deek.blog.common.BizException;
import com.deek.blog.service.music.LxSourceRuntime;
import com.deek.blog.service.storage.StoragePathResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * LX 音源导入、歌曲搜索与播放地址解析。
 *
 * <p>自定义音源协议只提供 musicUrl，不提供搜索；本站站内搜索使用当前音源声明的
 * 可搜索平台，再由导入的音源解析播放地址。付费/版权受限歌曲不会绕过平台限制。</p>
 */
@Service
public class MusicSourceService {

    private static final Logger log = LoggerFactory.getLogger(MusicSourceService.class);
    private static final String USER_AGENT = "Mozilla/5.0 (compatible; DeekBlog/1.18; +https://github.com/1823774778/blogYking)";
    private static final int MAX_SCRIPT_BYTES = 2 * 1024 * 1024;
    private static final int MAX_SEARCH_LIMIT = 30;
    private static final int MAX_CHART_LIMIT = 30;
    private static final int MAX_PLAYLIST_TOTAL = 36;
    /** 歌单详情读取上限只用于防御异常超大歌单；常规歌单会完整翻页读取。 */
    private static final int MAX_PLAYLIST_TRACKS = 1000;
    private static final int PLAYLIST_PAGE_SIZE = 100;
    private static final long RUNTIME_TTL_MILLIS = 6 * 60 * 60 * 1000L;
    private static final long SEARCH_TTL_MILLIS = 2 * 60 * 1000L;
    private static final long CHART_TTL_MILLIS = 10 * 60 * 1000L;
    private static final long PLAYLIST_TTL_MILLIS = 10 * 60 * 1000L;
    /** 播放地址缓存只保留很短时间，避免平台短 token 失效；用于重复播放/刷新后的快速命中。 */
    private static final long URL_TTL_MILLIS = 90 * 1000L;
    private static final String SOURCE_CACHE_DIR = "music-sources";
    private static final String LOCAL_SOURCE_PREFIX = "local://";
    private static final String CHART_FALLBACK_QUERY = "热门";
    private static final Set<String> QUALITIES = Set.of("128k", "320k", "flac");
    private static final Map<String, String> PLATFORM_LABELS = Map.of(
            "wy", "网易音乐",
            "tx", "QQ 音乐",
            "kw", "酷我音乐",
            "kg", "酷狗音乐",
            "mg", "咪咕音乐"
    );
    /** 本站已实现榜单适配的平台。平台是否出现在前台，仍由当前音源声明决定。 */
    private static final Set<String> CHART_PLATFORM_CODES = PLATFORM_LABELS.keySet();
    /** 本站已实现平台歌单读取适配的平台。 */
    private static final Set<String> PLAYLIST_PLATFORM_CODES = PLATFORM_LABELS.keySet();
    /** 站内搜索已适配的平台；实际检索范围还要与当前音源声明的平台取交集。 */
    private static final Set<String> SEARCH_PLATFORM_CODES = PLATFORM_LABELS.keySet();
    private static final Map<String, String> QUALITY_LABELS = Map.of(
            "128k", "标准",
            "320k", "高品质",
            "flac", "无损"
    );

    private final SiteConfigService siteConfigService;
    private final ObjectMapper objectMapper;
    private final HttpClient client;
    private final Path sourceDir;
    private final Map<String, RuntimeEntry> runtimes = new ConcurrentHashMap<>();
    private final Map<String, SearchEntry> searches = new HashMap<>();
    private final Map<String, ChartEntry> charts = new HashMap<>();
    private final Map<String, ChartEntry> playlistLists = new HashMap<>();
    private final Map<String, ChartEntry> playlistDetails = new HashMap<>();
    private final Map<String, UrlEntry> resolvedUrls = new ConcurrentHashMap<>();
    private final Object runtimeLock = new Object();
    private final Object searchLock = new Object();
    private final Object chartLock = new Object();
    private final Object playlistLock = new Object();

    public MusicSourceService(SiteConfigService siteConfigService, ObjectMapper objectMapper,
                              StoragePathResolver storagePaths) {
        this.siteConfigService = siteConfigService;
        this.objectMapper = objectMapper;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .build();
        this.sourceDir = storagePaths.root().resolve(SOURCE_CACHE_DIR).toAbsolutePath().normalize();
        try {
            Files.createDirectories(sourceDir);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建音源脚本缓存目录: " + sourceDir, e);
        }
    }

    public String configuredSourceUrl() {
        return String.valueOf(siteConfigService.get().getOrDefault("musicSourceUrl", "")).trim();
    }

    @PostConstruct
    void pruneLegacyScriptsOnStart() {
        pruneSourceScripts(configuredScriptFileName());
    }

    public Map<String, Object> status() {
        Map<String, String> cfg = siteConfigService.get();
        Map<String, Object> data = new LinkedHashMap<>();
        String url = String.valueOf(cfg.getOrDefault("musicSourceUrl", "")).trim();
        boolean local = isLocalSource(cfg);
        boolean configured = local ? sourceScriptReady(cfg) : !url.isBlank();
        data.put("configured", configured);
        data.put("type", local ? "file" : (url.isBlank() ? "" : "url"));
        data.put("url", local ? "" : url);
        data.put("name", cfg.getOrDefault("musicSourceName", ""));
        data.put("version", cfg.getOrDefault("musicSourceVersion", ""));
        data.put("description", cfg.getOrDefault("musicSourceDescription", ""));
        data.put("platforms", split(cfg.getOrDefault("musicSourcePlatforms", "")));
        data.put("qualities", split(cfg.getOrDefault("musicSourceQualities", "")));
        data.put("platformLabels", platformLabels());
        data.put("status", cfg.getOrDefault("musicSourceStatus", ""));
        data.put("verifiedAt", cfg.getOrDefault("musicSourceVerifiedAt", ""));
        data.put("localFileName", cfg.getOrDefault("musicSourceLocalFileName", ""));
        data.put("scriptReady", sourceScriptReady(cfg));
        return data;
    }

    /**
     * 当前音源实际支持的前台能力。平台名称、actions、音质均来自音源脚本，
     * 本站只追加“是否有榜单适配 / 是否能读取平台歌单 / 是否能承接站内搜索”这三项站点能力。
     */
    public Map<String, Object> platformCapabilities() {
        Map<String, String> cfg = siteConfigService.get();
        String url = String.valueOf(cfg.getOrDefault("musicSourceUrl", "")).trim();
        boolean local = isLocalSource(cfg);
        boolean configured = local ? sourceScriptReady(cfg) : !url.isBlank();
        List<Map<String, Object>> platforms = new ArrayList<>();
        List<String> chartCodes = new ArrayList<>();
        List<String> playlistCodes = new ArrayList<>();
        List<String> searchCodes = new ArrayList<>();
        List<String> allQualities = new ArrayList<>();
        List<String> allActions = new ArrayList<>();
        boolean runtimeLoaded = false;
        String message = "";
        if (configured) {
            try {
                LxSourceRuntime runtime = loadRuntime(url, false);
                runtimeLoaded = true;
                for (LxSourceRuntime.PlatformInfo info : runtime.platforms().values()) {
                    String code = String.valueOf(info.code() == null ? "" : info.code()).trim().toLowerCase(Locale.ROOT);
                    if (code.isBlank()) continue;
                    List<String> actions = new ArrayList<>(info.actions());
                    // LX 音源没有声明 actions 时按可播放处理；声明了就必须具备 musicUrl。
                    boolean canPlay = actions.isEmpty()
                            || actions.stream().anyMatch(action -> "musicUrl".equalsIgnoreCase(action));
                    if (!canPlay) continue;
                    boolean charts = CHART_PLATFORM_CODES.contains(code);
                    boolean playlists = PLAYLIST_PLATFORM_CODES.contains(code);
                    boolean search = SEARCH_PLATFORM_CODES.contains(code);
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("code", code);
                    item.put("label", displayPlatformLabel(code, info.label()));
                    item.put("name", item.get("label"));
                    item.put("actions", actions);
                    item.put("qualities", new ArrayList<>(info.qualities()));
                    item.put("canPlay", true);
                    item.put("charts", charts);
                    item.put("playlists", playlists);
                    item.put("search", search);
                    platforms.add(item);
                    for (String quality : new ArrayList<>(info.qualities())) {
                        if (!quality.isBlank() && !allQualities.contains(quality)) allQualities.add(quality);
                    }
                    for (String action : actions) {
                        if (!action.isBlank() && !allActions.contains(action)) allActions.add(action);
                    }
                    if (charts) chartCodes.add(code);
                    if (playlists) playlistCodes.add(code);
                    if (search) searchCodes.add(code);
                }
            } catch (RuntimeException e) {
                message = "音源平台信息读取失败：" + shortMessage(e);
                log.warn("读取音源平台能力失败 url={}: {}", url, e.toString());
            }
        }
        if (!runtimeLoaded && configured) {
            // 脚本暂时读不到时，用已保存的平台代码兜底，前端仍不会凭空补平台。
            for (String code : split(cfg.getOrDefault("musicSourcePlatforms", ""))) {
                String normalized = code.trim().toLowerCase(Locale.ROOT);
                if (normalized.isBlank()) continue;
                boolean charts = CHART_PLATFORM_CODES.contains(normalized);
                boolean playlists = PLAYLIST_PLATFORM_CODES.contains(normalized);
                boolean search = SEARCH_PLATFORM_CODES.contains(normalized);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("code", normalized);
                item.put("label", displayPlatformLabel(normalized, ""));
                item.put("name", item.get("label"));
                item.put("actions", List.of("musicUrl"));
                item.put("qualities", split(cfg.getOrDefault("musicSourceQualities", "")));
                item.put("canPlay", true);
                item.put("charts", charts);
                item.put("playlists", playlists);
                item.put("search", search);
                platforms.add(item);
                for (String quality : split(cfg.getOrDefault("musicSourceQualities", ""))) {
                    if (!allQualities.contains(quality)) allQualities.add(quality);
                }
                if (!allActions.contains("musicUrl")) allActions.add("musicUrl");
                if (charts) chartCodes.add(normalized);
                if (playlists) playlistCodes.add(normalized);
                if (search) searchCodes.add(normalized);
            }
        }
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("name", cfg.getOrDefault("musicSourceName", ""));
        source.put("version", cfg.getOrDefault("musicSourceVersion", ""));
        source.put("description", cfg.getOrDefault("musicSourceDescription", ""));
        source.put("author", cfg.getOrDefault("musicSourceAuthor", ""));
        source.put("homepage", cfg.getOrDefault("musicSourceHomepage", ""));
        source.put("initAlert", "");
        if (runtimeLoaded) {
            Map<String, Object> described = describe(loadRuntime(url, false), url);
            source.put("name", described.getOrDefault("name", source.get("name")));
            source.put("version", described.getOrDefault("version", source.get("version")));
            source.put("description", described.getOrDefault("description", source.get("description")));
            source.put("author", described.getOrDefault("author", source.get("author")));
            source.put("homepage", described.getOrDefault("homepage", source.get("homepage")));
            source.put("initAlert", described.getOrDefault("initAlert", ""));
        }
        source.put("platformCount", platforms.size());
        source.put("chartCount", chartCodes.size());
        source.put("playlistCount", playlistCodes.size());
        source.put("searchCount", searchCodes.size());
        source.put("qualities", allQualities);
        source.put("actions", allActions);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("configured", configured);
        data.put("sourceName", source.get("name"));
        data.put("sourceVersion", source.get("version"));
        data.put("source", source);
        data.put("qualities", allQualities);
        data.put("actions", allActions);
        data.put("platforms", platforms);
        data.put("chartPlatforms", chartCodes);
        data.put("playlistPlatforms", playlistCodes);
        data.put("searchPlatforms", searchCodes);
        data.put("message", message);
        return data;
    }

    public Map<String, Object> preview(String rawUrl, boolean refresh) {
        String url = normalizeSourceUrl(rawUrl);
        boolean sameVerified = !refresh && url.equals(currentVerifiedSourceKey());
        RuntimeEntry entry = loadRuntimeEntry(url, refresh);
        Map<String, Object> data = describe(entry.runtime, url);
        data.put("type", "url");
        data.put("localFileName", "");
        if (sameVerified) {
            data.put("tested", true);
            data.put("testMessage", "沿用上次验证结果，已跳过重复测速");
            data.put("verifiedAt", siteConfigService.get().getOrDefault("musicSourceVerifiedAt", ""));
        } else {
            testPlayback(entry.runtime, data);
        }
        persistVerifiedSource(url, "url", "", data, entry.scriptPath, entry.hash);
        data.put("verifiedAt", siteConfigService.get().getOrDefault("musicSourceVerifiedAt", ""));
        return data;
    }

    /** 上传本地 .js 音源脚本：校验、初始化、测试并立即持久化。 */
    public Map<String, Object> previewUpload(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new BizException("请选择音源脚本文件");
        String original = file.getOriginalFilename() == null ? "local-source.js" : file.getOriginalFilename().trim();
        if (original.length() > 200) original = original.substring(0, 200);
        if (!original.toLowerCase(Locale.ROOT).endsWith(".js")) {
            throw new BizException("只支持上传 .js 格式的 LX 音源脚本");
        }
        if (file.getSize() > MAX_SCRIPT_BYTES) throw new BizException("音源脚本超过 2MB 限制");
        String script;
        try {
            script = new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new BizException("读取音源脚本失败");
        }
        if (script.isBlank() || !script.contains("@name") || !script.contains("@version")) {
            throw new BizException("这个文件不像 LX 音源脚本，缺少 @name 或 @version");
        }
        String hash;
        try {
            hash = sha256(script);
        } catch (Exception e) {
            throw new BizException("无法计算音源脚本校验值");
        }
        String fileName = "upload-" + hash.substring(0, 12) + ".js";
        String key = LOCAL_SOURCE_PREFIX + fileName;
        LxSourceRuntime runtime = null;
        try {
            runtime = createRuntimeWithTimeout(script);
            String relativePath = writeScriptCache(fileName, script);
            Map<String, Object> data = describe(runtime, key);
            data.put("type", "file");
            data.put("localFileName", original);
            testPlayback(runtime, data);
            persistVerifiedSource(key, "file", original, data, relativePath, hash);
            registerRuntime(key, hash, runtime, relativePath);
            // 新脚本已落库并成为当前音源，旧的上传文件不再需要保留。
            pruneSourceScripts(fileName);
            data.put("verifiedAt", siteConfigService.get().getOrDefault("musicSourceVerifiedAt", ""));
            return data;
        } catch (RuntimeException e) {
            if (runtime != null) runtime.close();
            deleteScriptCache(fileName);
            throw e;
        } catch (Exception e) {
            if (runtime != null) runtime.close();
            deleteScriptCache(fileName);
            throw new BizException("音源导入失败：" + shortMessage(e));
        }
    }

    /**
     * 按当前音源声明的可搜索平台并行检索，再按平台轮流合并。
     * 单平台失败不影响其他平台；结果携带 platformLabel，前端可标识来源。
     */
    public Map<String, Object> search(String keyword, int limit) {
        String text = String.valueOf(keyword == null ? "" : keyword).trim();
        if (text.isEmpty()) throw new BizException("请输入要搜索的歌曲或歌手");
        if (text.length() > 50) text = text.substring(0, 50);
        int size = Math.max(1, Math.min(MAX_SEARCH_LIMIT, limit));
        String sourceUrl = requireSourceUrl();
        LxSourceRuntime runtime = loadRuntime(sourceUrl, false);
        List<String> codes = searchablePlatformCodes(runtime);
        if (codes.isEmpty()) {
            throw new BizException("当前音源没有可搜索平台");
        }

        String cacheKey = text.toLowerCase(Locale.ROOT) + ":" + size + ":" + String.join(",", codes);
        synchronized (searches) {
            SearchEntry entry = searches.get(cacheKey);
            if (entry != null && System.currentTimeMillis() - entry.createdAt < SEARCH_TTL_MILLIS) {
                return entry.data;
            }
        }

        int perPlatform = Math.max(6, Math.min(size, (size + codes.size() - 1) / codes.size() + 2));
        final String query = text;
        List<CompletableFuture<List<Map<String, Object>>>> tasks = new ArrayList<>();
        for (String code : codes) {
            tasks.add(CompletableFuture.supplyAsync(() -> {
                try {
                    return searchPlatformTracks(code, query, perPlatform);
                } catch (Exception e) {
                    log.warn("音乐平台搜索失败 platform={} keyword={}: {}", code, query, e.toString());
                    return null;
                }
            }));
        }

        Map<String, List<Map<String, Object>>> byPlatform = new LinkedHashMap<>();
        List<String> failedPlatforms = new ArrayList<>();
        for (int i = 0; i < codes.size(); i += 1) {
            String code = codes.get(i);
            try {
                List<Map<String, Object>> tracks = tasks.get(i).get(15, TimeUnit.SECONDS);
                if (tracks == null) {
                    failedPlatforms.add(code);
                    continue;
                }
                List<Map<String, Object>> labeled = new ArrayList<>();
                for (Map<String, Object> track : tracks) {
                    Map<String, Object> copy = new LinkedHashMap<>(track);
                    copy.put("source", code);
                    copy.put("platform", code);
                    copy.put("platformLabel", platformLabel(runtime, code));
                    labeled.add(copy);
                }
                byPlatform.put(code, labeled);
            } catch (Exception e) {
                failedPlatforms.add(code);
                log.warn("音乐平台搜索超时 platform={} keyword={}: {}", code, text, e.toString());
            }
        }
        if (byPlatform.isEmpty()) {
            throw new BizException("搜索暂时不可用，请稍后重试");
        }

        List<Map<String, Object>> merged = new ArrayList<>();
        Map<String, Boolean> seen = new HashMap<>();
        int cursor = 0;
        while (merged.size() < size) {
            boolean progressed = false;
            for (List<Map<String, Object>> tracks : byPlatform.values()) {
                if (cursor >= tracks.size()) continue;
                progressed = true;
                Map<String, Object> track = tracks.get(cursor);
                String dedupeKey = normalizeSongText(String.valueOf(track.get("title")))
                        + "|" + normalizeSongText(String.valueOf(track.get("artist")));
                if (seen.putIfAbsent(dedupeKey, Boolean.TRUE) != null) continue;
                merged.add(track);
                if (merged.size() >= size) break;
            }
            if (!progressed) break;
            cursor += 1;
        }

        List<Map<String, Object>> platformInfo = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> entry : byPlatform.entrySet()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", entry.getKey());
            item.put("label", platformLabel(runtime, entry.getKey()));
            item.put("count", entry.getValue().size());
            platformInfo.add(item);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("keyword", text);
        data.put("total", merged.size());
        data.put("loaded", merged.size());
        data.put("platform", codes.size() > 1 ? "multi" : codes.get(0));
        data.put("platformLabel", codes.size() > 1 ? "多平台" : platformLabel(runtime, codes.get(0)));
        data.put("platforms", platformInfo);
        data.put("failedPlatforms", failedPlatforms);
        data.put("source", describe(runtime, sourceUrl));
        data.put("tracks", merged);
        synchronized (searches) {
            if (searches.size() > 60) searches.clear();
            searches.put(cacheKey, new SearchEntry(System.currentTimeMillis(), data));
        }
        return data;
    }

    private List<String> searchablePlatformCodes(LxSourceRuntime runtime) {
        List<String> codes = new ArrayList<>();
        for (LxSourceRuntime.PlatformInfo info : runtime.platforms().values()) {
            String code = String.valueOf(info.code() == null ? "" : info.code()).trim().toLowerCase(Locale.ROOT);
            if (code.isBlank() || codes.contains(code) || !SEARCH_PLATFORM_CODES.contains(code)) continue;
            List<String> actions = info.actions();
            boolean canPlay = actions.isEmpty()
                    || actions.stream().anyMatch(action -> "musicUrl".equalsIgnoreCase(action));
            if (canPlay) codes.add(code);
        }
        return codes;
    }

    private List<Map<String, Object>> searchNeteaseTracks(String text, int size) throws Exception {
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
        return tracks;
    }

    /**
     * 为播放失败的歌曲寻找其他平台的同名歌曲。只返回歌名完全一致且歌手能对上的候选，
     * 避免自动切源时播成同名但不是同一首歌。
     */
    public Map<String, Object> fallbackCandidates(String title, String artist, String source) {
        String wantedTitle = String.valueOf(title == null ? "" : title).trim();
        String wantedArtist = String.valueOf(artist == null ? "" : artist).trim();
        if (wantedTitle.isBlank()) throw new BizException("缺少歌曲名称");
        if (wantedTitle.length() > 100) wantedTitle = wantedTitle.substring(0, 100);
        if (wantedArtist.length() > 100) wantedArtist = wantedArtist.substring(0, 100);
        String exclude = String.valueOf(source == null ? "" : source).trim().toLowerCase(Locale.ROOT);

        String sourceUrl = requireSourceUrl();
        LxSourceRuntime runtime = loadRuntime(sourceUrl, false);
        String normalizedTitle = baseSongText(wantedTitle);
        if (normalizedTitle.isBlank()) normalizedTitle = normalizeSongText(wantedTitle);
        if (normalizedTitle.isBlank()) throw new BizException("歌曲名称不合法");
        final String searchTitle = normalizedTitle;

        List<String> searched = new ArrayList<>();
        List<CompletableFuture<List<Map<String, Object>>>> futures = new ArrayList<>();
        for (Map.Entry<String, LxSourceRuntime.PlatformInfo> item : runtime.platforms().entrySet()) {
            String code = String.valueOf(item.getKey() == null ? "" : item.getKey()).trim().toLowerCase(Locale.ROOT);
            if (code.isBlank() || code.equals(exclude)) continue;
            List<String> actions = item.getValue().actions();
            boolean canPlay = actions.isEmpty()
                    || actions.stream().anyMatch(action -> "musicUrl".equalsIgnoreCase(action));
            if (!canPlay) continue;
            searched.add(code);
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    return searchPlatformTracks(code, searchTitle, 20);
                } catch (Exception e) {
                    log.debug("跨源匹配搜索失败 platform={}: {}", code, e.toString());
                    return List.of();
                }
            }));
        }

        List<Map<String, Object>> raw = new ArrayList<>();
        for (CompletableFuture<List<Map<String, Object>>> future : futures) {
            try {
                raw.addAll(future.get(8, TimeUnit.SECONDS));
            } catch (Exception e) {
                log.debug("跨源匹配超时或失败: {}", e.toString());
            }
        }

        List<Map<String, Object>> candidates = new ArrayList<>();
        Map<String, Boolean> seen = new LinkedHashMap<>();
        for (Map<String, Object> track : raw) {
            String code = String.valueOf(track.getOrDefault("source", "")).trim().toLowerCase(Locale.ROOT);
            String id = String.valueOf(track.getOrDefault("id", "")).trim();
            String candidateArtist = String.valueOf(track.getOrDefault("artist", "")).trim();
            if (code.isBlank() || id.isBlank()) continue;
            String rawTitle = String.valueOf(track.getOrDefault("title", "")).trim();
            if (!baseSongText(rawTitle).equals(normalizedTitle)) continue;
            if (!artistCompatible(wantedArtist, candidateArtist)) continue;
            String key = code + ":" + id;
            if (seen.putIfAbsent(key, Boolean.TRUE) != null) continue;
            Map<String, Object> candidate = new LinkedHashMap<>(track);
            candidate.remove("matchTitle");
            candidate.put("source", code);
            candidate.put("platform", code);
            candidate.put("platformLabel", platformLabel(runtime, code));
            candidate.put("matchTitle", wantedTitle);
            candidate.put("artistMatch", true);
            candidates.add(candidate);
            if (candidates.size() >= 8) break;
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("title", wantedTitle);
        data.put("artist", wantedArtist);
        data.put("exclude", exclude);
        data.put("searched", searched);
        data.put("candidates", candidates);
        data.put("count", candidates.size());
        data.put("message", candidates.isEmpty()
                ? "没有找到歌名和歌手都一致的其他来源"
                : "已找到歌名一致的其他来源");
        return data;
    }

    private List<Map<String, Object>> searchPlatformTracks(String code, String text, int size) throws Exception {
        return switch (code) {
            case "wy" -> searchNeteaseTracks(text, size);
            case "tx" -> searchQq(text, size);
            case "kg" -> searchKugou(text, size);
            case "kw" -> searchKuwo(text, size);
            case "mg" -> searchMigu(text, size);
            default -> List.of();
        };
    }

    private String normalizeSongText(String value) {
        String text = Normalizer.normalize(String.valueOf(value == null ? "" : value), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .trim();
        return text.replaceAll("[\\p{Punct}\\p{Z}\\s，。！？、；：“”‘’（）《》【】〈〉「」『』…—·]", "");
    }

    /** 去掉歌名末尾或中间的括号补充说明，用于跨平台同曲匹配。 */
    private String baseSongText(String value) {
        String text = Normalizer.normalize(String.valueOf(value == null ? "" : value), Normalizer.Form.NFKC)
                .replaceAll("[（(\\[【][^）)\\]】]*[）)\\]】]", " ");
        return normalizeSongText(text);
    }

    /**
     * 公共聚合接口在未登录/无会员或上游限流时会返回同一个约 7 秒的占位 mp3。
     * 该地址不能当真实歌曲播放，统一丢弃后走跨平台同曲匹配。
     */
    private boolean isPlaceholderAudio(String audio) {
        if (audio == null || audio.isBlank()) return false;
        try {
            URI uri = URI.create(audio);
            String host = uri.getHost();
            String path = uri.getPath();
            return host != null && "cdn-img.gitcode.com".equalsIgnoreCase(host)
                    && path != null && path.contains("d36b953eeef28a05c5086233ca7dbf7af1bfdf9bbbf1d9cc69789f79d619c9bd");
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private boolean artistCompatible(String requested, String candidate) {
        String wanted = normalizeSongText(requested);
        String actual = normalizeSongText(candidate);
        if (wanted.isBlank() || actual.isBlank()) return false;
        if (wanted.contains("未知歌手") || actual.contains("未知歌手")) return false;
        if (wanted.equals(actual)) return true;

        List<String> wantedParts = artistParts(requested);
        List<String> actualParts = artistParts(candidate);
        if (wantedParts.isEmpty() || actualParts.isEmpty()) return false;
        if (wantedParts.size() == 1 && actualParts.size() == 1) {
            String wantedPart = wantedParts.get(0);
            String actualPart = actualParts.get(0);
            return wantedPart.contains(actualPart) || actualPart.contains(wantedPart);
        }
        if (wantedParts.size() != actualParts.size()) return false;

        List<String> remaining = new ArrayList<>(actualParts);
        for (String wantedPart : wantedParts) {
            boolean matched = false;
            for (int i = 0; i < remaining.size(); i++) {
                String actualPart = remaining.get(i);
                if (wantedPart.contains(actualPart) || actualPart.contains(wantedPart)) {
                    remaining.remove(i);
                    matched = true;
                    break;
                }
            }
            if (!matched) return false;
        }
        return remaining.isEmpty();
    }

    private List<String> artistParts(String value) {
        List<String> parts = new ArrayList<>();
        for (String raw : String.valueOf(value == null ? "" : value)
                .split("[/、,，&]|\\bfeat\\.?\\b", -1)) {
            String normalized = normalizeSongText(raw);
            if (!normalized.isBlank()) parts.add(normalized);
        }
        return parts;
    }

    public Map<String, Object> resolveUrl(String platform, String songId, String quality) {
        return resolveUrl(platform, songId, quality, "", "");
    }

    /**
     * 解析歌曲播放地址。原平台失败时，按歌曲名与歌手在当前音源支持的其他平台里
     * 寻找同名可播放版本，尽量做到点击即播，而不是直接报“无法播放”。
     */
    public Map<String, Object> resolveUrl(String platform, String songId, String quality,
                                          String title, String artist) {
        return resolveUrl(platform, songId, quality, title, artist, "", "");
    }

    public Map<String, Object> resolveUrl(String platform, String songId, String quality,
                                          String title, String artist, String album, String duration) {
        String sourceCode = String.valueOf(platform == null ? "" : platform).trim();
        if (sourceCode.isBlank()) sourceCode = "wy";
        String id = String.valueOf(songId == null ? "" : songId).trim();
        if (!id.matches("[0-9A-Za-z_-]{1,80}")) throw new BizException("歌曲 ID 不合法");
        String qualityCode = String.valueOf(quality == null ? "" : quality).trim().toLowerCase(Locale.ROOT);
        if (!QUALITIES.contains(qualityCode)) qualityCode = "320k";

        String sourceUrl = requireSourceUrl();
        LxSourceRuntime runtime = loadRuntime(sourceUrl, false);
        if (!runtime.platforms().containsKey(sourceCode)) throw new BizException("当前音源不支持该平台");
        qualityCode = supportedQuality(runtime, sourceCode, qualityCode);

        String cacheKey = sourceUrl + "|" + sourceCode + "|" + id + "|" + qualityCode;
        UrlEntry cached = resolvedUrls.get(cacheKey);
        if (cached != null && System.currentTimeMillis() - cached.createdAt < URL_TTL_MILLIS) {
            Map<String, Object> hit = new LinkedHashMap<>(cached.data);
            hit.put("cached", true);
            return hit;
        }

        String originalSource = sourceCode;
        String originalId = id;
        String audio = "";
        String message = "";
        boolean fallback = false;
        Map<String, Object> chosen = null;
        RuntimeException resolveError = null;
        try {
            audio = runtime.resolveMusicUrl(sourceCode, id, qualityCode,
                    playbackMeta(title, artist, album, duration));
        } catch (RuntimeException e) {
            resolveError = e;
        }

        if ((audio == null || audio.isBlank()) && "wy".equals(sourceCode)) {
            try {
                audio = resolveNeteaseFallback(id, qualityCode);
                message = "音源未返回地址，已使用网易云公开地址";
            } catch (Exception fallbackError) {
                log.debug("网易云公开地址回退失败 id={}: {}", id, fallbackError.toString());
            }
        }

        boolean placeholderAudio = false;
        if (audio != null && !audio.isBlank() && isPlaceholderAudio(audio)) {
            // 聚合接口在未登录/无会员或限流时会返回固定 7 秒占位音频。
            placeholderAudio = true;
            audio = "";
            message = "音源接口返回占位音频";
            log.warn("音源返回占位音频，尝试其他平台 platform={} id={}", sourceCode, id);
        }

        String wantedTitle = String.valueOf(title == null ? "" : title).trim();
        if ((audio == null || audio.isBlank()) && !wantedTitle.isBlank()) {
            chosen = findPlayableFallback(runtime, wantedTitle,
                    String.valueOf(artist == null ? "" : artist).trim(),
                    originalSource, qualityCode);
            if (chosen != null) {
                audio = String.valueOf(chosen.getOrDefault("url", ""));
                String nextSource = String.valueOf(chosen.getOrDefault("source", originalSource)).trim();
                String nextId = String.valueOf(chosen.getOrDefault("id", originalId)).trim();
                if (!nextSource.isBlank()) sourceCode = nextSource;
                if (!nextId.isBlank()) id = nextId;
                fallback = true;
                message = "原来源播放失败，已自动切换到"
                        + platformLabel(runtime, sourceCode) + "继续播放";
            }
        }

        if (audio == null || audio.isBlank()) {
            if (!wantedTitle.isBlank()) {
                if (placeholderAudio && "tx".equals(originalSource)) {
                    throw new BizException("QQ 音乐接口只返回占位音频，且没有找到其他平台的同曲版本，换一首试试");
                }
                throw new BizException(placeholderAudio
                        ? "音源接口只返回占位音频，且没有找到其他平台的同曲版本，换一首试试"
                        : "该歌曲在当前可用平台都没有播放地址，换一首试试");
            }
            String reason = resolveError == null ? "" : shortMessage(resolveError);
            if (placeholderAudio) {
                throw new BizException("tx".equals(originalSource)
                        ? "QQ 音乐接口只返回占位音频，需要登录或会员授权"
                        : "音源接口只返回占位音频，需要登录或会员授权");
            }
            throw new BizException(reason.isBlank()
                    ? "该歌曲暂时无法播放，可能受版权限制，换一首试试"
                    : "该歌曲暂时无法播放（" + reason + "），换一首试试");
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("url", playableUrl(audio));
        data.put("source", sourceCode);
        data.put("platform", sourceCode);
        data.put("id", id);
        data.put("quality", qualityCode);
        data.put("qualityLabel", QUALITY_LABELS.getOrDefault(qualityCode, qualityCode));
        data.put("message", message);
        data.put("fallback", fallback);
        data.put("originalSource", originalSource);
        data.put("originalId", originalId);
        data.put("platformLabel", platformLabel(runtime, sourceCode));
        if (chosen != null) {
            for (String key : List.of("title", "artist", "album", "cover", "duration", "fee", "pageUrl")) {
                if (chosen.containsKey(key)) data.put(key, chosen.get(key));
            }
        }
        if (resolvedUrls.size() > 300) resolvedUrls.clear();
        resolvedUrls.put(cacheKey, new UrlEntry(System.currentTimeMillis(), new LinkedHashMap<>(data)));
        return data;
    }

    /** 给音源脚本补充 LX Music 常见的 musicInfo 字段，避免只靠歌曲 ID 无法拼出播放地址。 */
    private Map<String, String> playbackMeta(Object title, Object artist, Object album, Object duration) {
        Map<String, String> meta = new LinkedHashMap<>();
        String name = String.valueOf(title == null ? "" : title).trim();
        String singer = String.valueOf(artist == null ? "" : artist).trim();
        String albumName = String.valueOf(album == null ? "" : album).trim();
        String durationValue = String.valueOf(duration == null ? "" : duration).trim();
        if (!name.isBlank()) {
            meta.put("name", name);
            meta.put("title", name);
        }
        if (!singer.isBlank()) {
            meta.put("singer", singer);
            meta.put("artist", singer);
        }
        if (!albumName.isBlank()) meta.put("albumName", albumName);
        if (!durationValue.isBlank() && !"null".equals(durationValue)) meta.put("duration", durationValue);
        return meta;
    }

    /** 音源平台不一定支持请求的音质；按平台声明的音质选择最接近的可用档位，避免无谓重试。 */
    private String supportedQuality(LxSourceRuntime runtime, String code, String requested) {
        LxSourceRuntime.PlatformInfo info = runtime.platforms().get(code);
        List<String> supported = info == null ? List.of() : info.qualities();
        if (supported.isEmpty() || supported.contains(requested)) return requested;
        if (supported.contains("320k") && "flac".equals(requested)) return "320k";
        if (supported.contains("128k")) return "128k";
        return supported.get(0);
    }

    /** 在音源声明的平台中查找同歌名、同歌手且能解析出地址的版本。 */
    private Map<String, Object> findPlayableFallback(LxSourceRuntime runtime, String title, String artist,
                                                     String excludeSource, String quality) {
        List<Map<String, Object>> candidates;
        try {
            Map<String, Object> found = fallbackCandidates(title, artist, excludeSource);
            Object list = found.get("candidates");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> parsed = list instanceof List<?>
                    ? (List<Map<String, Object>>) list : List.of();
            candidates = parsed;
        } catch (Exception e) {
            log.debug("自动匹配可播放来源失败 title={}: {}", title, e.toString());
            return null;
        }
        int attempt = 0;
        for (Map<String, Object> candidate : candidates) {
            if (attempt >= 5) break;
            String code = String.valueOf(candidate.getOrDefault("source", "")).trim().toLowerCase(Locale.ROOT);
            String candidateId = String.valueOf(candidate.getOrDefault("id", "")).trim();
            if (code.isBlank() || candidateId.isBlank()) continue;
            if (!runtime.platforms().containsKey(code)) continue;
            if (code.equals(excludeSource)) continue;
            attempt += 1;
            String preferred = supportedQuality(runtime, code, quality);
            List<String> candidateQualities = new ArrayList<>();
            candidateQualities.add(preferred);
            if (!"128k".equals(preferred)) candidateQualities.add("128k");
            for (String candidateQuality : candidateQualities) {
                try {
                    String url = runtime.resolveMusicUrl(code, candidateId, candidateQuality,
                            playbackMeta(candidate.get("title"), candidate.get("artist"),
                                    candidate.get("album"), candidate.get("duration")));
                    if (url == null || url.isBlank()) continue;
                    Map<String, Object> item = new LinkedHashMap<>(candidate);
                    item.put("url", url);
                    item.put("quality", candidateQuality);
                    return item;
                } catch (Exception e) {
                    log.debug("同歌名来源解析失败 source={} id={}: {}", code, candidateId, e.toString());
                }
            }
        }
        return null;
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
        String platformLabel = platformLabel(runtime, code);
        if (!runtime.platforms().containsKey(code)) {
            throw new BizException(platformLabel + "未包含在当前导入的音源中");
        }

        List<Map<String, Object>> tracks;
        boolean fallback = false;
        String chartTitle;
        try {
            switch (code) {
                case "tx" -> {
                    tracks = chartQq(size);
                    chartTitle = platformLabel + "热歌榜";
                }
                case "kg" -> {
                    tracks = chartKugou(size);
                    chartTitle = platformLabel + " TOP500";
                }
                case "kw" -> {
                    tracks = chartKuwo(size);
                    chartTitle = platformLabel + "热歌";
                    fallback = true;
                }
                case "mg" -> {
                    tracks = chartMigu(size);
                    chartTitle = platformLabel + "热歌";
                    fallback = true;
                }
                default -> {
                    tracks = chartNetease(size);
                    chartTitle = platformLabel + "热歌榜";
                }
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("获取平台热歌榜失败 platform={}: {}", code, e.toString());
            throw new BizException("暂时无法获取" + platformLabel + "榜单，请稍后重试");
        }
        if (tracks.isEmpty()) {
            throw new BizException(platformLabel + "暂时没有返回可用的热歌数据");
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("platform", code);
        data.put("platformLabel", platformLabel);
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

    /**
     * 当前音源各平台推荐歌单。只读取当前音源声明的平台，并把各平台结果交错排列，
     * 避免前台又变成按平台分组的列表。
     */
    public Map<String, Object> playlists(int limit, String platform) {
        int total = Math.max(1, Math.min(MAX_PLAYLIST_TOTAL, limit));
        String sourceUrl = requireSourceUrl();
        LxSourceRuntime runtime = loadRuntime(sourceUrl, false);
        String requested = String.valueOf(platform == null ? "" : platform).trim().toLowerCase(Locale.ROOT);
        List<String> codes = new ArrayList<>();
        if (!requested.isBlank()) {
            if (!PLAYLIST_PLATFORM_CODES.contains(requested)) throw new BizException("不支持的平台");
            if (!runtime.platforms().containsKey(requested)) {
                throw new BizException(displayPlatformLabel(requested, "") + "未包含在当前导入的音源中");
            }
            codes.add(requested);
        } else {
            for (String code : runtime.platforms().keySet()) {
                String normalized = String.valueOf(code == null ? "" : code).trim().toLowerCase(Locale.ROOT);
                if (PLAYLIST_PLATFORM_CODES.contains(normalized) && !codes.contains(normalized)) {
                    codes.add(normalized);
                }
            }
        }
        if (codes.isEmpty()) throw new BizException("当前音源没有可读取歌单的平台");
        int perPlatform = codes.size() == 1 ? total
                : Math.max(1, Math.min(8, (total + codes.size() - 1) / codes.size()));
        String cacheKey = sourceUrl + "|" + String.join(",", codes) + ":" + perPlatform;
        synchronized (playlistLock) {
            ChartEntry entry = playlistLists.get(cacheKey);
            if (entry != null && System.currentTimeMillis() - entry.createdAt < PLAYLIST_TTL_MILLIS) {
                return entry.data;
            }
        }

        Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        List<CompletableFuture<List<Map<String, Object>>>> futures = new ArrayList<>();
        for (String code : codes) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    return fetchPlaylists(code, perPlatform);
                } catch (Exception e) {
                    log.debug("读取平台歌单失败 platform={}: {}", code, e.toString());
                    return List.of();
                }
            }));
        }
        int index = 0;
        for (String code : codes) {
            List<Map<String, Object>> list = List.of();
            try {
                list = futures.get(index).get(18, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.debug("读取平台歌单超时 platform={}: {}", code, e.toString());
            }
            grouped.put(code, list == null ? List.of() : list);
            index += 1;
        }

        List<Map<String, Object>> items = new ArrayList<>();
        int maxSize = grouped.values().stream().mapToInt(List::size).max().orElse(0);
        for (int i = 0; i < maxSize && items.size() < total; i++) {
            for (String code : codes) {
                List<Map<String, Object>> list = grouped.getOrDefault(code, List.of());
                if (i >= list.size()) continue;
                items.add(list.get(i));
                if (items.size() >= total) break;
            }
        }
        if (items.isEmpty()) throw new BizException("暂时无法读取平台歌单，请稍后重试");

        List<String> labels = new ArrayList<>();
        for (String code : codes) labels.add(platformLabel(runtime, code));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("platforms", codes);
        data.put("platformLabels", labels);
        data.put("updatedAt", OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        data.put("total", items.size());
        data.put("items", items);
        data.put("message", "已读取 " + labels.size() + " 个平台的推荐歌单");
        synchronized (playlistLock) {
            if (playlistLists.size() > 20) playlistLists.clear();
            playlistLists.put(cacheKey, new ChartEntry(System.currentTimeMillis(), data));
        }
        return data;
    }

    private List<Map<String, Object>> fetchPlaylists(String code, int size) throws Exception {
        return switch (code) {
            case "wy" -> playlistsNetease(size);
            case "tx" -> playlistsQq(size);
            case "kg" -> playlistsKugou(size);
            case "kw" -> playlistsKuwo(size);
            case "mg" -> playlistsMigu(size);
            default -> List.of();
        };
    }

    private List<Map<String, Object>> playlistsNetease(int size) throws Exception {
        JsonNode root = getJson("https://music.163.com/api/personalized/playlist?limit="
                + Math.max(1, Math.min(20, size)));
        List<Map<String, Object>> items = new ArrayList<>();
        for (JsonNode item : root.path("result")) {
            if (items.size() >= size) break;
            String id = item.path("id").asText("").trim();
            if (id.isBlank()) continue;
            items.add(playlistItem("wy", id,
                    item.path("name").asText("未命名歌单"),
                    item.path("picUrl").asText(""),
                    item.path("playCount").asLong(0),
                    item.path("trackCount").asInt(0),
                    "网易云音乐",
                    item.path("copywriter").asText(""),
                    "https://music.163.com/#/playlist?id=" + id));
        }
        return items;
    }

    private List<Map<String, Object>> playlistsQq(int size) throws Exception {
        String url = "https://c.y.qq.com/splcloud/fcgi-bin/fcg_get_diss_by_tag.fcg?picmid=1&rnd=0.2"
                + "&g_tk=5381&loginUin=0&hostUin=0&format=json&inCharset=utf8&outCharset=utf-8"
                + "&notice=0&platform=yqq.json&needNewCode=0&categoryId=10000000&sortId=5"
                + "&sin=0&ein=" + Math.max(1, Math.min(20, size));
        JsonNode root = getJsonRaw(url, "https://y.qq.com/");
        List<Map<String, Object>> items = new ArrayList<>();
        for (JsonNode item : root.path("data").path("list")) {
            if (items.size() >= size) break;
            String id = item.path("dissid").asText("").trim();
            if (id.isBlank()) continue;
            items.add(playlistItem("tx", id,
                    item.path("dissname").asText("未命名歌单"),
                    item.path("imgurl").asText(""),
                    item.path("listennum").asLong(0),
                    0,
                    item.path("creator").path("name").asText("QQ 音乐"),
                    item.path("introduction").asText(""),
                    "https://y.qq.com/n/ryqq/playlist/" + id));
        }
        return items;
    }

    private List<Map<String, Object>> playlistsKugou(int size) throws Exception {
        JsonNode root = getJsonRaw("https://m.kugou.com/plist/index&json=true", "https://www.kugou.com/");
        List<Map<String, Object>> items = new ArrayList<>();
        for (JsonNode item : root.path("plist").path("list").path("info")) {
            if (items.size() >= size) break;
            String id = item.path("specialid").asText("").trim();
            if (id.isBlank()) continue;
            String cover = item.path("imgurl").asText("").replace("{size}", "400");
            items.add(playlistItem("kg", id,
                    item.path("specialname").asText("未命名歌单"),
                    cover,
                    item.path("playcount").asLong(0),
                    item.path("songcount").asInt(0),
                    "酷狗音乐",
                    "",
                    "https://www.kugou.com/yy/special/single/" + id + ".html"));
        }
        return items;
    }

    private List<Map<String, Object>> playlistsKuwo(int size) throws Exception {
        String url = "https://wapi.kuwo.cn/api/www/classify/playlist/getRcmPlayList?pn=1&rn="
                + Math.max(1, Math.min(20, size)) + "&httpsStatus=1";
        JsonNode root = getJsonRaw(url, "https://www.kuwo.cn/");
        List<Map<String, Object>> items = new ArrayList<>();
        for (JsonNode item : root.path("data").path("data")) {
            if (items.size() >= size) break;
            String id = item.path("id").asText("").trim();
            if (id.isBlank()) continue;
            String cover = item.path("img500").asText("");
            if (cover.isBlank()) cover = item.path("img").asText("");
            items.add(playlistItem("kw", id,
                    item.path("name").asText("未命名歌单"),
                    cover,
                    item.path("listencnt").asLong(0),
                    item.path("total").asInt(0),
                    item.path("uname").asText("酷我音乐"),
                    "",
                    "https://www.kuwo.cn/playlist_detail/" + id));
        }
        return items;
    }

    private List<Map<String, Object>> playlistsMigu(int size) throws Exception {
        JsonNode root = getJsonRaw("https://app.c.nf.migu.cn/pc/bmw/page-data/playlist-square-recommend/v1.0?templateVersion=2&pageNo=1",
                "https://music.migu.cn/");
        List<Map<String, Object>> items = new ArrayList<>();
        collectMiguPlaylists(root.path("data"), items, new LinkedHashMap<>(), size);
        return items;
    }

    private void collectMiguPlaylists(JsonNode node, List<Map<String, Object>> items,
                                      Map<String, Boolean> seen, int limit) {
        if (node == null || items.size() >= limit) return;
        if (node.isArray()) {
            for (JsonNode child : node) {
                if (items.size() >= limit) return;
                collectMiguPlaylists(child, items, seen, limit);
            }
            return;
        }
        if (!node.isObject()) return;

        String resType = node.path("resType").asText("");
        String action = node.path("action").asText("");
        boolean playlistNode = "2021".equals(resType) || action.startsWith("mgmusic://song-list-info");
        if (playlistNode) {
            String id = node.path("resId").asText("").trim();
            if (id.isBlank()) {
                java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?:id=)(\\d+)").matcher(action);
                if (matcher.find()) id = matcher.group(1);
            }
            String title = node.path("txt").asText("").trim();
            if (title.isBlank()) title = node.path("logEvent").path("contentName").asText("").trim();
            String cover = node.path("img").asText("");
            if (cover.isBlank()) cover = node.path("img2").asText("");
            if (!id.isBlank() && !title.isBlank() && seen.putIfAbsent(id, Boolean.TRUE) == null) {
                String pageUrl = "https://music.migu.cn/v5/#/playlist?playlistId=" + id;
                items.add(playlistItem("mg", id, title, cover, 0, 0, "咪咕音乐", "", pageUrl));
            }
            return;
        }
        if (node.isObject()) {
            java.util.Iterator<JsonNode> fields = node.elements();
            while (fields.hasNext()) {
                if (items.size() >= limit) return;
                collectMiguPlaylists(fields.next(), items, seen, limit);
            }
        }
    }

    private Map<String, Object> playlistItem(String source, String id, String title, String cover,
                                             long playCount, int trackCount, String creator,
                                             String description, String pageUrl) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", id);
        item.put("platform", source);
        item.put("source", source);
        item.put("title", title == null || title.isBlank() ? "未命名歌单" : title.trim());
        item.put("cover", secureUrl(cover));
        item.put("playCount", Math.max(0, playCount));
        item.put("trackCount", Math.max(0, trackCount));
        item.put("creator", creator == null ? "" : creator.trim());
        item.put("description", description == null ? "" : description.trim());
        item.put("pageUrl", pageUrl == null ? "" : pageUrl);
        return item;
    }

    /** 当前音源下某个平台的歌单详情。 */
    public Map<String, Object> playlistDetail(String platform, String playlistId) {
        String code = String.valueOf(platform == null ? "" : platform).trim().toLowerCase(Locale.ROOT);
        String id = String.valueOf(playlistId == null ? "" : playlistId).trim();
        if (!PLAYLIST_PLATFORM_CODES.contains(code)) throw new BizException("不支持的平台");
        if (!id.matches("[0-9A-Za-z_-]{1,80}")) throw new BizException("歌单 ID 不合法");
        String sourceUrl = requireSourceUrl();
        LxSourceRuntime runtime = loadRuntime(sourceUrl, false);
        String platformLabel = platformLabel(runtime, code);
        if (!runtime.platforms().containsKey(code)) throw new BizException(platformLabel + "未包含在当前导入的音源中");

        String cacheKey = sourceUrl + "|" + code + ":" + id;
        synchronized (playlistLock) {
            ChartEntry entry = playlistDetails.get(cacheKey);
            if (entry != null && System.currentTimeMillis() - entry.createdAt < PLAYLIST_TTL_MILLIS) {
                return entry.data;
            }
        }

        Map<String, Object> detail;
        try {
            detail = switch (code) {
                case "wy" -> playlistDetailNetease(id);
                case "tx" -> playlistDetailQq(id);
                case "kg" -> playlistDetailKugou(id);
                case "kw" -> playlistDetailKuwo(id);
                default -> playlistDetailMigu(id);
            };
        } catch (Exception e) {
            log.warn("读取歌单详情失败 platform={} id={}: {}", code, id, e.toString());
            throw new BizException("暂时无法读取" + platformLabel + "歌单，请稍后重试");
        }
        detail.put("platform", code);
        detail.put("source", code);
        detail.put("platformLabel", platformLabel);
        detail.put("updatedAt", OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        if (!detail.containsKey("trackCount")) detail.put("trackCount", 0);
        if (!detail.containsKey("tracks")) detail.put("tracks", List.of());
        List<Map<String, Object>> tracks = (List<Map<String, Object>>) detail.get("tracks");
        detail.put("total", tracks.size());
        synchronized (playlistLock) {
            if (playlistDetails.size() > 80) playlistDetails.clear();
            playlistDetails.put(cacheKey, new ChartEntry(System.currentTimeMillis(), detail));
        }
        return detail;
    }

    private Map<String, Object> playlistDetailNetease(String id) throws Exception {
        JsonNode root;
        try {
            root = getJson("https://music.163.com/api/v6/playlist/detail?id=" + id + "&n=1000&s=0");
        } catch (Exception e) {
            // 网易接口偶尔返回 -462 限流；旧详情接口仍可读，作为一次退避。
            root = getJsonRaw("https://music.163.com/api/playlist/detail?id=" + id, "https://music.163.com/");
        }
        JsonNode playlist = root.path("result").isObject() ? root.path("result") : root.path("playlist");
        List<Map<String, Object>> tracks = new ArrayList<>();
        // 歌单详情默认只带前 10 首；用 trackIds 批量补全，按接口返回的完整列表读取。
        List<Long> trackIds = new ArrayList<>();
        for (JsonNode item : playlist.path("trackIds")) {
            if (trackIds.size() >= MAX_PLAYLIST_TRACKS) break;
            if (item.path("id").canConvertToLong()) trackIds.add(item.path("id").asLong());
        }
        Map<Long, JsonNode> details = trackIds.isEmpty() ? Map.of() : fetchSongDetails(trackIds);
        Map<Long, JsonNode> playlistTracks = new LinkedHashMap<>();
        for (JsonNode song : playlist.path("tracks")) {
            if (song.path("id").canConvertToLong()) playlistTracks.put(song.path("id").asLong(), song);
        }
        if (trackIds.isEmpty()) trackIds.addAll(playlistTracks.keySet().stream().limit(MAX_PLAYLIST_TRACKS).toList());
        for (Long trackId : trackIds) {
            JsonNode song = details.get(trackId);
            if (song == null) song = playlistTracks.get(trackId);
            if (song == null) continue;
            JsonNode artists = song.path("ar").isArray() ? song.path("ar") : song.path("artists");
            JsonNode album = song.path("al").isObject() ? song.path("al") : song.path("album");
            long durationMs = song.path("dt").asLong(song.path("duration").asLong(0));
            tracks.add(chartTrack("wy", String.valueOf(trackId),
                    song.path("name").asText("未命名歌曲"),
                    joinArtists(artists),
                    album.path("name").asText(""),
                    album.path("picUrl").asText(""),
                    durationMs / 1000,
                    song.path("fee").asInt(0),
                    "https://music.163.com/#/song?id=" + trackId));
        }
        Map<String, Object> detail = playlistDetailData("wy", id,
                playlist.path("name").asText("未命名歌单"),
                playlist.path("coverImgUrl").asText(""),
                playlist.path("creator").path("nickname").asText("网易云音乐"),
                playlist.path("description").asText(""),
                playlist.path("trackCount").asInt(tracks.size()),
                playlist.path("playCount").asLong(0),
                "https://music.163.com/#/playlist?id=" + id,
                tracks);
        return detail;
    }

    private Map<String, Object> playlistDetailQq(String id) throws Exception {
        JsonNode dir = null;
        List<Map<String, Object>> tracks = new ArrayList<>();
        // QQ 接口单页最多返回 30 首，按 song_begin 继续翻页，直到歌单读取完。
        for (int begin = 0; begin < MAX_PLAYLIST_TRACKS; begin += 30) {
            ObjectNode payload = objectMapper.createObjectNode();
            ObjectNode req = payload.putObject("req_0");
            req.put("module", "music.srfDissInfo.aiDissInfo");
            req.put("method", "uniform_get_Dissinfo");
            ObjectNode param = req.putObject("param");
            param.put("disstid", Long.parseLong(id));
            param.put("enc_host_uin", "");
            param.put("tag", 1);
            param.put("userinfo", 1);
            param.put("song_begin", begin);
            param.put("song_num", 30);
            String url = "https://u.y.qq.com/cgi-bin/musicu.fcg?format=json&data="
                    + URLEncoder.encode(objectMapper.writeValueAsString(payload), StandardCharsets.UTF_8);
            JsonNode root = getJsonRaw(url, "https://y.qq.com/");
            JsonNode data = root.path("req_0").path("data");
            if (!data.path("dirinfo").isObject()) throw new IOException("QQ 歌单不存在");
            if (dir == null) dir = data.path("dirinfo");
            JsonNode songList = data.path("songlist");
            if (!songList.isArray() || songList.isEmpty()) break;
            for (JsonNode song : songList) {
                if (tracks.size() >= MAX_PLAYLIST_TRACKS) break;
                String songId = song.path("mid").asText("").trim();
                if (songId.isBlank()) continue;
                String albumMid = song.path("album").path("mid").asText("");
                String cover = albumMid.isBlank() ? "" : "https://y.gtimg.cn/music/photo_new/T002R300x300M000" + albumMid + ".jpg";
                int fee = song.path("pay").isObject() ? song.path("pay").path("payplay").asInt(0) : 0;
                tracks.add(chartTrack("tx", songId,
                        song.path("name").asText("未命名歌曲"),
                        joinArtists(song.path("singer")),
                        song.path("album").path("name").asText(""),
                        cover,
                        song.path("interval").asLong(0),
                        fee,
                        "https://y.qq.com/n/ryqq/songDetail/" + songId));
            }
            int expected = dir == null ? 0 : dir.path("songnum").asInt(0);
            if (expected > 0 && tracks.size() >= expected) break;
            if (expected == 0 && songList.size() < 30) break;
        }
        if (dir == null) throw new IOException("QQ 歌单不存在");
        return playlistDetailData("tx", id,
                dir.path("title").asText("未命名歌单"),
                dir.path("picurl").asText(""),
                dir.path("creator").path("nick").asText(dir.path("host_nick").asText("QQ 音乐")),
                dir.path("desc").asText(""),
                dir.path("songnum").asInt(tracks.size()),
                dir.path("listennum").asLong(0),
                "https://y.qq.com/n/ryqq/playlist/" + id,
                tracks);
    }

    private Map<String, Object> playlistDetailKugou(String id) throws Exception {
        List<Map<String, Object>> metaItems;
        try {
            metaItems = playlistsKugou(20);
        } catch (Exception e) {
            metaItems = List.of();
        }
        Map<String, Object> meta = findPlaylist(metaItems, id);
        List<Map<String, Object>> tracks = new ArrayList<>();
        int page = 1;
        while (tracks.size() < MAX_PLAYLIST_TRACKS) {
            String url = "https://mobiles.kugou.com/api/v3/special/song?specialid=" + id
                    + "&page=" + page + "&pagesize=" + PLAYLIST_PAGE_SIZE + "&plat=0&version=8000";
            JsonNode root = getJsonRaw(url, "https://www.kugou.com/");
            JsonNode info = root.path("data").path("info");
            if (!info.isArray() || info.isEmpty()) break;
            int added = 0;
            for (JsonNode song : info) {
                if (tracks.size() >= MAX_PLAYLIST_TRACKS) break;
                String hash = song.path("hash").asText("").trim();
                if (hash.isBlank()) continue;
                String filename = song.path("filename").asText("").trim();
                String title = song.path("remark").asText("").trim();
                String artist = "未知歌手";
                int dash = filename.indexOf(" - ");
                if (dash > 0) {
                    artist = filename.substring(0, dash).trim();
                    if (title.isBlank()) title = filename.substring(dash + 3).trim();
                } else if (title.isBlank()) {
                    title = filename;
                }
                if (title.isBlank()) title = "未命名歌曲";
                int fee = song.path("pay_type").asInt(0) > 0 ? 1 : 0;
                tracks.add(chartTrack("kg", hash, title, artist, "", "",
                        song.path("duration").asLong(0), fee,
                        "https://www.kugou.com/song/#hash=" + hash));
                added += 1;
            }
            int expected = meta == null ? 0 : ((Number) meta.getOrDefault("trackCount", 0)).intValue();
            if (added == 0) break;
            if (expected > 0 && tracks.size() >= expected) break;
            if (expected == 0 && info.size() < PLAYLIST_PAGE_SIZE) break;
            page += 1;
        }
        String pageUrl = "https://www.kugou.com/yy/special/single/" + id + ".html";
        return playlistDetailData("kg", id,
                meta == null ? "酷狗歌单" : String.valueOf(meta.getOrDefault("title", "酷狗歌单")),
                meta == null ? "" : String.valueOf(meta.getOrDefault("cover", "")),
                "酷狗音乐",
                "",
                meta == null ? tracks.size() : ((Number) meta.getOrDefault("trackCount", tracks.size())).intValue(),
                meta == null ? 0L : ((Number) meta.getOrDefault("playCount", 0L)).longValue(),
                pageUrl, tracks);
    }

    private Map<String, Object> playlistDetailKuwo(String id) throws Exception {
        JsonNode data = null;
        List<Map<String, Object>> tracks = new ArrayList<>();
        int page = 1;
        int total = 0;
        while (tracks.size() < MAX_PLAYLIST_TRACKS) {
            String url = "https://wapi.kuwo.cn/api/www/playlist/playListInfo?pid=" + id + "&pn=" + page
                    + "&rn=" + PLAYLIST_PAGE_SIZE + "&httpsStatus=1";
            JsonNode root = getJsonRaw(url, "https://www.kuwo.cn/");
            JsonNode current = root.path("data");
            if (data == null) data = current;
            if (current.isMissingNode() || current.isNull()) break;
            total = Math.max(total, current.path("total").asInt(0));
            JsonNode musicList = current.path("musicList");
            if (!musicList.isArray() || musicList.isEmpty()) break;
            int added = 0;
            for (JsonNode song : musicList) {
                if (tracks.size() >= MAX_PLAYLIST_TRACKS) break;
                String rawId = song.path("musicrid").asText("").trim();
                if (rawId.isBlank()) continue;
                String songId = rawId.replaceFirst("^MUSIC_", "");
                String cover = song.path("albumpic").asText("");
                tracks.add(chartTrack("kw", songId,
                        cleanKuwoText(song.path("name").asText("未命名歌曲")),
                        cleanKuwoText(song.path("artist").asText("未知歌手")),
                        cleanKuwoText(song.path("album").asText("")),
                        cover,
                        song.path("duration").asLong(0),
                        song.path("isListenFee").asInt(0) > 0 ? 1 : 0,
                        "https://www.kuwo.cn/play_detail/" + songId));
                added += 1;
            }
            if (added == 0) break;
            if (total > 0 && tracks.size() >= total) break;
            if (total == 0 && musicList.size() < PLAYLIST_PAGE_SIZE) break;
            page += 1;
        }
        if (data == null) throw new IOException("酷我歌单不存在");
        String cover = data.path("img500").asText("");
        if (cover.isBlank()) cover = data.path("img").asText("");
        return playlistDetailData("kw", id,
                data.path("name").asText("未命名歌单"),
                cover,
                data.path("userName").asText(data.path("uname").asText("酷我音乐")),
                data.path("tag").asText(""),
                total > 0 ? total : tracks.size(),
                data.path("listencnt").asLong(0),
                "https://www.kuwo.cn/playlist_detail/" + id,
                tracks);
    }

    private Map<String, Object> playlistDetailMigu(String id) throws Exception {
        JsonNode metaRoot = getJsonRaw("https://c.musicapp.migu.cn/MIGUM3.0/resource/playlist/v2.0?playlistId=" + id,
                "https://music.migu.cn/");
        JsonNode meta = metaRoot.path("data");
        int total = meta.path("musicNum").asInt(0);
        // 咪咕接口每页最多接受 50 首；pageSize 传更大时第二页会返回空。
        int pageSize = 50;
        List<Map<String, Object>> tracks = new ArrayList<>();
        int page = 1;
        while (tracks.size() < MAX_PLAYLIST_TRACKS) {
            JsonNode songsRoot = getJsonRaw("https://app.c.nf.migu.cn/MIGUM3.0/resource/playlist/song/v2.0?pageNo="
                    + page + "&pageSize=" + pageSize + "&playlistId=" + id, "https://music.migu.cn/");
            JsonNode songList = songsRoot.path("data").path("songList");
            if (!songList.isArray() || songList.isEmpty()) break;
            int added = 0;
            for (JsonNode song : songList) {
                if (tracks.size() >= MAX_PLAYLIST_TRACKS) break;
                String songId = song.path("copyrightId").asText("").trim();
                if (songId.isBlank()) songId = song.path("contentId").asText("").trim();
                if (songId.isBlank()) continue;
                String cover = song.path("img1").asText("");
                if (cover.startsWith("/")) cover = "https://d.musicapp.migu.cn" + cover;
                int fee = 0;
                for (JsonNode tag : song.path("showTags")) {
                    String value = tag.asText("");
                    if (value.contains("vip")) { fee = 1; break; }
                }
                tracks.add(chartTrack("mg", songId,
                        song.path("songName").asText("未命名歌曲"),
                        joinArtists(song.path("singerList")),
                        song.path("album").asText(""),
                        cover,
                        song.path("duration").asLong(0),
                        fee,
                        "https://music.migu.cn/v3/music/song/" + songId));
                added += 1;
            }
            if (added == 0) break;
            if (total > 0 && tracks.size() >= total) break;
            if (total == 0 && songList.size() < pageSize) break;
            page += 1;
        }
        String cover = meta.path("imgItem").path("img").asText("");
        if (cover.isBlank()) cover = meta.path("originalImgUrl").asText("");
        return playlistDetailData("mg", id,
                meta.path("title").asText("未命名歌单"),
                cover,
                meta.path("ownerName").asText("咪咕音乐"),
                meta.path("summary").asText(""),
                total > 0 ? total : tracks.size(),
                meta.path("opNumItem").path("playNum").asLong(0),
                "https://music.migu.cn/v5/#/playlist?playlistId=" + id,
                tracks);
    }

    private Map<String, Object> playlistDetailData(String platform, String id, String title, String cover,
                                                    String creator, String description, int trackCount,
                                                    long playCount, String pageUrl,
                                                    List<Map<String, Object>> tracks) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("id", id);
        detail.put("title", title == null || title.isBlank() ? "未命名歌单" : title.trim());
        detail.put("cover", secureUrl(cover));
        detail.put("creator", creator == null ? "" : creator.trim());
        detail.put("description", description == null ? "" : description.trim());
        detail.put("trackCount", Math.max(trackCount, tracks.size()));
        detail.put("playCount", Math.max(0, playCount));
        detail.put("pageUrl", pageUrl);
        detail.put("tracks", tracks);
        return detail;
    }

    private Map<String, Object> findPlaylist(List<Map<String, Object>> items, String id) {
        for (Map<String, Object> item : items) {
            if (String.valueOf(item.get("id")).equals(id)) return item;
        }
        return null;
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
        return searchKuwo(CHART_FALLBACK_QUERY, size);
    }

    private List<Map<String, Object>> searchKuwo(String text, int size) throws Exception {
        String url = "https://search.kuwo.cn/r.s?client=kt&all="
                + URLEncoder.encode(String.valueOf(text == null ? "" : text), StandardCharsets.UTF_8)
                + "&pn=0&rn=" + Math.max(1, Math.min(MAX_SEARCH_LIMIT, size))
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
        return searchMigu(CHART_FALLBACK_QUERY, size);
    }

    private List<Map<String, Object>> searchMigu(String text, int size) throws Exception {
        String url = "https://app.c.nf.migu.cn/MIGUM2.0/v1.0/content/search_all.do"
                + "?text=" + URLEncoder.encode(String.valueOf(text == null ? "" : text), StandardCharsets.UTF_8)
                + "&pageNo=1&pageSize=30&searchSwitch="
                + URLEncoder.encode("{\"song\":1}", StandardCharsets.UTF_8);
        JsonNode root = getJsonRaw(url, "https://music.migu.cn/");
        List<Map<String, Object>> tracks = new ArrayList<>();
        for (JsonNode song : root.path("songResultData").path("result")) {
            if (tracks.size() >= Math.max(1, Math.min(MAX_SEARCH_LIMIT, size))) break;
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

    private List<Map<String, Object>> searchQq(String text, int size) throws Exception {
        String url = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp?w="
                + URLEncoder.encode(String.valueOf(text == null ? "" : text), StandardCharsets.UTF_8)
                + "&format=json&n=" + Math.max(1, Math.min(MAX_SEARCH_LIMIT, size)) + "&p=1&platform=yqq.json";
        JsonNode root = getJsonRaw(url, "https://y.qq.com/");
        List<Map<String, Object>> tracks = new ArrayList<>();
        for (JsonNode song : root.path("data").path("song").path("list")) {
            String id = song.path("songmid").asText("").trim();
            if (id.isBlank()) id = song.path("media_mid").asText("").trim();
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

    private List<Map<String, Object>> searchKugou(String text, int size) throws Exception {
        String url = "https://songsearch.kugou.com/song_search_v2?keyword="
                + URLEncoder.encode(String.valueOf(text == null ? "" : text), StandardCharsets.UTF_8)
                + "&page=1&pagesize=" + Math.max(1, Math.min(MAX_SEARCH_LIMIT, size))
                + "&platform=WebFilter&userid=-1&clientver=2000&iscorrection=1"
                + "&privilege_filter=0&filter=10&area_code=101";
        JsonNode root = getJsonRaw(url, "https://www.kugou.com/");
        List<Map<String, Object>> tracks = new ArrayList<>();
        for (JsonNode song : root.path("data").path("lists")) {
            String id = song.path("FileHash").asText("").trim();
            if (id.isBlank()) id = song.path("HQFileHash").asText("").trim();
            if (id.isBlank()) continue;
            String title = song.path("SongName").asText("未命名歌曲");
            String originalTitle = song.path("OriSongName").asText("").trim();
            Map<String, Object> track = chartTrack(
                    "kg", id,
                    title,
                    song.path("SingerName").asText("未知歌手"),
                    song.path("AlbumName").asText(""),
                    "",
                    song.path("Duration").asLong(0),
                    song.path("PayType").asInt(0),
                    "https://www.kugou.com/song/#hash=" + id
            );
            if (!originalTitle.isBlank()) track.put("matchTitle", originalTitle);
            tracks.add(track);
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
        List<String> runtimeQualities = new ArrayList<>();
        for (LxSourceRuntime.PlatformInfo info : runtime.platforms().values()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", info.code());
            String label = displayPlatformLabel(info.code(), info.label());
            item.put("label", label);
            item.put("name", label);
            item.put("actions", info.actions());
            item.put("qualities", info.qualities());
            platforms.add(item);
            for (String quality : info.qualities()) {
                if (!runtimeQualities.contains(quality)) runtimeQualities.add(quality);
            }
        }
        data.put("platforms", platforms);
        data.put("platformLabels", platformLabels());
        data.put("qualities", runtimeQualities.isEmpty()
                ? split(siteConfigService.get().getOrDefault("musicSourceQualities", "128k,320k,flac"))
                : runtimeQualities);
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
        return loadRuntimeEntry(url, refresh).runtime;
    }

    private RuntimeEntry loadRuntimeEntry(String url, boolean refresh) {
        synchronized (runtimeLock) {
            RuntimeEntry entry = runtimes.get(url);
            long now = System.currentTimeMillis();
            if (!refresh && entry != null && now - entry.createdAt < RUNTIME_TTL_MILLIS) {
                return entry;
            }
            try {
                Map<String, String> cfg = siteConfigService.get();
                boolean localSource = isLocalSource(cfg);
                String cached = refresh ? null : readCachedScript(url);
                String script = cached != null ? cached : downloadScript(url);
                String hash = sha256(script);
                if (!refresh && entry != null && entry.hash.equals(hash)
                        && now - entry.createdAt < RUNTIME_TTL_MILLIS) {
                    return entry;
                }
                String scriptPath;
                if (localSource) {
                    // 本地音源的真实文件就是上传时保存的 upload-<hash>.js，
                    // 运行时直接复用它，避免再生成一份 source-<hash>.js 造成历史脚本堆积。
                    String fileName = url.substring(LOCAL_SOURCE_PREFIX.length());
                    scriptPath = SOURCE_CACHE_DIR + "/" + fileName;
                } else {
                    String fileName = "source-" + hash.substring(0, 12) + ".js";
                    scriptPath = writeScriptCache(fileName, script);
                }
                LxSourceRuntime runtime = createRuntimeWithTimeout(script);
                if (entry != null) entry.runtime.close();
                RuntimeEntry next = new RuntimeEntry(hash, runtime, now, scriptPath);
                runtimes.put(url, next);
                trimRuntimes(url);
                return next;
            } catch (BizException e) {
                throw e;
            } catch (Exception e) {
                log.warn("加载音源失败 url={}: {}", url, e.toString());
                throw new BizException("音源导入失败：" + shortMessage(e));
            }
        }
    }

    private void registerRuntime(String key, String hash, LxSourceRuntime runtime, String scriptPath) {
        synchronized (runtimeLock) {
            RuntimeEntry old = runtimes.put(key, new RuntimeEntry(hash, runtime,
                    System.currentTimeMillis(), scriptPath));
            if (old != null) old.runtime.close();
            trimRuntimes(key);
        }
    }

    private void trimRuntimes(String keepKey) {
        while (runtimes.size() > 3) {
            String oldest = runtimes.entrySet().stream()
                    .filter(item -> !item.getKey().equals(keepKey))
                    .min(java.util.Comparator.comparingLong(item -> item.getValue().createdAt))
                    .map(Map.Entry::getKey)
                    .orElse(null);
            if (oldest == null) break;
            RuntimeEntry old = runtimes.remove(oldest);
            if (old != null) old.runtime.close();
        }
    }

    private void testPlayback(LxSourceRuntime runtime, Map<String, Object> data) {
        data.put("tested", false);
        data.put("testMessage", "音源已初始化，尚未测试播放地址");
        if (!runtime.platforms().containsKey("wy")) {
            data.put("testMessage", "音源已初始化；未包含网易云测试平台");
            return;
        }
        try {
            String testUrl = runtime.resolveMusicUrl("wy", "1883571160", "128k");
            if (testUrl.isBlank()) {
                data.put("tested", false);
                data.put("testMessage", "音源已初始化，测试曲目未返回播放地址");
                return;
            }
            String playable = playableUrl(testUrl);
            String probeError = probeAudioUrl(playable);
            data.put("tested", probeError.isBlank());
            data.put("testMessage", probeError.isBlank()
                    ? "音源播放地址测试通过"
                    : "音源已初始化，但测试音频地址不可用：" + probeError);
            data.put("testTrack", Map.of(
                    "id", "1883571160",
                    "title", "慢慢喜欢你",
                    "artist", "白青",
                    "url", playable
            ));
        } catch (RuntimeException e) {
            data.put("testMessage", "音源已初始化，但测试曲目未返回地址：" + shortMessage(e));
        }
    }

    /** 导入时真实请求一小段音频，避免只拿到地址就误报“播放测试通过”。 */
    private String probeAudioUrl(String url) {
        if (url == null || url.isBlank()) return "播放地址为空";
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(8))
                    .header("User-Agent", USER_AGENT)
                    .header("Range", "bytes=0-0")
                    .header("Accept", "audio/*,*/*")
                    .GET()
                    .build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            try (InputStream body = response.body()) {
                // 只探测可读性，不下载完整音频。
            }
            if (status >= 200 && status < 400) return "";
            return "HTTP " + status;
        } catch (java.net.http.HttpTimeoutException e) {
            return "请求超时";
        } catch (Exception e) {
            return shortMessage(e);
        }
    }

    private String currentVerifiedSourceKey() {
        Map<String, String> cfg = siteConfigService.get();
        if (!"verified".equalsIgnoreCase(cfg.getOrDefault("musicSourceStatus", ""))) return "";
        return String.valueOf(cfg.getOrDefault("musicSourceUrl", "")).trim();
    }

    private void persistVerifiedSource(String key, String type, String localFileName,
                                       Map<String, Object> data, String scriptPath, String hash) {
        String now = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        List<String> platformCodes = new ArrayList<>();
        Object platforms = data.get("platforms");
        if (platforms instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Object code = map.get("code");
                    if (code != null && !String.valueOf(code).isBlank()) platformCodes.add(String.valueOf(code));
                }
            }
        }
        List<String> qualities = new ArrayList<>();
        Object qualityData = data.get("qualities");
        if (qualityData instanceof List<?> list) {
            for (Object item : list) {
                if (item != null && !String.valueOf(item).isBlank()) qualities.add(String.valueOf(item));
            }
        }
        Map<String, String> values = new LinkedHashMap<>();
        values.put("musicSourceUrl", key);
        values.put("musicSourceType", type);
        values.put("musicSourceLocalFileName", localFileName == null ? "" : localFileName);
        values.put("musicSourceName", String.valueOf(data.getOrDefault("name", "")));
        values.put("musicSourceVersion", String.valueOf(data.getOrDefault("version", "")));
        values.put("musicSourceDescription", String.valueOf(data.getOrDefault("description", "")));
        values.put("musicSourcePlatforms", String.join(",", platformCodes));
        values.put("musicSourceQualities", String.join(",", qualities));
        values.put("musicSourceScriptPath", scriptPath == null ? "" : scriptPath);
        values.put("musicSourceScriptHash", hash == null ? "" : hash);
        values.put("musicSourceStatus", "verified");
        values.put("musicSourceVerifiedAt", now);
        values.put("musicSourceCheckedAt", now);
        siteConfigService.update(values);
    }

    private boolean isLocalSource(Map<String, String> cfg) {
        String type = cfg.getOrDefault("musicSourceType", "").trim();
        String url = cfg.getOrDefault("musicSourceUrl", "").trim();
        return "file".equals(type) || url.startsWith(LOCAL_SOURCE_PREFIX);
    }

    private boolean sourceScriptReady(Map<String, String> cfg) {
        String path = cfg.getOrDefault("musicSourceScriptPath", "").trim();
        if (path.isBlank()) return false;
        try {
            return Files.isRegularFile(resolveScriptPath(path));
        } catch (Exception e) {
            return false;
        }
    }

    private String readCachedScript(String url) {
        Map<String, String> cfg = siteConfigService.get();
        String configured = cfg.getOrDefault("musicSourceUrl", "").trim();
        if (url == null || !url.equals(configured)) return null;
        String path = cfg.getOrDefault("musicSourceScriptPath", "").trim();
        if (path.isBlank()) return null;
        try {
            Path file = resolveScriptPath(path);
            if (!Files.isRegularFile(file)) return null;
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.debug("读取音源缓存失败: {}", e.toString());
            return null;
        }
    }

    private String writeScriptCache(String fileName, String script) throws IOException {
        if (fileName == null || !fileName.matches("[A-Za-z0-9._-]{1,120}")) {
            throw new IOException("非法的音源缓存文件名");
        }
        Path target = sourceDir.resolve(fileName).normalize();
        if (!target.startsWith(sourceDir)) throw new IOException("非法的音源缓存路径");
        Files.writeString(target, script, StandardCharsets.UTF_8);
        return SOURCE_CACHE_DIR + "/" + fileName;
    }

    private void deleteScriptCache(String fileName) {
        try {
            Path target = sourceDir.resolve(fileName).normalize();
            if (target.startsWith(sourceDir)) Files.deleteIfExists(target);
        } catch (Exception ignored) {
        }
    }

    private String configuredScriptFileName() {
        String path = String.valueOf(siteConfigService.get().getOrDefault("musicSourceScriptPath", ""))
                .trim().replace('\\', '/');
        if (path.isBlank()) return "";
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    /** 音源缓存目录只保留当前脚本；未配置当前脚本时保留最近写入的一个。 */
    private void pruneSourceScripts(String keepFileName) {
        try (Stream<Path> entries = Files.list(sourceDir)) {
            List<Path> scripts = entries
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".js"))
                    .sorted(Comparator.comparingLong((Path path) -> path.toFile().lastModified()).reversed())
                    .toList();
            if (scripts.isEmpty()) return;
            String keep = keepFileName == null ? "" : keepFileName.trim();
            if (keep.isBlank()) keep = scripts.get(0).getFileName().toString();
            for (Path script : scripts) {
                if (keep.equals(script.getFileName().toString())) continue;
                try {
                    Files.deleteIfExists(script);
                    log.info("已清理历史音源脚本：{}", script.getFileName());
                } catch (IOException e) {
                    log.warn("清理历史音源脚本失败 {}: {}", script.getFileName(), e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("扫描音源脚本缓存失败：{}", e.getMessage());
        }
    }

    private Path resolveScriptPath(String relativePath) {
        String value = String.valueOf(relativePath == null ? "" : relativePath).trim().replace('\\', '/');
        if (value.isBlank() || value.contains("..")) throw new IllegalArgumentException("非法音源脚本路径");
        Path root = sourceDir.getParent();
        Path target = root.resolve(value).normalize();
        if (!target.startsWith(root)) throw new IllegalArgumentException("非法音源脚本路径");
        return target;
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
        if (url.startsWith(LOCAL_SOURCE_PREFIX)) {
            String name = url.substring(LOCAL_SOURCE_PREFIX.length());
            Path file = sourceDir.resolve(name).normalize();
            if (!file.startsWith(sourceDir) || !Files.isRegularFile(file)) {
                throw new IOException("本地音源脚本不存在");
            }
            return Files.readString(file, StandardCharsets.UTF_8);
        }
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
        if (value.startsWith(LOCAL_SOURCE_PREFIX)) {
            String name = value.substring(LOCAL_SOURCE_PREFIX.length());
            if (!name.matches("[A-Za-z0-9._-]{1,120}") || !Files.isRegularFile(sourceDir.resolve(name))) {
                throw new BizException("本地音源脚本不存在，请重新导入");
            }
            return value;
        }
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

    private String platformLabel(LxSourceRuntime runtime, String code) {
        if (runtime != null) {
            LxSourceRuntime.PlatformInfo info = runtime.platforms().get(code);
            if (info != null) return displayPlatformLabel(code, info.label());
        }
        return displayPlatformLabel(code, "");
    }

    /**
     * 音源有时把平台名写成 {@code kg音乐} / {@code tx音乐} 这类代码占位。
     * 这类占位才换成站点已知的中文名；正常自定义名称（如“企鹅音乐”）原样保留。
     */
    private String displayPlatformLabel(String code, String rawLabel) {
        String normalizedCode = String.valueOf(code == null ? "" : code).trim().toLowerCase(Locale.ROOT);
        String canonical = PLATFORM_LABELS.getOrDefault(normalizedCode,
                normalizedCode.isBlank() ? "" : normalizedCode.toUpperCase(Locale.ROOT));
        String value = String.valueOf(rawLabel == null ? "" : rawLabel).trim();
        if (value.isBlank()) return canonical;
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.equals(normalizedCode)
                || lower.equals(normalizedCode + "音乐")
                || lower.equals(normalizedCode + "music")
                || lower.equals(normalizedCode + " music")) {
            return canonical;
        }
        return value;
    }

    private String secureUrl(String url) {
        String value = String.valueOf(url == null ? "" : url).trim();
        if (value.startsWith("//")) return "https:" + value;
        if (value.startsWith("http://")) return "https://" + value.substring(7);
        return value;
    }

    /**
     * 音频地址保留源站协议。部分源站只开放 http，强行改成 https 会直接导致无法播放；
     * 浏览器端统一通过本站 /api/music/audio 同源代理加载，不受混合内容限制。
     */
    private String playableUrl(String url) {
        String value = String.valueOf(url == null ? "" : url).trim();
        if (value.startsWith("//")) return "https:" + value;
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

    private record RuntimeEntry(String hash, LxSourceRuntime runtime, long createdAt, String scriptPath) {
    }

    private record SearchEntry(long createdAt, Map<String, Object> data) {
    }

    private record ChartEntry(long createdAt, Map<String, Object> data) {
    }

    private record UrlEntry(long createdAt, Map<String, Object> data) {
    }
}
