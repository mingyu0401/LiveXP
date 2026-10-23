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
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.robv.android.xposed.XposedHelpers;

/**
 * 实况图点击播放:点开大图查看器时把内嵌视频提取到缓存并全屏播放一次,
 * 播完移除覆盖层回到照片;查看器右上角留实况图标重播按钮,播放中覆盖层盖住查看器按钮。
 * 触发链(QQGalleryActivity onResume 后,GalleryHook 调用):
 * 1. OpenImageSaver 在 getLocalPath 回调发现"已确认实况"时记 pending(聊天图片);
 * 2. 兜底:用 intent 的 KEY_THUMBNAL_BOUND 匹配 bind 时登记的实况气泡或未判定候选
 *    (群文件图片打开大图不走 getLocalPath;候选的原图未下载,后台轮询等落盘再判);
 * 3. 左右滑动:hook ViewPager2 翻页 + 查看 RecyclerView adapter 的 onBindViewHolder
 *    建立页码→消息映射,翻页即判定播放。
 */
public final class LiveVideoPlayer {

    private static final long PENDING_FRESH_MS = 8000;
    /** 等相册页码映射的最长时间,超时才退回 thumb-bound 反查。 */
    private static final long INITIAL_ALBUM_WAIT_MS = 1500;
    /** QQ 查看器打开过渡动画时长,期间不自动播放(真机按观感取 350ms)。 */
    private static final long OPEN_TRANSITION_MS = 350;
    private static final File CACHE_DIR =
            new File("/sdcard/Android/data/com.tencent.mobileqq/LiveXP/video_cache");

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private static final ExecutorService RETRY_EXEC = Executors.newSingleThreadExecutor();
    /** 相册对象图扫描单独一条线程:它耗时长,不能堵住视频提取(EXEC)。 */
    private static final ExecutorService ALBUM_EXEC = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static volatile String pendingPath;
    private static volatile long pendingAt;
    private static volatile String currentSrc;
    private static volatile Activity galleryRef;
    private static View button;
    private static ViewGroup overlay;
    private static android.widget.VideoView overlayVideo;

    /** 查看器会话内已自动播放过的路径(手动重播不受限);detach 时清空。 */
    private static final java.util.Set<String> playedPaths = ConcurrentHashMap.newKeySet();
    /** 当前查看器的 ViewPager2 与 页码→消息/路径 映射。 */
    private static volatile Object pagerRef;
    private static volatile Object innerRv;
    private static volatile java.util.List<String> albumPaths;
    private static volatile java.util.List<Object> albumRecs;
    private static volatile java.util.List<Boolean> albumLive;
    private static volatile java.util.List<String> albumIds;
    private static volatile int lastSettledPos = -1;
    /** 本次打开查看器时 thumb-bound/pending 给出的首屏猜测,相册映射到手即作废。 */
    private static volatile Match initialMatch;
    private static volatile boolean initialResolved;
    private static volatile int initialSeq;
    /** 首屏自动播放的最早时间:等 QQ 打开查看器的过渡动画放完再起播。 */
    private static volatile long initialPlayNotBefore;
    private static final Map<Integer, Object> pageRec = new ConcurrentHashMap<>();
    private static final Map<Integer, String> pagePath = new ConcurrentHashMap<>();
    /** 已经对某一页做过 View 对象图兜底扫描,避免"缩略图→扫描→还是缩略图"来回打转。 */
    private static final java.util.Set<Integer> viewScanned = ConcurrentHashMap.newKeySet();
    private static final java.util.Set<Class<?>> hookedAdapters = ConcurrentHashMap.newKeySet();
    private static volatile boolean scrollSignalInstalled;
    /** 翻页计数:playWhenReady 等待期间翻页则放弃播放。 */
    private static volatile int pageSeq;

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
            initialSeq++;
            initialResolved = false;
            initialPlayNotBefore = SystemClock.uptimeMillis() + OPEN_TRANSITION_MS;
            setupPager(activity);   // 先定好 currentItem 并启动相册扫描
            String p = pendingPath;
            if (p != null && SystemClock.uptimeMillis() - pendingAt < PENDING_FRESH_MS) {
                pendingPath = null;
                initialMatch = new Match(null, p, null, true);
            } else {
                Match m = matchByThumbBound(activity);
                Log.i("thumb-bound resolve: " + (m == null ? "null"
                        : "msgId=" + m.msgId + " path=" + m.path + " confirmed=" + m.confirmed));
                initialMatch = m;
            }
            MAIN.postDelayed(() -> resolveInitialPage(
                    SystemClock.uptimeMillis() + INITIAL_ALBUM_WAIT_MS, initialSeq), 60);
        } catch (Throwable t) {
            Log.i("gallery resume error: " + t);
        }
    }

    /**
     * 首屏这一页到底播哪张:相册映射按页码索引,不受聊天气泡 View 回收影响,优先采用;
     * 扫描没在 INITIAL_ALBUM_WAIT_MS 内给出映射时(转发查看器等)才退回 thumb-bound。
     */
    private static void resolveInitialPage(long deadline, int seq) {
        try {
            Activity a = galleryRef;
            if (a == null || a.isFinishing() || initialResolved || seq != initialSeq) return;
            long wait = initialPlayNotBefore - SystemClock.uptimeMillis();
            if (wait > 0) {
                MAIN.postDelayed(() -> resolveInitialPage(deadline, seq), wait);
                return;
            }
            java.util.List<String> paths = albumPaths;
            int pos = lastSettledPos;
            if (paths != null && pos >= 0 && pos < paths.size()) {
                PageData d = pageDataAt(pos);
                if (d != null && d.path != null) {
                    Match stale = initialMatch;
                    if (stale != null && stale.path != null && !stale.path.equals(d.path))
                        Log.i("thumb-bound stale, use album path: " + d.path);
                    initialResolved = true;
                    initialMatch = null;
                    Log.i("initial page from album: pos=" + pos + " path=" + d.path);
                    applyPage(a, d, pos);
                    return;
                }
            }
            if (SystemClock.uptimeMillis() >= deadline) {
                initialResolved = true;
                Match m = initialMatch;
                initialMatch = null;
                if (m == null) {
                    Log.i("initial page unresolved: no album mapping and no thumb-bound match");
                    return;
                }
                Log.i("initial page from thumb-bound: " + m.path);
                if (m.confirmed) {
                    currentSrc = m.path;
                    installButton(a);
                    playAuto(a, m.path);
                } else {
                    playWhenReady(m.msgId, m.path, m.md5);
                }
                return;
            }
            MAIN.postDelayed(() -> resolveInitialPage(deadline, seq), 200);
        } catch (Throwable t) {
            Log.i("resolveInitialPage error: " + t);
        }
    }

    /** GalleryHook 在 QQGalleryActivity onDestroy 后调用。 */
    public static void detach() {
        galleryRef = null;
        button = null;
        overlay = null;
        overlayVideo = null;
        pagerRef = null;
        innerRv = null;
        albumPaths = null;
        albumRecs = null;
        albumLive = null;
        albumIds = null;
        lastSettledPos = -1;
        pageSeq++;
        initialMatch = null;
        initialResolved = false;
        initialSeq++;
        pageRec.clear();
        pagePath.clear();
        viewScanned.clear();
        playedPaths.clear();
    }

    /** thumb-bound 匹配结果。 */
    static final class Match {
        final String msgId;
        final String path;
        final String md5;
        final boolean confirmed;

        Match(String msgId, String path, String md5, boolean confirmed) {
            this.msgId = msgId;
            this.path = path;
            this.md5 = md5;
            this.confirmed = confirmed;
        }
    }

    /**
     * 用查看器 intent 里的 KEY_THUMBNAL_BOUND(被点缩略图屏幕矩形)与 bind 时登记的
     * 实况气泡/未判定候选的气泡图矩形匹配,反查当前消息;只有一条登记时直接采用。
     */
    private static Match matchByThumbBound(Activity activity) {
        try {
            android.os.Bundle ex = activity.getIntent() == null ? null : activity.getIntent().getExtras();
            android.graphics.Rect bound = ex == null ? null : (android.graphics.Rect) ex.get("KEY_THUMBNAL_BOUND");
            if (bound != null) {
                Match m = matchBoundIn(LivePhotoDetector.liveBinds, bound, true);
                if (m == null) m = matchBoundIn(LivePhotoDetector.candidates, bound, false);
                if (m != null) return m;
            }
            Match only = onlyOne(LivePhotoDetector.liveBinds, true);
            if (only == null) only = onlyOne(LivePhotoDetector.candidates, false);
            return only;
        } catch (Throwable t) {
            Log.i("thumb-bound resolve error: " + t);
            return null;
        }
    }

    private static Match matchBoundIn(Map<String, LivePhotoDetector.BindAnchor> map,
                                      android.graphics.Rect bound, boolean confirmed) {
        int[] loc = new int[2];
        for (java.util.Map.Entry<String, LivePhotoDetector.BindAnchor> e : map.entrySet()) {
            if (e.getValue().image == null || e.getValue().path == null) continue;
            View img = e.getValue().image.get();
            if (img == null || img.getWidth() <= 0) continue;
            img.getLocationOnScreen(loc);
            int cx = loc[0] + img.getWidth() / 2;
            int cy = loc[1] + img.getHeight() / 2;
            if (bound.contains(cx, cy)) {
                Log.i("thumb-bound matched (" + (confirmed ? "live" : "candidate") + ") msgId=" + e.getKey());
                return new Match(e.getKey(), e.getValue().path, e.getValue().md5, confirmed);
            }
        }
        return null;
    }

    private static Match onlyOne(Map<String, LivePhotoDetector.BindAnchor> map, boolean confirmed) {
        String onlyId = null;
        String onlyPath = null;
        String onlyMd5 = null;
        int onlyCount = 0;
        for (java.util.Map.Entry<String, LivePhotoDetector.BindAnchor> e : map.entrySet()) {
            if (e.getValue().path != null) {
                onlyId = e.getKey();
                onlyPath = e.getValue().path;
                onlyMd5 = e.getValue().md5;
                onlyCount++;
            }
        }
        return onlyCount == 1 ? new Match(onlyId, onlyPath, onlyMd5, confirmed) : null;
    }

    /**
     * 候选图打开时原图可能还没下载:后台轮询等落盘,扫描判定后实况才自动播
     * (静态图记 md5 库2 后不再重扫)。等待期间用户翻页则只入库不再播。
     */
    private static void playWhenReady(final String msgId, final String path, final String md5) {
        playWhenReady(msgId, path, md5, false);
    }

    /** forceLive=QQ 相册条目已标 isLivePhoto,原图落盘后不用再扫描直接播。 */
    private static void playWhenReady(final String msgId, final String path, final String md5,
                                      final boolean forceLive) {
        if (path == null) return;
        if (!forceLive && LivePhotoDetector.isKnownStaticMd5(md5)) return;
        final int seq = pageSeq;
        Log.i("candidate wait for original: " + path + (forceLive ? " (qq live)" : ""));
        RETRY_EXEC.execute(() -> {
            try {
                for (int i = 0; i < 40; i++) {
                    Activity act = galleryRef;
                    if (act == null || act.isFinishing()) return;
                    File f = new File(path);
                    if (f.isFile() && f.length() >= LivePhotoDetector.MIN_DETECT_BYTES) {
                        boolean live = forceLive || LivePhotoDetector.isMotionPhotoFile(path);
                        Log.i("candidate scanned: " + f.getName() + " -> " + (live ? "LIVE" : "static"));
                        if (!live) {
                            LivePhotoDetector.confirmStatic(path, md5);
                            return;
                        }
                        LivePhotoDetector.confirmLive(msgId, path, md5);
                        MAIN.post(() -> {
                            Activity a = galleryRef;
                            if (a == null || a.isFinishing() || seq != pageSeq) return;
                            currentSrc = path;
                            installButton(a);
                            playAuto(a, path);
                        });
                        return;
                    }
                    Thread.sleep(1500);
                }
                Log.i("candidate wait timeout: " + path);
            } catch (Throwable t) {
                Log.i("candidate wait error: " + t);
            }
        });
    }

    /** 每次查看器会话每张图只自动播一次;之后靠右上角重播按钮。 */
    private static void playAuto(Activity act, String path) {
        if (!playedPaths.add(path)) {
            Log.i("already auto-played this session: " + path);
            return;
        }
        play(act, path);
    }

    /** GalleryHook/滚动信号:查看器翻到某一页。 */
    public static void onGalleryPage(int pos) {
        try {
            Activity act = galleryRef;
            if (act == null || act.isFinishing()) return;
            if (pos < 0 || pos == lastSettledPos) return;
            lastSettledPos = pos;
            pageSeq++;
            // 翻页立即打断正在播放的视频
            removeOverlay();
            PageData d = pageDataAt(pos);
            Log.i("gallery page selected: pos=" + pos + " path=" + (d == null ? "null" : d.path)
                    + " msgId=" + (d == null ? "null" : d.mediaId) + " qqLive=" + (d == null ? "-" : d.live));
            if (d != null && d.path != null) {
                applyPage(act, d, pos);
                return;
            }
            // 页码映射还没拿到:从当前页 View 的对象图里挖(后台跑,避免主线程反射风暴)
            removeButton();
            currentSrc = null;
            resolvePageFromView(pos);
        } catch (Throwable t) {
            Log.i("gallery page error: " + t);
        }
    }

    /** 页码→该页数据:先看本会话已解析的,再看相册映射。 */
    private static PageData pageDataAt(int pos) {
        PageData d = new PageData();
        java.util.List<String> paths = albumPaths;
        java.util.List<Object> recs = albumRecs;
        java.util.List<Boolean> live = albumLive;
        java.util.List<String> ids = albumIds;
        d.path = pagePath.get(pos);
        d.rec = pageRec.get(pos);
        if (paths != null && pos < paths.size()) {
            String p = paths.get(pos);
            if (p != null && !p.isEmpty()) d.path = p;
            if (d.rec == null && recs != null && pos < recs.size()) d.rec = recs.get(pos);
            if (live != null && pos < live.size()) d.live = live.get(pos);
            if (ids != null && pos < ids.size()) d.mediaId = ids.get(pos);
        }
        return (d.path == null && d.rec == null) ? null : d;
    }

    /** 拿到某页数据后的判定与播放。 */
    private static void applyPage(Activity act, PageData d, int pos) {
        String path = d.path;
        String msgId = d.mediaId != null ? d.mediaId
                : (d.rec == null ? null : LivePhotoDetector.msgIdOf(d.rec));
        String md5 = d.rec == null ? null : LivePhotoDetector.picMd5OfAt(d.rec, d.picIndex);
        if (path == null) {
            removeButton();
            currentSrc = null;
            return;
        }
        if (LivePhotoDetector.isThumbPath(path)) {
            // 多图消息的 remembered 路径只对应第一张,拿来兜底会串图
            String orig = LivePhotoDetector.picCount(d.rec) > 1
                    ? null : LivePhotoDetector.knownOriginal(msgId);
            if (orig != null) {
                Log.i("thumb path replaced by known original: " + orig);
                path = orig;
            } else {
                // 相册条目只带缩略图(原图还没下载),缩略图里没有内嵌视频,硬提只会弹失败提示
                Log.i("page only has thumbnail, skip play: " + path + " msgId=" + msgId);
                removeButton();
                currentSrc = null;
                if (viewScanned.add(pos)) resolvePageFromView(pos);
                return;
            }
        }
        // 多图消息不能按消息级 liveMsgIds 判定:同一气泡里可能只有一张是实况
        boolean multiPic = LivePhotoDetector.picCount(d.rec) > 1;
        boolean known = multiPic
                ? LivePhotoDetector.isKnownLivePath(path)
                        || LivePhotoDetector.isKnownLiveMd5At(d.rec, d.picIndex)
                : (msgId != null && LivePhotoDetector.liveMsgIds.contains(msgId))
                        || LivePhotoDetector.isKnownLiveOpen(path, d.rec);
        if (known || Boolean.TRUE.equals(d.live)) {
            currentSrc = path;
            installButton(act);
            File f = new File(path);
            if (f.isFile() && f.length() >= LivePhotoDetector.MIN_DETECT_BYTES) {
                playAuto(act, path);
            } else {
                playWhenReady(msgId, path, md5, true);
            }
        } else {
            currentSrc = null;
            removeButton();
            playWhenReady(msgId, path, md5, false);
        }
    }

    /** 页码→路径未知时,后台深挖当前居中页 View 的对象图找 MsgRecord 或图片路径。 */
    private static void resolvePageFromView(final int pos) {
        final int seq = pageSeq;
        final View page = centerPageView();
        if (page == null) return;
        EXEC.execute(() -> {
            try {
                PageData d = readPageData(page);
                if (d == null || d.path == null) {
                    Log.i("page view scan empty: pos=" + pos);
                    return;
                }
                Log.i("page view scan: pos=" + pos + " path=" + d.path
                        + " msgId=" + (d.rec == null ? "null" : LivePhotoDetector.msgIdOf(d.rec)));
                pagePath.put(pos, d.path);
                if (d.rec != null) pageRec.put(pos, d.rec);
                MAIN.post(() -> {
                    Activity a = galleryRef;
                    if (a == null || a.isFinishing() || seq != pageSeq || lastSettledPos != pos) return;
                    applyPage(a, d, pos);
                });
            } catch (Throwable t) {
                Log.i("resolvePageFromView error: " + t);
            }
        });
    }

    /** 当前居中显示的那一页。 */
    private static View centerPageView() {
        try {
            ViewGroup rv = (ViewGroup) innerRv;
            int n = rv.getChildCount();
            if (n == 0) return null;
            int centerX = rv.getWidth() / 2;
            View best = null;
            int bestDist = Integer.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                View c = rv.getChildAt(i);
                int dist = Math.abs((c.getLeft() + c.getRight()) / 2 - centerX);
                if (dist < bestDist) {
                    bestDist = dist;
                    best = c;
                }
            }
            return best;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 一页的解析结果:图片路径 + 可选的 MsgRecord + QQ 自己的实况标记与消息 id。 */
    static final class PageData {
        String path;
        Object rec;
        String mediaId;
        Boolean live;
        /** RFWLayerItemMediaInfo._mediaId 的下标:一条消息多张图时用来取对应那张。 */
        int picIndex = -1;
    }

    /**
     * 在页 View 的对象图里找 MsgRecord(优先,能拿到原图路径与 md5)或看起来是图片路径的字符串。
     * 反射分配量大,只能在后台线程调用。
     */
    private static PageData readPageData(View root) {
        PageData out = new PageData();
        java.util.Set<Object> visited =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        java.util.Deque<Object> queue = new java.util.ArrayDeque<>();
        queue.add(root);
        visited.add(root);
        int scanned = 0;
        while (!queue.isEmpty() && scanned++ < 250) {
            Object o = queue.poll();
            if (o.getClass().getSimpleName().equals("RFWLayerItemMediaInfo")) {
                PageData ed = readElementData(o);
                if (ed != null && ed.path != null) {
                    if (ed.rec != null) out.rec = ed.rec;
                    out.path = ed.path;
                    out.live = ed.live;
                    out.mediaId = ed.mediaId;
                    return out;
                }
            }
            if (out.rec == null && looksLikeMsgRecord(o)) {
                out.rec = o;
                String p = LivePhotoDetector.elementPathOf(o);
                if (p != null) out.path = p;
            }
            for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    Object v;
                    try {
                        f.setAccessible(true);
                        v = f.get(o);
                    } catch (Throwable ignored) {
                        continue;
                    }
                    if (v == null || !visited.add(v)) continue;
                    if (v instanceof String) {
                        String s = (String) v;
                        if (out.path == null && looksLikePath(s)) out.path = s;
                        else if (out.path != null && !out.path.contains("chatraw")
                                && s.contains("chatraw") && looksLikePath(s)) out.path = s;
                    } else if (v instanceof View) {
                        queue.add(v);
                    } else if (out.rec == null && depthOk(v)) {
                        queue.add(v);
                    }
                }
            }
        }
        return (out.path == null && out.rec == null) ? null : out;
    }

    private static boolean looksLikeMsgRecord(Object o) {
        if (!o.getClass().getName().contains("MsgRecord")) return false;
        try {
            XposedHelpers.getObjectField(o, "msgId");
            XposedHelpers.getObjectField(o, "elements");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void removeButton() {
        View b = button;
        button = null;
        if (b != null && b.getParent() instanceof ViewGroup) {
            ((ViewGroup) b.getParent()).removeView(b);
        }
    }

    static Object currentPager() {
        return pagerRef;
    }

    static boolean galleryActive() {
        return galleryRef != null;
    }

    /** 在 decor 里找查看器分页组件:QQ 绕过 ViewPager2 直接驱动内层 RecyclerView,翻页信号要另挂。 */
    private static void setupPager(Activity act) {
        try {
            if (pagerRef != null) return;
            ViewGroup decor = (ViewGroup) act.getWindow().getDecorView();
            java.util.List<Object> pagers = new java.util.ArrayList<>();
            collectViewsOfClass(decor, "androidx.viewpager2.widget.ViewPager2", pagers);
            if (pagers.isEmpty()) {
                Log.i("gallery pager not found in decor");
                return;
            }
            pagerRef = pagers.get(0);
            Object rv = null;
            for (Field f : pagerRef.getClass().getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                Object v = f.get(pagerRef);
                if (v != null && v.getClass().getName().contains("RecyclerView")) {
                    rv = v;
                    break;
                }
            }
            if (rv == null) {
                Log.i("pager inner recycler not found");
                return;
            }
            innerRv = rv;
            Object adapter = XposedHelpers.callMethod(rv, "getAdapter");
            int itemCount = adapter == null ? -1 : ((Number) XposedHelpers.callMethod(adapter, "getItemCount")).intValue();
            int cur = ((Number) XposedHelpers.callMethod(pagerRef, "getCurrentItem")).intValue();
            lastSettledPos = cur;
            Log.i("pager state: currentItem=" + cur + " items=" + itemCount);
            installScrollSignal(rv);
            final Object adapterF = adapter;
            final Object pagerF = pagerRef;
            final int itemF = itemCount;
            android.os.Bundle ex = act.getIntent() == null ? null : act.getIntent().getExtras();
            if (ex != null) ex.keySet(); // 主线程先解包,后台线程再读
            // discoverAlbum 要深挖 QQ 对象图,会触发大量反射分配,主线程跑会 GC 风暴导致 ANR(真机实测);
            // 也不走 EXEC,避免堵住视频提取。
            ALBUM_EXEC.execute(() -> discoverAlbum(act, adapterF, pagerF, itemF));
        } catch (Throwable t) {
            Log.i("setupPager error: " + t);
        }
    }

    /**
     * 翻页信号:QQ 不走 dispatchOnPageSelected。
     * 1) scrollToPosition/smoothScrollToPosition(过滤到查看器内层 RecyclerView)直接给出目标页;
     * 2) 滚动静止时按子 View 中心算当前页兜底(覆盖 fling 滑动)。
     */
    private static void installScrollSignal(Object rv) {
        try {
            // 回调按 innerRv 过滤,进程级装一次就够;每次开会话都重装会撞上
            // LSPosed 的 "callback already registered 64 times" 上限,之后翻页信号彻底失效(真机实测)。
            if (scrollSignalInstalled) return;
            Class<?> rvBase = null;
            for (Class<?> c = rv.getClass(); c != null; c = c.getSuperclass()) {
                if ("androidx.recyclerview.widget.RecyclerView".equals(c.getName())) {
                    rvBase = c;
                    break;
                }
            }
            if (rvBase == null) {
                Log.i("RecyclerView base class not found for scroll signal");
                return;
            }
            de.robv.android.xposed.XC_MethodHook signal = new de.robv.android.xposed.XC_MethodHook() {
                @Override
                protected void afterHookedMethod(de.robv.android.xposed.XC_MethodHook.MethodHookParam param) {
                    if (param.thisObject != innerRv) return;
                    Object a0 = param.args != null && param.args.length > 0 ? param.args[0] : null;
                    if (a0 instanceof Integer) onGalleryPage((Integer) a0);
                }
            };
            de.robv.android.xposed.XposedBridge.hookAllMethods(rvBase, "scrollToPosition", signal);
            de.robv.android.xposed.XposedBridge.hookAllMethods(rvBase, "smoothScrollToPosition", signal);
            de.robv.android.xposed.XposedBridge.hookAllMethods(rvBase, "onScrollStateChanged",
                    new de.robv.android.xposed.XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(
                                de.robv.android.xposed.XC_MethodHook.MethodHookParam param) {
                            if (param.thisObject != innerRv) return;
                            Object st = param.args != null && param.args.length > 0 ? param.args[0] : null;
                            if (!(st instanceof Integer)) return;
                            int state = (Integer) st;
                            // 手指一开始拖动/开始回弹就撤掉播放层,滑动过程连贯(等静止才撤会黑屏闪现)
                            if (state != 0) {
                                if (overlay != null) {
                                    Log.i("drag stops playback: state=" + state);
                                    removeOverlay();
                                }
                                return;
                            }
                            int pos = currentPosFromChildren();
                            if (pos >= 0) onGalleryPage(pos);
                        }
                    });
            scrollSignalInstalled = true;
            Log.i("gallery scroll signal installed on " + rvBase.getName());
        } catch (Throwable t) {
            Log.i("installScrollSignal error: " + t);
        }
    }

    private static int currentPosFromChildren() {
        try {
            ViewGroup rv = (ViewGroup) innerRv;
            int n = rv.getChildCount();
            if (n == 0) return -1;
            int centerX = rv.getWidth() / 2;
            View best = null;
            int bestDist = Integer.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                View c = rv.getChildAt(i);
                int dist = Math.abs((c.getLeft() + c.getRight()) / 2 - centerX);
                if (dist < bestDist) {
                    bestDist = dist;
                    best = c;
                }
            }
            if (best == null) return -1;
            Object pos = XposedHelpers.callMethod(innerRv, "getChildAdapterPosition", best);
            return pos instanceof Integer ? (Integer) pos : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * 页码→路径映射:QQ 相册数据(各消息图片的 chatimg/chatraw/chatthumb 路径列表)
     * 藏在查看器 intent extras 或 adapter 对象图里,后台深挖与页数对齐的路径列表。
     * 只允许在后台线程调用(主线程跑会 GC 风暴 ANR)。
     */
    private static void discoverAlbum(Activity act, Object adapter, Object pager, int itemCount) {
        try {
            if (albumPaths != null || itemCount <= 0) return;
            java.util.Set<Object> visited =
                    java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            java.util.Deque<Object> queue = new java.util.ArrayDeque<>();
            android.os.Bundle ex =
                    act.getIntent() == null ? null : act.getIntent().getExtras();
            StringBuilder extraNames = new StringBuilder();
            if (ex != null) {
                for (String k : ex.keySet()) {
                    Object v;
                    try {
                        v = ex.get(k);
                    } catch (Throwable ignored) {
                        continue;
                    }
                    if (v == null) continue;
                    extraNames.append(k).append('=')
                            .append(v.getClass().getSimpleName())
                            .append(v instanceof java.util.List
                                    ? "(size=" + ((java.util.List<?>) v).size() + ")" : "").append(' ');
                    if (v instanceof java.util.List && Math.abs(((java.util.List<?>) v).size() - itemCount) <= 2) {
                        java.util.List<PageData> pd = pageDataOf((java.util.List<?>) v);
                        if (pd != null && pd.size() == itemCount) {
                            Log.i("album elem#0 dump: " + fieldsDump(((java.util.List<?>) v).get(0)));
                            adoptAlbum(pd);
                            Log.i("album adopted from extra " + k);
                            return;
                        }
                    }
                    if (visited.add(v)) queue.add(v);
                }
            }
            if (adapter != null && visited.add(adapter)) queue.add(adapter);
            if (pager != null && visited.add(pager)) queue.add(pager);
            int scanned = 0;
            boolean dumpedElem = false;
            while (!queue.isEmpty() && albumPaths == null) {
                Object o = queue.poll();
                scanned++;
                if (scanned > 3000) {
                    Log.i("album scan gave up: scanned=" + scanned + " extras=" + extraNames);
                    return;
                }
                for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                    for (Field f : c.getDeclaredFields()) {
                        if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                        Object v;
                        try {
                            f.setAccessible(true);
                            v = f.get(o);
                        } catch (Throwable ignored) {
                            continue;
                        }
                        if (v == null || !visited.add(v)) continue;
                        if (v instanceof java.util.List) {
                            java.util.List<?> l = (java.util.List<?>) v;
                            // 只对页数附近的表做逐元素解析,避免全图反射扫描拖慢后台线程
                            if (Math.abs(l.size() - itemCount) <= 2 && l.size() >= 2) {
                                java.util.List<PageData> pd = pageDataOf(l);
                                if (pd != null) {
                                    Log.i("album list found: size=" + pd.size()
                                            + " first=" + (pd.get(0) == null ? "null" : pd.get(0).path));
                                    if (pd.size() == itemCount) {
                                        Log.i("album elem#0 dump: " + fieldsDump(l.get(0)));
                                        adoptAlbum(pd);
                                        Log.i("album adopted: aligned with " + itemCount + " pages");
                                        logMultiImage(l, pd);
                                        return;
                                    }
                                } else if (l.size() == itemCount && !dumpedElem) {
                                    dumpedElem = true;
                                    Log.i("album elem dump: " + o.getClass().getSimpleName() + "."
                                            + f.getName() + " -> " + fieldsDump(l.get(0)));
                                } else {
                                    Object e0 = l.get(0);
                                    Log.i("album cand: " + o.getClass().getSimpleName() + "." + f.getName()
                                            + " size=" + l.size() + " elem="
                                            + (e0 == null ? "null" : e0.getClass().getName()));
                                }
                            }
                        } else if (depthOk(v)) {
                            queue.add(v);
                        }
                    }
                }
            }
            Log.i("album scan done: scanned=" + scanned + " found=" + (albumPaths != null));
        } catch (Throwable t) {
            Log.i("discoverAlbum error: " + t);
        }
    }

    /** 多图消息诊断:同一消息出现多页时,打出每页的原始 _mediaId(含下标)与解析出的路径。 */
    private static void logMultiImage(java.util.List<?> raw, java.util.List<PageData> pd) {
        try {
            java.util.Map<String, Integer> counts = new java.util.HashMap<>();
            for (PageData d : pd) {
                if (d != null && d.mediaId != null) {
                    Integer n = counts.get(d.mediaId);
                    counts.put(d.mediaId, n == null ? 1 : n + 1);
                }
            }
            for (int i = 0; i < pd.size() && i < raw.size(); i++) {
                PageData d = pd.get(i);
                if (d == null || d.mediaId == null) continue;
                Integer n = counts.get(d.mediaId);
                if (n == null || n < 2) continue;
                String rawId = null;
                try {
                    Object mid = XposedHelpers.getObjectField(raw.get(i), "_mediaId");
                    if (mid instanceof String) rawId = (String) mid;
                } catch (Throwable ignored) {
                }
                Log.i("multi-image elem#" + i + " _mediaId=" + rawId
                        + " idx=" + d.picIndex + " path=" + d.path);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 相册元素自己那张图的本地路径:优先 layerPicInfo 各档里的原图 localPath。
     * 全是缩略图时也返回缩略图路径(让上层跳过播放),返回 null 反而会让对象图扫描
     * 挖到同一条消息第一张的路径,造成串图。
     */
    private static String layerPicPath(Object elem) {
        try {
            Object lpi = XposedHelpers.getObjectField(elem, "layerPicInfo");
            if (lpi == null) return null;
            String thumb = null;
            for (String fn : new String[]{"_currentPicInfo", "originPicInfo", "bigPicInfo", "smallPicInfo"}) {
                Object pi;
                try {
                    pi = XposedHelpers.getObjectField(lpi, fn);
                } catch (Throwable ignored) {
                    continue;
                }
                if (pi == null) continue;
                Object lp;
                try {
                    lp = XposedHelpers.getObjectField(pi, "localPath");
                } catch (Throwable ignored) {
                    continue;
                }
                if (!(lp instanceof String) || !looksLikePath((String) lp)) continue;
                if (LivePhotoDetector.isThumbPath((String) lp)) {
                    if (thumb == null) thumb = (String) lp;
                } else {
                    return (String) lp;
                }
            }
            return thumb;
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean depthOk(Object v) {
        String n = v.getClass().getName();
        return !n.startsWith("android.") && !n.startsWith("androidx.") && !(v instanceof android.view.View)
                && !(v instanceof android.content.Context) && !(v instanceof Class);
    }

    /** 多数条目(≥80%)能解析出内容路径才采用;个别页(如视频消息)可以没有路径。 */
    private static java.util.List<PageData> pageDataOf(java.util.List<?> l) {
        java.util.List<PageData> out = new java.util.ArrayList<>();
        int ok = 0;
        for (Object e : l) {
            PageData d = readElementData(e);
            out.add(d);
            if (d != null && d.path != null) ok++;
        }
        return ok * 5 >= l.size() * 4 ? out : null;
    }

    /**
     * 单个相册条目:可能是路径字符串,也可能是 RFWLayerItemMediaInfo 这类对象
     * (路径藏在多层字段里,还可能直接挂着 MsgRecord)。后台调用。
     * 条目里同时挂着发送者头像等无关路径,按内容度打分取最优。
     */
    private static PageData readElementData(Object e) {
        if (e == null) return null;
        PageData best = new PageData();
        if (e instanceof String) {
            best.path = looksLikePath((String) e) ? (String) e : null;
            return best;
        }
        if (!depthOk(e)) return null;
        // RFWLayerItemMediaInfo 上 QQ 自己标了是否实况,字段名不混淆,直接采信(只做加速,不做否决)
        try {
            Object lp = XposedHelpers.getObjectField(e, "isLivePhoto");
            if (lp instanceof Boolean) best.live = (Boolean) lp;
            Object mid = XposedHelpers.getObjectField(e, "_mediaId");
            if (mid instanceof String) {
                String s = (String) mid;
                int i = s.lastIndexOf('_');
                if (i > 0) {
                    best.mediaId = s.substring(0, i);
                    try {
                        // 后缀 0 表示单图消息;多图消息从 1 开始编号,换成 0 基下标
                        int suffix = Integer.parseInt(s.substring(i + 1));
                        best.picIndex = Math.max(0, suffix - 1);
                    } catch (Throwable ignored) {
                    }
                } else {
                    best.mediaId = s;
                }
            }
        } catch (Throwable ignored) {
        }
        // 多图消息:同一条 MsgRecord 被所有元素共享,从对象图里挖到的总是第一张的路径。
        // 每个元素自己的 layerPicInfo._currentPicInfo.localPath 才是这一页的原图,优先锁定。
        String own = layerPicPath(e);
        if (own != null) {
            best.path = own;
        }
        java.util.Set<Object> visited =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        java.util.Deque<Object> queue = new java.util.ArrayDeque<>();
        queue.add(e);
        visited.add(e);
        int scanned = 0;
        int bestScore = own == null ? 0 : 100;
        while (!queue.isEmpty() && scanned++ < 80) {
            Object o = queue.poll();
            if (best.rec == null && looksLikeMsgRecord(o)) {
                best.rec = o;
                String p = LivePhotoDetector.elementPathOfAt(o, best.picIndex);
                int sc = pathScore(p);
                if (bestScore < 100 && sc > bestScore) {
                    bestScore = sc;
                    best.path = p;
                }
            }
            try {
                for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                    for (Field f : c.getDeclaredFields()) {
                        if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                        Object v;
                        try {
                            f.setAccessible(true);
                            v = f.get(o);
                        } catch (Throwable ignored) {
                            continue;
                        }
                        if (v == null || !visited.add(v)) continue;
                        if (v instanceof String) {
                            String s = (String) v;
                            int sc = pathScore(s);
                            if (sc > bestScore) {
                                bestScore = sc;
                                best.path = s;
                            }
                        } else if (depthOk(v)) {
                            queue.add(v);
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            if (best.rec != null && bestScore >= 3) break;
        }
        return best;
    }

    /** 路径内容度评分:0=无关(头像/缩略图)或不是路径,越大越像图片正文。 */
    private static int pathScore(String s) {
        if (s == null || !looksLikePath(s)) return 0;
        if (s.contains("/avatar/") || s.contains("/.thumbnails/")) return 0;
        if (s.contains("chatraw")) return 4;
        if (s.contains("chatpic")) return 3;
        if (s.contains("/QQfile_recv/") || s.contains("/Pictures/") || s.contains("/DCIM/")
                || s.contains("/msfntemp/") || s.contains("nt_image")) return 2;
        return 1;
    }

    private static void adoptAlbum(java.util.List<PageData> pd) {
        java.util.List<String> paths = new java.util.ArrayList<>(pd.size());
        java.util.List<Object> recs = new java.util.ArrayList<>(pd.size());
        java.util.List<Boolean> live = new java.util.ArrayList<>(pd.size());
        java.util.List<String> ids = new java.util.ArrayList<>(pd.size());
        for (PageData d : pd) {
            paths.add(d == null ? null : d.path);
            recs.add(d == null ? null : d.rec);
            live.add(d == null ? null : d.live);
            ids.add(d == null ? null : d.mediaId);
        }
        albumLive = live;
        albumIds = ids;
        albumRecs = recs;
        albumPaths = paths;
        applyCurrentPageIfUnknown();
    }

    /** 相册映射到手后立即判定首屏这一页(不再等超时)。 */
    private static void applyCurrentPageIfUnknown() {
        MAIN.post(() -> resolveInitialPage(0, initialSeq));
    }

    /** 诊断用:列出对象第一层字段名与看起来像路径/记录的值。 */
    private static String fieldsDump(Object o) {
        if (o == null) return "null";
        StringBuilder sb = new StringBuilder(o.getClass().getSimpleName()).append("{");
        try {
            int n = 0;
            for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || n++ > 30) continue;
                    Object v;
                    try {
                        f.setAccessible(true);
                        v = f.get(o);
                    } catch (Throwable ignored) {
                        continue;
                    }
                    if (v == null) continue;
                    if (v instanceof String) {
                        String s = (String) v;
                        if (s.length() > 90) s = s.substring(0, 90);
                        if (!s.isEmpty()) sb.append(f.getName()).append("='").append(s).append("' ");
                    } else if (!(v instanceof android.view.View) && !(v instanceof android.content.Context)) {
                        sb.append(f.getName()).append('=')
                                .append(v.getClass().getSimpleName())
                                .append(v instanceof java.util.List ? "(" + ((java.util.List<?>) v).size() + ")" : "")
                                .append(' ');
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        String s = sb.append('}').toString();
        return s.length() > 900 ? s.substring(0, 900) : s;
    }

    private static void collectViewsOfClass(ViewGroup root, String clsName, java.util.List<Object> out) {
        if (clsName.equals(root.getClass().getName())) out.add(root);
        for (int i = 0; i < root.getChildCount(); i++) {
            View c = root.getChildAt(i);
            if (clsName.equals(c.getClass().getName())) out.add(c);
            if (c instanceof ViewGroup) collectViewsOfClass((ViewGroup) c, clsName, out);
        }
    }

    private static final int PATH_STRING_MAX = 200;

    private static boolean looksLikePath(String s) {
        if (s == null || s.isEmpty() || s.length() > PATH_STRING_MAX) return false;
        return s.startsWith("/storage/") || s.startsWith("/data/user/0/com.tencent.mobileqq/")
                || s.startsWith("chatpic/") || s.contains("/chatpic/");
    }

    private static void installButton(final Activity activity) {
        if (button != null && button.getParent() != null) return;
        try {
            ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
            final LiveMarkView mark = new LiveMarkView(activity);
            mark.setAlpha(0.9f);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    px(activity, 48), px(activity, 48), Gravity.TOP | Gravity.END);
            lp.topMargin = px(activity, 38);
            lp.rightMargin = px(activity, 6);
            decor.addView(mark, lp);
            mark.setOnClickListener(v -> {
                String src = currentSrc;
                if (src != null) play(activity, src);
            });
            button = mark;
            Log.i("replay button installed (live mark)");
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
            final Activity act = galleryRef;
            if (act == null || act.isFinishing()) return;
            ViewGroup decor = (ViewGroup) act.getWindow().getDecorView();
            final FrameLayout container = new FrameLayout(act);
            VideoView video = new VideoView(act);
            // 透明 surface 放在窗口之上:首帧前完全透明,露出的是查看器原图;
            // 若留在窗口下方,透明区会透出底下的聊天界面(之前滑动后闪屏的根因)。
            video.setZOrderOnTop(true);
            try {
                video.getHolder().setFormat(android.graphics.PixelFormat.TRANSLUCENT);
            } catch (Throwable ignored) {
            }
            FrameLayout.LayoutParams vlp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER);
            container.addView(video, vlp);
            decor.addView(container, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            overlay = container;
            overlayVideo = video;
            // 播放中不吞手势:横向滑动可打断播放并翻页,点击落在查看器自身控件上
            container.setOnTouchListener((v, e) -> false);
            container.setClickable(false);
            container.setLongClickable(false);
            video.setOnTouchListener((v, e) -> false);
            video.setClickable(false);
            video.setLongClickable(false);
            video.setOnCompletionListener(mp -> removeOverlay());
            video.setOnErrorListener((mp, what, extra) -> {
                Log.i("video play error what=" + what + " extra=" + extra);
                removeOverlay();
                return true;
            });
            video.setVideoURI(Uri.fromFile(vf));
            video.start();
            Log.i("playing overlay video");
        } catch (Throwable t) {
            Log.i("overlay error: " + t);
        }
    }

    private static void removeOverlay() {
        ViewGroup o = overlay;
        overlay = null;
        android.widget.VideoView vv = overlayVideo;
        overlayVideo = null;
        if (vv != null) {
            try {
                vv.stopPlayback();
            } catch (Throwable ignored) {
            }
        }
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
