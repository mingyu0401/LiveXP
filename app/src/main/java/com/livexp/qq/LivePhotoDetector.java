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
        public final String md5;
        public final java.lang.ref.WeakReference<View> image;

        BindAnchor(String path, String md5, View image) {
            this.path = path;
            this.md5 = md5;
            this.image = image == null ? null : new java.lang.ref.WeakReference<>(image);
        }
    }

    public static final Map<String, BindAnchor> liveBinds = new ConcurrentHashMap<>();

    /** 未判定的图片消息 bind 候选:打开大图时缩略图矩形可匹配到它们,后台等原图落盘再判。 */
    public static final Map<String, BindAnchor> candidates = new ConcurrentHashMap<>();

    /**
     * msgId → 最近一次见过的原图路径。查看器的相册条目有时只带 chatthumb 缩略图路径
     * (原图还没下载),缩略图里没有内嵌视频,拿它去提取必失败,得换回这条记忆里的原图。
     */
    private static final Map<String, String> originalByMsg = new ConcurrentHashMap<>();

    /** 缩略图路径:chatthumb 目录或 _hd 后缀。这类文件不能用于视频提取。 */
    public static boolean isThumbPath(String p) {
        return p != null && (p.contains("/chatthumb/") || p.endsWith("_hd"));
    }

    private static void rememberOriginal(String msgId, String path) {
        if (msgId == null || path == null || isThumbPath(path)) return;
        if (originalByMsg.size() >= 300) originalByMsg.clear();
        originalByMsg.put(msgId, path);
    }

    /** 该消息已知的原图路径;文件不在盘上就当不知道,免得又提一次空气。 */
    public static String knownOriginal(String msgId) {
        if (msgId == null) return null;
        String p = originalByMsg.get(msgId);
        if (p == null || !new java.io.File(p).isFile()) return null;
        return p;
    }

    public static void noteLiveBind(Object msgRecord, ViewGroup root, Object msgItem) {
        String msgId = msgIdOf(msgRecord);
        if (msgId == null) return;
        String path = elementPathOf(msgRecord);
        if (path == null) path = OpenImageSaver.probeLocalPath(msgItem);
        rememberOriginal(msgId, path);
        View img = root == null ? null : BadgeInjector.findLargestImageView(root);
        if (liveBinds.size() >= 40) liveBinds.clear();
        if (candidates.size() >= 40) candidates.clear();
        purgeRecycled(liveBinds, img);
        purgeRecycled(candidates, img);
        liveBinds.put(msgId, new BindAnchor(path, picMd5Of(msgRecord), img));
    }

    public static void noteCandidateBind(Object msgRecord, ViewGroup root, String path) {
        String msgId = msgIdOf(msgRecord);
        if (msgId == null || liveMsgIds.contains(msgId)) return;
        rememberOriginal(msgId, path);
        View img = root == null ? null : BadgeInjector.findLargestImageView(root);
        if (candidates.size() >= 40) candidates.clear();
        purgeRecycled(candidates, img);
        candidates.put(msgId, new BindAnchor(path, picMd5Of(msgRecord), img));
    }

    /**
     * 聊天气泡的 ImageView 会被 RecyclerView 回收复用:同一个 View 实例换绑到别的消息后,
     * 旧登记的缩略图矩形就会匹配到当前这一条,反查出别人的路径。登记新锚点前先按实例身份清掉旧账。
     */
    private static void purgeRecycled(Map<String, BindAnchor> map, View img) {
        if (img == null) return;
        map.entrySet().removeIf(e -> {
            BindAnchor a = e.getValue();
            if (a.image == null) return false;
            View v = a.image.get();
            return v == img;
        });
    }

    /** 扫描/轮询确认实况后统一登记:liveMsgIds + 路径缓存 + md5 库1。 */
    public static void confirmLive(String msgId, String path, String md5) {
        if (msgId != null) liveMsgIds.add(msgId);
        if (path != null) pathVerdict.put(path, Boolean.TRUE);
        rememberOriginal(msgId, path);
        recordMd5(md5, true);
        Log.i("live confirmed: " + msgId + " path=" + path);
    }

    /** 扫描确认非实况:记路径缓存 + md5 库2,避免重复扫描。 */
    public static void confirmStatic(String path, String md5) {
        if (path != null) pathVerdict.put(path, Boolean.FALSE);
        recordMd5(md5, false);
    }

    public static boolean isKnownStaticMd5(String md5) {
        ensureMd5Loaded();
        return md5 != null && md5Static.contains(md5);
    }

    /** 从 MsgRecord 的 FileElement.filePath / PicElement.sourcePath 取本地路径,取不到返回 null。 */
    public static String elementPathOf(Object msgRecord) {
        return elementPathOfAt(msgRecord, -1);
    }

    /**
     * 取消息里第 idx 张图的本地路径(-1 表示第一张)。
     * 一条消息可能带多张图,查看器每页对应其中一张,靠 RFWLayerItemMediaInfo._mediaId 的
     * 后缀(如 7773..._1)区分,否则两页都会拿到第一张的路径、播同一个视频。
     */
    public static String elementPathOfAt(Object msgRecord, int idx) {
        if (msgRecord == null) return null;
        try {
            Object elements = XposedHelpers.getObjectField(msgRecord, "elements");
            if (!(elements instanceof List)) return null;
            int seen = 0;
            String first = null;
            for (Object element : (List<?>) elements) {
                if (element == null) continue;
                Object pic;
                try {
                    pic = XposedHelpers.getObjectField(element, "picElement");
                } catch (Throwable ignored) {
                    pic = null;
                }
                String p = null;
                if (pic != null) {
                    Object sp = XposedHelpers.getObjectField(pic, "sourcePath");
                    if (sp instanceof String && !((String) sp).isEmpty()) p = (String) sp;
                } else {
                    Object file;
                    try {
                        file = XposedHelpers.getObjectField(element, "fileElement");
                    } catch (Throwable ignored) {
                        file = null;
                    }
                    if (file != null) {
                        Object fp = XposedHelpers.getObjectField(file, "filePath");
                        if (fp instanceof String && !((String) fp).isEmpty()) p = (String) fp;
                    }
                }
                if (p == null) continue;
                if (first == null) first = p;
                if (seen++ == idx) return p;
            }
            // 指定了下标却找不到(该图原图未落盘)时不要回落到第一张,否则多图消息会串图
            return idx <= 0 ? first : null;
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * 异步判定一个本地文件是否实况(>=1MB 且含动态照片特征)。
     * 命中后把消息 msgId 记入 liveMsgIds;若气泡还活着,直接在主线程补打标。
     */
    public static void detectAsync(String path, Object msgRecord, ViewGroup boundRoot) {
        detectAsyncAt(path, msgRecord, boundRoot, -1);
    }

    /** 同上,但针对多图消息里的第 picIndex 张(-1 = 不区分,按第一张处理)。 */
    public static void detectAsyncAt(String path, Object msgRecord, ViewGroup boundRoot,
                                     final int picIndex) {
        if (path == null) return;
        Boolean known = pathVerdict.get(path);
        if (known != null) {
            if (known) onLiveAt(msgRecord, boundRoot, picIndex);
            return;
        }
        ensureMd5Loaded();
        String md5 = picMd5OfAt(msgRecord, picIndex);
        if (md5 != null) {
            if (md5Live.contains(md5)) {
                Log.i("md5 lib1 hit: " + md5);
                onLiveAt(msgRecord, boundRoot, picIndex);
                return;
            }
            if (md5Static.contains(md5)) {
                Log.i("md5 lib2 hit: " + md5);
                return;
            }
        }
        java.lang.ref.WeakReference<ViewGroup> rootRef =
                boundRoot == null ? null : new java.lang.ref.WeakReference<>(boundRoot);
        final String msgId = msgIdOf(msgRecord);
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
                    Log.i("live photo detected: " + path + (picIndex >= 0 ? " idx=" + picIndex : ""));
                    // 提升候选登记为实况登记,大图查看器缩略图匹配才能命中未点开过的图
                    if (msgId != null) {
                        BindAnchor cand = candidates.remove(msgId);
                        if (cand != null) {
                            if (liveBinds.size() >= 40) liveBinds.clear();
                            liveBinds.put(msgId, cand);
                        }
                    }
                    onLiveAt(msgRecord, rootRef == null ? null : rootRef.get(), picIndex);
                }
            } catch (Throwable t) {
                Log.i("detect error: " + t);
            }
        });
    }

    /** chatraw 原始图片目录:多图消息源路径缺失时按 fileSize+md5 反查原图。 */
    private static final String CHATRAW_DIR =
            "/storage/emulated/0/Android/data/com.tencent.mobileqq/Tencent/MobileQQ/chatpic/chatraw";
    private static final java.util.Map<Long, java.util.List<String>> sizeIndex = new java.util.HashMap<>();
    private static boolean sizeIndexBuilt;
    private static final Map<String, String> metaPathHit = new ConcurrentHashMap<>();
    private static final Set<String> metaPathMiss = ConcurrentHashMap.newKeySet();
    private static final Set<String> metaPending = ConcurrentHashMap.newKeySet();

    /**
     * 多图消息的 PicElement.sourcePath 经常为空(发送后清空/原图未下载),
     * 但 fileSize 与 md5 一定有:按大小筛出候选文件再比对 md5,找到 chatraw 里的原图。
     * 运行在后台 SCAN_EXEC,命中后继续走文件级判定。
     */
    public static void resolvePathByMetaAsync(Object msgRecord, ViewGroup boundRoot, int picIndex) {
        final long size = picFileSizeAt(msgRecord, picIndex);
        final String md5 = picMd5OfAt(msgRecord, picIndex);
        if (size < MIN_DETECT_BYTES || md5 == null) return;
        final String key = size + ":" + md5;
        if (!metaPending.add(key)) return;
        SCAN_EXEC.execute(() -> {
            String path = null;
            try {
                path = findPathByMeta(size, md5);
                Log.i("meta resolve idx=" + picIndex + " size=" + size + " md5=" + md5
                        + " -> " + path);
            } catch (Throwable t) {
                Log.i("meta resolve error: " + t);
            } finally {
                metaPending.remove(key);
            }
            if (path != null) detectAsyncAt(path, msgRecord, boundRoot, picIndex);
        });
    }

    private static String findPathByMeta(long size, String md5) {
        String key = size + ":" + md5;
        String hit = metaPathHit.get(key);
        if (hit != null) return hit;
        if (metaPathMiss.contains(key)) return null;
        buildSizeIndex();
        java.util.List<String> cands;
        synchronized (sizeIndex) {
            java.util.List<String> l = sizeIndex.get(size);
            cands = l == null ? null : new java.util.ArrayList<>(l);
        }
        if (cands != null) {
            for (String p : cands) {
                if (md5.equals(md5OfFile(p))) {
                    metaPathHit.put(key, p);
                    return p;
                }
            }
        }
        metaPathMiss.add(key);
        return null;
    }

    /** chatraw 按文件大小建索引(只收 ≥1MB 的原图),进程内只建一次。 */
    private static void buildSizeIndex() {
        synchronized (sizeIndex) {
            if (sizeIndexBuilt) return;
            sizeIndexBuilt = true;
            collectSizes(new File(CHATRAW_DIR), 0);
            Log.i("chatraw size index: " + sizeIndex.size() + " sizes");
        }
    }

    private static void collectSizes(File dir, int depth) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                if (depth < 2) collectSizes(f, depth + 1);
                continue;
            }
            long len = f.length();
            if (len < MIN_DETECT_BYTES) continue;
            java.util.List<String> l = sizeIndex.get(len);
            if (l == null) {
                l = new java.util.ArrayList<>(2);
                sizeIndex.put(len, l);
            }
            if (l.size() < 8) l.add(f.getAbsolutePath());
        }
    }

    private static String md5OfFile(String path) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(path)) {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] buf = new byte[256 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            byte[] d = md.digest();
            char[] hex = "0123456789abcdef".toCharArray();
            char[] out = new char[d.length * 2];
            for (int i = 0; i < d.length; i++) {
                int v = d[i] & 0xFF;
                out[i * 2] = hex[v >>> 4];
                out[i * 2 + 1] = hex[v & 0xF];
            }
            return new String(out);
        } catch (Throwable t) {
            return null;
        }
    }

    public static int md5LiveCount() {
        ensureMd5Loaded();
        return md5Live.size();
    }

    public static int md5StaticCount() {
        ensureMd5Loaded();
        return md5Static.size();
    }

    /** 路径判定缓存里已确认是实况(多图消息逐张判定用)。 */
    public static boolean isKnownLivePath(String path) {
        return path != null && Boolean.TRUE.equals(pathVerdict.get(path));
    }

    /** 第 idx 张图的 md5 已在库1。 */
    public static boolean isKnownLiveMd5At(Object msgRecord, int idx) {
        ensureMd5Loaded();
        String md5 = picMd5OfAt(msgRecord, idx);
        return md5 != null && md5Live.contains(md5);
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
        return picMd5OfAt(msgRecord, -1);
    }

    /** 第 idx 张图的原始文件大小(-1 表示第一张),与 {@link #elementPathOfAt} 同序。 */
    public static long picFileSizeAt(Object msgRecord, int idx) {
        if (msgRecord == null) return -1;
        try {
            Object elements = XposedHelpers.getObjectField(msgRecord, "elements");
            if (!(elements instanceof List)) return -1;
            int seen = 0;
            long first = -1;
            for (Object element : (List<?>) elements) {
                if (element == null) continue;
                Object pic;
                try {
                    pic = XposedHelpers.getObjectField(element, "picElement");
                } catch (Throwable ignored) {
                    pic = null;
                }
                long size = -1;
                if (pic != null) {
                    Object v = XposedHelpers.getObjectField(pic, "fileSize");
                    if (v instanceof Number) size = ((Number) v).longValue();
                } else {
                    Object file;
                    try {
                        file = XposedHelpers.getObjectField(element, "fileElement");
                    } catch (Throwable ignored) {
                        file = null;
                    }
                    if (file != null) {
                        Object v = XposedHelpers.getObjectField(file, "fileSize");
                        if (v instanceof Number) size = ((Number) v).longValue();
                    }
                }
                if (size <= 0) continue;
                if (first < 0) first = size;
                if (seen++ == idx) return size;
            }
            return idx <= 0 ? first : -1;
        } catch (Throwable ignored) {
        }
        return -1;
    }

    /** 第 idx 张图的 md5(-1 表示第一张),与 {@link #elementPathOfAt} 同序。 */
    public static String picMd5OfAt(Object msgRecord, int idx) {
        if (msgRecord == null) return null;
        try {
            Object elements = XposedHelpers.getObjectField(msgRecord, "elements");
            if (!(elements instanceof List)) return null;
            int seen = 0;
            String first = null;
            for (Object element : (List<?>) elements) {
                if (element == null) continue;
                Object pic;
                try {
                    pic = XposedHelpers.getObjectField(element, "picElement");
                } catch (Throwable ignored) {
                    pic = null;
                }
                String s = null;
                if (pic != null) {
                    Object md5 = XposedHelpers.getObjectField(pic, "md5HexStr");
                    s = md5 instanceof String ? ((String) md5).trim().toLowerCase(Locale.US) : null;
                } else {
                    Object file;
                    try {
                        file = XposedHelpers.getObjectField(element, "fileElement");
                    } catch (Throwable ignored) {
                        file = null;
                    }
                    if (file != null) {
                        Object md5 = XposedHelpers.getObjectField(file, "fileMd5");
                        s = md5 instanceof String ? ((String) md5).trim().toLowerCase(Locale.US) : null;
                    }
                }
                if (s == null || s.isEmpty()) continue;
                if (first == null) first = s;
                if (seen++ == idx) return s;
            }
            return idx <= 0 ? first : null;
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 消息里能拿到本地路径的图片数量(多图消息要按下标区分)。 */
    public static int picCount(Object msgRecord) {
        if (msgRecord == null) return 0;
        try {
            Object elements = XposedHelpers.getObjectField(msgRecord, "elements");
            if (!(elements instanceof List)) return 0;
            int n = 0;
            for (Object element : (List<?>) elements) {
                if (element == null) continue;
                try {
                    if (XposedHelpers.getObjectField(element, "picElement") != null) {
                        n++;
                        continue;
                    }
                } catch (Throwable ignored) {
                }
                try {
                    if (XposedHelpers.getObjectField(element, "fileElement") != null) n++;
                } catch (Throwable ignored) {
                }
            }
            return n;
        } catch (Throwable ignored) {
            return 0;
        }
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

    private static void onLiveAt(Object msgRecord, ViewGroup boundRoot, int picIndex) {
        String msgId = msgRecord == null ? null : msgIdOf(msgRecord);
        if (msgId != null) liveMsgIds.add(msgId);
        if (boundRoot != null) {
            MAIN.post(() -> {
                if (boundRoot.isAttachedToWindow()) BadgeInjector.show(boundRoot, picIndex);
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
