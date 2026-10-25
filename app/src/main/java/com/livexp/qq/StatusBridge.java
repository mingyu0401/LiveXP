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
    /** 独立 App 写不了 QQ 的 Android/data 目录时,托 QQ 进程里的模块代写阈值。 */
    static final String ACTION_SET_THRESHOLD = "com.livexp.qq.SET_THRESHOLD";
    private static final String APP_PKG = "com.livexp.qq";
    private static final String APP_RECEIVER = APP_PKG + ".StatusReceiver";
    /** 独立 App 的 StatusProvider(写心跳主通道)。 */
    private static final String STATUS_URI = "content://com.livexp.qq.status";
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
            filter.addAction(ACTION_SET_THRESHOLD);
            BroadcastReceiver ping = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    final Context c = context == null ? ctx : context;
                    // provider 调用是同步 binder,放到后台线程,别堵 QQ 主线程
                    if (intent != null && ACTION_SET_THRESHOLD.equals(intent.getAction())) {
                        final long bytes = intent.getLongExtra("bytes", -1);
                        new Thread(() -> {
                            boolean ok = ThresholdConfig.set(bytes);
                            Log.i("threshold set via app bridge: bytes=" + bytes + " ok=" + ok);
                            report(c, "threshold");
                        }, "LiveXP-status").start();
                        return;
                    }
                    new Thread(() -> report(c, "ping"), "LiveXP-status").start();
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

    /**
     * 推一次状态给独立 App:优先 ContentResolver.call(同步 binder,不会被 ROM 的广播 defer 策略拖住),
     * 失败再退回显式广播。
     */
    static void report(Context ctx, String why) {
        try {
            LspInfo.ensure(ctx);
            android.os.Bundle b = new android.os.Bundle();
            b.putLong("ts", System.currentTimeMillis());
            b.putString("lsposed", LspInfo.lsposedVersion);
            b.putInt("api", LspInfo.apiVersion);
            b.putString("qq", LspInfo.qqVersion);
            b.putLong("threshold", ThresholdConfig.get());
            try {
                ctx.getPackageManager().getPackageInfo(APP_PKG, 0);
                Log.i("status bridge: target app visible");
            } catch (Throwable t) {
                Log.i("status bridge: target app NOT visible: " + t);
            }
            boolean ok = false;
            try {
                android.os.Bundle r = ctx.getContentResolver().call(
                        android.net.Uri.parse(STATUS_URI), "report", null, b);
                ok = r != null && r.getBoolean("ok", false);
            } catch (Throwable t) {
                Log.i("status bridge: provider call failed " + t);
            }
            if (!ok) {
                Intent i = new Intent(ACTION_STATUS);
                i.setClassName(APP_PKG, APP_RECEIVER);
                i.putExtras(b);
                ctx.sendBroadcast(i);
            }
            Log.i("status bridge: report(" + why + ") viaProvider=" + ok + " lsposed=" + LspInfo.lsposedVersion
                    + " api=" + LspInfo.apiVersion + " qq=" + LspInfo.qqVersion);
        } catch (Throwable t) {
            Log.i("status bridge: report failed " + t);
        }
    }
}
