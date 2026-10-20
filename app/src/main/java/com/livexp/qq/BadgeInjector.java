package com.livexp.qq;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.constraintlayout.widget.ConstraintSet;

/** 在聊天气泡根布局(BaseChatItemLayout)上叠加/移除「实况」角标。 */
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

        if (root instanceof ConstraintLayout) {
            ConstraintLayout cl = (ConstraintLayout) root;
            cl.addView(badge, new ConstraintLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            View anchor = directChildOf(root, findFirstImageView(root));
            ConstraintSet cs = new ConstraintSet();
            cs.clone(cl);
            int anchorId;
            if (anchor != null) {
                if (anchor.getId() == View.NO_ID) anchor.setId(View.generateViewId());
                anchorId = anchor.getId();
            } else {
                anchorId = ConstraintSet.PARENT_ID;
            }
            int margin = dp(root, 4);
            cs.connect(BADGE_ID, ConstraintSet.TOP, anchorId, ConstraintSet.TOP, margin);
            cs.connect(BADGE_ID, ConstraintSet.START, anchorId, ConstraintSet.START, margin);
            cs.applyTo(cl);
        } else {
            root.addView(badge, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    public static void hide(ViewGroup root) {
        View badge = root.findViewById(BADGE_ID);
        if (badge != null) root.removeView(badge);
    }

    private static View directChildOf(ViewGroup root, View view) {
        View current = view;
        while (current != null && current.getParent() != root) {
            if (!(current.getParent() instanceof View)) return null;
            current = (View) current.getParent();
        }
        return current;
    }

    private static View findFirstImageView(ViewGroup group) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof ImageView) return child;
            if (child instanceof ViewGroup) {
                View found = findFirstImageView((ViewGroup) child);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static int dp(View v, float value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                v.getResources().getDisplayMetrics());
    }
}
