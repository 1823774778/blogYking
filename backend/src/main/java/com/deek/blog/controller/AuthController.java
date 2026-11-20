package com.deek.blog.controller;

import com.deek.blog.common.ApiResponse;
import com.deek.blog.dto.AuthDtos.AuthResp;
import com.deek.blog.dto.AuthDtos.ForgotReq;
import com.deek.blog.dto.AuthDtos.LoginReq;
import com.deek.blog.dto.AuthDtos.MsgResp;
import com.deek.blog.dto.AuthDtos.RegisterReq;
import com.deek.blog.dto.AuthDtos.ResetReq;
import com.deek.blog.dto.AuthDtos.UpdateProfileReq;
import com.deek.blog.dto.AuthDtos.UserView;
import com.deek.blog.security.SecurityUtils;
import com.deek.blog.service.AuthService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ApiResponse<AuthResp> register(@RequestBody RegisterReq req) {
        return ApiResponse.ok(authService.register(req));
    }

    @PostMapping("/login")
    public ApiResponse<AuthResp> login(@RequestBody LoginReq req) {
        return ApiResponse.ok(authService.login(req));
    }

    @GetMapping("/me")
    public ApiResponse<UserView> me() {
        return ApiResponse.ok(authService.me(SecurityUtils.requireUserId()));
    }

    @PostMapping("/forgot-password")
    public ApiResponse<MsgResp> forgot(@RequestBody ForgotReq req) {
        return ApiResponse.ok(authService.forgotPassword(req.email()));
    }

    @PostMapping("/reset-password")
    public ApiResponse<MsgResp> reset(@RequestBody ResetReq req) {
        return ApiResponse.ok(authService.resetPassword(req));
    }

    @PutMapping("/profile")
    public ApiResponse<UserView> updateProfile(@RequestBody UpdateProfileReq req) {
        return ApiResponse.ok(authService.updateProfile(SecurityUtils.requireUserId(), req));
    }
}
