package com.livexp.qq;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.robv.android.xposed.XposedHelpers;

/**
 * 「解析实况」合成视频登记处。
 * 用途有两个:1) 让自己这边的聊天气泡能给这条视频消息贴「实况」角标;
 * 2) 记住临时文件路径,发送后延迟删除 + 下次启动清理超过 1 小时的残留。
 * 所有磁盘读写都在后台线程,主线程只碰内存集合。
 */
public final class LiveSendMarks {

    private static final File DIR =
            new File("/sdcard/Android/data/com.tencent.mobileqq/LiveXP");
    private static final File REGISTRY = new File(DIR, "live_sent.txt");
    /**
     * 已认定过的合成视频消息 msgId。
     * QQ 转码上传完会把 filePath 换成自己的 shortvideo 副本、videoMd5 也换成转码后的值,
     * 单靠路径/md5 认不过第二次启动;msgId 是稳定的,首次认出来就记下来。
     */
    private static final File MSGREG = new File(DIR, "live_msgids.txt");
    /** 残留临时文件的最长存活时间。 */
    private static final long MAX_AGE_MS = 60 * 60 * 1000L;

    private static final Set<String> md5s = ConcurrentHashMap.newKeySet();
    private static final Set<String> paths = ConcurrentHashMap.newKeySet();
    private static final Set<String> msgIds = ConcurrentHashMap.newKeySet();
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static volatile boolean loaded;

    private LiveSendMarks() {}

    public static void loadAsync() {
        if (loaded) return;
        IO.execute(() -> {
            try {
                purgeStale();
                if (REGISTRY.isFile()) {
                    try (BufferedReader br = new BufferedReader(new FileReader(REGISTRY))) {
                        String line;
                        while ((line = br.readLine()) != null) {
                            String[] parts = line.split("\t");
                            if (parts.length >= 2 && !parts[0].isEmpty()) {
                                if (new File(parts[1]).isFile()) {
                                    md5s.add(parts[0].toLowerCase(java.util.Locale.US));
                                    paths.add(parts[1]);
                                }
                            }
                        }
                    }
                }
                if (MSGREG.isFile()) {
                    try (BufferedReader br = new BufferedReader(new FileReader(MSGREG))) {
                        String line;
                        while ((line = br.readLine()) != null) {
                            line = line.trim();
                            if (!line.isEmpty()) msgIds.add(line);
                        }
                    }
                }
                Log.i("live-sent registry loaded: " + md5s.size() + " 条 / msgId "
                        + msgIds.size() + " 条");
            } catch (Throwable t) {
                Log.i("live-sent registry load failed: " + t);
            }
            loaded = true;
        });
    }

    /** 首次认出这条合成视频消息后,把 msgId 记成永久标记。 */
    public static void rememberMsgId(String msgId) {
        if (msgId == null || msgId.isEmpty() || msgId.equals("null")) return;
        if (!msgIds.add(msgId)) return;
        IO.execute(() -> {
            try {
                if (!DIR.isDirectory() && !DIR.mkdirs()) return;
                try (FileWriter w = new FileWriter(MSGREG, true)) {
                    w.write(msgId + "\n");
                }
            } catch (Throwable t) {
                Log.i("live-msgid 写入失败: " + t);
            }
        });
    }

    /** 记下这条合成视频;发送动作已完成,可以安排延迟删除。 */
    public static void remember(String md5, String path) {
        if (md5 != null && md5.length() == 32) {
            md5s.add(md5.toLowerCase(java.util.Locale.US));
        }
        if (path != null) paths.add(path);
        IO.execute(() -> {
            try {
                if (!DIR.isDirectory() && !DIR.mkdirs()) return;
                try (FileWriter w = new FileWriter(REGISTRY, true)) {
                    w.write((md5 == null ? "-" : md5) + "\t" + path + "\t"
                            + System.currentTimeMillis() + "\n");
                }
            } catch (Throwable t) {
                Log.i("live-sent registry write failed: " + t);
            }
        });
    }

    /** 这条消息是不是本模块合成着发出去的实况视频。 */
    public static boolean isLiveVideoMsg(Object msgRecord) {
        if (msgRecord == null) return false;
        String msgId = LivePhotoDetector.msgIdOf(msgRecord);
        boolean known = msgId != null && msgIds.contains(msgId);
        Object elements;
        try {
            elements = XposedHelpers.getObjectField(msgRecord, "elements");
        } catch (Throwable t) {
            return false;
        }
        if (!(elements instanceof List)) return false;
        for (Object element : (List<?>) elements) {
            if (element == null) continue;
            Object video;
            try {
                video = XposedHelpers.getObjectField(element, "videoElement");
            } catch (Throwable ignored) {
                continue;
            }
            if (video == null) continue;
            String md5 = str(video, "videoMd5");
            if (md5 == null) md5 = str(video, "originVideoMd5");
            boolean hit = known;
            if (!hit && md5 != null && md5s.contains(md5.toLowerCase(java.util.Locale.US))) hit = true;
            String path = str(video, "filePath");
            if (!hit && path != null && paths.contains(path)) hit = true;
            // QQ 转码上传完会把 filePath 换成自己的 shortvideo 副本、md5 也换成转码后的值,
            // 所以认出一次之后把稳定的 msgId 记下来,重启也还认得。
            if (!hit && path != null && path.contains("livexp_")) hit = true;
            if (hit) {
                rememberMsgId(msgId);
                dumpOnce(msgRecord, video);
                Log.i("合成实况视频发送态: md5=" + str(video, "videoMd5")
                        + " status=" + str(video, "transferStatus")
                        + " progress=" + str(video, "progress")
                        + " size=" + str(video, "fileSize")
                        + " path=" + path);
                return true;
            }
        }
        return false;
    }

    private static final Set<String> dumped = java.util.Collections.newSetFromMap(
            new ConcurrentHashMap<String, Boolean>());

    /** 每条合成视频消息记一行 VideoElement 真实字段:时长/方向这类取值只能靠实测校准。 */
    private static void dumpOnce(Object msgRecord, Object video) {
        try {
            String key = String.valueOf(XposedHelpers.getObjectField(msgRecord, "msgId"));
            if (!dumped.add(key)) return;
            StringBuilder sb = new StringBuilder("VideoElement dump(")
                    .append(video.getClass().getName()).append("): ");
            for (java.lang.reflect.Field f : video.getClass().getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())
                        || f.getType() == java.lang.reflect.Field.class) continue;
                String n = f.getName();
                if (n.startsWith("$") || n.startsWith("this") || n.startsWith("CREATOR")) continue;
                f.setAccessible(true);
                Object v;
                try {
                    v = f.get(video);
                } catch (Throwable t) {
                    v = "?";
                }
                if (v instanceof String && ((String) v).length() > 90) {
                    v = ((String) v).substring(0, 90) + "...";
                }
                sb.append(n).append('=').append(v).append("; ");
            }
            Log.i(sb.toString());
        } catch (Throwable ignored) {
        }
    }

    /** 清掉超过 {@link #MAX_AGE_MS} 的临时视频(模块启动时跑一次)。 */
    private static void purgeStale() {
        long now = System.currentTimeMillis();
        int n = purgeDir(LiveComposer.TMP_DIR, now);
        n += purgeDir(storeDir(), now);
        if (n > 0) Log.i("stale compose temp cleaned: " + n + " 个");
        purgeStoreRows(now);
    }

    private static int purgeDir(File dir, long now) {
        try {
            if (dir == null || !dir.isDirectory()) return 0;
            File[] files = dir.listFiles();
            if (files == null) return 0;
            int n = 0;
            for (File f : files) {
                if (f.isFile() && now - f.lastModified() > MAX_AGE_MS) {
                    LiveComposer.deleteQuietly(f);
                    n++;
                }
            }
            return n;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static File storeDir() {
        try {
            return new File(android.os.Environment.getExternalStorageDirectory(),
                    "Movies/LiveXP");
        } catch (Throwable t) {
            return null;
        }
    }

    /** 媒体库里的残留行(进程被杀导致延迟删除没跑)按 RELATIVE_PATH 清掉。 */
    private static int storePurgeTries;

    private static void purgeStoreRows(long now) {
        android.content.Context ctx = HookEntry.appCtx;
        if (ctx == null) {
            if (storePurgeTries++ < 5) {
                new android.os.Handler(android.os.Looper.getMainLooper())
                        .postDelayed(() -> IO.execute(
                                () -> purgeStoreRows(System.currentTimeMillis())), 15_000L);
            }
            return;
        }
        try {
            android.net.Uri ext = android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
            String col = android.provider.MediaStore.MediaColumns.DATA;
            android.database.Cursor c = ctx.getContentResolver().query(ext,
                    new String[]{col}, col + " LIKE '%/LiveXP/%'", null, null);
            if (c == null) return;
            int deleted = 0;
            try {
                while (c.moveToNext()) {
                    String path = c.getString(0);
                    if (path == null) continue;
                    File f = new File(path);
                    if (f.isFile() && now - f.lastModified() > MAX_AGE_MS) {
                        java.util.List<android.net.Uri> hits = new ArrayList<>();
                        android.database.Cursor q = ctx.getContentResolver().query(ext,
                                new String[]{android.provider.MediaStore.MediaColumns._ID},
                                col + "=?", new String[]{path}, null);
                        if (q != null) {
                            try {
                                while (q.moveToNext()) {
                                    hits.add(android.content.ContentUris.withAppendedId(ext,
                                            q.getLong(0)));
                                }
                            } finally {
                                q.close();
                            }
                        }
                        for (android.net.Uri u : hits) {
                            try {
                                ctx.getContentResolver().delete(u, null, null);
                            } catch (Throwable ignored) {
                            }
                        }
                        LiveComposer.deleteQuietly(f);
                        deleted++;
                    }
                }
            } finally {
                c.close();
            }
            if (deleted > 0) Log.i("媒体库残留实况视频已清理: " + deleted + " 行");
        } catch (Throwable t) {
            Log.i("媒体库残留清理失败: " + t);
        }
    }

    private static String str(Object obj, String field) {
        try {
            Object v = XposedHelpers.getObjectField(obj, field);
            return v == null ? null : String.valueOf(v);
        } catch (Throwable t) {
            return null;
        }
    }
}
