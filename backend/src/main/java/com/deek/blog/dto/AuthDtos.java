package com.deek.blog.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public class AuthDtos {

    public record RegisterReq(String email, String password, String emailCode,
                              String inviteCode, String captchaId, String captchaAnswer) {
    }

    public record EmailCodeReq(String email) {
    }

    public record LoginReq(String account, String password) {
    }

    public record ForgotReq(String email) {
    }

    public record ResetReq(String token, String password) {
    }

    public record UpdateProfileReq(String nickname, String email, String bio, String avatar,
                                   String phone, String gender, LocalDate birthday,
                                   String oldPassword, String newPassword) {
    }

    public record UserView(Long id, String username, String email, String nickname, String avatar,
                            String bio, String role, String roleName, List<String> permissions,
                            String phone, String gender, LocalDate birthday,
                            boolean enabled, LocalDateTime mutedUntil,
                            String muteReason, boolean muted, LocalDateTime createdAt) {
    }

    public record AuthResp(String token, UserView user) {
    }

    public record MsgResp(String message, String devLink) {
    }

    public record CaptchaResp(String id, String image, int expiresIn) {
    }

    public record ToggleResp(boolean active, long count) {
    }
}
