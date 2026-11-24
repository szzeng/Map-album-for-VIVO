# MAP-001 V2 · 设备清单

调查日期为 2026-11-24，时区为 Asia/Shanghai。设备和系统数据来自本机 ADB 的只读查询。

| 项目 | 实机结果 |
| --- | --- |
| Windows / ADB | Windows 10.0.26300，ADB 1.0.41，platform-tools 37.0.0-14910828 |
| 连接 | USB 设备状态 `device`，`adb -d` 可执行查询 |
| 制造商 / 型号 / 设备代号 | vivo / V2502A / PD2502 |
| Android / API Level | Android 17 / API 37 |
| 系统展示名称 | `ro.vivo.os.build.display.id=OriginOS 7` |
| vivo 内部属性 | `ro.vivo.os.name=Funtouch`，`ro.vivo.os.version=17.0` |
| vivo 产品版本 | `PD2502E_A_17.1.13.6.W10.V000L1` |
| 安全补丁 | 2026-10-01 |
| Build Fingerprint | `vivo/PD2502/PD2502:17/CP2A.260605.016_V000L1/261119011022:user/release-keys` |

`MAIN` 和 `APP_GALLERY` 解析到 `com.vivo.gallery/com.android.gallery3d.vivo.GalleryTabActivity`。通用 `VIEW image/jpeg` 由文件管理器响应，原生相册包名以相册分类的结果和已装包清单交叉确认。

系统 `media` authority 属于 `com.android.providers.media.module`，由 `com.android.mediaprovider` APEX 提供。系统 Photo Picker 为同一 APEX 内独立的 `com.android.photopicker`。设备还安装了 `com.vivo.base.gallery` 和 `com.vivo.widget.gallery`。Binder 清单含 `media.features`、`media.parameters`、`media.vivocamera`、`vivomediametrics` 和 `location`，本次清单与足迹读链均未提供证据把它们认定为第三方照片 GPS 服务。

ADB、Java 20.0.1 和 Python 3.12.10 已可用。未发现现成 JADX，使用官方 1.5.7 便携包并核对发布 SHA-256。取证脚本保留相关注册输出，省去 USB 序列号和无关应用。APK 读取限于 `pm path` 返回的包文件。

- [ADB 清单与 Provider 注册原始输出](evidence/A-adb-inventory.txt)
- [APK 双端 SHA-256 和 JADX 摘要](evidence/B-apk-hashes.txt)
