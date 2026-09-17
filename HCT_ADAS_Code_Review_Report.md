# HCT ADAS 全链路代码审查报告

| 项目 | 内容 |
| --- | --- |
| 审查对象 | `HCTCarAdas` 独立 Android 工程（Java 前台原型） |
| 审查基线 | **rev.3 基线：Git HEAD `6599789`**（rev.1/rev.2 基线为 `9ac35f0`） |
| 审查方式 | 全量静态审查（代码只读），未编译、未运行 Gradle、未做设备验证 |
| 审查范围 | 主源 17 文件 + 单元测试 10 类 77 方法 + 资源 + 两份方案文档一致性 |
| 结论定性 | 链路骨架健康，无方向性缺陷；**当前未闭环 1 项 P0 + 2 项 P1 + 8 组 P2**，另 3 项 P1 与 2 组 P2 已随 `6599789` 闭环 |
| 修订记录 | **rev.2**：撤销 2 项误判（原 P0-4、原 P1-1），重定性 2 项为产品权衡（原 P0-1、原 P1-4）<br>**rev.3**：按 `6599789` 复核，闭环 3 项 P1、部分闭环 1 项 P0、新增 1 项 P2；详见第 0 节 |

---

## 0. rev.3 变更摘要

`6599789 fix: gate LDW on calibration, add lane ROI guard, split frame counters`（11 文件，+259/−58）对本报告的影响：

| 原编号 | rev.2 状态 | rev.3 状态 | 依据 |
| --- | --- | --- | --- |
| P1-1 LDW 缺标定门控 | 待处理 | ✅ **已闭环** | `MainActivity:658-661` 改为 `!simulationFrame && calibrationStatus != CALIBRATED` |
| P1-2 叠加层未按尺寸校验 | 待处理 | ✅ **已闭环** | `VehicleOverlayView:123-149`，`drawLane` 已受 `sizeMismatch` 门控 |
| P2-3 丢帧计数混算 | 待处理 | ✅ **已闭环** | `FrameDispatcher:89` 只累加 `discardedFrames`；UI 已接"作废"位 |
| P0-2 标定可学域 | 待处理（建议加可见性守卫） | ⚠️ **部分闭环** | 新增 ROI 可见性守卫（4 处），但"静默卡 0%"仍存 → 见第 4 节 P0-2 |
| — | — | 🆕 **P2-10** | 守卫拒绝采样时 UI 无区分提示，与 P0-2 残余同源 |
| 第 7 节文档 5 处偏差 | 待同步 | ✅ **已同步** | 两份文档本次一并修正（含性能基线） |

> rev.3 复审确认：`6599789` 未引入新的功能缺陷；`docs` 与代码现值一致，未发现新的表述偏差。

---


## 1. 审查范围与可信度前提

### 1.1 覆盖清单

`app/src/main/java/com/hct/adas/` 全部 17 个类：

| 层 | 类 |
| --- | --- |
| 采集 | `UsbCameraSource`(478) `FrameDispatcher`(103) `FrameConsumer`(116) |
| 感知 | `LiteRtVehicleDetector`(137) `Nv21Preprocessor`(68) `LaneDepartureDetector`(142) |
| 跟踪与测距 | `LeadVehicleTracker`(216) `LeadVehicleMotionEstimator`(83) `CameraCalibration`(92) |
| 标定 | `AutoCalibrationLearner`(259) `CalibrationStore`(135) |
| 决策 | `AdasDecisionEngine`(325) |
| 输出 | `AlertAudio`(265) `VehicleOverlayView`(252) |
| 编排 | `MainActivity`(1,044) `AdasSimulator`(293) `VehicleDetector`(22) |

配套：`app/src/test/java/com/hct/adas/` 10 个测试类，`AndroidManifest.xml`，`activity_main.xml`，`strings.xml`，`app/build.gradle`，以及构建产物 `app/build/intermediates/apk/debug/app-debug.apk`。

### 1.2 构建产物与源码同源校验

为排除"审查的是过期代码"这一风险，对已构建 APK 的 `classes.dex` 做字符串反查，取样均为当前源码独有标识：

| 探测串 | 来源 | 结果 |
| --- | --- | --- |
| `HMW 条件` | `MainActivity.measurementStatus` | FOUND |
| `车道已识别，LDW等待有效车速` | `MainActivity.measurementStatus` | FOUND |
| `FCW_CONFIRM_MILLIS` | `AdasDecisionEngine` 字段名 | FOUND |
| `室内模拟测试` | `showSimulationDialog` | FOUND |
| `自动重试已用尽` | `UsbCameraSource.frameWatchdog` | FOUND |
| `报警音不可用` | `MainActivity.renderMetrics` | FOUND |

**结论**：APK 由当前 Java 源码构建，本报告结论对运行时行为有效。

### 1.2.1 性能实测数据来源

链路性能基线（第 3.1 节）中以下两项为**用户设备实测值**，非文档转录、非静态推断：

| 指标 | 实测值 | 来源 | 与旧文档的差异 |
| --- | --- | --- | --- |
| 相机原始采集率 | **最高 31 FPS** | 用户设备实测 | 文档记 15–20 FPS，**已过时，需同步** |
| 单帧推理耗时 | **60–90 ms** | 用户设备实测 | 文档记 60–90 ms 但**未标注仅含推理段**，需补口径 |

由此派生的三个结论已写入第 3.1 节的延迟分解表与 P0-1：

1. 采样丢弃比 **约 84%**（5 / 31）——采集能力严重富余
2. 消费者线程占用 **30–45%**（90 ms ÷ 200 ms）——推理非瓶颈
3. 端到端延迟 **≈775 ms（均值）~ 890 ms（最坏）**，其中 600 ms 来自"3 帧 @5 Hz"确认

> **rev.2 保留声明**：上述实测基线及其派生的方向修正（含"提高采样率到 10 Hz 不成立"的判断，见 P0-1）在 rev.2 重排中**完整保留**，未被勘误修订覆盖。rev.2 只撤销了原 P0-4 / 原 P1-1 两项误判，未触碰性能数据。

### 1.3 审查边界

- 未编译、未运行 Gradle、未执行单元测试（遵循项目约定，编译由用户负责）
- 未做设备/实车验证；第 P1-4 项（USB 广播可达性）静态审查无法判定，已明确标注需实测
- 未审查 `libusbcamera.aar` 内部实现（仅按调用契约推断）
- 本报告不修改任何代码与既有文档

---

## 2. 总体结论

链路骨架健康，**不存在报警逻辑方向性错误**。以下四点做得正确，是后续加固的基础：

1. **线程归属清晰**：推理在锁外执行（`MainActivity.onFrame` 第 184 行）、native 资源归相机线程独占（`UsbCameraSource` 第 67 行注释与实现一致）、`LeadVehicleTracker` / `LeadVehicleMotionEstimator` / `LaneDepartureDetector` / `AdasDecisionEngine` 单线程独占。
2. **失效降级方向正确**：几何无效、标定不匹配、速度过期统一产出 `NaN` 并静默，**不被当作 0 参与判定**（这是 ADAS 最容易被写反的地方）。
3. **队列策略正确**：容量 2、满时丢最旧（`FrameDispatcher.offer`），对预警场景"宁新勿全"。
4. **状态机可复现**：决策引擎全部阈值集中为常量，四条告警的冷却与确认逻辑可被单元测试驱动。

需要排期处理的问题共 **1 项 P0 + 2 项 P1 + 8 组 P2**（rev.3 口径；另有 2 项属产品策略权衡而非缺陷，依据见第 4.2 节）。rev.2 时该数字为 2 + 5 + 9，差额来自 `6599789` 的闭环（3 项 P1、2 组 P2）与新增项（P2-10），明细见第 0 节。

### 2.1 勘误（rev.2，相对 rev.1 的更正）

rev.1 报告存在 2 项事实误判，经代码与算术复核后**全部撤销**：

| 原编号 | rev.1 错误结论 | 事实 | 错因 |
| --- | --- | --- | --- |
| 原 P0-4 | "模拟自标定会持久化覆盖实车标定" | `MainActivity:613` 已有 `boolean persistCalibration = !simulationFrame;`，`:626` 用它守卫写入。模拟帧恒为 false，**不可能写盘** | 审查者引用 `:626` 的守卫代码时，漏读了 `:613` 的前置赋值，属断章取义 |
| 原 P1-1 | "下采样带中心约 0.79，与 `Y_BOTTOM=0.78` 差 0.01，导致 UI 外推错位" | `(0.72+0.84)/2 = 0.78`，`(0.54+0.66)/2 = 0.60`。**两个常量恰好是各自采样带的绝对几何中心**，自洽 | 审查者心算失误（误取 0.79），并在此基础上推导出并不存在的 5–8% 外推偏差 |

另 2 项经复核后**由"缺陷"重定性为"产品策略权衡"**，技术事实不变但定性更正：

| 原编号 | rev.1 定性 | rev.2 定性 | 依据 |
| --- | --- | --- | --- |
| 原 P0-1 | "1–15 km/h 速段断层漏报" | 产品权衡：低速段使用物理绝对距离是行业通行且必要的保底 | 见第 4.2.3 节 |
| 原 P1-4 | "新目标继承旧冷却，可能被静音" | 产品权衡：跨目标保留冷却是防骚扰设计 | 见第 4.2.4 节 |

**对审查结论的实质影响**：rev.1 把 2 项误判列为 P0/高优，其中原 P0-4 还排在建议处理顺序第 1 位。这两项撤销后，真正需要**改代码**的 P0 只剩 2 项（rev.2 的 P0-1 延迟收口、P0-2 标定可学域提示），工程优先级显著下降。此勘误同时说明：rev.1 在"引用代码片段时未通读所在方法的完整上下文"与"未对算术做二次核验"两处存在方法缺陷，后续审查已按这两点自查。

### 2.2 rev.3 复审（基线 `6599789`）

rev.3 对 `6599789` 逐文件复核后的状态如下（明细见第 0 节）：

| 项 | rev.2 | rev.3 | 复核要点 |
| --- | --- | --- | --- |
| P1-1 LDW 标定门控 | 缺陷 | ✅ 已闭环 | 门控条件正确；不变式 `CALIBRATED ⇒ calibration != null` 经 5 个赋值点核对成立 |
| P1-2 叠加层尺寸校验 | 缺陷 | ✅ 已闭环 | `sizeMismatch` 同时门控参考线与 `drawLane` |
| P2-3 丢帧计数 | 缺陷 | ✅ 已闭环 | 两个计数器语义分开，UI 已显示"作废"；新增单测覆盖 |
| P0-2 标定可学域 | 缺陷 | ⚠️ 部分闭环 | 窗口 0.35→0.30（≈12.7°）+ ROI 可见性守卫（4 处）；**"静默卡 0%"仍在** |
| 文档 5 处偏差 | 偏差 | ✅ 已同步 | 含性能基线（31 FPS / 推理口径）与新增守卫说明 |
| — | — | 🆕 P2-10 | 守卫拒绝时 UI 无区分提示，与 P0-2 残余同源 |

**rev.3 未发现 `6599789` 引入新缺陷。** 该提交中 `MIN_VANISHING_Y` 曾先取 0.25、后收窄为 0.30，最终值经数值核对与 ROI 守卫边界自洽（窗口上限 12.68° 落在守卫生效点 14.78° 内侧，无死区），此结论已作为不变量固化进 `vanishingWindowStaysInsideTheLaneRoiGuard` 用例。

---

## 3. 链路基线（实测口径）

### 3.1 帧率与延迟预算

| 环节 | 实际值 | 依据 |
| --- | --- | --- |
| 相机原始采集 | **最高 31 FPS（用户设备实测）** | 用户实测；旧文档记 15–20 FPS，已过时 |
| **主动采样降频** | **5 FPS（200 ms 硬节流）** | `UsbCameraSource.SAMPLE_INTERVAL_NANOS = 200_000_000L` |
| 采样丢弃比 | **约 84%**（保留 5/31） | 实测采集率与节流间隔之比 |
| 队列容量 | 2 帧 | `MainActivity:150` `new FrameDispatcher(2)` |
| 单帧**推理** | 60–90 ms（**不含车道检测**） | 用户设备实测；计时口径见下 |
| 消费者线程占用 | 30–45%（90 ms ÷ 200 ms） | 推理耗时 ÷ 采样间隔 |
| **链路有效分析率** | **5 Hz（由节流间隔决定，与 31 FPS 无关）** | 上列综合 |

**计时口径说明**：`VehicleDetector.Result.inferenceNanos` 取值区间为 `LiteRtVehicleDetector.detect` 第 94 行 `started` 到第 130 行 `System.nanoTime() - started`，区间内只有 `Nv21Preprocessor.fill`（NV21→RGB 下采样至 320×320）与 `interpreter.runForMultipleInputsOutputs`。**不包含** `LaneDepartureDetector.detect` 的逐像素扫描（与推理并列于 `MainActivity.onFrame` 第 184、205 行），也不含 `tracker` / `motionEstimator` / `decisionEngine`。5 Hz 下这些分摊成本可忽略，但若把采样率提高到 10 Hz 必须一并计入。

端到端报警延迟（FCW 为例，按实测值分解）：

| 分量 | 均值 | 最坏 |
| --- | --- | --- |
| 采样相位滞后（决策所用帧在 TTC 越线时的年龄） | 100 ms | 200 ms |
| 推理完成耗时 | 75 ms | 90 ms |
| 帧数确认（3 帧 @5 Hz） | 600 ms | 600 ms |
| **合计（TTC 越线 → 出声）** | **≈ 775 ms** | **≈ 890 ms** |

**关键结论：600 ms 的确认分量完全由 5 Hz 采样节流决定，与 31 FPS 采集能力和 60–90 ms 推理性能无关。**采集与推理都有富余（31 FPS 只用了 16% 用于采样；90 ms 推理只占 200 ms 间隔的 45%），瓶颈在节流常量与帧数确认方式的组合上。这是 P0-1 的核心。

### 3.2 决策阈值基线（代码现值）

| 功能 | 前置条件 | 触发条件 | 冷却 |
| --- | --- | --- | --- |
| FCW | 目标可见、GPS 速度有效、标定 `CALIBRATED` 且尺寸匹配 | 速度 ≥20 km/h、距离 >0、逼近速度 >0、`distance/closing ≤ 2.4 s`；**连续 3 帧且间隔 ≥200 ms** | 3 s |
| HMW 普通 | 目标可见、距离有效 | v ≥15 km/h：`THW ≤1.2 s` 或 `≤8 m`；v ≤1 km/h：`≤4 m`；**1<v<15：仅 `≤8 m`** | — |
| HMW_CRITICAL | 同上，距离有效 | v ≥15 km/h：`THW ≤0.6 s` 或 `≤4 m`；v ≤1 km/h：抑制；**1<v<15：仅 `≤4 m`** | 4 s |
| LVSA | 目标可见、GPS 速度有效且 ≤1 km/h | 距离 3–9 m 稳定 ≥3 s；随后距离增 ≥2.5 m 或面积降 ≥15%，连续 2 帧 | 10 s |
| LDW | 车道 `available`、速度有效 | 速度 ≥50 km/h、置信度 ≥0.35、`|offset| ≥0.12`，持续 ≥1 s | 6 s |

门控常量：`LVSA_MAX_STATIONARY_SPEED_KMH=1.0`、`HMW_LOW_SPEED_THRESHOLD_KMH=15.0`、`HMW_THW_CAUTION_SECONDS=1.2`、`HMW_THW_CRITICAL_SECONDS=0.6`、`HMW_DISTANCE_METERS=8.0`、`HMW_CRITICAL_DISTANCE_METERS=4.0`、`FCW_MIN_SPEED_KMH=20.0`、`FCW_TTC_SECONDS=2.4`、`LDW_MIN_SPEED_KMH=50.0`、`LDW_OFFSET=0.12`、`LDW_CONFIDENCE=0.35`。

### 3.3 标定可学域

`AutoCalibrationLearner.solveVanishingPoint` 把有效灭点限制在 `y ∈ [0.30, 0.60]`（rev.3 值，`6599789` 由 0.35 收窄而来），经 `computePitchFromVanishingY` 换算（向导默认 90° HFOV / 1280×720 时 `fy_norm=0.889`、`cy=0.5`）：

| 灭点 y | 反算 pitch | 说明 |
| --- | --- | --- |
| 0.60 | −6.4° | 超出 `MIN_PITCH_DEGREES = −5.0`，此端由 pitch 验收挡住 |
| 0.50 | 0° | 视线水平 |
| 0.44 | +4.0° | 与向导默认初值一致 |
| 0.35 | +9.6° | rev.2 的旧上限 |
| **0.30** | **+12.7°** | **rev.3 上限**（`vanishingPitchLimitDegrees`） |

**可学域由两道门共同决定**：

1. **灭点窗口** `[0.30, 0.60]` → 反向 pitch `[−6.4°, +12.7°]`
2. **ROI 可见性守卫** `isLaneRoiVisible`：远带（`ROI_TOP_ROW = 0.54`）地面距离须 ≥ `MIN_ROI_FAR_DISTANCE_METERS = 4.0 m`，近带（`ROI_BOTTOM_ROW = 0.84`）须 ≤ 40 m

守卫在不同安装高度下的生效点（H = 相机离地高度）：

| 安装高度 | 守卫开始拒绝的 pitch | 远带 4 m 判据 |
| --- | --- | --- |
| 1.25 m（轿车） | ≈14.8° | 高度相关 |
| 1.75 m（货车） | >20°（未触发） | 同上 |
| 2.00 m | >20°（未触发） | 同上 |

**工程含义**（rev.3 更新）：

- 可学上限由 rev.2 的 **+9.6° 放宽到 +12.7°**，对中高位安装车辆更友好
- 窗口上限（12.68°）落在守卫生效点（14.78°，H=1.25）**内侧**，守卫不会拒绝窗口仍放行的样本，两者不自相矛盾
- 守卫是**物理判据**（ROI 是否还看得到路）而非角度上限：同为 20°，H=1.25 被拒而 H=2.0 通过
- **残留问题**：守卫拒绝采样时只写日志，UI 仍显示"标定自学习中 0%"，用户无法区分"条件未满足"与"几何上不可能"——见 P0-2 与 P2-10

---

## 4. P0 — 影响预警正确性或安全延迟（rev.3：1 项未闭环 + 1 项部分闭环）

> 本节仅列**仍需改代码**的 2 项：P0-1 完全未闭环，P0-2 已由 `6599789` 部分闭环、残余为 UI 无区分提示。rev.1 的原 P0-1（HMW 低速段）已重定性为产品权衡（第 4.2.3 节）、原 P0-4（模拟标定持久化）已撤销（第 4.2.1 节），均移至第 4.2 节。

### 4.1 需改代码的确认项

#### P0-1 帧驱动确认与 5 Hz 实际分析率脱钩，报警延迟被放大（原 P0-2）

**位置**：`UsbCameraSource.SAMPLE_INTERVAL_NANOS` 第 39 行；`AdasDecisionEngine` 第 48-49、109-117 行；`MainActivity:150`。

| 事实 | 位置 | 后果 |
| --- | --- | --- |
| 主动降采样到 5 FPS | `SAMPLE_INTERVAL_NANOS=200 ms` | **实测 31 FPS 输入被丢弃约 84%** |
| FCW 用 **3 帧**确认 | `FCW_REQUIRED_FRAMES=3` | 5 Hz 下等价 600 ms |
| LVSA 用 **2 帧**确认 | `LVSA_REQUIRED_MOVEMENT_FRAMES=2` | 5 Hz 下等价 400 ms |
| 时间窗约束形同虚设 | `FCW_CONFIRM_MILLIS=200L`（**已参与判定**） | 200 ms 在 5 Hz 下只覆盖 1 帧 |
| 队列容量 2 | `MainActivity:150` | 5 Hz 下几乎不堆积（生产 200 ms/帧 < 消费 ~75 ms/帧），排队分量可忽略 |

**影响**：FCW 从 TTC 越线到出声约 **775 ms（均值）~ 890 ms（最坏）**，其中 **600 ms 来自"3 帧 @5 Hz"的确认方式**，而非算力不足。对 `TTC ≤2.4 s` 的预警，这是安全相关量级；且确认时间会随推理耗时与丢帧情况漂移，不受 `FCW_CONFIRM_MILLIS` 有效约束。

**实测数据带来的方向修正**：原建议"提高采样率到 10 Hz"经实测数据核算后**不成立**——90 ms 推理在 100 ms 间隔下占 90% 线程占用，会挤掉车道检测与决策开销，且推理线程占满后队列会开始堆积，反而引入新的延迟与丢帧。正确的收口方向是**保留 5 Hz 节流，把确认方式从帧数改为时间窗**：

1. `FCW_REQUIRED_FRAMES` 由 3 降为 2，保留 `FCW_CONFIRM_MILLIS` 时间窗作为主约束（当前 200 ms，可加大到 300–400 ms 以维持等效确认强度）
2. `LVSA_REQUIRED_MOVEMENT_FRAMES` 由 2 保持不变，但补一个时间窗上界防抖
3. 若后续实测推理降到 40 ms 以内，再评估是否提高到 8–10 Hz

**配套建议（可观测性）**：`MainActivity` 心跳日志（第 347-349 行）当前输出 FPS / 速度 / 标定 / 目标 / 车道 / 告警，**未输出结果年龄**。`renderMetrics` 第 373 行已在计算 `now - result.timestampNanos()`（用于 `fresh` 判定），把它一并加进心跳日志即可获得端到端延迟的现场证据，用于验收 P0-2 的改动效果。

#### P0-2 标定学习方向性受限：超范围时仍静默卡死（原 P0-3）—— ⚠️ rev.3 部分闭环

**位置**：`AutoCalibrationLearner` 第 31 行（窗口）、第 46 行（守卫阈值）、第 103/148/170/177 行（守卫调用点）；`MainActivity:917`（`initialPitch = 4.0`）。

**rev.3 已修复的部分**（`6599789`）：

| 修复 | 内容 |
| --- | --- |
| 窗口放宽 | `MIN_VANISHING_Y` 0.35 → **0.30**，可学上限 +9.6° → **+12.7°** |
| 守卫新增 | `isLaneRoiVisible` 校验 ROI 地面距离，**4 处**生效：入样前、收敛候选、跟踪候选、在线漂移候选 |
| 边界自洽 | 窗口上限 12.68° 落在守卫生效点 14.78°（H=1.25）内侧，不再出现"窗口放行但守卫拒绝"的死区 |
| 候选校验 | 收敛写入的**候选 pitch 本身**也要过守卫，避免把越界 pitch 持久化（rev.2 建议中的关键一条） |

**仍未闭环的部分**：

1. **静默失败仍在**：守卫或窗口拒绝采样时只写 `Log.w`（`[CALIB] Rejected sample: lane ROI collapsed to the hood...`），UI 仍显示 `calibration_in_progress`"标定自学习中 0%"，文案只提示"请保持直行"。用户无法区分"条件未满足，继续开就好"与"几何上不可能收敛，需要调整安装角"。
2. **收敛门槛未动**：`MAX_CONVERGENCE_STD_DEV=0.015` 叠加 `REQUIRED_CONVERGENCE_SAMPLES=60`，对真实抖动图像仍偏严，本次未评估也未调整。
3. **守卫阈值未做实车标定**：`MIN_ROI_FAR_DISTANCE_METERS=4.0` 是工程经验值（对应"ROI 越靠越近直到压到机盖"的物理边界），但"4 m 之外车道线是否仍可稳定检出"缺实测依据。

**影响**：可学域变宽了，但"卡住时不知道为什么"的用户体验问题原样保留。

**建议方向**（rev.3 修订）：给 `AutoCalibrationLearner` 增加一个"连续拒绝计数 + 拒绝原因"的最小状态（例如 `consecutiveRoiRejections`），在 `MainActivity.updateCalibrationStatus` 中当该计数超过阈值时切换文案为"安装角度超出可学习范围，请调整俯仰后重试"。改动面约 3 文件、无算法风险。

### 4.2 已撤销与已重定性的项（rev.2）

#### 4.2.1 【已撤销】原 P0-4：模拟自标定"不会"污染持久化标定

rev.1 指控模拟自标定收敛出的 1.29° 会写入 SharedPreferences 覆盖实车标定。**经复核，此指控不成立**，撤销。

事实（`MainActivity.processAdasFrame`）：

```java
613:  boolean persistCalibration = !simulationFrame;        // ← rev.1 漏读了这一行
...
620:  if (step.calibrationUpdated()) {
621:      calibration = step.calibration();
...
626:      if (persistCalibration) {                         // 模拟帧恒为 false，不执行
627:          String currentId = cameraSource != null ? cameraSource.currentCameraId() : "";
628:          calibrationStore.save(step.calibration(), step.status(), step.progressPercent(), currentId);
629:      }
630:  } else if (...) {
633:      if (persistCalibration) {                         // 第二条写盘路径同样受守卫
634:          calibrationStore.saveStatus(step.status(), step.progressPercent());
635:      }
```

代码在**两条**持久化路径（第 626、633 行）之前均以 `!simulationFrame` 守卫，模拟帧不可能写盘。rev.1 的错因是引用第 626 行守卫时未通读方法上下文，遗漏了第 613 行的前置赋值——属审查者断章取义，非代码缺陷。

**保留的次级观察（非缺陷，供参考）**：模拟期间内存中的 `calibration` 确实会被替换为 1.29°，`restoreSimulationState()` 会回滚内存值，因此退出模拟后行为一致。若希望模拟期间完全不触碰标定状态对象，可将 `calibration` / `calibrationStatus` 改为仅由快照托管；当前实现无实际风险，无需改动。

#### 4.2.2 【已撤销】原 P1-1：采样带中心与常量"完全自洽"

rev.1 声称下带 `[0.72, 0.84]` 中心"约 0.79"，与 `Y_BOTTOM = 0.78` 存在 0.01 偏差并导致 UI 外推错位 5–8%。**算术复核证明该结论错误**，撤销。

| 常量 | 值 | 采样带（`LaneDepartureDetector:32-35`） | 带中心 | 是否一致 |
| --- | --- | --- | --- | --- |
| `Y_TOP` | 0.60 | `[0.54, 0.66]` | `(0.54+0.66)/2 = 0.6000` | ✅ 完全一致 |
| `Y_BOTTOM` | 0.78 | `[0.72, 0.84]` | `(0.72+0.84)/2 = 0.7800` | ✅ 完全一致 |

两个常量**恰好是各自采样带的绝对几何中心**，与实现 100% 自洽；代码注释（第 31 行"Top band [0.54, 0.66], Bottom band [0.72, 0.84]"）同样准确。rev.1 的错因是心算失误（误取 0.79），并在此基础上推导出并不存在的锚点偏差，进而虚构了"AI 叠加层车道线偏 5–8% 画幅宽"的影响。**锚点契约正确，无需改动。**

> 附带说明：`findBrightLine` 的行循环 `for (y = yStart; y <= yEnd; y += stepY)` 在 `stepY = max(1, (yEnd-yStart)/10)` 下会额外采到 `y = yEnd` 一行（占比约 9%），使实际行均值略微偏离名义中心，量级约 0.001–0.002（≤0.2% 画幅高），**远小于 rev.1 声称的 0.01**，且为纯像素级抖动，不构成界面错位。若追求极致可把循环改为 `< yEnd`，属可选优化而非缺陷。

#### 4.2.3 【重定性】原 P0-1：低速蠕行用绝对距离是产品权衡，不是断层

rev.1 将 1–15 km/h 段落描述为"断层漏报"。**该定性错误**，改为产品权衡。重新逐点核验后的真实行为：

| 车速 | 分支 | 触发距离 | 是否存在断崖 |
| --- | --- | --- | --- |
| v = 0（≤1 km/h） | 停车分支 | 极近提示音抑制；普通 HMW 视觉提示在 ≤4 m 仍显示 | 参考点 |
| v = 0.5 | 兜底分支 | 8 m | — |
| v = 2 ~ 15 | 兜底分支 | 8 m | **无** |
| v ≥ 15 | THW 分支 | `THW ≤1.2 s 或 ≤8 m` | **无** |

关键更正：`return distance <= HMW_DISTANCE_METERS` 是**无条件**执行的兜底，v=2 与 v=14 都返回 8 m，**rev.1 声称的"2 km/h 时 7 m 反而不报"与实际不符**（实际会报）；v=15 处 `min(THW, 8 m)` 与兜底同为 8 m，亦非断崖。因此 **1–15 km/h 全段有 8 m 的米数保护，低速段不会静默**。

关于"为何低速不用 THW"的工程依据，报告采信用户说明并复核成立：v = 5 km/h（1.39 m/s）下若强行套用 `THW ≤0.6 s`，临界距离仅 `1.39 × 0.6 = 0.83 m`——两车保险杠贴死才报警，属失效而非保护。成熟方案在拥堵蠕行段退化为物理绝对米数（4 m / 7–8 m）是通行做法，且与本文档"固定距离兜底"的既有产品定义一致（见 `HCT_ADAS_Feasibility_and_Architecture.md` 第 14 行）。

**结论**：无需改代码。唯一可讨论的边界是"车辆停稳后由兜底 8 m 收紧到 4 m"（`strings.xml` 中普通 HMW 视觉提示保留、极近音抑制）是否为期望行为——现有测试 `doesNotChimeCriticalHeadwayWhileEgoVehicleIsStationary` 已将此固化为设计意图，如需调整属产品变更而非缺陷修复。

#### 4.2.4 【重定性】原 P1-4：跨目标保留冷却是防骚扰设计

rev.1 将"目标切换后冷却被继承"列为缺陷。复核后改为产品权衡，且**代码已含双重防重复机制**：

```java
public void resetTargetState() {
    dangerousFrames = 0;          // ① 帧数确认计数清零 → 新目标必须重新累计 3 帧
    dangerSinceMillis = null;     // ② 确认起始时间清零 → 200 ms 时间窗重新计时
    clearStationaryState();
}
```

即在目标切换时，`FCW` 的**确认链已完整复位**；保留的 `fcwCooldownUntil` / `hmwCriticalCooldownUntil` 只抑制"刚响过的告警再次出声"，与 `dangerousFrames` 复位共同构成防重复鸣叫。若冷却也清零，城市路口频繁切车会在数百毫秒内连续蜂鸣——这正是用户指出的失效场景。

**结论**：无需改代码。rev.1 的"新目标被静音 3–4 s"表述虽在时序上成立，但未认识到此时**新目标自身也尚未完成 3 帧确认**，两者共同作用的结果是"最坏情况"而非"设计缺陷"。

---

## 5. P1 — 正确性与健壮性缺陷（rev.3：1 项已闭环 + 1 项待设备验证）

> 本节列出全部 2 项 P1：P1-1 已随 `6599789` 闭环（保留复核结论供回溯），P1-2 属静态审查无法判定、必须设备实测的项。rev.1 的原 P1-1（Y 锚点错位）已按第 4.2.2 节撤销，原 P1-4（跨目标冷却）已按第 4.2.4 节重定性为产品权衡。

### P1-1 USB 看门狗不覆盖"打不开"阶段（原 P1-3，次序重排）—— ✅ rev.3 已闭环

**位置**：`UsbCameraSource.frameWatchdog` 第 76-89 行、`tryOpen` 第 216-222 行、`scheduleOpenRetry` 第 340-351 行。

rev.3 复核：`6599789` 已加入 `OPEN_WATCHDOG_TIMEOUT_NANOS = 8_000_000_000L` 与 `openingStartedNanos`，看门狗新增 opening 分支：

```java
if (opening && !previewActive && openingStartedNanos > 0L
        && now - openingStartedNanos >= OPEN_WATCHDOG_TIMEOUT_NANOS) {
    int token = invalidatePreview();                  // 同时清 opening / openingStartedNanos
    cameraHandler.post(UsbCameraSource.this::closeCamera);
    listener.onError("USB 摄像头打开超时，正在自动重连");
    scheduleOpenRetry(token);
}
```

核验通过项：`openingStartedNanos` 置位（`tryOpen:218`，在 `invalidatePreview()` 之后）与清零（`invalidatePreview:329`）配对；token 由 `invalidatePreview()` 推进而非重读，旧回调被 `isCurrent` 挡掉；`scheduleOpenRetry` 仍受 `openRetryCount >= 3` 与 500/1000/2000 ms 退避约束，不会无限重试；与 stream 分支用 `else if` 互斥，无双触发。

**遗留约束（非缺陷）**：若 native `camera.open()` 为无返回阻塞调用，`closeCamera` 会排在该阻塞调用之后执行——清理仍会完成（阻塞返回时 `isCurrent` 已为 false），但"自动重连"受 native 调用时长拖累。属 AAR 层固有约束，建议在注释中写明。

### P1-2 USB 权限广播对"未导出"接收器的可达性未验证（原 P1-4）—— 🔎 需设备验证

**位置**：`UsbCameraSource.start` 第 147-161 行；`requestPermission` 第 183-194 行。

以 `Context.RECEIVER_NOT_EXPORTED` 注册，同时监听系统 `ACTION_USB_DEVICE_ATTACHED/DETACHED`；授权回调经 `PendingIntent.getBroadcast(... FLAG_MUTABLE)` + `usbManager.requestPermission` 触发。

Android 13+ 允许"仅供系统广播"的接收器使用 `NOT_EXPORTED`，系统 UID 发送的广播通常仍可达；但社区存在 USB 授权 PendingIntent 在该组合下不回调的案例报告：
- <https://github.com/jiangdongguo/AndroidUSBCamera/issues/651>
- <https://stackoverflow.com/questions/79286907/pendingintent-not-triggered-for-usb-permission-request-in-android>

**失败模式**：用户点"允许"后回调不达 → 界面停在"已检测到 USB 摄像头，等待授权或预览"，可点状态栏恢复。

**处理**：静态审查无法判定，**必须设备实测**三条路径——首次授权、拒绝后重试、运行中热插拔。

---

## 6. P2 — 可维护性与一致性（rev.3：2 项已闭环，新增 1 项）

> rev.3 状态：P2-1、P2-3 随 `6599789` 闭环；P2-6 部分闭环；新增 P2-10。当前仍有 **8 组**待处理（P2-2、P2-4、P2-5、P2-6 剩余、P2-7、P2-8、P2-9、P2-10）。

### P2-1 `VehicleOverlayView` 重复且未使用的 import —— ✅ rev.3 已闭环

`6599789` 已删除重复的 `Paint` / `Path` / `Shader` / `AttributeSet` / `View` 与未使用的 `Insets` / `WindowInsets` 共 6 行。当前文件第 3-14 行为单一 import 列表，零行为影响。

### P2-2 `AlertAudio.status()` 的 READY 判定被 `toneGenerator` 短路

第 102-120 行：只要软件音发生器建立成功（多数设备都能建立），即使四个 wav 全部加载失败也报 `READY`。后果是 `MainActivity:359` 的"报警音不可用，请检查车机音频通道"在 wav 损坏/丢失场景下**永不显示**，用户误以为有完整音频。另 `activePriority` 仅在 `play()` 第 168 行读、由 `play()`/`testSound()` 写，`testSound()` 先置 1 再绕过优先级判定，两条路径行为不一致。

### P2-3 `discardPending()` 把"主动作废"计入丢帧指标 —— ✅ rev.3 已闭环

`6599789` 拆分为两个计数器：

```java
public synchronized void discardPending() {
    discardedFrames += frames.size();   // 不再累加 droppedFrames
    frames.clear();
}
```

- `droppedFrames` 现在只在 `offer()` 队列满时递增（拥塞语义）
- `discardedFrames` 接入 UI：`strings.xml` 的 `stream_metrics` 新增"作废 %5$d"位，`MainActivity:360` 传值
- 测试同步：`discardsFramesFromDisconnectedCameraWithoutClosingQueue` 改为断言 `dropped=0 / discarded=1`；新增 `reportsQueueOverflowAndDeliberateInvalidationSeparately` 验证两者互不污染

**现场收益**：现在能直接区分"推理太慢挤爆队列"（丢帧涨）与"USB 反复断流"（作废涨）。

### P2-4 死代码：已实现但无人调用

| 成员 | 定义处 | 状态 |
| --- | --- | --- |
| `CalibrationStore.loadStatus(String expectedCameraId)` | `CalibrationStore:61` | 全仓库零调用，摄像头身份校验 API 未接线 |
| `AutoCalibrationLearner.lastVanishingX()` / `lastVanishingY()` | `AutoCalibrationLearner:263,267` | 零调用，灭点信息未进日志或 UI |
| `FrameDispatcher.size()` | `FrameDispatcher:78` | 仅测试使用，生产不暴露队列水位 |

其中第 1 条是标定误继承的代码根因：`load(expectedCameraId)` 仅在 stored ID 非空时比对（第 51-59 行），从未写入过 ID 的历史标定被无条件放行。

### P2-5 `@string/calibration_required` 为"僵尸资源"

`activity_main.xml:45` 用它作 XML 初值，但 `MainActivity.updateCalibrationStatus()`（第 566-588 行）在首个检测结果到达前不改写它，全代码零 `R.string.calibration_required` 引用。后果是启动到首帧之间显示的是"点击设置摄像头标定；绿色区域为车道估计…"，与稳态文案 `calibration_unset` 不一致且语义偏离（在讲颜色图例）。

### P2-6 冗余计算与跨线程取数 —— ⚠️ rev.3 部分闭环

`6599789` 已把 `cameraSource.capturedFrames()` 上提到 `renderMetrics()` 开头（`MainActivity:324`），消除了同一周期内取两遍的问题。**仍未处理**：

- `fitPreview(result)` 每 250 ms 被调用（第 379 行），内部无条件 `new Matrix()` + `setTransform()`（第 972-983 行）；矩阵仅在窗口或帧尺寸变化时才变，当前每秒 4 次无效视图更新
- 每 250 ms 分别加锁取 `frameDispatcher.metrics()`、`frameConsumer.metrics()`、`cameraSource.capturedFrames()`（第 324、357-359 行），主线程周期性抢三把锁

### P2-7 `onCreate` 中 insets 监听器早于 `overlayView` 赋值

第 104-111 行注册监听器并 `requestApplyInsets()`，监听器体第 108 行直接使用 `overlayView.setBottomInset(bottom)`，而 `overlayView` 到第 120 行才赋值。当前安全（insets 实际派发在下一帧 `ViewRootImpl.doTraversal()`），属依赖框架调度时序的潜在 NPE，建议把监听器注册移到视图查找之后。

### P2-8 无 `WakeLock`、无前台 Service，息屏可能静默降级

全仓库零 `PowerManager` 引用，`AndroidManifest.xml` 仅有 `MainActivity` 一个组件。车机息屏后系统限频会拉低分析率，而"帧仍偶尔到达"使 `fresh` 判定（750 ms `MAX_OBSERVATION_GAP_NANOS`）不触发告警——**不是"没做后台能力"，而是"息屏后可能静默降级为不报警"**。需产品决策。

### P2-9 `FrameDispatcher.await()` 超时计算存在脆弱点

第 71-73 行：`remainingNanos ≤ 999_999` 时 `waitMillis = 0`、`waitNanos > 0`，此时 `Object.wait(0, n)` 不会立即返回（与 `wait(0)` 语义不同），故当前安全；但一旦被"优化"成 `wait(waitMillis)`，将退化为最多 1 ms 忙等（5 Hz 下每秒约 200 次自旋）。建议显式处理 `waitMillis == 0` 的分支。

### P2-10 🆕 标定守卫拒绝时 UI 无区分提示（rev.3 新增）

`AutoCalibrationLearner` 的两道门槛（灭点窗口 + ROI 可见性守卫）在拒绝采样时只写日志：

```java
Log.w(TAG, String.format(Locale.ROOT,
        "[CALIB] Rejected sample: lane ROI collapsed to the hood at pitch=%.1f deg", ...));
```

但 `StepResult` 只带 `status` / `progressPercent`，`MainActivity.updateCalibrationStatus` 因此只能渲染 `calibration_in_progress` →"标定自学习中 0% · 测距预警未启用，请保持直行"。**用户看到的是"再开开就好"，而真实原因可能是"这个安装角永远学不出来"。**

与 P0-2 残余同源，两者应一并处理：给 `AutoCalibrationLearner` 增加连续拒绝计数与拒绝原因，由 `MainActivity` 在超过阈值时切换文案。

**P2 处理优先序**（rev.3 更新）：**P2-10 + P0-2 残余**（同一个用户可见问题，合并处理）→ P2-4（标定误继承根因）→ P2-2（音频状态误报）→ P2-6 剩余两项（实际 CPU 开销）→ P2-5 / P2-7 / P2-9（纯清理加固）→ P2-8（需产品决策）。~~P2-1~~、~~P2-3~~ 已闭环。

---

## 7. 测试与文档一致性偏差

### 7.1 rev.3：5 处偏差已全部同步 ✅

rev.1/rev.2 列出的 5 处文档偏差已在 `6599789` 中随两份文档一并修正：

| # | 原偏差 | 处理 |
| --- | --- | --- |
| 1 | `FCW_CONFIRM_MILLIS=400` 且"未参与判定" | 改为 200 且已参与，并写明"连续 3 个分析帧且跨度 ≥200 ms" |
| 2 | "速度 ≥45 km/h 时普通提醒为 THW≤1.2 s 或 ≤8 m" | 改为 **15 km/h**，并补低速段与停车段行为 |
| 3 | "9 个 JVM 测试类、约 75 个方法" | 改为 **10 个测试类、73 个方法**（rev.3 复核现值：10 类 / **77** 个方法，新增 4 个守卫用例） |
| 4 | "`USBMonitor.openDevice()` 原始控制块当前未保存" | 已改写 |
| 5 | "LiteRT 连续 3 次异常…没有退避" | 已改写 |

同时同步了性能基线（31 FPS 采集、推理计时口径）与新增的 ROI 守卫说明。

> **rev.3 待跟进**：第 3 项在文档中记为"73 个测试方法"，而 `6599789` 新增了 4 个用例，现值 **77**。属正常漂移（文档写作时点早于用例补充），下次文档收口时更新即可，不单列为偏差。

### 7.2 归因（历史记录）

复核计划 `docs/superpowers/plans/2026-09-17-adas-project-review.md` 的 Task 1（建立当前规则与链路基线）未按代码逐行收口，7.1 中的条目系沿用旧版描述。**性能基线偏差（15–20 FPS vs 实测 31 FPS）也已一并同步**，不再列为待确认项。

### 7.3 测试覆盖缺口（rev.3 复核，仍属实）

- 无 Android instrumented 测试
- 无真实 NV21 车道帧测试（`LaneDepartureDetectorTest` 用合成图；`AdasSimulator` 直接注入可用 Observation，两者不能互证）
- 无 UVC 热插拔、LiteRT 真模型、音频出声、Activity 重建测试
- 无端到端延迟测量（P0-1 因此未被发现）
- 无"低速蠕行 HMW 行为"与"目标切换冷却"的显式用例（原 P0-1、原 P1-4 的定性分歧即源于缺少这类把产品意图写进测试的用例）
- **rev.3 新增**：`6599789` 为 ROI 守卫补了 4 个用例（`laneRoiGuardRejectsSteepPitchWhereTheFixedRoiLooksAtTheHood`、`vanishingWindowStaysInsideTheLaneRoiGuard`、`rejectsSamplesWhenTheConfiguredPitchPutsTheRoiOnTheHood`、`reachesCalibratedForATallVehicleWithinTheGuard`），把"窗口上限必须落在守卫内侧"固化为不变量；但仍缺真实图像的守卫阈值验证（`MIN_ROI_FAR_DISTANCE_METERS = 4.0` 的来源是几何推导+经验值）

---

## 8. 建议处理顺序（rev.3）

| 序 | 项 | 改动面 | 风险 | 验证方式 |
| --- | --- | --- | --- | --- |
| 1 | P0-2 残余 + P2-10：守卫拒绝原因进 UI | 3 文件（learner 加拒绝计数、strings 加文案、activity 切换） | 低（无算法改动） | 单测 + 实车安装角验证 |
| 2 | P0-1 延迟收口（降帧数 + 结果年龄进触发日志） | 2 文件 | 中（改确认强度，需复测误报率） | **必须设备实测端到端延迟** |
| 3 | P2-4 标定身份校验接线 | 1 文件 | 低 | 单测 |
| 4 | P2-2 音频 READY 判定 | 1 文件 | 低 | 手动损坏 wav 复现 |
| 5 | P1-1 USB 广播可达性 | 只验证 | — | **设备实测三条授权路径** |
| 6 | P2-6 剩余（`fitPreview` 冗余） | 1 文件 | 低 | 目视回归 |
| 7 | P2-5 / P2-7 / P2-9 | 分散 | 低 | 现有单测回归 |
| 8 | P2-8 WakeLock / 前台 Service | 需产品决策 | — | 长稳测试 |
| 9 | 文档测试计数 73 → 77 | 2 文档 | 极低 | `git diff --check` |
| — | ~~P1-1 LDW 标定门控~~ | — | — | ✅ 已随 `6599789` 闭环 |
| — | ~~P1-2 叠加层尺寸校验~~ | — | — | ✅ 已随 `6599789` 闭环 |
| — | ~~P1-3 USB 打开超时~~ | — | — | ✅ 已随 `6599789` 闭环 |
| — | ~~P2-1 import 清理~~ | — | — | ✅ 已随 `6599789` 闭环 |
| — | ~~P2-3 丢帧计数分离~~ | — | — | ✅ 已随 `6599789` 闭环 |
| — | ~~文档 5 处偏差~~ | — | — | ✅ 已随 `6599789` 同步 |
| — | ~~原 P0-4 模拟禁写盘~~ | — | — | rev.2 已撤销（第 4.2.1 节） |
| — | ~~原 P1-1 锚点对齐~~ | — | — | rev.2 已撤销（第 4.2.2 节） |
| — | ~~原 P0-1 HMW 速段分支~~ | — | — | 重定性为产品权衡（第 4.2.3 节） |
| — | ~~原 P1-4 冷却语义~~ | — | — | 重定性为产品权衡（第 4.2.4 节） |

> **rev.3 相比 rev.2**：待办从 12 项降到 **9 项**，其中 5 项由 `6599789` 闭环、2 项在 rev.2 已撤销/重定性。剩余项里没有 P0 级算法风险——第 1 项是文案与状态透出，第 2 项是延迟参数收口（需实测），其余为清理与验证。

---

## 9. 后续验证清单（rev.3）

**必须在目标设备完成**：
1. USB 授权三条路径（首次 / 拒绝后重试 / 运行中热插拔）—— 对应 P1-1
2. 端到端报警延迟测量（TTC 越线 → 出声）：心跳日志已有 `Age=%.0fms` 字段（`6599789` 加入），先确认现状 ≈775 ms，再验证时间窗收口效果 —— 对应 P0-1
3. 息屏 / 后台 30 min 长稳运行，记录分析率与漏报 —— 对应 P2-8
4. 1280×720 → 640×480 降级路径复测：叠加层车道现已被门控（`6599789`），确认降级时不再出现错位车道、参考线正常提示 —— 对应已闭环 P1-2 的回归

**必须在实车完成**：
5. 标定收敛性验证（至少含一台高位安装车辆）：确认 +12.7° 新上限可用，且 ROI 守卫不误拒正常安装 —— 对应 P0-2
6. 真实 NV21 车道帧的 LDW 可用性（白天 / 夜间 / 逆光 / 雨雾）—— 对应已闭环 P1-1 的回归
7. 已知距离静态场景的测距精度 —— 全链路基础
8. （可选，非缺陷）低速蠕行段 HMW 提醒曲线实测，用于确认"8 m 米数保底"在实车上的可接受度 —— 对应第 4.2.3 节

---

## 附录 A：本报告与代码的关系

- 代码/测试：全部只读审查，本报告**未修改**任何源码
- 报告自身改动仅限本文件（`HCT_ADAS_Code_Review_Report.md`），按用户指示单独提交
- **rev.3 基线**：HEAD `6599789`。该提交闭环了 3 项 P1、部分闭环 1 项 P0 并同步了两份文档，报告在此基线上完成复审
- rev.1/rev.2 的基线为 `9ac35f0`，其中的行号引用未随基线迁移（凡涉及变更文件的行号，rev.3 已按 `6599789` 重新核对）

## 附录 B：审查中确认的正确实现（易被误判为缺陷）

| 项 | 位置 | 说明 |
| --- | --- | --- |
| 推理在锁外执行 | `MainActivity.onFrame:184` | 仅"转移 + 短分析"进锁，避免长时持锁 |
| native 缓冲快照 | `UsbCameraSource:261-264` | 按注释"返回即复用"做 `duplicate()` + 拷贝，只拷采样帧 |
| `lastFrameNanos` 与队列同锁 | `UsbCameraSource:265-271` | 与 `invalidatePreview` 的 `discardPending()` 原子，避免旧 token 复活 |
| 过期间隔立即重置分析态 | `MainActivity:192-204` | 750 ms 无观测即清状态，不沿用陈旧目标 |
| 重放/乱序帧防护 | `LeadVehicleTracker:41-43` | `now <= latest.timestampNanos()` 直接返回上次快照 |
| `detect()` 失败退避 | `MainActivity:180,185` | 失败置 5 s 退避，成功清零；避免模型损坏时高频重启 |
| 消费线程重启前释放解释器 | `FrameConsumer:89-95` | `onStopped()` 回收后再 `launchWorker()` |
| `NaN` 不被当 0 使用 | 全链路 | 速度/距离/置信度无效统一静默，方向正确 |
| **模拟帧写盘守卫** | `MainActivity:614` | `persistCalibration = !simulationFrame`，两条持久化路径（第 627、634 行）均已受控——rev.1 曾误判为缺陷，见第 4.2.1 节 |
| **车道带中心与常量一致** | `LaneDepartureDetector:9,11` + `:44-52` | `Y_TOP=0.60` ↔ 上带 `[0.54,0.66]` 中心 0.6000；`Y_BOTTOM=0.78` ↔ 下带 `[0.72,0.84]` 中心 0.7800，完全自洽——rev.1 曾误判为偏差，见第 4.2.2 节 |
| **低速段绝对距离保底** | `AdasDecisionEngine:161` | 无条件兜底 `distance <= 8 m`，1–15 km/h 全段有米数保护——rev.1 曾误判为断层，见第 4.2.3 节 |
| **目标切换双重防重复** | `AdasDecisionEngine:311-315` | `dangerousFrames` / `dangerSinceMillis` 复位 + 冷却保留，共同防连续蜂鸣——rev.1 曾误判为缺陷，见第 4.2.4 节 |
| **标定门控不变式** | `MainActivity:612,658` | `calibrationStatus == CALIBRATED ⇒ calibration != null`：该状态仅由 `loadStatus()`（内部含 null 检查）与 `step.status()`（learner 仅在返回非空收敛结果时置位）产生，故 `LaneObservation` 门控无需再判 null |
| **窗口与守卫边界自洽** | `AutoCalibrationLearner:31,46` | 窗口上限 12.68° 落在守卫生效点 14.78°（H=1.25）内侧，无死区；已由 `vanishingWindowStaysInsideTheLaneRoiGuard` 固化为不变量 |
| **守卫是物理判据而非角度上限** | `AutoCalibrationLearner:272-277` | 同为 20° 俯仰，H=1.25 被拒（远带 ROI 3.01 m < 4 m）而 H=2.0 通过（4.81 m）——按"ROI 是否还看得到路"判定，避免一刀切 |
| **模拟场景的 LDW 例外** | `MainActivity:659` | 门控写作 `!simulationFrame && calibrationStatus != CALIBRATED`，模拟帧有意放行：`AdasSimulator` 的 LDW 场景需要注入合成车道，不这样写会让该场景永远不告警 |

---

*报告 rev.3，基于 Git HEAD `6599789` 的静态审查结论。行号对应该提交内容。rev.1→rev.2 修订见第 2.1 节勘误，rev.2→rev.3 修订见第 0 节变更摘要。*
