# HCT ADAS 最终方案与实施计划

> **文档状态：** 可执行规划版
>
> **适用平台：** Qualcomm SM4450 / Android 16
>
> **交付形态：** 当前目录内独立 HCTADAS Java/Gradle 工程，复用车机现有 UVC 摄像头底层能力
>
> **最低运行环境：** Android 13（API 33）

## 1. 最终结论

项目具备实现条件。车机已经存在可运行的 USB 摄像头方案：

- `hct/apps/USBCamera/HCTUSBCamera` 已完成 UVC 设备枚举、权限申请、预览和录制。
- `HCTUSBCamera/MainActivity.java` 已配置 MJPEG、1280×720，并通过 `onPreviewResult(byte[] nv21Yuv)` 输出 NV21 帧。
- `hct/apps/USBCamera/HCTUSBCamera/libs/libusbcamera.aar` 已包含 Java UVC 封装。
- `hct/device/libusbcamera` 已提供 `libUVCCamera.so`、`libuvc.so`、`libusb100.so` 等设备侧库。
- `hct/device/qssi/qssi.mk` 已将应用和 `libUVCCamera` 纳入目标产品。

因此，ADAS 不需要重新实现 USB 驱动或 UVC 协议，重点转为独立的帧分析、算法和报警状态机。

文档中的 CPU、RSS、误报率和实际报警效果仍需在目标设备与实际摄像头上验证，不能在验证前视为承诺指标。

## 2. 设计边界

### 2.1 必须实现

1. USB 摄像头持续采集和断连重连。
2. NV21 帧进入独立分析队列，不阻塞 UVC 回调线程。
3. 车辆检测、单目距离估计和 TTC 计算。
4. FCW、HMW、LDW、LVSA 四类功能的状态机。
5. SoundPool 固定报警音，TTS 作为辅助提示。
6. 摄像头参数标定和运行状态显示。
7. CPU、内存、帧率、延迟、温升和断流监控。

### 2.2 明确不做

- 不修改 AOSP、Vendor、HAL、SELinux 或 init。
- 不接入 CAN/LIN、毫米波雷达和超声波传感器。
- 不把 ADAS 逻辑直接耦合到录制 UI 和录像文件流程。
- 不宣称自动驾驶、制动控制或安全等级认证。

## 3. 最终架构

```text
USB 摄像头
    ↓
现有 libusbcamera / libUVCCamera
    ↓ NV21 预览帧
UVC Frame Adapter
    ↓ 有界队列，满载丢旧帧
JNI Frame Bridge
    ↓
libadas_core.so (C++17)
    ├── 图像缩放与预处理
    ├── NCNN 车辆检测
    ├── 单目测距与卡尔曼滤波
    ├── OpenCV-mobile 车道线处理
    └── FCW/HMW/LDW/LVSA 状态机
    ↓ 事件
ADAS UI / SoundPool / TTS / 运行日志
```

### 3.1 应用层模块

当前已在本目录建立独立 `HCTADAS` 应用工程，使用 Java，`minSdk=33`，`targetSdk=36`，并复制现有 `libusbcamera.aar` 到 `app/libs/`。后续算法和帧分析代码继续放在该工程内，不修改录制应用源码。

```text
HCTCarAdas/
├── settings.gradle
├── build.gradle
├── gradle.properties
├── app/
│   ├── build.gradle
│   ├── libs/libusbcamera.aar
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/hct/adas/MainActivity.java
│       └── res/values/
│
后续新增：
├── app/src/main/java/com/hct/adas/UsbCameraSource.java       # UVC 生命周期、权限、断连重连
├── app/src/main/java/com/hct/adas/FrameDispatcher.java       # 帧限流、队列和线程隔离
├── app/src/main/java/com/hct/adas/AdasNativeBridge.java      # JNI 接口
├── app/src/main/java/com/hct/adas/AdasService.java           # 前台运行和生命周期
├── app/src/main/java/com/hct/adas/AdasOverlayView.java       # 画框、车道线、状态提示
└── native/
    ├── CMakeLists.txt 或 Android.mk
    ├── adas_jni.cpp
    ├── adas_core.cpp
    ├── detector.cpp
    ├── distance_estimator.cpp
    ├── lane_detector.cpp
    ├── warning_state_machine.cpp
    └── calibration_store.cpp
```

### 3.2 帧处理约束

- UVC 回调只负责接收和投递，不执行模型推理。
- 使用固定容量的环形队列，默认容量 2；队列满时丢弃旧帧。
- 输入预览保持 1280×720，算法输入缩放到 320×192 或经实机测试后确定的等比例尺寸。
- 检测线程单线程运行，避免多线程导致 CPU 峰值和调度抖动。
- Native 侧复用图像缓冲区，禁止每帧重复分配大块内存。

## 4. 功能实现顺序

### 阶段 0：目标设备基线确认

**目标：** 明确软件、摄像头和系统策略边界。

检查项：

1. 确认 USB 摄像头 VID/PID、MJPEG 支持、实际分辨率和帧率。
2. 确认开机后 USB 权限是否自动恢复。
3. 确认 HCTADAS 是否需要平台签名、`persistent` 或产品预装。
4. 确认后台运行、音频焦点、GPS 权限和显示策略。
5. 记录目标设备的 ABI、CPU 核数、内存和温度基线。

**通过条件：** 摄像头可稳定采集 1280×720，连续运行 30 分钟无断流；权限和启动策略有明确结论。

### 阶段 1：独立 USB 帧输入原型

**目标：** 从现有 UVC 库取得 NV21，并与录制业务完全隔离。

实施内容：

1. 复制现有 `Android.mk` 中对 `libusbcamera.aar` 的引用方式。
2. 将 `UVCCameraHelper` 封装为 `UsbCameraSource`。
3. 把 `onPreviewResult(byte[] nv21Yuv)` 转发给 `FrameDispatcher`。
4. 增加宽高、帧率、时间戳、丢帧和断连统计。
5. 验证断开、重新插入和 Activity/Service 生命周期。

**通过条件：** 分析线程不阻塞预览；30 分钟内无持续性内存增长；断连后能恢复采集。

### 阶段 2：JNI 与检测性能基线

**目标：** 在目标设备上确认模型和 Native 处理是否满足资源约束。

实施内容：

1. 引入 NCNN Android 依赖和车辆检测模型。
2. 建立 `AdasNativeBridge.processFrame()` 接口。
3. 完成 NV21 到灰度/目标输入的缩放和颜色处理。
4. 测量单帧推理耗时、端到端延迟、CPU、RSS 和温升。
5. 确认单线程和跳帧策略，默认按 10~12 FPS 分析。

**通过条件：** 目标设备上持续运行 30 分钟无崩溃，资源数据满足项目设定，或形成可接受的修订指标。

### 阶段 3：FCW/HMW

**目标：** 先实现最依赖车辆检测的核心功能。

实施内容：

1. 过滤车辆类别和低置信度框。
2. 使用目标框底边中心点估计距离。
3. 引入摄像头高度、俯仰角、焦距和地平线标定参数。
4. 使用卡尔曼滤波平滑距离和相对速度。
5. 计算 TTC，并以连续时间窗口触发 FCW。
6. 实现 HMW 的近距持续跟车提示。

**通过条件：** 对静止目标、跟车目标、切入目标分别记录距离、TTC 和报警结果，确认没有单帧误报警。

### 阶段 4：LDW

**目标：** 在不引入大分割模型的前提下实现车道偏离提示。

实施内容：

1. 对 NV21 的 Y 分量执行 ROI 裁剪。
2. 使用 Sobel、阈值化和 HoughLinesP 提取左右车道线。
3. 计算车道中心与图像中心偏移。
4. 加入车道线置信度、持续时间、速度门限和报警冷却。
5. 在车道线不可见时进入静默状态，不输出误报。

**通过条件：** 直道、弯道、变道、阴影和车道线缺失场景均能输出明确状态。

### 阶段 5：LVSA 与报警系统

**目标：** 完成低速/静止等待场景的前车起步提醒。

实施内容：

1. 使用 GPS 速度和视觉状态判断自车静止。
2. 静止时将检测频率降至约 3 FPS。
3. 追踪前车框位置、面积和距离变化。
4. 加入 10 秒冷却，避免重复播报。
5. 固定报警音走 SoundPool；TTS 仅用于确认性语音提示。

**通过条件：** 前车起步、前车未起步、目标丢失和自车起步四种状态均可正确退出或恢复。

### 阶段 6：实车标定和稳定性收口

**目标：** 将功能原型变成可交付的稳定版本。

验证场景：

- 白天、夜间、逆光、雨雾
- 高速、城市道路、上下坡、隧道
- 摄像头轻微偏移、震动和重新插拔
- GPS 暂时不可用
- 前车切入、变道、停车和再次起步

收口内容：

1. 固化摄像头标定参数和默认阈值。
2. 完善异常状态、降级状态和用户提示。
3. 检查 APK 体积、RSS、CPU、温升和日志量。
4. 确认开机启动、后台保活和权限策略。
5. 形成实车测试记录和问题清单。

## 5. 验收指标

### 5.1 必须达成

- USB 摄像头正常识别、采集和断连恢复。
- 分析链路不阻塞预览和系统主线程。
- FCW/HMW/LDW/LVSA 状态机可复现、可记录、可关闭。
- 发生输入丢失、GPS 不可用或车道线不可见时进入安全静默状态。
- 运行 30 分钟以上无崩溃、无持续性内存增长、无明显系统卡顿。

### 5.2 需要实测后确定

- CPU 是否能稳定控制在 5% 以内。
- RSS 是否能控制在 80 MB 以内。
- 实际推理延迟和端到端报警延迟。
- 各道路场景下的误报率和漏报率。
- 夜间、雨雾和坡道场景的有效性。

## 6. 主要风险和处理原则

| 风险 | 处理原则 |
|---|---|
| USB 权限无法自动恢复 | 在阶段 0 决定是否使用预装平台签名或系统授权；不能靠重复请求权限解决。 |
| UVC 回调阻塞 | 回调只入队，算法独立线程消费，队列满时丢帧。 |
| NV21 每帧产生 Java 分配 | 优先确认现有 AAR 行为；必要时改为 Native 缓冲或降低分析频率。 |
| 单目测距受俯仰角影响 | 将测距标定作为必选配置，并以 TTC 趋势作为主要报警依据。 |
| GPS 在隧道不可用 | 进入降级状态；没有可靠车速时不强行触发依赖速度的报警。 |
| 录制应用回归 | HCTADAS 独立复用库，不修改 HCTUSBCamera 业务逻辑。 |
| TTS 延迟或抢占音频 | 固定报警音作为主报警路径，TTS 只做辅助播报。 |

## 7. 预计周期

| 版本 | 周期 | 交付内容 |
|---|---:|---|
| 采集与性能原型 | 3~5 个工作日 | USB/NV21/JNI/检测基线 |
| 功能原型 | 5~8 个工作日 | FCW、HMW、LDW、LVSA 基本状态机 |
| 实车稳定版 | 5~10 个工作日 | 标定、降级、报警收口和实车验证 |

整体按 **2~4 周** 规划更稳妥；实际周期取决于目标设备、摄像头样品和实车测试条件。

## 8. 实施入口

正式实施时按以下顺序开始：

1. 在当前 `HCTCarAdas` 独立 Git 中继续开发，不修改 `HCTUSBCamera`。
2. 先完成阶段 0 和阶段 1，拿到真实帧率、格式、延迟和断连数据。
3. 阶段 1 通过后再引入 NCNN 和 Native 算法库。
4. 先做 FCW/HMW，再做 LDW 和 LVSA。
5. 每个阶段都保留可运行版本和实测数据，再进入下一阶段。
