package com.livexp.qq;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/** 独立 App 侧:接收 QQ 进程里模块发来的激活心跳,落盘供关于页读取。 */
public class StatusReceiver extends BroadcastReceiver {

    static final String PREFS = "livexp_status";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        sp.edit()
                .putLong("ts", intent.getLongExtra("ts", 0))
                .putString("lsposed", intent.getStringExtra("lsposed"))
                .putInt("api", intent.getIntExtra("api", -1))
                .putString("qq", intent.getStringExtra("qq"))
                .apply();
    }
}
