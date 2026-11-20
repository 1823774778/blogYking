package com.deek.blog.service.storage;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 本地存储根目录解析：默认放在当前运行 jar 的同级 local-storage。
 * 兼容旧版 ./uploads；设置 UPLOAD_DIR 时仍以显式目录为准。
 */
@Component
public class StoragePathResolver {

    private static final Logger log = LoggerFactory.getLogger(StoragePathResolver.class);

    @Value("${app.upload-dir:}")
    private String configuredUploadDir;

    @Value("${app.ota.jar-path:}")
    private String configuredJarPath;

    private Path root;
    private Path jarDirectory;
    private Path legacyRoot;

    @PostConstruct
    public void init() {
        Path jar = locateJar();
        this.jarDirectory = jar == null ? null : jar.getParent();
        String explicit = configuredUploadDir == null ? "" : configuredUploadDir.trim();
        if (!explicit.isBlank()) {
            root = Paths.get(explicit).toAbsolutePath().normalize();
            legacyRoot = null;
        } else {
            Path base = jarDirectory == null
                    ? Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize()
                    : jarDirectory;
            root = base.resolve("local-storage").toAbsolutePath().normalize();
            Path old = base.resolve("uploads").toAbsolutePath().normalize();
            legacyRoot = Files.isDirectory(old) && !old.equals(root) ? old : null;
        }
        try {
            Files.createDirectories(root);
            for (StorageCategory category : StorageCategory.values()) {
                Files.createDirectories(root.resolve(category.folder()));
            }
        } catch (Exception e) {
            throw new IllegalStateException("无法创建本地存储目录: " + root, e);
        }
        log.info("本地存储目录：{}，旧目录：{}", root, legacyRoot == null ? "无" : legacyRoot);
    }

    public Path root() {
        return root;
    }

    public Path jarDirectory() {
        return jarDirectory;
    }

    public Path legacyRoot() {
        return legacyRoot;
    }

    private Path locateJar() {
        String configured = configuredJarPath == null ? "" : configuredJarPath.trim();
        if (!configured.isBlank()) {
            try {
                Path path = Paths.get(configured).toAbsolutePath().normalize();
                if (Files.isRegularFile(path)) return path.toRealPath();
            } catch (Exception ignored) {
            }
        }
        String classPath = System.getProperty("java.class.path", "");
        if (!classPath.isBlank() && !classPath.contains(File.pathSeparator)) {
            try {
                Path path = Paths.get(classPath).toAbsolutePath().normalize();
                if (Files.isRegularFile(path) && path.toString().toLowerCase().endsWith(".jar")) {
                    return path.toRealPath();
                }
            } catch (Exception ignored) {
            }
        }
        try {
            URI uri = StoragePathResolver.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            String raw = uri.toString();
            int bang = raw.indexOf('!');
            if (bang > 0) {
                raw = raw.substring(0, bang);
                if (raw.startsWith("jar:")) raw = raw.substring(4);
                uri = URI.create(raw);
            }
            Path path = Paths.get(uri).toAbsolutePath().normalize();
            if (Files.isRegularFile(path) && path.toString().toLowerCase().endsWith(".jar")) {
                return path.toRealPath();
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
