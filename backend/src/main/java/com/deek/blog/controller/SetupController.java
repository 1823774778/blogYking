package com.deek.blog.controller;

import com.deek.blog.common.ApiResponse;
import com.deek.blog.dto.AuthDtos.AuthResp;
import com.deek.blog.dto.SetupDtos.ConnResp;
import com.deek.blog.dto.SetupDtos.MysqlReq;
import com.deek.blog.dto.SetupDtos.RedisReq;
import com.deek.blog.dto.SetupDtos.SetupReq;
import com.deek.blog.dto.SetupDtos.SetupStatus;
import com.deek.blog.service.SetupService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 首次启动初始化。除 status 外，所有接口只在「还没有管理员」时可用。
 */
@RestController
@RequestMapping("/api/setup")
public class SetupController {

    private final SetupService setupService;

    public SetupController(SetupService setupService) {
        this.setupService = setupService;
    }

    @GetMapping("/status")
    public ApiResponse<SetupStatus> status() {
        return ApiResponse.ok(setupService.status());
    }

    @PostMapping("/complete")
    public ApiResponse<AuthResp> complete(@RequestBody SetupReq req) {
        return ApiResponse.ok(setupService.complete(req));
    }

    @PostMapping("/test/mysql")
    public ApiResponse<ConnResp> testMysql(@RequestBody MysqlReq req) {
        return ApiResponse.ok(setupService.testMysql(req));
    }

    @PostMapping("/test/redis")
    public ApiResponse<ConnResp> testRedis(@RequestBody RedisReq req) {
        return ApiResponse.ok(setupService.testRedis(req));
    }
}
