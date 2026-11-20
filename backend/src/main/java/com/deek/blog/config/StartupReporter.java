package com.deek.blog.config;

import com.deek.blog.dto.SetupDtos.SetupStatus;
import com.deek.blog.service.SetupService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 启动完成后的首次运行提示：把 MySQL / Redis / 管理员账号的配置入口直接打到控制台。
 */
@Component
@Order(10)
public class StartupReporter implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupReporter.class);

    private final SetupService setupService;

    public StartupReporter(SetupService setupService) {
        this.setupService = setupService;
    }

    @Override
    public void run(ApplicationArguments args) {
        SetupStatus status = setupService.status();
        if (status.required()) {
            log.warn("""

                    ============================================================
                      首次启动：站点尚未初始化
                    ------------------------------------------------------------
                      1. 管理员账号：打开 {}/setup 填写用户名 / 邮箱 / 密码
                      2. MySQL      ：向导中填写连接信息，保存后重启生效
                                      （也可以直接加 --spring.profiles.active=mysql）
                      3. Redis      ：可选，用于浏览量去重；向导中填写后重启生效
                      当前数据库    ：{}
                      配置文件      ：{}（初始化完成后自动生成）
                    ============================================================
                    """, status.siteUrl(), status.database(), status.configFile());
        } else {
            log.info("站点已初始化，管理员账号已就绪；数据库：{}，Redis：{}",
                    status.database(), status.redisEnabled() ? "已启用" : "未启用");
        }
    }
}
