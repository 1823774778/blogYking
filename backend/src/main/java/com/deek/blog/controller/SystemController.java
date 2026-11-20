package com.deek.blog.controller;

import com.deek.blog.common.ApiResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OTA 版本探针 + 运行状态。替换 jar 重启后，前端可轮询此接口确认新版本已上线。
 */
@RestController
@RequestMapping("/api/system")
public class SystemController {

    private final Instant startedAt = Instant.now();
    private final DataSource dataSource;
    private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;
    private final com.deek.blog.service.OtaService otaService;

    @Value("${app.version:}")
    private String version;

    @Value("${app.build-time:}")
    private String buildTime;

    @Value("${app.redis.enabled:false}")
    private boolean redisEnabled;

    public SystemController(DataSource dataSource, ObjectProvider<StringRedisTemplate> redisTemplateProvider,
                            com.deek.blog.service.OtaService otaService) {
        this.dataSource = dataSource;
        this.redisTemplateProvider = redisTemplateProvider;
        this.otaService = otaService;
    }

    @GetMapping("/version")
    public ApiResponse<Map<String, Object>> version() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("version", version == null || version.isBlank() ? otaService.getCurrentVersion() : version);
        data.put("buildTime", buildTime == null || buildTime.isBlank() ? "dev" : buildTime);
        data.put("instanceId", otaService.getInstanceId());
        data.put("pid", otaService.getPid());
        data.put("startedAt", LocalDateTime.now().minus(Duration.between(startedAt, Instant.now())).toString());
        data.put("uptimeSeconds", Duration.between(startedAt, Instant.now()).getSeconds());
        data.put("java", System.getProperty("java.version"));
        data.put("os", System.getProperty("os.name"));
        return ApiResponse.ok(data);
    }

    @GetMapping("/health")
    public ApiResponse<Map<String, Object>> health() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("database", databaseStatus());
        data.put("redis", redisStatus());
        return ApiResponse.ok(data);
    }

    private String databaseStatus() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.getMetaData().getDatabaseProductName();
        } catch (Exception e) {
            return "error: " + e.getMessage();
        }
    }

    private String redisStatus() {
        if (!redisEnabled) {
            return "disabled";
        }
        StringRedisTemplate template = redisTemplateProvider.getIfAvailable();
        if (template == null || template.getConnectionFactory() == null) {
            return "not configured";
        }
        try {
            RedisConnection connection = template.getConnectionFactory().getConnection();
            try {
                String pong = connection.ping();
                return "ok: " + pong;
            } finally {
                connection.close();
            }
        } catch (Exception e) {
            return "error: " + e.getMessage();
        }
    }
}
