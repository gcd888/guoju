package com.github.tvbox.osc.util;

import com.github.tvbox.osc.base.App;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 全局崩溃捕获：未捕获异常的堆栈写入应用外部文件目录 crash_时间戳.txt，
 * 便于无法连接 adb 时定位闪退（路径：/Android/data/<包名>/files/）。
 */
public class CrashHandler implements Thread.UncaughtExceptionHandler {

    private static final CrashHandler INSTANCE = new CrashHandler();

    private final Thread.UncaughtExceptionHandler defaultHandler;

    private CrashHandler() {
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
    }

    public static void register() {
        Thread.setDefaultUncaughtExceptionHandler(INSTANCE);
    }

    @Override
    public void uncaughtException(Thread thread, Throwable throwable) {
        try {
            App app = App.getInstance();
            File dir = app.getExternalFilesDir(null);
            if (dir == null) {
                dir = app.getFilesDir();
            }
            String time = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(new Date());
            File file = new File(dir, "crash_" + time + ".txt");
            String version = "";
            try {
                version = app.getPackageManager()
                        .getPackageInfo(app.getPackageName(), 0).versionName;
            } catch (Throwable ignored) {
            }
            PrintWriter writer = new PrintWriter(new FileWriter(file));
            writer.println("thread: " + thread.getName());
            writer.println("time: " + time);
            writer.println("version: " + version);
            writer.println();
            throwable.printStackTrace(writer);
            writer.close();
        } catch (Throwable ignored) {
        }
        if (defaultHandler != null) {
            defaultHandler.uncaughtException(thread, throwable);
        }
    }
}
