package com.livexp.qq;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.util.ArrayList;
import java.util.List;

/**
 * 单图预览页图片右上角的实况角标。
 *
 * 之前发完转码视频后,相册里那张实况图会被换成视频,预览就只剩视频、纯图片发不出去;
 * 现在原图保持不动,只在右上角挂一个点环角标提示这是实况。
 */
public final class PreviewBadge {

    private static final int BADGE_ID = 0x7e0b1e21;
    private static final int SIZE_DP = 26;

    private PreviewBadge() {}

    public static void show(View root) {
        if (!(root instanceof ViewGroup)) return;
        ViewGroup g = (ViewGroup) root;
        View image = previewImage(g);
        View badge = g.findViewById(BADGE_ID);
        if (badge == null) {
            Context ctx = g.getContext();
            FrameLayout b = new FrameLayout(ctx);
            b.setId(BADGE_ID);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0x73000000);
            bg.setCornerRadius(dp(ctx, 8));
            b.setBackground(bg);
            b.addView(new LiveMarkView(ctx, 0.78f), new FrameLayout.LayoutParams(
                    dp(ctx, 16), dp(ctx, 16), Gravity.CENTER));
            int size = dp(ctx, SIZE_DP);
            ViewGroup.LayoutParams lp = g instanceof FrameLayout
                    ? new FrameLayout.LayoutParams(size, size)
                    : new ViewGroup.LayoutParams(size, size);
            g.addView(b, lp);
            badge = b;
        }
        badge.setVisibility(View.VISIBLE);
        final View badgeView = badge;
        final View img = image;
        position(g, badge, image);
        badge.post(() -> position(g, badgeView, img));
    }

    public static void hide(View root) {
        if (root == null) return;
        View badge = root.findViewById(BADGE_ID);
        if (badge == null) return;
        android.view.ViewParent p = badge.getParent();
        if (p instanceof ViewGroup) ((ViewGroup) p).removeView(badge);
    }

    /** 预览画面:面积最大的那张图(底栏里的小图标不算)。 */
    private static View previewImage(ViewGroup root) {
        List<View> all = new ArrayList<>();
        collect(root, all);
        View best = null;
        long bestArea = 0;
        for (View v : all) {
            if (!(v instanceof ImageView)) continue;
            if (v.getWidth() <= 0 || v.getHeight() <= 0) continue;
            long area = (long) v.getWidth() * v.getHeight();
            if (area > bestArea) {
                bestArea = area;
                best = v;
            }
        }
        return best;
    }

    private static void collect(View cur, List<View> out) {
        if (!(cur instanceof ViewGroup)) return;
        ViewGroup g = (ViewGroup) cur;
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c instanceof ImageView) out.add(c);
            if (c instanceof ViewGroup) collect(c, out);
        }
    }

    /** 角标贴到图片右上角;找不到图片就贴到内容区右上角。 */
    private static void position(ViewGroup root, View badge, View image) {
        int pad = dp(root.getContext(), 6);
        int x;
        int y;
        if (image != null) {
            int[] off = offsetInRoot(root, image);
            if (off == null) return;
            x = off[0] + image.getWidth() - badge.getWidth() - pad;
            y = off[1] + pad;
        } else {
            x = root.getWidth() - badge.getWidth() - pad;
            y = pad;
        }
        if (x < 0) x = pad;
        if (y < 0) y = pad;
        badge.setTranslationX(x);
        badge.setTranslationY(y);
    }

    private static int[] offsetInRoot(ViewGroup root, View v) {
        float dx = 0, dy = 0;
        View cur = v;
        while (cur != root) {
            dx += cur.getLeft() + cur.getTranslationX();
            dy += cur.getTop() + cur.getTranslationY();
            android.view.ViewParent p = cur.getParent();
            if (!(p instanceof View)) return null;
            cur = (View) p;
        }
        return new int[]{(int) dx, (int) dy};
    }

    private static int dp(Context ctx, float value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                ctx.getResources().getDisplayMetrics());
    }
}
