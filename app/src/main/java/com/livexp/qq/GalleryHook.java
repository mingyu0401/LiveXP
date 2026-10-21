package com.livexp.qq;

import android.app.Activity;
import android.app.Instrumentation;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 大图查看器:com.tencent.richframework.gallery.QQGalleryActivity(类名不混淆)。
 * 不 hook 查看器自身(方法签名混淆),走 Instrumentation.callActivityOnResume/OnDestroy
 * 唯一必经入口,按 Activity 类名过滤,把时机交给 LiveVideoPlayer。
 * 当前消息的路径由 LiveVideoPlayer 用 intent 的 KEY_THUMBNAL_BOUND 与 bind 时登记的
 * 实况气泡(LivePhotoDetector.liveBinds)匹配得到。
 */
public final class GalleryHook {

    private static final String GALLERY = "com.tencent.richframework.gallery.QQGalleryActivity";

    private GalleryHook() {}

    public static void install(ClassLoader cl) {
        try {
            XposedBridge.hookAllMethods(Instrumentation.class, "callActivityOnResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Activity act = activityOf(param);
                    if (act != null && GALLERY.equals(act.getClass().getName())) {
                        LiveVideoPlayer.onGalleryResumed(act);
                    }
                }
            });
            XposedBridge.hookAllMethods(Instrumentation.class, "callActivityOnDestroy", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Activity act = activityOf(param);
                    if (act != null && GALLERY.equals(act.getClass().getName())) {
                        LiveVideoPlayer.detach();
                    }
                }
            });
            Log.i("gallery hook installed (Instrumentation)");
        } catch (Throwable t) {
            Log.i("gallery hook failed: " + t);
        }
    }

    private static Activity activityOf(XC_MethodHook.MethodHookParam param) {
        Object a = param.args != null && param.args.length > 0 ? param.args[0] : null;
        return a instanceof Activity ? (Activity) a : null;
    }
}
