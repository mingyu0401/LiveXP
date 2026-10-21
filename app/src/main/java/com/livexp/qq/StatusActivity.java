package com.livexp.qq;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayDeque;

/** LiveXP 独立状态页:显示模块运行日志末尾内容。 */
public class StatusActivity extends Activity {

    // 注意:模块自身进程读不了 QQ 的 Android/data,这里保留入口但多半会显示提示;
    // 看日志请用 QQ 设置页的 LiveXP 胶囊弹窗(QQ 进程内可读自己的数据目录)。
    private static final File LOG_FILE = new File("/sdcard/Android/data/com.tencent.mobileqq/LiveXP/livexp.log");

    private LinearLayout container;
    private TextView logView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF101418);
        int pad = dp(16);
        root.setPadding(pad, dp(28), pad, pad);

        TextView title = new TextView(this);
        title.setText("LiveXP v" + BuildConfig.VERSION_NAME);
        title.setTextColor(Color.WHITE);
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView subtitle = new TextView(this);
        subtitle.setText("QQ 实况图检测模块 · 运行日志(末尾 150 行)");
        subtitle.setTextColor(0xFF8A97A8);
        subtitle.setTextSize(13);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.bottomMargin = dp(12);
        root.addView(subtitle, subLp);

        container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        root.addView(container, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        container.removeAllViews();
        String log = readLogTail(150);
        if (log != null) {
            logView = new TextView(this);
            logView.setTypeface(Typeface.MONOSPACE);
            logView.setTextColor(0xFFD7E3EE);
            logView.setTextSize(11);
            logView.setText(log);

            ScrollView scroll = new ScrollView(this);
            scroll.setFillViewport(true);
            scroll.addView(logView, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            container.addView(scroll, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            scroll.post(() -> scroll.fullScroll(ScrollView.FOCUS_DOWN));
        } else {
            TextView hint = new TextView(this);
            hint.setTextColor(0xFFE8B04B);
            hint.setTextSize(14);
            hint.setLineSpacing(dp(4), 1f);
            hint.setText("无法读取日志文件\n\nLiveXP 在 QQ 进程里把日志写到:\n/sdcard/Download/LiveXP/livexp.log\n\n需要授予「所有文件访问权限」才能在这个界面里查看。");
            container.addView(hint, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            if (!Environment.isExternalStorageManager()) {
                Button grant = new Button(this);
                grant.setText("授予文件访问权限");
                grant.setTextColor(Color.WHITE);
                grant.setBackgroundColor(0xFF2E7D32);
                grant.setOnClickListener(v -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:" + getPackageName())));
                    } catch (Throwable t) {
                        try {
                            startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                        } catch (Throwable ignored) {}
                    }
                });
                LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                btnLp.topMargin = dp(16);
                btnLp.gravity = Gravity.CENTER_HORIZONTAL;
                container.addView(grant, btnLp);
            }
        }
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
