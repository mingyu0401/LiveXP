package com.livexp.qq;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * hook QQ 自己的路径解析器拿图片本地文件:
 * com.tencent.qqnt.aio.msg.api.impl.AIOMsgItemApiImpl.getLocalPath(AIOMsgItem) -> String
 * (QAuxiliary PicCopyToClipboard 的 NT 实现同款)。
 * QQ 每次查路径(气泡绑定、打开大图)都会经过这里,拿到的路径交给 LivePhotoDetector 判定;
 * hook 回调里缓存 thisObject,供 bind 时 probeLocalPath 复用(newInstance 出的实例拿不到路径)。
 */
public final class OpenImageSaver {

    private static final String API_IMPL = "com.tencent.qqnt.aio.msg.api.impl.AIOMsgItemApiImpl";

    private static final Set<String> loggedPaths = ConcurrentHashMap.newKeySet();
    private static volatile Method probeMethod;
    private static volatile Object probeInstance;

    private OpenImageSaver() {}

    public static void install(ClassLoader cl) {
        try {
            Class<?> api = XposedHelpers.findClassIfExists(API_IMPL, cl);
            if (api == null) {
                Log.i("open-saver: AIOMsgItemApiImpl not found");
                return;
            }
            java.util.Set<XC_MethodHook.Unhook> hooks =
                    XposedBridge.hookAllMethods(api, "getLocalPath", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (param.thisObject != null) probeInstance = param.thisObject;
                        Object r = param.getResult();
                        if (!(r instanceof String) || ((String) r).isEmpty()) return;
                        String path = (String) r;
                        if (loggedPaths.add(path)) {
                            Log.i("getLocalPath -> " + path);
                        }
                        LivePhotoDetector.detectAsync(path, msgRecordOf(param.args), null);
                    } catch (Throwable t) {
                        Log.i("open-saver hook error: " + t);
                    }
                }
            });
            for (Method m : api.getDeclaredMethods()) {
                if ("getLocalPath".equals(m.getName()) && m.getParameterTypes().length == 1) {
                    m.setAccessible(true);
                    probeMethod = m;
                }
            }
            Log.i("open-saver hooked: getLocalPath (" + hooks.size() + " methods), probe=" + (probeMethod != null));
        } catch (Throwable t) {
            Log.i("open-saver install failed: " + t);
        }
    }

    /** bind 时主动查一次本地路径,不依赖 QQ 是否自己调用。每个路径只记一次日志。返回路径,查不到返回 null。 */
    public static String probeLocalPath(Object msgItem) {
        try {
            Method m = probeMethod;
            if (m == null || msgItem == null) return null;
            Object owner = probeInstance;
            if (owner == null) owner = m.getDeclaringClass().newInstance();
            Object r = m.invoke(owner, msgItem);
            if (!(r instanceof String) || ((String) r).isEmpty()) return null;
            String path = (String) r;
            if (loggedPaths.add(path)) {
                Log.i("probe getLocalPath -> " + path);
            }
            return path;
        } catch (Throwable t) {
            Log.i("probe error: " + t);
            return null;
        }
    }

    private static Object msgRecordOf(Object[] args) {
        if (args == null) return null;
        for (Object a : args) {
            if (a == null) continue;
            try {
                Object rec = XposedHelpers.callMethod(a, "getMsgRecord");
                if (rec != null) return rec;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }
}
