package com.livexp.qq;

import android.app.Activity;
import android.app.Instrumentation;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

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
            XposedBridge.hookAllMethods(Instrumentation.class, "callActivityOnPause", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Activity act = activityOf(param);
                    if (act != null && GALLERY.equals(act.getClass().getName())) {
                        LiveVideoPlayer.onGalleryPaused();
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
            installPagerHook(cl);
            Log.i("gallery hook installed (Instrumentation)");
        } catch (Throwable t) {
            Log.i("gallery hook failed: " + t);
        }
    }

    /**
     * 左右滑动播放:ViewPager2.dispatchOnPageSelected 是翻页唯一入口(方法名不混淆)。
     * androidx 类在热更和 base 两个加载器里可能有独立副本,实例用哪个不确定,按类身份去重后都 hook。
     */
    private static void installPagerHook(ClassLoader hotCl) {
        java.util.Set<Class<?>> hooked = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        int n = 0;
        for (ClassLoader c : new ClassLoader[]{hotCl, HookEntry.baseCl}) {
            if (c == null) continue;
            Class<?> k = XposedHelpers.findClassIfExists("androidx.viewpager2.widget.ViewPager2", c);
            if (k == null || !hooked.add(k)) continue;
            XposedBridge.hookAllMethods(k, "dispatchOnPageSelected", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!LiveVideoPlayer.galleryActive()) return;
                    Object a0 = param.args != null && param.args.length > 0 ? param.args[0] : null;
                    Log.i("page selected fired: pager=@" + Integer.toHexString(
                            System.identityHashCode(param.thisObject)) + " pos=" + a0);
                    if (a0 instanceof Integer) LiveVideoPlayer.onGalleryPage((Integer) a0);
                }
            });
            n++;
            Log.i("ViewPager2 page hook #" + n + " installed via " + k.getClassLoader());
        }
        if (n == 0) Log.i("ViewPager2 class not found, swipe play disabled");
    }

    private static Activity activityOf(XC_MethodHook.MethodHookParam param) {
        Object a = param.args != null && param.args.length > 0 ? param.args[0] : null;
        return a instanceof Activity ? (Activity) a : null;
    }
}
