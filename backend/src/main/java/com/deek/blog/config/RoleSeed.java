package com.deek.blog.config;

import com.deek.blog.role.RoleService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(0)
public class RoleSeed implements ApplicationRunner {

    private final RoleService roleService;

    public RoleSeed(RoleService roleService) {
        this.roleService = roleService;
    }

    @Override
    public void run(ApplicationArguments args) {
        roleService.ensureDefaults();
    }
}
