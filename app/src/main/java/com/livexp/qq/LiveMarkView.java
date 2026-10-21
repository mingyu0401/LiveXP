package com.livexp.qq;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** 「实况」点环图标(与 APP 启动图标同款):20 点圆环 + 实心环 + 圆盘,白色,宿主进程代码绘制(不能加载模块 R 资源)。 */
public final class LiveMarkView extends View {

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);

    public LiveMarkView(Context c) {
        super(c);
        fill.setColor(0xFFFFFFFF);
        fill.setStyle(Paint.Style.FILL);
        stroke.setColor(0xFFFFFFFF);
        stroke.setStyle(Paint.Style.STROKE);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        float scale = Math.min(w, h) * 0.58f / 200f;
        canvas.save();
        canvas.translate(w / 2f, h / 2f);
        canvas.scale(scale, scale);
        for (int i = 0; i < 20; i++) {
            double a = Math.PI * 2 * i / 20;
            canvas.drawCircle((float) (Math.cos(a) * 84), (float) (Math.sin(a) * 84), 9, fill);
        }
        stroke.setStrokeWidth(13);
        canvas.drawCircle(0, 0, 57, stroke);
        canvas.drawCircle(0, 0, 29, fill);
        canvas.restore();
    }
}
