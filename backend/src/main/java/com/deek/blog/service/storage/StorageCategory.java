package com.deek.blog.service.storage;

import java.util.Locale;
import java.util.Set;

/** 本地存储的分类目录，也是本地文件列表的筛选维度。 */
public enum StorageCategory {

    IMAGE("images", "图片", Set.of("jpg", "jpeg", "png", "gif", "webp", "svg", "bmp", "avif")),
    AUDIO("audio", "音频", Set.of("mp3", "ogg", "wav", "m4a", "aac", "flac", "opus")),
    VIDEO("video", "视频", Set.of("mp4", "webm", "mov", "mkv", "avi")),
    DOCUMENT("documents", "文档", Set.of("pdf", "txt", "md", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "csv")),
    ARCHIVE("archives", "压缩包", Set.of("zip", "7z", "rar", "tar", "gz", "tgz", "bz2")),
    OTHER("other", "其他", Set.of());

    private final String folder;
    private final String label;
    private final Set<String> extensions;

    StorageCategory(String folder, String label, Set<String> extensions) {
        this.folder = folder;
        this.label = label;
        this.extensions = extensions;
    }

    public String folder() {
        return folder;
    }

    public String label() {
        return label;
    }

    public static StorageCategory fromFolder(String folder) {
        for (StorageCategory category : values()) {
            if (category.folder.equalsIgnoreCase(folder)) {
                return category;
            }
        }
        return null;
    }

    public static StorageCategory fromFileName(String fileName) {
        String ext = extension(fileName);
        for (StorageCategory category : values()) {
            if (category.extensions.contains(ext)) {
                return category;
            }
        }
        return OTHER;
    }

    public static StorageCategory fromContentType(String contentType) {
        String value = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (value.startsWith("image/")) return IMAGE;
        if (value.startsWith("audio/")) return AUDIO;
        if (value.startsWith("video/")) return VIDEO;
        if (value.startsWith("text/") || value.contains("pdf") || value.contains("word")
                || value.contains("excel") || value.contains("powerpoint")) {
            return DOCUMENT;
        }
        if (value.contains("zip") || value.contains("rar") || value.contains("tar")
                || value.contains("gzip") || value.contains("7z")) {
            return ARCHIVE;
        }
        return OTHER;
    }

    public static String extension(String fileName) {
        if (fileName == null) return "";
        String name = fileName.trim().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot < 0 || dot == name.length() - 1 ? "" : name.substring(dot + 1);
    }
}
