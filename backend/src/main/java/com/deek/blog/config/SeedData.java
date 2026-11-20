package com.deek.blog.config;

import com.deek.blog.entity.User;
import com.deek.blog.repository.UserRepository;
import com.deek.blog.service.DemoDataService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 空库启动时的管理员兜底：
 * - 初始化向导会写入 app.bootstrap.*（含 bcrypt 哈希），换库重启时用它建管理员；
 * - 没有 bootstrap 配置时什么都不做，把首次初始化交给 /setup 向导。
 */
@Component
@Order(1)
public class SeedData implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedData.class);

    private final UserRepository userRepository;
    private final DemoDataService demoDataService;

    @Value("${app.bootstrap.admin-username:}")
    private String bootstrapUsername;
    @Value("${app.bootstrap.admin-email:}")
    private String bootstrapEmail;
    @Value("${app.bootstrap.admin-password-hash:}")
    private String bootstrapPasswordHash;
    @Value("${app.bootstrap.admin-nickname:}")
    private String bootstrapNickname;

    public SeedData(UserRepository userRepository, DemoDataService demoDataService) {
        this.userRepository = userRepository;
        this.demoDataService = demoDataService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (userRepository.count() > 0) {
            return;
        }
        if (bootstrapUsername.isBlank() || bootstrapEmail.isBlank() || bootstrapPasswordHash.isBlank()) {
            log.info("数据库为空，尚未创建管理员：请打开站点完成首次初始化");
            return;
        }
        User admin = new User();
        admin.setUsername(bootstrapUsername.trim());
        admin.setEmail(bootstrapEmail.trim().toLowerCase());
        admin.setPassword(bootstrapPasswordHash.trim());
        admin.setNickname(bootstrapNickname.isBlank() ? bootstrapUsername.trim() : bootstrapNickname.trim());
        admin.setRole("ADMIN");
        admin.setEnabled(true);
        admin.setBio("写字的人，偶尔写代码。");
        userRepository.save(admin);
        demoDataService.seedPosts(admin);
        log.info("已按初始化配置创建管理员：{}", admin.getUsername());
    }
}
