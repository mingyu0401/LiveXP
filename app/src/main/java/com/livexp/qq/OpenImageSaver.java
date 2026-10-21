package com.livexp.qq;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 借 QQ 自己的路径解析器拿图片本地文件:
 * com.tencent.qqnt.aio.msg.api.impl.AIOMsgItemApiImpl.getLocalPath(AIOMsgItem) -> String
 * (QAuxiliary PicCopyToClipboard 的 NT 实现同款)。
 * QQ 每次查路径(气泡绑定、打开大图)都会经过这里:
 *  - 路径记日志,学习接收方图片的落盘命名规则;
 *  - 文件大于 512KB(原图量级)时拷贝到 /sdcard/Download/LiveXP/opens/ 留档。
 */
public final class OpenImageSaver {

    private static final String API_IMPL = "com.tencent.qqnt.aio.msg.api.impl.AIOMsgItemApiImpl";
    // 模块专属文件夹在 QQ 数据目录下,opens/ 存点开后捕获的原图
    private static final File DEST_DIR =
            new File("/sdcard/Android/data/com.tencent.mobileqq/LiveXP/opens");
    private static final long MIN_ORIGINAL_SIZE = 512 * 1024;

    private static final Set<String> loggedPaths = ConcurrentHashMap.newKeySet();
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private static volatile Method probeMethod;

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
                        Object r = param.getResult();
                        if (!(r instanceof String) || ((String) r).isEmpty()) return;
                        String path = (String) r;
                        if (loggedPaths.add(path)) {
                            Log.i("getLocalPath -> " + path);
                        }
                        saveIfLarge(path);
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
            Object r = m.invoke(m.getDeclaringClass().newInstance(), msgItem);
            if (!(r instanceof String) || ((String) r).isEmpty()) return null;
            String path = (String) r;
            if (loggedPaths.add(path)) {
                Log.i("probe getLocalPath -> " + path);
            }
            saveIfLarge(path);
            return path;
        } catch (Throwable ignored) {
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

    private static void saveIfLarge(String path) {
        try {
            File f = new File(path);
            if (!f.isFile() || f.length() < MIN_ORIGINAL_SIZE) return;
            EXEC.execute(() -> copy(f));
        } catch (Throwable ignored) {
        }
    }

    private static void copy(File src) {
        try {
            if (!DEST_DIR.isDirectory() && !DEST_DIR.mkdirs()) return;
            File dst = new File(DEST_DIR, src.getParentFile().getName() + "_" + src.getName());
            if (dst.isFile() && dst.length() == src.length()) return;
            try (FileInputStream in = new FileInputStream(src);
                 FileOutputStream out = new FileOutputStream(dst)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            Log.i("open-saver saved: " + dst.getAbsolutePath()
                    + " (" + (src.length() / 1024) + "KB)");
        } catch (Throwable t) {
            Log.i("open-saver copy error: " + t);
        }
    }
}
