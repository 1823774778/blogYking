package com.deek.blog.service.storage;

import com.deek.blog.common.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
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
import java.util.UUID;
import java.util.stream.Stream;

/** 纯文件系统操作，不负责数据库元数据与 HTTP 提供方选择。 */
@Service
public class LocalStorageStore {

    private static final Logger log = LoggerFactory.getLogger(LocalStorageStore.class);
    private static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;

    private final StoragePathResolver paths;

    public LocalStorageStore(StoragePathResolver paths) {
        this.paths = paths;
        migrateLegacy(paths.legacyRoot());
    }

    public Path root() {
        return paths.root();
    }

    public StoreResult store(MultipartFile file, StorageCategory category) {
        if (file == null || file.isEmpty()) {
            throw new BizException("请选择要上传的文件");
        }
        String original = file.getOriginalFilename() == null ? "file" : file.getOriginalFilename();
        String ext = StorageCategory.extension(original);
        if (ext.isBlank()) {
            ext = switch (category) {
                case IMAGE -> "jpg";
                case AUDIO -> "mp3";
                case VIDEO -> "mp4";
                default -> "bin";
            };
        }
        if (!ext.matches("[a-z0-9]{1,12}")) {
            throw new BizException("文件扩展名不合法");
        }
        String name = LocalDate.now().format(DATE) + "-"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 12) + "." + ext;
        Path directory = root().resolve(category.folder()).normalize();
        Path target = directory.resolve(name).normalize();
        if (!target.startsWith(root()) || !target.startsWith(directory)) {
            throw new BizException("非法的文件名");
        }
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new BizException("本地文件保存失败：" + e.getMessage());
        }
        String objectKey = category.folder() + "/" + name;
        return new StoreResult(objectKey, "/uploads/" + objectKey);
    }

    public List<LocalObject> listAll() {
        List<LocalObject> items = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root(), 3)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> !path.getFileName().toString().startsWith("."))
                    .filter(path -> !path.getFileName().toString().endsWith(".part"))
                    .forEach(path -> {
                        try {
                            String relative = toRelative(path);
                            if (relative.isBlank() || relative.contains("..")) return;
                            String first = relative.contains("/") ? relative.substring(0, relative.indexOf('/')) : "";
                            StorageCategory category = StorageCategory.fromFolder(first);
                            if (category == null) {
                                category = StorageCategory.fromFileName(path.getFileName().toString());
                            }
                            items.add(new LocalObject(
                                    path,
                                    relative,
                                    path.getFileName().toString(),
                                    category,
                                    Files.size(path),
                                    Files.getLastModifiedTime(path).toMillis(),
                                    "/uploads/" + relative
                            ));
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException e) {
            throw new BizException("读取本地存储失败：" + e.getMessage());
        }
        items.sort(Comparator.comparingLong(LocalObject::modifiedAt).reversed());
        return items;
    }

    public Map<String, Map<String, Object>> categoryStats() {
        Map<String, Map<String, Object>> stats = new LinkedHashMap<>();
        for (StorageCategory category : StorageCategory.values()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("key", category.folder());
            item.put("label", category.label());
            item.put("count", 0L);
            item.put("bytes", 0L);
            stats.put(category.folder(), item);
        }
        for (LocalObject object : listAll()) {
            Map<String, Object> item = stats.get(object.category().folder());
            if (item != null) {
                item.put("count", ((Long) item.get("count")) + 1);
                item.put("bytes", ((Long) item.get("bytes")) + object.size());
            }
        }
        return stats;
    }

    public long[] totals() {
        long count = 0;
        long bytes = 0;
        for (LocalObject object : listAll()) {
            count++;
            bytes += object.size();
        }
        return new long[]{count, bytes};
    }

    public void delete(String rawPath) {
        String value = rawPath == null ? "" : rawPath.trim().replace('\\', '/');
        if (value.isBlank() || value.contains("..")) {
            throw new BizException("文件路径不合法");
        }
        Path target = root().resolve(value).normalize();
        if (!target.startsWith(root()) || !Files.isRegularFile(target)) {
            // 兼容旧接口只传文件名：在分类目录和根目录中按文件名查找。
            target = findByStoredName(value);
        }
        if (target == null || !Files.isRegularFile(target)) {
            throw new BizException("文件不存在");
        }
        try {
            Files.delete(target);
        } catch (IOException e) {
            throw new BizException("删除失败：" + e.getMessage());
        }
    }

    public Path findByStoredName(String name) {
        String safe = name == null ? "" : name.trim();
        if (safe.isBlank() || safe.contains("/") || safe.contains("\\") || safe.contains("..")) {
            return null;
        }
        for (StorageCategory category : StorageCategory.values()) {
            Path candidate = root().resolve(category.folder()).resolve(safe).normalize();
            if (candidate.startsWith(root()) && Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        Path legacy = root().resolve(safe).normalize();
        return legacy.startsWith(root()) && Files.isRegularFile(legacy) ? legacy : null;
    }

    public Path findLegacyByName(String name) {
        if (paths.legacyRoot() == null) return null;
        String safe = name == null ? "" : name.trim();
        if (safe.isBlank() || safe.contains("/") || safe.contains("\\") || safe.contains("..")) {
            return null;
        }
        Path candidate = paths.legacyRoot().resolve(safe).normalize();
        return candidate.startsWith(paths.legacyRoot()) && Files.isRegularFile(candidate) ? candidate : null;
    }

    private void migrateLegacy(Path legacyRoot) {
        if (legacyRoot == null || !Files.isDirectory(legacyRoot)) {
            return;
        }
        int copied = 0;
        try (Stream<Path> stream = Files.walk(legacyRoot, 2)) {
            for (Path path : stream.filter(Files::isRegularFile).toList()) {
                String name = path.getFileName().toString();
                if (name.startsWith(".") || name.endsWith(".part")) continue;
                StorageCategory category = StorageCategory.fromFileName(name);
                Path target = root().resolve(category.folder()).resolve(name).normalize();
                if (Files.exists(target)) continue;
                try {
                    Files.copy(path, target, StandardCopyOption.COPY_ATTRIBUTES);
                    copied++;
                } catch (IOException e) {
                    log.warn("迁移旧上传文件失败 {}: {}", path, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("扫描旧上传目录失败: {}", e.getMessage());
        }
        if (copied > 0) {
            log.info("已从旧上传目录迁移 {} 个文件到 {}", copied, root());
        }
    }

    private String toRelative(Path path) {
        Path relative = root().relativize(path);
        return relative.toString().replace('\\', '/');
    }

    public record StoreResult(String objectKey, String url) {
    }

    public record LocalObject(Path path, String objectKey, String storedName,
                              StorageCategory category, long size, long modifiedAt, String url) {

        public String modifiedText() {
            return Instant.ofEpochMilli(modifiedAt).atZone(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        }

        public String extension() {
            return StorageCategory.extension(storedName).toUpperCase(Locale.ROOT);
        }
    }
}
