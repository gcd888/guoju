package com.github.tvbox.osc.util;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.github.tvbox.osc.base.App;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 调试日志双写：logcat + 本地文件（无需连电脑即可排查）。
 * 文件位置：{cachePath}/guoju_log.txt，超过 512KB 自动清空重写。
 */
public class LogWriter {
    private static final String TAG = "GuoJuHome";
    private static final long MAX_SIZE = 512L * 1024;
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private static final SimpleDateFormat FMT = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.CHINA);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private LogWriter() {
    }

    /** 信息级日志（logcat i + 文件）。 */
    public static void i(String msg) {
        Log.i(TAG, msg);
        write("I", msg);
    }

    /** 警告级日志。 */
    public static void w(String msg) {
        Log.w(TAG, msg);
        write("W", msg);
    }

    /** 错误级日志（含异常堆栈）。 */
    public static void e(String msg, Throwable t) {
        Log.e(TAG, msg, t);
        write("E", msg + (t == null ? "" : " | " + Log.getStackTraceString(t)));
    }

    /** 错误级日志（无异常对象）。 */
    public static void e(String msg) {
        Log.e(TAG, msg);
        write("E", msg);
    }

    private static void write(final String level, final String msg) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                final String line = FMT.format(new Date()) + " " + level + "/" + TAG + ": " + msg + "\n";
                EXEC.execute(new Runnable() {
                    @Override
                    public void run() {
                        FileWriter fw = null;
                        try {
                            // 外部 files 目录（/Android/data/<pkg>/files/），无 root 可见，与 crash 日志同目录
                            File dir = App.getInstance().getExternalFilesDir(null);
                            if (dir == null) dir = App.getInstance().getFilesDir();
                            if (!dir.exists()) dir.mkdirs();
                            File file = new File(dir, "guoju_log.txt");
                            if (file.length() > MAX_SIZE) {
                                file.delete();
                            }
                            fw = new FileWriter(file, true);
                            fw.write(line);
                            fw.flush();
                        } catch (Throwable ignored) {
                        } finally {
                            if (fw != null) {
                                try {
                                    fw.close();
                                } catch (IOException ignored) {
                                }
                            }
                        }
                    }
                });
            }
        });
    }
}
