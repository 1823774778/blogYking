package com.deek.blog.dto;

import java.time.LocalDateTime;

public class AuthDtos {

    public record RegisterReq(String username, String email, String password, String nickname) {
    }

    public record LoginReq(String account, String password) {
    }

    public record ForgotReq(String email) {
    }

    public record ResetReq(String token, String password) {
    }

    public record UpdateProfileReq(String nickname, String bio, String avatar, String oldPassword, String newPassword) {
    }

    public record UserView(Long id, String username, String email, String nickname, String avatar,
                            String bio, String role, boolean enabled, LocalDateTime createdAt) {
    }

    public record AuthResp(String token, UserView user) {
    }

    public record MsgResp(String message, String devLink) {
    }

    public record ToggleResp(boolean active, long count) {
    }
}
