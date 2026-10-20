package com.livexp.qq;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;

import java.io.File;
import java.io.RandomAccessFile;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XposedHelpers;

/**
 * 实况图片(动态照片)判定。数据来源是 NT 内核 MsgRecord -> MsgElement -> PicElement。
 * 命中任意一条规则即视为实况:
 *  1. summary 含「实况」或 "live"
 *  2. fileName / sourcePath / originImageUrl 含 livephoto / live_photo
 *  3. picSubType / picSubSubType 命中已知实况取值(靠诊断日志校准)
 *  4. 本地图片文件本身是 Motion Photo(JPEG 内嵌 MP4)
 */
public final class LivePhotoDetector {

    private static final Set<Integer> LIVE_PIC_SUBTYPES = new HashSet<>();
    private static final Set<Integer> LIVE_PIC_SUB_SUBTYPES = new HashSet<>();

    private static final byte[] NEEDLE_MICRO_VIDEO = "MicroVideo".getBytes();
    private static final byte[] NEEDLE_MOTION_PHOTO = "MotionPhoto".getBytes();
    private static final byte[] NEEDLE_SAMSUNG_TRAILER = "MotionPhoto_Data".getBytes();

    private static final Set<String> dumpedKeys = new HashSet<>();
    private static final int DUMP_CAP = 300;

    public static volatile int dumpCount;
    public static volatile long liveHitCount;

    private static final ExecutorService SCAN_EXEC = Executors.newSingleThreadExecutor();
    private static final Map<String, Boolean> fileCheckCache = new ConcurrentHashMap<>();
    private static final Set<String> pendingMd5 = ConcurrentHashMap.newKeySet();
    private static final AtomicBoolean scanQueued = new AtomicBoolean(false);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private LivePhotoDetector() {}

    public static boolean isLive(Object msgRecord) {
        Object elements;
        try {
            elements = XposedHelpers.getObjectField(msgRecord, "elements");
        } catch (Throwable t) {
            return false;
        }
        if (!(elements instanceof ArrayList)) return false;
        for (Object element : (ArrayList<?>) elements) {
            if (element == null) continue;
            Object pic = null;
            try {
                pic = XposedHelpers.getObjectField(element, "picElement");
            } catch (Throwable ignored) {
            }
            if (pic == null) continue;
            dumpOnce(pic);
            if (checkPic(pic)) {
                liveHitCount++;
                return true;
            }
        }
        return false;
    }

    private static boolean checkPic(Object pic) {
        String summary = str(pic, "summary");
        if (summary != null) {
            if (summary.contains("实况")) return true;
            if (summary.toLowerCase(Locale.ROOT).contains("live")) return true;
        }
        for (String f : new String[]{"fileName", "sourcePath", "originImageUrl"}) {
            String v = str(pic, f);
            if (v != null) {
                String l = v.toLowerCase(Locale.ROOT);
                if (l.contains("livephoto") || l.contains("live_photo")) return true;
            }
        }
        Integer subType = intObj(pic, "picSubType");
        if (subType != null && LIVE_PIC_SUBTYPES.contains(subType)) return true;
        Integer subSubType = intObj(pic, "picSubSubType");
        if (subSubType != null && LIVE_PIC_SUB_SUBTYPES.contains(subSubType)) return true;

        String sourcePath = str(pic, "sourcePath");
        if (sourcePath != null && isMotionPhotoFile(sourcePath)) return true;
        Object thumbPath = null;
        try {
            thumbPath = XposedHelpers.getObjectField(pic, "thumbPath");
        } catch (Throwable ignored) {
        }
        if (thumbPath instanceof Map) {
            for (Object v : ((Map<?, ?>) thumbPath).values()) {
                if (v instanceof String && isMotionPhotoFile((String) v)) return true;
            }
        }
        return false;
    }

    /**
     * 判断本地文件是否为 Motion Photo:头部 64KB 内出现 MicroVideo/MotionPhoto 的 XMP 标记,
     * 或文件尾部出现三星的 MotionPhoto_Data 标记。
     */
    static boolean isMotionPhotoFile(String path) {
        try {
            File file = new File(path);
            if (!file.isFile() || file.length() < 1024) return false;
            try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                long len = raf.length();
                int headLen = (int) Math.min(len, 64 * 1024);
                byte[] head = new byte[headLen];
                raf.readFully(head);
                if (indexOf(head, NEEDLE_MICRO_VIDEO) >= 0 || indexOf(head, NEEDLE_MOTION_PHOTO) >= 0) {
                    return true;
                }
                if (headLen >= 2 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8 && len > 64 * 1024) {
                    int tailLen = (int) Math.min(len, NEEDLE_SAMSUNG_TRAILER.length + 16);
                    byte[] tail = new byte[tailLen];
                    raf.seek(len - tailLen);
                    raf.readFully(tail);
                    return indexOf(tail, NEEDLE_SAMSUNG_TRAILER) >= 0;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    /** 每张图只在 Xposed 日志里 dump 一次 PicElement 全部字段,用于校准实况判定字段。 */
    private static void dumpOnce(Object pic) {
        String key;
        try {
            key = str(pic, "md5HexStr") + "|" + str(pic, "fileName") + "|" + str(pic, "summary");
        } catch (Throwable t) {
            return;
        }
        synchronized (dumpedKeys) {
            if (dumpedKeys.size() >= DUMP_CAP || !dumpedKeys.add(key)) return;
            dumpCount = dumpedKeys.size();
        }
        StringBuilder sb = new StringBuilder("[").append(HookEntry.TAG).append("] PicElement dump: ");
        for (Field f : pic.getClass().getFields()) {
            try {
                Object v = f.get(pic);
                String s = String.valueOf(v);
                if (s.length() > 160) s = s.substring(0, 160) + "...";
                sb.append(f.getName()).append('=').append(s).append("; ");
            } catch (Throwable ignored) {
            }
        }
        Log.i(sb.toString());
    }

    private static String str(Object obj, String field) {
        try {
            Object v = XposedHelpers.getObjectField(obj, field);
            return v instanceof String ? (String) v : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Integer intObj(Object obj, String field) {
        try {
            Object v = XposedHelpers.getObjectField(obj, field);
            return v instanceof Integer ? (Integer) v : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 接收方消息的 PicElement.sourcePath 为空,原图只在点开查看后落在 QQ 私有缓存里,
     * 文件名以 md5 命名。QQ 自己的 livephoto 框架也是文件级判定(LivePhotoLocalBean
     * 的 FAILURE_NOT_LIVE/FAILURE_EXTRACT_VIDEO),所以这里按 md5 找缓存原图再验文件。
     */
    public static void scheduleFileCheck(Object msgRecord, ViewGroup root) {
        try {
            Object elements = XposedHelpers.getObjectField(msgRecord, "elements");
            if (!(elements instanceof List)) return;
            List<String> md5s = new ArrayList<>();
            for (Object element : (List<?>) elements) {
                if (element == null) continue;
                Object pic;
                try {
                    pic = XposedHelpers.getObjectField(element, "picElement");
                } catch (Throwable ignored) {
                    continue;
                }
                if (pic == null) continue;
                String md5 = str(pic, "md5HexStr");
                if (md5 != null && md5.length() >= 16) md5s.add(md5.toLowerCase(Locale.ROOT));
            }
            if (md5s.isEmpty()) return;
            java.lang.ref.WeakReference<ViewGroup> rootRef = new java.lang.ref.WeakReference<>(root);
            for (String md5 : md5s) {
                Boolean known = fileCheckCache.get(md5);
                if (known != null) {
                    if (known) MAIN.post(() -> showIfAlive(rootRef));
                    continue;
                }
                pendingMd5.add(md5);
            }
            if (pendingMd5.isEmpty() || !scanQueued.compareAndSet(false, true)) return;
            Context app = root.getContext() != null ? root.getContext().getApplicationContext() : null;
            if (app == null) return;
            SCAN_EXEC.execute(() -> {
                try {
                    while (!pendingMd5.isEmpty()) {
                        scanPending(app, rootRef);
                    }
                } catch (Throwable t) {
                    Log.i("file scan error: " + t);
                } finally {
                    scanQueued.set(false);
                }
            });
        } catch (Throwable t) {
            Log.i("schedule file check error: " + t);
        }
    }

    private static void showIfAlive(java.lang.ref.WeakReference<ViewGroup> rootRef) {
        ViewGroup root = rootRef.get();
        if (root != null && root.isAttachedToWindow()) {
            BadgeInjector.show(root);
        }
    }

    private static void scanPending(Context app, java.lang.ref.WeakReference<ViewGroup> rootRef) {
        List<String> targets = new ArrayList<>(pendingMd5);
        pendingMd5.clear();
        if (targets.isEmpty()) return;

        List<File> roots = new ArrayList<>();
        File ext = app.getExternalFilesDir(null);
        if (ext != null) {
            File extParent = ext.getParentFile();
            if (extParent != null) {
                // 聊天图片缓存固定在这两棵子树里,全量走
                File tencent = new File(extParent, "Tencent");
                if (tencent.isDirectory()) roots.add(tencent);
                File cache = new File(extParent, "cache");
                if (cache.isDirectory()) roots.add(cache);
                roots.add(ext);
            }
            roots.add(extParent);
        }
        File filesDir = app.getFilesDir();
        if (filesDir != null) {
            File[] children = filesDir.listFiles();
            if (children != null) {
                for (File c : children) {
                    if (c.isDirectory() && c.getName().matches("\\d{5,}")) roots.add(c);
                }
            }
        }

        int[] scanned = {0};
        Map<String, String> found = new HashMap<>();
        Set<String> remaining = new HashSet<>(targets);
        for (File r : roots) {
            if (remaining.isEmpty()) break;
            walk(r, 0, remaining, found, scanned);
        }
        StringBuilder sb = new StringBuilder("file scan done: scanned=").append(scanned[0]);
        for (String md5 : targets) {
            String path = found.get(md5);
            boolean live = path != null && isMotionPhotoFile(path);
            fileCheckCache.put(md5, live);
            sb.append("; ").append(md5.substring(0, 8)).append("->")
                    .append(path != null ? (live ? "LIVE " : "static ") : "missing ")
                    .append(path == null ? "" : path);
            if (live) {
                liveHitCount++;
            }
        }
        Log.i(sb.toString());
        for (String md5 : targets) {
            if (Boolean.TRUE.equals(fileCheckCache.get(md5))) MAIN.post(() -> showIfAlive(rootRef));
        }
    }

    /** QQ 缓存文件量巨大,只进媒体相关目录;目录名没有媒体语义但整体文件量小的也进。 */
    private static void walk(File dir, int depth, Set<String> remaining, Map<String, String> found, int[] scanned) {
        if (dir == null || depth > 8 || scanned[0] > 150000 || remaining.isEmpty()) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File f : children) {
            if (scanned[0] > 150000 || remaining.isEmpty()) return;
            scanned[0]++;
            if (f.isDirectory()) {
                walk(f, depth + 1, remaining, found, scanned);
            } else {
                String name = f.getName().toLowerCase(Locale.ROOT);
                for (String md5 : remaining) {
                    if (name.contains(md5) || (md5.length() >= 16 && name.contains(md5.substring(0, 16)))) {
                        found.put(md5, f.getAbsolutePath());
                        remaining.remove(md5);
                        break;
                    }
                }
            }
        }
    }
}
