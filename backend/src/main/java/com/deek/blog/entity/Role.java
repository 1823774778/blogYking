package com.deek.blog.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "roles")
public class Role {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 32)
    private String code;

    @Column(nullable = false, length = 64)
    private String name;

    @Column(length = 255)
    private String description;

    /** 逗号分隔的权限编码，例如 system:admin,system:posts */
    @Column(columnDefinition = "TEXT")
    private String permissions;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(nullable = false)
    private boolean builtin = false;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getPermissions() { return permissions; }
    public void setPermissions(String permissions) { this.permissions = permissions; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isBuiltin() { return builtin; }
    public void setBuiltin(boolean builtin) { this.builtin = builtin; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
