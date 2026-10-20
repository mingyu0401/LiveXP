package com.livexp.qq;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

/** 在聊天气泡根布局上叠加「实况」角标。不依赖 androidx 类(QQ 进程里没有模块的依赖)。 */
public final class BadgeInjector {

    private static final int BADGE_ID = 0x7e0b1e02;

    private BadgeInjector() {}

    public static void show(ViewGroup root) {
        if (root.findViewById(BADGE_ID) != null) return;
        TextView badge = new TextView(root.getContext());
        badge.setId(BADGE_ID);
        badge.setText("实况");
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        badge.setTextColor(Color.WHITE);
        int padH = dp(root, 5);
        int padV = dp(root, 2);
        badge.setPadding(padH, padV, padH, padV);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x99000000);
        bg.setCornerRadius(dp(root, 6));
        badge.setBackground(bg);
        root.addView(badge);
        positionOverImage(root, badge);
        // 绑定时子视图往往还没布局,布局完成后再校正位置
        badge.post(() -> {
            if (badge.getParent() == root) positionOverImage(root, badge);
        });
    }

    private static void positionOverImage(ViewGroup root, TextView badge) {
        View anchor = findLargestImageView(root);
        if (anchor == null) return;
        float dx = 0, dy = 0;
        View cur = anchor;
        while (cur != root) {
            dx += cur.getLeft() + cur.getTranslationX();
            dy += cur.getTop() + cur.getTranslationY();
            android.view.ViewParent p = cur.getParent();
            if (!(p instanceof View)) {
                return;
            }
            cur = (View) p;
        }
        // 右上角:图片右缘往左收一个角标宽,再留 4dp 边距
        badge.setTranslationX(dx + anchor.getWidth() - badge.getWidth() - dp(root, 4));
        badge.setTranslationY(dy + dp(root, 4));
    }

    public static void hide(ViewGroup root) {
        View badge = root.findViewById(BADGE_ID);
        if (badge != null) root.removeView(badge);
    }

    /** 气泡根布局里头像和消息图都是 ImageView;实况标要贴在消息图上,取面积最大的那个。 */
    private static View findLargestImageView(ViewGroup group) {
        View best = null;
        long bestArea = -1;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof ImageView) {
                long area = (long) child.getWidth() * child.getHeight();
                if (area > bestArea) {
                    bestArea = area;
                    best = child;
                }
            } else if (child instanceof ViewGroup) {
                View found = findLargestImageView((ViewGroup) child);
                if (found != null) {
                    long area = (long) found.getWidth() * found.getHeight();
                    if (area > bestArea) {
                        bestArea = area;
                        best = found;
                    }
                }
            }
        }
        return best;
    }

    private static int dp(View v, float value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                v.getResources().getDisplayMetrics());
    }
}
