package com.livexp.qq;

import android.content.Context;
import android.content.SharedPreferences;

/** 模块开关。存 QQ 进程内的 SharedPreferences。 */
public final class LiveSettings {

    private static final String PREFS = "livexp";
    private static final String KEY_PARSE = "parse_enabled";
    private static final String KEY_SEND_PARSE = "send_parse_enabled";

    private static volatile Boolean parseCache;
    private static volatile Boolean sendParseCache;

    private LiveSettings() {}

    /** “实况图片解析”总开关:关闭后停止模块识别/预热,不影响 QQ 原生图片能力。 */
    public static boolean parsingEnabled(Context ctx) {
        Boolean cached = parseCache;
        if (cached != null) return cached;
        boolean on = true;
        try {
            Context app = ctx != null ? ctx : HookEntry.appCtx;
            if (app != null) {
                on = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .getBoolean(KEY_PARSE, true);
            }
        } catch (Throwable t) {
            Log.i("read parse setting failed: " + t);
        }
        parseCache = on;
        return on;
    }

    public static void setParsingEnabled(Context ctx, boolean on) {
        parseCache = on;
        try {
            Context app = ctx != null ? ctx : HookEntry.appCtx;
            if (app != null) {
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .edit().putBoolean(KEY_PARSE, on).apply();
            }
        } catch (Throwable t) {
            Log.i("save parse setting failed: " + t);
        }
        Log.i("setting changed: 实况图片解析=" + on);
        if (!on) LiveSendHook.onParsingDisabled();
    }

    /** “实况转MP4发送”开关:默认开启,只控制发送替换,不影响 QQ 原生图片发送/查看。 */
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
            Log.i("read send setting failed: " + t);
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
            Log.i("save send setting failed: " + t);
        }
        Log.i("setting changed: 实况转MP4发送=" + on);
        if (!on) LiveSendHook.onSendDisabled();
    }
}
