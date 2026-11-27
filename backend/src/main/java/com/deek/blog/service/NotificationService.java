package com.deek.blog.service;

import com.deek.blog.common.BizException;
import com.deek.blog.dto.NotificationDtos.NotificationPageView;
import com.deek.blog.dto.NotificationDtos.NotificationReq;
import com.deek.blog.dto.NotificationDtos.NotificationView;
import com.deek.blog.entity.Notification;
import com.deek.blog.repository.NotificationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class NotificationService {

    private static final Set<String> TYPES = Set.of("INFO", "SYSTEM", "UPDATE");

    private final NotificationRepository repository;

    public NotificationService(NotificationRepository repository) {
        this.repository = repository;
    }

    public List<NotificationView> adminList() {
        return repository.findAllByOrderByPinnedDescCreatedAtDesc().stream().map(this::toView).toList();
    }

    public List<NotificationView> publicList(int limit) {
        int size = Math.min(20, Math.max(1, limit));
        return repository.findByPublishedTrueOrderByPinnedDescCreatedAtDesc()
                .stream().limit(size).map(this::toView).toList();
    }

    public NotificationPageView publicPage(int page, int size, String type, String keyword) {
        List<Notification> all = repository.findByPublishedTrueOrderByPinnedDescCreatedAtDesc();
        int pageSize = Math.min(30, Math.max(1, size));
        String typeFilter = type == null ? "" : type.trim().toUpperCase(Locale.ROOT);
        if (!TYPES.contains(typeFilter)) {
            typeFilter = "";
        }
        String query = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        if (query.length() > 80) {
            query = query.substring(0, 80);
        }

        long latestId = all.stream().mapToLong(Notification::getId).max().orElse(0L);
        LocalDateTime latestAt = all.stream().map(Notification::getCreatedAt).max(LocalDateTime::compareTo).orElse(null);
        long pinnedCount = all.stream().filter(Notification::isPinned).count();
        Map<String, Long> typeCounts = new LinkedHashMap<>();
        typeCounts.put("all", (long) all.size());
        typeCounts.put("info", all.stream().filter(n -> "INFO".equals(n.getType())).count());
        typeCounts.put("system", all.stream().filter(n -> "SYSTEM".equals(n.getType())).count());
        typeCounts.put("update", all.stream().filter(n -> "UPDATE".equals(n.getType())).count());

        String finalType = typeFilter;
        String finalQuery = query;
        List<Notification> filtered = all.stream()
                .filter(n -> finalType.isEmpty() || finalType.equals(n.getType()))
                .filter(n -> finalQuery.isEmpty()
                        || String.valueOf(n.getTitle()).toLowerCase(Locale.ROOT).contains(finalQuery)
                        || String.valueOf(n.getContent()).toLowerCase(Locale.ROOT).contains(finalQuery))
                .toList();
        long total = filtered.size();
        int totalPages = total == 0 ? 1 : (int) Math.ceil(total / (double) pageSize);
        int currentPage = Math.min(Math.max(1, page), totalPages);
        int from = Math.min((currentPage - 1) * pageSize, filtered.size());
        int to = Math.min(from + pageSize, filtered.size());
        List<NotificationView> items = filtered.subList(from, to).stream().map(this::toView).toList();
        return new NotificationPageView(items, total, currentPage, pageSize,
                to < total, latestId, latestAt, pinnedCount, typeCounts);
    }

    @Transactional
    public NotificationView create(NotificationReq req) {
        Notification n = new Notification();
        apply(n, req);
        n.setCreatedAt(LocalDateTime.now());
        n.setUpdatedAt(LocalDateTime.now());
        repository.save(n);
        return toView(n);
    }

    @Transactional
    public NotificationView update(Long id, NotificationReq req) {
        Notification n = repository.findById(id)
                .orElseThrow(() -> BizException.notFound("通知不存在"));
        apply(n, req);
        n.setUpdatedAt(LocalDateTime.now());
        repository.save(n);
        return toView(n);
    }

    @Transactional
    public void delete(Long id) {
        Notification n = repository.findById(id)
                .orElseThrow(() -> BizException.notFound("通知不存在"));
        repository.delete(n);
    }

    private void apply(Notification n, NotificationReq req) {
        String title = req.title() == null ? "" : req.title().trim();
        String content = req.content() == null ? "" : req.content().trim();
        if (title.isEmpty() || title.length() > 160) {
            throw new BizException("通知标题不能为空且最多 160 个字符");
        }
        if (content.isEmpty() || content.length() > 2000) {
            throw new BizException("通知内容不能为空且最多 2000 个字符");
        }
        String type = req.type() == null ? "INFO" : req.type().trim().toUpperCase();
        if (!TYPES.contains(type)) {
            throw new BizException("通知类型只能是 INFO、SYSTEM 或 UPDATE");
        }
        n.setTitle(title);
        n.setContent(content);
        n.setType(type);
        n.setPinned(Boolean.TRUE.equals(req.pinned()));
        n.setPublished(req.published() == null || req.published());
    }

    private NotificationView toView(Notification n) {
        return new NotificationView(n.getId(), n.getTitle(), n.getContent(), n.getType(),
                n.isPinned(), n.isPublished(), n.getCreatedAt(), n.getUpdatedAt());
    }
}
