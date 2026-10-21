# LiveXP

QQ(NT 内核) Xposed 模块:在聊天界面检测**实况图片**(Live Photo / Motion Photo),给对应气泡加「实况」角标;点开大图自动播放内嵌动态视频,右上角可一键重播。

> **⚠️ 未完成作品 / WIP** —— 本项目仍在开发中,判定规则依赖真机校准,混淆锚点可能随 QQ 版本漂移。

## 功能

- **实况角标**:聊天里的实况图片气泡右上角显示「实况」小标(文件级判定:≥1MB 且含 MicroVideo/MotionPhoto XMP、三星 MotionPhoto_Data 尾标或苹果内嵌 ftyp)
- **大图播放**:点开已确认的实况图自动播放一次内嵌视频,播完回到照片;右上角圆形按钮可重播,播放中覆盖查看器其他按钮
- **双 md5 持久库**:判定过的图片(实况/静态)记入 `/sdcard/Android/data/com.tencent.mobileqq/LiveXP/`,跨重启免重扫
- **群文件图片**:以文件形式发送的大图(FileMsgItem)同样支持检测与播放
- **模块设置入口**:QQ 设置页「LiveXP 模块」项 → 进程内关于页(介绍、复制链接、赞赏)
- **独立 App**:桌面图标,关于页/支持弹窗/日志视图,深色模式跟随系统

## 已验证的环境

- QQ 9.3.70(NT 内核,tinker 热更)+ LSPosed
- Android 16 / arm64

## 构建要点(踩坑记录)

- QQ 运行时业务类由 tinker 热更的 `DelegateLastClassLoader` 加载,base.apk 里的同名类是死副本 —— 必须 hook `BaseDexClassLoader` 构造捕获真实类加载器后再装 hook(见 `RuntimeLoaderHook`)。
- 大图查看器 `com.tencent.richframework.gallery.QQGalleryActivity` 类名不混淆但方法全混淆,hook `Instrumentation.callActivityOnResume/OnDestroy` 按类名过滤;当前消息路径用 intent 里的 `KEY_THUMBNAL_BOUND` 与 bind 时登记的实况气泡矩形匹配反查。
- 内嵌视频提取:XMP `MicroVideoOffset` 起点可能指向 mp4 box 内容而非 box 头,需校准前移 8 字节,否则 MediaPlayer 播放报错。
- QQ 防劫持会静默拦截跨应用 `startActivity`,模块界面均在 QQ 进程内渲染。
- debug 签名构建:`gradle :app:assembleDebug`。

## 用法

LSPosed 里启用模块并勾选 QQ 作用域,重启 QQ:

- QQ 设置页出现「LiveXP 模块」入口,点进关于页
- 聊天里的实况图片气泡会(在判定命中后)显示「实况」角标
- 点开实况图查看大图:自动播放一次动态视频,右上角 ↻ 可重播

代码按「个人调试 playbook」风格编写,大量反射与运行时识别,类名混淆随 QQ 版本漂移时需要重新校准。
