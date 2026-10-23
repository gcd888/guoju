package com.github.gcd888.guoju.data;

import androidx.annotation.NonNull;
import androidx.room.Entity;

/**
 * 配置路由表：一行 = 一条线路在「类型 × 配置 × 仓库」下的谱系。
 * 类型单值：一条线路含 N 个数据段（sites/lives/dramas/musics）即落 N 行；
 * 复合主键 = 类型 + 配置地址 + 仓库地址 + 线路地址；
 * 重复推送/刷新同一配置地址 → 整组删除重插（幂等，行数恒定）。
 * 「是否配置」不落盘：读取时用线路地址比对四模块生效键
 * （API_URL / LIVE_API_URL / DRAMA_CONFIG_URL / MUSIC_CONFIG_URL），模块键是唯一真源。
 * 仓库/线路为虚拟层时（配置自身即线路 / 线路直接挂配置下）地址=配置地址，名称=「自定义仓库/线路」。
 */
@Entity(tableName = "config_route", primaryKeys = {"type", "configUrl", "warehouseUrl", "lineUrl"})
public class ConfigRoute {
    /** 模块类型：0=点播 1=直播 2=短剧 3=音乐（与 RoomDataManger.MEDIA_TYPE_* 一致）。 */
    public int type;

    @NonNull
    public String configName = "";

    @NonNull
    public String configUrl = "";

    @NonNull
    public String warehouseName = "";

    @NonNull
    public String warehouseUrl = "";

    @NonNull
    public String lineName = "";

    @NonNull
    public String lineUrl = "";

    /** 写入时间（刷新诊断用，非业务键）。 */
    public long updateTime;
}
