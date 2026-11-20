package com.deek.blog.service;

import com.deek.blog.entity.Post;
import com.deek.blog.entity.User;
import com.deek.blog.repository.PostRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 演示文章：仅在文章表为空时写入，用于首次初始化后的空站点。
 */
@Service
public class DemoDataService {

    private final PostRepository postRepository;
    private final TagService tagService;

    public DemoDataService(PostRepository postRepository, TagService tagService) {
        this.postRepository = postRepository;
        this.tagService = tagService;
    }

    @Transactional
    public void seedPosts(User author) {
        if (postRepository.count() > 0) {
            return;
        }

        seedPost(author, "从零搭一个属于自己的博客",
                "为什么在平台写得好好的，还要折腾独立博客？大概是因为，这里的一切都由自己说了算。",
                "随笔,建站",
                """
                我一直觉得，写字这件事需要一个安静的地方。

                ## 为什么要有自己的博客

                平台很好，流量大、排版省心。但它终究不是自己的地方：样式会变，规则会变，某一天甚至连入口都可能消失。独立博客的意义不在于有多少人看，而在于**你拥有这块地**。

                ## 这次搭了什么

                这次用 Spring Boot 3 + Vue 3 搭了一套前后端分离的博客，最后打包成一个 jar：

                - 注册、登录、JWT 鉴权
                - 邮箱找回密码（SMTP）
                - 文章的增删改，Markdown 编辑
                - 评论、点赞、收藏、浏览量
                - 站点信息在线修改，改完即生效

                ```bash
                java -jar blog.jar
                ```

                一条命令就能跑起来。剩下的，就是持续地写。

                ## 一点想法

                工具越简单，越容易坚持。博客不需要多复杂，能让人安静地敲下第一个字就够了。
                """, true);

        seedPost(author, "JWT 无状态登录的几个实践细节",
                "把 token 放进 Authorization 头，只是开始。真正麻烦的是过期、刷新与失效。",
                "Java,Spring Boot,安全",
                """
                这篇记录我在这个博客项目里做登录鉴权时踩过的一些坑。

                ## 为什么选 JWT

                前后端分离之后，服务端不想维护 session。JWT 把身份信息签名后交给客户端保存，服务端只验签，天然适合横向扩展。

                ## 三个容易忽略的点

                1. **密钥长度**。HS256 要求密钥至少 256 bit，也就是 32 字节。短了会直接抛异常。
                2. **过期时间**。太短体验差，太长风险高。个人博客给了 72 小时，够用。
                3. **失效**。JWT 一旦签发，服务端无法单方面作废——除非加一层黑名单或者版本号。

                ```java
                Jwts.builder()
                    .subject(String.valueOf(userId))
                    .issuedAt(new Date())
                    .expiration(exp)
                    .signWith(key)
                    .compact();
                ```

                ## 我的取舍

                这是一个个人博客，不是银行系统。所以没有做 refresh token，也没有做黑名单。token 过期就重新登录，简单直接。

                安全策略要和系统的重要程度匹配，过度设计同样是一种浪费。
                """, false);

        seedPost(author, "写 Markdown 的第三年，我留下的几个习惯",
                "工具是次要的，习惯才是。",
                "写作,效率",
                """
                写了三年 Markdown，整理出几个真正留下来、并且一直在用的习惯。

                ## 一、先写骨架，再补血肉

                动笔之前先用标题把结构列出来：

                ```markdown
                ## 现象
                ## 原因
                ## 解决
                ## 复盘
                ```

                结构清楚了，写的时候就不会跑偏。写作最怕的不是没话说，而是不知道话该放哪里。

                ## 二、一段只说一件事

                长段落是最容易劝退读者的东西。一段三五句，一个意思讲完就换行。

                ## 三、代码块一定要能跑

                贴代码的时候，尽量贴能直接运行的片段，而不是"示意代码"。读者复制走能跑起来，才算真的帮到人。

                ## 四、写完放一夜

                第二天再看，会发现很多当时觉得理所当然、其实没讲清楚的地方。删掉一半，剩下的才更有力量。

                写作是删减的艺术。
                """, false);

        seedPost(author, "关于「坚持」这件小事",
                "不靠意志力，靠系统。",
                "生活,思考",
                """
                去年给自己定了个目标：每周写一篇。到现在写了四十多篇，靠的不是自律。

                ## 把门槛降到最低

                我准备了一个 `drafts` 文件夹，任何时候想到一句就扔进去，不做整理。写的时候从里面挑一条，扩成文章。

                创作最难的是开始的那一下。把开始变得足够便宜，事情就容易持续。

                ## 允许自己写烂

                有几篇我自己都不满意，但还是发了。后来发现，读得最多的恰恰是其中一篇。

                完成比完美重要。这句话听了很多年，真正信了是在自己动手写之后。

                ## 最后

                写作这件事，短期内看不到回报。但三年之后再回头看，那些文字就是时间的形状。
                """, false);
    }

    private void seedPost(User author, String title, String summary, String tags, String content, boolean top) {
        Post post = new Post();
        post.setTitle(title);
        post.setSummary(summary);
        post.setContent(content);
        post.setTags(tags);
        post.setStatus("PUBLISHED");
        post.setTop(top);
        post.setAuthorId(author.getId());
        post.setAuthorName(author.getNickname() == null ? author.getUsername() : author.getNickname());
        post.setAuthorAvatar(author.getAvatar());
        LocalDateTime now = LocalDateTime.now();
        post.setCreatedAt(now.minusDays(postRepository.count() * 3L));
        post.setUpdatedAt(now);
        post.setViews(postRepository.count() * 7 + 12);
        postRepository.save(post);
        tagService.ensureTags(post.getTags());
    }
}
