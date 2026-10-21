package com.livexp.qq;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayDeque;

/** LiveXP 独立 App:与 QQ 设置页二级页同款关于界面;「查看运行日志」切入日志视图。 */
public class StatusActivity extends Activity {

    // 日志在 QQ 进程内写入 QQ 自己的数据目录;本 App 不是 QQ,拿不到该路径,
    // 看日志请用 QQ 设置页的「LiveXP 模块」→ 查看运行日志(QQ 进程内可读自己的数据目录)。
    private static final File LOG_FILE = new File("/sdcard/Android/data/com.tencent.mobileqq/LiveXP/livexp.log");

    private LinearLayout root;
    private WebView web;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(0x0D, 0x11, 0x17));
        setContentView(root);
        showAbout();
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
        WebView page = ModuleAboutPage.createWebView(this, new ModuleAboutPage.Actions() {
            @Override
            public void openLogs() {
                showLogs();
            }

            @Override
            public void openSupport() {
                ModuleAboutPage.showSupportDialog(StatusActivity.this);
            }
        });
        root.addView(page, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        this.web = page;
    }

    private void showLogs() {
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
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView subtitle = new TextView(this);
        subtitle.setText("日志由模块在 QQ 进程内写入:/sdcard/Android/data/com.tencent.mobileqq/LiveXP/livexp.log(末尾 200 行)");
        subtitle.setTextColor(0xFF8A97A8);
        subtitle.setTextSize(12);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.bottomMargin = dp(12);
        root.addView(subtitle, subLp);

        TextView logView = new TextView(this);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextColor(0xFFD7E3EE);
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
