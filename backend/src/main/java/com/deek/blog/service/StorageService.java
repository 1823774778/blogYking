package com.deek.blog.service;

import com.deek.blog.common.BizException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

@Service
public class StorageService {

    private static final Set<String> IMAGE_EXT = Set.of("jpg", "jpeg", "png", "gif", "webp", "svg");
    private static final Set<String> AUDIO_EXT = Set.of("mp3", "ogg", "wav", "m4a", "aac", "flac");
    private static final long MAX_AUDIO_BYTES = 30L * 1024 * 1024;

    private final Path root;
    private final SiteConfigService siteConfigService;

    public StorageService(@Value("${app.upload-dir:./uploads}") String uploadDir, SiteConfigService siteConfigService) {
        this.root = Paths.get(uploadDir).toAbsolutePath().normalize();
        this.siteConfigService = siteConfigService;
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建上传目录: " + root, e);
        }
    }

    public String store(MultipartFile file) {
        return storeFile(file, IMAGE_EXT, "image/", "只支持上传图片文件", "图片保存失败，请重试");
    }

    public String storeAudio(MultipartFile file) {
        if (file != null && file.getSize() > MAX_AUDIO_BYTES) {
            throw new BizException("音频不能超过 30MB");
        }
        return storeFile(file, AUDIO_EXT, "audio/", "只支持上传音频文件", "音频保存失败，请重试");
    }

    private String storeFile(MultipartFile file, Set<String> allowedExt, String contentTypePrefix,
                             String typeError, String saveError) {
        if (file == null || file.isEmpty()) {
            throw new BizException("请选择要上传的文件");
        }
        String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        if (!contentType.startsWith(contentTypePrefix)) {
            throw new BizException(typeError);
        }
        String original = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        String ext = original.contains(".")
                ? original.substring(original.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT)
                : switch (contentTypePrefix) {
                    case "image/" -> contentType.startsWith("image/png") ? "png"
                            : contentType.startsWith("image/gif") ? "gif"
                            : contentType.startsWith("image/webp") ? "webp" : "jpg";
                    case "audio/" -> "mp3";
                    default -> "";
                };
        if (!allowedExt.contains(ext)) {
            throw new BizException("不支持的文件格式：" + ext);
        }
        String name = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
                + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12) + "." + ext;
        if (isOnline()) {
            return storeOnline(file, name, contentType);
        }
        Path target = root.resolve(name).normalize();
        if (!target.startsWith(root)) {
            throw new BizException("非法的文件名");
        }
        try {
            Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new BizException(saveError);
        }
        return "/uploads/" + name;
    }

    public boolean isOnline() {
        return "s3".equalsIgnoreCase(config().getOrDefault("storageProvider", "local"));
    }

    public Map<String, Object> overview() {
        Map<String, String> cfg = config();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("provider", isOnline() ? "s3" : "local");
        result.put("root", root.toString());
        result.put("bucket", cfg.getOrDefault("storageBucket", ""));
        result.put("endpoint", cfg.getOrDefault("storageEndpoint", ""));
        result.put("publicBaseUrl", cfg.getOrDefault("storagePublicBaseUrl", ""));
        long[] totals = localTotals();
        result.put("localFiles", totals[0]);
        result.put("localBytes", totals[1]);
        result.put("configured", !isOnline() || s3Ready(cfg));
        return result;
    }

    public Map<String, Object> listLocal(int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(100, Math.max(1, size));
        List<Map<String, Object>> all = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root, 1)) {
            stream.filter(Files::isRegularFile).forEach(path -> {
                try {
                    Map<String, Object> item = new LinkedHashMap<>();
                    String name = path.getFileName().toString();
                    item.put("name", name);
                    item.put("size", Files.size(path));
                    item.put("url", "/uploads/" + name);
                    item.put("modifiedAt", Instant.ofEpochMilli(Files.getLastModifiedTime(path).toMillis())
                            .atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
                    all.add(item);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException e) {
            throw new BizException("读取本地存储失败：" + e.getMessage());
        }
        all.sort(Comparator.comparing((Map<String, Object> m) -> (String) m.get("modifiedAt")).reversed());
        int total = all.size();
        int from = Math.min(total, safePage * safeSize);
        int to = Math.min(total, from + safeSize);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", all.subList(from, to));
        result.put("total", total);
        result.put("page", safePage);
        result.put("size", safeSize);
        return result;
    }

    public void deleteLocal(String rawName) {
        String name = rawName == null ? "" : rawName.trim();
        if (name.isBlank() || name.contains("/") || name.contains("\\") || name.contains("..")) {
            throw new BizException("文件名不合法");
        }
        Path target = root.resolve(name).normalize();
        if (!target.startsWith(root) || !Files.isRegularFile(target)) {
            throw new BizException("文件不存在");
        }
        try {
            Files.delete(target);
        } catch (IOException e) {
            throw new BizException("删除失败：" + e.getMessage());
        }
    }

    /** 测试线上存储配置是否可访问。 */
    public Map<String, Object> testOnline() {
        Map<String, String> cfg = config();
        if (!isOnline()) {
            throw new BizException("当前使用本地存储，请先把存储方式切换为线上存储");
        }
        if (!s3Ready(cfg)) {
            throw new BizException("请先填写 Endpoint、Bucket、Access Key 和 Secret Key");
        }
        try (S3Client client = s3Client(cfg)) {
            client.headBucket(HeadBucketRequest.builder().bucket(cfg.get("storageBucket").trim()).build());
            return Map.of("ok", true, "message", "连接成功，Bucket 可访问");
        } catch (Exception e) {
            throw new BizException("连接失败：" + e.getMessage());
        }
    }

    private String storeOnline(MultipartFile file, String name, String contentType) {
        Map<String, String> cfg = config();
        if (!s3Ready(cfg)) {
            throw new BizException("线上存储配置不完整，请先在存储管理中填写");
        }
        String prefix = cfg.getOrDefault("storagePathPrefix", "blog").trim();
        prefix = prefix.replaceAll("^/+|/+$", "");
        String key = (prefix.isBlank() ? "" : prefix + "/") + name;
        try (InputStream in = file.getInputStream(); S3Client client = s3Client(cfg)) {
            client.putObject(PutObjectRequest.builder()
                            .bucket(cfg.get("storageBucket").trim())
                            .key(key)
                            .contentType(contentType)
                            .build(),
                    RequestBody.fromInputStream(in, file.getSize()));
        } catch (Exception e) {
            throw new BizException("线上存储上传失败：" + e.getMessage());
        }
        String base = cfg.getOrDefault("storagePublicBaseUrl", "").trim();
        if (!base.isBlank()) {
            return base.replaceAll("/+$", "") + "/" + key;
        }
        String endpoint = cfg.getOrDefault("storageEndpoint", "").trim().replaceAll("/+$", "");
        return endpoint + "/" + cfg.get("storageBucket").trim() + "/" + key;
    }

    private S3Client s3Client(Map<String, String> cfg) {
        String region = cfg.getOrDefault("storageRegion", "auto").trim();
        if (region.isBlank() || "auto".equalsIgnoreCase(region)) region = "us-east-1";
        return S3Client.builder()
                .endpointOverride(URI.create(cfg.get("storageEndpoint").trim()))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        cfg.get("storageAccessKey").trim(), cfg.get("storageSecretKey").trim())))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(Boolean.parseBoolean(cfg.getOrDefault("storagePathStyle", "true")))
                        .build())
                .build();
    }

    private boolean s3Ready(Map<String, String> cfg) {
        return !cfg.getOrDefault("storageEndpoint", "").isBlank()
                && !cfg.getOrDefault("storageBucket", "").isBlank()
                && !cfg.getOrDefault("storageAccessKey", "").isBlank()
                && !cfg.getOrDefault("storageSecretKey", "").isBlank();
    }

    private long[] localTotals() {
        long count = 0;
        long bytes = 0;
        try (Stream<Path> stream = Files.walk(root, 1)) {
            List<Path> files = stream.filter(Files::isRegularFile).toList();
            count = files.size();
            for (Path f : files) bytes += Files.size(f);
        } catch (IOException ignored) {
        }
        return new long[]{count, bytes};
    }

    private Map<String, String> config() {
        return siteConfigService.get();
    }
}
