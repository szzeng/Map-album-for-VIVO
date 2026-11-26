# MAP-004 UI 实施与验证记录

日期：2026-11-26。范围为受限样本 UI，不代表正式全图库数据层验收。

- Debug APK 构建和 X300 Pro 安装成功；合并清单未包含后台定位权限。百度定位 SDK 首次构建产生 D8 stack-map 警告，构建成功。
- 用户同意更新后的隐私说明，并选择 16 张测试照片。实机检查了地图收起与区域面板展开、三列网格、定位按钮随面板上移；聚合标记打开四张照片的「此处照片」，预览范围为该组，返回后保留组模式。
- 区域网格进入内部预览，检查了序号及上一张/下一张边界，切换后打开 vivo 相册，返回预览及展开的地图面板。未对原生相册内显示的原图内容做人工逐图核对，也未完成双指缩放与长列表滚动验收。
- 主动定位首次超时。手机日志显示 SDK 因缺少 `android.permission.ACCESS_WIFI_STATE` 抛出异常；用户确认增加该普通权限后，重新构建安装，主动定位成功，地图移动并显示蓝点。完成后系统服务列表未显示本应用的百度定位服务或定位远程进程。精度圈显示与数值、定位精度、更多授权和失败场景仍需验收；百度控制台的定位服务配置未单独检查。
- 保留 `IDENTITY_UNVERIFIED` 边界和广州附近已验证范围过滤；没有启用全图库读取。测试报告不记录样本图片、私有路径或精确坐标。
- 未完成：截图交付；定位精度圈、授权拒绝、永久拒绝、大致位置、系统开关关闭及海外坐标；旋转、进程回收、地图快速移动与全部视觉状态；原生相册中人工核验准确照片；SDK AK 服务状态及大样本性能。

参考：[百度 Android 定位 SDK 集成](https://lbsyun.baidu.com/docs/android?title=android-locsdk%2Fguide%2Fcreate-project%2Fandroid-studio)、[百度定位 SDK 单次定位](https://lbsyun.baidu.com/index.php?title=%E5%85%A8%E7%90%83%E5%AE%9A%E4%BD%8D)、[百度定位 SDK 错误码](https://lbsyun.baidu.com/docs/android?title=android-locsdk%2Fguide%2Faddition-func%2Ferror-code)。
