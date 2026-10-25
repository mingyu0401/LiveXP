package com.livexp.qq;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

/**
 * 状态接收端:QQ 进程里的模块通过 ContentResolver.call() 把激活心跳写进本 App。
 * 广播在部分 ROM(如 ColorOS 的 HANS 冻结策略)上会被 defer,provider 调用是同步 binder,
 * 更可靠,所以主通道用 call,广播只留作兜底。
 */
public class StatusProvider extends ContentProvider {

    public static final String AUTHORITY = "com.livexp.qq.status";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Bundle out = new Bundle();
        if (!"report".equals(method) || extras == null) return out;
        Context ctx = getContext();
        if (ctx == null) return out;
        SharedPreferences sp = ctx.getSharedPreferences(StatusReceiver.PREFS, Context.MODE_PRIVATE);
        sp.edit()
                .putLong("ts", extras.getLong("ts", System.currentTimeMillis()))
                .putString("lsposed", extras.getString("lsposed"))
                .putInt("api", extras.getInt("api", -1))
                .putString("qq", extras.getString("qq"))
                .putLong("threshold", extras.getLong("threshold", -1))
                .apply();
        out.putBoolean("ok", true);
        return out;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
                        String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
