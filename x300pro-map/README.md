# MAP-003 Android 样本工程

独立原生 Android 工程，使用 MapLibre Native Android 13.7.1、天地图 HTTPS WMTS `vec_w` 和 `cva_w`。它仅支持用户明确选中的少量主卷照片；照片和位置在内存中，不是完整相册。MAP-002 设备身份门槛不变；样本位置关联仍需用户确认。

调试版通过“地图配置”在手机应用私有文件中保存天地图 Key；不要将 Key 放入源码或 Git。`local.properties` 已被忽略；它也用于本机 Android SDK 配置。调试包和应用私有配置只用于本轮验证，发布前需另行评估 Key 保护和天地图授权。

构建使用 Gradle 8.6、Android Gradle Plugin 8.4.2、compileSdk 34。构建时直接从 `../map002-provider-probe/src` 引入原有 Provider 读取、基础数据集和筛选源文件；不修改或复制维护另一套 Provider 查询逻辑。运行时不启用原有全库加载入口。

当前实机进度及限制见 `../docs/research/MAP-003-progress.md`。
