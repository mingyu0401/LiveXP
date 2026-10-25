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
        /** 发送被拦下等合成:合成完成后自动重触发 onSendClick。 */
        boolean autoSend;
        Object autoItem;
        java.lang.ref.WeakReference<Object> autoTarget;
        Method autoMethod;
        Object[] autoArgs;
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
                    swapIfNeeded(param);
                } catch (Throwable t) {
                    Log.i("send swap failed, 原样发送: " + t);
                }
            }
        });
    }

    /**
     * 拦下这次发送,等合成完成后在主线程重调 onSendClick(此时已是 READY,正常走换引用)。
     * 只拦"单图预览页"的发送:QQ 的发送列表必须恰好是这一条 LocalMediaInfo,
     * 否则(相册多选、列表拿不到)不拦,按原图发,避免替用户发出意料外的组合。
     */
    private static boolean deferSend(XC_MethodHook.MethodHookParam param, Job j,
                                     String path, Object item) {
        if (j.autoSend) {
            // 已经在等了:再次点发送只拦不发,不重复提示
            param.setResult(null);
            return true;
        }
        java.util.List<Object> list = sendListOf(param.thisObject);
        if (list == null || list.size() != 1 || list.get(0) != item) {
            Log.i("send: 发送列表不是单图(size=" + (list == null ? "?" : list.size())
                    + "),不等合成,按原图发送");
            return false;
        }
        param.setResult(null);
        j.autoSend = true;
        j.autoItem = item;
        j.autoTarget = new java.lang.ref.WeakReference<>(param.thisObject);
        j.autoMethod = param.method instanceof Method ? (Method) param.method : null;
        j.autoArgs = param.args == null ? new Object[0] : param.args.clone();
        Log.i("send: 解析未完成,已拦下发送,合成完成后自动发出 path=" + path);
        toast("实况解析中…完成后自动发送");
        return true;
    }

    /** 合成完成(含失败)后由解析回调在主线程调用:重触发被拦下的那次发送。 */
    private static void autoSendNow(Job j, String path) {
        Object target = j.autoTarget != null ? j.autoTarget.get() : null;
        Method method = j.autoMethod;
        Object item = j.autoItem;
        Object[] args = j.autoArgs;
        j.autoItem = null;
        j.autoTarget = null;
        j.autoMethod = null;
        j.autoArgs = null;
        if (target == null || method == null || item == null) {
            cancelAutoSend(j, "预览页已销毁");
            return;
        }
        // 等待期间用户滑到别的图,或把预览页关了:不能替他发
        if (!path.equals(currentPath) || currentItem != item) {
            cancelAutoSend(j, "预览项已变化");
            return;
        }
        if (!anyBarAttached()) {
            cancelAutoSend(j, "预览页已关闭");
            return;
        }
        try {
            Log.i("auto send: 合成结束(status=" + j.status + "),自动发送");
            method.invoke(target, args);
        } catch (Throwable t) {
            Log.i("auto send failed: " + t);
            cancelAutoSend(j, "自动发送调用失败");
        }
    }

    /** 取消被拦下的自动发送:回收产物并复位状态(图没发出去,用户可自行再点发送)。 */
    private static void cancelAutoSend(Job j, String why) {
        Log.i("auto send 取消: " + why);
        j.checked = false;
        j.sent = false;
        j.status = ST_NONE;
        if (j.out != null) {
            LiveComposer.discard(j.out);
            j.out = null;
        }
        MAIN.post(LiveSendHook::refreshButtons);
    }

    /** 底栏还挂在窗口上,说明单图预览页还活着。 */
    private static boolean anyBarAttached() {
        synchronized (buttons) {
            for (View bar : buttons.keySet()) {
                if (bar != null && bar.isAttachedToWindow()) return true;
            }
        }
        return false;
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

    private static void ensureButton(View root) {
        if (root == null) return;
        try {
            if (root.findViewById(BTN_ID) != null) return;
            TextView anchor = findPreviewAnchor(root);
            if (anchor == null) return;
            injectInto(anchor.getContext(), anchor, "decor");
        } catch (Throwable t) {
            Log.i("兜底补挂失败: " + t);
        }
    }

    /**
     * 只在"单图预览页底栏"里找锚点:必须同时有"原图/编辑"和"发送"。
     * 相册网格底栏(相册|编辑|原图|发送)也满足前一条,但这时没有选中单张图,
     * 挂上去只会解析上一张、且占着网格底栏,所以用"相册"这个只出现在网格态的文案排除。
     */
    private static TextView findPreviewAnchor(View root) {
        TextView anchor = findText(root, "原图");
        if (anchor == null) anchor = findText(root, "编辑");
        if (anchor == null) return null;
        ViewGroup bar = anchor.getParent() instanceof ViewGroup ? (ViewGroup) anchor.getParent() : null;
        if (bar == null) return null;
        if (findText(bar, "发送") == null) return null;
        return findText(bar, "相册") == null ? anchor : null;
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
            injectInto(bottom.getContext(), anchor, "bar");
        } catch (Throwable t) {
            Log.i("inject button failed: " + t);
        }
    }

    private static void injectInto(Context ctx, TextView anchor, String from) {
        if (!LiveSettings.sendParseEnabled(ctx)) return;
        final ViewGroup bar = anchor.getParent() instanceof ViewGroup
                ? (ViewGroup) anchor.getParent() : null;
        if (bar == null || bar.findViewById(BTN_ID) != null) return;
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
        Log.i("解析实况按钮已挂上(" + from + ") bar=" + bar.getClass().getName());
        logHostActivity(bar);
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
        if (!LiveSettings.sendParseEnabled(btn.getContext())) {
            Log.i("解析实况:功能已在设置里关闭,忽略点击");
            return;
        }
        Job j = job(path);
        j.checked = !j.checked;
        if (j.checked && j.status != ST_READY && j.status != ST_PARSING) {
            j.sent = false;
            startParse(path, j);
        }
        if (!j.checked && j.out != null) {
            LiveComposer.discard(j.out);
            j.out = null;
            j.status = ST_NONE;
        }
        if (!j.checked) {
            // 用户主动取消:被拦下等合成的自动发送也一并放弃(原图没发,用户可再点发送)
            j.autoSend = false;
            j.autoItem = null;
            j.autoTarget = null;
            j.autoMethod = null;
            j.autoArgs = null;
        }
        style(btn);
        label(btn);
    }

    private static void startParse(String path, Job j) {
        j.status = ST_PARSING;
        Log.i("解析实况启动: " + path);
        LiveComposer.composeAsync(path, (status, src, out, err) -> {
            Job jj = job(src);
            if (status == LiveComposer.OK) {
                jj.status = ST_READY;
                jj.out = out;
            } else {
                jj.status = status == LiveComposer.NOT_LIVE ? ST_NOT_LIVE : ST_FAILED;
            }
            Log.i("解析实况结果 " + src + " -> status=" + jj.status
                    + (err == null ? "" : " err=" + err));
            if (jj.autoSend) {
                // 有被拦下的发送在等:主线程重调 onSendClick,READY 走换视频,失败走原图回退
                jj.autoSend = false;
                MAIN.post(() -> autoSendNow(jj, src));
            } else if (status == LiveComposer.OK && (jj.sent || !jj.checked)) {
                // 解析完成时这张图已经发出去了(或用户已取消勾选),产物没有用处,立刻回收。
                LiveComposer.discard(out);
                jj.status = ST_NONE;
                jj.out = null;
            }
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

    /** 设置里关掉「解析实况」:撤掉已经挂上的按钮,并把未发送的合成产物回收掉。 */
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
                    j.autoTarget = null;
                    j.autoMethod = null;
                    j.autoArgs = null;
                    if (j.status != ST_PARSING && j.out != null) {
                        LiveComposer.discard(j.out);
                        j.out = null;
                        j.status = ST_NONE;
                    }
                }
                Log.i("setting off: 已撤下解析实况按钮");
            } catch (Throwable t) {
                Log.i("remove parse buttons failed: " + t);
            }
        });
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
        // 勾了解析且还在合成:拦下这次发送,等合成完自动重发,不能把实况原图直接发出去。
        if (j.checked && j.status == ST_PARSING && deferSend(param, j, path, item)) {
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
            // sent 也要复位,否则同一张图再次勾选解析时,结果会在回调里被当成"已发送"直接丢弃。
            j.checked = false;
            j.sent = false;
            if (j.status != ST_PARSING) j.status = ST_NONE;
            Log.i("send: 未就绪,按原图发送 path=" + path + " status=" + j.status);
            MAIN.post(LiveSendHook::refreshButtons);
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
