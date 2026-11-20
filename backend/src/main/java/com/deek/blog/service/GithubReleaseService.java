package com.deek.blog.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 从 GitHub Releases API 读取博客版本与更新内容。
 *
 * <p>只读公开仓库的发布信息，不下载或执行 Release 资产。GitHub 未认证 API 每小时只有 60 次，
 * 因此默认缓存 10 分钟；配置 GITHUB_TOKEN 可提高限额。请求失败时优先返回上一次成功缓存，
 * 并在返回结果里标记 {@code stale=true} 与错误原因，便于后台提示而不是整块消失。
 */
@Service
public class GithubReleaseService {

    private static final Logger log = LoggerFactory.getLogger(GithubReleaseService.class);
    private static final Pattern REPO_PATTERN = Pattern.compile("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$");
    private static final int MAX_BODY_CHARS = 20_000;

    private final String apiBase;
    private final String repo;
    private final String token;
    private final int maxReleases;
    private final long cacheSeconds;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String apiUrl;

    private volatile Cache cache;

    public GithubReleaseService(@Value("${app.ota.github.api-base:https://api.github.com}") String apiBase,
                                @Value("${app.ota.github.repo:1823774778/blogYking}") String repo,
                                @Value("${app.ota.github.token:}") String token,
                                @Value("${app.ota.github.cache-seconds:600}") long cacheSeconds,
                                @Value("${app.ota.github.max-releases:12}") int maxReleases,
                                ObjectMapper objectMapper) {
        this.apiBase = trimSlash(apiBase == null || apiBase.isBlank() ? "https://api.github.com" : apiBase.trim());
        String configuredRepo = repo == null ? "" : repo.trim();
        this.repo = REPO_PATTERN.matcher(configuredRepo).matches() ? configuredRepo : "1823774778/blogYking";
        this.token = token == null ? "" : token.trim();
        this.cacheSeconds = Math.max(0, cacheSeconds);
        this.maxReleases = Math.min(50, Math.max(1, maxReleases));
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.apiUrl = this.apiBase + "/repos/" + this.repo + "/releases?per_page=" + this.maxReleases;
    }

    /**
     * 读取发布列表。{@code refresh=true} 时跳过硬缓存，但仍会在失败时回退到已有缓存。
     */
    public synchronized ReleasesView releases(String currentVersion, boolean refresh) {        Cache cached = cache;
        Instant now = Instant.now();
        if (!refresh && cached != null && cacheSeconds > 0
                && cached.fetchedAt().plusSeconds(cacheSeconds).isAfter(now)) {
            return view(cached.releases(), currentVersion, cached.fetchedAt(), true, false, "");
        }

        try {
            List<ReleaseInfo> releases = fetch();
            Cache fresh = new Cache(releases, now);
            cache = fresh;
            return view(releases, currentVersion, now, false, false, "");
        } catch (Exception e) {
            String message = friendlyError(e);
            log.warn("GitHub Releases 读取失败: {}", message);
            if (cached != null) {
                return view(cached.releases(), currentVersion, cached.fetchedAt(), true, true, message);
            }
            return view(List.of(), currentVersion, now, false, true, message);
        }
    }

    /** 清除 Release 列表缓存，下一次读取会重新请求 GitHub API。 */
    public synchronized void clearCache() {
        cache = null;
    }

    private List<ReleaseInfo> fetch() throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(apiUrl))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/vnd.github+json")
                .header("Accept-Encoding", "identity")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "deek-blog-ota/1.0 (+https://github.com/" + repo + ")")
                .GET();
        if (!token.isBlank()) {
            builder.header("Authorization", "Bearer " + token);
        }

        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException(githubHttpError(response));
        }

        JsonNode root = objectMapper.readTree(response.body());
        if (root == null || !root.isArray()) {
            throw new IOException("GitHub API 返回的不是 Release 列表");
        }

        List<ReleaseInfo> releases = new ArrayList<>();
        for (JsonNode item : root) {
            if (item.path("draft").asBoolean(false)) {
                continue;
            }
            String tag = text(item, "tag_name");
            if (tag.isBlank()) {
                continue;
            }
            String name = text(item, "name");
            if (name.isBlank()) {
                name = tag;
            }
            String body = text(item, "body");
            if (body.length() > MAX_BODY_CHARS) {
                body = body.substring(0, MAX_BODY_CHARS) + "\n\n…（更新内容过长，已截断）";
            }
            releases.add(new ReleaseInfo(
                    tag,
                    normalizeVersion(tag),
                    name,
                    body,
                    text(item, "published_at"),
                    text(item, "html_url"),
                    item.path("prerelease").asBoolean(false),
                    jarAsset(item.path("assets"))
            ));
        }

        releases.sort(Comparator.comparing((ReleaseInfo r) -> parseInstant(r.publishedAt()),
                Comparator.nullsLast(Comparator.naturalOrder())).reversed());
        return releases.stream().limit(maxReleases).toList();
    }

    private ReleaseAsset jarAsset(JsonNode assets) {
        if (assets == null || !assets.isArray()) {
            return null;
        }
        ReleaseAsset fallback = null;
        for (JsonNode asset : assets) {
            String name = text(asset, "name");
            if (!name.toLowerCase().endsWith(".jar")) {
                continue;
            }
            ReleaseAsset info = new ReleaseAsset(
                    name,
                    asset.path("size").asLong(0),
                    text(asset, "browser_download_url"),
                    text(asset, "content_type")
            );
            if ("blog.jar".equalsIgnoreCase(name)) {
                return info;
            }
            if (fallback == null) {
                fallback = info;
            }
        }
        return fallback;
    }

    private ReleasesView view(List<ReleaseInfo> releases, String currentVersion, Instant fetchedAt,
                              boolean cached, boolean stale, String error) {
        ReleaseInfo latest = releases.stream()
                .filter(r -> !r.prerelease())
                .findFirst()
                .orElse(releases.isEmpty() ? null : releases.get(0));
        List<ReleaseInfo> history = releases.stream()
                .filter(r -> latest == null || !r.tag().equals(latest.tag()))
                .toList();
        boolean updateAvailable = latest != null
                && !latest.version().equals("未知")
                && compareVersions(latest.version(), currentVersion) > 0;
        return new ReleasesView(
                repo,
                "https://github.com/" + repo,
                apiBase + "/repos/" + repo + "/releases",
                currentVersion == null || currentVersion.isBlank() ? "未知" : currentVersion,
                updateAvailable,
                latest,
                history,
                releases,
                fetchedAt.toString(),
                cached,
                stale,
                error == null ? "" : error
        );
    }

    private String githubHttpError(HttpResponse<String> response) {
        int status = response.statusCode();
        String remaining = response.headers().firstValue("X-RateLimit-Remaining").orElse("");
        if (status == 403 && "0".equals(remaining)) {
            long reset = response.headers().firstValue("X-RateLimit-Reset")
                    .map(v -> {
                        try {
                            return Long.parseLong(v);
                        } catch (NumberFormatException e) {
                            return 0L;
                        }
                    }).orElse(0L);
            long waitMinutes = reset > 0 ? Math.max(1, (reset - Instant.now().getEpochSecond() + 59) / 60) : 0;
            return "GitHub API 触发限流（未认证请求每小时 60 次），约 "
                    + (waitMinutes > 0 ? waitMinutes + " 分钟后" : "稍后")
                    + "恢复；可在服务端配置 GITHUB_TOKEN 提高限额";
        }
        if (status == 404) {
            return "GitHub 仓库或 Releases 不存在（" + repo + "）";
        }
        return "GitHub API 返回 HTTP " + status;
    }

    private String friendlyError(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return "无法连接 GitHub Releases API";
        }
        return message.length() > 180 ? message.substring(0, 180) : message;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText("").trim();
    }

    private static String normalizeVersion(String tag) {
        String value = tag == null ? "" : tag.trim();
        while (value.startsWith("v") || value.startsWith("V")) {
            value = value.substring(1);
        }
        return value.isBlank() ? "未知" : value;
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 只比较版本号中的数字段；同一数字序列下，稳定版高于预发布版。
     */
    static int compareVersions(String left, String right) {
        String a = left == null ? "" : left.trim();
        String b = right == null ? "" : right.trim();
        String[] ap = a.split("[^0-9]+");
        String[] bp = b.split("[^0-9]+");
        int max = Math.max(ap.length, bp.length);
        for (int i = 0; i < max; i++) {
            int av = i < ap.length ? parseInt(ap[i]) : 0;
            int bv = i < bp.length ? parseInt(bp[i]) : 0;
            if (av != bv) {
                return Integer.compare(av, bv);
            }
        }
        boolean aPre = a.contains("-");
        boolean bPre = b.contains("-");
        if (aPre != bPre) {
            return aPre ? -1 : 1;
        }
        return 0;
    }

    private static int parseInt(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String trimSlash(String value) {
        String out = value;
        while (out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }

    private record Cache(List<ReleaseInfo> releases, Instant fetchedAt) {
    }

    public record ReleaseAsset(String name, long size, String downloadUrl, String contentType) {
    }

    public record ReleaseInfo(String tag, String version, String name, String body, String publishedAt,
                              String htmlUrl, boolean prerelease, ReleaseAsset jarAsset) {
    }

    public record ReleasesView(String repo, String repoUrl, String apiUrl, String currentVersion,
                               boolean updateAvailable, ReleaseInfo latest, List<ReleaseInfo> history,
                               List<ReleaseInfo> releases, String fetchedAt, boolean cached,
                               boolean stale, String error) {
    }
}
