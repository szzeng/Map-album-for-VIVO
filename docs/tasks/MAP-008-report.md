# MAP-008 APK 体积分析与验证记录

状态：ARM64 候选包已构建并在 X300 Pro 覆盖安装；功能仅做了部分实测，未完成完整验收。

## 体积对比

APK 条目按 ZIP 中未压缩大小统计；APK 总大小为实际文件大小。资源为 res 和 resources.arsc 之和。Assets 同时列出 ZIP 内压缩后的大小。基线在构建前通过 ZIP 目录读取；Gradle 构建清理了原输出目录中的基线 APK，以下保留了构建前的测量值。

| 项目 | 0.4.0 字节 | 0.4.1 字节 |
| --- | ---: | ---: |
| APK | 114,091,000 | 53,904,795 |
| arm64-v8a（5 个库） | 20,801,014 | 20,801,014 |
| armeabi-v7a（5 个库） | 14,369,964 | 0 |
| x86（5 个库） | 22,502,073 | 0 |
| x86_64（5 个库） | 22,858,977 | 0 |
| DEX | 7,562,360 | 7,443,128 |
| 资源 | 338,769 | 118,049 |
| Assets（未压缩 / ZIP 中） | 32,809,498 / 25,455,786 | 32,809,498 / 25,455,786 |

APK 减少 60,186,205 字节，约 52.75%。基线最大文件是四个 ABI 的地图库（分别 20,522,272、20,200,344、18,442,752、12,730,624 字节）；其后是百度地图 assets 中的 map.rs、mapext.rs 和 DEX。地图 assets 仍占 ZIP 中约 24.28 MiB，保留以避免破坏 SDK 动态加载。

## 构建与依赖

根据后续要求，Release 只保留 arm64-v8a，不支持仅 32 位 ARM 的设备；启用 R8 和资源压缩，Debug ABI 配置未改。版本为 0.4.1 / 6，包名不变。APK 含 ARM64 地图、base、C++、tiny_magic、定位五个库。Search 和 Util AAR 不含独立原生库；AAR 中 proguard.txt 均为空。保留规则依据[百度地图](https://lbsyun.baidu.com/docs/android?title=androidsdk%2Fguide%2Fcreate-project%2Fandroidstudio)及[定位](https://lbsyun.baidu.com/docs/android?title=android-locsdk%2Fguide%2Faddition-func%2Fobfuscation)文档，未保留应用全部代码。

定位库保留符号配置未修改：未找到适用于本版本的官方剥离要求，也没有可用 strip 工具进行可靠的前后测量，故符号本身的增量未知。两个定位库总计 654,986 字节，这是保留配置下的库总大小，并非符号增量。

## 验证与限制

- JVM 数据层、聚合及搜索脚本通过；Release 构建和资源压缩成功。默认 512 MiB Gradle 堆不足，使用 3 GiB 堆构建。
- 百度 SDK 引用了缺失的 OkHttp DNS 类型；按照[百度定位更新日志](https://lbsyun.baidu.com/docs/android?title=android-locsdk%2Ftheupdatelog)补充 Release OkHttp 3.13.1 后，类型检查警告不再出现。R8 仍报告百度定位和 common 库的 stack map 警告。
- apksigner 验证 v2 签名成功，新旧证书 SHA-256 均为 `9f3aa4053278ecd6e91c970459ad074beb7d78a436829cab5855126dc8cd2a68`。X300 Pro 上从 0.4.0 覆盖安装成功，首次安装时间保留；最终 ARM64 包再次覆盖安装成功。
- X300 Pro 上观察到地图、定位标记、照片和视频聚合缩略图，以及原生相册 154 项队列和切换；可打开视频并触发播放。搜索联想、POI 和历史在此前双 ABI 候选包上验证。应用户要求停止进一步逐项检查，内置预览回退等功能未验证，不能宣称完整验收。
- 构建前通过 ZIP 目录统计而非 APK Analyzer；未验证运行时 JNI 依赖的加载，也未完成符号增量测量。以上事项仍待完成。
