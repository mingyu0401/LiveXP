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

/** 模块关于页:炫彩图标 + 简介 + 复制/支持作者卡片。QQ 进程弹全屏 Dialog,独立 App 直接 setContentView。 */
public final class ModuleAboutPage {

    /** 页面动作回调。 */
    public interface Actions {
        void openLogs();

        void openSupport();
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

    /** 独立 App 直接 setContentView 时用这个拿关于页 HTML。 */
    public static String pageHtml(Context ctx) {
        return html(isDark(ctx));
    }

    private static WebView createWebView(Context ctx, Actions actions, Dialog self) {
        WebView web = new WebView(ctx);
        web.setBackgroundColor(isDark(ctx) ? Color.rgb(0x0D, 0x11, 0x17) : Color.rgb(0xF6, 0xF8, 0xFA));
        web.setOverScrollMode(WebView.OVER_SCROLL_NEVER);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
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
                if (url.startsWith("livexp://close")) {
                    if (self != null) self.dismiss();
                    return true;
                }
                return true;
            }
        });
        return web;
    }

    public static Dialog showDialog(Context ctx, Actions actions) {
        Dialog dialog = new Dialog(ctx);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        WebView web = createWebView(ctx, actions);
        web.loadDataWithBaseURL(null, html(isDark(ctx)), "text/html", "utf-8", null);
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
        WebView web = createWebView(ctx, null, dialog);
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

    private static String markSvg() {
        // 图案结构:20 点圆环 + 实心圆环 + 中心圆盘(参考作者提供的白色抠图)
        StringBuilder dots = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            double a = Math.PI * 2 * i / 20;
            double x = 84 * Math.cos(a);
            double y = 84 * Math.sin(a);
            dots.append("<circle cx='").append(String.format("%.1f", x))
                    .append("' cy='").append(String.format("%.1f", y))
                    .append("' r='9' fill='url(#g)'/>");
        }
        return "<svg width='70' height='70' viewBox='-100 -100 200 200' style='animation:glow 3s ease-in-out infinite alternate'>"
                + "<defs><linearGradient id='g' x1='-100' y1='-100' x2='100' y2='100' gradientUnits='userSpaceOnUse'>"
                + "<stop offset='0' stop-color='#a78bfa'><animate attributeName='stop-color' "
                + "values='#a78bfa;#38bdf8;#34d399;#fbbf24;#f472b6;#a78bfa' dur='8s' repeatCount='indefinite'/></stop>"
                + "<stop offset='0.5' stop-color='#34d399'><animate attributeName='stop-color' "
                + "values='#34d399;#fbbf24;#f472b6;#a78bfa;#38bdf8;#34d399' dur='8s' repeatCount='indefinite'/></stop>"
                + "<stop offset='1' stop-color='#f472b6'><animate attributeName='stop-color' "
                + "values='#f472b6;#a78bfa;#38bdf8;#34d399;#fbbf24;#f472b6' dur='8s' repeatCount='indefinite'/></stop>"
                + "</linearGradient></defs>"
                + dots
                + "<circle r='57' fill='none' stroke='url(#g)' stroke-width='13'/>"
                + "<circle r='29' fill='url(#g)'/>"
                + "</svg>";
    }

    private static String html(boolean dark) {
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
        return "<!DOCTYPE html><html><head><meta charset='utf-8'>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<style>"
                + "*{box-sizing:border-box;margin:0;padding:0}"
                + "body{background:" + bg + ";"
                + "color:" + main + ";font-family:sans-serif;display:flex;flex-direction:column;align-items:center;"
                + "min-height:100vh;padding:44px 22px 30px}"
                + ".hero{position:relative;width:150px;height:150px;display:flex;align-items:center;justify-content:center;margin-bottom:6px}"
                + ".ring{position:absolute;inset:0;border-radius:50%;"
                + "background:conic-gradient(from 0deg,#a78bfa,#38bdf8,#34d399,#fbbf24,#f472b6,#a78bfa);"
                + "animation:spin 4s linear infinite;filter:blur(14px);opacity:" + ringOpacity + "}"
                + ".mark{position:relative;z-index:2}"
                + "@keyframes spin{to{transform:rotate(360deg)}}"
                + "@keyframes glow{from{filter:drop-shadow(0 0 6px rgba(167,139,250,.35))}"
                + "to{filter:drop-shadow(0 0 18px rgba(56,189,248,.7))}}"
                + "h1{font-size:30px;letter-spacing:1px;margin-top:14px;"
                + "background:linear-gradient(90deg,#a78bfa,#38bdf8,#34d399,#fbbf24,#f472b6);"
                + "-webkit-background-clip:text;background-clip:text;color:transparent;background-size:200% 100%;"
                + "animation:shift 5s linear infinite}"
                + "@keyframes shift{to{background-position:200% 0}}"
                + ".ver{color:" + sub + ";font-size:13px;margin-top:6px}"
                + ".desc{color:" + desc + ";font-size:14px;line-height:1.8;margin:20px 0 26px;text-align:center;max-width:420px}"
                + ".cards{width:100%;max-width:420px;display:flex;flex-direction:column;gap:12px}"
                + "a.card{display:flex;align-items:center;gap:14px;text-decoration:none;color:" + main + ";"
                + "background:" + cardBg + ";border:1px solid " + cardBorder + ";"
                + "border-radius:14px;padding:15px 16px;transition:background .2s}"
                + "a.card:active{background:" + cardActive + "}"
                + ".ico{width:38px;height:38px;border-radius:10px;display:flex;align-items:center;justify-content:center;"
                + "font-size:19px;flex:none}"
                + ".c1{background:linear-gradient(135deg,#6e40c9,#388bfd)}"
                + ".c3{background:linear-gradient(135deg,#d29922,#f0883e)}"
                + ".c4{background:linear-gradient(135deg,#f0883e,#f85149)}"
                + ".tt{font-size:15px;font-weight:bold}.sub{font-size:12px;color:" + sub + ";margin-top:3px}"
                + ".arr{margin-left:auto;color:" + sub + ";font-size:18px}"
                + ".sec{width:100%;max-width:420px;margin-top:34px;padding-top:20px;border-top:1px solid " + cardBorder + "}"
                + ".secH{font-size:14px;font-weight:bold;display:flex;align-items:center;gap:8px}"
                + ".secH .tag{font-size:10px;font-weight:normal;color:" + sub + ";border:1px solid " + cardBorder + ";border-radius:6px;padding:1px 6px}"
                + ".secTip{font-size:11.5px;color:" + sub + ";line-height:1.75;margin:8px 0 10px;overflow-wrap:break-word}"
                + "a.cl,div.cl{display:flex;align-items:flex-start;gap:8px;text-decoration:none;color:" + main + ";"
                + "padding:11px 4px;border-bottom:1px solid " + cardBorder + "}"
                + "a.cl:active{background:" + cardActive + "}"
                + ".cl .clMain{flex:1;min-width:0}"
                + ".cl .nm{font-size:14px;font-weight:bold;display:flex;align-items:center;gap:7px;flex-wrap:wrap}"
                + ".cl .ds{font-size:11.5px;color:" + sub + ";line-height:1.65;margin-top:3px}"
                + ".cl .go{margin-left:auto;color:" + sub + ";font-size:12px;padding-top:1px;white-space:nowrap;flex:none}"
                + ".star{font-size:9.5px;font-weight:bold;color:#0d1117;padding:1px 5px;border-radius:5px;"
                + "background:linear-gradient(135deg,#a78bfa,#38bdf8)}"
                + "a.cl.fav,div.cl.fav{background:linear-gradient(100deg,rgba(167,139,250,.16),rgba(56,189,248,.05));"
                + "border:1px solid rgba(167,139,250,.38);border-radius:12px;padding:12px;margin-bottom:4px}"
                + ".secFoot{font-size:11px;color:" + sub + ";line-height:1.7;margin-top:14px;opacity:.85}"
                + ".foot{margin-top:auto;padding-top:30px}"
                + "a.log{color:#58a6ff;font-size:13px;text-decoration:none}"
                + "</style></head><body>"
                + "<div class='hero'><div class='ring'></div>"
                + "<div class='mark'>" + markSvg() + "</div></div>"
                + "<h1>LiveXP By 明雨</h1>"
                + "<div class='ver'>v" + BuildConfig.VERSION_NAME + " · for QQ 9.3.70 (NT)</div>"
                + "<div class='desc'>QQ 实况图片检测模块。检测聊天中的实况照片(动态照片),"
                + "在气泡右上角标注「实况」;支持普通图片与群文件大图,"
                + "并使用本地双 md5 库持久化判定结果,重启后秒判。<br>关于明雨:一个思想天马行空的大学生。"
                + "<br>点按卡片复制对应链接 / 邮箱。</div>"
                + "<div class='cards'>"
                + card("c1", "&#128187;", "项目源码", "github.com/mingyu0401/LiveXP",
                        copyHref("https://github.com/mingyu0401/LiveXP") + "&open=1")
                + card("c3", "&#9993;&#65039;", "联系我", "mingyu0401a@163.com",
                        copyHref("mingyu0401a@163.com") + "&mailto=mingyu0401a@163.com")
                + card("c4", "&#9749;", "支持作者", "请作者喝杯咖啡",
                        "livexp://support")
                + "</div>"
                + credits(sub, cardBorder)
                + "<div class='foot'><a class='log' href='livexp://logs'>查看运行日志</a></div>"
                + "</body></html>";
    }

    /**
     * 致谢和开源使用说明:首项 QA 模块(提供实现思路),次项 FUNBOX(作者最喜欢的模块,无开源地址),
     * 其余为可能用到的项目,逐个附 GitHub 链接。点按条目复制地址,独立 App 内同时尝试打开。
     */
    private static String credits(String sub, String cardBorder) {
        StringBuilder sb = new StringBuilder();
        sb.append("<div class='sec'>")
                .append("<div class='secH'>致谢和开源使用说明<span class='tag'>可选</span></div>")
                .append("<div class='secTip'>LiveXP 是站在别人肩膀上的一次尝试。下面这些人和项目让作者少走了很多弯路,"
                        + "点按条目可复制地址(独立 App 内会同时打开浏览器)。</div>")
                .append(creditRow("QA 模块", "提供了实现思路,LiveXP 的不少做法参考自它。",
                        "https://github.com/cinit/QAuxiliary", false))
                .append(creditRow("FUNBOX", "本人最喜欢的模块,给了我很大的动力。",
                        null, true))
                .append(creditRow("LSPosed", "模块的运行平台,作用域配置、入口与类加载器机制都由它提供。",
                        "https://github.com/LSPosed/LSPosed", false))
                .append(creditRow("XposedBridge (Xposed API 82)", "编译期依赖 de.robv.android.xposed:api,模块所有 hook 的接口来源。",
                        "https://github.com/rovo89/XposedBridge", false))
                .append(creditRow("AndroidX", "ConstraintLayout 等组件,以 compileOnly 方式参与编译。",
                        "https://github.com/androidx/androidx", false))
                .append(creditRow("jadx", "反编译 QQ、定位混淆锚点与核对调用链时使用的工具。",
                        "https://github.com/skylot/jadx", false))
                .append("<div class='secFoot'>LiveXP 自身以 GPL-3.0 协议开源,完整条文见仓库根目录 LICENSE 文件。<br>"
                        + "以上项目版权归各自作者所有,本模块仅在运行时依赖或借鉴其公开实现。</div>")
                .append("</div>");
        return sb.toString();
    }

    /** 一行致谢:url 为空表示没有开源地址,渲染为不可点条目。 */
    private static String creditRow(String name, String descText, String url, boolean favorite) {
        String cls = "cl" + (favorite ? " fav" : "") + (url == null ? " plain" : "");
        String inner = "<div class='clMain'><div class='nm'>" + name
                + (favorite ? "<span class='star'>&#9733; 最爱</span>" : "") + "</div>"
                + "<div class='ds'>" + descText + "</div></div>";
        String tail = url == null ? "<div class='go'>无开源</div>" : "<div class='go'>&#8599;</div>";
        if (url == null) {
            return "<div class='" + cls + "'>" + inner + tail + "</div>";
        }
        return "<a class='" + cls + "' href='" + copyHref(url) + "&open=1'>" + inner + tail + "</a>";
    }

    private static String supportHtml(boolean dark) {
        String bg = dark ? "rgba(13,17,23,.92)" : "rgba(246,248,250,.95)";
        String txt = dark ? "#c9d1d9" : "#424a53";
        String btnBg = dark ? "#30363d" : "#eaeef2";
        String btnText = dark ? "#9aa0a8" : "#57606a";
        String btnActive = dark ? "#3d444d" : "#dfe3e8";
        String qrBorder = dark ? "none" : "1px solid rgba(0,0,0,.1)";
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
                + ".txt{color:" + txt + ";font-size:16px;line-height:1.9;text-align:center;max-width:460px;margin-bottom:24px}"
                + ".qr{background:#fff;border:" + qrBorder + ";border-radius:16px;padding:14px;margin-bottom:28px}"
                + ".qr img{width:min(64vw,300px);height:auto;display:block}"
                + "a.btn{display:block;text-align:center;width:220px;padding:12px 0;border-radius:12px;"
                + "background:" + btnBg + ";color:" + btnText + ";font-size:15px;text-decoration:none;transition:background .2s}"
                + "a.btn:active{background:" + btnActive + "}"
                + "</style></head><body>"
                + "<h1>支持作者</h1>"
                + "<div class='txt'>作者大学牲,精力和 token 来之不易。<br>"
                + "如果您觉得这个模块好用,可以请作者喝一杯咖啡&#9749;<br>"
                + "LiveXP功能免费,自愿赞赏,感谢支持.<br>您的支持是我更新的动力!</div>"
                + "<div class='qr'><img src='data:image/webp;base64," + SupportAssets.QR_WEBP_BASE64 + "'/></div>"
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

    private static String card(String colorCls, String icon, String title, String sub, String href) {
        return "<a class='card' href='" + href + "'>"
                + "<div class='ico " + colorCls + "'>" + icon + "</div>"
                + "<div><div class='tt'>" + title + "</div><div class='sub'>" + sub + "</div></div>"
                + "<div class='arr'>&#8250;</div></a>";
    }
}
