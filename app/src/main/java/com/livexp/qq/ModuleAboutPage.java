package com.livexp.qq;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

/**
 * 模块关于页:炫彩 GitHub 图标 + 模块简介 + 源码/主页/联系邮箱。
 * QQ 进程里弹全屏 Dialog(QQ 防劫持,跳转独立 Activity 会被静默拦截);
 * 独立 App(StatusActivity)直接 setContentView 同一页面。
 */
public final class ModuleAboutPage {

    /** 二级页里点「查看运行日志」时的回调。 */
    public interface LogsOpener {
        void openLogs();
    }

    private ModuleAboutPage() {}

    public static WebView createWebView(Context ctx, LogsOpener opener) {
        WebView web = new WebView(ctx);
        web.setBackgroundColor(Color.rgb(0x0D, 0x11, 0x17));
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
                    if (opener != null) opener.openLogs();
                    return true;
                }
                return true;
            }
        });
        web.loadDataWithBaseURL(null, html(), "text/html", "utf-8", null);
        return web;
    }

    public static Dialog showDialog(Context ctx, LogsOpener opener) {
        Dialog dialog = new Dialog(ctx);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(createWebView(ctx, opener), new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
        dialog.show();
        return dialog;
    }

    private static String html() {
        String gh = "M12 .297c-6.63 0-12 5.373-12 12 0 5.303 3.438 9.8 8.205 11.385.6.113.82-.258.82-.577 "
                + "0-.285-.01-1.04-.015-2.04-3.338.724-4.042-1.61-4.042-1.61C4.422 18.07 3.633 17.7 3.633 17.7 "
                + "c-1.087-.744.084-.729.084-.729 1.205.084 1.838 1.236 1.838 1.236 1.07 1.835 2.809 1.305 3.495.998 "
                + ".108-.776.417-1.305.76-1.605-2.665-.3-5.466-1.332-5.466-5.93 0-1.31.465-2.38 1.235-3.22 "
                + "-.135-.303-.54-1.523.105-3.176 0 0 1.005-.322 3.3 1.23.96-.267 1.98-.399 3-.405 1.02.006 2.04.138 3 .405 "
                + "2.28-1.552 3.285-1.23 3.285-1.23.645 1.653.24 2.873.12 3.176.765.84 1.23 1.91 1.23 3.22 "
                + "0 4.61-2.805 5.625-5.475 5.92.42.36.81 1.096.81 2.22 0 1.606-.015 2.896-.015 3.286 0 .315.21.69.825.57 "
                + "C20.565 22.092 24 17.592 24 12.297c0-6.627-5.373-12-12-12";
        return "<!DOCTYPE html><html><head><meta charset='utf-8'>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<style>"
                + "*{box-sizing:border-box;margin:0;padding:0}"
                + "body{background:radial-gradient(1200px 600px at 50% -10%,#16202e 0%,#0d1117 60%),#0d1117;"
                + "color:#e6edf3;font-family:sans-serif;display:flex;flex-direction:column;align-items:center;"
                + "min-height:100vh;padding:40px 22px 30px}"
                + ".hero{position:relative;width:190px;height:190px;display:flex;align-items:center;justify-content:center;margin-bottom:6px}"
                + ".ring{position:absolute;inset:0;border-radius:50%;"
                + "background:conic-gradient(from 0deg,#a78bfa,#38bdf8,#34d399,#fbbf24,#f472b6,#a78bfa);"
                + "animation:spin 4s linear infinite;filter:blur(14px);opacity:.55}"
                + ".ring2{position:absolute;inset:14px;border-radius:50%;"
                + "background:conic-gradient(from 180deg,#f472b6,#fbbf24,#34d399,#38bdf8,#a78bfa,#f472b6);"
                + "animation:spin 6s linear infinite reverse}"
                + ".hole{position:absolute;inset:17px;border-radius:50%;background:#0d1117}"
                + ".cat{position:relative;z-index:2;animation:glow 3s ease-in-out infinite alternate}"
                + "@keyframes spin{to{transform:rotate(360deg)}}"
                + "@keyframes glow{from{filter:drop-shadow(0 0 6px rgba(167,139,250,.35))}"
                + "to{filter:drop-shadow(0 0 18px rgba(56,189,248,.7))}}"
                + "h1{font-size:30px;letter-spacing:1px;margin-top:14px;"
                + "background:linear-gradient(90deg,#a78bfa,#38bdf8,#34d399,#fbbf24,#f472b6);"
                + "-webkit-background-clip:text;background-clip:text;color:transparent;background-size:200% 100%;"
                + "animation:shift 5s linear infinite}"
                + "@keyframes shift{to{background-position:200% 0}}"
                + ".ver{color:#7d8590;font-size:13px;margin-top:6px}"
                + ".desc{color:#9ea7b3;font-size:14px;line-height:1.8;margin:20px 0 26px;text-align:center;max-width:420px}"
                + ".cards{width:100%;max-width:420px;display:flex;flex-direction:column;gap:12px}"
                + "a.card{display:flex;align-items:center;gap:14px;text-decoration:none;color:#e6edf3;"
                + "background:rgba(255,255,255,.045);border:1px solid rgba(255,255,255,.08);"
                + "border-radius:14px;padding:15px 16px;transition:background .2s}"
                + "a.card:active{background:rgba(255,255,255,.12)}"
                + ".ico{width:38px;height:38px;border-radius:10px;display:flex;align-items:center;justify-content:center;"
                + "font-size:19px;flex:none}"
                + ".c1{background:linear-gradient(135deg,#6e40c9,#388bfd)}"
                + ".c2{background:linear-gradient(135deg,#238636,#3fb950)}"
                + ".c3{background:linear-gradient(135deg,#d29922,#f0883e)}"
                + ".tt{font-size:15px;font-weight:bold}.sub{font-size:12px;color:#7d8590;margin-top:3px}"
                + ".arr{margin-left:auto;color:#7d8590;font-size:18px}"
                + ".foot{margin-top:auto;padding-top:30px}"
                + "a.log{color:#58a6ff;font-size:13px;text-decoration:none}"
                + "</style></head><body>"
                + "<div class='hero'><div class='ring'></div><div class='ring2'></div><div class='hole'></div>"
                + "<svg class='cat' width='104' height='104' viewBox='0 0 24 24'>"
                + "<defs><linearGradient id='g' x1='0' y1='0' x2='1' y2='1'>"
                + "<stop offset='0' stop-color='#a78bfa'><animate attributeName='stop-color' "
                + "values='#a78bfa;#38bdf8;#34d399;#fbbf24;#f472b6;#a78bfa' dur='8s' repeatCount='indefinite'/></stop>"
                + "<stop offset='1' stop-color='#38bdf8'><animate attributeName='stop-color' "
                + "values='#38bdf8;#34d399;#fbbf24;#f472b6;#a78bfa;#38bdf8' dur='8s' repeatCount='indefinite'/></stop>"
                + "</linearGradient></defs>"
                + "<path fill='url(#g)' d='" + gh + "'/></svg></div>"
                + "<h1>LiveXP</h1>"
                + "<div class='ver'>v" + BuildConfig.VERSION_NAME + " · for QQ 9.3.70 (NT)</div>"
                + "<div class='desc'>QQ 实况图片检测模块。检测聊天中的实况照片(动态照片),"
                + "在气泡右上角标注「实况」;支持普通图片与群文件大图,"
                + "并使用本地双 md5 库持久化判定结果,重启后秒判。<br>点按下方卡片可复制对应链接 / 邮箱。</div>"
                + "<div class='cards'>"
                + card("c1", "&#128187;", "项目源码", "github.com/mingyu0401/LiveXP",
                        copyHref("https://github.com/mingyu0401/LiveXP"))
                + card("c2", "&#128100;", "作者主页", "github.com/mingyu0401",
                        copyHref("https://github.com/mingyu0401"))
                + card("c3", "&#9993;&#65039;", "联系我", "mingyu0401@qq.com",
                        copyHref("mingyu0401@qq.com") + "&mailto=mingyu0401@qq.com")
                + "</div>"
                + "<div class='foot'><a class='log' href='livexp://logs'>查看运行日志</a></div>"
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
