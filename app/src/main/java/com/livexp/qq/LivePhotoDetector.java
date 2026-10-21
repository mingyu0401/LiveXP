package com.livexp.qq;

import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;

import java.io.File;
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

    private LivePhotoDetector() {}

    /** 消息里是否含图片元素(每张图 dump 一次 PicElement 字段,便于继续校准)。 */
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
            if (pic == null) continue;
            dumpOnce(pic);
            return true;
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
        java.lang.ref.WeakReference<ViewGroup> rootRef =
                boundRoot == null ? null : new java.lang.ref.WeakReference<>(boundRoot);
        SCAN_EXEC.execute(() -> {
            try {
                File f = new File(path);
                boolean exists = f.isFile();
                long size = f.length();
                boolean live = exists && size >= MIN_DETECT_BYTES && isMotionPhotoFile(path);
                pathVerdict.put(path, live);
                Log.i("live check: " + f.getName() + " exists=" + exists
                        + " size=" + (size / 1024) + "KB -> " + (live ? "LIVE" : "static")
                        + (exists ? " magic=" + magicHex(f) : ""));
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

    private static String str(Object obj, String field) {
        try {
            Object v = XposedHelpers.getObjectField(obj, field);
            return v instanceof String ? (String) v : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
