# LiveXP

QQ(NT 内核) 的 LSPosed 模块:在聊天界面检测**实况图片**(Live Photo / Motion Photo),给对应气泡加「实况」点环角标;点开大图自动播放内嵌动态视频。

[![Star](https://img.shields.io/github/stars/mingyu0401/LiveXP?style=for-the-badge&logo=github&label=%E2%AD%90%20Star&color=ff69b4)](https://github.com/mingyu0401/LiveXP)

> **当前版本 v1.54**(versionCode 55)
> 判定规则依赖真机校准,QQ 版本升级后混淆锚点可能漂移,届时需要重新定位。

## 功能

- **实况角标**:聊天里的实况图片气泡右上角显示白色点环图标(20dp,图案占比 94%,与 App 启动图标同款);多图消息**逐张**打标,不再只标第一张
- **大图播放**:点开已确认的实况图自动播放一次内嵌视频,播完回到照片;查看器右上角留实况图标作重播按钮,播放中覆盖层盖住查看器其他按钮
- **翻页续播**:相册内左右滑动切到下一张实况图自动播放,拖动即停播
- **模块设置入口**:QQ 设置页「LiveXP 模块」项 → 进程内关于页(介绍、复制链接、赞赏、运行日志、致谢与开源说明)
- **独立 App**:桌面图标,同一套关于页 + 日志视图,深色模式跟随系统;并检测模块是否真的被 LSPosed 启用,主标题在「LiveXP 已激活 / 未激活」间切换,未激活时停掉全部炫彩效果
- **关于页细节**:版本行显示**实际** QQ 版本(运行时读取,不写死);长按顶部图案有 3 秒渐快震动彩蛋;交流群一键跳转

## 已验证的环境

- KSU + LSPosed 2.2.1(7925)+ Android 17(一加 15 PLK110 真机,arm64)
- QQ 9.2.66 / 9.3.70 测试通过(NT 内核,tinker 热更)

## 构建

需要 JDK 17+(实测 Android Studio 自带 JBR 21)与 Android SDK 36。建议直接拿 release。

## 用法

LSPosed 里启用模块并勾选 QQ 作用域,重启 QQ:

- QQ 设置页出现「LiveXP 模块」入口,点进关于页
- 聊天里的实况图片气泡会(在判定命中后)显示点环角标,多图逐张显示
- 点开实况图查看大图:自动播放一次动态视频,右上角图标可重播;左右翻页自动续播

## 致谢与开源说明

- **Qoder 与 deepseek-v4.1-flash / glm-5.3-flash / qwen-3.8-flash** —— 一起写代码、查资料、反复试错的搭档;还有作者自己和那台小手机(一加 15),所有结论都是在这台机器上跑出来的 :)
- **FUNBOX** —— 本人最喜欢的模块,给了我很大的动力。
- **[QA 模块](https://github.com/cinit/QAuxiliary)** —— 提供了实现思路。
- [LSPosed](https://github.com/LSPosed/LSPosed)、[XposedBridge](https://github.com/rovo89/XposedBridge)、[AndroidX](https://github.com/androidx/androidx) 等等。

完整清单也在模块关于页底部的「致谢和开源使用说明」里。以上项目版权归各自作者所有。

## 许可证

本项目以 [GPL-3.0](LICENSE) 协议开源,Copyright (C) 2026 明雨 (mingyu0401)。

分发或修改后的版本需同样以 GPL-3.0 开源并提供源码。仅供学习与个人使用,请勿用于修改 QQ 服务器数据或商业用途。

## 赞助

功能免费,自愿赞赏,不打赏也完全不影响使用。想请作者喝杯咖啡就扫下面这个码(图片同样在 [DONATE.md](DONATE.md) 里):

<img src="assets/donate-qr.webp" width="280" alt="明雨的赞赏码">

