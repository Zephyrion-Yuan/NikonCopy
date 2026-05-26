# NikonCopy

USB OTG 把 Nikon Z f / SD 卡 / U 盘的 NEF / JPG / MP4 拷贝到 Android 手机。

测试环境:小米 14 / HyperOS / Android 16。兼容 Android 11+。

<p align="center">
  <img src="nikoncopy.svg" alt="NikonCopy icon" width="120"/>
</p>

## 功能

- 拷贝全部 / 增量拷贝 / 修复日期
- 多设备 profile:每台相机或外接存储独立保存源目录、目标目录、文件格式、已导入清单
- 同型号相机用 USB iSerial 区分;SD 卡用分区 UUID 识别
- 可选「拷贝目录结构」(不可逆)
- USB PTP 直连 160+ MB/s(需 root + 直连加速),未开走 SAF 约 14 MB/s

## 安装

[Releases](https://github.com/Zephyrion-Yuan/NikonCopy/releases)

## 使用

1. 设置 → 默认保存目录 → 选 `Pictures/Nikon` 之类的路径
2. OTG 接相机或读卡器,授权 USB
3. 首页「拷贝全部」或「增量拷贝」
4. 完成后「修复日期」

## 直连加速

```bash
adb shell settings put global hidden_api_policy 1
adb shell pm disable-user --user 0 com.android.mtp
```

root 设备应用自动执行。未开启走 SAF 路径,可正常使用。

## Android 16 限制

| API | 状态 | 影响 |
|---|---|---|
| `setHiddenApiExemptions` | 禁用 | 隐藏 API 不可访问 |
| `Os.utimensat` 反射 | 不可用 | 文件 mtime 写不回 |
| `Os.ioctlInt` 反射 | 不可用 | 无法 reset USB 驱逐 `com.android.mtp` |

`DATE_TAKEN` 通过 `MediaStore.insert` 正常写入,相册排序不受影响。

## 项目结构

```
app/src/main/java/com/garag/nikoncopy/
├── MainActivity.kt              NavHost (Home ↔ Settings)
├── NikonCopyApp.kt              Application
├── ui/
│   ├── HomeScreen.kt
│   ├── SettingsScreen.kt
│   ├── PickTreeContract.kt      SAF 选择器 contract
│   └── theme/
├── data/
│   ├── SettingsRepository.kt
│   ├── CameraProfile.kt
│   ├── DeviceDetector.kt
│   ├── MediaProbe.kt
│   ├── DestinationIndex.kt      .nikoncopy_index.json 管理
│   └── ManifestStore.kt
├── copy/
│   ├── CopyEngine.kt            PTP / SAF 双路径拷贝
│   ├── CopyService.kt           前台服务
│   ├── CopyFilter.kt
│   ├── CopyLayout.kt            扁平 / 结构模式路径计算
│   ├── OrphanCleaner.kt
│   ├── RootPatcher.kt           直连加速
│   └── NativeMtime.kt
├── mtp/
│   ├── PtpClient.kt             PTP over USB
│   └── NikonDirect.kt           USB 权限 + deviceKey
├── util/
│   └── LogCollector.kt          logcat 环形 buffer
└── viewmodel/CopyViewModel.kt
```

## License

私人项目,代码可自由参考。
