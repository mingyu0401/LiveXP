package com.livexp.qq;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** 「实况」点环图标(与 APP 启动图标同款):20 点圆环 + 实心环 + 圆盘,白色,宿主进程代码绘制(不能加载模块 R 资源)。 */
public final class LiveMarkView extends View {

    private final float fillRatio;

    public LiveMarkView(Context c) {
        this(c, 0.58f);
    }

    /** fillRatio = 图案外径占视图短边的比例(1.0 会贴边)。 */
    public LiveMarkView(Context c, float fillRatio) {
        super(c);
        this.fillRatio = fillRatio;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        float side = Math.min(w, h) * fillRatio;
        drawMark(canvas, (w - side) / 2f, (h - side) / 2f, side, 0xFFFFFFFF);
    }

    /** 离屏绘制同款点环图标(合成视频烧录角标要用,不能依赖 View attach)。 */
    public static void drawMark(Canvas canvas, float left, float top, float side, int color) {
        if (side <= 0f) return;
        float scale = side / 200f;
        int save = canvas.save();
        canvas.translate(left + side / 2f, top + side / 2f);
        canvas.scale(scale, scale);
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(color);
        fill.setStyle(Paint.Style.FILL);
        Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        stroke.setColor(color);
        stroke.setStyle(Paint.Style.STROKE);
        for (int i = 0; i < 20; i++) {
            double a = Math.PI * 2 * i / 20;
            canvas.drawCircle((float) (Math.cos(a) * 84), (float) (Math.sin(a) * 84), 9, fill);
        }
        stroke.setStrokeWidth(13);
        canvas.drawCircle(0, 0, 57, stroke);
        canvas.drawCircle(0, 0, 29, fill);
        canvas.restoreToCount(save);
    }
}
