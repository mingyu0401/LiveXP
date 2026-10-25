package com.livexp.qq;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 发送图片链路的「解析实况」能力。
 *
 * 单图预览页的宿主 Activity 两条路径都是 com.tencent.qqnt.qbasealbum.WinkHomeActivity,
 * 底栏都由 PreviewCustomizationBase#getBottomView 产出;但实现类不同(实测日志):
 * 半屏面板直接用 PreviewCustomizationBase 本身,全屏图库用覆写了 onSendClick 的
 * com.tencent.mobileqq.flashtransfer.album.FlashTransferPreviewCustomization。
 * 这些名字是业务实现的公开 API,不随版本混淆,所以按名字 hook,并按运行时类补挂子类。
 *
 * 流程:进预览页 → 底栏"原图"右侧挂一个可勾选的「解析实况」→ 勾选即后台合成 MP4
 * → 点发送时(onSendClick 之前)把当前这条 LocalMediaInfo 的媒体引用换成合成视频
 * → QQ 自己的通道按视频发出 → 延迟回收临时文件。
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

    private static final int ST_NONE = 0;
    private static final int ST_PARSING = 1;
    private static final int ST_READY = 2;
    private static final int ST_NOT_LIVE = 3;
    private static final int ST_FAILED = 4;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** 每张图自己的勾选与解析状态:key = 预览项的文件路径。 */
    private static final Map<String, Job> jobs = new ConcurrentHashMap<>();
    /** 底栏 View → 挂上去的按钮。 */
    private static final Map<View, TextView> buttons = new WeakHashMap<>();
    /** 底栏 View → 看门人:QQ 会重建底栏子 View,按钮被抹掉时要重新挂回去。 */
    private static final Map<View, android.view.ViewTreeObserver.OnGlobalLayoutListener> watchers =
            new WeakHashMap<>();

    private static volatile Object currentItem;
    private static volatile String currentPath;
    /** 已补挂过 hook 的预览实现类与方法。 */
    private static final java.util.Set<Class<?>> hookedClasses =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<Class<?>, Boolean>());
    private static final java.util.Set<Method> hookedMethods =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<Method, Boolean>());

    private LiveSendHook() {}

    private static final class Job {
        int status = ST_NONE;
        boolean checked;
        /** 这张图已经被"发送"处理过一次:之后再解析完的产物直接丢弃,不再留着占空间。 */
        boolean sent;
        LiveComposer.Out out;
    }

    private static Job job(String path) {
        return jobs.computeIfAbsent(path, k -> new Job());
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
        LiveSendMarks.loadAsync();
        Log.i("send hook installed on " + preview.getName());
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
            }
        });
    }

    private static void hookSend(Class<?> owner) {
        hookDeclared(owner, "onSendClick", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    dumpItem();
                    swapIfNeeded();
                } catch (Throwable t) {
                    Log.i("send swap failed, 原样发送: " + t);
                }
            }
        });
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
    /** QQ 会重建底栏子 View(选图、切原图态都会),按钮被抹掉时靠这个看门人补挂回去。 */
    private static void watch(final View bottom) {
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

    private static void injectButton(View bottom, int retry) {
        try {
            if (bottom.findViewById(BTN_ID) != null) return;
            TextView anchor = findAnchor(bottom);
            if (anchor == null) {
                if (retry < ANCHOR_RETRY_MAX) {
                    bottom.postDelayed(() -> injectButton(bottom, retry + 1), 250L * (retry + 1));
                } else {
                    Log.i("解析实况按钮:没找到原图/编辑锚点,放弃挂载");
                }
                return;
            }
            Context ctx = anchor.getContext();
            final ViewGroup bar = (ViewGroup) anchor.getParent();
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
                buttons.put(bottom, btn);
            }
            btn.setOnClickListener(v -> toggle(btn));
            btn.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> place(btn, anchor, bar));
            style(btn);
            label(btn);
            place(btn, anchor, bar);
            btn.post(() -> place(btn, anchor, bar));
            btn.postDelayed(() -> place(btn, anchor, bar), 400L);
            Log.i("解析实况按钮已挂上 bar=" + bar.getClass().getName());
            logHostActivity(bottom);
        } catch (Throwable t) {
            Log.i("inject button failed: " + t);
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
                Log.i("解析实况宿主 Activity: " + name);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 优先锚"原图";没有原图开关的会话(如"我的电脑")退回"编辑",再退回"发送"。 */
    private static TextView findAnchor(View root) {
        TextView hit = findText(root, "原图");
        if (hit == null) hit = findText(root, "编辑");
        if (hit == null) hit = findText(root, "发送");
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
            Log.i("解析实况:当前没有选中图片,忽略点击");
            return;
        }
        Job j = job(path);
        j.checked = !j.checked;
        if (j.checked && j.status != ST_READY && j.status != ST_PARSING) {
            startParse(path, j);
        }
        if (!j.checked && j.out != null) {
            LiveComposer.discard(j.out);
            j.out = null;
            j.status = ST_NONE;
        }
        style(btn);
        label(btn);
    }

    private static void startParse(String path, Job j) {
        j.status = ST_PARSING;
        Log.i("解析实况启动: " + path);
        LiveComposer.composeAsync(path, (status, src, out, err) -> {
            Job jj = job(src);
            if (status == LiveComposer.OK && (jj.sent || !jj.checked)) {
                // 解析完成时这张图已经发出去了(或用户已取消勾选),产物没有用处,立刻回收。
                LiveComposer.discard(out);
                jj.status = ST_NONE;
                jj.out = null;
            } else if (status == LiveComposer.OK) {
                jj.status = ST_READY;
                jj.out = out;
            } else {
                jj.status = status == LiveComposer.NOT_LIVE ? ST_NOT_LIVE : ST_FAILED;
            }
            Log.i("解析实况结果 " + src + " -> status=" + jj.status
                    + (err == null ? "" : " err=" + err));
            if (src.equals(currentPath)) refreshButtons();
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

    private static void style(TextView btn) {
        Job j = currentPath == null ? null : jobs.get(currentPath);
        boolean on = j != null && j.checked;
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
        Job j = currentPath == null ? null : jobs.get(currentPath);
        if (j == null || !j.checked) {
            btn.setText("解析实况");
            return;
        }
        switch (j.status) {
            case ST_PARSING:
                btn.setText("解析中…");
                break;
            case ST_READY:
                btn.setText("已解析 ✓");
                break;
            case ST_NOT_LIVE:
                btn.setText("非实况图");
                break;
            case ST_FAILED:
                btn.setText("解析失败");
                break;
            default:
                btn.setText("解析实况");
                break;
        }
    }

    // ------------------------------------------------------------ 发送替换

    private static void swapIfNeeded() {
        String path = currentPath;
        Object item = currentItem;
        if (path == null || item == null) {
            Log.i("send: 没拿到当前预览项,按原样发送");
            return;
        }
        Job j = jobs.get(path);
        if (j == null) return;
        j.sent = true;
        if (!j.checked || j.status != ST_READY || j.out == null
                || j.out.file == null || !j.out.file.isFile()) {
            if (j.out != null) {
                LiveComposer.discard(j.out);
                j.out = null;
            }
            Log.i("send: 未就绪,按原图发送 path=" + path + " status=" + j.status);
            return;
        }
        LiveComposer.Out out = j.out;
        if (out.storeId < 0 || out.storePath == null) {
            Log.i("send: 合成结果没进媒体库,按原图发送");
            return;
        }
        File published = new File(out.storePath);
        long len = published.length() > 0 ? published.length() : out.file.length();
        long ms = out.durationUs / 1000L;
        call(item, "set_id", Long.valueOf(out.storeId));
        call(item, "setPath", out.storePath);
        call(item, "setSystemMeidaStore", true);
        call(item, "setMMimeType", "video/mp4");
        call(item, "setVideoMimeType", "video/mp4");
        call(item, "setFileSize", Long.valueOf(len));
        call(item, "setVideoDuration", Long.valueOf(ms));
        call(item, "setMTotalDuration", Long.valueOf(ms));
        call(item, "setMStartTime", Long.valueOf(0L));
        call(item, "setMediaWidth", out.width);
        call(item, "setMediaHeight", out.height);
        call(item, "setMediaOriginWidth", out.width);
        call(item, "setMediaOriginHeight", out.height);
        call(item, "setMediaOriginSize", Long.valueOf(len));
        call(item, "setMHasAudioTrack", out.hasAudio);
        // 类型换算放在 mime 写完之后:MediaTypeHelper 是按 mMimeType 主段查表的。
        int type = videoTypeOf(item);
        call(item, "setMMediaType", type);
        Log.i("send: 媒体引用换成合成视频 item=" + item.getClass().getName()
                + " id=" + out.storeId + " path=" + out.storePath
                + " type=" + type + " " + out.width + "x" + out.height
                + " " + ms + "ms audio=" + out.hasAudio);
        LiveSendMarks.remember(out.md5, out.storePath);
        jobs.remove(path);
        MAIN.post(() -> {
            styleOfCurrent();
            scheduleDelete(out);
        });
    }

    private static void styleOfCurrent() {
        refreshButtons();
    }

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

    private static int dp(Context ctx, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                ctx.getResources().getDisplayMetrics());
    }
}
