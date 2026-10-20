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
    private static final byte[] NEEDLE_FTYP = "ftyp".getBytes();

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

    /** 临时校准用:消息里只要有图片元素就返回 true(顺带 dump),不判定实况。 */
    public static boolean hasPicAndDump(Object msgRecord) {
        Object elements;
        try {
            elements = XposedHelpers.getObjectField(msgRecord, "elements");
        } catch (Throwable t) {
            return false;
        }
        if (!(elements instanceof List)) return false;
        for (Object element : (List<?>) elements) {
            if (element == null) continue;
            Object pic;
            try {
                pic = XposedHelpers.getObjectField(element, "picElement");
            } catch (Throwable ignored) {
                continue;
            }
            if (pic == null) continue;
            dumpOnce(pic);
            dumpMsgOnce(msgRecord);
            return true;
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
                    if (indexOf(tail, NEEDLE_SAMSUNG_TRAILER) >= 0) return true;
                    // 苹果实况图:JPEG 内嵌 QuickTime MOV(文件中段出现 ftyp 盒)。流式全文件找。
                    return containsChunked(raf, NEEDLE_FTYP);
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

    private static boolean containsChunked(RandomAccessFile raf, byte[] needle) throws java.io.IOException {
        int chunk = 1024 * 1024;
        long len = raf.length();
        byte[] buf = new byte[chunk];
        long pos = 0;
        while (pos < len) {
            raf.seek(pos);
            int n = raf.read(buf);
            if (n <= 0) break;
            if (indexOf(buf, 0, n, needle) >= 0) return true;
            pos += n - needle.length;
            if (pos < 0) pos = 0;
        }
        return false;
    }

    private static int indexOf(byte[] haystack, int off, int len, byte[] needle) {
        outer:
        for (int i = off; i + needle.length <= off + len; i++) {
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

    /** 每条消息 dump 一次 MsgRecord 全字段,找实况标记可能藏在的 compat 等字段。 */
    private static void dumpMsgOnce(Object msgRecord) {
        String key;
        try {
            key = "msg|" + str(msgRecord, "msgId");
        } catch (Throwable t) {
            return;
        }
        synchronized (dumpedKeys) {
            if (dumpedKeys.size() >= DUMP_CAP || !dumpedKeys.add(key)) return;
        }
        StringBuilder sb = new StringBuilder("[").append(HookEntry.TAG).append("] MsgRecord dump: ");
        for (Class<?> c = msgRecord.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(msgRecord);
                    String s = String.valueOf(v);
                    if (s.length() > 120) s = s.substring(0, 120) + "...";
                    sb.append(f.getName()).append('=').append(s).append("; ");
                } catch (Throwable ignored) {
                }
            }
        }
        Log.i(sb.toString());
    }

    public static volatile boolean bigScanStarted = false;
    private static volatile boolean bigScanDone = false;
    private static final AtomicBoolean bigScanQueued = new AtomicBoolean(false);

    /** 诊断:扫 QQ 数据根下 >5MB 的文件并记录路径,定位接收到的实况原图/视频落盘位置。 */
    public static void diagBigFileScan(Context app) {
        bigScanStarted = true;
        if (!bigScanQueued.compareAndSet(false, true)) return;
        SCAN_EXEC.execute(() -> {
            try {
                diagThumbsProbe(app);
                diagRecentFiles(app);
            } catch (Throwable t) {
                Log.i("big-file scan error: " + t);
            }
        });
    }

    private static final java.util.concurrent.ExecutorService DIAG_EXEC =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private static final AtomicBoolean layoutScanQueued = new AtomicBoolean(false);
    private static final AtomicBoolean chatpicScanQueued = new AtomicBoolean(false);

    /** chatpic 定向搜索:找实况图 md5 对应的落盘文件,并打印目录结构样本。 */
    public static void diagChatpicScan(Context app, String md5) {
        if (md5 == null || md5.length() < 8) return;
        if (!chatpicScanQueued.compareAndSet(false, true)) return;
        DIAG_EXEC.execute(() -> {
            try {
                File chatpic = new File(app.getExternalFilesDir(null).getParentFile(),
                        "Tencent/MobileQQ/chatpic");
                StringBuilder dirs = new StringBuilder("chatpic dirs:");
                listDirs(chatpic, 0, 2, dirs, new int[]{0});
                Log.i(dirs.toString());
                Set<String> prefixes = new HashSet<>();
                prefixes.add(md5.toLowerCase(Locale.ROOT).substring(0, 16));
                Map<String, String> found = new HashMap<>();
                int[] scanned = {0};
                walk(chatpic, 0, prefixes, found, scanned);
                Log.i("chatpic search " + md5.substring(0, 8) + ": scanned=" + scanned[0]
                        + " found=" + found.values());
            } catch (Throwable t) {
                Log.i("chatpic scan error: " + t);
            }
        });
    }

    private static void listDirs(File dir, int depth, int maxDepth, StringBuilder sb, int[] n) {
        if (dir == null || depth > maxDepth || n[0] > 400) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File f : children) {
            if (f.isDirectory()) {
                n[0]++;
                sb.append("\n  ").append(f.getAbsolutePath());
                listDirs(f, depth + 1, maxDepth, sb, n);
            }
        }
    }

    /** 快速布局探针:MobileQQ 下一级每个目录的文件数与总字节,定位图片缓存目录。 */
    public static void diagLayoutScan(Context app) {
        if (!layoutScanQueued.compareAndSet(false, true)) return;
        DIAG_EXEC.execute(() -> {
            try {
                File mobileqq = new File(app.getExternalFilesDir(null).getParentFile(),
                        "Tencent/MobileQQ");
                File[] dirs = mobileqq.listFiles();
                if (dirs == null) {
                    Log.i("layout scan: no dir " + mobileqq);
                    return;
                }
                for (File d : dirs) {
                    long[] stat = new long[2];
                    statTree(d, 0, stat);
                    Log.i("layout scan: " + d.getName() + " files=" + stat[0]
                            + " bytes=" + (stat[1] / 1048576) + "MB");
                }
            } catch (Throwable t) {
                Log.i("layout scan error: " + t);
            }
        });
    }

    private static void statTree(File dir, int depth, long[] stat) {
        if (dir == null || depth > 6 || stat[0] > 30000) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File f : children) {
            if (stat[0] > 30000) return;
            if (f.isDirectory()) {
                statTree(f, depth + 1, stat);
            } else {
                stat[0]++;
                stat[1] += f.length();
            }
        }
    }

    private static void diagThumbsProbe(Context app) {
        File ext = app.getExternalFilesDir(null);
        if (ext == null) return;
        File thumbs = new File(ext.getParentFile(), "Tencent/MobileQQ/shortvideo/thumbs");
        File[] children = thumbs.listFiles();
        if (children == null) {
            Log.i("thumbs probe: no dir " + thumbs);
            return;
        }
        for (File f : children) {
            if (f.length() < 500 * 1024) continue;
            Log.i("thumbs probe: " + f.getName() + " " + (f.length() / 1024) + "KB magic="
                    + magicHex(f) + " live=" + isMotionPhotoFile(f.getAbsolutePath()));
        }
    }

    private static String magicHex(File f) {
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r")) {
            byte[] b = new byte[12];
            int n = raf.read(b);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < n; i++) sb.append(String.format("%02x", b[i]));
            return sb.toString();
        } catch (Throwable t) {
            return "?";
        }
    }

    private static void diagRecentFiles(Context app) {
        List<File> roots = new ArrayList<>();
        File ext = app.getExternalFilesDir(null);
        if (ext != null && ext.getParentFile() != null) {
            roots.add(new File(ext.getParentFile(), "Tencent"));
            roots.add(new File(ext.getParentFile(), "cache"));
            roots.add(ext);
        }
        File filesDir = app.getFilesDir();
        if (filesDir != null) roots.add(filesDir);
        long cutoff = System.currentTimeMillis() - 8L * 3600 * 1000;
        List<String> out = new ArrayList<>();
        int[] scanned = {0};
        for (File r : roots) collectRecent(r, 0, cutoff, out, scanned);
        StringBuilder sb = new StringBuilder("recent-file scan: scanned=").append(scanned[0]).append(" hits=");
        for (String p : out) sb.append("\n  ").append(p);
        Log.i(sb.toString());
    }

    private static void collectRecent(File dir, int depth, long cutoff, List<String> out, int[] scanned) {
        if (dir == null || depth > 8 || scanned[0] > 250000 || out.size() >= 60) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File f : children) {
            if (scanned[0] > 250000 || out.size() >= 60) return;
            scanned[0]++;
            if (f.isDirectory()) {
                collectRecent(f, depth + 1, cutoff, out, scanned);
            } else if (f.length() > 500 * 1024 && f.lastModified() > cutoff) {
                out.add(f.getAbsolutePath() + " (" + (f.length() / 1024) + "KB)");
            }
        }
    }

    private static String str(Object obj, String field) {        try {
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
