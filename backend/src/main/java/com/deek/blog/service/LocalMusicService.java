package com.deek.blog.service;

import com.deek.blog.common.BizException;
import com.deek.blog.entity.LocalMusic;
import com.deek.blog.entity.StoredFile;
import com.deek.blog.repository.LocalMusicRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 站点本地音乐：把管理员上传的音频登记为可播放曲目。 */
@Service
public class LocalMusicService {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final LocalMusicRepository repository;
    private final StorageService storageService;

    public LocalMusicService(LocalMusicRepository repository, StorageService storageService) {
        this.repository = repository;
        this.storageService = storageService;
    }

    public List<Map<String, Object>> adminTracks() {
        List<Map<String, Object>> tracks = new ArrayList<>();
        for (LocalMusic track : repository.findAllByOrderBySortOrderAscCreatedAtDesc()) {
            tracks.add(adminView(track));
        }
        return tracks;
    }

    public List<Map<String, Object>> publicTracks() {
        List<Map<String, Object>> tracks = new ArrayList<>();
        for (LocalMusic track : repository.findByEnabledTrueOrderBySortOrderAscCreatedAtDesc()) {
            tracks.add(publicView(track));
        }
        return tracks;
    }

    @Transactional
    public Map<String, Object> upload(MultipartFile file, String rawTitle, String rawArtist, Long uploaderId) {
        StoredFile asset = storageService.storeAudioFile(file, uploaderId);
        String original = asset.getOriginalName() == null ? "本地音乐" : asset.getOriginalName();
        String fileName = clean(original, 512, "本地音乐");
        String title = clean(rawTitle, 160, "");
        String artist = clean(rawArtist, 80, "");
        if (!title.isBlank()) {
            if (artist.isBlank()) artist = "未知歌手";
        } else {
            String base = stripExtension(fileName).replace('_', ' ').trim();
            int dash = base.indexOf(" - ");
            if (dash > 0) {
                if (artist.isBlank()) artist = base.substring(0, dash).trim();
                title = base.substring(dash + 3).trim();
            } else {
                title = base;
            }
            if (artist.isBlank()) artist = "未知歌手";
        }
        if (title.isBlank()) title = "本地音乐";
        LocalMusic track = new LocalMusic();
        track.setTitle(title);
        track.setArtist(artist.isBlank() ? "未知歌手" : artist);
        track.setAlbum("");
        track.setUrl(asset.getUrl());
        track.setProvider(asset.getProvider());
        track.setObjectKey(asset.getObjectKey());
        track.setFileName(fileName);
        track.setSize(asset.getSize());
        track.setDuration(0);
        track.setEnabled(true);
        track.setSortOrder((int) Math.min(Integer.MAX_VALUE, repository.count()));
        try {
            return adminView(repository.save(track));
        } catch (RuntimeException e) {
            storageService.deleteAsset(asset);
            throw e;
        }
    }

    @Transactional
    public Map<String, Object> update(Long id, Map<String, String> body) {
        LocalMusic track = repository.findById(id)
                .orElseThrow(() -> BizException.notFound("本地音乐不存在"));
        Map<String, String> values = body == null ? Map.of() : body;
        if (values.containsKey("title")) {
            String title = clean(values.get("title"), 160, "");
            if (!title.isBlank()) track.setTitle(title);
        }
        if (values.containsKey("artist")) {
            String artist = clean(values.get("artist"), 80, "");
            track.setArtist(artist.isBlank() ? "未知歌手" : artist);
        }
        if (values.containsKey("album")) {
            track.setAlbum(clean(values.get("album"), 160, ""));
        }
        if (values.containsKey("duration")) {
            try {
                int duration = Math.max(0, Integer.parseInt(String.valueOf(values.get("duration")).trim()));
                track.setDuration(Math.min(duration, 24 * 60 * 60));
            } catch (NumberFormatException ignored) {
            }
        }
        if (values.containsKey("enabled")) {
            track.setEnabled(!"false".equalsIgnoreCase(String.valueOf(values.get("enabled")).trim()));
        }
        track.setUpdatedAt(java.time.LocalDateTime.now());
        return adminView(repository.save(track));
    }

    @Transactional
    public void delete(Long id) {
        LocalMusic track = repository.findById(id)
                .orElseThrow(() -> BizException.notFound("本地音乐不存在"));
        repository.delete(track);
        storageService.deleteByKey(track.getProvider(), track.getObjectKey());
    }

    private Map<String, Object> publicView(LocalMusic track) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", String.valueOf(track.getId()));
        item.put("source", "local");
        item.put("platform", "local");
        item.put("title", track.getTitle());
        item.put("artist", track.getArtist());
        item.put("album", track.getAlbum());
        item.put("cover", "");
        item.put("url", track.getUrl());
        item.put("duration", track.getDuration());
        item.put("fee", 0);
        item.put("vip", false);
        item.put("pageUrl", track.getUrl());
        return item;
    }

    private Map<String, Object> adminView(LocalMusic track) {
        Map<String, Object> item = publicView(track);
        item.put("fileName", track.getFileName());
        item.put("size", track.getSize());
        item.put("enabled", track.isEnabled());
        item.put("createdAt", track.getCreatedAt() == null ? "" : track.getCreatedAt().format(TIME));
        item.put("updatedAt", track.getUpdatedAt() == null ? "" : track.getUpdatedAt().format(TIME));
        return item;
    }

    private String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private String clean(String value, int max, String fallback) {
        String text = String.valueOf(value == null ? "" : value).trim();
        if (text.length() > max) text = text.substring(0, max);
        return text.isBlank() ? fallback : text;
    }
}
