package com.deek.blog.mapper;

import com.deek.blog.entity.ActivityNotice;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface ActivityNoticeMapper {

    @Insert("""
            INSERT INTO activity_notices
              (type, title, content, link, actor_name, post_id, is_read, created_at)
            VALUES
              (#{type}, #{title}, #{content}, #{link}, #{actorName}, #{postId}, #{isRead}, #{createdAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ActivityNotice notice);

    @Select("SELECT * FROM activity_notices ORDER BY created_at DESC LIMIT #{limit}")
    List<ActivityNotice> recent(@Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM activity_notices WHERE is_read = FALSE")
    long unreadCount();

    @Update("UPDATE activity_notices SET is_read = TRUE WHERE is_read = FALSE")
    int markAllRead();
}
