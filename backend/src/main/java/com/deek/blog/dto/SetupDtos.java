package com.deek.blog.dto;

public class SetupDtos {

    public record AdminReq(String username, String email, String password, String nickname) {
    }

    public record MysqlReq(boolean enabled, String host, Integer port, String database,
                           String username, String password) {
    }

    public record RedisReq(boolean enabled, String host, Integer port, String password, Integer database) {
    }

    public record SetupReq(AdminReq admin, MysqlReq mysql, RedisReq redis) {
    }

    public record SetupStatus(boolean required, String database, boolean redisEnabled,
                              String configFile, String siteUrl) {
    }

    public record ConnResp(String message) {
    }
}
