package com.github.gcd888.guoju.cache;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

import java.io.Serializable;

@Entity(tableName = "vodCollect")
public class VodCollect implements Serializable {
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
    @ColumnInfo(name = "name")
    public String name;
    @ColumnInfo(name = "pic")
    public String pic;

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }
}