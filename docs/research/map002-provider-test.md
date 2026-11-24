# MAP-002：vivo Provider 最小实机探针（进行中）

日期：2026-11-24。测试设备 vivo X300 Pro / V2502A，Android 17（API 37），构建 PD2502E_A_17.1.13.6.W10；应用 target SDK 34，以普通应用 UID 执行。源码及构建方式见 `map002-provider-probe/`。

## 已执行：空条件查询

安装应用声明 `com.android.gallery3d.permission.GalleryDataProvider`；应用检查到权限已授予，Provider 可解析、已导出，读取权限为上述权限。对 `content://com.android.gallery3d.dataprovider/mediaextra` 执行 `1=0` 只读查询，返回非空 Cursor、0 行。返回列中包含 `_id`、`latitude`、`longitude`、`origin_date_taken` 和 `generation_modified`。仅检查结构，不读取图库行和真实坐标。

这证明此设备当前版本可由普通应用打开该查询入口；不证明返回有效 GPS、媒体 ID 对应关系、时间语义、第三方授权边界或接口稳定性，不能据此决定采用。

## 合成 JPEG 验证

应用在私有缓存中生成带已知 GPS 和拍摄时间的 JPEG，写入其自身创建的 MediaStore 项；查询限定为该项目的媒体 ID。实机返回同 ID 行，坐标与测试值相符，拍摄时间字段非空。应用日志只记录比较结果，不输出坐标或路径。清理结果为删除 MediaStore 项 1 条、临时文件删除成功。测试仅覆盖一个 JPEG；未比较拍摄时间具体值，也未证明接口在其他照片、系统版本或重启后稳定。

## 待完成

对照 MediaStore 和已知拍摄时间的具体值，扩展验证格式与重启稳定性。重启后可用性、授权边界及跨版本稳定性亦未验证。不能因为空查询成功就认定可用。
