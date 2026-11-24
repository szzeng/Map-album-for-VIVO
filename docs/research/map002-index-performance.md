# MAP-002 · 索引性能与 SQLite 能力（进行中）

2026-11-24，vivo X300 Pro / Android 17。数据均为应用自行创建的合成值，不涉及用户照片。

## SQLite 实机基础验证

应用以 Android `SQLiteDatabase` 执行 `CREATE VIRTUAL TABLE ... USING rtree`，系统 SQLite 返回 `no such module: rtree`。因此不能把系统 SQLite R*Tree 当作本机可用前提；也不能由此推断所有 Android 设备均不支持。

采用普通 SQLite 表、经纬度 B-tree 进行小样本试验：经度跨 ±180° 的两个范围、普通范围、精确边界、不同卷的相同媒体 ID 均通过；队列事务回滚及关闭数据库后重开均通过。`EXPLAIN QUERY PLAN` 显示按经度索引进行范围搜索，再按整数主键关联媒体表。B-tree 单维范围可能扫描很多候选，结果需二次精确过滤，不能根据这三个点推断大图库效率。

## 尚未实测

这不是照片索引性能测试。1,000 / 10,000 张首次索引耗时、EXIF P50/P95、无变化时原图打开次数、增量耗时、峰值内存、CPU、电量、数据库大小和扫描暂停、进程死亡后恢复均未测。不能用此前 Windows 合成 EXIF 结果或本次 SQLite 小样本冒充实机索引性能。
