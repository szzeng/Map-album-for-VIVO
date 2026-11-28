# MAP-NATIVE-VIEWER-001：vivo 原生相册队列验证

验证日期：2026-11-28。设备 V2502A（X300 Pro），Android 17；`com.vivo.gallery` 11.0.6.8（110006008）。结论仅适用于此设备和版本。

## 结论

后续验证发现，vivo 相册 11.0.6.8 的导出 `Gallery` Activity 接受一组**私有的 MediaStore 整数 ID 参数**，可以打开原生浏览页并指定顺序与起点。照片、视频和混合队列均已验证；混合队列以视频为起点需要采用下述非常规 MIME 类型。该接口并非公开 SDK 契约，不保证其他版本兼容。

用户从原生地图点开照片后，前台为 `com.android.gallery3d.vivo.NewPageActivity`，调用栈的上一层是 `com.android.gallery3d.vivo.footprint.ui.FootprintMapActivity`。已保存的相册 Manifest 将两者均标记为 `exported=false`，因此普通第三方应用不能直接启动它们。系统 activity dump 只显示内部 Intent 有 extras，未揭示队列协议。

## 入口检查

设备 `dumpsys package com.vivo.gallery` 及相册 Manifest 均显示，导出的 `com.android.gallery3d.vivo.share.ui.ShareAlbumEntranceActivity` 注册了 `ACTION_SEND_MULTIPLE`，包含 DEFAULT 类别及 `image/*`、`video/*`。该 Activity 的标签为共享相册。导出的 `com.android.gallery3d.app.Gallery` 注册了多种单项 `ACTION_VIEW` MIME 类型，后续队列测试见下文。

`QUICK_VIEW` 未见相册 Intent Filter；按照片和视频类型指定包查询均无匹配。Manifest 证据见 [相册清单](evidence/manifests/com.vivo.gallery/AndroidManifest.xml)。

## 实机调用

临时独立应用以 target SDK 34 运行，获得照片和视频读取权限，使用设备已有的 MediaStore URI。每组均传入两个 URI，使用 `ArrayList<Uri>`、含两个 URI 的 `ClipData` 和 `FLAG_GRANT_READ_URI_PERMISSION`；没有创建媒体文件或修改项目代码。探针测试后已卸载。

| 测试 | 请求 | 观察 |
| --- | --- | --- |
| 照片 | `SEND_MULTIPLE`, `image/*`，两张照片 | 启动 `ShareAlbumEntranceActivity`；界面提示需要开启共享相册，未进入连续浏览。 |
| 视频 | `SEND_MULTIPLE`, `video/*`，两个视频 | 启动 `ShareAlbumEntranceActivity`；未观察到连续浏览，未验证视频播放。 |
| 混合 | `SEND_MULTIPLE`, `*/*`，照片和视频 | 启动 `ShareAlbumEntranceActivity`；未观察到连续浏览。 |
| 照片 | `QUICK_VIEW`, 首项 `image/jpeg` | `ActivityNotFoundException`。 |
| 视频 | `QUICK_VIEW`, 首项 `video/mp4` | `ActivityNotFoundException`。 |
| 混合 | `QUICK_VIEW`, 首项照片的 `image/jpeg` | `ActivityNotFoundException`。 |

`QUICK_VIEW` 测试以首项 URI 和类型作为 Intent data/type，同时附 URI 列表与 ClipData；混合组不是 `*/*` 类型的独立测试。`SEND_MULTIPLE` 使用标准 `EXTRA_STREAM` 列表。启动成功只说明系统接受 Intent，不能证明相册读取、按顺序展示或播放了各项。共享相册未启用，也未执行上传或启用操作。

首轮测试中的共享相册入口不满足地图聚合队列连续浏览的目标。

## 后续验证：ACTION_VIEW 与私有 ID 列表

上段描述首轮 `SEND_MULTIPLE` / `QUICK_VIEW` 结论；以下是同日后续测试。

单独用照片 URI 发起 `ACTION_VIEW` 时，页面可左右浏览，但观察到的是当前照片所在目录的两张；即使增加跨目录 URI 的 `EXTRA_STREAM` 和 `ClipData`，列表仍是原来的两张。由此不能认为标准 URI 列表控制了队列。

通过分析当前版本导出 Activity 的代码，测试如下私有参数：

```java
Intent intent = new Intent(Intent.ACTION_VIEW);
intent.setClassName("com.vivo.gallery", "com.android.gallery3d.app.Gallery");
intent.setDataAndType(clickedUri, entryMimeType);
intent.putExtra("local_file_custom_flag", true);
intent.putIntegerArrayListExtra("local_file_custom_id_list", orderedIds);
intent.putExtra("local_file_custom_index", clickedIndex);
intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, orderedUris);
intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
// 向 ClipData 加入所有已授权、已有的 MediaStore URI。
intent.setClipData(clipData);
startActivity(intent);
```

探针附带了 `EXTRA_STREAM` 的 URI 列表，没有单独验证它或 ClipData 的每项是否为必要条件。`orderedIds` 在此设备传入 `ArrayList<Integer>`，`clickedIndex` 从 0 开始；ID 必须与当前可访问媒体及所选卷正确对应，整数溢出、多卷和大队列上限尚未验证。探针具有照片、视频读取权限，尚未验证仅部分授权场景。

实测两张来自不同目录的照片按指定顺序出现在同一原生页，左右滑动成功；点击第二张的模拟调用以第二张作为起点，原生收藏、详情、编辑等操作栏可见。用户确认此页面符合所需界面。两个视频的队列可以左右滑动；照片加视频的队列，以照片为起点和以视频为起点均已验证双向切换，并能播放视频到结束。测试各组均为两项，不能据此推断大队列上限。

单以包名解析的 `ACTION_VIEW video/mp4` 无匹配，测试通过显式指定导出的 `Gallery` 组件打开。纯视频队列使用 `video/mp4`。对于混合队列，即使点击的是视频，测试也将该视频 URI 与 `image/jpeg` 入口类型组合：在本机相册中照片与视频才都能正确显示和播放。若以视频 URI 配 `video/mp4` 进入混合队列，该版本会将前一张照片识别成视频。这种 URI 与声明 MIME 不一致的调用是设备特定的试验结果，不能假定其他相册或系统会接受，也不能当作标准 Android 行为。

`ACTION_TO_GRID_PAGE` 携带普通 URI 列表的尝试打开空白页，未构成有效方案。另一个 `custom`/`ImagesUri` 分支显示不同的精简界面且未验证滑动，用户确认不符合目标。

以上均为独立临时探针验证，尚未接入“此处媒体”或更改 Provider；私有参数及 MIME 处理的兼容性、授权回收、数据缺失和大队列仍须在正式实现前处理。
