package com.deek.blog.service;

import com.deek.blog.common.BizException;
import com.deek.blog.entity.StoredFile;
import com.deek.blog.entity.User;
import com.deek.blog.repository.StoredFileRepository;
import com.deek.blog.repository.UserRepository;
import com.deek.blog.service.storage.LocalStorageStore;
import com.deek.blog.service.storage.StorageCategory;
import com.deek.blog.service.storage.StoragePathResolver;
import com.deek.blog.service.storage.WebDavStorageClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 存储统一入口：本地目录、S3 兼容、WebDAV、OpenList。
 * 本地文件按 images/audio/video/documents/archives/other 分类，云端沿用同一分类前缀。
 */
@Service
public class StorageService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final java.util.Set<String> IMAGE_EXT = java.util.Set.of("jpg", "jpeg", "png", "gif", "webp", "svg", "bmp", "avif");
    private static final java.util.Set<String> AUDIO_EXT = java.util.Set.of("mp3", "ogg", "wav", "m4a", "aac", "flac", "opus");
    private static final long MAX_AUDIO_BYTES = 30L * 1024 * 1024;

    private final LocalStorageStore localStore;
    private final StoragePathResolver paths;
    private final WebDavStorageClient webDav;
    private final StoredFileRepository storedFileRepository;
    private final UserRepository userRepository;
    private final SiteConfigService siteConfigService;

    public StorageService(LocalStorageStore localStore, StoragePathResolver paths,
                          WebDavStorageClient webDav, StoredFileRepository storedFileRepository,
                          UserRepository userRepository, SiteConfigService siteConfigService) {
        this.localStore = localStore;
        this.paths = paths;
        this.webDav = webDav;
        this.storedFileRepository = storedFileRepository;
        this.userRepository = userRepository;
        this.siteConfigService = siteConfigService;
    }

    public String store(MultipartFile file, Long uploaderId) {
        return storeFile(file, uploaderId, IMAGE_EXT, "image/", StorageCategory.IMAGE,
                "只支持上传图片文件", "图片保存失败，请重试");
    }

    public String storeAudio(MultipartFile file, Long uploaderId) {
        if (file != null && file.getSize() > MAX_AUDIO_BYTES) {
            throw new BizException("音频不能超过 30MB");
        }
        return storeFile(file, uploaderId, AUDIO_EXT, "audio/", StorageCategory.AUDIO,
                "只支持上传音频文件", "音频保存失败，请重试");
    }

    private String storeFile(MultipartFile file, Long uploaderId, java.util.Set<String> allowedExt,
                             String contentTypePrefix, StorageCategory category,
                             String typeError, String saveError) {
        if (file == null || file.isEmpty()) {
            throw new BizException("请选择要上传的文件");
        }
        String original = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        if (!contentType.startsWith(contentTypePrefix)) {
            throw new BizException(typeError);
        }
        String ext = StorageCategory.extension(original);
        if (ext.isBlank()) {
            ext = switch (contentTypePrefix) {
                case "image/" -> contentType.contains("png") ? "png"
                        : contentType.contains("gif") ? "gif"
                        : contentType.contains("webp") ? "webp" : "jpg";
                case "audio/" -> "mp3";
                default -> "";
            };
        }
        if (!allowedExt.contains(ext)) {
            throw new BizException("不支持的文件格式：" + ext);
        }
        String generatedName = LocalDate.now().format(DATE) + "-"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 12) + "." + ext;
        Map<String, String> cfg = config();
        String provider = provider(cfg);
        String prefix = normalizePrefix(cfg.getOrDefault("storagePathPrefix", "blog"));
        String objectKey = prefix.isBlank()
                ? category.folder() + "/" + generatedName
                : prefix + "/" + category.folder() + "/" + generatedName;

        String url;
        String storedKey;
        try {
            if ("local".equals(provider)) {
                LocalStorageStore.StoreResult result = localStore.store(file, category);
                storedKey = result.objectKey();
                url = result.url();
            } else if ("s3".equals(provider)) {
                url = storeS3(file, cfg, objectKey, contentType);
                storedKey = objectKey;
            } else {
                WebDavStorageClient.UploadResult result = webDav.upload(cfg, provider, file, objectKey, contentType);
                url = result.url();
                storedKey = result.objectKey();
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(saveError + "：" + e.getMessage());
        }

        StoredFile meta = new StoredFile();
        meta.setProvider(provider);
        meta.setObjectKey(storedKey);
        meta.setOriginalName(original);
        meta.setCategory(category.folder());
        meta.setContentType(contentType);
        meta.setSize(file.getSize());
        meta.setUrl(url);
        applyUploader(meta, uploaderId);
        storedFileRepository.save(meta);
        return url;
    }

    public boolean isOnline() {
        return !"local".equals(provider(config()));
    }

    public String provider() {
        return provider(config());
    }

    public Map<String, Object> overview() {
        Map<String, String> cfg = config();
        String provider = provider(cfg);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("provider", provider);
        result.put("providerLabel", providerLabel(provider));
        result.put("configured", isConfigured(provider, cfg));
        result.put("endpoint", cfg.getOrDefault("storageEndpoint", ""));
        result.put("bucket", cfg.getOrDefault("storageBucket", ""));
        result.put("publicBaseUrl", cfg.getOrDefault("storagePublicBaseUrl", ""));
        result.put("pathPrefix", cfg.getOrDefault("storagePathPrefix", ""));
        result.put("webdavUsername", cfg.getOrDefault("storageUsername", ""));
        if ("local".equals(provider)) {
            long[] totals = localStore.totals();
            result.put("root", paths.root().toString());
            result.put("jarDirectory", paths.jarDirectory() == null ? "" : paths.jarDirectory().toString());
            result.put("localFiles", totals[0]);
            result.put("localBytes", totals[1]);
            result.put("categories", new ArrayList<>(localStore.categoryStats().values()));
        }
        return result;
    }

    /**
     * 线上存储模式不返回本地文件列表，避免线上页面混入本地目录内容。
     * 搜索覆盖存储文件名、原始文件名、上传人、文件类型与分类。
     */
    public Map<String, Object> listLocal(int page, int size, String keyword,
                                         String categoryFilter, String uploaderFilter) {
        if (isOnline()) {
            throw new BizException("当前为线上存储，本地文件列表已隐藏；切换回本地存储后可查看");
        }
        int safePage = Math.max(0, page);
        int safeSize = Math.min(100, Math.max(1, size));
        String query = lower(keyword);
        String uploaderNeedle = lower(uploaderFilter);
        StorageCategory category = categoryFilter == null || categoryFilter.isBlank()
                ? null : StorageCategory.fromFolder(categoryFilter.trim());
        if (categoryFilter != null && !categoryFilter.isBlank() && category == null) {
            throw new BizException("文件分类不合法");
        }

        Map<String, StoredFile> metadata = localMetadata();
        List<Map<String, Object>> all = new ArrayList<>();
        for (LocalStorageStore.LocalObject object : localStore.listAll()) {
            StoredFile meta = metadata.get(object.objectKey());
            String originalName = meta == null || meta.getOriginalName() == null || meta.getOriginalName().isBlank()
                    ? object.storedName() : meta.getOriginalName();
            String uploaderName = meta == null || meta.getUploaderName() == null || meta.getUploaderName().isBlank()
                    ? "历史文件" : meta.getUploaderName();
            String uploaderUsername = meta == null || meta.getUploaderUsername() == null
                    ? "" : meta.getUploaderUsername();
            String typeLabel = object.category().label();
            String extLabel = object.extension();
            if (category != null && object.category() != category) continue;
            if (!uploaderNeedle.isBlank()
                    && !uploaderName.toLowerCase(Locale.ROOT).contains(uploaderNeedle)
                    && !uploaderUsername.toLowerCase(Locale.ROOT).contains(uploaderNeedle)) {
                continue;
            }
            if (!query.isBlank()) {
                String haystack = String.join(" ", originalName, object.storedName(), uploaderName,
                        uploaderUsername, typeLabel, extLabel, object.category().folder()).toLowerCase(Locale.ROOT);
                if (!haystack.contains(query)) continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("path", object.objectKey());
            item.put("name", object.storedName());
            item.put("originalName", originalName);
            item.put("category", object.category().folder());
            item.put("categoryLabel", typeLabel);
            item.put("type", extLabel);
            item.put("typeLabel", extLabel.isBlank() ? typeLabel : typeLabel + " · " + extLabel);
            item.put("contentType", meta == null || meta.getContentType() == null
                    ? probeType(object.path()) : meta.getContentType());
            item.put("size", object.size());
            item.put("uploaderName", uploaderName);
            item.put("uploaderUsername", uploaderUsername);
            item.put("uploadedAt", meta == null ? object.modifiedText()
                    : meta.getCreatedAt().atZone(ZoneId.systemDefault()).format(TIME));
            item.put("modifiedAt", object.modifiedText());
            item.put("url", object.url());
            item.put("legacy", !object.objectKey().contains("/"));
            all.add(item);
        }
        all.sort(Comparator.comparing((Map<String, Object> item) -> String.valueOf(item.get("uploadedAt"))).reversed());
        int total = all.size();
        int from = Math.min(total, safePage * safeSize);
        int to = Math.min(total, from + safeSize);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", all.subList(from, to));
        result.put("total", total);
        result.put("page", safePage);
        result.put("size", safeSize);
        result.put("categories", new ArrayList<>(localStore.categoryStats().values()));
        result.put("root", paths.root().toString());
        return result;
    }

    @Transactional
    public void deleteLocal(String rawPath) {
        if (isOnline()) {
            throw new BizException("当前为线上存储，不能通过本地文件接口删除文件");
        }
        String path = rawPath == null ? "" : rawPath.trim().replace('\\', '/');
        StoredFile meta = storedFileRepository.findByProviderAndObjectKey("local", path).orElse(null);
        localStore.delete(path);
        if (meta != null) {
            storedFileRepository.delete(meta);
        }
    }

    /** 测试当前线上存储配置；本地存储直接返回可用。 */
    public Map<String, Object> testOnline() {
        Map<String, String> cfg = config();
        String provider = provider(cfg);
        if ("local".equals(provider)) {
            throw new BizException("当前使用本地存储，请先把存储方式切换为线上存储并保存");
        }
        if ("s3".equals(provider)) {
            if (!s3Ready(cfg)) {
                throw new BizException("请先填写 Endpoint、Bucket、Access Key 和 Secret Key");
            }
            try (S3Client client = s3Client(cfg)) {
                client.headBucket(HeadBucketRequest.builder().bucket(cfg.get("storageBucket").trim()).build());
                return Map.of("ok", true, "message", "S3 连接成功，Bucket 可访问");
            } catch (Exception e) {
                throw new BizException("S3 连接失败：" + e.getMessage());
            }
        }
        if (!"webdav".equals(provider) && !"openlist".equals(provider)) {
            throw new BizException("不支持的存储方式：" + provider);
        }
        return webDav.test(cfg, provider);
    }

    private Map<String, StoredFile> localMetadata() {
        Map<String, StoredFile> map = new LinkedHashMap<>();
        for (StoredFile file : storedFileRepository.findByProvider("local")) {
            map.put(file.getObjectKey(), file);
        }
        return map;
    }

    private void applyUploader(StoredFile meta, Long uploaderId) {
        if (uploaderId == null) {
            meta.setUploaderName("系统");
            meta.setUploaderUsername("system");
            return;
        }
        meta.setUploaderId(uploaderId);
        User user = userRepository.findById(uploaderId).orElse(null);
        if (user == null) {
            meta.setUploaderName("未知用户");
            meta.setUploaderUsername("");
            return;
        }
        meta.setUploaderName(user.getNickname() == null || user.getNickname().isBlank()
                ? user.getUsername() : user.getNickname());
        meta.setUploaderUsername(user.getUsername());
    }

    private String storeS3(MultipartFile file, Map<String, String> cfg,
                           String objectKey, String contentType) throws IOException {
        if (!s3Ready(cfg)) {
            throw new BizException("线上存储配置不完整，请先在存储管理中填写");
        }
        try (InputStream in = file.getInputStream(); S3Client client = s3Client(cfg)) {
            client.putObject(PutObjectRequest.builder()
                            .bucket(cfg.get("storageBucket").trim())
                            .key(objectKey)
                            .contentType(contentType)
                            .build(),
                    RequestBody.fromInputStream(in, file.getSize()));
        } catch (Exception e) {
            throw new BizException("S3 上传失败：" + e.getMessage());
        }
        String base = cfg.getOrDefault("storagePublicBaseUrl", "").trim();
        if (!base.isBlank()) {
            return base.replaceAll("/+$", "") + "/" + objectKey;
        }
        String endpoint = cfg.getOrDefault("storageEndpoint", "").trim().replaceAll("/+$", "");
        return endpoint + "/" + cfg.get("storageBucket").trim() + "/" + objectKey;
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

    private boolean isConfigured(String provider, Map<String, String> cfg) {
        return switch (provider) {
            case "local" -> true;
            case "s3" -> s3Ready(cfg);
            case "webdav", "openlist" -> !cfg.getOrDefault("storageEndpoint", "").isBlank();
            default -> false;
        };
    }

    private String provider(Map<String, String> cfg) {
        String value = cfg.getOrDefault("storageProvider", "local").trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "s3", "webdav", "openlist" -> value;
            default -> "local";
        };
    }

    private String providerLabel(String provider) {
        return switch (provider) {
            case "s3" -> "S3 兼容存储";
            case "webdav" -> "WebDAV";
            case "openlist" -> "OpenList";
            default -> "本地存储";
        };
    }

    private String normalizePrefix(String raw) {
        String value = raw == null ? "" : raw.trim().replace('\\', '/').replaceAll("^/+|/+$", "");
        if (value.isBlank()) return "";
        List<String> segments = new ArrayList<>();
        for (String segment : value.split("/")) {
            String item = segment.trim();
            if (item.isBlank() || ".".equals(item) || "..".equals(item)) continue;
            if (!item.matches("[A-Za-z0-9._\\-\\u4e00-\\u9fa5]{1,128}")) {
                throw new BizException("存储路径只能包含字母、数字、汉字、点、横线和下划线");
            }
            segments.add(item);
        }
        return String.join("/", segments);
    }

    private String probeType(java.nio.file.Path path) {
        try {
            String type = Files.probeContentType(path);
            return type == null ? "application/octet-stream" : type;
        } catch (IOException e) {
            return "application/octet-stream";
        }
    }

    private Map<String, String> config() {
        return siteConfigService.get();
    }

    private static String lower(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
