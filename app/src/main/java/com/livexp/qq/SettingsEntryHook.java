package com.livexp.qq;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import android.graphics.Typeface;
import android.widget.ScrollView;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 参考 QAuxiliary SettingEntryHook:hook 设置列表数据源的 getItemProcessList(Context): List,
 * 往结果里插入一个 SimpleItemProcessor 分组作为模块入口。
 * SimpleItemProcessor 类名各版本混淆不同,运行时从已有列表项里按构造器签名识别。
 */
public final class SettingsEntryHook {

    private static final String PROVIDER_PKG = "com.tencent.mobileqq.setting.main.";
    private static final String[] PROVIDER_NAMES = {
            "MainSettingConfigProvider", "NewSettingConfigProvider", "b"
    };
    private static final int ENTRY_ITEM_ID = 0x7e0b1e04;

    private static final Set<String> hookedPageClasses = new CopyOnWriteArraySet<>();
    private static final Set<String> seenClasses = new CopyOnWriteArraySet<>();
    private static final Set<Method> hookedMethods = new CopyOnWriteArraySet<>();

    private static volatile Constructor<?> itemCtor;
    private static volatile Method itemClickSetter;
    private static volatile Constructor<?> groupCtor;
    private static volatile Field groupItemsField;
    private static volatile Object kotlinUnit;

    private SettingsEntryHook() {}

    public static void install(ClassLoader cl) {
        try {
            for (String name : PROVIDER_NAMES) {
                Class<?> provider = XposedHelpers.findClassIfExists(PROVIDER_PKG + name, cl);
                if (provider == null) continue;
                for (Method m : provider.getDeclaredMethods()) {
                    if (isListMethod(m)) {
                        hookListMethod(m);
                    }
                }
            }
        } catch (Throwable t) {
            Log.i("provider name hook failed: " + t);
        }
        try {
            hookProviderBase(cl);
        } catch (Throwable t) {
            Log.i("provider base hook failed: " + t);
        }
        try {
            enumerateProviderSubclasses(cl);
        } catch (Throwable t) {
            Log.i("provider scan failed: " + t);
        }
        try {
            installFragmentFallback(cl);
        } catch (Throwable t) {
            Log.i("fragment fallback failed: " + t);
        }
    }

    /**
     * Provider 子类列表构建方法很可能是 static(基类构造探针从不触发),无法靠实例化发现。
     * 混淆子类名都是 1~2 个小写字母,直接枚举加载找 SettingConfigProvider 的子类,免引 DexKit。
     */
    private static void enumerateProviderSubclasses(ClassLoader cl) {
        Class<?> base = XposedHelpers.findClassIfExists(
                "com.tencent.mobileqq.setting.processor.SettingConfigProvider", cl);
        if (base == null) return;
        ArrayList<String> found = new ArrayList<>();
        for (char c1 = 'a'; c1 <= 'z'; c1++) {
            tryClass(cl, String.valueOf(c1), base, found);
            for (char c2 = 'a'; c2 <= 'z'; c2++) {
                tryClass(cl, "" + c1 + c2, base, found);
            }
        }
        Log.i("provider subclass scan done, found " + found.size() + ": " + found);
    }

    private static void tryClass(ClassLoader cl, String suffix, Class<?> base, List<String> found) {
        Class<?> c = XposedHelpers.findClassIfExists(PROVIDER_PKG + suffix, cl);
        if (c == null || c == base || !base.isAssignableFrom(c)) return;
        found.add(c.getName());
        for (Method m : c.getDeclaredMethods()) {
            if (isListMethod(m)) hookListMethod(m);
        }
    }

    /** 参考 QFun:基类名 SettingConfigProvider 不混淆,子类名各版本随机。hook 基类构造发现真实子类,hook 基类声明的方法拦住未重写的调用。 */
    private static void hookProviderBase(ClassLoader cl) {
        Class<?> base = XposedHelpers.findClassIfExists(
                "com.tencent.mobileqq.setting.processor.SettingConfigProvider", cl);
        if (base == null) {
            Log.i("SettingConfigProvider base not found");
            return;
        }
        for (Method m : base.getDeclaredMethods()) {
            if (isListMethod(m)) hookListMethod(m);
        }
        for (Constructor<?> ctor : base.getDeclaredConstructors()) {
            XposedBridge.hookMethod(ctor, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Class<?> actual = param.thisObject.getClass();
                        if (!seenClasses.add("provider:" + actual.getName())) return;
                        Log.i("config provider constructed: " + actual.getName());
                        for (Class<?> c = actual; c != null && c != Object.class; c = c.getSuperclass()) {
                            for (Method m : c.getDeclaredMethods()) {
                                if (isListMethod(m)) hookListMethod(m);
                            }
                        }
                    } catch (Throwable t) {
                        Log.i("provider ctor probe error: " + t);
                    }
                }
            });
        }
        Log.i("config provider base hooked: " + base.getName()
                + " (super=" + base.getSuperclass().getName() + ")");
    }

    private static boolean isListMethod(Method m) {
        return List.class.isAssignableFrom(m.getReturnType())
                && m.getParameterTypes().length == 1
                && Context.class.isAssignableFrom(m.getParameterTypes()[0]);
    }

    private static void hookListMethod(Method m) {
        if (Modifier.isAbstract(m.getModifiers())) {
            Log.i("skip abstract list method: " + m.getDeclaringClass().getName() + "." + m.getName());
            return;
        }
        if (!hookedMethods.add(m)) return;
        XposedBridge.hookMethod(m, listAppender);
        Log.i("settings list hooked: " + m.getDeclaringClass().getName() + "." + m.getName());
    }

    /**
     * 兜底:hook Instrumentation.callActivityOnCreate —— 所有 Activity 创建必经的总入口,
     * 不受子类重写影响。记录全部 Activity 类名用于发现真实的设置页类;
     * 类名含 etting 直接注入悬浮入口。Fragment 构造函数探针同理记录类名。
     */
    private static void installFragmentFallback(ClassLoader cl) {
        tryHookPageClass(cl, "com.tencent.mobileqq.setting.main.MainSettingFragment");
        probeConstructors(cl, "androidx.fragment.app.Fragment");
        monitorActivities(cl);
    }

    private static void monitorActivities(ClassLoader cl) {
        try {
            Class<?> instr = XposedHelpers.findClass("android.app.Instrumentation", cl);
            int hooked = 0;
            for (Method m : instr.getDeclaredMethods()) {
                if (!"callActivityOnCreate".equals(m.getName())) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Activity activity = (Activity) param.args[0];
                            String name = activity.getClass().getName();
                            if (seenClasses.add(name)) {
                                Log.i("activity created: " + name);
                            }
                            // QQ 9.3.70 设置页是通用容器 QPublicFragmentActivity,靠标题识别
                            if ("com.tencent.mobileqq.activity.QPublicFragmentActivity".equals(name)) {
                                scheduleSettingsCheck(activity);
                            }
                        } catch (Throwable t) {
                            Log.i("activity monitor error: " + t);
                        }
                    }
                });
                hooked++;
            }
            Log.i("activity monitor hooked: " + hooked + " callActivityOnCreate");
        } catch (Throwable t) {
            Log.i("activity monitor failed: " + t);
        }
    }

    /** 标题要等 Fragment 加载完才设置,分几次延迟检查。 */
    private static void scheduleSettingsCheck(Activity activity) {
        Handler handler = new Handler(Looper.getMainLooper());
        long[] delays = {500, 1500, 3000};
        for (long delay : delays) {
            handler.postDelayed(() -> {
                try {
                    if (activity.isFinishing() || activity.isDestroyed()) return;
                    CharSequence title = activity.getTitle();
                    if (title != null && title.toString().contains("设置")) {
                        injectPill(activity);
                        Log.i("settings pill injected into QQ settings page (title=" + title + ")");
                    }
                } catch (Throwable t) {
                    Log.i("settings check error: " + t);
                }
            }, delay);
        }
    }

    private static void probeConstructors(ClassLoader cl, String baseName) {
        try {
            Class<?> base = XposedHelpers.findClass(baseName, cl);
            Constructor<?>[] ctors = base.getDeclaredConstructors();
            for (Constructor<?> ctor : ctors) {
                XposedBridge.hookMethod(ctor, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            onPageInstanceConstructed(param.thisObject);
                        } catch (Throwable t) {
                            Log.i("ctor probe error: " + t);
                        }
                    }
                });
            }
            Log.i("ctor probe hooked: " + baseName + " (" + ctors.length + " ctors)");
        } catch (Throwable t) {
            Log.i("ctor probe failed: " + baseName + ": " + t);
        }
    }

    private static void onPageInstanceConstructed(Object obj) {
        String name = obj.getClass().getName();
        if (!name.startsWith("com.tencent.mobileqq")) return;
        // 只记录类名用于发现真实页面类,不再按 "etting" 启发式注入:
        // 群资料页 TroopSettingFragmentV2 等类名也会命中,且兜底会错误 hook 到
        // androidx 基类方法,导致胶囊出现在所有 QQ 界面。
        if (seenClasses.add(name) && seenClasses.size() <= 300) {
            Log.i("fragment constructed: " + name);
        }
    }

    private static void tryHookPageClass(ClassLoader cl, String className) {
        Class<?> cls = XposedHelpers.findClassIfExists(className, cl);
        if (cls == null) {
            Log.i("settings page class not found: " + className);
            return;
        }
        hookedPageClasses.add(className);
        if (!hookLifecycle(cls)) {
            Log.i("no lifecycle method found on " + className);
        }
    }

    private static boolean hookLifecycle(Class<?> cls) {
        final boolean isActivity = Activity.class.isAssignableFrom(cls);
        String[][] candidates = isActivity
                ? new String[][]{{"onResume", "0"}, {"doOnCreate", "1"}, {"onPostCreate", "1"}, {"onCreate", "1"}}
                : new String[][]{{"onViewCreated", "2"}, {"onResume", "0"}, {"onCreateView", "3"}};
        for (String[] cand : candidates) {
            final Method m = findMostDerived(cls, cand[0], Integer.parseInt(cand[1]));
            if (m == null) continue;
            // 找到的声明落到了 androidx/android 框架基类上说明目标类自身没有重写,
            // hook 它会拦住所有 Fragment/Activity,绝不能装。
            String decl = m.getDeclaringClass().getName();
            if (decl.startsWith("android.") || decl.startsWith("androidx.")) continue;
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object activity = isActivity ? param.thisObject
                                : XposedHelpers.callMethod(param.thisObject, "getActivity");
                        if (!(activity instanceof Activity)) return;
                        injectPill((Activity) activity);
                        Log.i("settings pill injected via "
                                + m.getDeclaringClass().getName() + "." + m.getName());
                    } catch (Throwable t) {
                        Log.i("settings pill error: " + t);
                    }
                }
            });
            Log.i("settings page hooked: " + m.getDeclaringClass().getName() + "." + m.getName()
                    + " (for " + cls.getName() + ")");
            return true;
        }
        return false;
    }

    /** 从 cls 沿继承链向上找 name/paramCount 匹配的最派生声明(虚拟分发实际执行的就是它)。 */
    private static Method findMostDerived(Class<?> cls, String name, int paramCount) {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (name.equals(m.getName()) && m.getParameterTypes().length == paramCount) {
                    return m;
                }
            }
        }
        return null;
    }

    private static void injectPill(Activity activity) {
        View content = activity.findViewById(android.R.id.content);
        if (!(content instanceof ViewGroup)) return;
        ViewGroup root = (ViewGroup) content;
        if (root.findViewById(ENTRY_ITEM_ID) != null) return;

        TextView pill = new TextView(activity);
        pill.setId(ENTRY_ITEM_ID);
        pill.setText("✔ LiveXP 已加载 · 点击查看状态");
        pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        pill.setTextColor(Color.WHITE);
        int padH = dp(activity, 14);
        int padV = dp(activity, 8);
        pill.setPadding(padH, padV, padH, padV);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xCC2E7D32);
        bg.setCornerRadius(dp(activity, 18));
        pill.setBackground(bg);
        pill.setOnClickListener(v -> {
            Log.i("pill clicked");
            onEntryClick(activity);
        });

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.bottomMargin = dp(activity, 64);
        root.addView(pill, lp);
    }

    private static int dp(Context ctx, float value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                ctx.getResources().getDisplayMetrics());
    }

    private static final XC_MethodHook listAppender = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                Object r = param.getResult();
                Log.i("provider method invoked: " + param.method.getDeclaringClass().getName()
                        + "." + param.method.getName() + ", result="
                        + (r instanceof List ? "List(" + ((List<?>) r).size() + ")" : String.valueOf(r)));
                appendEntry(param);
            } catch (Throwable t) {
                Log.i("settings entry append error: " + t);
            }
        }
    };

    private static void appendEntry(XC_MethodHook.MethodHookParam param) throws Throwable {
        Object resultObj = param.getResult();
        if (!(resultObj instanceof List)) return;
        List<Object> result = (List<Object>) resultObj;
        if (result.isEmpty()) return;
        for (Object o : result) {
            if (o != null && String.valueOf(o).contains("LiveXP")) {
                return;
            }
        }
        Context ctx = (Context) param.args[0];
        ClassLoader hostCl = result.get(0).getClass().getClassLoader();

        resolveSignatures(result, hostCl);
        if (itemCtor == null || itemClickSetter == null || groupCtor == null || groupItemsField == null) {
            Log.i("settings entry: signature resolve failed, itemCtor=" + itemCtor
                    + " clickSetter=" + itemClickSetter + " groupCtor=" + groupCtor);
            return;
        }

        int iconId = ctx.getResources().getIdentifier("qui_tuning", "drawable", ctx.getPackageName());
        Object entryItem = itemCtor.getParameterCount() == 5
                ? itemCtor.newInstance(ctx, 1001, "LiveXP 模块", iconId, null)
                : itemCtor.newInstance(ctx, 1001, "LiveXP 模块", iconId);

        Class<?> function0 = itemClickSetter.getParameterTypes()[0];
        Object func0 = Proxy.newProxyInstance(hostCl, new Class<?>[]{function0}, (proxy, method, args) -> {
            Log.i("entry Function0 invoked: " + method.getName());
            switch (method.getName()) {
                case "invoke":
                    onEntryClick(ctx);
                    return kotlinUnit;
                case "toString":
                    return "LiveXP";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    return null;
            }
        });
        itemClickSetter.invoke(entryItem, func0);

        ArrayList<Object> items = new ArrayList<>(1);
        items.add(entryItem);
        Object group = groupCtor.getParameterCount() == 5
                ? groupCtor.newInstance(items, "", "", 6, null)
                : groupCtor.newInstance(items, "", "");
        result.add(Math.min(2, result.size()), group);
        Log.i("settings entry appended");
    }

    private static void resolveSignatures(List<Object> groups, ClassLoader hostCl) throws Throwable {
        if (itemCtor != null) return;
        synchronized (SettingsEntryHook.class) {
            if (itemCtor != null) return;

            Class<?> groupClass = groups.get(0).getClass();
            Field itemsField = null;
            for (Class<?> c = groupClass; c != null && itemsField == null; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (List.class.isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        itemsField = f;
                        break;
                    }
                }
            }
            if (itemsField == null) return;

            Class<?> simpleItemClass = null;
            for (Object group : groups) {
                Object itemsObj = itemsField.get(group);
                if (!(itemsObj instanceof List)) continue;
                for (Object item : (List<?>) itemsObj) {
                    if (item == null) continue;
                    Class<?> c = item.getClass();
                    Constructor<?> ctor4 = findCtor(c, Context.class, int.class, CharSequence.class, int.class);
                    Constructor<?> ctor5 = findCtor(c, Context.class, int.class, CharSequence.class, int.class, String.class);
                    if (ctor4 == null && ctor5 == null) continue;
                    Method clickSetter = findClickSetter(c);
                    if (clickSetter == null) continue;
                    simpleItemClass = c;
                    itemCtor = ctor5 != null ? ctor5 : ctor4;
                    itemCtor.setAccessible(true);
                    itemClickSetter = clickSetter;
                    itemClickSetter.setAccessible(true);
                    break;
                }
                if (simpleItemClass != null) break;
            }

            Constructor<?> gCtor = null;
            try {
                gCtor = groupClass.getDeclaredConstructor(List.class, CharSequence.class, CharSequence.class);
            } catch (NoSuchMethodException e) {
                Class<?> marker = XposedHelpers.findClass("kotlin.jvm.internal.DefaultConstructorMarker", hostCl);
                gCtor = groupClass.getDeclaredConstructor(List.class, CharSequence.class, CharSequence.class,
                        int.class, marker);
            }
            gCtor.setAccessible(true);
            groupCtor = gCtor;
            groupItemsField = itemsField;
            kotlinUnit = XposedHelpers.findClass("kotlin.Unit", hostCl).getField("INSTANCE").get(null);
            Log.i("settings signatures resolved: simpleItem=" + simpleItemClass + ", group=" + groupClass.getName());
        }
    }

    private static Constructor<?> findCtor(Class<?> c, Class<?>... types) {
        try {
            return c.getDeclaredConstructor(types);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    /** SimpleItemProcessor 上接 kotlin Function0 的 void 方法(取名字典序最小者)。 */
    private static Method findClickSetter(Class<?> c) {
        ArrayList<Method> candidates = new ArrayList<>();
        for (Method m : c.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (m.getReturnType() == void.class && p.length == 1
                    && "kotlin.jvm.functions.Function0".equals(p[0].getName())) {
                candidates.add(m);
            }
        }
        if (candidates.isEmpty()) return null;
        candidates.sort(Comparator.comparing(Method::getName));
        return candidates.get(0);
    }

    private static String readLogTail(int maxLines) {
        java.io.File f = new java.io.File("/sdcard/Download/LiveXP/livexp.log");
        try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(f))) {
            ArrayDeque<String> lines = new ArrayDeque<>();
            String line;
            while ((line = br.readLine()) != null) {
                if (lines.size() == maxLines) lines.pollFirst();
                lines.offerLast(line);
            }
            if (lines.isEmpty()) return "(日志为空)";
            StringBuilder sb = new StringBuilder();
            for (String l : lines) sb.append(l).append('\n');
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static void onEntryClick(Context ctx) {
        Log.i("entry clicked, showing status panel");
        // QQ 有防劫持机制,会静默拦截跨应用 startActivity(QFun 靠 Parasitics 桩 Activity 绕过,太重),
        // 所以在 QQ 进程内直接弹自己的深色日志面板。
        try {
            TextView logView = new TextView(ctx);
            logView.setTypeface(Typeface.MONOSPACE);
            logView.setTextSize(11);
            logView.setTextColor(0xFFD7E3EE);
            String tail = readLogTail(120);
            logView.setText(tail != null ? tail : "(无法读取日志)");

            ScrollView scroll = new ScrollView(ctx);
            scroll.addView(logView);
            scroll.setBackgroundColor(0xFF101418);
            int pad = (int) (14 * ctx.getResources().getDisplayMetrics().density);
            scroll.setPadding(pad, pad, pad, pad);

            TextView title = new TextView(ctx);
            title.setText("LiveXP v" + BuildConfig.VERSION_NAME + " · 运行日志");
            title.setTextColor(0xFFFFFFFF);
            title.setTextSize(18);

            new AlertDialog.Builder(ctx)
                    .setCustomTitle(title)
                    .setView(scroll)
                    .setPositiveButton("关闭", null)
                    .show();
            return;
        } catch (Throwable t) {
            Log.i("status panel failed: " + t);
        }
        String msg = "版本: " + BuildConfig.VERSION_NAME + "\n"
                + "hook 方法: " + LiveBadgeHook.hookedTarget + "\n"
                + "hostView 解析: " + (LiveBadgeHook.hostViewResolved ? "成功" : "失败") + "\n"
                + "msgItem 解析: " + LiveBadgeHook.msgItemMethod + "\n"
                + "气泡绑定次数: " + LiveBadgeHook.boundCount + "\n"
                + "图片消息 dump 数: " + LivePhotoDetector.dumpCount + "\n"
                + "实况图命中数: " + LivePhotoDetector.liveHitCount;
        try {
            new AlertDialog.Builder(ctx)
                    .setTitle("LiveXP 状态")
                    .setMessage(msg)
                    .setPositiveButton("好", null)
                    .show();
        } catch (Throwable t) {
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show();
        }
    }
}
