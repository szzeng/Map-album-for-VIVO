# 图迹 / Map Album

正式 Android 应用身份：`io.github.szzeng.mapalbum`（applicationId 和 namespace）；工程目录为 `x300pro-map/`，GitHub 仓库为 `szzeng/x300pro-map`。

当前是受限照片样本 MVP：使用百度 Android 地图 SDK 9.0.0 普通地图，用户明确选择 1～20 张主卷照片，照片和位置仅保存在内存中，不是完整相册。当前样本验证仅支持广州附近照片；非该范围的照片不会自动定位或显示标记。MAP-002 的 `IDENTITY_UNVERIFIED` 设备身份门槛不变。

三张广州附近样本由用户对照实地位置，WGS84 转 BD09LL 最吻合。本版本使用百度官方离线坐标转换工具处理显示位置，原始 GPS 不变；vivo Provider 的普遍坐标基准和其他地区照片尚未验证，地图显示结果仍需用户核验。

## 本地构建

使用 Gradle 8.6、Android Gradle Plugin 8.4.2、compileSdk 34。地图组件和坐标工具使用百度 Maven 依赖 `BaiduMapSDK_Map:9.0.0` 和 `BaiduMapSDK_Util:9.0.0`；复用 `../map002-provider-probe/src` 中原有数据读取和筛选源文件。

在 Git 忽略的 `local.properties` 中设置 Android SDK 路径及 `baidu.map.ak`，缺少 AK 时构建会明确报错。AK 会被打入 APK，APK 不是保密存储；请配置百度控制台的包名和签名限制，不要将本地配置或签名密钥提交 Git。首次打开地图须阅读并同意百度隐私说明；SDK 初始化发生在同意后。

国内地图非商业服务及境外底图计费边界、实机结果和未验证项见 `../docs/research/MAP-003B-baidu-progress.md`。旧 MapLibre / 天地图实现可从 Git 历史基线 `754bb2f` 找回。
