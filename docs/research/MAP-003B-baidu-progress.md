# MAP-003B 百度地图替换进度

基线 `754bb2fbe1d9b2e07061d2240d85ceca6ee6fed1`。修改仅在本地，未提交、未推送。此前 MapLibre 和天地图实现保留在 Git 历史中，未卸载旧应用或清理其数据。

## 接入与构建

- 地图组件 `com.baidu.lbsyun:BaiduMapSDK_Map:9.0.0`；坐标转换需要官方 `BaiduMapSDK_Util:9.0.0`；传递依赖 `base:9.0.0`、`common:1.0.43`。移除 MapLibre、天地图请求和 Key 入口；最终 Debug 依赖中无 MapLibre。
- 沿用 Gradle 8.6、AGP 8.4.2、compileSdk 34 和原 Debug 签名；干净 Debug 构建成功。百度 common 依赖在首次转换时产生 D8 stack map 警告，实机样本可运行，但未做全面兼容性验证。
- AK 由 Git 忽略的 `local.properties` 注入，独立无 AK 构建检查确认会报错；APK 本身包含 AK。用户确认其绑定的包名与本机 Debug SHA1 相符；Debug 探针鉴权回调 status=0，最终候选正常显示底图。Release 签名尚未配置。
- 首次进入须阅读隐私说明并选择同意后初始化；拒绝则退出地图。仅实机走过同意路径，未完成独立隐私合规审计或拒绝路径及 SDK 网络行为的专项验证。

## X300 Pro 样本验收

用户确认广州底图、道路文字、百度版权标识以及拖动和缩放正常，手机未使用代理/VPN。对三张广州附近真实照片比较原始坐标直接显示、WGS84 转 BD09LL 和 GCJ02 转 BD09LL，用户确认 WGS84 转换结果最吻合；缩略图、点击预览、重叠照片附近列表、清空重选及退出再进入正常。最终只使用百度地图的候选包复测得到用户确认。

最终样本显示采用百度官方 `CoordinateConverter.CoordType.GPS` 转为 BD09LL，Marker、范围和附近列表投影使用同一转换结果；原始数据不变，转换结果仅在内存使用。该证据只覆盖三张样本，不能证明所有 vivo 照片、设备或地区的原始坐标基准。当前为避免海外照片自动定位，验证版仅显示广州附近范围内的照片标记；范围外照片不自动定位、不显示标记。这是样本限制，并非完整的大陆／境外边界判断。

原数据层未修改：照片仅限用户明确选择的主卷样本，未新增全图库权限、EXIF 扫描或 GPS 数据库；MAP-002 的 `IDENTITY_UNVERIFIED` 门槛未解除。应用代码未添加照片、缩略图、照片 ID 或批量 GPS 上传；百度 SDK 实际网络行为未独立抓包验证。

## 权限、费用与待办

最终 APK 合并权限仅包含 INTERNET、ACCESS_NETWORK_STATE 和 vivo GalleryDataProvider 权限；无设备定位、全图库照片权限及百度后台服务。底图 SDK 的设备与网络信息处理需以[百度隐私政策](https://lbsyun.baidu.com/docs/pcsa?title=compliance%2Fopenprivacy)及实际网络审计为准。

[百度官方说明](https://lbsyun.baidu.com/docs/android?title=androidsdk%2Findex)称 Android SDK 境内服务对非商业用途免费。用户表示个人非商业且未充值，并要求继续广州城区验证；未申请或购买付费服务。百度[境外地图说明](https://lbsyun.baidu.com/docs/android?title=androidsdk%2Fguide%2Fcreate-map%2Fshowmap%2Fabroad)称境外底图无需额外配置即可访问，按请求网格计费；账户的具体开通、配额及扣费状态未独立核实，也未进行全球范围浏览测试。用户层面没有主动访问境外底图，但没有网络抓包证据证明 SDK 未预取任何境外网格。

待办：确认账户境外服务与收费状态；扩展至其他大陆地区前，设计可靠的地域和坐标策略并用更多照片验证；核查 SDK 网络行为、隐私拒绝及资源释放；配置并安全保存正式 Release 签名。当前三张样本不代表全图库、其他设备或生产发布验收。
