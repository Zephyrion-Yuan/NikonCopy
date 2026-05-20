# NikonCopy

将 Nikon Z f 相机通过 USB OTG 接到 Android 手机上，一键把 NEF / JPG / MP4 文件拷贝到指定相册目录。同样支持 SD 卡 / U 盘等外接存储（任何被 Android 识别为可移动卷的 USB 大容量存储设备）。

针对 **小米 14（HyperOS · Android 16）** 调试，理论上兼容任何 Android 11+ 设备；具体行为见下文「已知限制」。

<p align="center">
  <img src="nikoncopy.svg" alt="NikonCopy icon" width="120"/>
</p>

## 特性

- **USB PTP 直连**：跳过系统自带的 `com.android.mtp`，避免 MTP 抢占相机会话，实测顺序读 160+ MB/s。
- **多设备配置**：每个相机 / 外接存储独立保存「源目录、源子目录、保存目录、选定文件格式、已成功导入清单」。识别到已知设备时自动切回它的配置。
- **拷贝全部 / 增量拷贝 / 修复日期** 三个主操作：
  - 拷贝全部：递归扫描源目录的选定子目录，按选定文件格式过滤后逐个拷贝；目标已存在的同名文件跳过（不会改名加后缀）。
  - 增量拷贝：跳过(目标已存在 ∪ 历史已成功导入过)。中断恢复友好。
  - 修复日期：用 EXIF DateTimeOriginal 同步写入 `MediaStore.DATE_TAKEN`，相册按拍摄时间正确排序。
- **外接存储约定优先扫描**：不会横扫整盘，先认 DCIM / Pictures / PRIVATE 等惯用目录，未命中才做有限 BFS，避免在 1 TB 固态硬盘上死等。
- **孤儿清理**：拷贝中断遗留的 `.pending-*` 文件和「行存在但磁盘文件丢失」的 MediaStore 残留，一键清理。
- **简洁 Material 3 UI**：每个 block 的说明文字都收在 (i) 子按钮里，主操作按钮拇指可及。

## 安装

直接下载预编译 APK：

[Releases → NikonCopy.v1.0.apk](https://github.com/Zephyrion-Yuan/NikonCopy/releases)

小米手机需要在「设置 → 我的设备 → 全部参数 → 连续点击 MIUI 版本」打开开发者选项，然后启用 **USB 安装**。

## 用法

1. 进入「设置」，给当前设备配置 **保存目录** —— 建议手机内置存储下你自己创建的相册文件夹（如 `Pictures/Nikon`）。系统相册会自动收录。
2. 把相机或 U 盘插到手机 OTG，授权 USB 访问。已知设备会被识别并自动切到对应配置。
3. **首次连接前**，请确认「直连加速设置」显示「已就绪」（需要 root；未 root 见下方说明）。
4. 回到首页，点 **拷贝全部** 或 **增量拷贝**。进度状态卡显示文件名 / 速率 / 已用时间。
5. 拷贝完成会自动跑一次「修复日期」。如果相册排序仍乱，可手动再触发。

## 直连加速设置

要让 USB PTP 直连吃满 160+ MB/s，需要两项系统改动：

1. 禁用 `com.android.mtp` DocumentsProvider，否则它会一直抢相机会话。
2. 把 `hidden_api_policy` 设为 `1`，解开 `Os.utimensat` 等隐藏 API。

应用会在 root 设备（Magisk / KernelSU / APatch）上自动写入这两项。**未 root** 的设备可以通过 adb 手动执行：

```bash
adb shell settings put global hidden_api_policy 1
adb shell pm disable-user --user 0 com.android.mtp
```

## 从源码构建

依赖：JDK 17、Android SDK platform-35、build-tools 35.0.0。

```bash
# 写入 local.properties（指向你本地的 Android SDK 根目录）
echo "sdk.dir=/Users/$USER/Library/Android/sdk" > local.properties

./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

## 已知限制（Android 16）

Android 16 收紧了 hidden-API 强制策略，以下能力在该版本上**不可用**：

| 能力 | 受影响功能 |
|---|---|
| `setHiddenApiExemptions` 完全禁用 | 无法绕过隐藏 API 检查 |
| `Os.utimensat` 反射调用被拒 | 文件 mtime 无法被设回拍摄时间，只能停在「现在」 |
| `Os.ioctlInt` 反射调用被拒 | 无法对 `com.android.mtp` 主动 reset USB 驱逐 |

因此目前 **mtime** 这一项在 Android 16 上不会被写入；`DATE_TAKEN` 仍通过 `MediaStore.insert` 正确写入，相册排序不受影响。

## 项目结构

```
app/src/main/java/com/garag/nikoncopy/
├── MainActivity.kt              NavHost (Home ↔ Settings)
├── ui/
│   ├── HomeScreen.kt            当前设备 chip / 进度卡 / 三大主按钮 / 上次拷贝
│   ├── SettingsScreen.kt        设备列表、源目录 / 保存目录 / 选定文件格式
│   └── theme/                   Material 3 主题
├── data/
│   ├── SettingsRepository.kt    DataStore 偏好持久化
│   ├── CameraProfile.kt         DeviceProfile（PTP / MSC）数据类
│   ├── DeviceDetector.kt        USB / StorageManager 设备扫描
│   ├── MediaProbe.kt            外接存储约定优先 + BFS 探测
│   └── ManifestStore.kt         「已成功导入」清单（每设备一份）
├── copy/
│   ├── CopyEngine.kt            核心拷贝循环（PTP 与 SAF 双路径）
│   ├── CopyService.kt           前台服务，挑选 PTP / SAF
│   ├── CopyFilter.kt            源子目录 + 文件格式过滤
│   ├── OrphanCleaner.kt         孤儿 / 残留行清理
│   ├── RootPatcher.kt           直连加速一键应用（root 路径）
│   └── NativeMtime.kt           C++ utimensat（绕过 hidden-API）
├── mtp/
│   ├── PtpClient.kt             原生 PTP over USB（force-claim + bulk）
│   └── NikonDirect.kt           USB 权限 + PtpClient 工厂
└── viewmodel/CopyViewModel.kt   绑定 CopyService、暴露 StateFlow
```

## License

私人项目。代码可自由学习参考，使用风险自负。
