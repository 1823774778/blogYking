package com.deek.blog.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 歌曲歌词查询。优先使用网易云公开歌词接口（按歌曲 ID 或歌名/歌手匹配），
 * 再回退到 LRCLIB；返回解析后的时间轴，供前台做跟随播放的动效歌词。
 */
@Service
public class MusicLyricService {

    private static final Logger log = LoggerFactory.getLogger(MusicLyricService.class);
    private static final Duration CACHE_TTL = Duration.ofHours(6);
    private static final Duration EMPTY_CACHE_TTL = Duration.ofMinutes(10);
    private static final int CACHE_LIMIT = 300;
    private static final Pattern LRC_TIMESTAMP = Pattern.compile("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]");
    private static final Pattern META_LINE = Pattern.compile("^\\s*\\[[a-zA-Z]{1,10}:.*]\\s*$");

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(6))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public MusicLyricService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> lookup(String source, String id, String title, String artist,
                                      String album, String duration, boolean refresh) {
        String platform = clean(source).toLowerCase(Locale.ROOT);
        String songId = clean(id);
        String songTitle = clean(title);
        String songArtist = clean(artist);
        String songAlbum = clean(album);
        if (songTitle.isBlank() && songId.isBlank()) {
            throw new IllegalArgumentException("缺少歌曲名称");
        }
        if (songTitle.length() > 160) songTitle = songTitle.substring(0, 160);
        if (songArtist.length() > 160) songArtist = songArtist.substring(0, 160);
        String key = String.join("|", platform, songId, songTitle, songArtist, songAlbum);
        CacheEntry entry = cache.get(key);
        if (!refresh && entry != null
                && System.currentTimeMillis() - entry.createdAt < entry.ttlMillis) {
            return entry.data;
        }

        Map<String, Object> data;
        try {
            data = resolve(platform, songId, songTitle, songArtist, songAlbum, duration);
        } catch (Exception e) {
            log.debug("歌词查询失败 source={} id={} title={}: {}", platform, songId, songTitle, e.toString());
            data = empty(songTitle, songArtist, "歌词暂时不可用");
        }
        if (cache.size() > CACHE_LIMIT) cache.clear();
        long ttl = ttlMillis(data);
        cache.put(key, new CacheEntry(System.currentTimeMillis(), ttl, data));
        return data;
    }

    private Map<String, Object> resolve(String platform, String id, String title, String artist,
                                        String album, String duration) throws Exception {
        // 网易云歌曲可直接按 ID 取歌词；其他平台用歌名/歌手在网易云曲库中匹配。
        // 搜索结果里可能同名但无歌词，逐个候选尝试，避免拿到空歌词就结束。
        List<String> candidates = new ArrayList<>();
        if ("wy".equals(platform) && id.matches("[0-9]{1,20}")) candidates.add(id);
        if (!title.isBlank()) {
            try {
                candidates.addAll(searchNeteaseCandidateIds(title, artist));
            } catch (Exception e) {
                log.debug("网易云歌词搜索失败，转 LRCLIB title={}: {}", title, e.toString());
            }
        }
        for (String candidateId : candidates) {
            try {
                String lyric = fetchNeteaseLyric(candidateId);
                if (!lyric.isBlank()) {
                    return payload("netease", candidateId, title, artist, lyric);
                }
            } catch (Exception e) {
                log.debug("网易云候选歌词跳过 id={}: {}", candidateId, e.toString());
            }
        }
        if (!title.isBlank()) {
            String lyric = fetchLrclib(title, artist, album, duration);
            if (lyric.isBlank() && !artist.isBlank()) {
                // 英文/外文歌在 LRCLIB 的艺人名经常与本站不一致，去掉艺人后再试一次。
                lyric = fetchLrclib(title, "", "", duration);
            }
            if (!lyric.isBlank()) {
                return payload("lrclib", id, title, artist, lyric);
            }
        }
        return empty(title, artist, "");
    }

    private String fetchNeteaseLyric(String id) throws Exception {
        if (id == null || id.isBlank()) return "";
        String url = "https://music.163.com/api/song/lyric?id=" + enc(id) + "&lv=1&kv=1&tv=-1";
        JsonNode root = getJson(url, "https://music.163.com/");
        return root.path("lrc").path("lyric").asText("");
    }

    private List<String> searchNeteaseCandidateIds(String title, String artist) throws Exception {
        String query = title + (artist.isBlank() ? "" : " " + artist);
        String url = "https://music.163.com/api/search/get/web?s=" + enc(query)
                + "&type=1&offset=0&total=true&limit=10";
        JsonNode root = getJson(url, "https://music.163.com/");
        JsonNode songs = root.path("result").path("songs");
        if (!songs.isArray()) return List.of();
        String wantedTitle = normalize(title);
        String wantedArtist = normalize(artist);
        List<String> strong = new ArrayList<>();
        List<String> titleOnly = new ArrayList<>();
        List<String> loose = new ArrayList<>();
        for (JsonNode song : songs) {
            String candidateId = song.path("id").asText("");
            if (candidateId.isBlank()) continue;
            String candidateTitle = normalize(song.path("name").asText(""));
            boolean exactTitle = candidateTitle.equals(wantedTitle);
            boolean containsTitle = !wantedTitle.isBlank()
                    && (candidateTitle.startsWith(wantedTitle) || candidateTitle.contains(wantedTitle));
            if (!exactTitle && !containsTitle) continue;
            String candidateArtist = "";
            JsonNode artists = song.path("artists");
            if (artists.isArray()) {
                List<String> names = new ArrayList<>();
                for (JsonNode item : artists) {
                    String name = normalize(item.path("name").asText(""));
                    if (!name.isBlank()) names.add(name);
                }
                candidateArtist = String.join("/", names);
            }
            boolean artistMatched = !wantedArtist.isBlank()
                    ? artistCompatible(wantedArtist, candidateArtist) : true;
            if (exactTitle && artistMatched) strong.add(candidateId);
            else if (exactTitle) titleOnly.add(candidateId);
            else loose.add(candidateId);
        }
        List<String> result = new ArrayList<>();
        for (String id : strong) if (!result.contains(id)) result.add(id);
        for (String id : titleOnly) if (!result.contains(id)) result.add(id);
        for (String id : loose) if (!result.contains(id)) result.add(id);
        return result.size() > 6 ? result.subList(0, 6) : result;
    }

    private String fetchLrclib(String title, String artist, String album, String duration) throws Exception {
        String url = "https://lrclib.net/api/get?artist_name=" + enc(artist)
                + "&track_name=" + enc(title)
                + "&album_name=" + enc(album)
                + "&duration=" + enc(normalizeDuration(duration));
        HttpResponse<String> response = getResponse(url, "https://lrclib.net/");
        if (response.statusCode() == 200) {
            JsonNode root = objectMapper.readTree(response.body());
            String synced = root.path("syncedLyrics").asText("");
            if (!synced.isBlank()) return synced;
            return root.path("plainLyrics").asText("");
        }
        if (!title.isBlank()) {
            String searchUrl = "https://lrclib.net/api/search?q=" + enc(title + " " + artist);
            HttpResponse<String> search = getResponse(searchUrl, "https://lrclib.net/");
            if (search.statusCode() == 200) {
                JsonNode rows = objectMapper.readTree(search.body());
                if (rows.isArray()) {
                    for (JsonNode row : rows) {
                        if (!normalize(row.path("trackName").asText("")).equals(normalize(title))) continue;
                        if (!artist.isBlank()
                                && !artistCompatible(normalize(artist), normalize(row.path("artistName").asText("")))) {
                            continue;
                        }
                        String synced = row.path("syncedLyrics").asText("");
                        if (!synced.isBlank()) return synced;
                        String plain = row.path("plainLyrics").asText("");
                        if (!plain.isBlank()) return plain;
                    }
                }
            }
        }
        return "";
    }

    private JsonNode getJson(String url, String referer) throws Exception {
        HttpResponse<String> response = getResponse(url, referer);
        if (response.statusCode() < 200 || response.statusCode() >= 300) return objectMapper.createObjectNode();
        return objectMapper.readTree(response.body());
    }

    private HttpResponse<String> getResponse(String url, String referer) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(12))
                .header("User-Agent", "Mozilla/5.0 (compatible; deek-blog/1.0)")
                .header("Referer", referer)
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private Map<String, Object> payload(String provider, String matchedId, String title,
                                        String artist, String rawLyric) {
        List<Map<String, Object>> lines = parseLyric(rawLyric);
        boolean hasTimed = lines.stream().anyMatch(line -> Number.class.cast(line.get("time")).doubleValue() > 0);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("provider", provider);
        data.put("matchedId", matchedId);
        data.put("title", title);
        data.put("artist", artist);
        data.put("hasTimed", hasTimed);
        data.put("lines", lines);
        data.put("message", lines.isEmpty() ? "暂无歌词" : "");
        return data;
    }

    private Map<String, Object> empty(String title, String artist, String message) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("provider", "");
        data.put("matchedId", "");
        data.put("title", title);
        data.put("artist", artist);
        data.put("hasTimed", false);
        data.put("lines", List.of());
        data.put("message", message);
        return data;
    }

    private List<Map<String, Object>> parseLyric(String raw) {
        List<Map<String, Object>> timed = new ArrayList<>();
        if (raw == null || raw.isBlank()) return timed;
        for (String original : raw.replace("\r", "").split("\n")) {
            String line = original.trim();
            if (line.isBlank() || META_LINE.matcher(line).matches()) continue;
            Matcher matcher = LRC_TIMESTAMP.matcher(line);
            List<Double> times = new ArrayList<>();
            int end = 0;
            while (matcher.find()) {
                String minute = matcher.group(1);
                String second = matcher.group(2);
                String fraction = matcher.group(3);
                double seconds = Integer.parseInt(minute) * 60.0 + Integer.parseInt(second);
                if (fraction != null && !fraction.isBlank()) {
                    seconds += Integer.parseInt(fraction) / Math.pow(10, fraction.length());
                }
                times.add(seconds);
                end = matcher.end();
            }
            String text = line.substring(end).trim();
            if (text.isBlank()) continue;
            for (Double time : times) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("time", Math.round(time * 1000.0) / 1000.0);
                item.put("text", text);
                timed.add(item);
            }
        }
        if (!timed.isEmpty()) {
            timed.sort(Comparator.comparingDouble(item -> ((Number) item.get("time")).doubleValue()));
            return timed;
        }
        List<Map<String, Object>> plain = new ArrayList<>();
        for (String line : raw.replace("\r", "").split("\n")) {
            String text = line.trim();
            if (text.isBlank() || META_LINE.matcher(text).matches()) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("time", 0);
            item.put("text", text);
            plain.add(item);
        }
        return plain;
    }

    private boolean artistCompatible(String wanted, String candidate) {
        if (wanted.isBlank() || candidate.isBlank()) return true;
        return wanted.contains(candidate) || candidate.contains(wanted)
                || wanted.split("/").length > 1 && candidate.contains(wanted.split("/")[0])
                || candidate.split("/").length > 1 && wanted.contains(candidate.split("/")[0]);
    }

    private String normalize(String value) {
        if (value == null) return "";
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{Punct}\\s·・]+", "");
        return normalized.trim();
    }

    private String normalizeDuration(String duration) {
        try {
            double value = Double.parseDouble(clean(duration));
            if (value <= 0) return "";
            return String.valueOf(Math.round(value));
        } catch (Exception ignored) {
            return "";
        }
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private long ttlMillis(Map<String, Object> data) {
        Object lines = data.get("lines");
        boolean empty = !(lines instanceof List<?>) || ((List<?>) lines).isEmpty();
        return (empty ? EMPTY_CACHE_TTL : CACHE_TTL).toMillis();
    }

    private static final class CacheEntry {
        private final long createdAt;
        private final long ttlMillis;
        private final Map<String, Object> data;

        private CacheEntry(long createdAt, long ttlMillis, Map<String, Object> data) {
            this.createdAt = createdAt;
            this.ttlMillis = ttlMillis;
            this.data = data;
        }
    }
}
