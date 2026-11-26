package com.deek.blog.config;

import com.deek.blog.entity.Post;
import com.deek.blog.entity.User;
import com.deek.blog.repository.PostRepository;
import com.deek.blog.repository.UserRepository;
import com.deek.blog.service.TagService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 为已有站点补一篇「关于本站」文章，并让 /about 页面直接读取这篇内容。
 * 只在缺少 slug=about 的文章时创建，不覆盖站长后来修改的正文。
 */
@Component
@Order(2)
public class AboutArticleSeed implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AboutArticleSeed.class);
    private static final String SLUG = "about";

    private static final String CONTENT = """
            这里记录我在工程与生活中的一些片段，也记录这套博客本身的生长过程。

            ## 关于墨记

            「墨记」是一个持续迭代的个人博客项目。它不依赖大型内容平台，文章、站点配置和文件都保存在自己的数据库与存储里。目标很简单：把写作、阅读、评论和站点管理放进一套轻量、可自托管的系统。

            ## 这里有什么

            - 文章、标签与分类，支持 Markdown 写作与草稿审核
            - 评论、点赞、收藏与互动通知
            - 站点音乐、歌单与本地音乐
            - 本地、WebDAV 与 OpenList 存储
            - SMTP 邮件、找回密码与账号安全
            - AI 写作与审核助手
            - 可配置的前台导航、站点域名与后台 OTA 更新

            ## 技术栈

            - 后端：Spring Boot、Java 17、Spring Security、JPA
            - 前端：Vue 3、Vite、原生 CSS
            - 数据：默认 H2，也可以切换 MySQL
            - 部署：单 jar 运行，支持 GitHub Release 与后台更新

            ## 为什么写

            把想清楚的事情留给以后的自己，也让每一次改动都有迹可循。这里没有固定的更新频率，只有持续把事情做完整的耐心。

            ## 关于导航

            这个页面既是一篇普通文章，也是顶部「关于」导航的落点。你可以在后台管理它的正文；修改后，关于页会同步更新。
            """;

    private final PostRepository postRepository;
    private final UserRepository userRepository;
    private final TagService tagService;

    public AboutArticleSeed(PostRepository postRepository, UserRepository userRepository, TagService tagService) {
        this.postRepository = postRepository;
        this.userRepository = userRepository;
        this.tagService = tagService;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (postRepository.existsBySlug(SLUG)) {
            return;
        }
        User author = userRepository.findAll().stream()
                .filter(user -> "ADMIN".equalsIgnoreCase(user.getRole()) && user.isEnabled())
                .findFirst()
                .orElse(null);
        if (author == null) {
            log.info("没有启用的管理员，暂不创建关于本站文章");
            return;
        }
        Post post = new Post();
        post.setTitle("关于本站");
        post.setSummary("关于墨记这个博客、它的技术栈、已有功能，以及这里为什么值得持续写下去。");
        post.setContent(CONTENT);
        post.setTags("关于本站,建站");
        post.setCategory("关于");
        post.setSlug(SLUG);
        post.setStatus("PUBLISHED");
        post.setTop(false);
        post.setAllowComment(true);
        post.setAuthorId(author.getId());
        post.setAuthorName(author.getNickname() == null || author.getNickname().isBlank()
                ? author.getUsername() : author.getNickname());
        post.setAuthorAvatar(author.getAvatar());
        LocalDateTime now = LocalDateTime.now();
        post.setCreatedAt(now);
        post.setUpdatedAt(now);
        postRepository.save(post);
        tagService.ensureTags(post.getTags());
        log.info("已创建关于本站文章：/post/{}", post.getId());
    }
}
