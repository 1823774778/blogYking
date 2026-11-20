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
        if (types.isEmpty()) {
            return; // 表还没建，交给 JPA 创建
        }
        String type = types.get(0);
        if ("longtext".equalsIgnoreCase(type)) {
            return;
        }

        jdbcTemplate.execute("ALTER TABLE posts MODIFY COLUMN content LONGTEXT");
        log.info("MySQL 旧库兼容：posts.content 已从 {} 升级为 LONGTEXT", type);
    }

    private boolean isMysql(String product) {
        return product != null
                && (product.equalsIgnoreCase("MySQL") || product.equalsIgnoreCase("MariaDB"));
    }
}
