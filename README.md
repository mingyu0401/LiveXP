# LiveXP

QQ(NT 内核) 的 LSPosed 模块:在聊天界面检测**实况图片**(Live Photo / Motion Photo),给对应气泡加「实况」点环角标;点开大图自动播放内嵌动态视频,相册里左右翻页也能跟着播。

> **当前版本 v1.50**(versionCode 51)
> 判定规则依赖真机校准,QQ 版本升级后混淆锚点可能漂移,届时需要重新定位。

## 功能

- **实况角标**:聊天里的实况图片气泡右上角显示白色点环图标(20dp,图案占比 94%,与 App 启动图标同款);多图消息**逐张**打标,不再只标第一张
- **大图播放**:点开已确认的实况图自动播放一次内嵌视频,播完回到照片;查看器右上角留实况图标作重播按钮,播放中覆盖层盖住查看器其他按钮
- **翻页续播**:相册内左右滑动切到下一张实况图自动播放,拖动即停播
- **透明叠加层**:视频用 `setZOrderOnTop` + 半透明叠加渲染,首帧之前露出原图,不闪黑
- **双 md5 持久库**:判定过的图片(实况/静态)记入 `/sdcard/Android/data/com.tencent.mobileqq/LiveXP/`,跨重启免重扫
- **群文件图片**:以文件形式发送的大图(FileMsgItem)同样支持检测与播放
- **模块设置入口**:QQ 设置页「LiveXP 模块」项 → 进程内关于页(介绍、复制链接、赞赏、运行日志、致谢与开源说明)
- **独立 App**:桌面图标,同一套关于页 + 日志视图,深色模式跟随系统

## 已验证的环境

- QQ 9.3.70(NT 内核,tinker 热更)+ LSPosed
- Android 16 / arm64(一加 15 PLK110 真机)

## 构建

需要 JDK 17 与 Android SDK 36。签名信息放仓库根的 `keystore.properties`(已 gitignore,密钥文件在仓库外),缺失时 release 退化为未签名产物。

```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"
gradle :app:assembleRelease
# 产物:app/build/outputs/apk/release/app-release.apk
```

`keystore.properties` 格式:

```properties
storeFile=C:/path/to/your.jks
storePassword=******
keyAlias=livexp
keyPassword=******
```

## 用法

LSPosed 里启用模块并勾选 QQ 作用域,重启 QQ:

- QQ 设置页出现「LiveXP 模块」入口,点进关于页
- 聊天里的实况图片气泡会(在判定命中后)显示点环角标,多图逐张显示
- 点开实况图查看大图:自动播放一次动态视频,右上角图标可重播;左右翻页自动续播

## 实现要点(踩坑记录)

- QQ 运行时业务类由 tinker 热更的 `DelegateLastClassLoader` 加载,base.apk 里的同名类是死副本 —— 必须 hook `BaseDexClassLoader` 构造捕获真实类加载器后再装 hook(见 `RuntimeLoaderHook`)。
- 实况判定只走文件级:≥1MB 且含 XMP `MicroVideo`/`MotionPhoto`、三星 `MotionPhoto_Data` 尾标或苹果内嵌 `ftyp`;`PicElement` 元数据里没有可用判据。
- 缩略图(`/chatthumb/` 或 `_hd`)不含内嵌视频,必须跳过并等原图落盘,否则硬提会弹「提取失败」。
- 多图消息的 `sourcePath` 常为 `null`,靠 `fileSize + md5` 在 chatraw 目录反查原图,索引在后台线程建一次(约 1978 个文件 / 1.3s),不要搬到主线程。
- `_mediaId` 后缀是元素下标而非图序,角标编号按同一 mediaId 在相册里的出现次序推导,不能简单 `suffix - 1`。
- 大图查看器 `com.tencent.richframework.gallery.QQGalleryActivity` 类名不混淆但方法全混淆,hook `Instrumentation.callActivityOnResume/OnDestroy` 按类名过滤;当前消息路径用 intent 里的 `KEY_THUMBNAL_BOUND` 与 bind 时登记的实况气泡矩形匹配反查。
- 翻页信号要 hook 内层 RecyclerView 的 `scrollToPosition` / `smoothScrollToPosition` / `onScrollStateChanged` —— QQ 绕过了 `ViewPager2.dispatchOnPageSelected`;androidx 的 hook 进程级只装一次,否则会撞 LSPosed 回调上限。
- 内嵌视频提取:XMP `MicroVideoOffset` 起点可能指向 mp4 box 内容而非 box 头,需校准前移 8 字节;一加产出的实况图通常需要这次前移,否则 MediaPlayer 报错。
- QQ 防劫持会静默拦截跨应用 `startActivity`,模块界面均在 QQ 进程内渲染;关于页里的链接点击行为是「复制到剪贴板」,只有独立 App 里才会真的拉起浏览器。

## 致谢与开源说明

- **[QA 模块](https://github.com/cinit/QAuxiliary)** —— 本人最喜欢的模块,给了我很大的动力,LiveXP 的很多交互和实现思路都受它启发。
- **FUNBOX** —— 同样好用的模块,可惜没有开源地址。
- [LSPosed](https://github.com/LSPosed/LSPosed)、[XposedBridge](https://github.com/rovo89/XposedBridge)、[AndroidX](https://github.com/androidx/androidx)、[jadx](https://github.com/skylot/jadx) —— 运行平台、API、组件与反编译工具。

完整清单也在模块关于页底部的「致谢和开源使用说明」里。以上项目版权归各自作者所有。

## 许可证

本项目以 [GPL-3.0](LICENSE) 协议开源,Copyright (C) 2026 明雨 (mingyu0401)。

分发或修改后的版本需同样以 GPL-3.0 开源并提供源码。仅供学习与个人使用,请勿用于修改 QQ 服务器数据或商业用途。
