package com.deek.blog.service;

import com.deek.blog.common.BizException;
import com.deek.blog.dto.PostDtos.TagView;
import com.deek.blog.dto.TagDtos.TagAdminView;
import com.deek.blog.dto.TagDtos.TagReq;
import com.deek.blog.entity.Post;
import com.deek.blog.entity.Tag;
import com.deek.blog.repository.PostRepository;
import com.deek.blog.repository.TagRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class TagService {

    private static final Logger log = LoggerFactory.getLogger(TagService.class);
    private static final Pattern COLOR = Pattern.compile("^#[0-9a-fA-F]{6}$");
    private static final String LEGACY_DEFAULT_COLOR = "#2f9d79";
    private static final String BLUE_DEFAULT_COLOR = "#4f86d8";

    private final TagRepository tagRepository;
    private final PostRepository postRepository;

    public TagService(TagRepository tagRepository, PostRepository postRepository) {
        this.tagRepository = tagRepository;
        this.postRepository = postRepository;
    }

    /** 1.10 蓝色主题：只把旧版默认绿色标签迁移为蓝色，保留用户自定义色。 */
    @Transactional
    @EventListener(ApplicationReadyEvent.class)
    public void migrateLegacyAccent() {
        List<Tag> legacy = tagRepository.findAll().stream()
                .filter(tag -> LEGACY_DEFAULT_COLOR.equalsIgnoreCase(tag.getColor()))
                .toList();
        if (legacy.isEmpty()) {
            return;
        }
        legacy.forEach(tag -> tag.setColor(BLUE_DEFAULT_COLOR));
        tagRepository.saveAll(legacy);
        log.info("已将 {} 个旧默认绿色标签迁移为 1.10 蓝色", legacy.size());
    }

    public List<TagAdminView> adminList() {
        Map<String, Long> counts = counts(postRepository.allTagsAll());
        return tagRepository.findAll().stream()
                .sorted(Comparator.comparingInt(Tag::getSortOrder).thenComparing(Tag::getName, String.CASE_INSENSITIVE_ORDER))
                .map(t -> toAdminView(t, counts.getOrDefault(t.getName().toLowerCase(Locale.ROOT), 0L)))
                .toList();
    }

    public List<TagView> publicTags() {
        Map<String, String> displayNames = new LinkedHashMap<>();
        for (String row : postRepository.allTagsAll()) {
            for (String raw : row.split(",")) {
                String name = raw.trim();
                if (!name.isEmpty()) displayNames.putIfAbsent(name.toLowerCase(Locale.ROOT), name);
            }
        }
        Map<String, Long> counts = counts(postRepository.allTags());
        Map<String, Tag> configured = new LinkedHashMap<>();
        for (Tag tag : tagRepository.findByVisibleTrueOrderBySortOrderAscNameAsc()) {
            configured.put(tag.getName().toLowerCase(Locale.ROOT), tag);
        }
        List<TagView> result = new ArrayList<>();
        Map<String, Boolean> seen = new LinkedHashMap<>();
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .forEach(e -> {
                    Tag tag = configured.get(e.getKey());
                    result.add(new TagView(tag == null ? displayNames.getOrDefault(e.getKey(), e.getKey()) : tag.getName(),
                            e.getValue(), tag == null ? null : tag.getColor(),
                            tag == null ? null : tag.getDescription()));
                    seen.put(e.getKey(), true);
                });
        configured.forEach((key, tag) -> {
            if (!seen.containsKey(key)) {
                result.add(new TagView(tag.getName(), 0, tag.getColor(), tag.getDescription()));
            }
        });
        return result.stream().limit(50).toList();
    }

    @Transactional
    public TagAdminView create(TagReq req) {
        String name = normalizeName(req.name());
        if (tagRepository.findFirstByNameIgnoreCase(name).isPresent()) {
            throw new BizException("标签名称已存在");
        }
        Tag tag = new Tag();
        apply(tag, req, name);
        tag.setCreatedAt(LocalDateTime.now());
        tag.setUpdatedAt(LocalDateTime.now());
        tagRepository.save(tag);
        return toAdminView(tag, 0);
    }

    @Transactional
    public TagAdminView update(Long id, TagReq req) {
        Tag tag = tagRepository.findById(id)
                .orElseThrow(() -> BizException.notFound("标签不存在"));
        String oldName = tag.getName();
        String name = normalizeName(req.name());
        if (!oldName.equalsIgnoreCase(name) && tagRepository.findFirstByNameIgnoreCase(name).isPresent()) {
            throw new BizException("标签名称已存在");
        }
        apply(tag, req, name);
        tag.setUpdatedAt(LocalDateTime.now());
        tagRepository.save(tag);
        if (!oldName.equalsIgnoreCase(name)) {
            replaceInPosts(oldName, name);
        }
        return toAdminView(tag, counts(postRepository.allTagsAll())
                .getOrDefault(name.toLowerCase(Locale.ROOT), 0L));
    }

    @Transactional
    public void delete(Long id) {
        Tag tag = tagRepository.findById(id)
                .orElseThrow(() -> BizException.notFound("标签不存在"));
        removeFromPosts(tag.getName());
        tagRepository.delete(tag);
    }

    /** 文章保存时把标签同步进标签目录，保证标签管理页能直接管理。 */
    @Transactional
    public void ensureTags(String tags) {
        if (tags == null || tags.isBlank()) return;
        for (String raw : tags.split(",")) {
            String name = raw.trim();
            if (name.isEmpty()) continue;
            if (tagRepository.findFirstByNameIgnoreCase(name).isEmpty()) {
                Tag tag = new Tag();
                tag.setName(name);
                tag.setSlug(slugify(name));
                tag.setColor("#4f86d8");
                tag.setVisible(true);
                tag.setSortOrder(0);
                tagRepository.save(tag);
            }
        }
    }

    /** 老版本文章只有 tags 字符串，启动时把这些标签补进标签目录。 */
    @Transactional
    public void syncExistingTags() {
        for (String row : postRepository.allTagsAll()) {
            ensureTags(row);
        }
    }

    private void apply(Tag tag, TagReq req, String name) {
        tag.setName(name);
        String slug = req.slug() == null ? "" : req.slug().trim().toLowerCase(Locale.ROOT);
        tag.setSlug(slug.isEmpty() ? slugify(name) : slug);
        String description = req.description() == null ? "" : req.description().trim();
        if (description.length() > 512) description = description.substring(0, 512);
        tag.setDescription(description);
        String color = req.color() == null ? "" : req.color().trim();
        if (color.isBlank()) color = "#4f86d8";
        if (!COLOR.matcher(color).matches()) {
            throw new BizException("标签颜色需要使用 #RRGGBB 格式");
        }
        tag.setColor(color);
        tag.setSortOrder(req.sortOrder() == null ? 0 : req.sortOrder());
        tag.setVisible(req.visible() == null || req.visible());
    }

    private void replaceInPosts(String oldName, String newName) {
        for (Post post : postRepository.findAll()) {
            String tags = post.getTags();
            if (tags == null || tags.isBlank()) continue;
            List<String> values = Arrays.stream(tags.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
            if (values.stream().noneMatch(v -> v.equalsIgnoreCase(oldName))) continue;
            String next = values.stream()
                    .map(v -> v.equalsIgnoreCase(oldName) ? newName : v)
                    .distinct()
                    .reduce((a, b) -> a + "," + b).orElse(null);
            post.setTags(next);
            post.setUpdatedAt(LocalDateTime.now());
            postRepository.save(post);
        }
    }

    private void removeFromPosts(String name) {
        for (Post post : postRepository.findAll()) {
            String tags = post.getTags();
            if (tags == null || tags.isBlank()) continue;
            List<String> values = Arrays.stream(tags.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
            if (values.stream().noneMatch(v -> v.equalsIgnoreCase(name))) continue;
            String next = values.stream()
                    .filter(v -> !v.equalsIgnoreCase(name))
                    .distinct()
                    .reduce((a, b) -> a + "," + b).orElse(null);
            post.setTags(next);
            post.setUpdatedAt(LocalDateTime.now());
            postRepository.save(post);
        }
    }

    private Map<String, Long> counts(List<String> rows) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String row : rows) {
            for (String raw : row.split(",")) {
                String name = raw.trim();
                if (!name.isEmpty()) {
                    counts.merge(name.toLowerCase(Locale.ROOT), 1L, Long::sum);
                }
            }
        }
        return counts;
    }

    private TagAdminView toAdminView(Tag t, long count) {
        return new TagAdminView(t.getId(), t.getName(), t.getSlug(), t.getDescription(), t.getColor(),
                t.getSortOrder(), t.isVisible(), count, t.getCreatedAt(), t.getUpdatedAt());
    }

    private String normalizeName(String raw) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty() || name.length() > 40) {
            throw new BizException("标签名称需为 1-40 个字符");
        }
        if (name.contains(",")) {
            throw new BizException("标签名称不能包含英文逗号");
        }
        return name;
    }

    private String slugify(String name) {
        return name.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9\\u4e00-\\u9fa5]+", "-")
                .replaceAll("^-|-$", "");
    }
}
