package com.livexp.qq;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

/**
 * 模块关于页:炫彩图标 + 简介 + 复制/支持作者卡片。QQ 进程弹全屏 Dialog,独立 App 直接 setContentView。
 * 长按顶部图案触发 3 秒渐快震动(WebView 里走 livexp://haptic 自定义协议)。
 */
public final class ModuleAboutPage {

    /** 页面动作回调。 */
    public interface Actions {
        void openLogs();

        void openSupport();
    }

    /** JS 直接调用原生震动,避免 location.href 自定义协议在非用户手势回调里被 WebView 拦掉。 */
    public static final class HapticBridge {
        private final Context ctx;

        HapticBridge(Context ctx) {
            this.ctx = ctx;
        }

        @android.webkit.JavascriptInterface
        public void buzz() {
            Haptics.play(ctx);
        }

        @android.webkit.JavascriptInterface
        public void stop() {
            Haptics.stop();
        }
    }

    /** 模块开关读写:设置页里的开关直接落到 QQ 进程的 SharedPreferences。 */
    public static final class SettingsBridge {
        private final Context ctx;

        SettingsBridge(Context ctx) {
            this.ctx = ctx;
        }

        @android.webkit.JavascriptInterface
        public boolean getSendParse() {
            return LiveSettings.sendParseEnabled(ctx);
        }

        @android.webkit.JavascriptInterface
        public void setSendParse(boolean on) {
            LiveSettings.setSendParseEnabled(ctx, on);
            Haptics.tick(ctx);
        }
    }

    /**
     * 滑块保存桥:QQ 进程内(页面宿主是 QQ 的 Context)直接落盘;
     * 独立 App 直写失败时(Android 11+ 常规限制 Android/data 他包目录)广播托
     * QQ 进程里的模块代写,保存结果同步返回给页面显示。
     */
    public static final class ThresholdBridge {
        private static final String QQ_PKG = "com.tencent.mobileqq";
        private final Context ctx;

        ThresholdBridge(Context ctx) {
            this.ctx = ctx;
        }

        @android.webkit.JavascriptInterface
        public String get() {
            return String.valueOf(ThresholdConfig.get());
        }

        /** @return ok=已落盘 / sent=已转发 QQ 进程(下次心跳确认) / fail=保存失败 */
        @android.webkit.JavascriptInterface
        public String set(String kb) {
            final long bytes;
            try {
                bytes = Long.parseLong(kb.trim()) * 1024L;
            } catch (NumberFormatException e) {
                return "fail";
            }
            if (ThresholdConfig.set(bytes)) return "ok";
            if (ctx != null && !QQ_PKG.equals(ctx.getPackageName())) {
                try {
                    Intent i = new Intent(StatusBridge.ACTION_SET_THRESHOLD);
                    i.setPackage(QQ_PKG);
                    i.putExtra("bytes", ThresholdConfig.clamp(bytes));
                    ctx.sendBroadcast(i);
                    Log.i("threshold set forwarded to qq process: " + bytes + " bytes");
                    return "sent";
                } catch (Throwable t) {
                    Log.i("threshold set forward failed: " + t);
                }
            }
            return "fail";
        }
    }

    private ModuleAboutPage() {}

    /** 跟随系统夜间模式;取不到明确浅色状态时默认深色。 */
    public static boolean isDark(Context ctx) {
        int mask = ctx.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mask != Configuration.UI_MODE_NIGHT_NO;
    }

    public static WebView createWebView(Context ctx, Actions actions) {
        return createWebView(ctx, actions, null);
    }

    /** onLoaded:页面加载完成回调(独立 App 用它把激活状态写进页面,避免整页重载闪烁)。 */
    public static WebView createWebView(final Context ctx, final Actions actions, final Runnable onLoaded) {
        WebView web = new WebView(ctx);
        web.setBackgroundColor(isDark(ctx) ? Color.rgb(0x0D, 0x11, 0x17) : Color.rgb(0xF6, 0xF8, 0xFA));
        web.setOverScrollMode(WebView.OVER_SCROLL_NEVER);
        // 长按彩蛋靠页面 JS 触发;WebView 默认关闭 JavaScript,之前一直没开导致看不到震动
        web.getSettings().setJavaScriptEnabled(true);
        web.addJavascriptInterface(new HapticBridge(ctx), "LiveXPHaptic");
        web.addJavascriptInterface(new SettingsBridge(ctx), "LiveXPSettings");
        web.addJavascriptInterface(new ThresholdBridge(ctx), "LiveXPThreshold");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url.startsWith("livexp://hapticstop")) {
                    Haptics.stop();
                    return true;
                }
                if (url.startsWith("livexp://haptic")) {
                    Haptics.play(ctx);
                    return true;
                }
                if (url.startsWith("livexp://join")) {
                    joinGroup(ctx);
                    return true;
                }
                if (url.startsWith("livexp://copy")) {
                    Uri u = Uri.parse(url);
                    String text = u.getQueryParameter("t");
                    String mailto = u.getQueryParameter("mailto");
                    if (text != null && !text.isEmpty()) {
                        android.content.ClipboardManager cm =
                                (android.content.ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                        if (cm != null) {
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("LiveXP", text));
                            Toast.makeText(ctx, "已复制 " + text, Toast.LENGTH_SHORT).show();
                        }
                        if ("1".equals(u.getQueryParameter("open"))) {
                            try {
                                ctx.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(text)));
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                    if (mailto != null && !mailto.isEmpty()) {
                        try {
                            ctx.startActivity(new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + mailto)));
                        } catch (Throwable ignored) {
                        }
                    }
                    return true;
                }
                if (url.startsWith("livexp://logs")) {
                    if (actions != null) actions.openLogs();
                    return true;
                }
                if (url.startsWith("livexp://support")) {
                    if (actions != null) actions.openSupport();
                    return true;
                }
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (onLoaded != null) onLoaded.run();
            }
        });
        return web;
    }

    /** 交流群 923820659:优先 QQ 的群资料卡 scheme,失败再退回群会话 scheme,最后复制群号。 */
    private static void joinGroup(Context ctx) {
        final String uin = "923820659";
        final String[] schemes = {
                "mqqapi://card/show_pslcard?src_type=internal&version=1&uin=" + uin
                        + "&card_type=group&source=qrcode",
                "mqq://im/chat?chat_type=group&uin=" + uin + "&version=1&src_type=web"
        };
        for (String s : schemes) {
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(s));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                i.setPackage("com.tencent.mobileqq");
                ctx.startActivity(i);
                Log.i("join group: " + s);
                return;
            } catch (Throwable t) {
                Log.i("join group failed " + s + " -> " + t);
            }
        }
        try {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(android.content.ClipData.newPlainText("LiveXP", uin));
            }
            Toast.makeText(ctx, "未能直接打开 QQ,群号 " + uin + " 已复制", Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {
        }
    }

    /** 模块设置页(QQ 进程内):始终炫彩;ver 行带实际 QQ 版本与 LSPosed 版本。 */
    public static String moduleHtml(Context ctx, boolean dark) {
        String qq = null;
        String lsp = "LSPosed";
        try {
            LspInfo.ensure(ctx);
            qq = LspInfo.qqVersion;
            lsp = LspInfo.lsposedLabel();
        } catch (Throwable ignored) {
        }
        StringBuilder ver = new StringBuilder("v").append(BuildConfig.VERSION_NAME);
        ver.append(" · for QQ").append(qq == null ? "" : " " + qq).append(" (NT)");
        ver.append(" · ").append(lsp);
        return buildHtml(dark, false, true, LiveSettings.sendParseEnabled(ctx), ver.toString());
    }

    /** 独立 App:显示激活状态与 LSPosed 版本;未激活时停掉全部炫彩。 */
    public static String appHtml(Context ctx, boolean dark, boolean active, String verText) {
        return buildHtml(dark, true, active, LiveSettings.sendParseEnabled(ctx), verText);
    }

    public static Dialog showDialog(Context ctx, Actions actions) {
        Dialog dialog = new Dialog(ctx);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        WebView web = createWebView(ctx, actions);
        web.loadDataWithBaseURL(null, moduleHtml(ctx, isDark(ctx)), "text/html", "utf-8", null);
        dialog.setContentView(web, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
        dialog.show();
        return dialog;
    }

    public static Dialog showSupportDialog(Context ctx) {
        Dialog dialog = new Dialog(ctx);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        boolean dark = isDark(ctx);
        WebView web = createWebView(ctx, null, null);
        web.loadDataWithBaseURL(null, supportHtml(dark), "text/html", "utf-8", null);
        dialog.setContentView(web, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(dark ? 0xB3000000 : 0xF2FFFFFF));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
        dialog.show();
        return dialog;
    }

    private static String buildHtml(boolean dark, boolean appMode, boolean active,
                                    boolean parseOn, String verText) {
        String bg = dark
                ? "radial-gradient(1200px 600px at 50% -10%,#16202e 0%,#0d1117 60%),#0d1117"
                : "radial-gradient(1200px 600px at 50% -10%,#eaeef2 0%,#f6f8fa 60%),#f6f8fa";
        String main = dark ? "#e6edf3" : "#1f2328";
        String sub = dark ? "#7d8590" : "#6e7781";
        String desc = dark ? "#9ea7b3" : "#57606a";
        String cardBg = dark ? "rgba(255,255,255,.045)" : "rgba(0,0,0,.04)";
        String cardBorder = dark ? "rgba(255,255,255,.08)" : "rgba(0,0,0,.1)";
        String cardActive = dark ? "rgba(255,255,255,.12)" : "rgba(0,0,0,.1)";
        String ringOpacity = dark ? ".55" : ".3";
        String title = appMode ? (active ? "LiveXP 已激活" : "LiveXP 未激活") : "LiveXP By 明雨";
        String descHtml = "LSPosed 模块:在“大而花”的聊天软件聊天界面检测实况图片,"
                + "给对应图片气泡加「实况」点环角标;点开大图自动实现实况图片查看效果。"
                + (appMode ? "" : "<br>关于明雨:一个思想天马行空的大学生。")
                + "<br>点按卡片复制对应链接 / 邮箱。";

        StringBuilder sb = new StringBuilder(8192);
        sb.append("<!DOCTYPE html><html><head><meta charset='utf-8'>")
                .append("<meta name='viewport' content='width=device-width,initial-scale=1'>")
                .append("<style>")
                .append("*{box-sizing:border-box;margin:0;padding:0}")
                .append("body{background:").append(bg).append(";color:").append(main)
                .append(";font-family:sans-serif;display:flex;flex-direction:column;align-items:center;")
                .append("min-height:100vh;padding:44px 22px 30px}")
                .append(".hero{position:relative;width:150px;height:150px;display:flex;align-items:center;")
                .append("justify-content:center;margin-bottom:6px;user-select:none;-webkit-user-select:none;")
                .append("-webkit-touch-callout:none;-webkit-tap-highlight-color:transparent}")
                .append(".ring{position:absolute;inset:0;border-radius:50%;")
                .append("background:conic-gradient(from 0deg,#a78bfa,#38bdf8,#34d399,#fbbf24,#f472b6,#a78bfa);")
                .append("animation:spin 4s linear infinite;filter:blur(14px);opacity:").append(ringOpacity).append("}")
                .append(".mark{position:relative;z-index:2}")
                .append("#markOff{display:none}")
                .append("@keyframes spin{to{transform:rotate(360deg)}}")
                .append("@keyframes glow{from{filter:drop-shadow(0 0 6px rgba(167,139,250,.35))}")
                .append("to{filter:drop-shadow(0 0 18px rgba(56,189,248,.7))}}")
                .append("h1{font-size:30px;letter-spacing:1px;margin-top:14px;text-align:center}")
                .append(".rainbow{background:linear-gradient(90deg,#a78bfa,#38bdf8,#34d399,#fbbf24,#f472b6);")
                .append("-webkit-background-clip:text;background-clip:text;color:transparent;")
                .append("background-size:200% 100%;animation:shift 5s linear infinite}")
                .append("@keyframes shift{to{background-position:200% 0}}")
                .append(".ver{color:").append(sub).append(";font-size:13px;margin-top:8px;text-align:center;")
                .append("line-height:1.7;max-width:420px}")
                .append(".desc{color:").append(desc).append(";font-size:14px;line-height:1.8;margin:20px 0 26px;")
                .append("text-align:center;max-width:420px}")
                .append(".set{width:100%;max-width:420px;display:flex;align-items:center;gap:14px;")
                .append("background:").append(cardBg).append(";border:1px solid ").append(cardBorder)
                .append(";border-radius:14px;padding:14px 16px;margin:0 0 12px;transition:background .2s}")
                .append(".set:active{background:").append(cardActive).append("}")
                .append(".setIco{width:38px;height:38px;border-radius:10px;flex:none;display:flex;")
                .append("align-items:center;justify-content:center;")
                .append("background:linear-gradient(135deg,#2ea043,#38bdf8)}")
                .append(".setMain{flex:1;min-width:0}")
                .append(".setT{font-size:15px;font-weight:bold}")
                .append(".setD{font-size:11.5px;color:").append(sub).append(";line-height:1.6;margin-top:3px}")
                .append(".sw{position:relative;flex:none;width:52px;height:32px;border-radius:16px;")
                .append("background:").append(dark ? "#3a4150" : "#cfd8e3").append(";")
                .append("box-shadow:inset 0 0 0 1px rgba(255,255,255,.06);")
                .append("transition:background .3s cubic-bezier(.4,0,.2,1),transform .18s ease;")
                .append("-webkit-tap-highlight-color:transparent}")
                .append(".sw:active{transform:scale(.97)}")
                .append(".sw .fill{position:absolute;left:0;right:0;top:0;bottom:0;border-radius:16px;")
                .append("background:linear-gradient(135deg,#2ea043,#38bdf8);opacity:0;")
                .append("transition:opacity .3s cubic-bezier(.4,0,.2,1)}")
                .append(".sw.on .fill{opacity:1}")
                .append(".sw .knob{position:absolute;left:3px;top:3px;width:26px;height:26px;border-radius:13px;")
                .append("background:#fff;box-shadow:0 1px 4px rgba(0,0,0,.35);")
                .append("transition:transform .34s cubic-bezier(.34,1.4,.64,1),width .18s ease}")
                .append(".sw.on .knob{transform:translateX(20px)}")
                .append(".sw.press .knob{width:30px}")
                .append(".sw.on.press .knob{transform:translateX(16px)}")
                .append(".cards{width:100%;max-width:420px;display:flex;flex-direction:column;gap:12px}")
                .append("a.card{display:flex;align-items:center;gap:14px;text-decoration:none;color:").append(main)
                .append(";background:").append(cardBg).append(";border:1px solid ").append(cardBorder)
                .append(";border-radius:14px;padding:15px 16px;transition:background .2s}")
                .append("a.card:active{background:").append(cardActive).append("}")
                .append(".ico{width:38px;height:38px;border-radius:10px;display:flex;align-items:center;")
                .append("justify-content:center;font-size:19px;flex:none}")
                .append(".c1{background:linear-gradient(135deg,#6e40c9,#388bfd)}")
                .append(".c2{background:linear-gradient(135deg,#1f883d,#38bdf8)}")
                .append(".c3{background:linear-gradient(135deg,#d29922,#f0883e)}")
                .append(".c4{background:linear-gradient(135deg,#f0883e,#f85149)}")
                .append(".tt{font-size:15px;font-weight:bold;display:flex;align-items:baseline;flex-wrap:wrap}")
                .append(".starText{font-size:11.5px;font-weight:normal;margin-left:4px}")
                .append(".sub{font-size:12px;color:").append(sub).append(";margin-top:3px}")
                .append(".arr{margin-left:auto;color:").append(sub).append(";font-size:18px}")
                .append(".sec{width:100%;max-width:420px;margin-top:34px;padding-top:20px;border-top:1px solid ")
                .append(cardBorder).append("}")
                .append(".secH{font-size:14px;font-weight:bold;display:flex;align-items:center;gap:8px}")
                .append(".secH .tag{font-size:10px;font-weight:normal;color:").append(sub)
                .append(";border:1px solid ").append(cardBorder).append(";border-radius:6px;padding:1px 6px}")
                .append(".secTip{font-size:11.5px;color:").append(sub)
                .append(";line-height:1.75;margin:8px 0 10px;overflow-wrap:break-word}")
                .append(".thRow{display:flex;align-items:center;gap:12px;margin-top:16px}")
                .append(".thRange{flex:1;-webkit-appearance:none;appearance:none;height:4px;border-radius:2px;")
                .append("background:").append(cardBorder).append(";outline:none;padding:0}")
                .append(".thRange::-webkit-slider-thumb{-webkit-appearance:none;appearance:none;width:22px;height:22px;")
                .append("border-radius:50%;background:linear-gradient(135deg,#a78bfa,#38bdf8);border:none}")
                .append(".thVal{min-width:54px;text-align:right;font-size:14px;font-weight:bold;flex:none}")
                .append(".thState{font-size:12px;color:").append(sub).append(";margin-top:8px;min-height:16px}")
                .append(".thState.ok{color:#34d399}")
                .append(".thState.sent{color:#fbbf24}")
                .append(".thState.err{color:#f85149}")
                .append(".thReset{font-size:11.5px;color:#58a6ff;text-decoration:none;margin-left:8px}")
                .append("a.cl,div.cl{display:flex;align-items:flex-start;gap:8px;text-decoration:none;color:")
                .append(main).append(";padding:11px 4px;border-bottom:1px solid ").append(cardBorder).append("}")
                .append("a.cl:active{background:").append(cardActive).append("}")
                .append(".cl .clMain{flex:1;min-width:0}")
                .append(".cl .nm{font-size:14px;font-weight:bold;display:flex;align-items:center;gap:7px;flex-wrap:wrap}")
                .append(".cl .ds{font-size:11.5px;color:").append(sub).append(";line-height:1.65;margin-top:3px}")
                .append(".cl .go{margin-left:auto;color:").append(sub)
                .append(";font-size:12px;padding-top:1px;white-space:nowrap;flex:none}")
                .append(".star{font-size:9.5px;font-weight:bold;color:#0d1117;padding:1px 5px;border-radius:5px;")
                .append("background:linear-gradient(135deg,#a78bfa,#38bdf8)}")
                .append("a.cl.fav,div.cl.fav{background:linear-gradient(100deg,rgba(167,139,250,.16),rgba(56,189,248,.05));")
                .append("border:1px solid rgba(167,139,250,.38);border-radius:12px;padding:12px;margin-bottom:4px}")
                .append(".secFoot{font-size:11px;color:").append(sub).append(";line-height:1.7;margin-top:14px;opacity:.85}")
                .append(".foot{margin-top:auto;padding-top:30px}")
                .append("a.log{color:#58a6ff;font-size:13px;text-decoration:none}")
                .append("body.off .ring{background:conic-gradient(from 0deg,#5b6673,#9aa4b2,#5b6673);")
                .append("animation:none;opacity:.12}")
                .append("body.off h1,body.off .rainbow{background:none;color:").append(sub)
                .append(";-webkit-text-fill-color:").append(sub).append(";animation:none}")
                .append("body.off #markOn{display:none}")
                .append("body.off #markOff{display:block}")
                .append("</style></head><body class='").append(appMode ? "app " : "")
                .append(active ? "on" : "off").append("'>")
                .append("<div class='hero' id='hero'><div class='ring'></div>")
                .append("<div class='mark' id='markOn'>").append(markSvg(sub, true)).append("</div>")
                .append("<div class='mark' id='markOff'>").append(markSvg(sub, false)).append("</div></div>")
                .append("<h1 class='rainbow' id='stateTitle'>").append(title).append("</h1>")
                .append("<div class='ver' id='verLine'>").append(verText).append("</div>")
                .append("<div class='desc'>").append(descHtml).append("</div>")
                .append(appMode ? "" : settingsCard(parseOn))
                .append("<div class='cards'>").append(cards(appMode)).append("</div>")
                .append(thresholdSection())
                .append(credits(sub, cardBorder, appMode))
                .append("<div class='foot'><a class='log' href='livexp://logs'>查看运行日志</a></div>")
                .append(script())
                .append("</body></html>");
        return sb.toString();
    }

    /** 设置卡片:标题 + 说明 + 自绘开关(轨道渐变淡入、滑块弹性位移、按压拉伸)。 */
    private static String settingsCard(boolean parseOn) {
        return "<div class='set' id='setParseRow'>"
                + "<div class='setIco'><svg width='20' height='20' viewBox='0 0 24 24'>"
                + "<circle cx='12' cy='12' r='8.4' fill='none' stroke='#fff' stroke-width='2.4'/>"
                + "<circle cx='12' cy='12' r='3.4' fill='#fff'/></svg></div>"
                + "<div class='setMain'><div class='setT'>实况转MP4</div>"
                + "<div class='setD'>发送图片时显示「实况转MP4」按钮，把实况图合成视频发出</div></div>"
                + "<div class='sw" + (parseOn ? " on" : "") + "' id='swParse' role='switch' tabindex='0'"
                + " aria-checked='" + (parseOn ? "true" : "false") + "'>"
                + "<div class='fill'></div><div class='knob'></div></div>"
                + "</div>";
    }

    /** 卡片区:独立 App 只留项目源码(支持作者/联系我在 QQ 里更合适)。 */
    private static String cards(boolean appMode) {
        String src = "https://github.com/mingyu0401/LiveXP";
        StringBuilder sb = new StringBuilder();
        sb.append(card("c1", "&#128187;", "项目源码",
                "<span class='rainbow starText'>欢迎 Star</span>",
                "github.com/mingyu0401/LiveXP", copyHref(src) + "&open=1"));
        if (!appMode) {
            String mail = "mingyu0401a@163.com";
            sb.append(card("c3", "&#9993;&#65039;", "联系我", null, mail,
                    copyHref(mail) + "&mailto=" + mail));
            sb.append(card("c4", "&#9749;", "支持作者", null, "请作者喝杯咖啡", "livexp://support"));
        }
        // 交流群:模块页排在「支持作者」下,独立 App 排在「项目源码」下
        sb.append(card("c2", "&#128172;", "加入交流群", null, "923820659", "livexp://join"));
        return sb.toString();
    }

    /** 「实况检测大小阈值」滑块区块:100KB~10MB、步进 100KB,松手保存;QQ 进程页与独立 App 页共用。 */
    private static String thresholdSection() {
        long kb = ThresholdConfig.get() / 1024;
        return "<div class='sec'>"
                + "<div class='secH'>实况检测大小阈值<span class='tag'>可调</span>"
                + "<a class='thReset' href='#' id='thReset'>恢复默认 1MB</a></div>"
                + "<div class='secTip'>文件大小 ≥ 阈值才会进入实况扫描与原图反查,默认 1MB(与旧版一致)。"
                + "松手即保存、即时生效,无需重启 QQ。<br>"
                + "<b>注意:</b>调小阈值会让更多文件进入扫描,提高 CPU 占用与耗电(大量小文件扫描可能明显发热),"
                + "没有需要请保持默认。</div>"
                + "<div class='thRow'>"
                + "<input type='range' class='thRange' id='thSlider' min='100' max='10240' step='100' value='" + kb + "'>"
                + "<div class='thVal' id='thVal'>" + fmtKb(kb) + "</div>"
                + "</div>"
                + "<div class='thState' id='thState'></div>"
                + "</div>";
    }

    /** 100KB~10MB 的人类可读值;KB 整数显示,MB 保留一位小数(如 1.0MB / 2.5MB)。 */
    static String fmtKb(long kb) {
        if (kb < 1024) return kb + "KB";
        return String.format(java.util.Locale.US, "%.1fMB", kb / 1024.0);
    }

    /** 长按顶部图案:430ms 后触发 3 秒渐快震动(Android 侧执行),松手/滑走即停。 */
    private static String script() {
        return "<script>"
                + "function livexpState(active,ver){"
                + "document.body.classList.toggle('off',!active);"
                + "var t=document.getElementById('stateTitle');"
                + "if(t)t.textContent=active?'LiveXP 已激活':'LiveXP 未激活';"
                + "var v=document.getElementById('verLine');"
                + "if(v&&ver)v.textContent=ver;}"
                + "var thS=document.getElementById('thSlider'),"
                + "thV=document.getElementById('thVal'),"
                + "thT=document.getElementById('thState'),"
                + "thPending=-1,thTouch=false;"
                + "function fmtKb(kb){return kb<1024?kb+'KB':(kb/1024).toFixed(1)+'MB';}"
                + "function thSave(){"
                + "var kb=+thS.value;"
                + "thT.textContent='保存中…';thT.className='thState';"
                + "try{"
                + "var r=window.LiveXPThreshold?String(LiveXPThreshold.set(String(kb))):'fail';"
                + "if(r==='ok'){thPending=-1;thT.textContent='已保存 '+fmtKb(kb)+' ✓';thT.className='thState ok';}"
                + "else if(r==='sent'){thPending=kb;thT.textContent='已转发 QQ 进程保存,QQ 在线时生效';thT.className='thState sent';}"
                + "else{thT.textContent='保存失败,值未写入';thT.className='thState err';}"
                + "}catch(e){thT.textContent='保存失败:'+e;thT.className='thState err';}}"
                + "if(thS){"
                + "thS.addEventListener('input',function(){thV.textContent=fmtKb(+thS.value);});"
                + "thS.addEventListener('change',thSave);"
                + "thS.addEventListener('touchstart',function(){thTouch=true;},{passive:true});"
                + "thS.addEventListener('touchend',function(){thTouch=false;},{passive:true});"
                + "var thR=document.getElementById('thReset');"
                + "if(thR)thR.addEventListener('click',function(e){e.preventDefault();"
                + "thS.value=1024;thV.textContent=fmtKb(1024);thSave();});"
                + "}"
                // 独立 App 收到 QQ 进程心跳里的权威阈值时同步滑块(拖动中/待确认时不打扰)
                + "function livexpThreshold(bytes){"
                + "if(!thS||thTouch)return;"
                + "var kb=Math.round(bytes/1024);"
                + "if(thPending>=0&&kb===thPending){thPending=-1;"
                + "thT.textContent='已保存 '+fmtKb(kb)+' ✓';thT.className='thState ok';return;}"
                + "if(thPending<0){thS.value=kb;thV.textContent=fmtKb(kb);}}"
                + "(function(){"
                + "var hero=document.getElementById('hero');if(!hero)return;"
                + "var timer=null,fired=false,sx=0,sy=0,lastBuzz=0;"
                + "function buzz(){var n=Date.now();if(n-lastBuzz<1500)return;lastBuzz=n;fired=true;"
                + "try{if(window.LiveXPHaptic){LiveXPHaptic.buzz();}else{location.href='livexp://haptic';}}catch(e){}}"
                + "function stopBuzz(){if(fired){fired=false;try{if(window.LiveXPHaptic){LiveXPHaptic.stop();}else{location.href='livexp://hapticstop';}}catch(e){}}}"
                + "hero.addEventListener('touchstart',function(e){if(timer||fired)return;"
                + "var t=e.touches&&e.touches[0];if(t){sx=t.clientX;sy=t.clientY;}"
                + "timer=setTimeout(function(){timer=null;buzz();},430);},{passive:true});"
                + "hero.addEventListener('touchend',function(){if(timer){clearTimeout(timer);timer=null;}stopBuzz();});"
                + "hero.addEventListener('touchcancel',function(){if(timer){clearTimeout(timer);timer=null;}stopBuzz();});"
                + "hero.addEventListener('touchmove',function(e){if(!timer)return;"
                + "var t=e.touches&&e.touches[0];"
                + "if(t&&(Math.abs(t.clientX-sx)+Math.abs(t.clientY-sy))>14){clearTimeout(timer);timer=null;}});"
                + "hero.addEventListener('contextmenu',function(e){e.preventDefault();});"
                + "document.addEventListener('visibilitychange',function(){if(document.hidden)stopBuzz();});"
                + "})();"
                + "(function(){"
                + "var row=document.getElementById('setParseRow');if(!row)return;"
                + "var sw=document.getElementById('swParse');"
                + "function paint(on){if(!sw)return;sw.classList.toggle('on',on);"
                + "sw.setAttribute('aria-checked',on?'true':'false');}"
                + "row.addEventListener('click',function(){"
                + "var on=!(sw&&sw.classList.contains('on'));paint(on);"
                + "try{if(window.LiveXPSettings)LiveXPSettings.setSendParse(on);}catch(e){}});"
                + "if(sw){sw.addEventListener('touchstart',function(){sw.classList.add('press');},{passive:true});"
                + "sw.addEventListener('touchend',function(){sw.classList.remove('press');});"
                + "sw.addEventListener('touchcancel',function(){sw.classList.remove('press');});}"
                + "})();"
                + "</script>";
    }

    /** 「实况」点环图案;colorful=false 时用纯色(未激活状态)。 */
    private static String markSvg(String plainColor, boolean colorful) {
        StringBuilder dots = new StringBuilder();
        String fill = colorful ? "url(#g)" : plainColor;
        for (int i = 0; i < 20; i++) {
            double a = Math.PI * 2 * i / 20;
            double x = 84 * Math.cos(a);
            double y = 84 * Math.sin(a);
            dots.append("<circle cx='").append(String.format("%.1f", x))
                    .append("' cy='").append(String.format("%.1f", y))
                    .append("' r='9' fill='").append(fill).append("'/>");
        }
        StringBuilder sb = new StringBuilder();
        sb.append("<svg width='70' height='70' viewBox='-100 -100 200 200'");
        if (colorful) {
            sb.append(" style='animation:glow 3s ease-in-out infinite alternate'");
        }
        sb.append(">");
        if (colorful) {
            sb.append("<defs><linearGradient id='g' x1='-100' y1='-100' x2='100' y2='100' gradientUnits='userSpaceOnUse'>")
                    .append("<stop offset='0' stop-color='#a78bfa'><animate attributeName='stop-color' ")
                    .append("values='#a78bfa;#38bdf8;#34d399;#fbbf24;#f472b6;#a78bfa' dur='8s' repeatCount='indefinite'/></stop>")
                    .append("<stop offset='0.5' stop-color='#34d399'><animate attributeName='stop-color' ")
                    .append("values='#34d399;#fbbf24;#f472b6;#a78bfa;#38bdf8;#34d399' dur='8s' repeatCount='indefinite'/></stop>")
                    .append("<stop offset='1' stop-color='#f472b6'><animate attributeName='stop-color' ")
                    .append("values='#f472b6;#a78bfa;#38bdf8;#34d399;#fbbf24;#f472b6' dur='8s' repeatCount='indefinite'/></stop>")
                    .append("</linearGradient></defs>");
        }
        sb.append(dots)
                .append("<circle r='57' fill='none' stroke='").append(fill).append("' stroke-width='13'/>")
                .append("<circle r='29' fill='").append(fill).append("'/>")
                .append("</svg>");
        return sb.toString();
    }

    /**
     * 致谢和开源使用说明:首项 QA 模块(提供实现思路),次项 FUNBOX(作者最喜欢的模块,无开源地址),
     * 其余为可能用到的项目,逐个附 GitHub 链接。点按条目复制地址,独立 App 内同时尝试打开。
     */
    private static String credits(String sub, String cardBorder, boolean appMode) {
        StringBuilder sb = new StringBuilder();
        sb.append("<div class='sec'>")
                .append("<div class='secH'>致谢和开源使用说明")
                .append(appMode ? "" : "<span class='tag'>可选</span>")
                .append("</div>")
                .append("<div class='secTip'>LiveXP 是站在别人肩膀上的一次尝试。下面这些人和项目让作者少走了很多弯路,")
                .append("点按条目可复制地址(独立 App 内会同时打开浏览器)。</div>")
                .append(creditRow("QA 模块", "提供了实现思路,LiveXP 的不少做法参考自它。",
                        "https://github.com/cinit/QAuxiliary", false))
                .append(creditRow("FUNBOX", "本人最喜欢的模块,给了我很大的动力。",
                        null, true))
                .append(creditRow("LSPosed", null, "https://github.com/LSPosed/LSPosed", false))
                .append(creditRow("XposedBridge (Xposed API 82)", null, "https://github.com/rovo89/XposedBridge", false))
                .append(creditRow("AndroidX", null, "https://github.com/androidx/androidx", false))
                .append(creditRow("等等", null, null, false))
                .append("<div class='secFoot'>LiveXP 自身以 GPL-3.0 协议开源,完整条文见仓库根目录 LICENSE 文件。<br>")
                .append("以上项目版权归各自作者所有,本模块仅在运行时依赖或借鉴其公开实现。</div>")
                .append("</div>");
        return sb.toString();
    }

    /** 一行致谢:url 为空表示不可点条目;descText 为空时不渲染小字介绍。 */
    private static String creditRow(String name, String descText, String url, boolean favorite) {
        String cls = "cl" + (favorite ? " fav" : "") + (url == null ? " plain" : "");
        String inner = "<div class='clMain'><div class='nm'>" + name
                + (favorite ? "<span class='star'>&#9733; 最爱</span>" : "") + "</div>"
                + (descText == null || descText.isEmpty() ? "" : "<div class='ds'>" + descText + "</div>")
                + "</div>";
        String tail = url == null ? "" : "<div class='go'>&#8599;</div>";
        if (url == null) {
            return "<div class='" + cls + "'>" + inner + tail + "</div>";
        }
        return "<a class='" + cls + "' href='" + copyHref(url) + "&open=1'>" + inner + tail + "</a>";
    }

    /** 赞助码已从 APK 内移出,统一放在仓库的 DONATE.md,这里只给 GitHub 图标和地址。 */
    private static final String DONATE_URL = "https://github.com/mingyu0401/LiveXP/blob/main/DONATE.md";

    private static String githubSvg() {
        return "<svg viewBox='0 0 24 24' xmlns='http://www.w3.org/2000/svg'><path d='M12 .297c-6.63 0-12 "
                + "5.373-12 12 0 5.303 3.438 9.8 8.205 11.385.6.113.82-.258.82-.577 0-.285-.01-1.04-.015-2.04"
                + "-3.338.724-4.042-1.61-4.042-1.61C4.422 18.07 3.633 17.7 3.633 17.7c-1.087-.744.084-.729.084-.729 "
                + "1.205.084 1.838 1.236 1.838 1.236 1.07 1.835 2.809 1.305 3.495.998.108-.776.417-1.305.76-1.605"
                + "-2.665-.3-5.466-1.332-5.466-5.93 0-1.31.465-2.38 1.235-3.22-.135-.303-.54-1.523.105-3.176 0 0 "
                + "1.005-.322 3.3 1.23.96-.267 1.98-.405 3-.405 1.02.006 2.04.138 3 .405 2.28-1.552 3.285-1.23 "
                + "3.285-1.23.645 1.653.24 2.873.12 3.176.765.84 1.23 1.91 1.23 3.22 0 4.61-2.805 5.625-5.475 "
                + "5.92.42.36.81 1.096.81 2.22 0 1.606-.015 2.896-.015 3.286 0 .315.21.69.825.57C20.565 22.092 "
                + "24 17.592 24 12.297c0-6.627-5.373-12-12-12'/></svg>";
    }

    private static String supportHtml(boolean dark) {
        String bg = dark ? "rgba(13,17,23,.92)" : "rgba(246,248,250,.95)";
        String txt = dark ? "#c9d1d9" : "#424a53";
        String btnBg = dark ? "#30363d" : "#eaeef2";
        String btnText = dark ? "#9aa0a8" : "#57606a";
        String btnActive = dark ? "#3d444d" : "#dfe3e8";
        String ghBg = dark ? "rgba(255,255,255,.05)" : "rgba(0,0,0,.04)";
        String ghBorder = dark ? "rgba(255,255,255,.14)" : "rgba(0,0,0,.1)";
        String ghFill = dark ? "#e6edf3" : "#1f2328";
        return "<!DOCTYPE html><html><head><meta charset='utf-8'>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<style>"
                + "*{box-sizing:border-box;margin:0;padding:0}"
                + "body{background:" + bg + ";color:" + txt + ";font-family:sans-serif;"
                + "display:flex;flex-direction:column;align-items:center;min-height:100vh;padding:48px 28px 34px}"
                + "h1{font-size:28px;margin-bottom:22px;"
                + "background:linear-gradient(90deg,#fbbf24,#f0883e,#f85149,#fbbf24);"
                + "-webkit-background-clip:text;background-clip:text;color:transparent;background-size:200% 100%;"
                + "animation:shift 5s linear infinite}"
                + "@keyframes shift{to{background-position:200% 0}}"
                + ".txt{color:" + txt + ";font-size:16px;line-height:1.9;text-align:center;max-width:460px;margin-bottom:26px}"
                + "a.gh{display:flex;align-items:center;justify-content:center;width:146px;height:146px;border-radius:50%;"
                + "background:" + ghBg + ";border:1px solid " + ghBorder + ";margin-bottom:16px;transition:background .2s}"
                + "a.gh:active{background:" + btnActive + "}"
                + "a.gh svg{width:84px;height:84px;fill:" + ghFill + "}"
                + "a.ghurl{display:block;max-width:430px;font-size:12px;line-height:1.7;color:" + btnText + ";"
                + "font-family:monospace;text-align:center;word-break:break-all;text-decoration:none;margin-bottom:30px}"
                + "a.btn{display:block;text-align:center;width:220px;padding:12px 0;border-radius:12px;"
                + "background:" + btnBg + ";color:" + btnText + ";font-size:15px;text-decoration:none;transition:background .2s}"
                + "a.btn:active{background:" + btnActive + "}"
                + "</style></head><body>"
                + "<h1>支持作者</h1>"
                + "<div class='txt'>作者大学牲,精力和 token 来之不易。<br>"
                + "如果您觉得这个模块好用,可以请作者喝一杯咖啡&#9749;<br>"
                + "LiveXP 功能免费,自愿赞赏,感谢支持。<br>赞助码已移到仓库的 DONATE.md,点图标或地址即可打开。</div>"
                + "<a class='gh' href='" + copyHref(DONATE_URL) + "&open=1'>" + githubSvg() + "</a>"
                + "<a class='ghurl' href='" + copyHref(DONATE_URL) + "&open=1'>"
                + "github.com/mingyu0401/LiveXP/blob/main/DONATE.md</a>"
                + "<a class='btn' href='livexp://close'>下次一定</a>"
                + "</body></html>";
    }

    private static String copyHref(String text) {
        try {
            return "livexp://copy?t=" + java.net.URLEncoder.encode(text, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return "livexp://copy";
        }
    }

    /** 卡片:title 右侧可带额外 HTML(欢迎 Star);sub 为空时只显示标题。 */
    private static String card(String colorCls, String icon, String title, String extra,
                               String sub, String href) {
        return "<a class='card' href='" + href + "'>"
                + "<div class='ico " + colorCls + "'>" + icon + "</div>"
                + "<div><div class='tt'>" + title + (extra == null ? "" : extra) + "</div>"
                + (sub == null || sub.isEmpty() ? "" : "<div class='sub'>" + sub + "</div>")
                + "</div>"
                + "<div class='arr'>&#8250;</div></a>";
    }
}
