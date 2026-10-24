package com.livexp.qq;

import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;

/**
 * 关于页顶部图案长按彩蛋:3 秒内节奏逐渐加快的震动。
 * 用一条 waveform 一次性下发,松手即 cancel。
 */
public final class Haptics {

    private static final long TOTAL_MS = 3000;
    private static final long BUZZ_MS = 26;
    private static volatile Vibrator current;

    private Haptics() {}

    public static void play(Context ctx) {
        try {
            Vibrator v = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null || !v.hasVibrator()) return;
            long[] wave = ramp(TOTAL_MS);
            try {
                v.cancel(); // 叠加重放会拖长震动,新的一次先收掉旧的
            } catch (Throwable ignored) {
            }
            current = v;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(wave, -1));
            } else {
                v.vibrate(wave, -1);
            }
            Log.i("haptic ramp start: steps=" + (wave.length / 2) + " total=" + TOTAL_MS + "ms");
        } catch (Throwable t) {
            Log.i("haptic play error: " + t);
        }
    }

    public static void stop() {
        Vibrator v = current;
        current = null;
        if (v != null) {
            try {
                v.cancel();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 交替的 [间隔, 震动] 序列:间隔从 220ms 指数衰减到 40ms,总时长不超过 total。 */
    static long[] ramp(long total) {
        java.util.ArrayList<Long> out = new java.util.ArrayList<>();
        long used = 0;
        double gap = 220;
        while (true) {
            long g = Math.max(40, Math.round(gap));
            if (used + g + BUZZ_MS > total) break;
            out.add(g);
            out.add(BUZZ_MS);
            used += g + BUZZ_MS;
            gap *= 0.90;
        }
        long[] wave = new long[out.size()];
        for (int i = 0; i < wave.length; i++) wave[i] = out.get(i);
        return wave;
    }
}
