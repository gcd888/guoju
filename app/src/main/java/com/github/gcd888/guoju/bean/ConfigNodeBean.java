package com.github.gcd888.guoju.bean;

/** 配置弹窗树节点：配置>仓库>线路 三级通用。 */
public class ConfigNodeBean {
    public static final int TYPE_CONFIG = 0;    // 一级：用户推送的配置条目
    public static final int TYPE_WAREHOUSE = 1; // 二级：storeHouse[].sourceName
    public static final int TYPE_LINE = 2;      // 三级：urls[].name 或虚拟线路
    public static final int TYPE_TERMINAL = 3;  // sites 终端配置（可激活）

    public String name;
    public String url;
    public int type;
    /** 是否虚拟补全节点（展示/删除逻辑区分） */
    public boolean virtual;

    public ConfigNodeBean() {
    }

    public ConfigNodeBean(String name, String url, int type, boolean virtual) {
        this.name = name;
        this.url = url;
        this.type = type;
        this.virtual = virtual;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ConfigNodeBean that = (ConfigNodeBean) o;
        return url != null ? url.equals(that.url) : that.url == null;
    }

    @Override
    public int hashCode() {
        return url == null ? 0 : url.hashCode();
    }
}
