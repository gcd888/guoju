package com.github.gcd888.guoju.data;

import androidx.room.Database;
import androidx.room.RoomDatabase;

import com.github.gcd888.guoju.cache.Cache;
import com.github.gcd888.guoju.cache.CacheDao;
import com.github.gcd888.guoju.cache.VodCollect;
import com.github.gcd888.guoju.cache.VodCollectDao;
import com.github.gcd888.guoju.cache.VodRecord;
import com.github.gcd888.guoju.cache.VodRecordDao;


/**
 * 类描述:
 *
 * @author pj567
 * @since 2020/5/15
 */
@Database(entities = {Cache.class, VodRecord.class, VodCollect.class}, version = 2)
public abstract class AppDataBase extends RoomDatabase {
    public abstract CacheDao getCacheDao();

    public abstract VodRecordDao getVodRecordDao();

    public abstract VodCollectDao getVodCollectDao();
}
