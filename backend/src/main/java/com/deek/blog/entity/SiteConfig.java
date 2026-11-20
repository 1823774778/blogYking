package com.deek.blog.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "site_config")
public class SiteConfig {

    @Id
    @Column(name = "cfg_key", length = 64)
    private String cfgKey;

    @Column(name = "cfg_value", length = 4000)
    private String cfgValue;

    public SiteConfig() {
    }

    public SiteConfig(String cfgKey, String cfgValue) {
        this.cfgKey = cfgKey;
        this.cfgValue = cfgValue;
    }

    public String getCfgKey() {
        return cfgKey;
    }

    public void setCfgKey(String cfgKey) {
        this.cfgKey = cfgKey;
    }

    public String getCfgValue() {
        return cfgValue;
    }

    public void setCfgValue(String cfgValue) {
        this.cfgValue = cfgValue;
    }
}
