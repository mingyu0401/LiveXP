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

    private LiveProbeHook() {}

    public static void install(ClassLoader cl) {
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
