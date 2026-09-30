# Photo Keyframe Recorder

录音时拍下板书或 PPT，回放时点照片时间跳到对应音频。Android 10 及以上，数据保存在手机本地。

**[下载 APK](https://github.com/gujiu502/photo-keyframe-recorder/releases/tag/v0.1.0)** · [所有版本](https://github.com/gujiu502/photo-keyframe-recorder/releases) · [实现与验证说明](docs/IMPLEMENTATION.md)

第一版为公开预览版。完整的两小时录音压力测试及不同实体手机验证尚未完成，请先用短录音确认你的设备兼容性。

## 使用

1. 允许麦克风权限，开始录音。
2. 点「拍照关键帧」，首次使用允许相机权限。快门记录此刻的音频毫秒位置；相机打开时录音继续。
3. 暂停时也能拍照，照片标在暂停位置。
4. 停止录音后输入文件名，自动附加 `yyyy-MM-dd_HH-mm-ss` 和音频扩展名；保存后在录音列表打开播放器。
5. 点关键帧时间播放对应片段，点图片全屏查看；可用上一帧／下一帧。
6. 点「导出」保存 ZIP 讲义包：音频、照片、`manifest.json`、`timeline.json`、`lecture.md`。

应用异常退出后会显示未完成录音。可尝试恢复，或导出原始文件。被强制停止的 MP4/AAC 可能缺少结尾索引，应用会保留它，但不能保证直接播放或续录。不要在导出前丢弃重要录音。

## 开源来源

基于 [tuuhin/RecorderApp v1.4.4](https://github.com/tuuhin/RecorderApp/tree/v1.4.4)，沿用其录音器、播放器、波形、书签、分类、通知与回收站，遵循 [MIT License](LICENCE)。照片拍摄使用 AndroidX CameraX。没有复制 GPL NotePad 的实现。

新增工作包括持久化会话、毫秒时间轴、CameraX 关键帧、播放器照片与跳转、异常恢复和讲义包导出。无账号、广告、分析 SDK 或云上传；没有 Internet 权限。系统自动备份关闭，请使用讲义包导出备份。

## 构建

需要 JDK 17 和 Android SDK 36。Windows 请使用不含中文的项目路径。

```sh
./gradlew :app:assembleDebug :data:recorder:testDebugUnitTest
./gradlew :data:database:connectedDebugAndroidTest
./gradlew :app:lintRelease :app:assembleRelease
```

正式 APK 使用独立包名 `com.gujiu502.lectureframe`，不会覆盖 RecorderApp。Debug 包名带 `.debug`。

签名文件保存在忽略的 `.signing/release.jks`。本地 `.signing/release.properties` 使用 `ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_PASSWORD`、`ANDROID_KEY_ALIAS` 三个字段；也可通过同名环境变量提供。GitHub 自动发布另需 `ANDROID_KEYSTORE_BASE64` secret。保留原签名密钥才能兼容以后升级，密钥不进入 Git。

界面、通知和小工具使用中文。CI 运行中文文案检查、编译、计时／命名测试和 lint。设备工作流运行数据库迁移和持久化测试，以及 API 34 模拟器上的相机／录音／ZIP 导出流程。带 `v` 的标签生成签名 APK 和 SHA-256 校验文件。
