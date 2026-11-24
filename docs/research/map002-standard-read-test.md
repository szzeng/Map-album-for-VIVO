# MAP-002 · Android 标准读取验证（进行中）

设备：vivo X300 Pro，Android 17 / API 37；探针 target SDK 34；2026-11-24。应用主动请求照片与位置权限，只访问自己生成并插入 MediaStore 的 JPEG。

## 单张 JPEG 实测

有位置访问权限时，以 `MediaStore.setRequireOriginal()` 取得流，由 AndroidX ExifInterface 1.3.7 读取，已知测试 GPS 和 EXIF 拍摄时间均匹配。以新建 URI 查询 MediaStore，返回 ID 匹配，`volume_name`、`mime_type`、`date_taken`、`date_modified`、`size`、`generation_added` 和 `generation_modified` 在这一行均非空。测试对象随后删除，删除返回 1；私有缓存文件删除成功。不包含真实图库信息。

这不证明其他媒体的字段都非空，也不证明撤销权限或部分照片选择的行为。AndroidX 版本固定并验证 AAR 的 SHA-256；构建工程位于 `map002-provider-probe`。

## 其他受控场景

无 GPS 的应用私有 JPEG 用 AndroidX 解析返回无坐标；删除应用自己插入的 JPEG 后，打开该 URI 抛出文件未找到异常。撤销探针的 `ACCESS_MEDIA_LOCATION` 后，再生成并读取它自己拥有的 JPEG，原始读取仍获得 GPS；测试后已恢复该探针的位置权限。这是自有媒体场景，不能用来推断对其他应用媒体的权限或脱敏行为，也不能把权限不足当作无 GPS。

## 尚未验证

HEIC、RAW、其他应用媒体在未授予及撤销位置权限时的行为、照片全量和部分选择及重新选择、卷变化、version 和 generation 变化。当前测试仅读取应用自身新建的照片，因此不能推断对其他应用创建的媒体的授权行为。DATE_TAKEN 的具体值和回退来源还未验证；DATE_MODIFIED 不能冒充拍摄时间。
