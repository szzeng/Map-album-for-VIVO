# Android 工程说明

本目录是「图迹 for VIVO」Android 应用工程。面向 vivo 手机，复用 vivo 图库 Provider 与 Android MediaStore 的已有媒体索引，在百度地图上展示照片和视频位置。

项目总览、功能介绍、设备支持、隐私边界和已知限制请先阅读仓库根目录的 [中文 README](../README.md)。

## 工程信息

- applicationId / namespace：`io.github.szzeng.mapalbum`
- 当前版本：`0.4.0`（versionCode `5`）
- minSdk：29（Android 10）
- compileSdk：34
- JDK：17
- Gradle：8.6
- Android Gradle Plugin：8.4.2
- 地图 SDK：百度地图 Android SDK 9.0.0
- 地点搜索：百度地图 Search SDK 9.0.0
- 定位 SDK：百度定位 SDK 9.7.0

## 主要实现

- `MainActivity`：地图主界面、地图类型切换、定位、照片与视频聚合、模式切换和菜单。
- `PlaceSearch`：地点联想、POI 搜索、搜索历史和地图定位。
- `PhotoGalleryView`：地图媒体列表与交互。
- `VivoNativeGalleryLauncher`：尝试按地图聚合顺序打开 vivo 原生相册；启动失败时回退到内置预览。
- `PhotoPreviewActivity`：回退时的照片预览、媒体切换和通过系统播放器播放视频。
- `ThumbnailLoader`：系统缩略图按需加载，32 MiB 有界内存 LRU 缓存，两个后台线程和有界任务队列。
- `ScreenClusters`：基于屏幕距离的媒体标记聚合。
- `Appearance`：系统深浅色跟随和手动暗夜模式。
- `../map002-provider-probe/src`：复用的只读图库数据读取、位置索引和视野筛选实现。

地图支持标准地图与卫星地图一键切换，并将选择保存在应用设置中；冷启动时恢复上次选择。定位按钮位于地图右下方，执行定位前会将地图方向归正。

## 数据与安全边界

- 仅在用户确认并授予权限后启用图库地图。
- 当前只支持 Android MediaStore 主卷；位置索引仅保存在进程内存中。
- 可见媒体 ID 容量上限为 100,000；视频不可用时隔离视频读取，照片仍按原有权限及安全检查处理。
- 通过 MediaStore 当前可见媒体 ID 与 vivo Provider 记录关联已有 GPS；视频必须通过类型、路径及有效坐标校验；不重新扫描 EXIF、不写入 Provider、不建立持久化照片或 GPS 数据库。
- 照片和视频元数据与系统缩略图按视野和用户操作读取；缩略图缓存仅在内存中，受 32 MiB 上限约束。
- 不后台持续定位，不保存位置历史。
- 地图位置支持全球视野，但当前境内外坐标转换使用近似区域判断，不能视为已完成全球坐标验证。边境附近和海外照片需结合实际地点核对。
- 百度地图、POI 搜索和定位服务需要网络与有效 AK。境外底图可能按请求网格计费，详见 [百度境外地图服务说明](https://lbsyun.baidu.com/docs/android?title=androidsdk/guide/create-map/showmap/abroad)。

## 设备适配

图库地图功能面向 vivo 手机。X300 Pro（V2502A / PD2502，Android 17）是当前主要实机验证设备；其他 vivo 机型可尝试，但未逐一验证，兼容性取决于系统与图库 Provider 实现。非 vivo 设备不开放图库地图。原生相册队列依赖私有接口，主要在相册 11.0.6.8 上验证；启动后相册内部加载失败无法自动触发回退。超过一万项的聚合曾出现相册无响应，原因尚未确认。

## 本地配置

百度地图 AK 的注册、申请和应用内设置步骤，请参阅[项目 README 中的获取指南](../README.md#获取和设置百度地图-ak)及[百度官方教程](https://lbsyun.baidu.com/docs/guide?title=android-guide%2Fcreate-project%2Fak)。

在本目录的 `local.properties` 中设置：

```properties
sdk.dir=/path/to/Android/Sdk
baidu.map.ak=YOUR_BAIDU_AK
```

`local.properties` 和 Release 签名配置 `keystore.properties` 均应保留在本地，不要提交 AK、签名密钥或密码。AK 会被打包进 APK，不是秘密存储；请在百度控制台限制包名和签名 SHA-1。

## 本地检查

在本目录运行：

```powershell
powershell -File tests/run-jvm-tests.ps1
powershell -File tests/run-search-tests.ps1
```

这些脚本运行无需手机的 JVM 数据层、聚合和搜索规则检查。它们不能替代真实 Android 权限、vivo Provider、百度地图 SDK、定位精度或照片位置准确性的实机验证。Gradle 的 `testDebugUnitTest` 当前为 `NO-SOURCE`，不应将其视为测试通过。

## 相关文档

- [MAP-002：图库 GPS 复用验证](../docs/research/MAP-002-final-report.md)
- [MAP-003B：百度地图接入记录](../docs/research/MAP-003B-baidu-progress.md)
- [MAP-004.1：数据层实现与限制](../docs/research/MAP-004.1-implementation.md)
- [MAP-004：实机测试记录](../docs/research/MAP-004-device-test.md)
- [MAP-007：品牌与机型开放策略](../docs/tasks/MAP-007.md)
- [MAP-004：UI 规范](../docs/design/MAP-004-UI-SPEC.md)
