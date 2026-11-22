package com.deek.blog.config;

import com.deek.blog.repository.UserRepository;
import com.deek.blog.security.JwtAuthenticationFilter;
import com.deek.blog.security.JwtUtil;
import com.deek.blog.security.SiteModeFilter;
import com.deek.blog.role.RoleService;
import com.deek.blog.service.SiteConfigService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.nio.charset.StandardCharsets;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtUtil jwtUtil;
    private final UserRepository userRepository;
    private final SiteConfigService siteConfigService;
    private final RoleService roleService;

    public SecurityConfig(JwtUtil jwtUtil, UserRepository userRepository, SiteConfigService siteConfigService,
                          RoleService roleService) {
        this.jwtUtil = jwtUtil;
        this.userRepository = userRepository;
        this.siteConfigService = siteConfigService;
        this.roleService = roleService;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> writeJson(res, 401, "请先登录"))
                        .accessDeniedHandler((req, res, ex) -> writeJson(res, 403, "没有权限执行该操作")))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/captcha", "/api/auth/register", "/api/auth/login", "/api/auth/logout",
                                "/api/auth/email-code", "/api/auth/forgot-password", "/api/auth/reset-password",
                                "/api/meta/**", "/api/system/**", "/api/setup/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/posts/**", "/api/comments/**",
                                "/api/notifications/**", "/api/tags/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/posts/*/ai-overview").permitAll()
                        .requestMatchers("/api/admin/**").hasAuthority("PERM_system:admin")
                        .requestMatchers("/api/**").authenticated()
                        // 其余 GET 请求属于静态资源或前端 history 路由，交还给 SPA 处理
                        .requestMatchers(HttpMethod.GET, "/**").permitAll()
                        .requestMatchers("/uploads/**", "/", "/index.html", "/favicon.svg", "/assets/**").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(new JwtAuthenticationFilter(jwtUtil, userRepository, roleService),
                        UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(new SiteModeFilter(siteConfigService),
                        UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private void writeJson(jakarta.servlet.http.HttpServletResponse res, int status, String message) throws java.io.IOException {
        res.setStatus(status);
        res.setCharacterEncoding(StandardCharsets.UTF_8.name());
        res.setContentType("application/json;charset=UTF-8");
        res.getWriter().write("{\"code\":" + status + ",\"message\":\"" + message + "\",\"data\":null}");
    }
}
