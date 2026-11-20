package com.deek.blog;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class BlogApplication {

    public static void main(String[] args) throws Exception {
        // H2 文件库与上传目录都放在工作目录下，首次启动自动创建
        Files.createDirectories(Paths.get("data"));
        Path uploads = Paths.get("uploads");
        Files.createDirectories(uploads);
        SpringApplication.run(BlogApplication.class, args);
    }
}
