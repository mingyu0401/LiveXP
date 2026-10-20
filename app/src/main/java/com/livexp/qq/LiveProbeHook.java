package com.livexp.qq;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 诊断探针:QQ 自己的实况框架(LivePhotoLocalBean 等)被构建时 dump 全部字段,
 * 从中学习原图缓存路径规则与实况判定方式。QQ 构建该 bean 说明它已认定该图为实况。
 */
public final class LiveProbeHook {

    private static final String BEAN = "com.tencent.mobileqq.common.livephoto.utils.LivePhotoLocalBean";
    private static final String DL_HELPER = "com.tencent.mobileqq.common.livephoto.utils.LivePhotoDownloadHelper";

    private LiveProbeHook() {}

    public static void install(ClassLoader cl) {
        hookBeanCtors(cl);
        hookDownloadHelper(cl);
        hookItemDelegate(cl);
    }

    private static void hookItemDelegate(ClassLoader cl) {
        try {
            Class<?> dlg = XposedHelpers.findClassIfExists(
                    "com.tencent.mobileqq.common.livephoto.layer.RFWLayerLivePhotoItemDelegate", cl);
            if (dlg == null) {
                Log.i("probe: RFWLayerLivePhotoItemDelegate not found");
                return;
            }
            int n = 0;
            for (java.lang.reflect.Constructor<?> c : dlg.getDeclaredConstructors()) {
                XposedBridge.hookMethod(c, dumpArgs("LivePhotoItemDelegate<init>"));
                n++;
            }
            for (java.lang.reflect.Method m : dlg.getDeclaredMethods()) {
                if (java.lang.reflect.Modifier.isAbstract(m.getModifiers())) continue;
                try {
                    XposedBridge.hookMethod(m, dumpArgs("LivePhotoItemDelegate." + m.getName()));
                    n++;
                } catch (Throwable ignored) {
                }
            }
            Log.i("probe hooked: LivePhotoItemDelegate (" + n + ")");
        } catch (Throwable t) {
            Log.i("delegate probe failed: " + t);
        }
    }

    private static XC_MethodHook dumpArgs(String tag) {
        return new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    StringBuilder sb = new StringBuilder("[LiveXP] ").append(tag).append('(');
                    for (Object a : param.args) {
                        String s = String.valueOf(a);
                        if (s.length() > 300) s = s.substring(0, 300) + "...";
                        sb.append(s).append(", ");
                    }
                    sb.append(')');
                    Log.i(sb.toString());
                } catch (Throwable ignored) {
                }
            }
        };
    }

    private static void hookDownloadHelper(ClassLoader cl) {
        try {
            Class<?> helper = XposedHelpers.findClassIfExists(DL_HELPER, cl);
            if (helper == null) {
                Log.i("probe: LivePhotoDownloadHelper not found");
                return;
            }
            int n = 0;
            for (java.lang.reflect.Method m : helper.getDeclaredMethods()) {
                if (java.lang.reflect.Modifier.isAbstract(m.getModifiers())) continue;
                try {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                StringBuilder sb = new StringBuilder("[LiveXP] LivePhotoDownloadHelper.")
                                        .append(param.method.getName()).append('(');
                                for (Object a : param.args) {
                                    String s = String.valueOf(a);
                                    if (s.length() > 300) s = s.substring(0, 300) + "...";
                                    sb.append(s).append(", ");
                                }
                                sb.append(") => ");
                                String r = String.valueOf(param.getResult());
                                if (r.length() > 400) r = r.substring(0, 400) + "...";
                                Log.i(sb.append(r).toString());
                            } catch (Throwable ignored) {
                            }
                        }
                    });
                    n++;
                } catch (Throwable ignored) {
                }
            }
            Log.i("probe hooked: LivePhotoDownloadHelper (" + n + " methods)");
        } catch (Throwable t) {
            Log.i("download helper probe failed: " + t);
        }
    }

    private static void hookBeanCtors(ClassLoader cl) {
        try {
            Class<?> bean = XposedHelpers.findClassIfExists(BEAN, cl);
            if (bean == null) {
                Log.i("probe: LivePhotoLocalBean not found");
                return;
            }
            for (java.lang.reflect.Constructor<?> ctor : bean.getDeclaredConstructors()) {
                XposedBridge.hookMethod(ctor, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            StringBuilder sb = new StringBuilder("[LiveXP] LivePhotoLocalBean<init>: ");
                            for (Class<?> c = bean; c != null && c != Object.class; c = c.getSuperclass()) {
                                for (Field f : c.getDeclaredFields()) {
                                    if (Modifier.isStatic(f.getModifiers())) continue;
                                    try {
                                        f.setAccessible(true);
                                        String v = String.valueOf(f.get(param.thisObject));
                                        if (v.length() > 200) v = v.substring(0, 200) + "...";
                                        sb.append(f.getName()).append('=').append(v).append("; ");
                                    } catch (Throwable ignored) {
                                    }
                                }
                            }
                            Log.i(sb.toString());
                        } catch (Throwable t) {
                            Log.i("probe error: " + t);
                        }
                    }
                });
            }
            Log.i("probe hooked: LivePhotoLocalBean (" + bean.getDeclaredConstructors().length + " ctors)");
        } catch (Throwable t) {
            Log.i("probe install failed: " + t);
        }
    }
}
