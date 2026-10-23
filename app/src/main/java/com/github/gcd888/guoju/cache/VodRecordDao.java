package com.github.gcd888.guoju.cache;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

/**
 * @author pj567
 * @date :2021/1/7
 * @description:
 */
@Dao
public interface VodRecordDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insert(VodRecord record);

    @Query("select * from vodRecord order by updateTime desc, id desc limit :size")
    List<VodRecord> getAll(int size);

    @Query("select * from vodRecord where mediaType=:mediaType order by updateTime desc, id desc limit :size")
    List<VodRecord> getAllByType(int mediaType, int size);

    @Query("select count(*) from vodRecord where mediaType=:mediaType")
    int getCountByType(int mediaType);

    @Query("DELETE FROM vodRecord where mediaType=:mediaType and id NOT IN (SELECT id FROM vodRecord where mediaType=:mediaType ORDER BY updateTime desc, id desc LIMIT :size)")
    int reserverByType(int mediaType, int size);

    @Query("select * from vodRecord where `sourceKey`=:sourceKey and `vodId`=:vodId")
    VodRecord getVodRecord(String sourceKey, String vodId);

    @Delete
    int delete(VodRecord record);

    @Query("select count(*) from vodRecord")
    int getCount();

    @Query("DELETE FROM vodRecord")
    void deleteAll();

    @Query("DELETE FROM vodRecord where mediaType=:mediaType")
    void deleteAllByType(int mediaType);

    /**
     * 保留最新指定条数, 其他删除.
     * @param size 保留条数
     * @return
     */
    @Query("DELETE FROM vodRecord where id NOT IN (SELECT id FROM vodRecord ORDER BY updateTime desc, id desc LIMIT :size)")
    int reserver(int size);
}
