package com.livexp.qq;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import de.robv.android.xposed.XposedBridge;

/** 文件日志:同时写 QQ 私有目录和公共 Download,并镜像到 LSPosed 日志。 */
public final class Log {

    private static final String[] DIRS = {
            "/sdcard/Android/data/com.tencent.mobileqq/files/LiveXP",
            "/sdcard/Download/LiveXP",
    };

    private static FileWriter[] writers = new FileWriter[0];
    private static String readyPaths = "";

    private Log() {}

    public static synchronized void init() {
        java.util.ArrayList<FileWriter> ok = new java.util.ArrayList<>();
        StringBuilder paths = new StringBuilder();
        for (String dir : DIRS) {
            try {
                new File(dir).mkdirs();
                ok.add(new FileWriter(dir + "/livexp.log", true));
                if (paths.length() > 0) paths.append(" | ");
                paths.append(dir).append("/livexp.log");
            } catch (Throwable ignored) {
            }
        }
        writers = ok.toArray(new FileWriter[0]);
        readyPaths = paths.toString();
        i("log file ready: " + (readyPaths.isEmpty() ? "无(全部写入失败)" : readyPaths));
    }

    public static synchronized void i(String msg) {
        XposedBridge.log("[" + HookEntry.TAG + "] " + msg);
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
