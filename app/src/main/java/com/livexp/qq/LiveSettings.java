package com.livexp.qq;

import android.content.Context;
import android.content.SharedPreferences;

/** 模块开关。存 QQ 进程内的 SharedPreferences,默认全开。 */
public final class LiveSettings {

    private static final String PREFS = "livexp";
    private static final String KEY_SEND_PARSE = "send_parse_enabled";

    private static volatile Boolean sendParseCache;

    private LiveSettings() {}

    /** 「解析实况」功能开关:关闭后发送页不再挂按钮。 */
    public static boolean sendParseEnabled(Context ctx) {
        Boolean cached = sendParseCache;
        if (cached != null) return cached;
        boolean on = true;
        try {
            Context app = ctx != null ? ctx : HookEntry.appCtx;
            if (app != null) {
                on = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .getBoolean(KEY_SEND_PARSE, true);
            }
        } catch (Throwable t) {
            Log.i("read setting failed: " + t);
        }
        sendParseCache = on;
        return on;
    }

    public static void setSendParseEnabled(Context ctx, boolean on) {
        sendParseCache = on;
        try {
            Context app = ctx != null ? ctx : HookEntry.appCtx;
            if (app != null) {
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .edit().putBoolean(KEY_SEND_PARSE, on).apply();
            }
        } catch (Throwable t) {
            Log.i("save setting failed: " + t);
        }
        Log.i("setting changed: 解析实况=" + on);
        if (!on) LiveSendHook.onParseDisabled();
    }
}
