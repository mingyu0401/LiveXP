package com.livexp.qq;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import de.robv.android.xposed.XposedHelpers;

/**
 * 模块侧状态桥:QQ 进程里模块加载后,把(LSPosed 版本/API、QQ 版本)推给独立 App,
 * 并响应 App 发来的探活广播。独立 App 只靠这条通路判断「模块是否被 LSPosed 启用」。
 */
final class StatusBridge {

    static final String ACTION_STATUS = "com.livexp.qq.STATUS";
    static final String ACTION_PING = "com.livexp.qq.PING";
    private static final String APP_PKG = "com.livexp.qq";
    private static final String APP_RECEIVER = APP_PKG + ".StatusReceiver";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static volatile boolean installed;

    private StatusBridge() {}

    /** 等 QQ 进程有 Context 后再装探活接收器(attachBaseContext 阶段还取不到 Application)。 */
    static void start() {
        MAIN.postDelayed(new Runnable() {
            private int tries;

            @Override
            public void run() {
                Context ctx = appContext();
                if (ctx == null && tries++ < 24) {
                    MAIN.postDelayed(this, 500);
                    return;
                }
                if (ctx == null) {
                    Log.i("status bridge: no context, give up");
                    return;
                }
                install(ctx);
            }
        }, 800);
    }

    private static Context appContext() {
        Context c = HookEntry.appCtx;
        if (c != null) return c;
        try {
            Object o = XposedHelpers.callStaticMethod(
                    Class.forName("android.app.AndroidAppHelper"), "currentApplication");
            if (o instanceof Context) return (Context) o;
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void install(Context ctx) {
        if (installed) return;
        installed = true;
        try {
            IntentFilter filter = new IntentFilter(ACTION_PING);
            BroadcastReceiver ping = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    report(context == null ? ctx : context, "ping");
                }
            };
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ctx.registerReceiver(ping, filter, Context.RECEIVER_EXPORTED);
            } else {
                ctx.registerReceiver(ping, filter);
            }
            Log.i("status bridge: ping receiver installed");
        } catch (Throwable t) {
            Log.i("status bridge: register failed " + t);
        }
        report(ctx, "boot");
    }

    /** 推一次状态给独立 App(显式广播,目标不存在时静默失败)。 */
    static void report(Context ctx, String why) {
        try {
            LspInfo.ensure(ctx);
            Intent i = new Intent(ACTION_STATUS);
            i.setClassName(APP_PKG, APP_RECEIVER);
            i.putExtra("ts", System.currentTimeMillis());
            i.putExtra("lsposed", LspInfo.lsposedVersion);
            i.putExtra("api", LspInfo.apiVersion);
            i.putExtra("qq", LspInfo.qqVersion);
            ctx.sendBroadcast(i);
            Log.i("status bridge: report(" + why + ") lsposed=" + LspInfo.lsposedVersion
                    + " api=" + LspInfo.apiVersion + " qq=" + LspInfo.qqVersion);
        } catch (Throwable t) {
            Log.i("status bridge: report failed " + t);
        }
    }
}
