package com.github.gcd888.guoju.bean;

/** 一级配置条目（用户推送，持久化于 Hawk CONFIG_LIST）。 */
public class ConfigItem {
    public String name;
    public String url;

    public ConfigItem() {
    }

    public ConfigItem(String name, String url) {
        this.name = name;
        this.url = url;
    }
}
