package com.deek.blog.config;

import com.deek.blog.service.TagService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** 把已有文章里的标签补进标签目录，兼容旧数据库。 */
@Component
@Order(2)
public class TagBackfill implements ApplicationRunner {

    private final TagService tagService;

    public TagBackfill(TagService tagService) {
        this.tagService = tagService;
    }

    @Override
    public void run(ApplicationArguments args) {
        tagService.syncExistingTags();
    }
}
