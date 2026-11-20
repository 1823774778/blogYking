package com.deek.blog.config;

import com.deek.blog.service.storage.StoragePathResolver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;
import java.nio.file.Path;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final StoragePathResolver storagePaths;
    private final String siteUrl;

    public WebConfig(StoragePathResolver storagePaths,
                     @Value("${app.site-url:http://localhost:8080}") String siteUrl) {
        this.storagePaths = storagePaths;
        this.siteUrl = siteUrl.endsWith("/") ? siteUrl.substring(0, siteUrl.length() - 1) : siteUrl;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // Cookie 登录态只允许生产站点域名与本机开发端口，避免任意站点携带凭证读取接口。
        registry.addMapping("/api/**")
                .allowedOrigins(siteUrl, "http://localhost:5173", "http://127.0.0.1:5173",
                        "http://localhost:8080", "http://127.0.0.1:8080")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Path uploadPath = storagePaths.root();
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations(uploadPath.toUri().toString());

        // 静态资源 + history 路由回退：找不到的页面路径统一返回 index.html
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        Resource resource = location.createRelative(resourcePath);
                        if (resource.exists() && resource.isReadable()) {
                            return resource;
                        }
                        if (resourcePath.startsWith("api/") || resourcePath.startsWith("uploads/")
                                || resourcePath.contains(".")) {
                            return null;
                        }
                        Resource index = new ClassPathResource("static/index.html");
                        return index.exists() ? index : null;
                    }
                });
    }
}
