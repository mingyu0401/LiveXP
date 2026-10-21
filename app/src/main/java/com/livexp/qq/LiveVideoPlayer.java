package com.livexp.qq;

import android.app.Activity;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.VideoView;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 实况图点击播放:点开大图查看器时把内嵌视频提取到缓存并全屏播放一次,
 * 播完移除覆盖层回到照片;查看器右上角留圆形重播按钮,播放中覆盖层盖住查看器按钮。
 * 触发链(QQGalleryActivity onResume 后,GalleryHook 调用):
 * 1. OpenImageSaver 在 getLocalPath 回调发现"已确认实况"时记 pending(聊天图片);
 * 2. 兜底:用 intent 的 KEY_THUMBNAL_BOUND 匹配 bind 时登记的实况气泡拿路径
 *    (群文件图片打开大图不走 getLocalPath)。
 */
public final class LiveVideoPlayer {

    private static final long PENDING_FRESH_MS = 8000;
    private static final File CACHE_DIR =
            new File("/sdcard/Android/data/com.tencent.mobileqq/LiveXP/video_cache");

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static volatile String pendingPath;
    private static volatile long pendingAt;
    private static volatile String currentSrc;
    private static volatile Activity galleryRef;
    private static View button;
    private static ViewGroup overlay;

    private LiveVideoPlayer() {}

    /** OpenImageSaver 检测到"打开的是已确认实况图"时调用。 */
    public static void noteLiveOpen(String path) {
        pendingPath = path;
        pendingAt = SystemClock.uptimeMillis();
        Log.i("live open pending: " + path);
    }

    /** GalleryHook 在 QQGalleryActivity onResume 后调用。 */
    public static void onGalleryResumed(Activity activity) {
        try {
            galleryRef = activity;
            String p = pendingPath;
            if (p != null && SystemClock.uptimeMillis() - pendingAt < PENDING_FRESH_MS) {
                pendingPath = null;
                currentSrc = p;
                installButton(activity);
                play(activity, p);
                return;
            }
            Log.i("gallery resumed, no pending");
            String bp = resolveByThumbBound(activity);
            Log.i("thumb-bound resolve: " + bp);
            if (bp != null) {
                currentSrc = bp;
                installButton(activity);
                if (!autoPlayed) {
                    autoPlayed = true;
                    play(activity, bp);
                }
            }
        } catch (Throwable t) {
            Log.i("gallery resume error: " + t);
        }
    }

    /** GalleryHook 在 QQGalleryActivity onDestroy 后调用。 */
    public static void detach() {
        galleryRef = null;
        button = null;
        overlay = null;
        autoPlayed = false;
    }

    private static volatile boolean autoPlayed;

    /**
     * 用查看器 intent 里的 KEY_THUMBNAL_BOUND(被点缩略图屏幕矩形)与 bind 时登记的
     * 实况气泡图矩形匹配,反查当前消息的本地路径;只有一条登记时直接采用。
     */
    private static String resolveByThumbBound(Activity activity) {
        try {
            android.os.Bundle ex = activity.getIntent() == null ? null : activity.getIntent().getExtras();
            android.graphics.Rect bound = ex == null ? null : (android.graphics.Rect) ex.get("KEY_THUMBNAL_BOUND");
            String onlyPath = null;
            int onlyCount = 0;
            if (bound != null) {
                int[] loc = new int[2];
                for (java.util.Map.Entry<String, LivePhotoDetector.BindAnchor> e
                        : LivePhotoDetector.liveBinds.entrySet()) {
                    if (e.getValue().image == null) continue;
                    View img = e.getValue().image.get();
                    if (img == null || img.getWidth() <= 0) continue;
                    img.getLocationOnScreen(loc);
                    int cx = loc[0] + img.getWidth() / 2;
                    int cy = loc[1] + img.getHeight() / 2;
                    if (bound.contains(cx, cy)) {
                        Log.i("thumb-bound matched msgId=" + e.getKey());
                        if (e.getValue().path != null) return e.getValue().path;
                    }
                }
            }
            for (LivePhotoDetector.BindAnchor a : LivePhotoDetector.liveBinds.values()) {
                if (a.path != null) {
                    onlyPath = a.path;
                    onlyCount++;
                }
            }
            return onlyCount == 1 ? onlyPath : null;
        } catch (Throwable t) {
            Log.i("thumb-bound resolve error: " + t);
            return null;
        }
    }

    private static void installButton(final Activity activity) {
        if (button != null && button.getParent() != null) return;
        try {
            ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
            final android.widget.TextView tv = new android.widget.TextView(activity);
            tv.setText("↻");
            tv.setTextColor(0xFFFFFFFF);
            tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 20);
            tv.setGravity(Gravity.CENTER);
            tv.setAlpha(0.85f);
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            bg.setColor(0x66000000);
            tv.setBackground(bg);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.END);
            lp.topMargin = px(activity, 42);
            lp.rightMargin = px(activity, 8);
            decor.addView(tv, lp);
            tv.setOnClickListener(v -> {
                String src = currentSrc;
                if (src != null) play(activity, src);
            });
            button = tv;
            Log.i("replay button installed");
        } catch (Throwable t) {
            Log.i("button install error: " + t);
        }
    }

    private static void play(final Activity activity, final String src) {
        EXEC.execute(() -> {
            try {
                final File vf = extract(src);
                Log.i("video ready: " + vf.getPath() + " " + (vf.length() / 1024) + "KB");
                MAIN.post(() -> startOverlay(activity, vf));
            } catch (Throwable t) {
                Log.i("extract error: " + t);
                MAIN.post(() -> android.widget.Toast.makeText(activity,
                        "实况视频提取失败: " + t.getMessage(), android.widget.Toast.LENGTH_SHORT).show());
            }
        });
    }

    private static void startOverlay(Activity activity, File vf) {
        try {
            removeOverlay();
            Activity act = galleryRef;
            if (act == null || act.isFinishing()) return;
            ViewGroup decor = (ViewGroup) act.getWindow().getDecorView();
            FrameLayout container = new FrameLayout(act);
            container.setBackgroundColor(0xFF000000);
            VideoView video = new VideoView(act);
            FrameLayout.LayoutParams vlp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER);
            container.addView(video, vlp);
            decor.addView(container, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            overlay = container;
            // 全屏覆盖层同时盖住查看器自身的按钮(播放中无关按钮隐藏)
            video.setVideoURI(Uri.fromFile(vf));
            video.setOnCompletionListener(mp -> removeOverlay());
            video.setOnErrorListener((mp, what, extra) -> {
                Log.i("video play error what=" + what + " extra=" + extra);
                removeOverlay();
                return true;
            });
            video.start();
            Log.i("playing overlay video");
        } catch (Throwable t) {
            Log.i("overlay error: " + t);
        }
    }

    private static void removeOverlay() {
        ViewGroup o = overlay;
        overlay = null;
        if (o != null && o.getParent() instanceof ViewGroup) {
            ((ViewGroup) o.getParent()).removeView(o);
        }
    }

    /**
     * 从动态照片 JPEG 里定位内嵌视频并拷到缓存:
     * 1) 旧版 XMP MicroVideoOffset(视频在文件尾,起点=文件大小-offset)
     * 2) 三星尾部 MotionPhoto_Data 标记
     * 3) 文件内首个 ftyp box(OPPO/Google/苹果内嵌)
     */
    static File extract(String src) throws Exception {
        File in = new File(src);
        long size = in.length();
        if (size < 1024) throw new IllegalStateException("source missing");
        long start = -1;
        try (RandomAccessFile raf = new RandomAccessFile(in, "r")) {
            int headLen = (int) Math.min(size, 128 * 1024);
            byte[] head = new byte[headLen];
            raf.readFully(head);
            Matcher m = Pattern.compile("MicroVideoOffset=\"(\\d+)\"")
                    .matcher(new String(head, StandardCharsets.ISO_8859_1));
            if (m.find()) start = size - Long.parseLong(m.group(1));
        }
        if (start <= 0) {
            try (RandomAccessFile raf = new RandomAccessFile(in, "r")) {
                long tailLen = Math.min(size, 4096);
                byte[] tail = new byte[(int) tailLen];
                raf.seek(size - tailLen);
                raf.readFully(tail);
                int idx = indexOf(tail, "MotionPhoto_Data".getBytes(StandardCharsets.ISO_8859_1));
                if (idx >= 0) start = size - tailLen + idx + "MotionPhoto_Data".length();
            }
        }
        if (start <= 0) start = findFtypStart(in, size);
        if (start <= 0 || start >= size - 64) throw new IllegalStateException("no embedded video");
        // 校准:XMP offset 可能指向 box 内容而非 box 头,若 start-8 处是合法 [size][ftyp] 头则前移
        if (start >= 8) {
            try (RandomAccessFile raf = new RandomAccessFile(in, "r")) {
                byte[] probe = new byte[8];
                raf.seek(start - 8);
                raf.readFully(probe);
                if (probe[4] == 'f' && probe[5] == 't' && probe[6] == 'y' && probe[7] == 'p') {
                    long bs = ((probe[0] & 0xFFL) << 24) | ((probe[1] & 0xFFL) << 16)
                            | ((probe[2] & 0xFFL) << 8) | (probe[3] & 0xFFL);
                    if (bs >= 8) {
                        start -= 8;
                        Log.i("video start adjusted to box header: " + start);
                    }
                }
            }
        }
        if (!CACHE_DIR.isDirectory() && !CACHE_DIR.mkdirs()) throw new IllegalStateException("cache dir failed");
        File out = new File(CACHE_DIR, Integer.toHexString(src.hashCode()) + ".mp4");
        if (!(out.isFile() && out.length() == size - start)) {
            try (RandomAccessFile raf = new RandomAccessFile(in, "r");
                 java.io.OutputStream os = new java.io.FileOutputStream(out)) {
                raf.seek(start);
                byte[] buf = new byte[256 * 1024];
                long remain = size - start;
                while (remain > 0) {
                    int n = raf.read(buf, 0, (int) Math.min(buf.length, remain));
                    if (n <= 0) break;
                    os.write(buf, 0, n);
                    remain -= n;
                }
            }
            Log.i("video extracted offset=" + start + " len=" + (size - start));
        }
        return out;
    }

    /** 找第一个合法的 ftyp box(前 4 字节是 box size 且不越界),返回 box 起始偏移。 */
    private static long findFtypStart(File in, long size) throws Exception {
        byte[] needle = "ftyp".getBytes(StandardCharsets.ISO_8859_1);
        long chunk = 1024 * 1024;
        byte[] buf = new byte[(int) chunk];
        long filePos = 0;
        int carry = 0;
        try (RandomAccessFile raf = new RandomAccessFile(in, "r")) {
            while (filePos < size) {
                raf.seek(filePos);
                int n = raf.read(buf, carry, (int) chunk - carry);
                if (n <= 0) break;
                int total = carry + n;
                for (int i = 0; i + 8 <= total; i++) {
                    if (buf[i] == needle[0] && buf[i + 1] == needle[1]
                            && buf[i + 2] == needle[2] && buf[i + 3] == needle[3]) {
                        int boxStart = i - 4;
                        if (boxStart >= 0) {
                            long boxSize = ((buf[boxStart] & 0xFFL) << 24) | ((buf[boxStart + 1] & 0xFFL) << 16)
                                    | ((buf[boxStart + 2] & 0xFFL) << 8) | (buf[boxStart + 3] & 0xFFL);
                            if (boxSize >= 8) {
                                return filePos + boxStart;
                            }
                        }
                    }
                }
                carry = 8;
                System.arraycopy(buf, total - carry, buf, 0, carry);
                filePos += n;
            }
        }
        return -1;
    }

    private static int indexOf(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= hay.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static int px(Activity a, float dp) {
        return (int) (dp * a.getResources().getDisplayMetrics().density + 0.5f);
    }
}
