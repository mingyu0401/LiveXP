package com.livexp.qq;

import android.content.Context;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class HookEntry implements IXposedHookLoadPackage {

    private static final String QQ_PACKAGE = "com.tencent.mobileqq";
    static final String TAG = "LiveXP";
    /** base.apk 加载器(androidx 等库类可能在这里,热更加载器找不到时兜底)。 */
    static volatile ClassLoader baseCl;
    /** QQ 进程的 Application Context(attachBaseContext 阶段捕获),状态桥要用它发广播。 */
    static volatile Context appCtx;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!QQ_PACKAGE.equals(lpparam.packageName)) return;
        if (!QQ_PACKAGE.equals(lpparam.processName)) return;
        baseCl = lpparam.classLoader;
        Log.init();
        Log.i("module loaded, version " + BuildConfig.VERSION_NAME);
        try {
            RuntimeLoaderHook.install(lpparam.classLoader);
        } catch (Throwable t) {
            Log.i("runtime loader hook failed: " + t);
        }
    }

    static void startHooks(ClassLoader realCl) {
        if (!startedOnce()) {
            return;
        }
        Log.i("installing all hooks with classloader: " + realCl);
        try {
            LiveBadgeHook.install(realCl);
        } catch (Throwable t) {
            Log.i("badge hook install failed: " + t);
        }
        try {
            LiveProbeHook.install(realCl);
        } catch (Throwable t) {
            Log.i("probe hook failed: " + t);
        }
        try {
            OpenImageSaver.install(realCl);
        } catch (Throwable t) {
            Log.i("open-saver install failed: " + t);
        }
        try {
            GalleryHook.install(realCl);
        } catch (Throwable t) {
            Log.i("gallery hook failed: " + t);
        }
        try {
            LiveSendHook.install(realCl);
        } catch (Throwable t) {
            Log.i("send hook failed: " + t);
        }
        try {
            SettingsEntryHook.install(realCl);
        } catch (Throwable t) {
            Log.i("settings entry failed: " + t);
        }
        try {
            StatusBridge.start();
        } catch (Throwable t) {
            Log.i("status bridge failed: " + t);
        }
    }

    private static final java.util.concurrent.atomic.AtomicBoolean STARTED =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private static boolean startedOnce() {
        return STARTED.compareAndSet(false, true);
    }
}
