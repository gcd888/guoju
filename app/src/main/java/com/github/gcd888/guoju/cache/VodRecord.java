package com.github.gcd888.guoju.cache;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

import java.io.Serializable;

/**
 * @author pj567
 * @date :2021/1/7
 * @description:
 */
@Entity(tableName = "vodRecord")
public class VodRecord implements Serializable {
    @PrimaryKey(autoGenerate = true)
    private int id;
    @ColumnInfo(name = "vodId")
    public String vodId;
    @ColumnInfo(name = "updateTime")
    public long updateTime;
    @ColumnInfo(name = "sourceKey")
    public String sourceKey;
    /** 内容分类：0=点播 1=直播 2=短剧 3=音乐 */
    @ColumnInfo(name = "mediaType", defaultValue = "0")
    public int mediaType;
    public String dataJson;

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }
}