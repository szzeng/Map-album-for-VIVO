# MAP-001 V2 · 足迹 GPS 索引与数据来源

## 从地图回溯数据

1. `FootprintMapActivity.I3()` 构建 `com.android.gallery3d.vivo.footprint.ui.b`。Fragment 的 `initData()` 以 `qx.c.f(..., 128)` 生成 `/cluster/{…}/baidumap` 的内部路径并创建 `c9.h`。`c9.h.w()` 加载 `ly.u` 地图集合。
2. `ly.u.q4()` 根据地图模式选用 `com.vivo.data_api.footprint.m.g()` 的数据读取器，`W3()` 取回并过滤含位置的 `qx.e`。`footprint.d.g()` 等先尝试已有内存数据，必要时查询媒体行。`footprint.b` 投影包含媒体 ID、媒体类型和时间。坐标读取按分支使用查询列，或调用 `mediaextra.f.K()` 按媒体 ID 批量查扩展位置。地图对象保留媒体 ID、内部媒体路径、经纬度、媒体类型和时间。内部路径是相册对象标识，外部 `content://` URI 仍需要另行验证关联。
3. `com.vivo.data_api.mediaextra.c.l()` 从图片文件的 EXIF 读取经纬度，并从元数据推导 `origin_date_taken_exif` 后放入 `MediaExtraEntry`。这解释了扩展坐标的一个来源。目标系统的 MediaProvider 自己也有 EXIF 解析和内部入库代码。当前照片库究竟走了哪个分支、各分支的覆盖率，以及地图中各点是否与原图一致，没有运行样本验证。

## 已定位的存储

| 存储 | 静态代码所证实的结构或行为 | 与照片关联及限制 |
| --- | --- | --- |
| `gallery-mediaextra.db`，版本 33 | `com.android.gallery3d.vivo.mediaextra.d$d` 的 `SQLiteOpenHelper` 创建 `MediaExtraTable`，有 `_id`、`latitude`、`longitude`、`_data`、`media_type`、`origin_date_taken`、`origin_date_taken_exif`、`generation_modified`。插入和更新触发器维护扩展表 generation，并有 generation 索引 | 地图读取存在按媒体 ID 查扩展坐标的分支。实际普通应用能否看到足够的有效记录并安全对应到当前卷的 MediaStore URI、时间字段含义和覆盖率均未验证。扩展表 generation 与标准 MediaStore generation 分属不同存储 |
| `geo.db`，版本 4 | `g9.a` 管理 `gps`、`location` 和 `local_config`。`gps` 保存坐标、唯一 `gpsuniqekey` 和 `location_id`，后者关联国家、省、市、街道等地址维度，通过索引支持反查 | `LocationQueryEngine.queryGpsByLocation()` 使用该库。`gps` 的键用于坐标与地址，表中没有逐照片 URI 或拍摄时间字段。照片位置缓存和逆地理编码缓存各自有用途 |
| `footprint_cluster_cache.bin` | `dy.a` 从相册的应用专属外部缓存目录加载 `MapClusterSnapshot`，`c9.h` 安排磁盘预热，`ly.u.a4()` 校验指纹后接入已聚类数据 | 静态证据支持存在可持久化的地图聚类加速路径。实际缓存文件是否生成及内容留存未查验 |

结构和读链来自已安装版本 APK。活跃数据库文件、地址、照片数据和真实坐标均未读取。可以确认 vivo 实现了可重复使用的 GPS 相关数据层和地图聚类缓存。用户照片库中的表行是否已建好以及可复用覆盖率，仍属未知。

- [主相册 DEX 锚点](evidence/B-dex-anchors.txt) · [定向代码位置与摘录](evidence/B-code-excerpts.txt) · [位置锚点的字节码调用引用](evidence/B-invoke-references.txt)
- [Provider 与第三方权限边界](provider-analysis.md)
