package com.livexp.qq;

import android.content.Context;
import android.content.pm.PackageInfo;

import de.robv.android.xposed.XposedBridge;

/**
 * 运行时探测要展示的版本信息:宿主 QQ 版本、LSPosed 版本与 API。
 * 只用于关于页/设置页文案,探测失败一律降级为空,不影响任何功能。
 * 注意:本类在独立 App 里也会被引用(同一个 APK),所有 Xposed 调用都必须包在 try 里。
 */
public final class LspInfo {

    /** LSPosed 的 BuildConfig 可能随框架 dex 注入宿主进程,优先读它拿版本号。 */
    private static final String[] BUILD_CONFIGS = {
            "org.lsposed.lspd.BuildConfig",
            "org.lsposed.lspd.core.BuildConfig",
            "org.lsposed.lspd.service.BuildConfig",
            "org.lsposed.lspd.impl.BuildConfig"
    };
    /** 管理器 APK 的包名(装了就能读到 versionName/versionCode)。 */
    private static final String[] MANAGER_PKGS = {
            "org.lsposed.manager", "com.lsposed.manager"
    };

    /** 形如 "2.2.1 (7925)";探测不到为 null。 */
    public static volatile String lsposedVersion;
    /** Xposed/LSPosed API 版本;探测不到为 -1。 */
    public static volatile int apiVersion = -1;
    /** 宿主 QQ 的 versionName;探测不到为 null。 */
    public static volatile String qqVersion;

    private static volatile boolean probed;

    private LspInfo() {}

    public static synchronized void ensure(Context ctx) {
        if (probed) return;
        probed = true;
        try {
            apiVersion = XposedBridge.getXposedVersion();
        } catch (Throwable t) {
            apiVersion = -1;
        }
        Log.i("lsp probe: XposedBridge.getXposedVersion()=" + apiVersion);
        probeBuildConfig();
        if (lsposedVersion == null) probeManager(ctx);
        if (ctx != null) {
            try {
                PackageInfo pi = ctx.getPackageManager().getPackageInfo("com.tencent.mobileqq", 0);
                qqVersion = pi.versionName;
            } catch (Throwable t) {
                Log.i("lsp probe: qq version unavailable: " + t);
            }
        }
        Log.i("lsp probe: qq=" + qqVersion + " lsposed=" + lsposedVersion + " api=" + apiVersion);
    }

    private static void probeBuildConfig() {
        Class<?>[] owners = new Class<?>[]{LspInfo.class, XposedBridge.class};
        for (String cn : BUILD_CONFIGS) {
            for (Class<?> owner : owners) {
                try {
                    ClassLoader cl = owner.getClassLoader();
                    Class<?> c = Class.forName(cn, false, cl);
                    String vn = staticString(c, "VERSION_NAME");
                    int vc = staticInt(c, "VERSION_CODE");
                    Log.i("lsp probe: " + cn + " via " + cl + " name=" + vn + " code=" + vc);
                    if (lsposedVersion == null && vn != null && !vn.isEmpty()) {
                        lsposedVersion = vc > 0 ? vn + " (" + vc + ")" : vn;
                        return;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void probeManager(Context ctx) {
        if (ctx == null) return;
        for (String pkg : MANAGER_PKGS) {
            try {
                PackageInfo pi = ctx.getPackageManager().getPackageInfo(pkg, 0);
                long code = pi.getLongVersionCode();
                Log.i("lsp probe: manager " + pkg + " " + pi.versionName + " (" + code + ")");
                if (lsposedVersion == null) {
                    lsposedVersion = code > 0 ? pi.versionName + " (" + code + ")" : pi.versionName;
                }
                return;
            } catch (Throwable t) {
                Log.i("lsp probe: manager " + pkg + " missing");
            }
        }
    }

    /** 供设置页展示的 LSPosed 文案,如 "LSPosed 2.2.1 (7925)"。 */
    public static String lsposedLabel() {
        String v = lsposedVersion;
        if (v != null && !v.isEmpty()) return "LSPosed " + v;
        if (apiVersion > 0) return "LSPosed API " + apiVersion;
        return "LSPosed";
    }

    /** 供独立 App 展示的 LSPosed 版本 + API,如 "LSPosed 2.2.1 (7925), API 102"。 */
    public static String lsposedWithApi(String version) {
        StringBuilder sb = new StringBuilder("LSPosed");
        if (version != null && !version.isEmpty()) sb.append(' ').append(version);
        if (apiVersion > 0) sb.append(", API ").append(apiVersion);
        return sb.toString();
    }

    private static String staticString(Class<?> c, String field) {
        try {
            java.lang.reflect.Field f = c.getField(field);
            Object v = f.get(null);
            return v instanceof String ? (String) v : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static int staticInt(Class<?> c, String field) {
        try {
            java.lang.reflect.Field f = c.getField(field);
            Object v = f.get(null);
            return v instanceof Number ? ((Number) v).intValue() : -1;
        } catch (Throwable t) {
            return -1;
        }
    }
}
