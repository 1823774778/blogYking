package com.deek.blog.service.storage;

import com.deek.blog.common.BizException;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * WebDAV 与 OpenList 使用同一套 WebDAV 协议上传。
 * OpenList 的地址若只填站点根地址，会自动补上 /dav。
 */
@Component
public class WebDavStorageClient {

    private static final String PROPFIND_BODY = """
            <?xml version="1.0" encoding="utf-8" ?>
            <propfind xmlns="DAV:"><prop><displayname/></prop></propfind>
            """;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public UploadResult upload(Map<String, String> cfg, String provider, MultipartFile file,
                               String objectKey, String contentType) {
        URI target = objectUri(cfg, provider, objectKey);
        createParentCollections(cfg, provider, objectKey);
        Path temp = null;
        try {
            temp = Files.createTempFile("webdav-upload-", ".part");
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
            }
            HttpRequest request = request(cfg, target)
                    .header("Content-Type", contentType == null || contentType.isBlank()
                            ? "application/octet-stream" : contentType)
                    .PUT(HttpRequest.BodyPublishers.ofFile(temp))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 && response.statusCode() != 201 && response.statusCode() != 204) {
                throw new BizException("WebDAV 上传失败：HTTP " + response.statusCode() + serverMessage(response));
            }
            return new UploadResult(objectKey, publicUrl(cfg, provider, objectKey));
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("WebDAV 上传失败：" + e.getMessage());
        } finally {
            if (temp != null) {
                try { Files.deleteIfExists(temp); } catch (IOException ignored) {}
            }
        }
    }

    public Map<String, Object> test(Map<String, String> cfg, String provider) {
        String prefix = normalizePrefix(cfg.getOrDefault("storagePathPrefix", "blog"));
        try {
            davBase(cfg, provider);
            if (!prefix.isBlank()) {
                createCollections(cfg, provider, prefix);
            }
            URI probe = collectionUri(cfg, provider, prefix);
            HttpRequest request = request(cfg, probe)
                    .method("PROPFIND", HttpRequest.BodyPublishers.ofString(PROPFIND_BODY))
                    .header("Depth", "0")
                    .header("Content-Type", "application/xml; charset=utf-8")
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 207 && response.statusCode() != 200) {
                throw new BizException("WebDAV 连接失败：HTTP " + response.statusCode() + serverMessage(response));
            }
            return Map.of("ok", true, "message",
                    provider.equals("openlist") ? "OpenList WebDAV 连接成功，目录可访问" : "WebDAV 连接成功，目录可访问");
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("WebDAV 连接失败：" + e.getMessage());
        }
    }

    private void createParentCollections(Map<String, String> cfg, String provider, String objectKey) {
        String prefix = normalizePrefix(cfg.getOrDefault("storagePathPrefix", "blog"));
        String category = objectKey.contains("/") ? objectKey.substring(0, objectKey.lastIndexOf('/')) : "";
        String path;
        if (prefix.isBlank()) {
            path = category;
        } else if (category.isBlank()) {
            path = prefix;
        } else if (category.equals(prefix) || category.startsWith(prefix + "/")) {
            path = category;
        } else {
            path = prefix + "/" + category;
        }
        if (!path.isBlank()) {
            createCollections(cfg, provider, path);
        }
    }

    private void createCollections(Map<String, String> cfg, String provider, String path) {
        List<String> segments = safeSegments(path);
        if (segments.isEmpty()) return;
        StringBuilder current = new StringBuilder();
        for (String segment : segments) {
            if (current.length() > 0) current.append('/');
            current.append(segment);
            URI uri = collectionUri(cfg, provider, current.toString());
            HttpRequest request = request(cfg, uri)
                    .method("MKCOL", HttpRequest.BodyPublishers.noBody())
                    .build();
            try {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if (status != 201 && status != 204 && status != 405 && status != 301) {
                    throw new BizException("创建 WebDAV 目录失败：HTTP " + status + serverMessage(response));
                }
            } catch (BizException e) {
                throw e;
            } catch (Exception e) {
                throw new BizException("创建 WebDAV 目录失败：" + e.getMessage());
            }
        }
    }

    private URI objectUri(Map<String, String> cfg, String provider, String objectKey) {
        return URI.create(davBase(cfg, provider) + "/" + encodePath(objectKey));
    }

    private URI collectionUri(Map<String, String> cfg, String provider, String path) {
        String base = davBase(cfg, provider);
        if (path == null || path.isBlank()) {
            return URI.create(base + "/");
        }
        return URI.create(base + "/" + encodePath(path) + "/");
    }

    private HttpRequest.Builder request(Map<String, String> cfg, URI uri) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60))
                .header("Accept", "*/*")
                .header("User-Agent", "deek-blog-storage/1.0");
        String username = cfg.getOrDefault("storageUsername", "").trim();
        String password = cfg.getOrDefault("storagePassword", "");
        if (!username.isBlank()) {
            String token = Base64.getEncoder().encodeToString(
                    (username + ":" + password).getBytes(StandardCharsets.UTF_8));
            builder.header("Authorization", "Basic " + token);
        }
        return builder;
    }

    private String davBase(Map<String, String> cfg, String provider) {
        String raw = cfg.getOrDefault("storageEndpoint", "").trim();
        if (raw.isBlank()) {
            throw new BizException("请填写 WebDAV 地址");
        }
        try {
            URI uri = URI.create(raw);
            if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
                throw new BizException("WebDAV 地址只支持 http/https");
            }
            if (uri.getHost() == null || uri.getHost().isBlank() || uri.getUserInfo() != null) {
                throw new BizException("WebDAV 地址不合法，账号密码请填写在独立字段");
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("WebDAV 地址不合法：" + e.getMessage());
        }
        String base = raw.replaceAll("/+$", "");
        if (provider.equals("openlist")) {
            URI uri = URI.create(base);
            String path = uri.getPath() == null ? "" : uri.getPath();
            if (path.isBlank() || "/".equals(path)) {
                base += "/dav";
            }
        }
        return base;
    }

    private String publicUrl(Map<String, String> cfg, String provider, String objectKey) {
        String base = cfg.getOrDefault("storagePublicBaseUrl", "").trim();
        if (!base.isBlank()) {
            return base.replaceAll("/+$", "") + "/" + encodePath(objectKey);
        }
        if (provider.equals("openlist")) {
            URI endpoint = URI.create(davBase(cfg, provider));
            String origin = endpoint.getScheme() + "://" + endpoint.getRawAuthority();
            return origin + "/d/" + encodePath(objectKey);
        }
        return davBase(cfg, provider) + "/" + encodePath(objectKey);
    }

    private String normalizePrefix(String raw) {
        String value = raw == null ? "" : raw.trim().replace('\\', '/').replaceAll("^/+|/+$", "");
        if (value.isBlank()) return "";
        List<String> safe = safeSegments(value);
        return String.join("/", safe);
    }

    private List<String> safeSegments(String path) {
        List<String> result = new ArrayList<>();
        for (String segment : path.split("/")) {
            String value = segment.trim();
            if (value.isBlank() || ".".equals(value) || "..".equals(value)) continue;
            if (!value.matches("[A-Za-z0-9._\\-\\u4e00-\\u9fa5]{1,128}")) {
                throw new BizException("存储路径只能包含字母、数字、汉字、点、横线和下划线");
            }
            result.add(value);
        }
        return result;
    }

    private String encodePath(String path) {
        List<String> parts = new ArrayList<>();
        for (String segment : path.split("/")) {
            if (segment.isBlank()) continue;
            parts.add(URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return String.join("/", parts);
    }

    private String serverMessage(HttpResponse<String> response) {
        String body = response.body();
        if (body == null || body.isBlank()) return "";
        String compact = body.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
        return compact.isBlank() ? "" : "（" + compact.substring(0, Math.min(80, compact.length())) + "）";
    }

    public record UploadResult(String objectKey, String url) {
    }
}
