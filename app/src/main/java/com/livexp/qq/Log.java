package com.livexp.qq;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import de.robv.android.xposed.XposedBridge;

/** 文件日志:写 QQ 数据目录下模块专属文件夹,并镜像到 LSPosed 日志。 */
public final class Log {

    private static final String DIR = "/sdcard/Android/data/com.tencent.mobileqq/LiveXP";

    private static FileWriter[] writers = new FileWriter[0];
    private static String readyPaths = "";

    private Log() {}

    public static synchronized void init() {
        StringBuilder paths = new StringBuilder();
        FileWriter w = null;
        try {
            new File(DIR).mkdirs();
            w = new FileWriter(DIR + "/livexp.log", true);
            paths.append(DIR).append("/livexp.log");
        } catch (Throwable ignored) {
        }
        writers = w == null ? new FileWriter[0] : new FileWriter[]{w};
        readyPaths = paths.toString();
        i("log file ready: " + (readyPaths.isEmpty() ? "无(全部写入失败)" : readyPaths));
    }

    public static synchronized void i(String msg) {
        try {
            XposedBridge.log("[" + HookEntry.TAG + "] " + msg);
        } catch (Throwable t) {
            // 独立 App 进程里没有 XposedBridge,退回 logcat,方便 adb 验证
            android.util.Log.i("LiveXP", msg);
        }
        String line = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date()) + " " + msg + "\n";
        for (FileWriter w : writers) {
            try {
                w.write(line);
                w.flush();
            } catch (Throwable ignored) {
            }
        }
    }
}
