package com.livexp.qq;

import android.content.Context;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import dalvik.system.BaseDexClassLoader;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * QQ NT 的业务类由 attachBaseContext 期间创建的热更 ClassLoader 加载,
 * lpparam.classLoader 里的同名类是死副本(参考 QFun Startup.kt)。
 * 这里捕获热更 ClassLoader,再用它安装全部 hook。
 */
public final class RuntimeLoaderHook {

    private static final AtomicBoolean captured = new AtomicBoolean(false);
    private static final AtomicBoolean started = new AtomicBoolean(false);

    private RuntimeLoaderHook() {}

    public static void install(ClassLoader initialCl) {
        Class<?> proxy = XposedHelpers.findClassIfExists(
                "com.tencent.common.app.QFixApplicationImplProxy", initialCl);
        Method attach = null;
        if (proxy != null) {
            for (Method m : proxy.getDeclaredMethods()) {
                if ("attachBaseContext".equals(m.getName())
                        && m.getParameterTypes().length == 1
                        && Context.class.isAssignableFrom(m.getParameterTypes()[0])) {
                    attach = m;
                    break;
                }
            }
        }
        if (attach == null) {
            Log.i("qfix proxy not found, starting with initial classloader");
            HookEntry.startHooks(initialCl);
            return;
        }
        try {
            for (Constructor<?> ctor : BaseDexClassLoader.class.getDeclaredConstructors()) {
                XposedBridge.hookMethod(ctor, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            ClassLoader loader = (ClassLoader) param.thisObject;
                            String s = String.valueOf(loader);
                            if (s.contains("com.livexp.qq")) return;
                            if (!captured.compareAndSet(false, true)) return;
                            if (s.contains("TinkerClassLoader")
                                    || s.contains("DelegateLastClassLoader")
                                    || s.contains("com.tencent.")) {
                                Log.i("captured host hotfix classloader: " + s);
                                HookEntry.startHooks(loader);
                            } else {
                                captured.set(false);
                            }
                        } catch (Throwable t) {
                            Log.i("loader capture error: " + t);
                        }
                    }
                });
            }
        } catch (Throwable t) {
            Log.i("BaseDexClassLoader probe failed: " + t);
        }
        XposedBridge.hookMethod(attach, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (captured.get() || started.get()) return;
                Context ctx = (Context) param.args[0];
                Log.i("hotfix loader not captured at attach end, fallback to context classloader");
                HookEntry.startHooks(ctx.getClassLoader());
            }
        });
        Log.i("qfix attach hooked, waiting for hotfix classloader");
    }
}
