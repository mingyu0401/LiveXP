package com.livexp.qq;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayDeque;

/**
 * LiveXP 独立 App:与 QQ 设置页同款关于界面;「查看运行日志」切入日志视图。
 * 关键差异:这里要判断模块是否真的被 LSPosed 启用 —— 向 QQ 进程探活,
 * 模块收到后经 StatusProvider(失败退回广播)回报状态与实测 QQ 版本,超时没回就算未激活。
 */
public class StatusActivity extends Activity {

    /** 日志在 QQ 进程内写入 QQ 自己的数据目录;本 App 拿不到该路径时会给出提示。 */
    private static final File LOG_FILE = new File("/sdcard/Android/data/com.tencent.mobileqq/LiveXP/livexp.log");
    private static final String QQ_PKG = "com.tencent.mobileqq";
    /** 探活等待时长:模块进程响应通常在几十毫秒内,1.2s 足够覆盖冷启动。 */
    private static final long PING_WAIT_MS = 1200;
    /** 页面停留期间的复查间隔:QQ 后启动/模块后启用也能自己变回来。 */
    private static final long REFRESH_MS = 5000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private LinearLayout root;
    private WebView web;
    private boolean dark;
    private boolean resumed;
    private boolean pageLoaded;
    private long checkSeq;
    private boolean stateActive;
    private String stateVer = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        dark = ModuleAboutPage.isDark(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(dark ? Color.rgb(0x0D, 0x11, 0x17) : Color.rgb(0xF6, 0xF8, 0xFA));
        setContentView(root);
        showAbout();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        handler.postDelayed(this::checkActive, 200);
    }

    @Override
    protected void onPause() {
        resumed = false;
        handler.removeCallbacksAndMessages(null);
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        // 日志视图时返回键回到关于页
        if (web == null) {
            showAbout();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (web != null) web.destroy();
        super.onDestroy();
    }

    private void showAbout() {
        if (web != null) {
            root.removeView(web);
            web.destroy();
            web = null;
        }
        root.removeAllViews();
        pageLoaded = false;

        SharedPreferences sp = getSharedPreferences(StatusReceiver.PREFS, MODE_PRIVATE);
        boolean cached = sp.getLong("ts", 0) > 0;
        String ver = cached ? activeVer(sp) : idleVer(sp);
        stateActive = cached;
        stateVer = ver;

        WebView page = ModuleAboutPage.createWebView(this, new ModuleAboutPage.Actions() {
            @Override
            public void openLogs() {
                showLogs();
            }

            @Override
            public void openSupport() {
                ModuleAboutPage.showSupportDialog(StatusActivity.this);
            }
        }, () -> {
            pageLoaded = true;
            applyState(stateActive, stateVer);
        });
        root.addView(page, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        this.web = page;
        page.loadDataWithBaseURL(null, ModuleAboutPage.appHtml(this, dark, cached, ver),
                "text/html", "utf-8", null);
    }

    /** 发一次探活广播,等模块回状态;无论结果如何都排下一次复查。 */
    private void checkActive() {
        if (!resumed) return;
        final long wall = System.currentTimeMillis();
        final long seq = ++checkSeq;
        try {
            Intent ping = new Intent(StatusBridge.ACTION_PING);
            ping.setPackage(QQ_PKG);
            sendBroadcast(ping);
        } catch (Throwable ignored) {
        }
        handler.postDelayed(() -> {
            if (!resumed || seq != checkSeq) return;
            SharedPreferences sp = getSharedPreferences(StatusReceiver.PREFS, MODE_PRIVATE);
            boolean active = sp.getLong("ts", 0) >= wall;
            String ver = active ? activeVer(sp) : idleVer(sp);
            stateActive = active;
            stateVer = ver;
            applyState(active, ver);
            handler.postDelayed(this::checkActive, REFRESH_MS);
        }, PING_WAIT_MS);
    }

    /** 把状态推给页面:只改 DOM,不整页重载,避免闪烁。 */
    private void applyState(boolean active, String ver) {
        if (web == null || !pageLoaded) return;
        try {
            web.evaluateJavascript("livexpState(" + active + "," + JSONObject.quote(ver) + ")", null);
        } catch (Throwable ignored) {
        }
    }

    /** 激活时的副标题:框架名 + API 级别(LSPosed 版本号暂不探测,管理器 versionName 并不可靠)。 */
    private static String activeVer(SharedPreferences sp) {
        int api = sp.getInt("api", -1);
        return api > 0 ? "LSPosed API " + api : "LSPosed";
    }

    /** 未激活时的副标题:本机实际 QQ 版本,读不到就退回上次上报值。 */
    private String idleVer(SharedPreferences sp) {
        String qq = readQqVersion();
        if (qq == null) qq = sp.getString("qq", null);
        return qq == null ? "for QQ (NT)" : "for QQ " + qq + " (NT)";
    }

    private String readQqVersion() {
        try {
            return getPackageManager().getPackageInfo(QQ_PKG, 0).versionName;
        } catch (Throwable t) {
            return null;
        }
    }

    private void showLogs() {
        handler.removeCallbacksAndMessages(null);
        if (web != null) {
            root.removeView(web);
            web.destroy();
            web = null;
        }
        root.removeAllViews();

        int pad = dp(16);
        root.setPadding(pad, dp(28), pad, pad);

        TextView title = new TextView(this);
        title.setText("LiveXP v" + BuildConfig.VERSION_NAME + " · 运行日志");
        title.setTextColor(dark ? Color.WHITE : 0xFF1F2328);
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView subtitle = new TextView(this);
        subtitle.setText("日志由模块在 QQ 进程内写入:/sdcard/Android/data/com.tencent.mobileqq/LiveXP/livexp.log(末尾 200 行)");
        subtitle.setTextColor(dark ? 0xFF8A97A8 : 0xFF6E7781);
        subtitle.setTextSize(12);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.bottomMargin = dp(12);
        root.addView(subtitle, subLp);

        TextView logView = new TextView(this);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextColor(dark ? 0xFFD7E3EE : 0xFF24292F);
        logView.setTextSize(11);
        String log = readLogTail(200);
        logView.setText(log != null ? log : "无法读取日志文件\n\n该目录只有 QQ 自己能读,本 App 拿不到。\n请打开 QQ → 设置 → LiveXP 模块 → 查看运行日志。");

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(logView, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        scroll.post(() -> scroll.fullScroll(ScrollView.FOCUS_DOWN));
    }

    private static String readLogTail(int maxLines) {
        try (BufferedReader br = new BufferedReader(new FileReader(LOG_FILE))) {
            ArrayDeque<String> lines = new ArrayDeque<>();
            String line;
            while ((line = br.readLine()) != null) {
                if (lines.size() == maxLines) lines.pollFirst();
                lines.offerLast(line);
            }
            if (lines.isEmpty()) return "(日志为空)";
            StringBuilder sb = new StringBuilder();
            for (String l : lines) {
                sb.append(l).append('\n');
            }
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
