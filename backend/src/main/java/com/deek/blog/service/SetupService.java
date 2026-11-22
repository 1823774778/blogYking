package com.deek.blog.service;

import com.deek.blog.common.BizException;
import com.deek.blog.dto.AuthDtos.AuthResp;
import com.deek.blog.dto.SetupDtos.AdminReq;
import com.deek.blog.dto.SetupDtos.ConnResp;
import com.deek.blog.dto.SetupDtos.MysqlReq;
import com.deek.blog.dto.SetupDtos.RedisReq;
import com.deek.blog.dto.SetupDtos.SetupReq;
import com.deek.blog.dto.SetupDtos.SetupStatus;
import com.deek.blog.entity.User;
import com.deek.blog.repository.UserRepository;
import com.deek.blog.security.JwtUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class SetupService {

    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[A-Za-z0-9_]{3,20}$");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+$");
    private static final Pattern DATABASE_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final DemoDataService demoDataService;
    private final DataSource dataSource;

    @Value("${app.setup.config-file:./config/application.yml}")
    private String configFile;

    @Value("${app.site-url:http://localhost:8080}")
    private String siteUrl;

    @Value("${app.redis.enabled:false}")
    private boolean redisEnabled;

    public SetupService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtUtil jwtUtil,
                        DemoDataService demoDataService, DataSource dataSource) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.demoDataService = demoDataService;
        this.dataSource = dataSource;
    }

    /** 只要库里还没有任何账号，就认为站点未初始化（首个账号必为向导创建的管理员） */
    public boolean required() {
        return userRepository.count() == 0;
    }

    public SetupStatus status() {
        return new SetupStatus(required(), databaseLabel(), redisEnabled, configFile, siteUrl);
    }

    @Transactional
    public AuthResp complete(SetupReq req) {
        if (!required()) {
            throw BizException.forbidden("站点已完成初始化，不能重复执行");
        }
        AdminReq adminReq = req == null ? null : req.admin();
        if (adminReq == null) {
            throw new BizException("请填写管理员账号信息");
        }
        // 选用了 MySQL / Redis 时先验证连通性，避免把不可用的配置写进文件
        if (req.mysql() != null && req.mysql().enabled()) {
            testMysql(req.mysql());
        }
        if (req.redis() != null && req.redis().enabled()) {
            testRedis(req.redis());
        }
        String username = adminReq.username() == null ? "" : adminReq.username().trim();
        String email = adminReq.email() == null ? "" : adminReq.email().trim().toLowerCase();
        String password = adminReq.password() == null ? "" : adminReq.password();
        if (!USERNAME_PATTERN.matcher(username).matches()) {
            throw new BizException("管理员用户名需为 3-20 位字母、数字或下划线");
        }
        if (!EMAIL_PATTERN.matcher(email).matches()) {
            throw new BizException("管理员邮箱格式不正确");
        }
        if (password.length() < 6 || password.length() > 64) {
            throw new BizException("管理员密码长度需为 6-64 位");
        }
        if (userRepository.existsByUsername(username)) {
            throw new BizException("用户名已被占用");
        }
        if (userRepository.existsByEmail(email)) {
            throw new BizException("邮箱已被注册");
        }

        User admin = new User();
        admin.setUsername(username);
        admin.setEmail(email);
        admin.setPassword(passwordEncoder.encode(password));
        admin.setNickname(adminReq.nickname() == null || adminReq.nickname().isBlank()
                ? username : adminReq.nickname().trim());
        admin.setRole("ADMIN");
        admin.setEnabled(true);
        admin.setBio("写字的人，偶尔写代码。");
        userRepository.save(admin);

        demoDataService.seedPosts(admin);
        writeConfig(req, admin);

        return new AuthResp(jwtUtil.generate(admin.getId()), AuthService.toBasicView(admin));
    }

    public ConnResp testMysql(MysqlReq req) {
        requireSetupPhase();
        if (req == null || blank(req.host()) || blank(req.database()) || blank(req.username())) {
            throw new BizException("请完整填写 MySQL 地址、数据库名和用户名");
        }
        int port = portOf(req.port(), 3306);
        String database = databaseOf(req.database());
        String url = mysqlUrl(req.host().trim(), port, database);
        try (Connection connection = openMysql(url, req)) {
            return mysqlResult(connection, database, false);
        } catch (SQLException e) {
            if (!isUnknownDatabase(e)) {
                throw new BizException("MySQL 连接失败：" + mysqlError(e));
            }
            createDatabase(req, req.host().trim(), port, database);
            try (Connection connection = openMysql(url, req)) {
                return mysqlResult(connection, database, true);
            } catch (SQLException retryError) {
                throw new BizException("MySQL 数据库创建后连接失败：" + mysqlError(retryError));
            }
        }
    }

    private ConnResp mysqlResult(Connection connection, String database, boolean created) throws SQLException {
        if (!connection.isValid(3)) {
            throw new BizException("MySQL 连接已建立但不可用");
        }
        int tableCount = tableCount(connection, database);
        String state = created
                ? "数据库 " + database + " 不存在，已自动创建"
                : "数据库 " + database + " 已存在";
        if (tableCount > 0) {
            state += "，检测到 " + tableCount + " 张表；重启后会自动更新表结构，已有数据保留";
        } else if (tableCount == 0) {
            state += "，当前没有业务表；重启后会自动建表并写入初始化数据";
        } else {
            state += "；重启后会自动建表或更新表结构";
        }
        return new ConnResp("MySQL 连接成功：" + state);
    }

    private void createDatabase(MysqlReq req, String host, int port, String database) {
        String serverUrl = mysqlServerUrl(host, port);
        String quoted = "`" + database.replace("`", "``") + "`";
        try (Connection connection = openMysql(serverUrl, req);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE DATABASE IF NOT EXISTS " + quoted
                    + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        } catch (SQLException e) {
            throw new BizException("MySQL 数据库 " + database + " 自动创建失败：" + mysqlCreateError(e, database));
        }
    }

    private int tableCount(Connection connection, String database) {
        String sql = "SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema = ? AND table_type = 'BASE TABLE'";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, database);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getInt(1) : -1;
            }
        } catch (SQLException ignored) {
            // 统计失败不应让连接测试失败；只返回未知状态
            return -1;
        }
    }

    private Connection openMysql(String url, MysqlReq req) throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", req.username().trim());
        props.setProperty("password", req.password() == null ? "" : req.password());
        props.setProperty("connectTimeout", "5000");
        props.setProperty("socketTimeout", "10000");
        return DriverManager.getConnection(url, props);
    }

    private boolean isUnknownDatabase(SQLException error) {
        SQLException current = error;
        while (current != null) {
            String message = current.getMessage() == null ? "" : current.getMessage().toLowerCase();
            if (current.getErrorCode() == 1049 || message.contains("unknown database")) {
                return true;
            }
            current = current.getNextException();
        }
        return false;
    }

    public ConnResp testRedis(RedisReq req) {
        requireSetupPhase();
        if (req == null || blank(req.host())) {
            throw new BizException("请填写 Redis 地址");
        }
        int port = portOf(req.port(), 6379);
        int db = req.database() == null ? 0 : Math.max(0, req.database());
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(req.host().trim(), port);
        if (!blank(req.password())) {
            configuration.setPassword(RedisPassword.of(req.password()));
        }
        configuration.setDatabase(db);

        LettuceConnectionFactory factory = new LettuceConnectionFactory(configuration);
        factory.setTimeout(3000);
        try {
            factory.afterPropertiesSet();
            String pong = factory.getConnection().ping();
            if (pong == null || !"PONG".equalsIgnoreCase(pong)) {
                throw new BizException("Redis 返回异常：" + pong);
            }
        } catch (Exception e) {
            throw new BizException("Redis 连接失败：" + redisError(e, req.host().trim(), port));
        } finally {
            try {
                factory.destroy();
            } catch (Exception ignored) {
                // 清理失败不影响结果
            }
        }
        return new ConnResp("Redis 连接成功");
    }

    private void requireSetupPhase() {
        if (!required()) {
            throw BizException.forbidden("站点已完成初始化，连接测试接口已关闭");
        }
    }

    private void writeConfig(SetupReq req, User admin) {
        Map<String, Object> spring = new LinkedHashMap<>();
        boolean useMysql = req.mysql() != null && req.mysql().enabled();
        if (useMysql) {
            MysqlReq mysql = req.mysql();
            if (blank(mysql.host()) || blank(mysql.database()) || blank(mysql.username())) {
                throw new BizException("启用 MySQL 时，地址、数据库名、用户名必须填写");
            }
            String database = databaseOf(mysql.database());
            Map<String, Object> datasource = new LinkedHashMap<>();
            datasource.put("url", mysqlUrl(mysql.host().trim(), portOf(mysql.port(), 3306), database, true));
            datasource.put("username", mysql.username().trim());
            datasource.put("password", mysql.password() == null ? "" : mysql.password());
            datasource.put("driver-class-name", "com.mysql.cj.jdbc.Driver");
            spring.put("datasource", datasource);
            spring.put("jpa", Map.of("hibernate", Map.of("ddl-auto", "update"), "open-in-view", false));
        }

        boolean useRedis = req.redis() != null && req.redis().enabled();
        if (useRedis) {
            RedisReq redis = req.redis();
            if (blank(redis.host())) {
                throw new BizException("启用 Redis 时，地址必须填写");
            }
            Map<String, Object> redisProps = new LinkedHashMap<>();
            redisProps.put("host", redis.host().trim());
            redisProps.put("port", portOf(redis.port(), 6379));
            redisProps.put("password", redis.password() == null ? "" : redis.password());
            redisProps.put("database", redis.database() == null ? 0 : Math.max(0, redis.database()));
            redisProps.put("timeout", "3s");
            spring.put("data", Map.of("redis", redisProps));
        }

        Map<String, Object> app = new LinkedHashMap<>();
        app.put("redis", Map.of("enabled", useRedis));
        Map<String, Object> bootstrap = new LinkedHashMap<>();
        bootstrap.put("admin-username", admin.getUsername());
        bootstrap.put("admin-email", admin.getEmail());
        bootstrap.put("admin-password-hash", admin.getPassword());
        bootstrap.put("admin-nickname", admin.getNickname());
        app.put("bootstrap", bootstrap);

        Map<String, Object> root = new LinkedHashMap<>();
        if (!spring.isEmpty()) {
            root.put("spring", spring);
        }
        root.put("app", app);

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setIndent(2);
        options.setWidth(200);
        options.setSplitLines(false);
        String yaml = """
                # 由首次启动初始化向导生成（%s）
                # 修改后需重启服务生效；删除本文件会回到内置默认配置（H2 + 未启用 Redis），请确认 data/ 里的 H2 库仍可登录
                # MySQL：库不存在时由驱动自动创建（需 CREATE 权限）；已有库按 ddl-auto=update 更新表结构，不清空数据
                # 注意：本文件包含数据库/Redis 密码与管理员密码哈希，请限制为 600 权限
                """.formatted(siteUrl) + new Yaml(options).dump(root);

        Path path = configPath();
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Path temp = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(temp, yaml, StandardCharsets.UTF_8);
            try {
                Set<PosixFilePermission> perms = EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
                Files.setPosixFilePermissions(temp, perms);
            } catch (UnsupportedOperationException ignored) {
                // Windows 等不支持 POSIX 权限的环境跳过
            }
            try {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new BizException(HttpStatus.INTERNAL_SERVER_ERROR, "写入初始化配置失败：" + e.getMessage());
        }
    }

    private String mysqlError(SQLException e) {
        String message = e.getMessage() == null ? "未知错误" : e.getMessage();
        String lower = message.toLowerCase();
        if (lower.contains("communications link failure") || lower.contains("connection refused")) {
            return "无法建立连接，请检查地址、端口和 MySQL 服务是否已启动";
        }
        if (lower.contains("access denied")) {
            return "用户名或密码不正确";
        }
        if (lower.contains("unknown database")) {
            return "数据库不存在，请先在 MySQL 中创建";
        }
        if (lower.contains("timeout") || lower.contains("timed out")) {
            return "连接超时，请检查网络和防火墙";
        }
        return message.lines().findFirst().orElse(message);
    }

    private String redisError(Exception e, String host, int port) {
        String message = e.getMessage() == null ? "未知错误" : e.getMessage();
        String lower = message.toLowerCase();
        if (lower.contains("connection refused") || lower.contains("unable to connect")) {
            return "无法连接 " + host + ":" + port + "，请确认 Redis 已启动";
        }
        if (lower.contains("noauth") || lower.contains("wrongpass") || lower.contains("invalid password")) {
            return "密码不正确，或 Redis 未配置密码";
        }
        if (lower.contains("timeout") || lower.contains("timed out")) {
            return "连接超时，请检查网络和防火墙";
        }
        return message.lines().findFirst().orElse(message);
    }

    private Path configPath() {
        return Paths.get(configFile).toAbsolutePath().normalize();
    }

    private String databaseLabel() {
        try (Connection connection = dataSource.getConnection()) {
            String product = connection.getMetaData().getDatabaseProductName();
            String url = connection.getMetaData().getURL();
            if (url != null && url.startsWith("jdbc:h2")) {
                return product + "（文件库）";
            }
            return product;
        } catch (Exception e) {
            return "未知";
        }
    }

    private String mysqlUrl(String host, int port, String database) {
        return mysqlUrl(host, port, database, false);
    }

    private String mysqlUrl(String host, int port, String database, boolean autoCreate) {
        return "jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
                + "&allowPublicKeyRetrieval=true&useSSL=false"
                + (autoCreate ? "&createDatabaseIfNotExist=true" : "");
    }

    private String mysqlServerUrl(String host, int port) {
        return "jdbc:mysql://" + host + ":" + port + "/"
                + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
                + "&allowPublicKeyRetrieval=true&useSSL=false";
    }

    private String databaseOf(String value) {
        String database = value == null ? "" : value.trim();
        if (!DATABASE_PATTERN.matcher(database).matches()) {
            throw new BizException("数据库名需为 1-64 位字母、数字、下划线或连字符");
        }
        return database;
    }

    private String mysqlCreateError(SQLException e, String database) {
        String message = e.getMessage() == null ? "未知错误" : e.getMessage();
        String lower = message.toLowerCase();
        if (lower.contains("access denied")) {
            return "当前账号没有创建数据库的权限，请手动创建 " + database + " 或授予该账号 CREATE 权限";
        }
        if (lower.contains("timeout") || lower.contains("timed out")) {
            return "连接超时，请检查网络和防火墙";
        }
        return message.lines().findFirst().orElse(message);
    }

    private int portOf(Integer value, int fallback) {
        int port = value == null ? fallback : value;
        if (port < 1 || port > 65535) {
            throw new BizException("端口号需在 1-65535 之间");
        }
        return port;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
