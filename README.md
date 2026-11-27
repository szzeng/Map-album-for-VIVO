# 图迹 for VIVO

> 把手机相册里的照片和视频，重新放回地图上。

**图迹 for VIVO** 是一款面向 vivo Android 手机的个人使用型地图相册。它不打算重新做一套照片管理系统，而是复用手机图库已经保存的媒体索引和位置信息，在地图上浏览照片和视频。

项目使用百度地图 SDK，重点是地图上的照片与视频定位、聚合和浏览；媒体文件仍由手机系统图库管理。

## 项目特点

- **复用现有图库数据**：优先读取 vivo 图库 Provider 已有的 GPS 信息，并通过 Android MediaStore 获取当前授权范围内的照片和视频索引。
- **只读设计**：不修改原媒体和图库 Provider，不重新扫描全图库 EXIF，不复制媒体，也不另建媒体或 GPS 数据库。
- **按需加载**：地图根据当前视野筛选媒体；元数据和系统缩略图按需读取。位置索引和缩略图缓存只保存在应用进程内存中。
- **地图浏览**：在百度地图上显示照片和视频位置，按屏幕空间聚合相近标记；支持“照片”“全部”“视频”模式，默认选择“全部”并记住上次选择。
- **照片预览**：支持照片切换和进度条选图；需要查看完整照片时可跳转到 vivo 原生相册。
- **视频浏览**：视频显示缩略图与播放标识，可在混合列表中浏览并调用系统播放器播放。
- **地图与地点**：支持标准地图/卫星地图一键切换并记住选择，支持地点联想、POI 搜索和搜索历史。
- **定位与外观**：支持冷启动时尝试一次前台定位和手动定位；地图支持跟随系统深浅色或手动切换暗夜模式。

## 设备支持与当前状态

- 项目面向 **vivo 手机**。非 vivo 设备不会开放图库地图功能。
- **vivo X300 Pro（V2502A / PD2502，Android 17）** 是当前主要验证设备，已进行真实照片位置核对、地图浏览、权限和原生相册跳转等体验检查。
- 其他 vivo 机型没有逐一验证，可以尝试使用；是否兼容取决于系统版本、图库 Provider 行为和照片索引对应关系，不作保证。
- 当前工程版本为 **0.4.0（versionCode 5）**。项目仍处于个人使用版开发与实机验证阶段；已有体验结果不等于所有机型、照片和地区均已完成验收。

## 数据读取与隐私边界

视频地图支持正在验证：默认选择“全部”并记住上次选择；视频访问不可用时回退到照片模式。照片和视频仅从当前授权的主卷只读处理，媒体及位置索引不持久化，也不上传照片、视频、GPS 或媒体索引。视频使用系统播放器打开；Provider 类型或路径不能对应的记录不会被用于视频地图。

1. 只读取用户授予访问权限的照片和视频，并且当前仅支持 Android MediaStore 的主卷（Primary External Volume）。
2. 先取得主卷当前可见媒体 ID，再分批向 vivo 图库 Provider 查询已有的经纬度；视频还须满足类型为 3、MediaStore 与 Provider 路径一致、坐标有效，才会进入地图。应用不重新扫描文件或 EXIF 来重建 GPS。
3. 位置索引仅保存在内存中，容量上限为 100,000 个可见媒体 ID。照片读取的容量、权限或 Provider 异常会报告状态；视频不可用时隔离视频读取并保留照片访问。
4. 照片与视频元数据按当前视野和用户操作读取；缩略图使用系统缩略图接口，并采用有上限的内存缓存，不建立应用自己的持久化缩略图库。
5. 应用不实现媒体内容、媒体 ID 或 GPS 列表的批量上传，也不后台持续定位、不保存位置历史。百度地图、地点搜索和定位 SDK 需要联网提供服务；第三方 SDK 的数据处理以[百度隐私政策](https://lbsyun.baidu.com/docs/pcsa?title=compliance/openprivacy)及实际账号配置为准。

**注意：** vivo Provider 的数据结构和照片 ID 对应关系可能因机型、系统和图库版本而异。X300 Pro 上的验证不能证明所有 vivo 设备都具有完全相同的行为。

## 已知限制

- 地图媒体目前只读取主卷；不支持其他存储卷。
- 索引最多容纳 100,000 个可见媒体 ID；视频超出剩余容量时不会显示视频。
- 已取消独立的地图缓存统计与手动清理入口。百度地图 SDK 的缓存由 SDK 自身管理，应用不承诺能够准确统计或完整清理其全部缓存。
- 地图照片位置已开放全球视野，但 Provider 坐标基准及境内外转换仍采用当前实现的近似区域判断。边境附近可能误判，海外和跨境照片尚未完成全面验证；请以实际照片地点核对结果为准。
- 地点搜索依赖百度 POI 服务和地图中心所在城市的解析结果，需要网络及有效 AK。
- 百度官方说明境内非商业地图服务的使用政策与境外地图服务计费规则不同；境外底图可能按地图网格请求计费。使用前请查看[百度境外地图服务说明](https://lbsyun.baidu.com/docs/android?title=androidsdk/guide/create-map/showmap/abroad)，并确认自己的账号配置。

## 本地构建

### 环境

- Android Studio
- JDK 17
- Gradle 8.6
- Android Gradle Plugin 8.4.2
- compileSdk 34，minSdk 29（Android 10）

Android 应用工程位于 **x300pro-map/**。仓库当前未提交 Gradle Wrapper，请使用 Android Studio 配置的 Gradle 8.6 或本机 Gradle 8.6。

### 获取和设置百度地图 AK

1. 登录百度账号，按照[百度地图开放平台使用准备](https://lbsyun.baidu.com/docs/guide?title=android-guide%2Fprepare)完成开发者注册和认证。
2. 打开[百度地图控制台](https://lbsyun.baidu.com/apiconsole/key)，在“我的应用”中选择“创建应用”，应用类型选择 **Android SDK**，并启用需要的地图、定位和地点搜索服务。
3. 填写应用包名 `io.github.szzeng.mapalbum` 和安装包的签名证书 SHA-1。GitHub 发布的 **0.4.0 Release APK** 签名 SHA-1 为 `20:F5:A0:1B:2E:4C:CB:CE:49:0D:6B:1E:24:5A:65:F0:26:F6:86:0D`。自行编译、Debug 包或其他签名的 APK 请使用各自的包名和签名；可用 Android SDK 的 `apksigner verify --print-certs 路径/应用.apk` 查看证书 SHA-1。
4. 提交后复制生成的 AK，在图迹右上角菜单选择“百度地图 AK”，粘贴并保存。也可以在该页面点击“如何获取百度地图 AK？”打开[百度官方申请教程](https://lbsyun.baidu.com/docs/guide?title=android-guide%2Fcreate-project%2Fak)。

百度会校验包名与签名，只有两者与安装包一致，AK 才能正常鉴权。使用服务前请查看百度开放平台的服务条款和计费说明。

如果自行构建应用，还需要在 **x300pro-map/local.properties** 中配置本机 Android SDK 路径和百度地图 AK：

\`\`\`properties
sdk.dir=/path/to/Android/Sdk
baidu.map.ak=YOUR_BAIDU_AK
\`\`\`

**local.properties** 已被 Git 忽略，不要提交 AK、签名密钥或其他本地凭据。AK 会被打包进 APK，并不是 APK 内的秘密；请在百度控制台限制应用包名和签名 SHA-1。

Release 签名配置使用本地 **keystore.properties**，不要将密钥文件或密码提交到仓库。

### 检查

在 **x300pro-map/** 目录下运行：

\`\`\`powershell
powershell -File tests/run-jvm-tests.ps1
powershell -File tests/run-search-tests.ps1
\`\`\`

这些脚本用于运行不依赖手机的 JVM 数据层、聚合和搜索规则检查。它们不能替代真实 Android 权限、vivo Provider、百度地图 SDK、定位精度或照片位置准确性的实机验证。当前 Gradle **testDebugUnitTest** 没有注册测试源码，**NO-SOURCE** 不应被当作测试通过。

## 项目结构

| 路径 | 说明 |
| --- | --- |
| **x300pro-map/** | Android 应用工程 |
| **map002-provider-probe/** | Provider 数据读取验证代码；部分数据层源码由应用构建复用 |
| **docs/research/** | 技术调研、数据验证和实机记录 |
| **docs/design/** | UI 规范与设计稿 |
| **docs/tasks/** | 阶段任务卡 |

## 技术与验证记录

- [MAP-001：vivo 图库数据调研](docs/research/MAP-001-final-report.md)
- [MAP-002：图库 GPS 复用验证](docs/research/MAP-002-final-report.md)
- [MAP-003B：百度地图接入记录](docs/research/MAP-003B-baidu-progress.md)
- [MAP-004.1：数据层实现与限制](docs/research/MAP-004.1-implementation.md)
- [MAP-004：实机测试记录](docs/research/MAP-004-device-test.md)
- [MAP-007：品牌与机型开放策略](docs/tasks/MAP-007.md)
- [MAP-004：UI 规范](docs/design/MAP-004-UI-SPEC.md)
- [开发与技术说明](x300pro-map/README.md)

---

图迹 for VIVO 是独立个人项目，与 vivo、百度无官方隶属或背书关系。
