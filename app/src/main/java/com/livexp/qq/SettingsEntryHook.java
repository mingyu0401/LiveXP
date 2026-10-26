package com.livexp.qq;

import android.app.AlertDialog;
import android.content.Context;
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

    private static final XC_MethodHook listAppender = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
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

        // 只在「设置」主页插入口。账号与安全等二级页同样继承 SettingConfigProvider
        // (如 com.tencent.mobileqq.account.provider.AccountManagerConfigProvider),
        // 不按包名限定会到处多出一个「LiveXP 模块」按钮。
        String declaring = param.method.getDeclaringClass().getName();
        Object receiver = param.thisObject;
        String actual = receiver == null ? declaring : receiver.getClass().getName();
        if (!declaring.startsWith(PROVIDER_PKG) && !actual.startsWith(PROVIDER_PKG)) {
            Log.i("skip non-main settings list: declaring=" + declaring + " actual=" + actual);
            return;
        }

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
        java.io.File f = new java.io.File("/sdcard/Android/data/com.tencent.mobileqq/LiveXP/livexp.log");
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

    /** 入口点击:进模块关于二级页(炫彩图标 + 简介 + 链接)。 */
    private static void onEntryClick(Context ctx) {
        Log.i("entry clicked, opening about page");
        ModuleAboutPage.showDialog(ctx, new ModuleAboutPage.Actions() {
            @Override
            public void openLogs() {
                showLogPanel(ctx);
            }

            @Override
            public void openSupport() {
                ModuleAboutPage.showSupportDialog(ctx);
            }
        });
    }

    /** 关于页底部的「查看运行日志」入口(原日志面板保留)。 */
    private static void showLogPanel(Context ctx) {
        try {
            TextView logView = new TextView(ctx);
            logView.setTypeface(Typeface.MONOSPACE);
            logView.setTextSize(11);
            logView.setTextColor(0xFFD7E3EE);
            String stats = "实况判定 " + LivePhotoDetector.liveMsgIds.size()
                    + " 条 · 命中 " + LivePhotoDetector.liveHitCount
                    + " · md5库 实况 " + LivePhotoDetector.md5LiveCount()
                    + " / 非实况 " + LivePhotoDetector.md5StaticCount() + "\n\n";
            String tail = readLogTail(120);
            logView.setText(stats + (tail != null ? tail : "(无法读取日志)"));

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
        } catch (Throwable t) {
            Log.i("log panel failed: " + t);
            Toast.makeText(ctx, "日志读取失败: " + t, Toast.LENGTH_SHORT).show();
        }
    }
}
