# MAP-001 V2 · Android MediaStore 能力

## 三种能力

| 能力 | 官方契约与本机证据 |
| --- | --- |
| 枚举有权限的照片 | 按具体 volume 查询 `Images.Media`，以 `_ID` 构造 `content://` URI，取得 `DATE_TAKEN` 等媒体元数据。`DATE_TAKEN` 可能缺失或由 Provider 推导，显示替代时间时应记录来源。实机 `gettype` 返回图片目录类型，空条件投影 `_id/datetaken/generation_added/generation_modified` 被受理。 |
| 直接查询 GPS 列 | 公共 `ImageColumns.LATITUDE/LONGITUDE` 从 API 29 废弃，文档定义外部可见结果为 `null`。实机 APK 中 `ModernMediaScanner` 有从 EXIF 填内部 GPS 的代码，`MediaProvider.queryInternal()` 则把外部调用的坐标投影替换为 `null as ...`。这解释了内部索引和公共查询能力的差异。实机只做空条件投影，未读取实际值。 |
| 检测新增和修改 | API 30 起以具体 volume 的 `getVersion()`、`getGeneration()` 及行的 `GENERATION_ADDED/MODIFIED` 同步。实机 `external_primary` 的只读 generation 查询返回 `17312317`，这是游标值，和照片数量无关。没有在设备上制造新增、修改或删除事件。 |

## 原始元数据读取

对于可访问的共享媒体，在适用版本申请 `READ_MEDIA_IMAGES` 或旧版读取权限，再单独征得 `ACCESS_MEDIA_LOCATION`。对具体 MediaStore URI 使用 `MediaStore.setRequireOriginal()` 请求原始字节，以 AndroidX `ExifInterface.getLatLong()` 解析。用户拒绝授权、位置已去除、资源不可用和照片原本没有 GPS 应保留不同状态。Photo Picker URI 属于用户选取授权，位置元数据需遵循对应系统能力与选取时授权。应保留 Picker 单独接入的能力判断，避免假定其 URI 与任意 MediaStore URI 可以互换。当前设备未安装验证应用，普通应用授权和原图链路依据官方契约和后续受控验证。

## 增量及权限变化

- 每卷检查 `getVersion()`，仅在同一 version 域比较 generation。合并卷 URI 适合浏览，增量状态按具体卷保存。
- `ContentObserver` 供合并触发，有时携带单项 URI 和删除标志。后台漏报、合并通知、集合 URI 或进程退出后，需在前台恢复及周期任务重查。
- 已删除记录无法通过查询现存行的 generation 获得。以明确的通知复核该项，再以周期性可见 ID 对账补漏。
- Android 14+ 支持全量、部分选择和拒绝。处理 `READ_MEDIA_VISUAL_USER_SELECTED`，在 `onResume`、授权回调与手动刷新重新查询可见集合。部分选择内容可能变化而权限级别和 generation 均未变化。`QUERY_ARG_LATEST_SELECTION_ONLY` 仅可在支持版本作为辅助，不能代替当前全集对账。

- [实机空结果及 generation 日志](evidence/C-adb-mediastore.txt) · [目标 MediaProvider 的 Manifest](evidence/manifests/com.android.providers.media.module/AndroidManifest.xml)
- [MediaStore API](https://developer.android.com/reference/android/provider/MediaStore) · [ImageColumns](https://developer.android.com/reference/android/provider/MediaStore.Images.ImageColumns) · [MediaColumns](https://developer.android.com/reference/android/provider/MediaStore.MediaColumns)
- [访问共享媒体和原始位置元数据](https://developer.android.com/training/data-storage/shared/media) · [ExifInterface](https://developer.android.com/reference/androidx/exifinterface/media/ExifInterface) · [ContentObserver](https://developer.android.com/reference/android/database/ContentObserver) · [部分照片权限](https://developer.android.com/about/versions/14/changes/partial-photo-video-access)
