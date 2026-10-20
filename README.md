# LiveXP

QQ(NT 内核) Xposed 模块:在聊天界面检测**实况图片**(Live Photo / Motion Photo),给对应气泡加「实况」角标,并提供模块状态查看入口。

> **⚠️ 未完成作品 / WIP** —— 本项目仍在开发中,功能不稳定,判定规则需要真机数据持续校准,请勿在生产环境依赖。

## 现状

- ✅ QQ 设置页模块入口(列表项 + 状态胶囊),点击弹出运行日志面板
- ✅ 聊天气泡绑定链 hook(AIOBubbleMsgItemVB.handleUIState)
- 🚧 实况图判定:文件级 Motion Photo 检测已实现,接收方原图缓存路径定位仍在调试
- 🚧 气泡「实况」角标显示

## 已验证的环境

- QQ 9.3.70(NT 内核,tinker 热更)+ LSPosed 2.2.1
- Android 16 / arm64

## 构建要点(踩坑记录)

- QQ 运行时业务类由 tinker 热更的 `DelegateLastClassLoader` 加载,base.apk 里的同名类是死副本 —— 必须 hook `BaseDexClassLoader` 构造捕获真实类加载器后再装 hook(见 `RuntimeLoaderHook`)。
- QQ 设置页是通用容器 `QPublicFragmentActivity` + `MainSettingFragment`,列表数据源是 `SettingConfigProvider` 的混淆子类。
- QQ 防劫持会静默拦截跨应用 `startActivity`,模块入口改为 QQ 进程内弹面板。
- debug 签名构建:`gradle :app:assembleDebug`。

## 用法

LSPosed 里启用模块并勾选 QQ 作用域,重启 QQ:

- QQ 设置页出现「LiveXP 模块」入口,点击查看运行日志
- 设置页底部绿色胶囊显示加载状态
- 聊天里的实况图片气泡会(在判定命中后)显示「实况」角标

代码按「个人调试 playbook」风格编写,大量反射与运行时识别,类名混淆随 QQ 版本漂移时需要重新校准。
