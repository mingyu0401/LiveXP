package com.livexp.qq;

import android.content.Context;
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
 * 在聊天气泡的每张实况图上叠加点环图标角标(与 APP 启动图标同款图案)。
 * 不依赖 androidx 类(QQ 进程里没有模块的依赖)。一条气泡多张图时按图片下标各贴一个。
 */
public final class BadgeInjector {

    private static final int BADGE_ID = 0x7e0b1e02;
    private static final int MAX_BADGES = 9;

    private BadgeInjector() {}

    public static void show(ViewGroup root) {
        show(root, -1);
    }

    /** picIndex = 消息里第几张图(-1 表示不区分,贴在最大的那张上)。 */
    public static void show(ViewGroup root, int picIndex) {
        int slot = Math.max(0, Math.min(MAX_BADGES - 1, picIndex));
        int id = BADGE_ID + slot;
        if (root.findViewById(id) != null) return;
        Context ctx = root.getContext();
        FrameLayout badge = new FrameLayout(ctx);
        badge.setId(id);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x99000000);
        bg.setCornerRadius(dp(ctx, 7));
        badge.setBackground(bg);
        LiveMarkView mark = new LiveMarkView(ctx);
        badge.addView(mark, new FrameLayout.LayoutParams(
                dp(ctx, 18), dp(ctx, 18), Gravity.CENTER));
        root.addView(badge);
        int size = dp(ctx, 24);
        ViewGroup.LayoutParams lp = badge.getLayoutParams();
        if (lp != null) {
            lp.width = size;
            lp.height = size;
            badge.setLayoutParams(lp);
        }
        positionOverImage(root, badge, picIndex);
        // 绑定时子视图往往还没布局,布局完成后再校正位置
        badge.post(() -> {
            if (badge.getParent() == root) positionOverImage(root, badge, picIndex);
        });
    }

    private static void positionOverImage(ViewGroup root, View badge, int picIndex) {
        View anchor = imageFor(root, picIndex);
        if (anchor == null) return;
        int[] pos = offsetInRoot(root, anchor);
        if (pos == null) return;
        badge.setTranslationX(pos[0] + anchor.getWidth() - badge.getWidth() - dp(root.getContext(), 4));
        badge.setTranslationY(pos[1] + dp(root.getContext(), 4));
    }

    /** 第 picIndex 张消息图;下标越界时退回最大的那张。 */
    private static View imageFor(ViewGroup root, int picIndex) {
        List<View> images = contentImages(root);
        if (images.isEmpty()) return null;
        if (picIndex < 0) {
            View best = images.get(0);
            long bestArea = -1;
            for (View v : images) {
                long area = (long) v.getWidth() * v.getHeight();
                if (area > bestArea) {
                    bestArea = area;
                    best = v;
                }
            }
            return best;
        }
        return images.get(Math.min(picIndex, images.size() - 1));
    }

    /** 气泡里的消息图(排除头像等小图):面积不低于最大图的 25%,按从上到下排序。 */
    private static List<View> contentImages(ViewGroup root) {
        List<View> all = new ArrayList<>();
        collect(root, root, all);
        long maxArea = 0;
        for (View v : all) maxArea = Math.max(maxArea, (long) v.getWidth() * v.getHeight());
        List<View> out = new ArrayList<>();
        for (View v : all) {
            if ((long) v.getWidth() * v.getHeight() * 4 >= maxArea) out.add(v);
        }
        out.sort((a, b) -> {
            int[] pa = offsetInRoot(root, a);
            int[] pb = offsetInRoot(root, b);
            if (pa == null || pb == null) return 0;
            return pa[1] != pb[1] ? Integer.compare(pa[1], pb[1]) : Integer.compare(pa[0], pb[0]);
        });
        return out;
    }

    private static void collect(View cur, View root, List<View> out) {
        if (!(cur instanceof ViewGroup)) return;
        ViewGroup g = (ViewGroup) cur;
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c instanceof ImageView && c.getWidth() > 0 && c.getHeight() > 0) out.add(c);
            if (c instanceof ViewGroup) collect(c, root, out);
        }
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

    public static void hide(ViewGroup root) {
        for (int i = 0; i < MAX_BADGES; i++) {
            View badge = root.findViewById(BADGE_ID + i);
            if (badge != null) root.removeView(badge);
        }
    }

    /** 气泡根布局里头像和消息图都是 ImageView;实况标要贴在消息图上,取面积最大的那个。 */
    public static View findLargestImageView(ViewGroup group) {
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

    private static int dp(Context ctx, float value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                ctx.getResources().getDisplayMetrics());
    }
}
