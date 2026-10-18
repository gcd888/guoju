package com.github.tvbox.osc.bean;

/**
 * 多仓（影视仓 storeHouse 格式）单个仓条目。
 * 层级：仓 > 接口 > 站源；一个仓指向一份 TVBox 配置链接。
 */
public class WarehouseBean {
    /** 仓名，对应 storeHouse[].sourceName */
    public String sourceName;
    /** 该仓的配置链接，对应 storeHouse[].sourceUrl，支持 http(s)/clan */
    public String sourceUrl;

    public WarehouseBean() {
    }

    public WarehouseBean(String sourceName, String sourceUrl) {
        this.sourceName = sourceName;
        this.sourceUrl = sourceUrl;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        WarehouseBean that = (WarehouseBean) o;
        return sourceUrl != null ? sourceUrl.equals(that.sourceUrl) : that.sourceUrl == null;
    }

    @Override
    public int hashCode() {
        return sourceUrl == null ? 0 : sourceUrl.hashCode();
    }
}
