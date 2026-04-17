# NikonCopy App 开发日志

## 项目目标
Xiaomi 14 (HyperOS, Android 16, SDK 36) 通过 OTG 接 Nikon Z f，把相机里的 NEF / JPG / MP4 拷贝到手机指定目录。两个界面：主页（拷贝全部 / 增量拷贝两个大按钮 + 进度条 + 速度）、设置页（保存目录、默认相机目录、清空缓存）。Material 3 明亮风格。

前置条件已由 `DEBUG_LOG.md` 解决：USB-C → USB-A → USB-C 物理拓扑 + 恢复 `com.android.mtp` 后，相机稳定连接，系统文件管理器可见。

## 当前状态 (2026-04-17)

| 模块 | 状态 |
|---|---|
| Gradle / Compose 项目骨架 | ✅ |
| 主页 + 设置页 UI（Material 3 明亮） | ✅ |
| SAF 路径递归拷贝 + 进度 | ✅ 14-17 MB/s 跑通 |
| 拷贝引擎按 mode 分流（ALL / INCREMENTAL） | ✅ |
| 「同名跳过」逻辑 | ✅ |
| 「manifest 持久化清单」实现增量恢复 | ✅ |
| EXIF DateTimeOriginal 提取（含 NEF） | ✅ `exifT=27-60ms`，NEF 和 JPG 均提取成功 |
| `setMtime` via `Os.utimensat` 反射 | ❌ Android 16 block `setHiddenApiExemptions` + `utimensat`；**确认不可用** |
| `MediaStore.DATE_TAKEN` 写入 — SAF 路径 | ❌ SecurityException（SAF 创建的文件 owner 不是我们）|
| `MediaStore.DATE_TAKEN` 写入 — MediaStore.insert 路径 | ⚠️ **代码已切换，待验证** |
| 默认相机目录持久化 + 自动复用 | ✅ |
| **MtpDevice 直连路径 (PtpClient + force claim)** | ❌ Android 16 blocked → `ENABLE_DIRECT_PTP=false` |
| **Enrichment pipeline (off PTP critical path)** | ✅ 代码已就绪（direct path 内用 Channel pipeline） |
| **MediaStore.insert 目标路径切换** | ⚠️ 代码已实装（当目标在 Pictures/DCIM/Movies 下时生效），**待验证** |

## 项目架构

```
nikon/
├── app/build.gradle.kts                        AGP 8.7.3 / Kotlin 2.0.21 / Compose BOM 2024.10.01
├── app/src/main/AndroidManifest.xml            含 USB intent-filter (VID=1200) + dataSync FGS
├── app/src/main/res/xml/usb_device_filter.xml  Nikon VID 过滤
└── app/src/main/java/com/garag/nikoncopy/
    ├── MainActivity.kt                          NavHost 入口
    ├── ui/
    │   ├── HomeScreen.kt                        两大按钮 + 进度卡 + 缓存卡
    │   ├── SettingsScreen.kt                    保存目录 / 默认相机目录 / 清空 + 帮助卡
    │   └── theme/{Theme,Color,Type}.kt          Google 蓝 + 白底
    ├── data/
    │   ├── SettingsRepository.kt                DataStore 封装：destinationUri / sourceUri / cacheRecord
    │   └── ManifestStore.kt                     append-only 文本文件存"已成功"清单
    ├── copy/
    │   ├── CopyState.kt                         sealed class: Idle/Scanning/Running/Done/Failed
    │   ├── CopyMode.kt                          ALL / INCREMENTAL
    │   ├── CopyEngine.kt   (826 行)             两套 flow:
    │   │                                          - copyFlow      : SAF 源
    │   │                                          - copyFlowDirect: PtpClient 源
    │   │                                        共用 enrichDestination + MtimeUtil
    │   └── CopyService.kt                       前台服务，先 try direct → fallback SAF
    ├── viewmodel/CopyViewModel.kt              绑定 CopyService，暴露 state/destination/source
    └── mtp/
        ├── PtpClient.kt   (435 行)              裸 PTP 客户端
        │                                          - force claimInterface
        │                                          - OpenSession / GetStorageIDs / GetObjectHandles
        │                                          - GetObjectInfo / GetObject + readDataStream
        │                                          - ptpClassReset (USB control transfer 0x21/0x66)
        └── NikonDirect.kt                       USB perm 流程 + PtpClient 工厂
```

## 关键设计决策

1. **不重写 PTP 协议从零开始** — 先用 SAF / `MtpDocumentsProvider` 走通；速度不达标后再加 `PtpClient`。
2. **两条源路径并存** — `copyFlow` (SAF) 和 `copyFlowDirect` (PtpClient) 共享同一套 `enrichDestination` (EXIF + mtime + DATE_TAKEN)。`CopyService.start` 先尝试 direct，失败回退 SAF。
3. **同名跳过 + manifest 双过滤** — `ALL`: 跳过目标已存在；`INCREMENTAL`: 跳过 (目标已存在 ∪ manifest 已记录)。手动删除的不会重新导入；上次中断的会被补传。
4. **mtime 设置走 reflection 后门** — `Os.utimensat` 在 SDK 35+ 是 `@hide`，用 `setHiddenApiExemptions("L")` + reflection 调；`/proc/self/fd/N` 路径绕过 SAF 文件不属于本进程的限制。
5. **DATE_TAKEN 双保险** — `MediaScannerConnection.scanFile` 回调 + `queryMediaUriByPath` fallback；同时 update Images / Files 两个 collection。
6. **USB intent filter** — `<usb-device vendor-id="1200" />`，让用户插相机时系统弹"用 NikonCopy 打开"，勾选后 USB 权限自动批准、`force claimInterface` 顺利。

## 调试方法

### 设备 / 网络
- Xiaomi 14 `houji`，Android 16 SDK 36，`adb` 序列号 `a1af6bc9`
- WiFi adb：`adb connect 192.168.124.19:34043`（端口动态，每次"无线调试"页查；首次需 `adb pair <配对IP:配对PORT>` 输入 6 位码）
- Mac 网段 `192.168.124.10/24`，与手机同子网

### 构建
- `gradle` 系统级 9.4.1，wrapper 固定 8.10.2（AGP 8.7.3 兼容）
- `ANDROID_HOME=/opt/homebrew/share/android-commandlinetools`，platform-35 + build-tools 35.0.0
- 命令：`./gradlew --no-daemon :app:assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk`

### 安装注意
- HyperOS 默认拦截 adb install (`INSTALL_FAILED_USER_RESTRICTED`)
- 必须先在「开发者选项 → USB 安装」打开（已开）

### Logcat 过滤
关键 Tag:
- `CopyEngine` — 流程 + 计时 + DATE_TAKEN 验证
- `CopyService` — direct/fallback 决策
- `PtpClient` — PTP 命令收发 + 端点信息
- `NikonDirect` — USB 权限 + 设备发现
- `MtimeUtil` — `setMtime` 验证 (含 `Os.fstat` 读回比对)

抓取一次会话日志：
```bash
adb shell am force-stop com.garag.nikoncopy && \
adb logcat -c && \
# 用户操作: 启动 app + 点拷贝全部 + 等"完成"
adb logcat -d | grep -E 'CopyEngine|CopyService|PtpClient|NikonDirect|MtimeUtil|DATE_TAKEN'
```

## 实测观察 (本轮)

### 实验 1: SAF 路径基线
- 14 MB/s 稳定（4 文件、JPG + NEF）
- 文件 mtime 改成拍摄时间（推断成功；JPG 在 Xiaomi 相册按拍摄时间排）
- NEF 在 Xiaomi 相册排序错误 — 可能 DATE_TAKEN 没写入或 mtime 反射失败

### 实验 2: PtpClient 直连第一次启动
日志（关键行）：
```
PtpClient: PTP endpoints: in=ep129 maxPkt=1024 out=ep2 maxPkt=1024
                                                ↑
                                      USB 3.0 SuperSpeed 确认（HS=512）
PtpClient: OpenSession → SessionAlreadyOpen; issuing PTP class reset
PtpClient: PTP class reset rc=0
PtpClient: OpenSession OK after reset
PtpClient: PTP session opened
CopyService: using DIRECT PTP path
CopyEngine: [direct] mode=ALL existingNames=0 manifestNames=0
CopyEngine: [direct] PTP listFiles failed
CopyEngine: java.io.IOException: PTP data: header -1 != 12
            at PtpClient.readData(PtpClient.kt:335)
            at PtpClient.getStorageIds(PtpClient.kt:157)
```

**`bulkTransfer` 在 readData 里返回 -1（超时）。** 即使 OpenSession 拿到了 OK，紧接着 GetStorageIDs 的数据阶段拿不到响应。

### 实验 3: 第二、三次启动
- OpenSession 阶段直接 ANR：`MIUIScout App` 报 `sendCommand at PtpClient.kt:305` 卡 30 秒
- bulkTransfer 在 OUT 方向也卡（命令都送不出去）
- 用户感知为"闪退"

### 144 MB/s 单文件速度的真相
用户截图显示峰值 144 MB/s，实际是**稍早一次 SAF 路径**的瞬时峰值（splice 偶尔生效），平均仍然 ~14 MB/s。文件之间的"空白期"是 enrichment（EXIF 解析 + 反射 setMtime + scanFile + DATE_TAKEN update）卡在 PTP 主循环上。

## 已知问题

### 问题 #1: enrichment 卡 PTP 主循环（已写好 fix，未验证）
**已改**：`copyFlowDirect` 把 enrichment 拆到独立 consumer 协程（Channel）。PTP 生产者拷完一文件就立刻 `enrichQueue.send` 然后处理下一个。
**未验证**：因为问题 #2 阻断了 PtpClient 路径。

### 问题 #2: PtpClient 在 PTP 类专用 reset 后端点死锁 ❗ **当前阻断**
**现象**：
- `controlTransfer(0x21, 0x66, 0, intf.id, ...)` 返回 rc=0 (成功)
- 紧接着 OpenSession 也 OK
- 但 GetStorageIDs 的 readData 阶段 bulk-in 返回 -1

**推测原因（按可能性排）**：
1. 类 reset 把相机端 PTP 状态清了，但**主机端 (xHCI/dwc3) 的 bulk endpoint pipe 里还有 MtpDocumentsProvider 留下的旧数据**。OpenSession 命令实际被相机收到了响应，但响应被旧数据"挤"在 pipe 后面；我们读到的 OK 可能是旧响应；GetStorageIDs 真正的响应永远到不了。
2. 类 reset 后相机需要重新协商 endpoint state，仅 150ms sleep 不够
3. **`com.android.mtp` 的 MtpDevice 实例被强夺 interface 后陷入异常状态**，可能在 race 里又重新发了 PTP 命令把状态搅乱
4. 类 reset 把 device 端事件队列清了但 host 端没同步，下一个事务的 transactionId 与相机预期不匹配

**未尝试的修复方向**：
1. 在 PTP 类 reset 之后，对 bulk in/out 各发一次 USB CLEAR_FEATURE(ENDPOINT_HALT)：
   ```kotlin
   connection.controlTransfer(0x02, 0x01, 0x00, ep.address, null, 0, timeout)
   ```
   清掉 endpoint 上的 stall + 残留数据
2. 完全跳过类 reset，按 `ptp_keeper.c` 的做法把 `SessionAlreadyOpen` 当成 OK 接收下去（已有此分支但被类 reset 抢先）
3. 类 reset 之后做一轮"读到空"的 drain：循环 bulkTransfer(bulkIn, ...) 短超时直到返回 0/超时，把残留 byte 吃掉
4. 用 USBDEVFS_RESET ioctl 做完整 device reset（但这会断开 device，需要重新 openDevice）

### 问题 #3: NEF 在 Xiaomi 相册排序仍然错误 (待验证)
SAF 路径下，JPG 排序对、NEF 不对。怀疑链：
- MediaScanner 的 native EXIF parser 不识别 NEF → DATE_TAKEN 是 NULL
- 我们手动 update DATE_TAKEN 是否真的写进去了 — **本轮未抓到验证日志**（PtpClient 阻断了流程，没走到 SAF fallback）
- 即使 DATE_TAKEN 写进去了，Xiaomi 相册可能查 DATE_ADDED 或者有自家缓存

**下一轮要验证的关键日志**：
- `enrich <name>: capture=<ms> exifT=<ms> mtimeOK=true/false mtimeT=<ms>` — EXIF 是否解析出来、mtime 反射成功否
- `MtimeUtil setMtime VERIFIED|MISMATCH: target=<sec> actual=<sec>` — Android 16 上 hidden API 是否还能用
- `DATE_TAKEN <name> uri=... rows=N wrote=<ms> read=<ms> verify=OK|FAIL` — MediaStore 真的持久化了吗

## 测试方法 (用户操作流程)

1. Mac 端：`adb connect 192.168.124.19:<port>`（端口看手机"无线调试"页，每次重启会变）
2. 手机端把当前 NikonCopy 完全杀掉（任务卡片清掉）
3. 我端：`adb shell am force-stop com.garag.nikoncopy && adb logcat -c`
4. 用户重新启动 app + 点拷贝按钮，跑完看到"完成"
5. 用户回复"ok"
6. 我端：`adb logcat -d | grep -E '...'` 抓日志

## 历史交付

| 版本 | 关键改动 | 状态 |
|---|---|---|
| v1 | 项目骨架 + UI + SAF 拷贝 + DataStore | ✅ 跑通 |
| v2 | 同名跳过 (代替原 _1/_2 重命名) | ✅ |
| v3 | manifest-based 增量；默认相机目录；EXIF + DATE_TAKEN | ✅ 跑通；但 NEF 排序仍错 |
| v4 | FileUtils.copy + EXIF 通过 ExifInterface | ✅；速度 14 MB/s 没改善 |
| v5 | PtpClient 直连 + force claim | ❌ 端点死锁 (OpenSession 后 GetStorageIDs data read = -1) |
| v6 | + PTP 类 reset + 接受 SessionAlreadyOpen | ❌ 类 reset 把 OUT pipe 也搞坏 |
| v7 | + enrichment pipeline + DATE_TAKEN 读回验证 | ❌ 跑不到，被 PtpClient 问题挡住 |
| v8 | + withTransportRecovery (clearHalt + drain) | ❌ bulk OUT 在 init clearHalt 后仍 -1 |
| v9 | + USBDEVFS_RESET ioctl fallback | ❌ `Os.ioctlInt` 是 `core-platform-api`，Android 16 block |
| v10 | **根因确认**: hidden API 全被 block；NEF SecurityException 确认 | logcat 三个关键发现（见下） |
| v11 (当前) | 直连 PTP 默认关闭；destination 切 MediaStore.insert | ⚠️ **待验证** |

## 2026-04-17 根因总结（来自 v10 实测日志）

三个 Android 16 (SDK 36) 限制同时浮现，全部由 logcat 确认:

### 1. hidden API 反射在 Android 16 上全线崩溃

```
hiddenapi: Accessing hidden method Ldalvik/system/VMRuntime;->setHiddenApiExemptions(...)
  runtime_flags=CorePlatformApi, api=blocked,core-platform-api → DENIED
MtimeUtil: bypass failed → utimensat init failed → setMtime unavailable
```

连 `setHiddenApiExemptions` 本身都被拒了。所有依赖 hidden API 的路径（`Os.utimensat`、`Os.ioctlInt`）在 Android 16 上完全不可用。

### 2. DATE_TAKEN update 因 SecurityException 失败

```
DATE_TAKEN update threw for NZF_4393.NEF uri=content://media/external_primary/images/media/...
  SecurityException: com.garag.nikoncopy has no access to ... forWrite = true
```

通过 SAF `createDocument` 创建的文件，MediaScanner 索引后 MediaStore 行的 owner 不是我们。Android 11+ scoped storage 不允许非 owner update。JPG 不受影响因为 MediaScanner 原生解析 JPG EXIF 写入 DATE_TAKEN；NEF 的 DATE_TAKEN 由于 MediaScanner 不解析 NEF EXIF 始终为 NULL。

### 3. 直连 PTP bulk endpoint 在 force-claim 后硬错误

```
PtpClient: PTP endpoints: in=ep129 maxPkt=1024 out=ep2 maxPkt=1024
PtpClient: CLEAR_FEATURE(ENDPOINT_HALT) ep2 rc=0 / ep129 rc=0
PtpClient: drain bulk-in: reads=0 bytes=0
NikonDirect: first PtpClient ctor failed (PTP cmd 0x1002: bulkTransfer wrote -1 != 16)
```

即使 clearHalt 返回 rc=0，bulk OUT 仍 -1。`com.android.mtp` 通过自己的 fd 持有对设备的 PTP 会话；我们 force-claim 了 USB interface 但 camera firmware 级别的 session state 不属于我们；发出的命令被相机忽略或被 `com.android.mtp` 的 reader 竞争。

### 修法 (v11)

| 问题 | 修法 |
|---|---|
| NEF DATE_TAKEN | destination 切 `MediaStore.Images.Media.insert()` → 我们 own 该行 → update 无 SecurityException |
| mtime | 接受失败（DATE_TAKEN 覆盖排序需求）|
| 直连 PTP | `ENABLE_DIRECT_PTP = false`；代码保留（供 root/旧 Android 使用）|
| enrichment 串行 | copyFlowDirect 内 enrichment 已 pipeline 到独立协程（Channel），但需直连路径激活才生效 |

### EXIF 解析确认正常

```
enrich NZF_4393.JPG: capture=1768102718520ms exifT=32ms mtimeOK=false
enrich NZF_4393.NEF: capture=1768102718520ms exifT=40ms mtimeOK=false
```

`androidx.exifinterface` 1.3.7 在 NEF (TIFF-based RAW) 上成功提取 DateTimeOriginal，耗时 30-60ms。

## 下一步（由用户决定优先级）

A. **验证 v11 的 MediaStore.insert 路径** — 跑一轮 logcat 看 `[mediastore]` tag + `DATE_TAKEN ... verify=OK` → 如果 OK，NEF 排序修好
B. **速度仍是 14-17 MB/s** — MtpDocumentsProvider/AppFuse 的硬上限；要突破需 root + native PTP（ptp_keeper 路线）或等 Android 增加更快的 MTP API
C. **enrichment pipeline 对 SAF 路径也适用** — 当前只在 direct path 做了 Channel pipeline；SAF path 仍串行。可加同样机制减少文件间空隙
