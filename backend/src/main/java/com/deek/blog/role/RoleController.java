package com.deek.blog.role;

import com.deek.blog.common.ApiResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/roles")
public class RoleController {

    public record RoleReq(String code, String name, String description,
                          List<String> permissions, Boolean enabled) {}

    private final RoleService roleService;

    public RoleController(RoleService roleService) {
        this.roleService = roleService;
    }

    @GetMapping
    public ApiResponse<List<RoleService.RoleView>> list() {
        return ApiResponse.ok(roleService.list());
    }

    @GetMapping("/permissions")
    public ApiResponse<List<RoleService.PermissionView>> permissions() {
        return ApiResponse.ok(RoleService.catalog());
    }

    @PostMapping
    public ApiResponse<RoleService.RoleView> create(@RequestBody RoleReq req) {
        return ApiResponse.ok(roleService.create(req.code(), req.name(), req.description(),
                req.permissions(), Boolean.TRUE.equals(req.enabled())));
    }

    @PutMapping("/{id}")
    public ApiResponse<RoleService.RoleView> update(@PathVariable Long id, @RequestBody RoleReq req) {
        return ApiResponse.ok(roleService.update(id, req.code(), req.name(), req.description(),
                req.permissions(), Boolean.TRUE.equals(req.enabled())));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        roleService.delete(id);
        return ApiResponse.ok(null);
    }
}
