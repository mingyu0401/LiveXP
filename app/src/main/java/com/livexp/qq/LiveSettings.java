package com.livexp.qq;

import android.content.Context;
import android.content.SharedPreferences;

/** 模块开关。存 QQ 进程内的 SharedPreferences。 */
public final class LiveSettings {

    private static final String PREFS = "livexp";
    private static final String KEY_SEND_PARSE = "send_parse_enabled";

    private static volatile Boolean sendParseCache;

    private LiveSettings() {}

    /** 「实况转MP4」功能开关:关闭后发送页不挂按钮(默认关闭)。 */
    public static boolean sendParseEnabled(Context ctx) {
        Boolean cached = sendParseCache;
        if (cached != null) return cached;
        boolean on = false;
        try {
            Context app = ctx != null ? ctx : HookEntry.appCtx;
            if (app != null) {
                on = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .getBoolean(KEY_SEND_PARSE, false);
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
        Log.i("setting changed: 实况转MP4=" + on);
        if (!on) LiveSendHook.onParseDisabled();
    }
}
