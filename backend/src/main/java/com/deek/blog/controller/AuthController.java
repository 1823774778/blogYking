package com.deek.blog.controller;

import com.deek.blog.common.ApiResponse;
import com.deek.blog.dto.AuthDtos.AuthResp;
import com.deek.blog.dto.AuthDtos.CaptchaResp;
import com.deek.blog.dto.AuthDtos.EmailCodeReq;
import com.deek.blog.dto.AuthDtos.ForgotReq;
import com.deek.blog.dto.AuthDtos.LoginReq;
import com.deek.blog.dto.AuthDtos.MsgResp;
import com.deek.blog.dto.AuthDtos.RegisterReq;
import com.deek.blog.dto.AuthDtos.ResetReq;
import com.deek.blog.dto.AuthDtos.UpdateProfileReq;
import com.deek.blog.dto.AuthDtos.UserView;
import com.deek.blog.security.SecurityUtils;
import com.deek.blog.service.AuthService;
import com.deek.blog.service.CaptchaService;
import com.deek.blog.service.RegistrationCodeService;
import com.deek.blog.security.JwtUtil;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
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
    private final RegistrationCodeService registrationCodeService;
    private final CaptchaService captchaService;
    private final JwtUtil jwtUtil;

    public AuthController(AuthService authService, RegistrationCodeService registrationCodeService,
                         CaptchaService captchaService, JwtUtil jwtUtil) {
        this.authService = authService;
        this.registrationCodeService = registrationCodeService;
        this.captchaService = captchaService;
        this.jwtUtil = jwtUtil;
    }

    @GetMapping("/captcha")
    public ApiResponse<CaptchaResp> captcha() {
        return ApiResponse.ok(captchaService.create());
    }

    @PostMapping("/register")
    public ApiResponse<AuthResp> register(@RequestBody RegisterReq req,
                                          HttpServletRequest request, HttpServletResponse response) {
        AuthResp auth = authService.register(req);
        if (auth.token() != null && !auth.token().isBlank()) {
            writeAuthCookie(request, response, auth.token());
        }
        return ApiResponse.ok(auth);
    }

    @PostMapping("/email-code")
    public ApiResponse<MsgResp> emailCode(@RequestBody EmailCodeReq req) {
        registrationCodeService.send(req.email());
        return ApiResponse.ok(new MsgResp("验证码已发送，请查收邮箱", null));
    }

    @PostMapping("/login")
    public ApiResponse<AuthResp> login(@RequestBody LoginReq req,
                                       HttpServletRequest request, HttpServletResponse response) {
        AuthResp auth = authService.login(req);
        writeAuthCookie(request, response, auth.token());
        return ApiResponse.ok(auth);
    }

    @PostMapping("/logout")
    public ApiResponse<MsgResp> logout(HttpServletRequest request, HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from("blog_token", "")
                .httpOnly(true)
                .secure(request.isSecure())
                .sameSite("Lax")
                .path("/")
                .maxAge(0)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        return ApiResponse.ok(new MsgResp("已退出登录", null));
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

    private void writeAuthCookie(HttpServletRequest request, HttpServletResponse response, String token) {
        ResponseCookie cookie = ResponseCookie.from("blog_token", token)
                .httpOnly(true)
                .secure(request.isSecure())
                .sameSite("Lax")
                .path("/")
                .maxAge(jwtUtil.getExpireHours() * 3600)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
