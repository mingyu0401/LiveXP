package com.livexp.qq;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;

/**
 * 实况检测大小阈值的单一数据源:文件 ≥ 阈值才进入实况扫描/原图反查。
 * 一行纯字节数(如 1048576)存在模块自有目录,QQ 进程与独立 App 都读写;
 * 缺失/损坏/越界一律回退默认 1MB,不抛异常。
 * 读取走进程内缓存 + lastModified/length 限频 stat:检测热路径不每次碰盘,
 * 文件被改(滑块保存或手改)最迟 1 秒内自动生效,无需重启 QQ。
 */
public final class ThresholdConfig {

    public static final long MIN_BYTES = 100L * 1024L;
    public static final long MAX_BYTES = 10L * 1024L * 1024L;
    public static final long DEFAULT_BYTES = 1024L * 1024L;

    static final File FILE = new File(
            "/sdcard/Android/data/com.tencent.mobileqq/LiveXP/detect_threshold.txt");

    /** stat 限频间隔:检测每次判定都会取值,不能次次碰盘。 */
    private static final long STAT_INTERVAL_MS = 1000;

    private static final Object IO_LOCK = new Object();
    private static volatile long cached = DEFAULT_BYTES;
    private static volatile long cachedMtime = Long.MIN_VALUE;
    private static volatile long cachedLength = -1;
    private static volatile long lastStatMs;
    private static volatile boolean loaded;

    private ThresholdConfig() {}

    public static long clamp(long v) {
        return Math.max(MIN_BYTES, Math.min(MAX_BYTES, v));
    }

    public static long get() {
        long now = System.currentTimeMillis();
        if (loaded && now - lastStatMs < STAT_INTERVAL_MS) return cached;
        synchronized (IO_LOCK) {
            if (loaded && System.currentTimeMillis() - lastStatMs < STAT_INTERVAL_MS) return cached;
            long m = FILE.lastModified();
            long len = FILE.length();
            lastStatMs = now;
            if (loaded && m == cachedMtime && len == cachedLength) return cached;
            cached = resolveValue();
            cachedMtime = m;
            cachedLength = len;
            loaded = true;
            return cached;
        }
    }

    private static long resolveValue() {
        Long v = null;
        try (BufferedReader br = new BufferedReader(new FileReader(FILE))) {
            v = parse(br.readLine());
        } catch (Throwable ignored) {
        }
        // 文件不存在/读不了/内容非法都视为未配置,回退默认,绝不把 0 或负数当阈值
        return v == null ? DEFAULT_BYTES : clamp(v);
    }

    /** 一行纯字节数;非数字、0、负数、越界都算非法。 */
    static Long parse(String line) {
        if (line == null) return null;
        String s = line.trim();
        if (s.isEmpty()) return null;
        try {
            long v = Long.parseLong(s);
            return (v < MIN_BYTES || v > MAX_BYTES) ? null : v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 钳制到 [MIN,MAX] 后落盘并刷新缓存;失败返回 false,缓存不动。 */
    public static boolean set(long bytes) {
        long v = clamp(bytes);
        synchronized (IO_LOCK) {
            try {
                File dir = FILE.getParentFile();
                if (!dir.isDirectory() && !dir.mkdirs()) return false;
                File tmp = new File(dir, FILE.getName() + ".tmp");
                try (FileWriter w = new FileWriter(tmp, false)) {
                    w.write(Long.toString(v));
                }
                if (!tmp.renameTo(FILE)) {
                    try (FileWriter w = new FileWriter(FILE, false)) {
                        w.write(Long.toString(v));
                    }
                }
                cached = v;
                cachedMtime = FILE.lastModified();
                cachedLength = FILE.length();
                lastStatMs = System.currentTimeMillis();
                loaded = true;
                return true;
            } catch (Throwable t) {
                Log.i("threshold set error: " + t);
                return false;
            }
        }
    }
}
