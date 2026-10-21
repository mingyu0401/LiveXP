package com.livexp.qq;

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.RandomAccessFile;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.robv.android.xposed.XposedHelpers;

/**
 * 实况图片(动态照片)判定。元数据路线(PicElement 字段 dump 校准)已确认没有字段级判据,
 * QQ 接收方自身也不识别实况,故采用文件级判定:
 * 本地文件 >= 1MB 且具备动态照片特征 ——
 *  1. 头部 XMP 标记 MicroVideo / MotionPhoto(Android 动态照片)
 *  2. 尾部三星 MotionPhoto_Data 标记
 *  3. 苹果实况图:JPEG 内嵌 QuickTime MOV(文件中出现 ftyp 盒,流式全文件搜索)
 */
public final class LivePhotoDetector {

    public static final long MIN_DETECT_BYTES = 1024L * 1024L;

    private static final byte[] NEEDLE_MICRO_VIDEO = "MicroVideo".getBytes();
    private static final byte[] NEEDLE_MOTION_PHOTO = "MotionPhoto".getBytes();
    private static final byte[] NEEDLE_SAMSUNG_TRAILER = "MotionPhoto_Data".getBytes();
    private static final byte[] NEEDLE_FTYP = "ftyp".getBytes();

    private static final Set<String> dumpedKeys = ConcurrentHashMap.newKeySet();
    private static final int DUMP_CAP = 300;

    /** 已判定为实况的消息 msgId(字符串化),bind 时据此打标。 */
    public static final Set<String> liveMsgIds = ConcurrentHashMap.newKeySet();
    public static volatile long liveHitCount;
    private static final Map<String, Boolean> pathVerdict = new ConcurrentHashMap<>();

    private static final ExecutorService SCAN_EXEC = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    // md5 持久库:库1=已确认实况,库2=已确认非实况。只在文件真实存在(≥1MB)后记录,
    // 跨重启免重扫;文件还没下载到本地时不产生任何记录,等下次 bind 再判。
    private static final File MD5_LIVE_FILE =
            new File("/sdcard/Android/data/com.tencent.mobileqq/LiveXP/md5_live.txt");
    private static final File MD5_STATIC_FILE =
            new File("/sdcard/Android/data/com.tencent.mobileqq/LiveXP/md5_static.txt");
    private static final Set<String> md5Live = ConcurrentHashMap.newKeySet();
    private static final Set<String> md5Static = ConcurrentHashMap.newKeySet();
    private static volatile boolean md5Loaded;
    private static final Object MD5_LOCK = new Object();

    private LivePhotoDetector() {}

    /** 消息里是否含图片/文件元素(每张图 dump 一次字段,便于继续校准)。群文件大图以 FileMsgItem+fileElement 出现。 */
    public static boolean hasPic(Object msgRecord) {
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
            if (pic != null) {
                dumpOnce(pic);
                return true;
            }
            Object file;
            try {
                file = XposedHelpers.getObjectField(element, "fileElement");
            } catch (Throwable ignored) {
                continue;
            }
            if (file != null) {
                dumpFileOnce(file);
                // 只对图片文件打标(压缩包/视频等不扫描不记库),FileElement 用 picWidth/picHeight 区分
                Object w = num(file, "picWidth");
                Object h = num(file, "picHeight");
                if (isPositive(w) || isPositive(h)) return true;
                continue;
            }
        }
        return false;
    }

    public static String msgIdOf(Object msgRecord) {
        try {
            Object id = XposedHelpers.getObjectField(msgRecord, "msgId");
            return id == null ? null : String.valueOf(id);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * bind 时已确认实况的消息登记:msgId -> 本地路径 + 气泡内消息图 View 弱引用。
     * 打开大图查看器时用 intent 里的缩略图矩形匹配气泡,反查当前消息的本地路径
     * (群文件图片打开大图不走 AIOMsgItemApiImpl.getLocalPath,这是唯一可靠回查)。
     */
    public static final class BindAnchor {
        public final String path;
        public final java.lang.ref.WeakReference<View> image;

        BindAnchor(String path, View image) {
            this.path = path;
            this.image = image == null ? null : new java.lang.ref.WeakReference<>(image);
        }
    }

    public static final Map<String, BindAnchor> liveBinds = new ConcurrentHashMap<>();

    public static void noteLiveBind(Object msgRecord, ViewGroup root, Object msgItem) {
        String msgId = msgIdOf(msgRecord);
        if (msgId == null) return;
        String path = elementPathOf(msgRecord);
        if (path == null) path = OpenImageSaver.probeLocalPath(msgItem);
        View img = root == null ? null : BadgeInjector.findLargestImageView(root);
        if (liveBinds.size() >= 40) liveBinds.clear();
        liveBinds.put(msgId, new BindAnchor(path, img));
    }

    /** 从 MsgRecord 的 FileElement.filePath / PicElement.sourcePath 取本地路径,取不到返回 null。 */
    public static String elementPathOf(Object msgRecord) {
        if (msgRecord == null) return null;
        try {
            Object elements = XposedHelpers.getObjectField(msgRecord, "elements");
            if (!(elements instanceof List)) return null;
            for (Object element : (List<?>) elements) {
                if (element == null) continue;
                Object pic;
                try {
                    pic = XposedHelpers.getObjectField(element, "picElement");
                } catch (Throwable ignored) {
                    pic = null;
                }
                if (pic != null) {
                    Object sp = XposedHelpers.getObjectField(pic, "sourcePath");
                    return sp instanceof String && !((String) sp).isEmpty() ? (String) sp : null;
                }
                Object file;
                try {
                    file = XposedHelpers.getObjectField(element, "fileElement");
                } catch (Throwable ignored) {
                    file = null;
                }
                if (file != null) {
                    Object fp = XposedHelpers.getObjectField(file, "filePath");
                    if (fp instanceof String && !((String) fp).isEmpty()) return (String) fp;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * 异步判定一个本地文件是否实况(>=1MB 且含动态照片特征)。
     * 命中后把消息 msgId 记入 liveMsgIds;若气泡还活着,直接在主线程补打标。
     */
    public static void detectAsync(String path, Object msgRecord, ViewGroup boundRoot) {
        if (path == null) return;
        Boolean known = pathVerdict.get(path);
        if (known != null) {
            if (known) onLive(msgRecord, boundRoot);
            return;
        }
        ensureMd5Loaded();
        String md5 = picMd5Of(msgRecord);
        if (md5 != null) {
            if (md5Live.contains(md5)) {
                Log.i("md5 lib1 hit: " + md5);
                onLive(msgRecord, boundRoot);
                return;
            }
            if (md5Static.contains(md5)) {
                Log.i("md5 lib2 hit: " + md5);
                return;
            }
        }
        java.lang.ref.WeakReference<ViewGroup> rootRef =
                boundRoot == null ? null : new java.lang.ref.WeakReference<>(boundRoot);
        SCAN_EXEC.execute(() -> {
            try {
                File f = new File(path);
                boolean exists = f.isFile();
                long size = f.length();
                boolean live = exists && size >= MIN_DETECT_BYTES && isMotionPhotoFile(path);
                // 文件还没下载到本地时不要缓存结论,等原图落盘后 bind 再触发可重新判定
                if (exists) pathVerdict.put(path, live);
                Log.i("live check: " + f.getName() + " exists=" + exists
                        + " size=" + (size / 1024) + "KB -> " + (live ? "LIVE" : "static")
                        + (exists ? " magic=" + magicHex(f) : ""));
                if (exists && size >= MIN_DETECT_BYTES) recordMd5(md5, live);
                if (live) {
                    liveHitCount++;
                    Log.i("live photo detected: " + path);
                    onLive(msgRecord, rootRef == null ? null : rootRef.get());
                }
            } catch (Throwable t) {
                Log.i("detect error: " + t);
            }
        });
    }

    public static int md5LiveCount() {
        ensureMd5Loaded();
        return md5Live.size();
    }

    public static int md5StaticCount() {
        ensureMd5Loaded();
        return md5Static.size();
    }

    /** bind 快速路径:该图 md5 已在库1(实况)则无需扫描直接打标。 */
    public static boolean isKnownLiveMd5(Object msgRecord) {
        ensureMd5Loaded();
        String md5 = picMd5Of(msgRecord);
        return md5 != null && md5Live.contains(md5);
    }

    /** 打开大图时判断是否已确认的实况图(路径判定缓存或 md5 库1)。 */
    public static boolean isKnownLiveOpen(String path, Object msgRecord) {
        Boolean v = path == null ? null : pathVerdict.get(path);
        if (v != null) return v;
        return isKnownLiveMd5(msgRecord);
    }

    /** 从 MsgRecord 的 PicElement(或群文件 FileElement)里取内容 md5(跨重启/跨设备稳定),小写,取不到返回 null。 */
    public static String picMd5Of(Object msgRecord) {
        if (msgRecord == null) return null;
        try {
            Object elements = XposedHelpers.getObjectField(msgRecord, "elements");
            if (!(elements instanceof List)) return null;
            for (Object element : (List<?>) elements) {
                if (element == null) continue;
                Object pic;
                try {
                    pic = XposedHelpers.getObjectField(element, "picElement");
                } catch (Throwable ignored) {
                    pic = null;
                }
                if (pic != null) {
                    Object md5 = XposedHelpers.getObjectField(pic, "md5HexStr");
                    String s = md5 instanceof String ? ((String) md5).trim().toLowerCase(Locale.US) : null;
                    return (s == null || s.isEmpty()) ? null : s;
                }
                Object file;
                try {
                    file = XposedHelpers.getObjectField(element, "fileElement");
                } catch (Throwable ignored) {
                    file = null;
                }
                if (file != null) {
                    Object md5 = XposedHelpers.getObjectField(file, "fileMd5");
                    String s = md5 instanceof String ? ((String) md5).trim().toLowerCase(Locale.US) : null;
                    return (s == null || s.isEmpty()) ? null : s;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void ensureMd5Loaded() {
        if (md5Loaded) return;
        synchronized (MD5_LOCK) {
            if (md5Loaded) return;
            loadMd5File(MD5_LIVE_FILE, md5Live);
            loadMd5File(MD5_STATIC_FILE, md5Static);
            md5Loaded = true;
            Log.i("md5 libs loaded: live=" + md5Live.size() + " static=" + md5Static.size());
        }
    }

    private static void loadMd5File(File f, Set<String> into) {
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = br.readLine()) != null) {
                String m = line.trim().toLowerCase(Locale.US);
                if (m.length() == 32) into.add(m);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void recordMd5(String md5, boolean live) {
        if (md5 == null || md5.length() != 32) return;
        Set<String> set = live ? md5Live : md5Static;
        if (!set.add(md5)) return;
        File f = live ? MD5_LIVE_FILE : MD5_STATIC_FILE;
        synchronized (MD5_LOCK) {
            try {
                File dir = f.getParentFile();
                if (!dir.isDirectory() && !dir.mkdirs()) return;
                try (FileWriter w = new FileWriter(f, true)) {
                    w.write(md5 + "\n");
                }
                Log.i("md5 recorded " + (live ? "LIVE" : "static") + ": " + md5);
            } catch (Throwable t) {
                Log.i("md5 record error: " + t);
            }
        }
    }

    private static String magicHex(File f) {
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            byte[] b = new byte[12];
            int n = raf.read(b);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < n; i++) sb.append(String.format("%02x", b[i]));
            return sb.toString();
        } catch (Throwable t) {
            return "?";
        }
    }

    private static void onLive(Object msgRecord, ViewGroup boundRoot) {
        String msgId = msgRecord == null ? null : msgIdOf(msgRecord);
        if (msgId != null) liveMsgIds.add(msgId);
        if (boundRoot != null) {
            MAIN.post(() -> {
                if (boundRoot.isAttachedToWindow()) BadgeInjector.show(boundRoot);
            });
        }
    }

    public static boolean isLiveFile(String path) {
        try {
            File f = new File(path);
            if (!f.isFile() || f.length() < MIN_DETECT_BYTES) return false;
            return isMotionPhotoFile(path);
        } catch (Throwable ignored) {
            return false;
        }
    }

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

    private static boolean containsChunked(RandomAccessFile raf, byte[] needle) throws java.io.IOException {
        long len = raf.length();
        int chunk = 1024 * 1024;
        byte[] buf = new byte[chunk];
        int overlap = needle.length - 1;
        long filePos = 0;
        int carry = 0;
        while (filePos < len) {
            raf.seek(filePos);
            int n = raf.read(buf, carry, chunk - carry);
            if (n <= 0) break;
            int total = carry + n;
            if (indexOf(buf, 0, total, needle) >= 0) return true;
            carry = Math.min(overlap, total);
            System.arraycopy(buf, total - carry, buf, 0, carry);
            filePos += n;
        }
        return false;
    }

    /** 每张图只在日志里 dump 一次 PicElement 全部字段,继续积累校准数据。 */
    private static void dumpOnce(Object pic) {
        String key;
        try {
            key = str(pic, "md5HexStr") + "|" + str(pic, "fileName") + "|" + str(pic, "summary");
        } catch (Throwable t) {
            return;
        }
        if (!dumpedKeys.add(key) || dumpedKeys.size() > DUMP_CAP) return;
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

    /** 每条文件消息只 dump 一次 FileElement 全部字段,学习群文件图片的可用标识。 */
    private static void dumpFileOnce(Object file) {
        String key;
        try {
            key = "file|" + str(file, "fileName") + "|" + str(file, "fileMd5");
        } catch (Throwable t) {
            return;
        }
        if (!dumpedKeys.add(key) || dumpedKeys.size() > DUMP_CAP) return;
        StringBuilder sb = new StringBuilder("[").append(HookEntry.TAG).append("] FileElement dump: ");
        for (Field f : file.getClass().getFields()) {
            try {
                Object v = f.get(file);
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

    private static Object num(Object obj, String field) {
        try {
            return XposedHelpers.getObjectField(obj, field);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isPositive(Object v) {
        return v instanceof Number && ((Number) v).longValue() > 0;
    }
}
