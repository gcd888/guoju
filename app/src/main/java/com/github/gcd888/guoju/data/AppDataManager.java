package com.github.gcd888.guoju.data;

import androidx.annotation.NonNull;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.sqlite.db.SupportSQLiteDatabase;

import com.github.gcd888.guoju.base.App;
import com.github.gcd888.guoju.util.FileUtils;

import java.io.File;
import java.io.IOException;


/**
 * Room DB 单例管理：实例创建（文件名版本化 guoju.v{N}.db，无迁移脚本，升版本即换新文件）、
 * 备份/恢复（文件拷贝）。历史/收藏等业务读写见 cache.RoomDataManger。
 *
 * @author pj567
 * @since 2020/5/15
 */
public class AppDataManager {
    private static final int DB_FILE_VERSION = 3;
    private static final String DB_NAME = "guoju";
    private static AppDataManager manager;
    private static AppDataBase dbInstance;

    private AppDataManager() {
    }

    public static void init() {
        if (manager == null) {
            synchronized (AppDataManager.class) {
                if (manager == null) {
                    manager = new AppDataManager();
                }
            }
        }
    }

    // 迁移策略：DB 版本走文件名（guoju.v{DB_FILE_VERSION}.db，见 dbPath()），升版本即换新文件。
    // 与 PRD"卸载重装、无迁移脚本"红线自洽；旧的 MIGRATION_1_2~4_5 已随 v2.0 文档重构删除（技术设计文档 §11-D）。

    static String dbPath() {
        return DB_NAME + ".v" + DB_FILE_VERSION + ".db";
    }

    public static AppDataBase get() {
        if (manager == null) {
            throw new RuntimeException("AppDataManager is no init");
        }
        if (dbInstance == null)
            dbInstance = Room.databaseBuilder(App.getInstance(), AppDataBase.class, dbPath())
                    .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
                    .addCallback(new RoomDatabase.Callback() {
                        @Override
                        public void onCreate(@NonNull SupportSQLiteDatabase db) {
                            super.onCreate(db);
//                        LOG.i("数据库第一次创建成功");
                        }

                        @Override
                        public void onOpen(@NonNull SupportSQLiteDatabase db) {
                            super.onOpen(db);
//                        LOG.i("数据库打开成功");
                        }
                    }).allowMainThreadQueries()//可以在主线程操作
                    .build();
        return dbInstance;
    }

    public static boolean backup(File path) throws IOException {
        if (dbInstance != null && dbInstance.isOpen()) {
            dbInstance.close();
        }
        // Room 懒建库：全新安装尚无任何 DB 读写时文件未落盘，先打开一次写连接按 schema 建库再拷贝，
        // 否则全新安装直接备份会命中"DB文件不存在"，连带 Hawk 配置也一并备份失败
        get().getOpenHelper().getWritableDatabase().close();
        File db = App.getInstance().getDatabasePath(dbPath());
        if (db.exists()) {
            FileUtils.copyFile(db, path);
            return true;
        } else {
            return false;
        }
    }

    public static boolean restore(File path) throws IOException {
        if (dbInstance != null && dbInstance.isOpen()) {
            dbInstance.close();
        }
        File db = App.getInstance().getDatabasePath(dbPath());
        if (db.exists()) {
            db.delete();
        }
        if (!db.getParentFile().exists())
            db.getParentFile().mkdirs();
        FileUtils.copyFile(path, db);
        return true;
    }
}
