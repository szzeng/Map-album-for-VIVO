# MAP-001 V2 · vivo 相册组件

## 已安装软件及版本

| 软件包 | 当前版本 / 版本号 | 活跃 APK | 取证范围 |
| --- | --- | --- | --- |
| `com.vivo.gallery` | 11.0.6.8 / 110006008 | `/data/app/~~VLvDQt_FGdbv3CNerFEstg==/com.vivo.gallery-opmgBrSjPJHMflFNOXHy8g==/base.apk` | Manifest 与足迹、Provider 定向反编译 |
| `com.vivo.base.gallery` | 2.3.1.0 / 2030100 | `/system/app/VivoBaseGallery/VivoBaseGallery.apk` | Manifest 与 GPS 候选类和常量定位 |
| `com.android.photopicker` | 16.0.1.1 / 16000101 | `/apex/com.android.mediaprovider/priv-app/Photopicker@CP2A.260605.016/Photopicker.apk` | Manifest 与 vivo 相册适配候选定位 |
| `com.android.providers.media.module` | 17 / 37 | `/apex/com.android.mediaprovider/priv-app/MediaProvider@CP2A.260605.016/MediaProvider.apk` | Manifest、标准照片读查询及扫描代码 |
| `com.vivo.widget.gallery` | 3.0.3.1 / 30301 | `/system/custom/app/GalleryWidget/GalleryWidget.apk` | 设备包和组件登记，未分析 APK |

各包的 `pm path` 均只返回一个 APK。相册还有预装的 11.0.6.5 条目，调查以 `pm path` 指向的已更新版本 11.0.6.8 为准。相册、基础相册、相册组件共享 `com.vivo.gallery.sharedId`。其内部互相访问能力与普通独立应用的权限不同。

分析用的四个 APK 已在设备和 Windows 分别计算 SHA-256，四组均一致。JADX 由 [官方 1.5.7 发布页](https://github.com/skylot/jadx/releases/tag/v1.5.7) 获取。APK 副本与完整定向反编译工作文件位于 Windows 临时目录 `%TEMP%\x300pro-map001-v2`。交付目录保留哈希、解码 Manifest、针对性引用与必要代码摘录。

## 与足迹有关的组件

- `GalleryTabActivity` 是系统解析出的原生相册入口。
- `FootprintMapActivity` 是地图 Activity，Manifest `exported=false`。`LocationPage` 有跳入地图的调用，地图 Fragment 从相册内部加载聚类数据。
- `GalleryDataProvider`、`GalleryOpenProvider`、`GalleryCloseProvider`、`GalleryMediaProvider` 是不同入口。它们的 export 状态和实际查询权限见 [Provider 分析](provider-analysis.md)。
- 基础相册的 `MediaExtraProvider` 和 Photo Picker 内的同名 Provider 各用自己的 authority。Photo Picker 的系统选图能力以用户选中的 URI 为边界。
- [实机包和组件](evidence/A-adb-inventory.txt) · [APK 哈希](evidence/B-apk-hashes.txt) · [主相册 Manifest](evidence/manifests/com.vivo.gallery/AndroidManifest.xml)
