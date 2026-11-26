# 图迹 / Map Album

正式 Android 应用身份：`io.github.szzeng.mapalbum`（applicationId 和 namespace）；工程目录为 `x300pro-map/`，GitHub 仓库为 `szzeng/x300pro-map`。该包名是后续百度 AK、签名和发布的长期标识。

当前为受限照片样本 MVP 的独立原生 Android 工程，使用 MapLibre Native Android 13.7.1、天地图 HTTPS WMTS `vec_w` 和 `cva_w`。它仅支持用户明确选中的少量主卷照片；照片和位置在内存中，不是完整相册。MAP-002 设备身份验证门槛（`IDENTITY_UNVERIFIED`）不变；样本位置关联仍需用户确认。后续计划将地图替换为百度 Android SDK，目前尚未接入。

调试版通过“地图配置”在手机应用私有文件中保存天地图 Key；不要将 Key 放入源码或 Git。`local.properties` 已被忽略；它也用于本机 Android SDK 配置。调试包和应用私有配置只用于本轮验证，发布前需另行评估 Key 保护和天地图授权。

构建使用 Gradle 8.6、Android Gradle Plugin 8.4.2、compileSdk 34。构建时直接从 `../map002-provider-probe/src` 引入原有 Provider 读取、基础数据集和筛选源文件；不修改或复制维护另一套 Provider 查询逻辑。运行时不启用原有全库加载入口。

当前实机进度及限制见 `../docs/research/MAP-003-progress.md`。
