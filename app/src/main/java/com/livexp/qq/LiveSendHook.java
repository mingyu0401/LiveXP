package com.livexp.qq;

import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 发送图片链路的「实况转MP4」能力。
 *
 * 单图预览页的宿主 Activity 两条路径都是 com.tencent.qqnt.qbasealbum.WinkHomeActivity,
 * 底栏都由 PreviewCustomizationBase#getBottomView 产出;但实现类不同(实测日志):
 * 半屏面板直接用 PreviewCustomizationBase 本身,全屏图库用覆写了 onSendClick 的
 * com.tencent.mobileqq.flashtransfer.album.FlashTransferPreviewCustomization。
 * 这些名字是业务实现的公开 API,不随版本混淆,所以按名字 hook,并按运行时类补挂子类。
 *
 * 流程:进预览页 → 底栏"原图"右侧挂一个可勾选的「实况转MP4」→ 勾选即后台合成 MP4
 * → 点发送时(onSendClick 之前)把这条 LocalMediaInfo 换成合成视频的副本
 * → QQ 自己的通道按视频发出 → 延迟回收临时文件。
 * 转码还没完成就点发送时:预览页照常关闭(用户先回聊天界面),
 * 发送结果被按住不放行,等转码完成再把结果里的图片换成视频放行。
 * 任一环节失败都什么都不改,原样发图。
 */
public final class LiveSendHook {

    private static final String PREVIEW_CLASS =
            "com.tencent.qqnt.qbasealbum.customization.preview.PreviewCustomizationBase";
    private static final String MEDIA_TYPE_HELPER =
            "com.tencent.qqnt.qbasealbum.utils.MediaTypeHelper";
    /**
     * LocalMediaInfo.mMediaType 的"视频"取值。
     * 证据:MediaTypeHelper 静态初始化里的映射表 hashMapOf("image" to 0, "video" to 1, "mobileqq" to 2),
     * a(LocalMediaInfo) 就是按 mMimeType 的主段查这张表。换算不到时用 1。
     */
    private static final int VIDEO_MEDIA_TYPE = 1;

    private static final int BTN_ID = 0x7e0b1e20;
    /**
     * 发送后过多久回收临时视频。
     * 不能只等"转码完成":QQ 的 shortvideo 缓存虽然已经写了转码副本,但本端回放播放器
     * 仍然按 VideoElement.filePath 读源文件(实测提前删除后本端只剩首帧、无法播放),
     * 上传也是异步的,所以给一个明显宽裕的窗口;进程被杀留下的由下次启动的 1 小时清理兜底。
     */
    private static final long DELETE_DELAY_MS = 10 * 60_000L;
    private static final int ANCHOR_RETRY_MAX = 6;
    /** 转码完成后,发送结果还没回传就放弃等待的时限。 */
    private static final long RESULT_WAIT_MS = 4000L;

    private static final int ST_NONE = 0;
    private static final int ST_PARSING = 1;
    private static final int ST_READY = 2;
    private static final int ST_NOT_LIVE = 3;
    private static final int ST_FAILED = 4;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    /** 每张图自己的勾选与解析状态:key = 预览项的文件路径。 */
    private static final Map<String, Job> jobs = new ConcurrentHashMap<>();
    /** 底栏 View → 挂上去的按钮。 */
    private static final Map<View, TextView> buttons = new WeakHashMap<>();
    /** 底栏 View → 看门人:QQ 会重建底栏子 View,按钮被抹掉时要重新挂回去。 */
    private static final Map<View, android.view.ViewTreeObserver.OnGlobalLayoutListener> watchers =
            new WeakHashMap<>();
    /** 实况判定缓存:预览角标用,同一张图只判一次。 */
    private static final Map<String, Boolean> liveVerdict = new ConcurrentHashMap<>();

    private static volatile Object currentItem;
    private static volatile String currentPath;
    /** 重放发送结果时用:这期间同一 Intent 的回传直接放行。 */
    private static volatile Intent replayIntent;
    /** 已补挂过 hook 的预览实现类与方法。 */
    private static final java.util.Set<Class<?>> hookedClasses =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<Class<?>, Boolean>());
    private static final java.util.Set<Method> hookedMethods =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<Method, Boolean>());

    private LiveSendHook() {}

    private static final class Job {
        String path;
        int status = ST_NONE;
        boolean checked;
        /** 这张图已经被"发送"处理过一次:之后再解析完的产物直接丢弃,不再留着占空间。 */
        boolean sent;
        /** 用户点了发送,等转码完成后自动发出。 */
        boolean autoSend;
        /** 面板打开后的自动排队转码(与用户点击无关,只是预热产物)。 */
        boolean autoQueued;
        Object autoItem;
        LiveComposer.Out out;
        /** 被按住的发送结果,转码完成后放行。 */
        Pending pending;
        /** 被拦下的发送点击(非结果回传通道时用),转码完成后重发。 */
        Pending deferred;
    }

    /** 被按住的"发送结果回传":放行时原样重调一次即可。 */
    private static final class Pending {
        Method method;
        Object target;
        Object[] args;
        Intent data;
    }

    private static Job job(String path) {
        return jobs.computeIfAbsent(path, k -> {
            Job j = new Job();
            j.path = k;
            return j;
        });
    }

    public static void install(ClassLoader cl) {
        Class<?> preview = XposedHelpers.findClassIfExists(PREVIEW_CLASS, cl);
        if (preview == null) {
            Log.i("send hook: 找不到 " + PREVIEW_CLASS);
            return;
        }
        hookBottom(preview);
        hookSelection(preview);
        hookSend(preview);
        hookResultDelivery();
        hookAlbumResume();
        LiveSendMarks.loadAsync();
        Log.i("send hook installed on " + preview.getName());
    }

    /**
     * 相册入口自启:QQ NT 是单 Activity 架构(SplashActivity),半屏相册面板/全屏相册/
     * 发送预览页都是它内部的浮层,不走独立 Activity 也不触发 getBottomView(实测零日志)。
     * 所以在所有 Activity resume 时把布局看门狗装到 DecorView 上:面板打开引发布局,
     * ensureButton/ensureGridAdapter 即可发现网格与预览底栏并补挂。
     */
    private static void hookAlbumResume() {
        try {
            XposedBridge.hookAllMethods(android.app.Instrumentation.class,
                    "callActivityOnResume", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object a = param.args != null && param.args.length > 0 ? param.args[0] : null;
                            if (!(a instanceof android.app.Activity)) return;
                            android.app.Activity act = (android.app.Activity) a;
                            if (act.isFinishing()) return;
                            String name = act.getClass().getName();
                            if (!seenHosts.add("resume:" + name)) return;
                            Log.i("看门狗就位: " + name);
                            ViewGroup decor = (ViewGroup) act.getWindow().getDecorView();
                            watchRoot(decor);
                            decor.postDelayed(() -> ensureButton(decor), 600);
                        }
                    });
            Log.i("相册 resume 钩子已挂");
        } catch (Throwable t) {
            Log.i("hook album resume failed: " + t);
        }
    }

    // ------------------------------------------------------------ hook

    private static void hookBottom(Class<?> preview) {
        try {
            XposedBridge.hookAllMethods(preview, "getBottomView", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object result = param.getResult();
                    if (!(result instanceof View)) return;
                    final View bottom = (View) result;
                    Log.i("getBottomView → " + bottom.getClass().getName()
                            + " 已有按钮=" + (bottom.findViewById(BTN_ID) != null));
                    watch(bottom);
                    bottom.post(() -> injectButton(bottom, 0));
                    followSubclass(param.thisObject.getClass());
                }
            });
        } catch (Throwable t) {
            Log.i("hook getBottomView failed: " + t);
        }
    }

    /**
     * 半屏面板与全屏图库是两个不同的 PreviewCustomizationBase 子类。
     * Xposed 只 hook 到方法真正声明的那个类,子类覆写 onSendClick 时基类 hook 不会被虚分发命中,
     * 所以按运行时类名补挂(每个类只挂一次,避免撞 LSPosed 的重复注册上限)。
     */
    private static void followSubclass(Class<?> c) {
        if (c == null || !hookedClasses.add(c)) return;
        if (PREVIEW_CLASS.equals(c.getName())) return;
        Log.i("预览实现类: " + c.getName() + " 方法=" + sendish(c));
        // 子类可能连 getBottomView 也覆写(Xposed 只挂到声明处,虚分发走子类时基类钩子拦不到),
        // 一并补挂;子类没声明时 hookAllMethods 找不到方法,无副作用
        hookBottom(c);
        hookSelection(c);
        hookSend(c);
    }

    private static String sendish(Class<?> c) {
        StringBuilder sb = new StringBuilder();
        for (Method m : c.getDeclaredMethods()) {
            String n = m.getName();
            if (n.toLowerCase().contains("send") || n.contains("ItemSelected")) {
                if (sb.length() > 0) sb.append('/');
                sb.append(n);
            }
        }
        return sb.toString();
    }

    private static void hookSelection(Class<?> owner) {
        hookDeclared(owner, "onGalleryItemSelected", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (param.args == null || param.args.length < 1) return;
                Object info = param.args[0];
                if (info == null) return;
                currentItem = info;
                currentPath = pathOf(info);
                refreshButtons();
                refreshPreviewBadge(param.thisObject);
                autoArmIfNeeded(currentPath);
            }
        });
    }

    /**
     * 选中(点开预览)已判定为实况的图片:置 armed(发送时替换成视频)并确保转码在跑。
     * 「实况转MP4」开关关闭时不做。
     */
    private static void autoArmIfNeeded(String path) {
        if (path == null) return;
        android.content.Context ctx = HookEntry.appCtx;
        if (ctx == null || !LiveSettings.sendParseEnabled(ctx)) return;
        Boolean live = liveVerdict.get(path);
        if (live == null) {
            IO.execute(() -> {
                boolean l = LivePhotoDetector.isLiveFile(path);
                liveVerdict.put(path, l);
                if (l) MAIN.post(() -> autoArmIfNeeded(path));
            });
            return;
        }
        if (!live) return;
        armedPaths.add(path);
        Job j = job(path);
        j.checked = true;
        if (j.status == ST_NONE && !j.autoQueued) {
            j.autoQueued = true;
            startParse(path, j);
        }
        refreshButtons();
    }

    /** 用户点按钮启用转码的图(发送时替换成视频);网格进度文案按这个集合统计。 */
    private static final java.util.Set<String> armedPaths = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 面板打开即自动排队转码(预热,发送是否替换由 armed 决定)。 */
    private static void autoQueue(String path) {
        if (path == null) return;
        Boolean live = liveVerdict.get(path);
        if (live == null) {
            IO.execute(() -> {
                boolean l = LivePhotoDetector.isLiveFile(path);
                liveVerdict.put(path, l);
                if (l) MAIN.post(() -> autoQueue(path));
            });
            return;
        }
        if (!live) return;
        Job j = job(path);
        if (j.autoQueued || j.checked || j.status == ST_PARSING || j.status == ST_READY) return;
        j.autoQueued = true;
        Log.i("面板打开,自动排队转码: " + path);
        startParse(path, j);
    }

    private static void hookSend(Class<?> owner) {
        hookDeclared(owner, "onSendClick", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    dumpItem();
                    swapIfNeeded(param);
                } catch (Throwable t) {
                    Log.i("send swap failed, 原样发送: " + t);
                }
            }
        });
    }

    /**
     * 发送结果回传拦截。
     *
     * QQ 的发送走"预览页 setResult → 宿主 Activity 回传"这一套,回传最终都进
     * Activity#dispatchActivityResult。在这里按住结果,预览页就能照常关闭(用户回到聊天界面),
     * 等转码完成再把结果里的图片换成视频放行,发送在后台继续。
     */
    private static void hookResultDelivery() {
        int n = 0;
        try {
            for (Method m : android.app.Activity.class.getDeclaredMethods()) {
                if (!m.getName().equals("dispatchActivityResult")) continue;
                XposedBridge.hookMethod(m, RESULT_HOOK);
                n++;
                Log.i("已挂钩结果回传: " + m);
            }
        } catch (Throwable t) {
            Log.i("hook dispatchActivityResult failed: " + t);
        }
        if (n == 0) {
            try {
                Class<?> at = XposedHelpers.findClassIfExists("android.app.ActivityThread", null);
                if (at != null) {
                    for (Method m : at.getDeclaredMethods()) {
                        if (!m.getName().equals("handleSendResult")) continue;
                        XposedBridge.hookMethod(m, RESULT_HOOK);
                        n++;
                        Log.i("已挂钩结果回传: " + m);
                    }
                }
            } catch (Throwable t) {
                Log.i("hook handleSendResult failed: " + t);
            }
        }
        Log.i("结果回传拦截钩子数量=" + n);
    }

    private static final XC_MethodHook RESULT_HOOK = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                onResultDelivery(param);
            } catch (Throwable t) {
                Log.i("结果拦截异常: " + t);
            }
        }
    };

    private static void onResultDelivery(XC_MethodHook.MethodHookParam param) {
        Intent data = intentIn(param.args);
        if (data == null) return;
        if (data == replayIntent) return;
        List<Job> hits = matchJobs(data);
        if (hits == null || hits.isEmpty()) return;
        // 有还在转码的(用户点了发送但没转完):按住结果,等转码完成再放行
        Pending hold = null;
        for (Job j : hits) {
            if (j.status == ST_PARSING) {
                if (hold == null) {
                    hold = new Pending();
                    hold.method = param.method instanceof Method ? (Method) param.method : null;
                    hold.target = param.thisObject;
                    hold.args = param.args == null ? new Object[0] : param.args.clone();
                    hold.data = data;
                }
                j.pending = hold;
            }
        }
        if (hold != null) {
            param.setResult(null);
            Log.i("发送结果已按住,等转码完成后放行 " + holdJobsText(hits));
            return;
        }
        boolean anySwapped = false;
        for (Job j : hits) {
            if (j.status == ST_READY && j.out != null && swapInResult(data, j)) {
                Log.i("发送结果里的图片换成合成视频 path=" + j.path);
                LiveSendMarks.remember(j.out.md5, j.out.storePath);
                scheduleDelete(j.out);
                anySwapped = true;
            }
            j.autoSend = false;
            j.pending = null;
            armedPaths.remove(j.path);
            jobs.remove(j.path);
        }
    }

    private static String holdJobsText(List<Job> jobs) {
        StringBuilder sb = new StringBuilder();
        for (Job j : jobs) sb.append(j.path.substring(j.path.lastIndexOf('/') + 1)).append(' ');
        return sb.toString();
    }

    /**
     * 结果回传里命中的 job 列表:预览页发送(autoSend)或用户点过按钮的图(armedPaths)。
     * 网格发送不走 onSendClick,靠 armedPaths 匹配。
     */
    private static List<Job> matchJobs(Intent data) {
        List<Object> list = resultList(data);
        if (list == null) return null;
        List<Job> hits = new java.util.ArrayList<>();
        for (Job j : jobs.values()) {
            boolean inResult = false;
            for (Object o : list) {
                if (o == null) continue;
                if (o == j.autoItem) {
                    inResult = true;
                    break;
                }
                String p = pathOf(o);
                if (p != null && p.equals(j.path)) {
                    inResult = true;
                    break;
                }
            }
            if (inResult && (j.autoSend || armedPaths.contains(j.path))) hits.add(j);
        }
        return hits;
    }

    /** 把结果里的媒体项换成合成视频的副本,相册里的原图对象不动。 */
    private static boolean swapInResult(Intent data, Job j) {
        List<Object> list = resultList(data);
        if (list == null || j.out == null) return false;
        int at = -1;
        for (int i = 0; i < list.size(); i++) {
            Object o = list.get(i);
            if (o == null) continue;
            if (o == j.autoItem) { at = i; break; }
            String p = pathOf(o);
            if (p != null && p.equals(j.path)) { at = i; break; }
        }
        if (at < 0) return false;
        Object copy = copyOf(list.get(at));
        if (copy == null) return false;
        applyVideoFields(copy, j.out);
        list.set(at, copy);
        return true;
    }

    /** 结果 Intent 里装着媒体列表的容器(AlbumResult)。 */
    @SuppressWarnings("unchecked")
    private static List<Object> resultList(Intent data) {
        if (data == null) return null;
        Bundle b;
        try {
            b = data.getExtras();
        } catch (Throwable t) {
            return null;
        }
        if (b == null) return null;
        List<Object> hit = listIn(safeGet(b, "ALBUM_RESULT"));
        if (hit != null) return hit;
        for (String k : b.keySet()) {
            List<Object> l = listIn(safeGet(b, k));
            if (l != null) return l;
        }
        return null;
    }

    private static Object safeGet(Bundle b, String key) {
        try {
            return b.get(key);
        } catch (Throwable t) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Object> listIn(Object o) {
        if (o instanceof List) return (List<Object>) o;
        if (o == null) return null;
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (!List.class.isAssignableFrom(f.getType())) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(o);
                    if (v instanceof List) return (List<Object>) v;
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    /** 从回传参数里找出 Intent(直接参数、列表里的结果项、或 ResultInfo.mResultData 这种字段)。 */
    private static Intent intentIn(Object[] args) {
        if (args == null) return null;
        for (Object a : args) {
            if (a instanceof Intent) return (Intent) a;
        }
        for (Object a : args) {
            if (a instanceof java.util.List) {
                for (Object o : (java.util.List<?>) a) {
                    Intent hit = intentIn(new Object[]{o});
                    if (hit != null) return hit;
                }
            }
        }
        for (Object a : args) {
            if (a == null) continue;
            for (Class<?> c = a.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (f.getType() != Intent.class) continue;
                    try {
                        f.setAccessible(true);
                        Object v = f.get(a);
                        if (v instanceof Intent) return (Intent) v;
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        return null;
    }

    /** 转码完成后放行被按住的发送结果(可能多个 job 共享同一个 Pending)。 */
    private static void releasePending(Job trigger) {
        Pending p = trigger.pending;
        trigger.pending = null;
        if (p == null) return;
        // 收集所有引用同一个 Pending 的 job,统一放行
        List<Job> group = new java.util.ArrayList<>();
        for (Job j : jobs.values()) {
            if (j.pending == p) {
                j.pending = null;
                group.add(j);
            }
        }
        boolean anySwapped = false;
        for (Job j : group) {
            if (j.status == ST_READY && j.out != null && swapInResult(p.data, j)) {
                Log.i("放行发送结果:已换成合成视频 path=" + j.path);
                LiveSendMarks.remember(j.out.md5, j.out.storePath);
                scheduleDelete(j.out);
                anySwapped = true;
            } else {
                Log.i("放行发送结果:按原图发送 path=" + j.path);
                if (j.out != null) {
                    LiveComposer.discard(j.out);
                    j.out = null;
                }
            }
        }
        for (Job j : group) {
            j.autoSend = false;
            armedPaths.remove(j.path);
            jobs.remove(j.path);
        }
        replayIntent = p.data;
        try {
            if (p.method != null) p.method.invoke(p.target, p.args);
        } catch (Throwable t) {
            Log.i("回放发送结果失败: " + t);
        } finally {
            replayIntent = null;
        }
    }

    /** 非结果回传通道:转码完成后重发这次点击(这时状态已是 READY,钩子会换成视频)。 */
    private static void releaseDeferred(Job j) {
        Pending d = j.deferred;
        j.deferred = null;
        if (d == null) return;
        j.autoSend = false;
        try {
            if (d.method != null) d.method.invoke(d.target, d.args);
        } catch (Throwable t) {
            Log.i("重发失败: " + t);
            jobs.remove(j.path);
        }
    }

    private static Pending pendingOf(XC_MethodHook.MethodHookParam param) {
        Pending p = new Pending();
        p.method = param.method instanceof Method ? (Method) param.method : null;
        p.target = param.thisObject;
        p.args = param.args == null ? new Object[0] : param.args.clone();
        return p;
    }

    // ------------------------------------------------------------ 发送替换

    private static void swapIfNeeded(XC_MethodHook.MethodHookParam param) {
        String path = currentPath;
        Object item = currentItem;
        if (path == null || item == null) {
            Log.i("send: 没拿到当前预览项,按原样发送");
            return;
        }
        Job j = jobs.get(path);
        if (j == null) return;
        j.sent = true;
        // 勾了转码且还在转码:让 QQ 正常走完"关预览页 + 回传结果",
        // 回传结果会被按住,转码完成后换成视频再放行 —— 用户先回到聊天界面。
        if (j.checked && j.status == ST_PARSING) {
            if (!takeOverSend(j, item, param.thisObject)) return;
            toast("实况转MP4 转码中…完成后自动发送");
            if (resultChannel(param.thisObject)) {
                // 结果回传通道:等结果回传时再换视频
                return;
            }
            // 兜底:这条通道不靠 Activity 结果回传,只能先拦下这次发送,转码完再重发
            param.setResult(null);
            j.deferred = pendingOf(param);
            Log.i("send: 非结果回传通道,拦下发送等转码完成 path=" + path);
            return;
        }
        if (!j.checked || j.status != ST_READY || j.out == null
                || j.out.file == null || !j.out.file.isFile()) {
            if (j.out != null) {
                LiveComposer.discard(j.out);
                j.out = null;
            }
            // 这次按原图发出去了,勾选状态必须一起清掉:否则回到这张图时按钮还是蓝色选中态,
            // 但解析状态已归零,第一次点击只会取消勾选,看起来像"点了没反应"。
            j.checked = false;
            j.sent = false;
            if (j.status != ST_PARSING) j.status = ST_NONE;
            Log.i("send: 未就绪,按原图发送 path=" + path + " status=" + j.status);
            MAIN.post(LiveSendHook::refreshButtons);
            return;
        }
        LiveComposer.Out out = j.out;
        if (!swapToVideo(param.thisObject, item, out)) {
            Log.i("send: 换视频失败,按原图发送");
            return;
        }
        Log.i("send: 媒体引用换成合成视频副本 " + out.width + "x" + out.height
                + " " + (out.durationUs / 1000) + "ms audio=" + out.hasAudio);
        LiveSendMarks.remember(out.md5, out.storePath);
        jobs.remove(path);
        MAIN.post(() -> {
            refreshButtons();
            scheduleDelete(out);
        });
    }

    /** 只有单图预览才接管发送:多选时不动,免得替用户发出意料外的组合。 */
    private static boolean takeOverSend(Job j, Object item, Object customization) {
        if (j.autoSend) return true;
        List<Object> list = sendListOf(customization);
        if (list == null || list.size() != 1 || list.get(0) != item) {
            Log.i("send: 发送列表不是单图(size=" + (list == null ? "?" : list.size())
                    + "),不接管,按原图发送");
            return false;
        }
        j.autoSend = true;
        j.autoItem = item;
        return true;
    }

    /**
     * 相册是不是用"Activity#setResult"把结果回传给聊天页。
     * 是的话才能按住结果、延后发送(用户先回聊天界面);不是就只能拦下发送等转码完成。
     * 依据:相册以"选择器"模式打开时 needActivityResult 为 true,走 setResult 回传。
     */
    private static boolean resultChannel(Object customization) {
        boolean calling = false;
        try {
            Object act = XposedHelpers.callMethod(customization, "getActivity");
            if (act instanceof android.app.Activity) {
                calling = ((android.app.Activity) act).getCallingActivity() != null;
            }
        } catch (Throwable t) {
            Log.i("calling activity 探测失败: " + t);
        }
        Boolean probe = needActivityResult();
        boolean result = calling && (probe == null || probe);
        Log.i("结果回传通道: calling=" + calling + " needActivityResult=" + probe
                + " → " + result);
        return result;
    }

    /** 反射相册内部的选择器标记 needActivityResult;拿不到返回 null。 */
    private static Boolean needActivityResult() {
        try {
            ClassLoader cl = HookEntry.baseCl != null ? HookEntry.baseCl : LiveSendHook.class.getClassLoader();
            Class<?> c = XposedHelpers.findClassIfExists(
                    "com.tencent.qqnt.qbasealbum.inject.QAlbumPickerContext", cl);
            if (c == null) return null;
            Object inst = null;
            for (Field f : c.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers()) || f.getType() != c) continue;
                f.setAccessible(true);
                Object v = f.get(null);
                if (v != null) {
                    inst = v;
                    break;
                }
            }
            if (inst == null) return null;
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals("i")) continue;
                if (m.getParameterTypes().length != 0 || m.getReturnType() != boolean.class) continue;
                m.setAccessible(true);
                Object r = m.invoke(inst);
                if (r instanceof Boolean) return (Boolean) r;
            }
        } catch (Throwable t) {
            Log.i("needActivityResult 探测失败: " + t);
        }
        return null;
    }

    /**
     * 把发送列表里的媒体项换成合成视频的副本。
     * 不就地改写相册共享的那个 LocalMediaInfo:就地改会让相册里的这张实况图之后
     * 一律显示成视频(半屏预览、全屏图库都只看得到视频),发不了纯图片。
     */
    private static boolean swapToVideo(Object customization, Object item, LiveComposer.Out out) {
        if (out.storeId < 0 || out.storePath == null) return false;
        List<Object> list = sendListOf(customization);
        if (list == null) return false;
        int at = -1;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) == item) { at = i; break; }
        }
        Object copy = copyOf(item);
        if (copy == null) return false;
        applyVideoFields(copy, out);
        if (at >= 0) {
            list.set(at, copy);
            return true;
        }
        // 不在发送列表里(极少数情况):只能就地改,保证发出去的是视频
        Log.i("send: 媒体项不在发送列表,就地改写");
        applyVideoFields(item, out);
        return true;
    }

    /** 深拷贝一个 LocalMediaInfo:优先走 Parcel,失败再按字段复制。 */
    private static Object copyOf(Object item) {
        try {
            Parcel p = Parcel.obtain();
            try {
                XposedHelpers.callMethod(item, "writeToParcel", p, 0);
                p.setDataPosition(0);
                Object creator = XposedHelpers.getStaticObjectField(item.getClass(), "CREATOR");
                if (creator instanceof Parcelable.Creator) {
                    Object copy = ((Parcelable.Creator) creator).createFromParcel(p);
                    if (copy != null) return copy;
                }
            } finally {
                p.recycle();
            }
        } catch (Throwable t) {
            Log.i("按 Parcel 复制媒体项失败: " + t);
        }
        try {
            Object copy = item.getClass().getDeclaredConstructor().newInstance();
            for (Class<?> c = item.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    f.setAccessible(true);
                    try {
                        f.set(copy, f.get(item));
                    } catch (Throwable ignored) {
                    }
                }
            }
            return copy;
        } catch (Throwable t) {
            Log.i("按字段复制媒体项失败: " + t);
        }
        return null;
    }

    private static void applyVideoFields(Object target, LiveComposer.Out out) {
        File published = new File(out.storePath);
        long len = published.length() > 0 ? published.length() : out.file.length();
        long ms = out.durationUs / 1000L;
        call(target, "set_id", Long.valueOf(out.storeId));
        call(target, "setPath", out.storePath);
        call(target, "setSystemMeidaStore", true);
        call(target, "setMMimeType", "video/mp4");
        call(target, "setVideoMimeType", "video/mp4");
        call(target, "setFileSize", Long.valueOf(len));
        call(target, "setVideoDuration", Long.valueOf(ms));
        call(target, "setMTotalDuration", Long.valueOf(ms));
        call(target, "setMStartTime", Long.valueOf(0L));
        call(target, "setMediaWidth", out.width);
        call(target, "setMediaHeight", out.height);
        call(target, "setMediaOriginWidth", out.width);
        call(target, "setMediaOriginHeight", out.height);
        call(target, "setMediaOriginSize", Long.valueOf(len));
        call(target, "setMHasAudioTrack", out.hasAudio);
        // 类型换算放在 mime 写完之后:MediaTypeHelper 是按 mMimeType 主段查表的。
        int type = videoTypeOf(target);
        call(target, "setMMediaType", type);
    }

    /** onSendClick 实际发送的 List<LocalMediaInfo>(getPhotoCommonData().h()),拿不到返回 null。 */
    private static java.util.List<Object> sendListOf(Object customization) {
        try {
            Object pc = XposedHelpers.callMethod(customization, "getPhotoCommonData");
            if (pc == null) return null;
            Object list = XposedHelpers.callMethod(pc, "h");
            return list instanceof java.util.List ? (java.util.List<Object>) list : null;
        } catch (Throwable t) {
            Log.i("send list unavailable: " + t);
            return null;
        }
    }

    private static void toast(String msg) {
        try {
            android.content.Context ctx = HookEntry.appCtx;
            if (ctx == null) return;
            MAIN.post(() -> {
                try {
                    android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show();
                } catch (Throwable ignored) {
                }
            });
        } catch (Throwable ignored) {
        }
    }

    /** 只挂该类自己声明的方法:基类已经挂过,重复注册会撞 LSPosed 的回调数量上限。 */
    private static void hookDeclared(Class<?> owner, String name, XC_MethodHook cb) {
        int n = 0;
        for (Class<?> c = owner; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(name)) continue;
                if (!hookedMethods.add(m)) continue;
                try {
                    XposedBridge.hookMethod(m, cb);
                    n++;
                } catch (Throwable t) {
                    Log.i("hook " + c.getName() + '.' + name + " failed: " + t);
                }
            }
            if (n > 0) break;
        }
        if (n == 0) Log.i("预览类 " + owner.getName() + " 没有自己声明的 " + name);
    }

    /**
     * 每次点发送记一行当前预览项的关键字段。
     * 用途:与 QQ 原生发视频时的取值对照(时长/尺寸/单位这类只能靠真实数据校准)。
     */
    private static void dumpItem() {
        Object item = currentItem;
        if (item == null) return;
        StringBuilder sb = new StringBuilder("send item: ");
        for (String g : new String[]{"getPath", "getMMediaType", "getVideoDuration",
                "getMTotalDuration", "getMStartTime", "getFileSize", "getMediaWidth",
                "getMediaHeight", "getMHasAudioTrack", "getMMimeType", "get_id"}) {
            try {
                sb.append(g).append('=').append(XposedHelpers.callMethod(item, g)).append(' ');
            } catch (Throwable t) {
                sb.append(g).append("=? ");
            }
        }
        try {
            sb.append("sysStore=").append(XposedHelpers.getObjectField(item, "isSystemMeidaStore"));
        } catch (Throwable ignored) {
        }
        Log.i(sb.toString());
    }

    // ------------------------------------------------------------ 按钮

    /**
     * QQ 会重建底栏子 View(选图、切原图态都会),按钮被抹掉时靠这个看门人补挂回去。
     * 只盯 getBottomView 返回的那个底栏不够:实测第二次打开相册面板时底栏换成了
     * LinearLayout 容器、getBottomView 也不再被调用,所以同样在 DecorView 上挂一份兜底。
     */
    private static void watch(final View bottom) {
        watchRoot(bottom.getRootView());
        synchronized (watchers) {
            if (watchers.containsKey(bottom)) return;
            android.view.ViewTreeObserver.OnGlobalLayoutListener l =
                    new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                        @Override
                        public void onGlobalLayout() {
                            if (!bottom.isAttachedToWindow() || !bottom.isShown()) return;
                            if (bottom.findViewById(BTN_ID) == null) injectButton(bottom, 0);
                        }
                    };
            try {
                bottom.getViewTreeObserver().addOnGlobalLayoutListener(l);
                watchers.put(bottom, l);
            } catch (Throwable t) {
                Log.i("底栏看门人注册失败: " + t);
            }
        }
    }

    /** DecorView 级兜底:任何一次布局只要看到"原图/编辑 + 发送"的底栏而没有按钮,就补一个。 */
    private static void watchRoot(final View root) {
        if (root == null) return;
        synchronized (watchers) {
            if (watchers.containsKey(root)) return;
            android.view.ViewTreeObserver.OnGlobalLayoutListener l =
                    new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                        @Override
                        public void onGlobalLayout() {
                            if (root.getWindowVisibility() != View.VISIBLE) return;
                            long now = System.currentTimeMillis();
                            if (now - lastEnsure < 400L) return;
                            lastEnsure = now;
                            ensureButton(root);
                        }
                    };
            try {
                root.getViewTreeObserver().addOnGlobalLayoutListener(l);
                watchers.put(root, l);
            } catch (Throwable t) {
                Log.i("根看门人注册失败: " + t);
            }
        }
    }

    private static volatile long lastEnsure;

    /** 窗口级"没有预览底栏"负缓存:聊天页常驻但永远没有原图/编辑,别每次布局都全树扫。 */
    private static final java.util.WeakHashMap<View, Long> noAnchorUntil = new java.util.WeakHashMap<>();

    /** 按钮从其父容器(含浮层态)上摘掉;面板关闭时必须收干净,否则残留在聊天界面上。 */
    private static void removeButton(View root) {
        View b = root.findViewById(BTN_ID);
        if (b != null && b.getParent() instanceof ViewGroup) {
            ((ViewGroup) b.getParent()).removeView(b);
            synchronized (buttons) {
                buttons.values().remove(b);
            }
        }
    }

    private static void ensureButton(View root) {
        if (root == null) return;
        try {
            ensureGridAdapter(root);
            Long until = noAnchorUntil.get(root);
            long now = System.currentTimeMillis();
            boolean scanning = until == null || now >= until;
            TextView anchor = scanning ? findPreviewAnchor(root) : peek(cachedAnchor);
            if (anchor == null || !alive(anchor)) {
                // 只有真正扫过确认没有底栏才收按钮:面板开着时负缓存期间不动它
                if (scanning) {
                    removeButton(root);
                    PreviewBadge.hide(root);
                    noAnchorUntil.put(root, now + 1500L);
                    currentPath = null;
                    cachedAnchor = null;
                    cachedSend = null;
                }
                return;
            }
            noAnchorUntil.remove(root);
            TextView existing = root.findViewById(BTN_ID);
            if (existing != null) {
                place(existing, anchor, (ViewGroup) anchor.getParent());
                return;
            }
            injectInto(anchor.getContext(), anchor, "decor");
        } catch (Throwable t) {
            Log.i("兜底补挂失败: " + t);
        }
    }

    /**
     * 在"有原图/编辑 + 发送"的底栏里找锚点。QQ NT 半屏相册面板/全屏相册的网格底栏
     * ([相册][编辑][原图][发送])就是要挂按钮的地方,不再按"相册"文案排除(实测误杀)。
     * 命中过的锚点缓存起来,布局风暴(左右滑动)时只做"还活着"的廉价校验,不再全树扫描。
     */
    private static java.lang.ref.WeakReference<TextView> cachedAnchor;
    private static java.lang.ref.WeakReference<TextView> cachedSend;

    private static TextView findPreviewAnchor(View root) {
        TextView hit = peek(cachedAnchor);
        if (hit != null && alive(hit)) {
            TextView send = peek(cachedSend);
            if (send != null && alive(send)) return hit;
            TextView again = findSendText(root);
            if (again != null) {
                cachedSend = new java.lang.ref.WeakReference<>(again);
                return hit;
            }
            return null;
        }
        TextView anchor = findText(root, "原图");
        if (anchor == null) anchor = findText(root, "编辑");
        if (anchor == null) return null;
        ViewGroup bar = anchor.getParent() instanceof ViewGroup
                ? (ViewGroup) anchor.getParent() : null;
        if (bar == null) return null;
        TextView send = findSendText(bar);
        if (send == null) send = findSendText(root);
        if (send == null) return null;
        cachedAnchor = new java.lang.ref.WeakReference<>(anchor);
        cachedSend = new java.lang.ref.WeakReference<>(send);
        return anchor;
    }

    private static TextView peek(java.lang.ref.WeakReference<TextView> r) {
        return r == null ? null : r.get();
    }

    private static boolean alive(View v) {
        return v.isAttachedToWindow() && v.isShown() && v.getWidth() > 0;
    }

    /** 底栏(含子树)的全部文字,诊断锚点判定用。 */
    private static String textsOf(View v) {
        StringBuilder sb = new StringBuilder();
        collectTexts(v, sb);
        String s = sb.toString();
        return s.length() > 200 ? s.substring(0, 200) : s;
    }

    private static void collectTexts(View v, StringBuilder sb) {
        if (v instanceof TextView) {
            CharSequence t = ((TextView) v).getText();
            if (t != null && t.length() > 0) sb.append('[').append(t).append(']');
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collectTexts(g.getChildAt(i), sb);
        }
    }

    private static void injectButton(View bottom, int retry) {
        try {
            if (bottom.findViewById(BTN_ID) != null) return;
            TextView anchor = findAnchor(bottom);
            if (anchor == null) {
                if (retry < ANCHOR_RETRY_MAX) {
                    bottom.postDelayed(() -> injectButton(bottom, retry + 1), 250L * (retry + 1));
                } else {
                    Log.i("实况转MP4 按钮:没找到原图/编辑锚点,放弃挂载 bar=" + textsOf(bottom));
                }
                return;
            }
            injectInto(bottom.getContext(), anchor, "bar");
        } catch (Throwable t) {
            Log.i("inject button failed: " + t);
        }
    }

    private static void injectInto(Context ctx, TextView anchor, String from) {
        if (!LiveSettings.sendParseEnabled(ctx)) return;
        final ViewGroup bar = anchor.getParent() instanceof ViewGroup
                ? (ViewGroup) anchor.getParent() : null;
        if (bar == null) return;
        final TextView btn = new TextView(ctx);
        btn.setId(BTN_ID);
        btn.setSingleLine(true);
        btn.setGravity(android.view.Gravity.CENTER);
        btn.setPadding(dp(ctx, 8), dp(ctx, 3), dp(ctx, 8), dp(ctx, 3));
        btn.setTextSize(TypedValue.COMPLEX_UNIT_PX, anchor.getTextSize());
        ViewGroup.LayoutParams lp = new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        int at = Math.min(bar.indexOfChild(anchor) + 1, bar.getChildCount());
        bar.addView(btn, at, lp);
        synchronized (buttons) {
            buttons.put(bar, btn);
        }
        btn.setOnClickListener(v -> toggle(btn));
        btn.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> place(btn, anchor, bar));
        style(btn);
        label(btn);
        place(btn, anchor, bar);
        btn.post(() -> place(btn, anchor, bar));
        btn.postDelayed(() -> place(btn, anchor, bar), 400L);
        // 底栏被裁剪时按钮会看不见,这时把按钮挪到窗口根上做浮层,保证半屏预览也能看到。
        btn.postDelayed(() -> {
            if (!visible(btn)) liftToOverlay(btn, anchor, bar);
        }, 500L);
        Log.i("实况转MP4 按钮已挂上(" + from + ") bar=" + bar.getClass().getName());
        logHostActivity(bar);
    }

    /** 底栏装不下时把按钮搬到窗口根做浮层,位置仍按"原图"按钮右边算。 */
    private static void liftToOverlay(TextView btn, TextView anchor, ViewGroup bar) {
        try {
            ViewGroup root = anchor.getRootView() instanceof ViewGroup
                    ? (ViewGroup) anchor.getRootView() : null;
            if (root == null) return;
            ViewGroup.LayoutParams lp = new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (btn.getParent() == bar) bar.removeView(btn);
            root.addView(btn, lp);
            place(btn, anchor, root);
            btn.post(() -> place(btn, anchor, root));
            Log.i("实况转MP4 按钮改为浮层显示");
        } catch (Throwable t) {
            Log.i("按钮浮层化失败: " + t);
        }
    }

    private static boolean visible(View v) {
        try {
            android.graphics.Rect r = new android.graphics.Rect();
            return v.getGlobalVisibleRect(r) && r.width() > 0 && r.height() > 0;
        } catch (Throwable t) {
            return true;
        }
    }

    /** 首次进入某个预览页时记一行宿主 Activity(半屏面板与全屏图库是不同类)。 */
    private static final java.util.Set<String> seenHosts =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private static void logHostActivity(View bottom) {
        try {
            android.content.Context c = bottom.getContext();
            while (c instanceof android.content.ContextWrapper
                    && !(c instanceof android.app.Activity)) {
                c = ((android.content.ContextWrapper) c).getBaseContext();
            }
            if (c == null) return;
            String name = c.getClass().getName();
            if (seenHosts.add(name)) {
                Log.i("实况转MP4 宿主 Activity: " + name);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 优先锚"原图";没有原图开关的会话(如"我的电脑")退回"编辑",再退回"发送"。 */
    private static TextView findAnchor(View root) {
        TextView hit = findText(root, "原图");
        if (hit == null) hit = findText(root, "编辑");
        if (hit == null) hit = findSendText(root);
        return hit;
    }

    /** 底栏里同名文本可能有多个(折叠面板里的副本宽度为 0),只认已经排好版的那个。 */
    private static TextView findText(View root, String want) {
        if (root instanceof TextView && want.equals(((TextView) root).getText())
                && root.isShown() && root.getWidth() > 0) {
            return (TextView) root;
        }
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                TextView hit = findText(g.getChildAt(i), want);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    /** "发送"按钮文案可能带数量(如"发送(3)"),按前缀找。 */
    private static TextView findSendText(View root) {
        if (root instanceof TextView) {
            CharSequence t = ((TextView) root).getText();
            if (t != null && t.toString().startsWith("发送") && root.isShown() && root.getWidth() > 0) {
                return (TextView) root;
            }
        }
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                TextView hit = findSendText(g.getChildAt(i));
                if (hit != null) return hit;
            }
        }
        return null;
    }

    /**
     * 底栏可能是 ConstraintLayout(新加的无约束子 View 会落在 0,0),也可能是 LinearLayout
     * (按插入位置自然排布)。用 getLocationInWindow 量"现在在哪",再按差值补 translation,
     * 对任何父容器都成立,重复调用也只是收敛到目标位置。
     */
    private static void place(TextView btn, TextView anchor, ViewGroup bar) {
        try {
            if (anchor.getWidth() == 0 || btn.getWidth() == 0) return;
            int[] a = new int[2];
            int[] b = new int[2];
            anchor.getLocationInWindow(a);
            btn.getLocationInWindow(b);
            int targetX = a[0] + anchor.getWidth() + dp(bar.getContext(), 10);
            int targetY = a[1] + (anchor.getHeight() - btn.getHeight()) / 2;
            int dx = targetX - b[0];
            int dy = targetY - b[1];
            if (Math.abs(dx) <= 1 && Math.abs(dy) <= 1) return;
            btn.setTranslationX(btn.getTranslationX() + dx);
            btn.setTranslationY(btn.getTranslationY() + dy);
        } catch (Throwable ignored) {
        }
    }

    private static void toggle(TextView btn) {
        String path = currentPath;
        if (path == null) {
            // 网格态(半屏/全屏相册面板):按当前勾选的那张图启用/取消
            String picked = selectedGridPath();
            if (picked == null) {
                toast("先勾选一张图片(一次一张)");
                return;
            }
            Job j = job(picked);
            if (armedPaths.contains(picked)) {
                // 再点一次 = 取消:发送时按原图发出,产物留作预热
                armedPaths.remove(picked);
                j.checked = false;
                j.autoSend = false;
                j.autoItem = null;
                if (j.pending != null) releasePending(j);
                if (j.deferred != null) releaseDeferred(j);
                toast("已取消该图的实况转MP4");
            } else {
                armedPaths.add(picked);
                j.checked = true;
                j.sent = false;
                if (j.status == ST_NONE && !j.autoQueued) {
                    j.autoQueued = true;
                    startParse(picked, j);
                }
            }
            refreshButtons();
            return;
        }
        if (!LiveSettings.sendParseEnabled(btn.getContext())) {
            Log.i("实况转MP4:功能已在设置里关闭,忽略点击");
            return;
        }
        Job j = job(path);
        j.checked = !j.checked;
        if (j.checked) {
            armedPaths.add(path);
            if (j.status != ST_READY && j.status != ST_PARSING) {
                j.sent = false;
                startParse(path, j);
            }
        } else {
            armedPaths.remove(path);
            // 用户主动取消:产物回收;被按住的发送结果也一并放行(按原图发出去)
            if (j.out != null) {
                LiveComposer.discard(j.out);
                j.out = null;
                j.status = ST_NONE;
            }
            j.autoSend = false;
            j.autoItem = null;
            if (j.pending != null) releasePending(j);
            if (j.deferred != null) releaseDeferred(j);
        }
        style(btn);
        label(btn);
    }

    /** 最近挂上网格角标钩子的 adapter(弱引用,防面板关闭后持旧实例)。 */
    private static java.lang.ref.WeakReference<Object> gridAdapterRef;

    /** 网格里当前被勾选的那张图(恰好一张才返回路径)。 */
    private static String selectedGridPath() {
        Object adapter = gridAdapterRef == null ? null : gridAdapterRef.get();
        java.util.List<Object> list = adapter == null ? null : adapterListOf(adapter);
        if (list == null) {
            Log.i("网格按钮: 数据表不可用 adapter="
                    + (adapter == null ? "null" : adapter.getClass().getName()));
            return null;
        }
        String hit = null;
        int picked = 0;
        int withPath = 0;
        boolean anySelected = false;
        for (Object o : list) {
            if (o == null) continue;
            boolean sel = isSelectedItem(o);
            if (sel) anySelected = true;
            String p = pathOfLoose(o);
            if (p == null) continue;
            withPath++;
            if (sel) {
                hit = p;
                picked++;
            }
        }
        Log.i("网格按钮: 总数=" + list.size() + " 带路径=" + withPath
                + " 勾选=" + picked + " anySelected=" + anySelected);
        if (!anySelected && withPath > 0) dumpItemShape(list);
        return picked == 1 ? hit : null;
    }

    /** 选中态探测失败时,把数据项与 adapter 的字段结构 dump 一次用于校准。 */
    private static final java.util.Set<String> dumpedShapes =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String, Boolean>());

    private static void dumpItemShape(java.util.List<Object> list) {
        try {
            Object sample = null;
            for (Object o : list) {
                if (o != null && pathOfLoose(o) != null) {
                    sample = o;
                    break;
                }
            }
            if (sample == null) return;
            String cls = sample.getClass().getName();
            if (!dumpedShapes.add(cls)) return;
            StringBuilder sb = new StringBuilder("数据项字段(").append(cls).append("): ");
            for (Class<?> k = sample.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
                for (Field f : k.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    f.setAccessible(true);
                    Object v;
                    try {
                        v = f.get(sample);
                    } catch (Throwable ignored) {
                        continue;
                    }
                    String vs = v == null ? "null" : (v instanceof String
                            ? "'" + (v.toString().length() > 40 ? v.toString().substring(0, 40) + ".." : v) + "'"
                            : String.valueOf(v));
                    if (vs.length() > 60) vs = vs.substring(0, 60);
                    sb.append(f.getName()).append(':').append(f.getType().getSimpleName())
                            .append('=').append(vs).append("; ");
                }
            }
            String s = sb.toString();
            Log.i(s.length() > 1500 ? s.substring(0, 1500) : s);
        } catch (Throwable ignored) {
        }
    }

    private static void startParse(String path, Job j) {
        j.status = ST_PARSING;
        Log.i("实况转MP4 启动: " + path);
        LiveComposer.composeAsync(path, (status, src, out, err) -> {
            Job jj = job(src);
            if (status == LiveComposer.OK) {
                jj.status = ST_READY;
                jj.out = out;
            } else {
                jj.status = status == LiveComposer.NOT_LIVE ? ST_NOT_LIVE : ST_FAILED;
            }
            Log.i("实况转MP4 结果 " + src + " -> status=" + jj.status
                    + (err == null ? "" : " err=" + err));
            if (jj.pending != null) {
                MAIN.post(() -> releasePending(jj));
            } else if (jj.deferred != null) {
                MAIN.post(() -> releaseDeferred(jj));
            } else if (jj.autoSend) {
                // 结果还没回传(QQ 可能走了别的发送通道):等一下再看,超时就当原图已发出
                MAIN.postDelayed(() -> {
                    if (!jj.autoSend || jj.pending != null || jj.deferred != null) return;
                    Log.i("发送结果没有回传,按原图已发送处理 path=" + jj.path);
                    jj.autoSend = false;
                    jobs.remove(jj.path);
                    if (jj.out != null) {
                        LiveComposer.discard(jj.out);
                        jj.out = null;
                        jj.status = ST_NONE;
                    }
                }, RESULT_WAIT_MS);
            } else if (status == LiveComposer.OK
                    && (jj.sent || (!jj.checked && !armedPaths.contains(src)))) {
                // 解析完成时这张图已经发出去了(或用户已取消且没点过按钮),产物没有用处,立刻回收。
                // 自动排队的预热产物保留:用户稍后点按钮可以直接用。
                LiveComposer.discard(out);
                jj.status = ST_NONE;
                jj.out = null;
            }
            if (src.equals(currentPath)) refreshButtons();
            if (armedPaths.contains(src)) refreshButtons();
        });
    }

    private static void refreshButtons() {
        synchronized (buttons) {
            for (TextView btn : buttons.values()) {
                if (btn == null) continue;
                btn.post(() -> {
                    style(btn);
                    label(btn);
                });
            }
        }
    }

    /** 设置里关掉「实况转MP4」:撤掉已经挂上的按钮,并把未发送的合成产物回收掉。 */
    static void onParseDisabled() {
        MAIN.post(() -> {
            try {
                synchronized (buttons) {
                    for (TextView btn : new java.util.ArrayList<>(buttons.values())) {
                        if (btn == null) continue;
                        ViewGroup parent = btn.getParent() instanceof ViewGroup
                                ? (ViewGroup) btn.getParent() : null;
                        if (parent != null) parent.removeView(btn);
                    }
                    buttons.clear();
                }
                for (Job j : jobs.values()) {
                    j.checked = false;
                    j.autoSend = false;
                    j.autoItem = null;
                    LiveComposer.Out old = j.out;
                    j.out = null;
                    if (j.pending != null) releasePending(j);
                    if (j.deferred != null) releaseDeferred(j);
                    if (old != null) LiveComposer.discard(old);
                    if (j.status != ST_PARSING) j.status = ST_NONE;
                }
                Log.i("setting off: 已撤下实况转MP4 按钮");
            } catch (Throwable t) {
                Log.i("remove parse buttons failed: " + t);
            }
        });
    }

    private static void style(TextView btn) {
        boolean on;
        if (currentPath != null) {
            Job j = jobs.get(currentPath);
            on = j != null && j.checked;
        } else {
            on = !armedPaths.isEmpty();
        }
        Context ctx = btn.getContext();
        if (on) {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xFF12B7F5);
            bg.setCornerRadius(dp(ctx, 12));
            btn.setBackground(bg);
            btn.setTextColor(0xFFFFFFFF);
        } else {
            btn.setBackground(null);
            btn.setTextColor(isDark(ctx) ? 0xFFFFFFFF : 0xFF111111);
        }
    }

    private static boolean isDark(Context ctx) {
        try {
            int night = ctx.getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            return night == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable t) {
            return true;
        }
    }

    private static void label(TextView btn) {
        if (currentPath != null) {
            Job j = jobs.get(currentPath);
            if (j != null && j.checked) {
                btn.setText(stateText(j, 1, 1));
                return;
            }
            btn.setText("实况转MP4");
            return;
        }
        // 网格态:按 armed 集合显示总体进度
        if (armedPaths.isEmpty()) {
            btn.setText("实况转MP4");
            return;
        }
        int total = 0, ready = 0, parsing = 0, failed = 0;
        for (String p : armedPaths) {
            Job j = jobs.get(p);
            if (j == null) continue;
            total++;
            if (j.status == ST_READY) ready++;
            else if (j.status == ST_PARSING) parsing++;
            else if (j.status == ST_FAILED || j.status == ST_NOT_LIVE) failed++;
        }
        if (total == 0) {
            btn.setText("实况转MP4");
        } else if (parsing > 0) {
            btn.setText("转换中(" + Math.min(ready + 1, total) + "/" + total + ")");
        } else if (failed > 0) {
            btn.setText("部分失败(" + ready + "/" + total + ")");
        } else {
            btn.setText("全部转换完成");
        }
    }

    /** 单张图的按钮文案。 */
    private static String stateText(Job j, int k, int n) {
        switch (j.status) {
            case ST_PARSING: return "转换中(" + k + "/" + n + ")";
            case ST_READY: return "全部转换完成";
            case ST_NOT_LIVE: return "非实况图";
            case ST_FAILED: return "转码失败";
            default: return "实况转MP4";
        }
    }

    // ------------------------------------------------------------ 预览角标

    /** 预览页图片右上角挂一个实况角标:图还是原图,只是提示这是实况。 */
    private static void refreshPreviewBadge(Object customization) {
        try {
            View root = rootOf(customization);
            if (root == null) return;
            String path = currentPath;
            if (path == null || findPreviewAnchor(root) == null) {
                PreviewBadge.hide(root);
                return;
            }
            Boolean known = liveVerdict.get(path);
            if (known == null) {
                PreviewBadge.hide(root);
                IO.execute(() -> {
                    boolean live = LivePhotoDetector.isLiveFile(path);
                    liveVerdict.put(path, live);
                    MAIN.post(() -> {
                        if (path.equals(currentPath)) refreshPreviewBadge(customization);
                    });
                });
                return;
            }
            if (known) {
                PreviewBadge.show(root);
            } else {
                PreviewBadge.hide(root);
            }
        } catch (Throwable t) {
            Log.i("预览角标刷新失败: " + t);
        }
    }

    private static View rootOf(Object customization) {
        try {
            Object act = XposedHelpers.callMethod(customization, "getActivity");
            if (!(act instanceof android.app.Activity)) return null;
            android.app.Activity a = (android.app.Activity) act;
            View content = a.findViewById(android.R.id.content);
            return content != null ? content : a.getWindow().getDecorView();
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------ 相册网格实况角标

    /**
     * 相册(半屏/全屏通用)网格缩略图左上角的实况角标:
     * 挂网格 adapter 的 onBindViewHolder,按条目文件路径做文件级判定(结果按路径缓存),
     * 判定是实况就在缩略图左上角贴点环角标。滚动时增量判定,不动已有数据。
     */
    private static final int GRID_BADGE_ID = 0x7e0b1e22;
    private static final Map<View, String> cellPaths = new WeakHashMap<>();
    private static final java.util.Set<Class<?>> gridHooked =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<Class<?>, Boolean>());
    private static final Map<Class<?>, Field> holderPathFields = new WeakHashMap<>();
    private static final Map<Object, java.util.List<Object>> adapterLists = new WeakHashMap<>();
    private static volatile long lastGridTry;

    /** 相册窗口里发现 RecyclerView 就挂它的 adapter(每类一次,带数据校验)。 */
    private static final java.util.WeakHashMap<View, Integer> gridRetries = new java.util.WeakHashMap<>();

    private static void ensureGridAdapter(View root) {
        long now = System.currentTimeMillis();
        if (now - lastGridTry < 2000L || !(root instanceof ViewGroup)) return;
        lastGridTry = now;
        java.util.List<View> rvs = new java.util.ArrayList<>();
        collectRecyclerViews((ViewGroup) root, rvs);
        boolean anyPending = false;
        for (View rv : rvs) {
            try {
                Object adapter = XposedHelpers.callMethod(rv, "getAdapter");
                if (adapter == null) continue;
                if (hookGridAdapter(adapter)) {
                    scanExistingCells(rv, adapter);
                } else {
                    anyPending = true;
                }
            } catch (Throwable ignored) {
            }
        }
        // 面板刚打开时数据表常常还是空的:安排延迟重试(面板静止后没有布局事件可依赖)
        if (anyPending && root instanceof View) {
            Integer n = gridRetries.get(root);
            int tried = n == null ? 0 : n;
            if (tried < 6) {
                gridRetries.put(root, tried + 1);
                ((View) root).postDelayed(() -> {
                    lastGridTry = 0;
                    ensureGridAdapter(root);
                }, 1200L);
            }
        }
    }

    private static void collectRecyclerViews(View v, java.util.List<View> out) {
        if ("androidx.recyclerview.widget.RecyclerView".equals(v.getClass().getName())) out.add(v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collectRecyclerViews(g.getChildAt(i), out);
        }
    }

    /** 数据项带路径的 adapter 才是媒体网格;数据未到(面板刚开)返回 false 等重试。 */
    private static boolean hookGridAdapter(Object adapter) {
        Class<?> c = adapter.getClass();
        java.util.List<Object> data = adapterListOf(adapter);
        if (data == null || data.isEmpty()) {
            if (data == null && gridHooked.add(c)) {
                Log.i("网格候选 adapter 排除(数据项无路径): " + c.getName());
            }
            return false;
        }
        if (!gridHooked.add(c)) return true;
        gridAdapterRef = new java.lang.ref.WeakReference<>(adapter);
        int n = 0;
        for (Class<?> k = c; k != null && k != Object.class && n == 0; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) {
                if (!m.getName().equals("onBindViewHolder")) continue;
                if (!hookedMethods.add(m)) continue;
                try {
                    XposedBridge.hookMethod(m, BIND_HOOK);
                    n++;
                } catch (Throwable t) {
                    Log.i("网格 onBind 挂载失败: " + t);
                }
            }
        }
        Log.i("相册网格 adapter 已挂: " + c.getName() + " onBind=" + n);
        return true;
    }

    /** 钩子挂上后,已显示的格子不会重绑:按位置补一次角标与自动排队。 */
    private static void scanExistingCells(View rv, Object adapter) {
        if (!(rv instanceof ViewGroup)) return;
        java.util.List<Object> data = adapterListOf(adapter);
        if (data == null) return;
        ViewGroup g = (ViewGroup) rv;
        int done = 0;
        for (int i = 0; i < g.getChildCount(); i++) {
            View child = g.getChildAt(i);
            try {
                Object posObj = XposedHelpers.callMethod(rv, "getChildAdapterPosition", child);
                if (!(posObj instanceof Integer)) continue;
                int pos = (Integer) posObj;
                if (pos < 0 || pos >= data.size()) continue;
                Object item = data.get(pos);
                String path = pathOfLoose(item);
                bindCellBadge(child, path);
                if (path != null) {
                    if (isSelectedItem(item)) autoArmIfNeeded(path);
                    else autoQueue(path);
                    done++;
                }
            } catch (Throwable ignored) {
            }
        }
        if (done > 0) Log.i("网格补扫完成: " + done + " 格");
    }

    private static final XC_MethodHook BIND_HOOK = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                if (param.args == null || param.args.length == 0) return;
                Object holder = param.args[0];
                if (holder == null) return;
                View item = (View) XposedHelpers.getObjectField(holder, "itemView");
                Object data = null;
                String path = pathFromHolder(holder);
                if (path == null) {
                    Integer pos = null;
                    for (Object a : param.args) {
                        if (a instanceof Integer) {
                            pos = (Integer) a;
                            break;
                        }
                    }
                    java.util.List<Object> list = adapterListOf(param.thisObject);
                    if (pos != null && list != null && pos >= 0 && pos < list.size()) {
                        data = list.get(pos);
                        path = pathOfLoose(data);
                    }
                }
                bindCellBadge(item, path);
                // 面板打开即自动排队转码(先后顺序,LiveComposer 单线程天然串行);
                // 用户勾选/预览选中时再置 armed,发送时才替换成视频
                if (path != null) {
                    if (data != null && isSelectedItem(data)) {
                        autoArmIfNeeded(path);
                    } else {
                        autoQueue(path);
                    }
                }
            } catch (Throwable t) {
                Log.i("网格 onBind 处理失败: " + t);
            }
        }
    };

    /** LocalMediaInfo 的选中态:方法名/字段名按常见形态试,全失败当未选中。 */
    private static boolean isSelectedItem(Object item) {
        for (String g : new String[]{"isIsSelected", "getIsSelected", "isSelected",
                "isChecked", "getChecked", "isCheck"}) {
            try {
                Object r = XposedHelpers.callMethod(item, g);
                if (r instanceof Boolean) return (Boolean) r;
            } catch (Throwable ignored) {
            }
        }
        for (Class<?> c = item.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType() != boolean.class && f.getType() != Boolean.class) continue;
                String n = f.getName().toLowerCase(java.util.Locale.US);
                if (!n.contains("select") && !n.contains("check")) continue;
                f.setAccessible(true);
                try {
                    Object v = f.get(item);
                    if (v instanceof Boolean) return (Boolean) v;
                } catch (Throwable ignored) {
                }
            }
        }
        return false;
    }

    /** ViewHolder 字段里找媒体对象(带 getPath),按 VH 类缓存字段。 */
    private static String pathFromHolder(Object holder) {
        Class<?> c = holder.getClass();
        Field f;
        synchronized (holderPathFields) {
            f = holderPathFields.get(c);
        }
        if (f != null) {
            try {
                return pathOfLoose(f.get(holder));
            } catch (Throwable ignored) {
            }
        }
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field cand : k.getDeclaredFields()) {
                if (Modifier.isStatic(cand.getModifiers())) continue;
                cand.setAccessible(true);
                Object v;
                try {
                    v = cand.get(holder);
                } catch (Throwable ignored) {
                    continue;
                }
                if (v == null) continue;
                String p = pathOfLoose(v);
                if (p != null) {
                    synchronized (holderPathFields) {
                        holderPathFields.put(c, cand);
                    }
                    return p;
                }
            }
        }
        return null;
    }

    /** adapter 里的媒体数据表(List 字段且首元素带 getPath),按 adapter 实例缓存。 */
    @SuppressWarnings("unchecked")
    private static java.util.List<Object> adapterListOf(Object adapter) {
        synchronized (adapterLists) {
            java.util.List<Object> cached = adapterLists.get(adapter);
            if (cached != null && !cached.isEmpty()) return cached;
        }
        java.util.List<Object> hit = adapterListOfInner(adapter, 0);
        if (hit != null) {
            synchronized (adapterLists) {
                adapterLists.put(adapter, hit);
            }
        }
        return hit;
    }

    /** 递归找数据表;ConcatAdapter 这类"装 adapter 的 adapter"要下钻一层。 */
    @SuppressWarnings("unchecked")
    private static java.util.List<Object> adapterListOfInner(Object adapter, int depth) {
        if (adapter == null || depth > 2) return null;
        for (Class<?> k = adapter.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || !List.class.isAssignableFrom(f.getType())) continue;
                f.setAccessible(true);
                Object v;
                try {
                    v = f.get(adapter);
                } catch (Throwable ignored) {
                    continue;
                }
                if (!(v instanceof List) || ((List<?>) v).isEmpty()) continue;
                List<?> l = (List<?>) v;
                Object e0 = l.get(0);
                if (e0 == null) continue;
                if (pathOfLoose(e0) != null) return (java.util.List<Object>) l;
                // 元素还是 adapter(ConcatAdapter 场景):下钻
                if (depth < 2 && isRecyclerAdapter(e0)) {
                    for (Object inner : l) {
                        java.util.List<Object> hit = adapterListOfInner(inner, depth + 1);
                        if (hit != null) return hit;
                    }
                }
            }
        }
        return null;
    }

    private static boolean isRecyclerAdapter(Object o) {
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            if ("androidx.recyclerview.widget.RecyclerView$Adapter".equals(c.getName())) return true;
        }
        return false;
    }

    private static void bindCellBadge(final View item, String path) {
        if (!(item instanceof ViewGroup)) return;
        if (path == null || isVideoPath(path)) {
            cellPaths.remove(item);
            setCellBadge(item, false);
            return;
        }
        cellPaths.put(item, path);
        Boolean live = liveVerdict.get(path);
        if (live != null) {
            setCellBadge(item, live);
            return;
        }
        setCellBadge(item, false);
        final String p = path;
        IO.execute(() -> {
            boolean l = LivePhotoDetector.isLiveFile(p);
            liveVerdict.put(p, l);
            MAIN.post(() -> {
                if (p.equals(cellPaths.get(item))) setCellBadge(item, l);
            });
        });
    }

    private static boolean isVideoPath(String p) {
        String s = p.toLowerCase(java.util.Locale.US);
        return s.endsWith(".mp4") || s.endsWith(".mov") || s.endsWith(".3gp")
                || s.endsWith(".avi") || s.endsWith(".mkv") || s.endsWith(".webm");
    }

    private static void setCellBadge(View itemView, boolean on) {
        ViewGroup item = itemView instanceof ViewGroup ? (ViewGroup) itemView : null;
        if (item == null) return;
        View badge = item.findViewById(GRID_BADGE_ID);
        if (!on) {
            if (badge != null && badge.getParent() instanceof ViewGroup) {
                ((ViewGroup) badge.getParent()).removeView(badge);
            }
            return;
        }
        if (badge != null) return;
        Context ctx = item.getContext();
        FrameLayout b = new FrameLayout(ctx);
        b.setId(GRID_BADGE_ID);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x73000000);
        bg.setCornerRadius(dp(ctx, 7));
        b.setBackground(bg);
        b.addView(new LiveMarkView(ctx, 0.78f), new FrameLayout.LayoutParams(
                dp(ctx, 14), dp(ctx, 14), android.view.Gravity.CENTER));
        int size = dp(ctx, 20);
        if (item instanceof FrameLayout) {
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size,
                    android.view.Gravity.TOP | android.view.Gravity.START);
            lp.leftMargin = dp(ctx, 4);
            lp.topMargin = dp(ctx, 4);
            item.addView(b, lp);
        } else {
            item.addView(b, new ViewGroup.LayoutParams(size, size));
            final float pad = dp(ctx, 4);
            b.post(() -> {
                b.setTranslationX(pad);
                b.setTranslationY(pad);
            });
        }
    }

    // ------------------------------------------------------------ 杂项

    /** 发送完成后延迟回收;期间若用户又取消/重发,重复 discard 也只是幂等删文件。 */
    private static void scheduleDelete(final LiveComposer.Out out) {
        if (out == null) return;
        MAIN.postDelayed(() -> {
            LiveComposer.discard(out);
            Log.i("临时合成视频已回收: " + out.file);
        }, DELETE_DELAY_MS);
    }

    /** 用 QQ 自己的 MediaTypeHelper 换算视频媒体类型值,换算不到才用常量。 */
    private static int videoTypeOf(Object info) {
        ClassLoader cl = info.getClass().getClassLoader();
        Class<?> helper = XposedHelpers.findClassIfExists(MEDIA_TYPE_HELPER, cl);
        if (helper == null && HookEntry.baseCl != null) {
            helper = XposedHelpers.findClassIfExists(MEDIA_TYPE_HELPER, HookEntry.baseCl);
        }
        if (helper == null) {
            Log.i("找不到 MediaTypeHelper,媒体类型用常量 " + VIDEO_MEDIA_TYPE);
            return VIDEO_MEDIA_TYPE;
        }
        try {
            // MediaTypeHelper 是单例,换算方法是实例方法且方法名混淆,按形态找:
            // 非静态 + 返回 int + 单个可接收本对象的参数。
            Object instance = null;
            for (java.lang.reflect.Field f : helper.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers()) || f.getType() != helper) continue;
                f.setAccessible(true);
                Object v = f.get(null);
                if (v != null) {
                    instance = v;
                    break;
                }
            }
            if (instance == null) {
                Log.i("MediaTypeHelper 单例字段没找到,媒体类型用常量 " + VIDEO_MEDIA_TYPE);
                return VIDEO_MEDIA_TYPE;
            }
            for (Method m : helper.getDeclaredMethods()) {
                if (Modifier.isStatic(m.getModifiers())) continue;
                if (m.getReturnType() != int.class || m.getParameterTypes().length != 1) continue;
                if (!m.getParameterTypes()[0].isInstance(info)) continue;
                m.setAccessible(true);
                Object r = m.invoke(instance, info);
                if (r instanceof Integer && (Integer) r >= 0) {
                    Log.i("MediaTypeHelper." + m.getName() + "(item) = " + r);
                    return (Integer) r;
                }
                Log.i("MediaTypeHelper." + m.getName() + "(item) 返回 " + r + ",用常量");
            }
        } catch (Throwable t) {
            Log.i("MediaTypeHelper 换算失败: " + t);
        }
        return VIDEO_MEDIA_TYPE;
    }

    private static void call(Object target, String method, Object arg) {
        try {
            XposedHelpers.callMethod(target, method, arg);
        } catch (Throwable t) {
            Log.i("set 失败 " + method + ": " + t);
        }
    }

    private static String pathOf(Object info) {
        try {
            Object p = XposedHelpers.callMethod(info, "getPath");
            return p instanceof String ? (String) p : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 宽松路径提取:相册面板的数据项(PhotoPanelAdapter 的元素)没有 getPath() 时,
     * 反射找值看起来像本机文件路径的 String 字段。仅用于网格角标/选中的判读,不参与发送替换。
     */
    private static String pathOfLoose(Object info) {
        String hit = pathOf(info);
        if (hit != null) return hit;
        if (info == null) return null;
        for (Class<?> c = info.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType() != String.class || Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                String v;
                try {
                    v = (String) f.get(info);
                } catch (Throwable ignored) {
                    continue;
                }
                if (v == null || v.isEmpty() || v.length() > 200) continue;
                if (v.startsWith("/storage/") || v.startsWith("/data/user/0/")) {
                    return v;
                }
            }
        }
        return null;
    }

    private static int dp(Context ctx, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                ctx.getResources().getDisplayMetrics());
    }
}
