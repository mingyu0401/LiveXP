# LiveXP 交接提示词（给 Qoder / 下一位 AI 助手）

> 你接手的是一个正在活跃开发的 Android Xposed/LSPosed 模块项目 LiveXP。
> 先通读本提示，再动手改代码。用户会在两轮对话之间手改代码并自行提交，**改任何文件前必须先 Read 最新内容、先看 git log**。

## 1. 项目目标

Hook Android QQ（NT 内核，版本会自动更新，当前 9.3.75+），实现：
1. **聊天实况识别与播放**：聊天气泡里识别"实况图"（Motion Photo），打点环角标；点开大图自动播放合成产物（动态段 + 收尾 3 秒定格照片，LiveComposer 单趟编码）。
2. **发送侧「实况转MP4」**（LiveSendHook）：半屏/全屏相册网格实况图左上角贴角标；底栏"原图"右边挂「实况转MP4」按钮；面板打开即自动排队转码，用户勾选并点按钮置 armed（文案：实况转MP4 → 转换中(k/N) → 全部转换完成），发送时替换成视频，未转完则按住结果等转码放行。
3. **周边**：QQ 设置页模块入口、关于页（长按渐快震动彩蛋、加群按钮）、独立 App 状态页（粘性激活显示）、检测阈值滑块、运行日志页。

## 2. 环境与流程

- 仓库：`C:\Users\Elaina\OneDrive-Elaina.MingYu\Desktop\QoderPlay\LiveXP`，纯 Java + AGP，包名 `com.livexp.qq`，minSdk 27 / target 36
- 设备：一加 15（PLK110），1272x2772，已装 LSPosed 2.2.1(7925, Zygisk, 管理器隐藏模式)
- adb：`C:\Users\Elaina\AppData\Local\Android\Sdk\platform-tools\adb.exe`
  无线调试 IP 一般是 192.168.137.93，**端口经常变**，连不上先 `adb mdns services`，不行就让用户重开"无线调试"给新端口
- Gradle：`C:\Users\Elaina\tools\gradle-8.13\bin\gradle.bat`，JAVA_HOME=`C:\Program Files\Android\Android Studio\jbr`
- 构建命令（cmd，必须看退出码）：
  ```
  cd /d C:\Users\Elaina\OneDrive-Elaina.MingYu\Desktop\QoderPlay\LiveXP
  set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
  C:\Users\Elaina\tools\gradle-8.13\bin\gradle.bat :app:assembleRelease > build.log 2>&1
  ```
  产物自动命名为 `LiveXP-v{versionName}({versionCode})-release.apk`，复制到 `C:\Users\Elaina\Downloads\`
- **版本号规则**（用户明令）：versionName 冻结在 1.66，**只有用户明确说"更新版本号"或"推送"时才 +1**；versionCode 每次提交 +1
- 签名：仓库外 `C:\Users\Elaina\.livexp\livexp-release.jks`，仓库根 keystore.properties 已 gitignore。**绝不提交/打印/泄露 keystore.properties 与 *.jks**
- Git：每完成一个需求 commit 一次，**绝不 push**（远端 main 是故意保留的旧快照）；push 或删数据前必须先问
- 校验：`build-tools\36.0.0\apksigner.bat verify` + `zipalign.exe -c -p 4`
- 装包：`adb install -r <apk>`；生效：`am force-stop com.tencent.mobileqq` + `monkey -p com.tencent.mobileqq -c android.intent.category.LAUNCHER 1`
- 日志：`adb shell su -c "cat /sdcard/Android/data/com.tencent.mobileqq/LiveXP/livexp.log"`（PC 端 findstr 过滤；GBK 乱码属正常，按拼音猜关键词）
- 导航一律 `uiautomator dump`（PC 端 cat 回来解析 XML DOM），**注意 dump 的坐标是真实分辨率**（1272x2772），截图 Read 显示是缩放过的，目测坐标要按比例换算
- 用户鼠标坏了：不要用鼠标点 GUI；用户开远程桌面占用设备时停止 input 注入；**不要给用户"你去点一下"的手动步骤，能 adb 自动完成就自动完成**
- 结论要实测得出；批量/有风险操作先说明

## 3. 代码地图（app/src/main/java/com/livexp/qq/）

- `HookEntry.java`：模块入口，装 RuntimeLoaderHook + StatusBridge
- `RuntimeLoaderHook.java`：QQ 业务类由 Tinker DelegateLastClassLoader 加载，必须在该加载器上装 hook；attachBaseContext 捕获 appCtx
- `LiveBadgeHook.java`：聊天气泡角标（AIOBubbleMsgItemVB.handleUIState），多图消息逐张判定
- `LivePhotoDetector.java`：文件级实况判定 + md5 双库持久化 + 多图 sourcePath 为空时按 fileSize+md5 反查 chatraw
- `BadgeInjector.java` / `LiveMarkView.java`：气泡角标绘制
- `LiveVideoPlayer.java`：大图查看器播放链路（QQGalleryActivity），相册数据解析（key_init_bean.richMediaDataList）、翻页信号、播放合成产物
- `LiveComposer.java`：**合成核心**（动态段+收尾3秒定格，单趟编码，EXIF/旋转/VBR 码控，发布 MediaStore 供发送）
- `LiveSendHook.java`：**发送侧全部逻辑**（按钮挂载、网格角标、armed 排队、结果回传拦截替换）——最大最复杂的文件，改动前先通读
- `PreviewBadge.java` / `LiveSendMarks.java` / `LiveSettings.java` / `ThresholdConfig.java` / `StatusBridge.java` / `StatusReceiver.java` / `StatusProvider.java` / `LspInfo.java` / `ModuleAboutPage.java` / `StatusActivity.java` / `SettingsEntryHook.java` / `Haptics.java` / `Log.java`

## 4. 已确认的技术真相（实测得出，勿重走弯路）

### 实况判定
- 只能文件级：≥1MB 且头 64KB 有 XMP MicroVideo/MotionPhoto，或尾部 MotionPhoto_Data（三星），或文件内 ftyp；OPPO/Google 新风格首选 **XMP Container:Directory 里 video/mp4 条目的 Item Length** 精确定位（起点=文件大小-Length）
- 盲扫 ftyp 会假命中 JPEG 数据；PicElement 元数据字段没有实况判据
- 缩略图（/chatthumb/ 或 _hd）不能判定/播放；多图消息 sourcePath 常为 null，按 fileSize+md5 反查

### 合成（LiveComposer 铁律）
- 动态段先播 + 收尾 3 秒定格；**音频按原生时间戳 remux 绝不平移到定格段**（否则播放器跳前三秒）
- 动态段按实际时长收尾，不循环补齐；定格帧用照片 EXIF 方向、动态段用视频 rotation，两者分开处理
- I_FRAME_INTERVAL 放宽 + VBR 码控，否则定格段开头发糊

### QQ 相册（LiveSendHook）
- QQ NT 单 Activity 架构（SplashActivity），半屏面板/全屏相册都是浮层；**全屏相册是独立 Activity**（com.tencent.qqnt.qbasealbum.WinkHomeActivity），看门狗靠 hookAlbumResume + hookWindowAdd（WindowManagerGlobal.addView）双保险覆盖
- 半屏网格 = `aio.panel.photo.PhotoPanelAdapter`；全屏网格待确认（AlbumListAdapter 是文件夹列表，数据项无路径）；数据项**没有 getPath()**，用 pathOfLoose（反射 String 字段找 /storage/ 开头）
- 面板刚打开时数据表为空，要延迟重试（最多 6 次×1.2s）+ 挂上后 scanExistingCells 补扫已显示格子
- 扫描节流必须 **per-root**（多窗口互相饿死）；按钮全局单例（多窗口各挂一份会打架）；面板关闭（锚点消失）必须 removeButton 清浮层
- 底栏锚点判定：有"原图/编辑"+有"发送"即可，**不要按"相册"文案排除**（实测误杀）；锚点/发送文案 WeakReference 缓存防布局风暴全树扫描
- 预览页宿主也是 WinkHomeActivity；半屏面板用 PreviewCustomizationBase 本体，全屏预览用覆写 onSendClick 的 FlashTransferPreviewCustomization——**子类覆写的方法要按运行时类补挂**（LSPosed 64 次回调上限，勿重复注册）
- 发送替换走 dispatchActivityResult 拦截；LocalMediaInfo 深拷贝用 Parcel；类型换算用 QQ 的 MediaTypeHelper

### 播放与状态
- 播放层 setZOrderOnTop(true)+TRANSLUCENT 透明 surface 置顶，首帧前露出原图——用户明确拒绝过闪黑/截图垫底方案，不要改回去
- App 页激活态 = 粘性（心跳 ts>0 永远点亮，探活只刷新版本/阈值）；长按图标只是震动彩蛋
- androidx 框架类 hook 必须进程级只装一次（64 次回调上限）

### LSPosed 版本探测
- 隐藏管理器包管理器查不到，旧 API 82 拿不到框架版本；唯一路径是现代入口（META-INF/xposed/java_init.list + XposedModule.getFrameworkVersion，Maven `io.github.libxposed:api:101.0.1`）——会改变模块加载方式，需全面回归，**等用户点头再做**

## 5. 当前进度与待办

- HEAD：8311d4f（相册面板排队转码+armed 文案+按钮残留清理）→ c72ae4a（v1.72 网格角标+按钮自启）→ a519e9e（产物改名+滑动卡顿修复）
- 已真机验证：半屏相册按钮/角标/自动排队/合成全链路；聊天大图播放合成链路
- **进行中（83 号包刚装上未验证）**：全屏相册按钮+角标修复（hookWindowAdd + per-root 节流 + 按钮单例 + AlbumListAdapter 结构 dump）。验证方法：进全屏相册看日志（"相册网格 adapter 已挂/网格候选 adapter 排除"+ adapter 字段 dump 行），按 dump 结果适配全屏网格数据结构
- **待办**：
  1. 全屏相册验证与适配（83 号包）
  2. 任务3：模块设置页状态栏灰遮罩去除+深浅色反色；"实况图片检测+播放"总开关（默认开，关闭弹挽留确认）
  3. 任务4：深浅色切换不重载（方案：CSS 变量+livexpTheme(dark)+Dialog 子类 onConfigurationChanged 热切换）
  4. 任务5：App 页浅色模式状态栏文字全白不可读（LIGHT_STATUS_BARS）
  5. 半屏左右滑动流畅度用户待反馈
  6. LSP 版本探测（等用户点头）

## 6. 协作规则（务必遵守）

- 全部进度说明用**中文**，代码/命令/日志保持原样
- 每个需求完成即 git commit（versionCode+1，versionName 不动），不 push
- 构建一律 release，产物放 Downloads
- 用户可能两轮对话间手改代码：**改文件前先 Read 最新内容、先 git log/status**
- 结论实测得出，不要从中间事实想当然；批量/有风险操作先说明
- 同一任务内的确认类操作直接做；push、删除数据、改共享配置必须先问
- 设备调试时先 ping/mDNS 判断连接，端口失效找用户要新的
