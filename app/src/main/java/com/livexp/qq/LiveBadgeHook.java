package com.livexp.qq;

import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * hook NT AIO 气泡绑定:QQ >= 9.2.30 走 AIOBubbleMsgItemVB.handleUIState,
 * 更早版本走 bind(int, AIOMsgItemSuper, List, Bundle)。
 * 绑定后取出 MsgRecord 判定实况图,并在气泡根布局上加/撤「实况」角标。
 */
public final class LiveBadgeHook {

    private static final String VB_CLASS = "com.tencent.mobileqq.aio.msglist.holder.AIOBubbleMsgItemVB";
    private static final String MSG_ITEM_CLASS = "com.tencent.mobileqq.aio.msg.AIOMsgItem";
    // AIOMsgItemState 的取值方法返回的是这个父类型(9.3.70 实测 b->a),运行时实例才是 AIOMsgItem
    private static final String MSG_ITEM_BASE_CLASS = "com.tencent.aio.data.msglist.a";

    private static volatile Method msgItemGetter;
    private static volatile Class<?> msgItemGetterOwner;
    private static volatile Method hostViewGetter;
    private static final Set<String> loggedClasses = new HashSet<>();
    private static volatile boolean firstBoundLogged;

    public static volatile String hookedTarget = "未安装";
    public static volatile boolean hostViewResolved;
    public static volatile String msgItemMethod = "未解析";
    public static volatile long boundCount;

    private LiveBadgeHook() {}

    public static void install(ClassLoader cl) throws Throwable {
        Class<?> vb = XposedHelpers.findClass(VB_CLASS, cl);
        Method target = null;
        boolean legacyBind = false;
        for (Method m : vb.getDeclaredMethods()) {
            if ("handleUIState".equals(m.getName())) {
                target = m;
                break;
            }
        }
        if (target == null) {
            for (Method m : vb.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (m.getReturnType() == void.class && p.length == 4
                        && p[0] == int.class && p[2] == java.util.List.class
                        && p[3] == android.os.Bundle.class) {
                    target = m;
                    legacyBind = true;
                    break;
                }
            }
        }
        if (target == null) throw new NoSuchMethodException("AIOBubbleMsgItemVB: no bind method found");
        hookedTarget = target.getName() + "(legacyBind=" + legacyBind + ")";

        hostViewGetter = resolveHostViewGetter(vb);
        hostViewResolved = hostViewGetter != null;
        if (hostViewGetter != null) hostViewGetter.setAccessible(true);

        final boolean legacy = legacyBind;
        XposedBridge.hookMethod(target, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    onBound(param, legacy);
                } catch (Throwable t) {
                    Log.i("onBound error: " + t);
                }
            }
        });
        Log.i("hooked " + target.getName() + " (legacyBind=" + legacy + ")");
    }

    private static volatile long diagCount;

    private static void onBound(XC_MethodHook.MethodHookParam param, boolean legacyBind) throws Throwable {
        boundCount++;
        Object msgItem = legacyBind ? param.args[1] : extractMsgItem(param.args[0]);
        if (diagCount < 12) {
            diagCount++;
            StringBuilder sb = new StringBuilder("[LiveXP] bind#").append(diagCount)
                    .append(" uiState=").append(param.args[0] == null ? "null" : param.args[0].getClass().getSimpleName())
                    .append(" item=").append(msgItem == null ? "null" : msgItem.getClass().getName())
                    .append(" hostGetter=").append(hostViewGetter == null ? "null" : hostViewGetter.getName());
            if (msgItem != null) {
                try {
                    Object rec = XposedHelpers.callMethod(msgItem, "getMsgRecord");
                    sb.append(" rec=").append(rec == null ? "null" : rec.getClass().getName());
                    if (rec != null) {
                        Object elems = XposedHelpers.getObjectField(rec, "elements");
                        sb.append(" elements=").append(elems == null ? "null"
                                : elems.getClass().getSimpleName() + "(" + (elems instanceof java.util.List ? ((java.util.List<?>) elems).size() : -1) + ")");
                    }
                } catch (Throwable t) {
                    sb.append(" recErr=").append(t);
                }
            }
            Log.i(sb.toString());
        }
        if (msgItem == null) return;
        Object msgRecord = XposedHelpers.callMethod(msgItem, "getMsgRecord");
        if (msgRecord == null) return;
        if (hostViewGetter == null) return;
        Object host = hostViewGetter.invoke(param.thisObject);
        if (!(host instanceof ViewGroup)) return;
        ViewGroup root = (ViewGroup) host;
        if (!LivePhotoDetector.hasPic(msgRecord)) {
            BadgeInjector.hide(root);
            return;
        }
        String msgId = LivePhotoDetector.msgIdOf(msgRecord);
        // 一条气泡多张图:逐张判定、逐张贴标(消息级快速路径会把两张图当成一张)
        int picN = LivePhotoDetector.picCount(msgRecord);
        if (picN > 1) {
            BadgeInjector.hide(root);
            Log.i("multi-pic bind: msgId=" + msgId + " pics=" + picN);
            for (int i = 0; i < picN; i++) {
                String p = LivePhotoDetector.elementPathOfAt(msgRecord, i);
                boolean knownPath = LivePhotoDetector.isKnownLivePath(p);
                // sourcePath 可能为空(原图未下载),但 md5 库1 命中同样能证明这张是实况
                boolean knownMd5 = LivePhotoDetector.isKnownLiveMd5At(msgRecord, i);
                Log.i("multi-pic #" + i + " path=" + p + " size="
                        + LivePhotoDetector.picFileSizeAt(msgRecord, i) + " knownPath=" + knownPath
                        + " knownMd5=" + knownMd5);
                if (knownPath || knownMd5) {
                    if (msgId != null) LivePhotoDetector.liveMsgIds.add(msgId);
                    BadgeInjector.show(root, i);
                } else if (p != null) {
                    LivePhotoDetector.detectAsyncAt(p, msgRecord, root, i);
                } else {
                    // sourcePath 为空:按 fileSize+md5 去 chatraw 里找这张图的原图
                    LivePhotoDetector.resolvePathByMetaAsync(msgRecord, root, i);
                }
            }
            return;
        }
        if (msgId != null && LivePhotoDetector.liveMsgIds.contains(msgId)) {
            BadgeInjector.show(root);
            LivePhotoDetector.noteLiveBind(msgRecord, root, msgItem);
            return;
        }
        // md5 库1快速路径:实况 md5 已持久化,无需扫描直接打标
        if (LivePhotoDetector.isKnownLiveMd5(msgRecord)) {
            if (msgId != null) LivePhotoDetector.liveMsgIds.add(msgId);
            BadgeInjector.show(root);
            LivePhotoDetector.noteLiveBind(msgRecord, root, msgItem);
            return;
        }
        BadgeInjector.hide(root);
        String path = LivePhotoDetector.elementPathOf(msgRecord);
        if (path == null) path = OpenImageSaver.probeLocalPath(msgItem);
        LivePhotoDetector.detectAsync(path, msgRecord, root);
        // 无论原图是否已落盘都登记候选:首次打开大图时按缩略图矩形匹配,
        // 后台等原图下载完成后扫描判定,解决"没点开过的图首播失败"
        LivePhotoDetector.noteCandidateBind(msgRecord, root, path);
    }

    /** UI 状态对象 -> AIOMsgItem。方法名被混淆,按返回类型解析:精确 AIOMsgItem 或其基类 msglist.a。 */
    private static Object extractMsgItem(Object uiState) throws Throwable {
        if (uiState == null) return null;
        Class<?> cls = uiState.getClass();
        if (isMsgItemClass(cls.getName())) return uiState;
        Method m = msgItemGetter;
        if (m == null || msgItemGetterOwner != cls) {
            m = null;
            for (Class<?> c = cls; c != null && m == null; c = c.getSuperclass()) {
                for (Method cand : c.getDeclaredMethods()) {
                    if (cand.getParameterTypes().length == 0
                            && isMsgItemClass(cand.getReturnType().getName())) {
                        m = cand;
                        break;
                    }
                }
            }
            if (m == null) {
                logExtractFailureOnce(cls);
                return null;
            }
            m.setAccessible(true);
            msgItemGetter = m;
            msgItemGetterOwner = cls;
            msgItemMethod = m.getDeclaringClass().getSimpleName() + "." + m.getName();
        }
        Object item = m.invoke(uiState);
        // 取值方法返回基类,运行时实例是各消息类型的子类(AIOMsgItem/PicMsgItem/...),
        // 只要能取到 MsgRecord 就继续,不要按具体类名过滤。
        if (item != null && findDeclaredMethod(item.getClass(), "getMsgRecord", 0) == null) {
            if (loggedClasses.add("mismatch:" + item.getClass().getName())) {
                Log.i("[LiveXP] getter returned non-msg item: " + item.getClass().getName());
            }
            return null;
        }
        return item;
    }

    private static boolean isMsgItemClass(String name) {
        return MSG_ITEM_CLASS.equals(name) || MSG_ITEM_BASE_CLASS.equals(name);
    }

    private static Method findDeclaredMethod(Class<?> cls, String name, int paramCount) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (name.equals(m.getName()) && m.getParameterTypes().length == paramCount) return m;
            }
        }
        return null;
    }

    private static void logExtractFailureOnce(Class<?> uiStateClass) {
        if (!loggedClasses.add(uiStateClass.getName())) return;
        StringBuilder sb = new StringBuilder("[").append(HookEntry.TAG)
                .append("] extract AIOMsgItem failed, uiState=").append(uiStateClass.getName())
                .append(", zero-param methods: ");
        for (Method cand : uiStateClass.getDeclaredMethods()) {
            if (cand.getParameterTypes().length == 0) {
                sb.append(cand.getName()).append("->").append(cand.getReturnType().getSimpleName()).append("; ");
            }
        }
        Log.i(sb.toString());
    }

    private static Method resolveHostViewGetter(Class<?> vb) {
        for (Method m : vb.getDeclaredMethods()) {
            if ("getHostView".equals(m.getName()) && m.getParameterTypes().length == 0) return m;
        }
        for (Method m : vb.getDeclaredMethods()) {
            if (Modifier.isPublic(m.getModifiers()) && m.getParameterTypes().length == 0
                    && View.class.isAssignableFrom(m.getReturnType())) {
                return m;
            }
        }
        return null;
    }
}
