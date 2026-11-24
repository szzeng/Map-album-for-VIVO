# MAP-001 V2 · Provider 与权限

## 调查结果

| 组件和 authority | 实机注册及静态访问条件 | GPS 复用判断 |
| --- | --- | --- |
| `GalleryDataProvider` / `com.android.gallery3d.dataprovider` | `exported=true`，读写继承 `com.android.gallery3d.permission.GalleryDataProvider`，运行时注册为 `normal` | 最重要的候选。静态查询分支直接到 `gallery-mediaextra.db` 的 `MediaExtraTable`。普通应用授权、非空行、照片关联及稳定性仍待验证 |
| `GalleryOpenProvider` / `com.vivo.gallery3d.mediaextra` | `exported=true`，Provider 节点无统一权限。查询通过 `BaseProvider` 分派并检查 DAO 读权限。MediaExtra DAO 检查 `com.vivo.gallery3d.permission.MediaExtraProvider`，实机为 `signature\|privileged` | 已查明一般第三方应用无法满足该路由的权限 |
| `GalleryOpenProvider` / `com.android.gallery3d.photopicker` | `xy.a` 的读写检查要求 `com.android.gallery3d.permission.GALLERY_PHOTOPICKER_PROVIDER`，相册 Manifest 为 `signatureOrSystem` | 地点查询相关分支属于系统选图适配 |
| `GalleryCloseProvider` / `com.vivo.gallery3d.file.meta` 等 | `exported=false` | 仅相册及具有相同 UID 等相应内部身份的调用者可用 |
| `GalleryMediaProvider` / `com.vivo.gallery.media.provider` | `exported=true`，读写继承 `com.vivo.gallery3d.permission.GALLERY_MEDIA_PROVIDER`，实机为 `signature\|privileged`。声明可发放 URI grant | 普通应用无图库枚举权限。单 URI grant 依赖提供方实际授权 |
| BaseGallery / `com.vivo.base.gallery.mediaextra` | `exported=true`，同一 `MediaExtraProvider` 特权权限 | 同上 |
| Photo Picker / `com.vivo.gallery3d.mediaextraprovider` | `exported=true`，Manifest 绑定 `com.vivo.gallery3d.permission.MediaExtra`，级别为 `signatureOrSystem` | 普通应用无法满足该扩展路由权限 |
| MediaProvider / `media` | 标准 MediaStore。有照片目录、普通元数据和 URI 访问机制 | 目标版本外部查询坐标列会被置空，详见 [标准接口](android-mediastore-analysis.md) |

两条指向媒体扩展数据的路由使用不同的权限机制。`GalleryDataProvider.query()` 的 case 0 调用 `com.android.gallery3d.vivo.mediaextra.d.w()`。该方法在共享 helper 可用时执行 SQLite 读查询，helper 未初始化则返回 `null`。`GalleryOpenProvider` 则走 `com.vivo.commonlibrary.database.p.query()`、`v()`、`y()`，DAO 单独实施权限检查。经纬度列与扩展表结构属于静态证据。

## 有限实机验证

对 `GalleryDataProvider` 使用仅投影 `_id` 并且永远为假的查询。系统拟打开 Provider 时即拒绝 UID 2000，要求该 `normal` 权限。`content` 命令在错误时仍返回退出码 0，以日志中的 `SecurityException` 判定。ADB shell 的失败只证明当前 shell 身份被拒绝。Android 的 `normal` 权限通常随声明在安装时授予，普通应用的现场行为有待测试。其余已确认要求特权和不导出的入口依据保护声明及调用链判定。

缺少厂商对三方用途、用户授权边界、字段语义和跨版本稳定性的公开契约。要升级为可复用结论，需要 vivo 确认接口和授权方式，并在允许安装验证应用的隔离设备上用合成或明确授权的测试照片验证结果与身份边界。当前设备的授权和安装状态保持原样。

- [实机权限与空条件查询日志](evidence/C-adb-provider.txt)
- [主相册 Manifest](evidence/manifests/com.vivo.gallery/AndroidManifest.xml) · [基础相册 Manifest](evidence/manifests/com.vivo.base.gallery/AndroidManifest.xml) · [Photo Picker Manifest](evidence/manifests/com.android.photopicker/AndroidManifest.xml)
- [`normal` 和 `signature` 权限定义](https://developer.android.com/guide/topics/manifest/permission-element)
