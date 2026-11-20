package com.deek.blog.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.util.List;

/**
 * MySQL 旧库兼容：1.2.0 的 @Lob 在 MySQL 上生成 TINYTEXT，
 * Hibernate ddl-auto=update 不会自动改宽，长文章会因字段太短写入失败。
 * 启动时在 SeedData 之前把 posts.content 升级为 LONGTEXT，已有数据保留。
 */
@Component
@Order(0)
public class MysqlSchemaMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MysqlSchemaMigration.class);

    private final JdbcTemplate jdbcTemplate;

    public MysqlSchemaMigration(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        try (Connection connection = jdbcTemplate.getDataSource().getConnection()) {
            String product = connection.getMetaData().getDatabaseProductName();
            if (!isMysql(product)) {
                return;
            }
        }

        List<String> types = jdbcTemplate.queryForList(
                "SELECT data_type FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = 'posts' AND column_name = 'content'",
                String.class);
        if (!types.isEmpty()) {
            String type = types.get(0);
            if (!"longtext".equalsIgnoreCase(type)) {
                jdbcTemplate.execute("ALTER TABLE posts MODIFY COLUMN content LONGTEXT");
                log.info("MySQL 旧库兼容：posts.content 已从 {} 升级为 LONGTEXT", type);
            }
        }

        // 1.8.0 起邮箱即用户名，旧库 username 只有 VARCHAR(32)，放不下长邮箱。
        List<Long> usernameLengths = jdbcTemplate.queryForList(
                "SELECT character_maximum_length FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = 'users' AND column_name = 'username'",
                Long.class);
        if (!usernameLengths.isEmpty() && usernameLengths.get(0) != null && usernameLengths.get(0) < 128) {
            jdbcTemplate.execute("ALTER TABLE users MODIFY COLUMN username VARCHAR(128) NOT NULL");
            log.info("MySQL 旧库兼容：users.username 已从 VARCHAR({}) 升级为 VARCHAR(128)", usernameLengths.get(0));
        }

        // 旧评论表新增 status 字段后，历史行可能是空字符串；这些评论应当继续显示。
        List<String> commentStatusColumns = jdbcTemplate.queryForList(
                "SELECT data_type FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = 'comments' AND column_name = 'status'",
                String.class);
        if (!commentStatusColumns.isEmpty()) {
            int updated = jdbcTemplate.update(
                    "UPDATE comments SET status = 'VISIBLE' WHERE status IS NULL OR status = ''");
            if (updated > 0) {
                log.info("MySQL 旧库兼容：{} 条历史评论状态补为 VISIBLE", updated);
            }
        }
    }

    private boolean isMysql(String product) {
        return product != null
                && (product.equalsIgnoreCase("MySQL") || product.equalsIgnoreCase("MariaDB"));
    }
}
