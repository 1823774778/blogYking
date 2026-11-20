package com.deek.blog.service;

import com.deek.blog.dto.ActivityNoticeDtos.ActivityNoticeView;
import com.deek.blog.entity.ActivityNotice;
import com.deek.blog.mapper.ActivityNoticeMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ActivityNoticeService {

    private static final Logger log = LoggerFactory.getLogger(ActivityNoticeService.class);

    private final ActivityNoticeMapper mapper;

    public ActivityNoticeService(ActivityNoticeMapper mapper) {
        this.mapper = mapper;
    }

    public Map<String, Object> overview(int limit) {
        int size = Math.min(30, Math.max(1, limit));
        List<ActivityNoticeView> items = mapper.recent(size).stream().map(this::toView).toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", items);
        data.put("unread", mapper.unreadCount());
        return data;
    }

    public long markAllRead() {
        return mapper.markAllRead();
    }

    public void record(String type, String title, String content, String link,
                       String actorName, Long postId) {
        try {
            ActivityNotice notice = new ActivityNotice();
            notice.setType(normalize(type, "SYSTEM", 24));
            notice.setTitle(normalize(title, "站点通知", 160));
            notice.setContent(normalize(content, "有新的互动", 2000));
            notice.setLink(normalize(link, "", 512));
            notice.setActorName(normalize(actorName, "", 64));
            notice.setPostId(postId);
            notice.setRead(false);
            notice.setCreatedAt(LocalDateTime.now());
            mapper.insert(notice);
        } catch (Exception e) {
            // 通知不能反过来打断点赞、收藏或评论主流程。
            log.warn("互动通知写入失败：type={}, title={}, error={}", type, title, e.getMessage());
        }
    }

    private ActivityNoticeView toView(ActivityNotice n) {
        return new ActivityNoticeView(n.getId(), n.getType(), n.getTitle(), n.getContent(),
                n.getLink(), n.getActorName(), n.getPostId(), n.isRead(), n.getCreatedAt());
    }

    private String normalize(String value, String fallback, int max) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty()) {
            return fallback;
        }
        return text.length() > max ? text.substring(0, max) : text;
    }
}
