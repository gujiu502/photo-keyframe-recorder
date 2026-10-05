# Photo Keyframe Recorder

录音时拍下板书或 PPT，回放时点照片时间跳到对应音频。Android 10 及以上，数据保存在手机本地。

**[下载 v0.2.1 APK](https://github.com/gujiu502/photo-keyframe-recorder/releases/tag/v0.2.1)** · [v0.1.0 离线版](https://github.com/gujiu502/photo-keyframe-recorder/releases/tag/v0.1.0) · [所有版本](https://github.com/gujiu502/photo-keyframe-recorder/releases) · [实现与验证说明](docs/IMPLEMENTATION.md)

当前为公开预览版。v0.2.1 首次设置需 Google 登录和 Drive 授权；2026-10-05 项目管理员已确认在 Google Console 发布应用。需要直接离线录音可使用 v0.1.0。完整的两小时录音压力测试及不同实体手机验证尚未完成，请先用短录音确认你的设备兼容性。

## 使用

1. v0.2.1 首次使用需接受协议、Google 登录并授权 Drive；然后允许麦克风权限，开始录音。v0.1.0 无需账号。
2. 点「拍照关键帧」，首次使用允许相机权限。快门记录此刻的音频毫秒位置；相机打开时录音继续。
3. 暂停时也能拍照，照片标在暂停位置。
4. 停止录音后输入文件名，自动附加 `yyyy-MM-dd_HH-mm-ss` 和音频扩展名；保存后在录音列表打开播放器。
5. 点关键帧时间播放对应片段，点图片全屏查看；可用上一帧／下一帧。
6. 点「导出」保存 ZIP 讲义包：音频、照片、`manifest.json`、`timeline.json`、`lecture.md`。

应用异常退出后会显示未完成录音。可尝试恢复，或导出原始文件。被强制停止的 MP4/AAC 可能缺少结尾索引，应用会保留它，但不能保证直接播放或续录。不要在导出前丢弃重要录音。

## 开源来源

基于 [tuuhin/RecorderApp v1.4.4](https://github.com/tuuhin/RecorderApp/tree/v1.4.4)，沿用其录音器、播放器、波形、书签、分类、通知与回收站，遵循 [MIT License](LICENCE)。照片拍摄使用 AndroidX CameraX。没有复制 GPL NotePad 的实现。

v0.1.0 包括持久化会话、毫秒时间轴、CameraX 关键帧、播放器照片与跳转、异常恢复和讲义包导出，无账号或云上传。

v0.2.1 包含版本化用户协议、Google 登录、Drive 自动备份及 Play/Direct 自动更新。Drive 只申请 `drive.file`，数据直接上传到用户的「課程錄音」文件夹；上课时本地录音优先，断网排队，备份完成也保留原件。正式签名 APK 已通过真实 Google 登录和 Drive 备份；Google Console 的应用发布也已由项目管理员确认。后续配置见 [Google 设置说明](docs/GOOGLE_SETUP.md)。没有广告或分析 SDK。

[应用首页](https://gujiu502.github.io/photo-keyframe-recorder/) · [隐私政策](https://gujiu502.github.io/photo-keyframe-recorder/privacy.html) · [使用条款](https://gujiu502.github.io/photo-keyframe-recorder/terms.html)

## 构建

需要 JDK 17 和 Android SDK 36。Windows 请使用不含中文的项目路径。

```sh
./gradlew :app:assembleDirectDebug :app:compilePlayDebugKotlin :data:recorder:testDebugUnitTest :data:cloud:testDebugUnitTest
./gradlew :data:database:connectedDebugAndroidTest :data:cloud:connectedDebugAndroidTest
./gradlew :app:lintDirectRelease :app:assembleDirectRelease :app:bundlePlayRelease
```

正式 APK 使用独立包名 `com.gujiu502.lectureframe`，不会覆盖 RecorderApp。Debug 包名带 `.debug`。

签名文件保存在忽略的 `.signing/release.jks`。本地 `.signing/release.properties` 使用 `ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_PASSWORD`、`ANDROID_KEY_ALIAS` 三个字段；也可通过同名环境变量提供。GitHub 自动发布另需 `ANDROID_KEYSTORE_BASE64` secret。保留原签名密钥才能兼容以后升级，密钥不进入 Git。

界面、通知和小工具使用中文。CI 检查中文文案、Direct/Play 编译、计时／命名、备份续传、数据库迁移、安装签名和 lint。设备工作流的模拟账号用于验证已授权后的离线行为，不能代替真实 Google 登录或 Drive 验证。带 `v` 的标签生成签名 APK、SHA-256 校验文件及 `update.json`；缺少 OAuth 配置时拒绝发布新版。

启动时会联网复查已完成的 Drive 备份，发现外部删除后用本地原件补备份；应用内确认的云端删除会保留删除意图。页面返回统一先回录音主页，主页再按返回退出到桌面，音频编辑保留未保存确认。
