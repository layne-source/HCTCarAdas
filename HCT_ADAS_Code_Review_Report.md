# HCT ADAS 全链路代码审查报告

| 项目 | 内容 |
| --- | --- |
| 审查对象 | `HCTCarAdas` 独立 Android 工程（Java 前台原型） |
| 审查基线 | **rev.7 历史基线：Git HEAD `6534ca0`；当前为 2026-09-18 未提交工作区加固** |
| 审查方式 | 全量静态审查；Gradle focused suite 受环境 `Unable to establish loopback connection` 阻断；另用临时 `javac + JUnit` harness 跑了 106 个纯 Java 用例；未做设备验证 |
| 审查范围 | 主源当前 20 个 Java 类 + 单元测试当前 14 类 143 个 `@Test` 方法 + 资源 + 两份方案文档一致性 |
| 结论定性 | 历史 P1/P2 已闭环；当前工作区补入车道同侧连续性、`LOST` 重获速度清理、LiteRT 退避、精确定位权限门控、USB 生命周期/格式协商和音频主动抢占。几何、真实 UVC、LiteRT、音频和道路投影仍需设备验证 |
| 修订记录 | **rev.2**：撤销 2 项误判、重定性 2 项为产品权衡<br>**rev.3**：基线 `6599789`，闭环 3 项 P1 + 2 组 P2，新增 P2-10<br>**rev.4**：基线 `32ea041`，闭环 P0-2 与 P2-10、新增 P2-11<br>**rev.5**：基线 `c6c1b46`，P1/P2 级别全部关闭（含 P2-8 按车机常亮前提关闭）；详见第 0 节 |

---

## 0. rev.7 变更摘要

rev.5 之后新增 4 个提交：一个崩溃修复与三次实测驱动的口径修正。

| 提交 | 内容 | 对本报告的影响 |
| --- | --- | --- |
| `b1d2134` | 用真机实测值替换全部延迟推算 | 第 3.1 节、P0-1 换成实测口径 |
| `fdddffc` | `Split=` 分段仪表（交接/排队/处理） | 使"延迟归到哪一段"可测 |
| `6534ca0` | **修复心跳日志格式符类型不匹配导致的进程崩溃**；日志格式化抽到 `AdasLogFormat` 并补 5 个单测 | 新增第 10 节；测试 82 → 87 |
| `6f5d3e1` / `b1d2134` | 报告同步 | — |

### 0.4 2026-09-18 工作区加固（未提交）

当前工作区在历史基线之上还有以下改动；这些改动本轮只做静态审查，未宣称构建或设备通过：

| 模块 | 当前行为 | 仍需验证 |
|---|---|---|
| `LaneDepartureDetector` | 热启动使用上一帧边界 ±0.07 搜索窗，并对位置跳变施加惩罚；冷启动才扫描整半幅 | 同侧接缝、邻近标线和真实夜间/雨雾帧 |
| `LeadVehicleMotionEstimator` | `LOST`、ID 变化、时间倒退/间隔过大或标定变化清空速度历史，重获从零估计 | 真实遮挡重获和目标切换 |
| `UsbCameraSource` | SurfaceTexture 由 listener 单次释放并返回 `false`；opening 8 s 与无帧 3 s watchdog；serial 身份，无 serial 不绑定；MJPEG/YUYV 多档协商 | AAR 控制块、真实格式枚举、热插拔和授权广播 |
| `MainActivity` | LiteRT 运行时异常 5/10/20/40 s 退避；车速链路要求 FINE，COARSE 仅提示升级权限 | 真模型损坏/运行时异常、定位质量和 UI 故障提示 |
| `AlertAudio` | 高优先级事件停止已有低优先级 SoundPool 流和 ToneGenerator；停止时清理优先级锁 | 实车音频焦点、媒体静音和声压 |

**rev.7 的核心修正是一处口径错误**：rev.6 把心跳的 `Age`（202 ms 均值）当作"测量段延迟"，但分段数据显示它与真实成本之和相差 **127 ms（均值）**——那部分是心跳采样时刻的相位，属观测伪影。**真实管线成本是 78.5 ms，不是 202 ms。** 端到端随之从 ≈600 ms 修正为 **≈580 ms**。

**同时记录了两种导致错误的做法**（详见第 10 节）：把 `String.format` 内联在 Activity 里导致单测无法覆盖；以及用"数格式符个数"代替"核对实参类型"。

---

## 0.1 rev.5 变更摘要（历史）

rev.4 之后新增 3 个提交，把报告里**可立即处理**的项清空：

| 提交 | 内容 | 对本报告的影响 |
| --- | --- | --- |
| `ee79474` | 向导新增初始俯仰角输入 | ✅ 闭环 **P1-3**（rev.4 新增的能力缺口） |
| `8b733a0` | 音频 READY 判定、`await()` 加固、onsets 时序、死字符串/import 清理、`fitPreview` 缓存 | ✅ 闭环 **P2-2 / P2-5 / P2-6 / P2-7 / P2-9 / P2-11**，并含 P2-4 的 `MainActivity` 部分 |
| `c6c1b46` | `CalibrationStore.storedCameraId()` | ✅ 闭环 **P2-4** 的存储层部分 |

| 编号 | rev.4 状态 | rev.5 状态 | 依据 |
| --- | --- | --- | --- |
| P2-2 音频 READY 误报 | 开放 | ✅ 闭环 | `status()` 改为要求 `allSamplesLoaded()` |
| P2-4 摄像头身份校验未接线 | 开放 | ✅ 闭环 | `reloadCalibrationForCamera()` + `storedCameraId()` |
| P2-5 僵尸字符串 | 开放 | ✅ 闭环 | 布局改指 `calibration_unset`，删除死字符串 |
| P2-6 冗余 `fitPreview` | 部分闭环 | ✅ 闭环 | 缓存 letterbox 缩放，缩放未变即返回 |
| P2-7 insets 监听器时序 | 开放 | ✅ 闭环 | 监听器注册移到视图解析之后 |
| P2-9 `await()` 脆弱点 | 开放 | ✅ 闭环 | 按纳秒余数选择 `wait` 重载 |
| P2-11 未使用 import | 开放 | ✅ 闭环 | 已删除并全仓库确认 |
| P1-3 向导无俯仰输入 | 开放 | ✅ 闭环 | 向导新增输入 + 范围校验 + 陡角提醒 |
| P2-8 息屏可能静默降级 | 开放 | ✅ **关闭（按宿主机制）** | 宿主是常亮车机，"息屏导致限频"不可达；未加 WakeLock/前台 Service，前提已写入 `onStop` Javadoc |
| P1-2 USB 广播可达性 | 🔎 需设备验证 | ✅ **关闭（按设备策略）** | 测试机按包名预授权，授权弹窗不出现，该路径不被走到；前提已写入接收器注册处注释 |

> **rev.5 决策记录**：
> 1. **P2-4 附带行为变更** —— 未记录相机 ID 的旧标定会被判定为"无法归属"并失效一次（车辆需重标定一次）。**已确认接受**：当前处于测试阶段、无老用户，不做一次性迁移。理由随 `43161c0` 写入 `CalibrationStore` 与 `reloadCalibrationForCamera()` 的注释。详见第 6 节 P2-4。
> 2. **P2-8 已关闭** —— 宿主是屏幕常亮的车机，"息屏导致限频"这条路径不可达；未加 WakeLock 或前台 Service（收益为零、徒增后台占用）。限定条件见第 6 节 P2-8。
> 3. **P1-2 已关闭** —— 测试机按包名全开权限、USB 访问预先授予，授权弹窗与回调路径不被走到；限定条件见第 5 节 P1-2。
>
> **其中 P1-2 与 P2-8 属于"绑定宿主/设备前提"的关闭，而非"代码已修"**：前提变化时需重新开启，代码注释与本文对应章节都写明了触发条件。

---

## 0.2 rev.4 变更摘要（历史）

rev.3 之后新增 3 个提交，对本报告的影响：

| 提交 | 内容 | 对本报告的影响 |
| --- | --- | --- |
| `094d64e` | 报告 rev.3 单独提交 | 交付物本身，无代码影响 |
| `21679fa` | 拒绝原因分类 + 连续几何拒绝计数 + UI 提示 | ✅ 闭环 **P0-2 残余**与 **P2-10** |
| `32ea041` | `[DANGER-START]` / `[ALERT-TRIGGER] Age+Confirm` 延迟仪表 | P0-1 **测量能力就位**，参数待实测后再调 |

| 编号 | rev.3 状态 | rev.4 状态 | 依据 |
| --- | --- | --- | --- |
| P0-2 标定可学域（残余） | ⚠️ 部分闭环 | ✅ **已闭环** | `AutoCalibrationLearner.Rejection` 分类 + `consecutiveGeometricRejections`；`MainActivity:601-617` 在 `WIZARD_COMPLETED` 与 `CALIBRATING` 两态透出提示 |
| P2-10 守卫拒绝无 UI 提示 | 🆕 待处理 | ✅ **已闭环** | 同上；新增 `calibration_angle_out_of_range` 文案 |
| P0-1 延迟收口 | 待处理 | 🔶 **待实测** | 仪表已加（`MainActivity:695-714`），确认参数未动，等设备日志 |
| — | — | 🆕 **P2-11** | `MainActivity` 存在未使用的 `android.view.ViewGroup` import（既有问题，非新增） |

> **rev.4 复审确认**：`21679fa` 与 `32ea041` 未引入新的功能缺陷。逐项核验通过——`reject()` 覆盖 5/5 拒绝点、`StepResult` record 形状未破坏、`resetSamples()` 未误清计数（`resetAnalysisState()` 走该路径）、计数清零仅发生在接受样本/驾驶条件拒绝/`reset()` 三处、日志格式符与实参一一对应、目标切换与分析重置均重新武装危险边沿。

---

## 0.3 rev.3 变更摘要（历史）

`6599789 fix: gate LDW on calibration, add lane ROI guard, split frame counters`（11 文件，+259/−58）：

| 原编号 | rev.2 状态 | rev.3 状态 | 依据 |
| --- | --- | --- | --- |
| P1-1 LDW 缺标定门控 | 待处理 | ✅ **已闭环** | `MainActivity:658-661` 改为 `!simulationFrame && calibrationStatus != CALIBRATED` |
| P1-2 叠加层未按尺寸校验 | 待处理 | ✅ **已闭环** | `VehicleOverlayView:123-149`，`drawLane` 已受 `sizeMismatch` 门控 |
| P2-3 丢帧计数混算 | 待处理 | ✅ **已闭环** | `FrameDispatcher:89` 只累加 `discardedFrames`；UI 已接"作废"位 |
| P0-2 标定可学域 | 待处理 | ⚠️ 部分闭环 → rev.4 已闭环 | ROI 可见性守卫（4 处） |
| — | — | 🆕 P2-10 → rev.4 已闭环 | 守卫拒绝采样时 UI 无区分提示 |
| 第 7 节文档 5 处偏差 | 待同步 | ✅ **已同步** | 两份文档一并修正（含性能基线） |

---


## 1. 审查范围与可信度前提

### 1.1 覆盖清单

`app/src/main/java/com/hct/adas/` 当前全部 20 个类：

| 层 | 类 |
| --- | --- |
| 采集 | `UsbCameraSource`(478) `FrameDispatcher`(103) `FrameConsumer`(116) |
| 感知 | `LiteRtVehicleDetector` `Nv21Preprocessor` `LaneDepartureDetector` |
| 跟踪与测距 | `LeadVehicleTracker` `LeadVehicleMotionEstimator` `CameraCalibration` `LaneGeometry` |
| 标定 | `AutoCalibrationLearner`(259) `CalibrationStore`(135) |
| 决策 | `AdasDecisionEngine` `AdasUiStatusMapper` |
| 输出 | `AlertAudio` `VehicleOverlayView` `AdasLogFormat` |
| 编排 | `MainActivity` `AdasSimulator` `VehicleDetector` |

配套：`app/src/test/java/com/hct/adas/` 当前 14 个测试类、143 个 `@Test` 方法，另有 `UsbCameraSourceTest` 等本轮契约测试；`AndroidManifest.xml`、`activity_main.xml`、`strings.xml`、`app/build.gradle`。本轮不以工作区构建产物作为新鲜验证证据。

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

**结论（历史基线）**：该 APK 只证明 2026-09-17 的历史源码曾构建成功；当前工作区在 2026-09-18 有未包含在 APK 内的几何、USB、推理和音频改动，因此本报告不把该 APK 当作当前源码的构建证据。

### 1.2.1 性能实测数据来源

链路性能基线（第 3.1 节）中以下两项为**用户设备实测值**，非文档转录、非静态推断：

| 指标 | 实测值 | 来源 | 与旧文档的差异 |
| --- | --- | --- | --- |
| 相机原始采集率 | **最高 31 FPS** | 用户设备实测 | 文档记 15–20 FPS，**已过时，需同步** |
| 单帧推理耗时 | **60–90 ms** | 用户设备实测 | 文档记 60–90 ms 但**未标注仅含推理段**，需补口径 |

由此派生的三个结论已写入第 3.1 节的延迟分解表与 P0-1：

1. 采样丢弃比 **约 84%**（5 / 31）——采集能力严重富余
2. 消费者线程占用 **30–45%**（90 ms ÷ 200 ms）——推理非瓶颈
3. 端到端延迟是历史 rev.2 派生值 **≈775 ms（均值）~ 890 ms（最坏）**，其中 600 ms 来自当时的"3 帧 @5 Hz"确认；rev.7 实测已修正为管线约 78.5 ms、测量段约 180 ms、端到端约 580 ms，当前 FCW 还要求 200 ms 时间跨度

> **rev.2 保留声明**：上述实测基线及其派生的方向修正（含"提高采样率到 10 Hz 不成立"的判断，见 P0-1）在 rev.2 重排中**完整保留**，未被勘误修订覆盖。rev.2 只撤销了原 P0-4 / 原 P1-1 两项误判，未触碰性能数据。

### 1.3 审查边界

- Gradle focused suite 仍受环境 loopback 错误阻断；本轮临时 `javac + JUnit` harness 执行的 106 个纯 Java 用例全部通过，其中包含几何 26 项。USB 5 项契约测试仍只作源码/API 静态核对，未在 Android classpath 下重跑
- 未做设备/实车验证（P1-2 的 USB 广播可达性因测试机预授权而未被走到，已在代码与报告中留档限定条件）
- 未审查 `libusbcamera.aar` 内部实现（仅按调用契约推断）
- 本报告不修改任何代码与既有文档

---

## 2. 总体结论

链路骨架健康，**不存在报警逻辑方向性错误**。以下四点做得正确，是后续加固的基础：

1. **线程归属清晰**：推理在锁外执行（`MainActivity.onFrame` 第 184 行）、native 资源归相机线程独占（`UsbCameraSource` 第 67 行注释与实现一致）、`LeadVehicleTracker` / `LeadVehicleMotionEstimator` / `LaneDepartureDetector` / `AdasDecisionEngine` 单线程独占。
2. **失效降级方向正确**：几何无效、标定不匹配、速度过期统一产出 `NaN` 并静默，**不被当作 0 参与判定**（这是 ADAS 最容易被写反的地方）。
3. **队列策略正确**：容量 2、满时丢最旧（`FrameDispatcher.offer`），对预警场景"宁新勿全"。
4. **状态机可复现**：决策引擎全部阈值集中为常量，四条告警的冷却与确认逻辑可被单元测试驱动。

需要排期处理的问题**只剩 1 项 P0**（另有 2 项属产品策略权衡而非缺陷，依据见第 4.2 节）。P0-1 的实测已完成（见第 3.1 与第 4 节），**当前结论是不改代码**：降帧数收益仅 ≈133 ms 且风险无法在模拟中验证；若要继续推进需先取得真实道路的误报率基线。rev.2 时的 2 + 5 + 9 已在前述五个提交中清空。

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

rev.3 对 `6599789` 逐文件复核后的状态如下（明细见第 0.1 节）：

| 项 | rev.2 | rev.3 | 复核要点 |
| --- | --- | --- | --- |
| P1-1 LDW 标定门控 | 缺陷 | ✅ 已闭环 | 门控条件正确；不变式 `CALIBRATED ⇒ calibration != null` 经 5 个赋值点核对成立 |
| P1-2 叠加层尺寸校验 | 缺陷 | ✅ 已闭环 | `sizeMismatch` 同时门控参考线与 `drawLane` |
| P2-3 丢帧计数 | 缺陷 | ✅ 已闭环 | 两个计数器语义分开，UI 已显示"作废"；新增单测覆盖 |
| P0-2 标定可学域 | 缺陷 | ⚠️ 部分闭环 | 窗口 0.35→0.30（≈12.7°）+ ROI 可见性守卫（4 处）；"静默卡 0%"仍在 |
| 文档 5 处偏差 | 偏差 | ✅ 已同步 | 含性能基线（31 FPS / 推理口径）与新增守卫说明 |
| — | — | 🆕 P2-10 | 守卫拒绝时 UI 无区分提示，与 P0-2 残余同源 |

**rev.3 未发现 `6599789` 引入新缺陷。** 该提交中 `MIN_VANISHING_Y` 曾先取 0.25、后收窄为 0.30，最终值经数值核对与 ROI 守卫边界自洽（窗口上限 12.68° 落在守卫生效点 14.78° 内侧，无死区），此结论已作为不变量固化进 `vanishingWindowStaysInsideTheLaneRoiGuard` 用例。

### 2.3 rev.4 复审（基线 `32ea041`）

rev.4 对 `21679fa`、`32ea041` 逐文件复核后的状态（明细见第 0.1 节）：

| 项 | rev.3 | rev.4 | 复核要点 |
| --- | --- | --- | --- |
| P0-2 残余（静默卡 0%） | 部分闭环 | ✅ 已闭环 | `Rejection` 四态分类；几何类拒绝累加计数、驾驶条件类清零；提示同时挂在 `WIZARD_COMPLETED` 与 `CALIBRATING`——**这一点是必要的**：几何坏掉时 status 永远不会进入 `CALIBRATING`，只挂后者会让提示不可达 |
| P2-10 UI 无区分提示 | 待处理 | ✅ 已闭环 | `calibration_angle_out_of_range` 文案 + 持续拒绝阈值 5 次（≈1 s @5 Hz） |
| P0-1 延迟收口 | 待处理 | 🔶 待实测 | 仪表就位（`Age` / `Confirm` 双日志），阈值与帧数未动；按"先测量后调整"原则等设备日志 |
| 新增能力缺口 | — | 🆕 **P1-3** | 向导硬编码 `initialPitch = 4.0`，用户无俯仰角输入入口 |
| — | — | 🆕 **P2-11** | `MainActivity` 未使用的 `android.view.ViewGroup` import |

**rev.4 的验证边界**：新增的几何拒绝提示依赖"ROI 不可见"这一条件。`AdasSimulator` 所有场景都使用固定的 4.0° 标定（`MainActivity:801`），该条件下 ROI 远带 10.84 m ≥ 4 m 始终可见，因此**提示路径在室内模拟中无法触发**——这正是 P1-3 的来源。

### 2.4 rev.5 复审（基线 `c6c1b46`）

rev.5 对 `ee79474`、`8b733a0`、`c6c1b46` 三个提交逐文件复核后，**P1 与 P2 级别全部闭环**（P2-8 按宿主机制关闭），逐项依据见第 0 节与第 6 节。代码侧自查结论：

| 检查 | 结果 |
| --- | --- |
| `AlertAudio.status()` 新判定 | `allSamplesLoaded()` 覆盖四个 wav；`playbackFailed` 仍作为独立降级路径保留 |
| `FrameDispatcher.await()` 重载选择 | 纳秒余数 >0 → `wait(ms, ns)`；=0 → `wait(ms)`。语义正确，误改会立刻破坏行为 |
| `fitPreview()` 缓存 | `appliedScaleX/Y` 在视图尺寸变化时自然失配并重算（`onLayoutChange` 与 metrics tick 都会调用），不会卡住旧矩阵 |
| `reloadCalibrationForCamera()`（历史 rev.5） | 当时仅在 camera id 非空时动作；未知 id 直接返回，瞬时断连不会误清标定。当前工作区已改为：摄像头实际打开但无稳定 serial 时清除米制标定 |
| 资源引用完整性 | 24 个字符串定义、23 个 Java 引用 + `app_name`（manifest）、布局引用全部有定义，无未使用项 |
| 向导俯仰输入 | 校验在 `fromWizard` 之前抛出，越界时不会产生部分写入 |
| 花括号平衡 / 空白 | 6 个改动文件全部通过，`git diff --check` 干净 |

**唯一的行为变更**：P2-4 使"未记录相机 ID 的旧标定"失效一次。该取舍已确认接受（测试阶段无老用户），理由已随 `43161c0` 写入代码注释，详见第 6 节 P2-4。

---

## 3. 链路基线（实测口径）

### 3.1 帧率与延迟预算

| 环节 | 实际值 | 依据 |
| --- | --- | --- |
| 相机原始采集 | **最高 31 FPS（用户设备实测）** | 用户实测 |
| **主动采样降频** | **5 FPS（200 ms 硬节流）** | `UsbCameraSource.SAMPLE_INTERVAL_NANOS = 200_000_000L` |
| 采样丢弃比 | 约 84%（保留 5/31） | 实测采集率与节流间隔之比 |
| 队列容量 | 2 帧 | `MainActivity:150` `new FrameDispatcher(2)` |
| 单帧**推理** | 60–90 ms（**不含车道检测与决策**） | 用户设备实测；计时口径见下 |
| **管线处理成本** | **78.5 ms 均值（69–92，σ=6.6）** | `Split=` 末段，n=11；含检测+车道+跟踪+测距+决策 |
| 消费者线程占用 | **≈39%**（78.5 ms ÷ 200 ms），实测**排队恒为 0** | 线程跟得上 200 ms 节奏，余量 108 ms（按最坏 92 ms 计） |
| **链路有效分析率** | **≈4.83 Hz（由节流间隔决定，与采集 FPS 无关）** | 模拟日志两事件间隔 414 ms ÷ 2 帧 |
| 心跳实际间隔 | ≈1029 ms（目标 1000 ms，n=31） | 相邻间隔均值，含 `renderMetrics` 自身开销；最大值 1060 ms |

**计时口径说明**：`VehicleDetector.Result.inferenceNanos` 取值区间为 `LiteRtVehicleDetector.detect` 第 94 行 `started` 到第 130 行 `System.nanoTime() - started`，区间内只有 `Nv21Preprocessor.fill`（NV21→RGB 下采样至 320×320）与 `interpreter.runForMultipleInputsOutputs`。**不包含** `LaneDepartureDetector.detect` 的逐像素扫描（与推理并列于 `MainActivity.onFrame` 第 184、205 行），也不含 `tracker` / `motionEstimator` / `decisionEngine`。

**rev.7 分段实测校正**：管线处理实测 **78.5 ms**（σ=6.6，最坏 92 ms），与 `inferenceNanos` 的 60–90 ms 基本吻合——**车道检测、跟踪与决策的增量很小**。排队 **11/11 样本恒为 0**，说明管线跟得上 200 ms 节奏。要把采样压到 100 ms，处理必须进 100 ms 以内，按最坏 92 ms 只剩 8 ms 余量，风险高。

> **口径说明（rev.7）**：`Age` 是从**采集时刻**到心跳采样时刻的差，**不是管线延迟**——它额外包含"心跳采样距最近一帧处理完"的相位（0–250 ms）。分段指标 ①+②+③ 才是真实成本，`Age` 仅在需要"这一刻结果有多旧"时有意义。
>
> **rev.7 已补分段指标**：`Frame` 现在携带 `offeredAtNanos`（生产者交接时刻），`FrameConsumer.Metrics` 增加 `offeredNanos` / `pickedUpNanos` / `finishedNanos`，心跳日志输出 `Split=采集→交接+排队+处理 ms`。三个分量都是同钟差、**不含 250 ms 心跳相位**，可直接回答"延迟该归到节流、队列还是管线"。
>
> **注意 `Age ≠ Split 合计`**：`Age` 还会加上"心跳采样时刻距最近一帧处理完"的相位（0–250 ms，实测均值 127 ms）。因此两者**不是**交叉校验关系——`Age` 偏大是观测方式的产物，`Split` 才是链路成本。

**关键旁证（3 组数据）**：采集率在 15–19 FPS 与 23–27 FPS 两档之间摆动时，Age 均值分别为 **201 ms 与 203 ms**——**采集率提升 75% 而端到端年龄零变化**，直接证明瓶颈是 200 ms 采样节流而非相机吞吐或 CPU 负载。心跳 FPS 只反映采集率、不代表分析率（分析率固定在 ≈4.83 Hz），**`Age` 与 `Confirm` 才是有效指标**。

端到端报警延迟（FCW 为例）。**rev.6 已用实测数据替换推算值**：

| 分量 | rev.5 推算 | **rev.7 实测（分段）** | 数据来源 |
| --- | --- | --- | --- |
| ① 采集→交接（节流等待 + NV21 拷贝） | 未单列 | **1.5 ms 均值（1–5）** | `Split=` 前段，n=11 |
| ② 排队 | "可忽略" | **0 ms（全部为 0）** | `Split=` 中段——**实测零积压** |
| ③ 管线处理（检测+车道+跟踪+测距+决策） | 未单列 | **78.5 ms 均值（69–92，σ=6.6）** | `Split=` 末段 |
| ④ 采样间隔相位（距上一帧被处理的时间） | 100 ms 估算 | **≤200 ms（均 100）** | 由 ①+②+③+④ = 测量段反推 |
| 测量段合计（①+②+③+④） | 175 ms | **≈180 ms 均值 / ≈280 ms 最坏** | 见下方校验 |
| 确认段（3 帧 + ≥200 ms 时间窗） | 600 ms | **≈400 ms** | 模拟场景 `Confirm=413 ms` |
| **合计（TTC 越线 → 出声）** | ≈775 / 890 ms | **≈580 ms 均值 / ≈680 ms 最坏** | 上两项相加 |

**rev.7 关键修正：`Age` 不是管线延迟。** rev.6 把心跳的 `Age`（202 ms 均值）当作测量段，但分段数据表明它与 ①+②+③ 之间存在 **127 ms 的均值缺口**（范围 23–253 ms）。该缺口 = 心跳采样时刻距"最近一帧被处理完"的时间，即 250 ms 心跳周期与 200 ms 处理周期的相位差（理论上界 200 ms）——**属于观测伪影，不是链路延迟**。

`Age` 与分段之和的差恰好落在 0–200 ms 内（实测 23–253 ms，均值 127），与该解释一致。**因此判定延迟应看 ①+②+③+④，不要用 `Age`。**

**rev.7 分段的工程含义**：

| 观察 | 数据 | 含义 |
| --- | --- | --- |
| 排队恒为 0 | 11/11 样本 | **管线目前不是瓶颈**，处理速度跟得上 200 ms 节奏；提高采样率不会缓解任何积压 |
| 处理 78.5 ± 6.6 ms | 69–92 ms | 管线成本稳定，与 `inferenceNanos`（60–90 ms）吻合，说明车道检测/跟踪/决策的增量很小 |
| 采样间隔相位 ≤200 ms | 由总和反推 | **占测量段的 55%**，是测量段里唯一的大头 |
| 采集→交接仅 1.5 ms | 1–5 ms | NV21 拷贝与节流剩余等待合计可忽略 |

**因此"让车道检测更便宜"的收益上限只有几十毫秒**（处理段的 78.5 ms 里，车道检测只占增量部分），而它换不来延迟量级的改善——rev.6 提出的替代方向经分段数据验证后**收益有限，降级为可选项**。

**真正的两块大头**：采样间隔相位（≤200 ms）与确认窗口（≈400 ms）。前者的下限受处理成本约束（要缩到 100 ms 就得把处理压进 100 ms，当前 σ=6.6 ms、最坏 92 ms，余量仅 8 ms，风险高）；后者是**设计选择而非算力问题**，因此仍是唯一有实质空间的方向，详见 P0-1。

### 3.2 决策阈值基线（代码现值）

| 功能 | 前置条件 | 触发条件 | 冷却 |
| --- | --- | --- | --- |
| FCW | 目标可见、GPS 速度有效、标定 `CALIBRATED` 且尺寸匹配 | 速度 ≥20 km/h、距离 >0、逼近速度 >0、`distance/closing ≤ 2.4 s`；**连续 3 帧且间隔 ≥200 ms** | 3 s |
| HMW 普通 | 目标可见、距离有效 | v ≥15 km/h：`THW ≤1.2 s` 或 `≤8 m`；v ≤1 km/h：`≤4 m`；**1<v<15：仅 `≤8 m`** | — |
| HMW_CRITICAL | 同上，距离有效 | v ≥15 km/h：`THW ≤0.6 s` 或 `≤4 m`；v ≤1 km/h：抑制；**1<v<15：仅 `≤4 m`** | 4 s |
| LVSA | 目标可见、GPS 速度有效且 ≤1 km/h | 距离 3–9 m 稳定 ≥3 s；随后距离增 ≥2.5 m 或面积降 ≥15%，连续 2 帧 | 10 s |
| LDW | 车道 `available`、速度有效 | 速度 ≥50 km/h、置信度 ≥0.35、`abs(offset) ≥ 0.12`，持续 ≥1 s | 6 s |

门控常量：`LVSA_MAX_STATIONARY_SPEED_KMH=1.0`、`HMW_LOW_SPEED_THRESHOLD_KMH=15.0`、`HMW_THW_CAUTION_SECONDS=1.2`、`HMW_THW_CRITICAL_SECONDS=0.6`、`HMW_DISTANCE_METERS=8.0`、`HMW_CRITICAL_DISTANCE_METERS=4.0`、`FCW_MIN_SPEED_KMH=20.0`、`FCW_TTC_SECONDS=2.4`、`LDW_MIN_SPEED_KMH=50.0`、`LDW_OFFSET=0.12`、`LDW_CONFIDENCE=0.35`。

### 3.3 标定可学域

兼容 VP 路径的 `AutoCalibrationLearner.solveVanishingPoint` 把有效灭点限制在 `y ∈ [0.30, 0.60]`（真实帧当前主路径优先使用 widthSamples 的 implied pitch/MAD），经 `computePitchFromVanishingY` 换算（向导默认 90° HFOV / 1280×720 时 `fy_norm=0.889`、`cy=0.5`）：

| 灭点 y | 反算 pitch | 说明 |
| --- | --- | --- |
| 0.60 | −6.4° | 超出 `MIN_PITCH_DEGREES = −5.0`，此端由 pitch 验收挡住 |
| 0.50 | 0° | 视线水平 |
| 0.44 | +4.0° | 与向导默认初值一致 |
| 0.35 | +9.6° | rev.2 的旧上限 |
| **0.30** | **+12.7°** | **rev.3 上限**（`vanishingPitchLimitDegrees`） |

**兼容 VP 路径的可学域由两道门共同决定**：

1. **灭点窗口** `[0.30, 0.60]` → 反向 pitch `[−6.4°, +12.7°]`
2. **ROI 可见性守卫** `isLaneRoiVisible`：远带（`ROI_TOP_ROW = 0.54`）地面距离须 ≥ `MIN_ROI_FAR_DISTANCE_METERS = 3.5 m`，近带（`ROI_BOTTOM_ROW = 0.82`）须 ≤ 40 m

守卫在不同安装高度下的生效点（H = 相机离地高度）：

| 安装高度 | 守卫开始拒绝的 pitch | 远带 4 m 判据 |
| --- | --- | --- |
| 1.30 m（轿车） | 约 14° 附近 | 高度相关 |
| 2.00 m（货车） | 高度相关，通常更宽 | 同上 |
| 2.00 m | >20°（未触发） | 同上 |

**工程含义**（rev.3 更新）：

- 可学上限由 rev.2 的 **+9.6° 放宽到 +12.7°**，对中高位安装车辆更友好
- 窗口上限（12.68°）落在守卫生效点（14.78°，H=1.25）**内侧**，守卫不会拒绝窗口仍放行的样本，两者不自相矛盾
- 守卫是**物理判据**（ROI 是否还看得到路）而非角度上限：同为 20°，H=1.25 被拒而 H=2.0 通过
- **rev.4 已闭环**：原来"守卫拒绝只写日志、UI 一律显示 0%"的问题已修复——`21679fa` 增加 `Rejection` 分类与连续几何拒绝计数，`MainActivity` 在两处前置状态透出"安装角度超出可学习范围"提示。可学域的**边界值本身未变**，变化的是失败原因对用户可见

**当前工作区几何契约（2026-09-18）**：真实车道帧优先走 `widthSamples` 的近/远宽度比，旧 VP 仅作无宽度样本的兼容路径。投影公式为
`w_norm = W·f_x·sin(α+β)/(H·cosβ)`，`β = atan((y-c_y)/f_y)`；目标测距使用 `Z_ground=H/tan(α+β)`，横向米制值使用
`Z_axis=H·cosβ/sin(α+β)` 和 `X=(x-0.5)·Z_axis/f_x`。发布的米制偏移先除以近端观测宽度再乘物理车道宽，车辆在车道中心右侧为正。曲率拟合 `X=aZ_ground²+bZ_ground+c` 后使用
`R=(1+(2aZ_ground+b)²)^1.5/(2a)`，负值为 Left，正值为 Right；`|a|<1e-4` 显式表示已知直行。
`LaneGeometryTest` 现在包含不复用生产函数的世界点投影/横向校验（显式使用相机 pitch 旋转、`Z_ground=H/tan(α+β)`、`Z_axis=H·cosβ/sin(α+β)` 和 `x=0.5+f_xX/Z_axis`）、偏移符号和曲率方向断言；临时 `javac + JUnit` harness 的 26 项几何/车道用例通过，Gradle 仍受 loopback 环境错误阻断。

---

## 4. P0 — 影响预警正确性或安全延迟（rev.7：实测完成，结论为暂不改动）

> 本节仅 1 项：P0-1。**rev.7 分段实测完成，维持"暂不改动确认参数"**；rev.6 提出的替代方向经数据验证收益有限，已降级。待办性质为"等真实道路误报率基线"。P0-2 已由 `21679fa` 闭环，见第 3.3 节末尾与第 4.2 节。rev.1 的原 P0-1（HMW 低速段）已重定性为产品权衡（第 4.2.3 节）、原 P0-4（模拟标定持久化）已撤销（第 4.2.1 节）。

### 4.1 待处理的确认项

#### P0-1 帧驱动确认与 5 Hz 实际分析率脱钩，报警延迟被放大（原 P0-2）—— 🔶 rev.7 分段实测完成，**结论为"暂不改动"**

**位置**：`UsbCameraSource.SAMPLE_INTERVAL_NANOS` 第 39 行；`AdasDecisionEngine` 第 48-49、109-117 行；`MainActivity:150`。

| 事实 | 位置 | rev.6 实测口径 |
| --- | --- | --- |
| 主动降采样到 5 FPS | `SAMPLE_INTERVAL_NANOS=200 ms` | 31 FPS 输入丢弃约 84%；**有效分析率实测 ≈4.83 Hz** |
| FCW 用 **3 帧**确认 | `FCW_REQUIRED_FRAMES=3` | **实测确认段 ≈400 ms**（3 帧跨度 = 2 个间隔，非 3 个） |
| LVSA 用 **2 帧**确认 | `LVSA_REQUIRED_MOVEMENT_FRAMES=2` | 跨度 1 个间隔 ≈200 ms |
| 时间窗约束 | `FCW_CONFIRM_MILLIS=200L`（已参与判定） | 200 ms 在 4.83 Hz 下只覆盖 1 帧，形同虚设 |
| 管线处理成本 | — | **78.5 ms 均值（69–92，σ=6.6）**，实测 |
| 排队 | — | **0 ms（11/11 样本）**，无积压 |
| 消费者线程占用 | — | **≈39%**（78.5 ÷ 200）；余量 108 ms（按最坏 92 ms 计） |

**rev.6 实测结论**：

| 分量 | 实测 |
| --- | --- |
| 管线处理 + 排队 + 交接 | **80 ms 均值**（78.5 + 0 + 1.5，实测 `Split=`） |
| 采样间隔相位 | **≤200 ms（均 100）**，由总和反推 |
| 测量段合计 | **≈180 ms 均值 / ≈280 ms 最坏** |
| 确认段（FCW 3 帧） | **≈400 ms**（模拟 `Confirm=413 ms`，由 `Dist=11.0m` 反查场景第 14 帧验证） |
| **端到端** | **≈580 ms 均值 / ≈680 ms 最坏** |

**处置改为"暂不改动确认参数"**，理由三条：

1. **收益比 rev.5 估算的小**：确认段实测 ≈400 ms 而非 600 ms，把 `FCW_REQUIRED_FRAMES` 由 3 降为 2 只能省 **≈133 ms**（400 → 267 ms），不是 200 ms
2. **风险无法在现有条件下验证**：降帧数直接削弱确认强度，而误报率只能靠真实道路数据评估——`AdasSimulator` 的合成目标过于干净，永远不会抖动，测不出误报
3. **余量仍然充足**：`FCW_TTC_SECONDS = 2.4 s` 减去 ≈600 ms 端到端，仍有 **≈1.8 s 的 TTC 余量**才到实际碰撞时间。当前的延迟量级没有把预警压到无意义

**替代方向经实测后降级**：rev.6 曾建议"压缩单帧处理成本"（车道检测降频等）。分段数据显示管线只有 **78.5 ms**、且车道检测的增量很小，因此**该方向收益上限只有几十毫秒**——列为可选项，不再是首选。

**若要继续推进降帧数**，需要先补两项数据：真实道路上的 FCW 误报率基线，以及测量段更精确的分解（建议暴露"消费线程侧的帧年龄"而非渲染时刻的年龄，见下）。

**rev.6 对仪表的一处修正建议**：当前 `Age` 取的是**渲染时刻**与帧采集时刻之差，混合了 250 ms 心跳周期的相位（实测 108–322 ms 的离散度主要由此产生）。若要精确测测量段，应在 `processAdasFrame` 里记录 `System.nanoTime() - result.timestampNanos()`（决策时刻的帧年龄），并在心跳中输出其最新值——这样能去掉心跳相位，得到稳定的单点指标。

#### P0-2 标定学习方向性受限：静默卡死问题（原 P0-3）—— ✅ rev.4 已闭环

**位置**：`AutoCalibrationLearner` 第 31 行（窗口）、第 46 行（守卫阈值）、第 103/148/170/177 行（守卫调用点）；`MainActivity:917`（`initialPitch = 4.0`）。

**rev.3 已修复的部分**（`6599789`）：

| 修复 | 内容 |
| --- | --- |
| 窗口放宽 | `MIN_VANISHING_Y` 0.35 → **0.30**，可学上限 +9.6° → **+12.7°** |
| 守卫新增 | `isLaneRoiVisible` 校验 ROI 地面距离，**4 处**生效：入样前、收敛候选、跟踪候选、在线漂移候选 |
| 边界自洽 | 窗口上限 12.68° 落在守卫生效点 14.78°（H=1.25）内侧，不再出现"窗口放行但守卫拒绝"的死区 |
| 候选校验 | 收敛写入的**候选 pitch 本身**也要过守卫，避免把越界 pitch 持久化（rev.2 建议中的关键一条） |

**rev.4 闭环情况**：

| 原残留项 | rev.4 状态 | 依据 |
| --- | --- | --- |
| 静默失败（UI 无区分） | ✅ 已闭环 | `Rejection` 四态 + `consecutiveGeometricRejections`；`MainActivity` 在 `WIZARD_COMPLETED` 与 `CALIBRATING` 两态透出提示 |
| 收敛门槛未评估 | ⏳ 仍开放 | 宽度主路径为 `MAX_CONVERGENCE_MAD_DEGREES=0.40` + 60 样本；需真实抖动图像数据才能定论 |
| 守卫阈值未做实车标定 | ⏳ 仍开放 | `MIN_ROI_FAR_DISTANCE_METERS=3.5` 仍是几何推导+经验值，需实车图像验证 |

**剩余影响**：可学域已放宽、失败原因已可见，但"4 m 阈值是否对应真实车道线可检距离"和"0.015 标准差门槛是否过严"两点仍需实车数据。两者都属**参数标定**而非逻辑缺陷。

**rev.4 新增的门槛**：提示阈值 `GEOMETRIC_REJECTION_HINT_THRESHOLD = 5`（≈1 s @5 Hz）。取值理由是"足够长以免误报、足够短以免让用户白等"；若实车上几何拒绝呈间歇性（例如弯道反复触发），该值可能需要上调——属可通过日志调参的项。

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

## 5. P1 — 正确性与健壮性缺陷（历史 rev.7：全部关闭）

> P1-1 已随 `6599789` 闭环；P1-2 因测试机预授权而在本项目内关闭（限定条件见该节）；P1-3 已随 `ee79474` 闭环。

本轮工作区没有重新打开历史 P1 编号；USB 生命周期、格式协商和推理退避属于新增加固，见第 0.4 节，仍以设备验证为准。

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

### P1-2 USB 权限广播对"未导出"接收器的可达性 —— ✅ rev.5 关闭（设备前提已确认）

**位置**：`UsbCameraSource.start` 第 147-170 行（接收器注册）；`requestPermission` 第 190-201 行。

**rev.4 时的问题描述**：以 `Context.RECEIVER_NOT_EXPORTED` 注册，同时监听系统 `ACTION_USB_DEVICE_ATTACHED/DETACHED`；授权回调经 `PendingIntent.getBroadcast(... FLAG_MUTABLE)` + `usbManager.requestPermission` 触发。Android 13+ 允许"仅供系统广播"的接收器使用 `NOT_EXPORTED`，但社区存在 USB 授权 PendingIntent 在该组合下不回调的案例报告，静态审查无法判定。失败模式是"用户点允许后界面无反应"。

**关闭依据（设备前提）**：当前测试设备**已按包名全开权限，USB 访问预先授予**，授权弹窗不会出现，`hasPermission()` 恒为真 —— 这条广播路径在实际使用中不被走到，因此不构成本项目的风险。

**代码已留档**（`UsbCameraSource.start`）：注释写明 `NOT_EXPORTED` 使投递行为取决于平台而非本代码控制，当前测试机上因预授权而未被验证，**任何非预授权设备或转为正常分发时都必须重新验证整个接收器**（授权结果 + attach/detach 广播）。这条限定的意义是防止"在测试机上没问题"被误当成"在生产上没问题"。

**⚠️ 该结论与设备策略绑定**：一旦设备的授权策略变化（例如换测试机、清除包名白名单、或应用以普通应用身份分发），本项需要重新开启并按三条路径验证——首次授权、拒绝后重试、运行中热插拔。报告不再把它列为待办，但保留此说明。

### P1-3 🆕 向导缺少俯仰角输入，导致新增的几何提示无法被验证（rev.4 新增）

**位置**：`MainActivity` 第 990 行（`parsePitchDegrees`）、第 944-961 行（向导新增的俯仰输入）、第 41-53 行（相关常量）。

**问题（rev.4 发现时）**：安装向导只采集车型高度与 HFOV，`initialPitch` 硬编码为 `4.0`：

```java
double initialPitch = 4.0;                    // 用户无从修改
CameraCalibration.fromWizard(width, height, heightMeters, hfov, initialPitch);
```

由此产生两个后果：

1. **用户无入口**：实车安装后已知支架角度（例如 13°）的用户，想直接填初值省掉自学习等待，没有可输入的地方
2. **修复无法验证**：`AdasSimulator` 所有场景都用固定的 4.0° 标定（`MainActivity:801`），此条件下 ROI 远带 10.84 m ≥ 4 m 始终可见，**几何拒绝永远不会发生**，因此 `21679fa` 新增的"安装角度超出可学习范围"提示在室内模拟与台架上都无法触发，只能靠实车极端安装验证

**rev.4 已处理**：向导第 3 项新增"初始俯仰角"输入框

| 实现点 | 说明 |
| --- | --- |
| 输入默认值 | 已有可用标定时预填其 pitch，否则当前填 `8.0°`（rev.4 历史记录中的 `4.0°` 已过时） |
| 范围校验（历史 rev.4 描述） | 当时记录为 `[-5, +20]`；当前向导实际限制为 `[-5, +14]`、默认 `8.0°`，运行时 ROI 守卫仍负责高度相关的可见性判断 |
| 超出 ROI 上限的引导 | 当前填 ≥ `STEEP_PITCH_WARNING_DEGREES = 14.0` 时保存提示改为"当前俯仰角较大，若标定栏未开始收敛请按提示调整支架角度"，与几何拒绝提示形成闭环 |
| 说明文案 | 明示"已知角度直接填，未知保持默认" |

**仍开放的边界**：当前向导上限为 `[-5°, +14°]`，有效 ROI 上限仍随安装高度变化，具体拒绝由 learner 的 ROI 守卫运行时判定；向导不复制该高度相关公式。若实车发现误接受（H 偏低 + 角度偏大），再考虑在向导内联高度检查。

---

## 6. P2 — 可维护性与一致性（rev.7：全部关闭）

> **rev.5 状态**：P2-2、P2-4、P2-5、P2-6、P2-7、P2-9、P2-11 随 `8b733a0` / `c6c1b46` 一次性闭环。叠加此前已闭环的 P2-1、P2-3、P2-10，以及按宿主机制关闭的 P2-8，**本级别全部关闭**。
>
> 历史口径：rev.4 时为 8 组待处理（P2-2、P2-4、P2-5、P2-6 剩余、P2-7、P2-8、P2-9、P2-11）。

历史 P2 编号保持原样用于追溯；当前工作区另有一组未提交的健壮性加固：

- `AlertAudio` 高优先级播放会停止低优先级 SoundPool 流和 ToneGenerator，`stop()` 同时清理优先级锁。
- `MainActivity` 对 LiteRT 运行时异常使用有界指数退避；车速链路要求 FINE，COARSE 不再被当作有效车速来源。
- `UsbCameraSource` 覆盖 opening/stream watchdog、SurfaceTexture 单次释放、serial 身份和 MJPEG/YUYV 候选协商。
- `LaneDepartureDetector` 使用跨帧位置连续性抑制同侧亮线竞争；`LeadVehicleMotionEstimator` 在 `LOST`/重获时清除速度历史。

上述加固均未在本轮运行 Gradle、真机或实车验证，不能把静态契约测试统计当成通过证据。
代理曾用 `javac + JUnit` 独立验证车道 12、运动 4、跟踪 19、USB 契约 5 个重点用例；Gradle 启动因环境 `Unable to establish loopback connection` 未完成。

### P2-1 `VehicleOverlayView` 重复且未使用的 import —— ✅ rev.3 已闭环

`6599789` 已删除重复的 `Paint` / `Path` / `Shader` / `AttributeSet` / `View` 与未使用的 `Insets` / `WindowInsets` 共 6 行。当前文件第 3-14 行为单一 import 列表，零行为影响。

### P2-2 `AlertAudio.status()` 的 READY 判定被 `toneGenerator` 短路 —— ✅ rev.5 已闭环

**原问题**（第 102-120 行）：只要软件音发生器建立成功（多数设备都能建立），即使四个 wav 全部加载失败也报 `READY`。后果是 `MainActivity:359` 的"报警音不可用，请检查车机音频通道"在 wav 损坏/丢失场景下**永不显示**，用户误以为有完整音频。

**rev.5 处理**：`READY` 改为要求 `allSamplesLoaded()`（四个 wav 全部加载成功）；tone fallback 只在播放阶段兜底，不再作为"就绪"的证据。`testSound()` 补注释说明它为何只占最低优先级（保证真实 FCW 仍可抢占测试音）。

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

### P2-4 死代码：摄像头身份校验未接线 —— ✅ rev.5 已闭环（`MainActivity` 部分随 `8b733a0`）

| 成员 | 定义处 | rev.5 状态 |
| --- | --- | --- |
| `CalibrationStore.loadStatus(String expectedCameraId)` | `CalibrationStore:61` | ✅ 已接线（经 `reloadCalibrationForCamera()`） |
| `AutoCalibrationLearner.lastVanishingX()` / `lastVanishingY()` | `AutoCalibrationLearner:366,370` | ⏳ 仍零调用（灭点信息未进日志/UI，无害） |
| `FrameDispatcher.size()` | `FrameDispatcher:78` | ⏳ 仅测试使用（生产不暴露队列水位，无害） |

**原问题（代码根因）**：`load(expectedCameraId)` 仅在 stored ID 非空时比对（第 51-59 行），从未写入过 ID 的历史标定被无条件放行；且 `MainActivity` 在 `onCreate` 走的是无参 `loadStatus()`，身份校验 API 实际从未被调用。

**rev.5 处理**：新增 `MainActivity.reloadCalibrationForCamera()`，在摄像头**实际打开后**按当前 camera id 重新装载标定、状态与进度：

- 有匹配标定 → 装载
- 无匹配且**存有 ID** → 判定换摄像头，失效旧标定
- 无匹配且**无存储 ID** → 判定为"无法归属"，同样失效（见下方行为变更）
- 摄像头已实际打开但没有稳定 serial → **清除当前米制标定并要求重新标定**；USB 地址、deviceName 和 VID/PID 都不作为持久身份
- 尚未选中设备或页面刚停止时不会进入该回调，因此不会因瞬时断连误清

**行为变更（已确认接受，`43161c0` 已留档）**：早期版本保存的、未记录 camera id 的标定会被失效一次，车辆需要重新标定一次。

该取舍已由项目方拍板：**当前处于测试阶段，没有需要兼容的老用户**，因此不做一次性迁移（补写旧记录的 camera id），而是直接视为"无法归属"。理由——"无 ID"无法证明该标定属于当前摄像头，继续沿用等于保留"同分辨率换摄像头继承旧 pitch"这一缺陷。`CalibrationStore` 与 `reloadCalibrationForCamera()` 的注释均已写明这是**有意选择而非遗漏**，并注明"若将来面向真实用户发布、升级会丢失有效标定时需重新评估"。

### P2-5 `@string/calibration_required` 为"僵尸资源" —— ✅ rev.5 已闭环

**原问题**：`activity_main.xml:45` 用它作 XML 初值，但全代码零 `R.string.calibration_required` 引用，`updateCalibrationStatus` 渲染的是 `calibration_unset`。启动到首帧之间显示的是"点击设置摄像头标定；绿色区域为车道估计…"，与稳态文案不一致。

**rev.5 处理**：布局初值改为 `@string/calibration_unset`，删除死字符串。资源引用完整性已校验（24 个定义 / 23 个 Java 引用 + `app_name` 供 manifest 使用 / 布局引用全部有定义，无未使用项）。

### P2-6 冗余计算与跨线程取数 —— ✅ rev.5 已闭环

`6599789` 已把 `cameraSource.capturedFrames()` 上提到 `renderMetrics()` 开头，消除了同一周期内取两遍的问题。**rev.5 补完剩余项**：

- `fitPreview()` 现缓存上次应用的 letterbox 缩放（`appliedScaleX/Y`），缩放未变时直接返回，不再每秒做 4 次 `new Matrix()` + `setTransform()` 的无效视图更新；并补 `result == null` 前置判断
- 跨线程取数（每 250 ms 分别加锁读三处 metrics）**保留**：官方 API 如此，单次开销可忽略，为消除它引入快照层属过度设计

### P2-7 `onCreate` 中 insets 监听器早于 `overlayView` 赋值 —— ✅ rev.5 已闭环

**原问题**（第 104-111 行）：监听器注册时 `overlayView` 尚未赋值，依赖"insets 实际派发在下一帧 `ViewRootImpl.doTraversal()`"这一框架时序才安全。

**rev.5 处理**：把 `setOnApplyWindowInsetsListener` + `requestApplyInsets()` 整体移到所有 `findViewById` 之后，并在注释中写明原因，消除对框架调度顺序的隐性依赖。

### P2-8 无 `WakeLock`、无前台 Service，息屏可能静默降级 —— ✅ rev.5 关闭（设备机制已确认）

**原问题描述**：全仓库零 `PowerManager` 引用，`AndroidManifest.xml` 仅有 `MainActivity` 一个组件，布局与 Activity 均未设置 `keepScreenOn`。若设备让显示屏休眠，系统会限频 CPU，而"帧仍偶尔到达"使 `fresh` 判定（750 ms `MAX_OBSERVATION_GAP_NANOS`）不触发告警——**不是"没做后台能力"，而是"息屏后可能静默降级为不报警"**。

**关闭依据（设备机制）**：目标宿主是**车机、屏幕常亮不休眠**，因此"息屏导致限频"这条路径不可达，本项目的风险为空。不需要为此引入 WakeLock 或前台 Service——那会额外增加后台资源占用与权限声明，而收益为零。

**代码已留档**（`MainActivity.onStop` 的 Javadoc）：写明分析只在 activity started 期间运行、相机与工作线程在 `onStop` 全部释放，因此**本原型依赖宿主让它保持前台**；并注明"没有持 WakeLock、没有前台 Service，若宿主让屏幕休眠则会限频，管线会以不断下降的频率继续上报 fresh 结果而不报警——目标车机不休眠，该路径在此不可达；换宿主前须重新核对这个假设"。

**该结论同样绑定宿主策略**：换到会休眠的设备（平板、手机、其他车机）时本项重新成立；另外**用户主动退出到后台或切换应用**会走 `onStop` 正常停机，这属预期行为而非降级，但需产品侧确认"行驶中允许应用失去前台"是可接受的使用方式。

### P2-9 `FrameDispatcher.await()` 超时计算存在脆弱点 —— ✅ rev.5 已闭环

**原问题**：`remainingNanos ≤ 999_999` 时 `waitMillis = 0`、`waitNanos > 0`，此时 `Object.wait(0, n)` 不会立即返回（与 `wait(0)` 语义不同），故当前安全；但一旦被"优化"成 `wait(waitMillis)`，将退化为最多 1 ms 忙等（5 Hz 下每秒约 200 次自旋）。

**rev.5 处理**：按纳秒余数选择重载——余数非零时明确写 `wait(waitMillis, waitNanos)`，为零时写 `wait(waitMillis)`，并在注释中说明"若折叠成 `wait(millis)`，毫秒为 0 时会变成无限等待"。这是**语义正确的形态**（不是仅靠注释约束），后续误改会立刻破坏行为而非静默变成忙等。

### P2-10 标定守卫拒绝时 UI 无区分提示 —— ✅ rev.4 已闭环（`21679fa`）

**rev.3 的问题**：两道门槛（灭点窗口 + ROI 可见性守卫）拒绝采样时只写日志，`StepResult` 只带 `status` / `progressPercent`，`MainActivity.updateCalibrationStatus` 因此只能渲染"标定自学习中 0% · 请保持直行"——用户看到的是"再开开就好"，而真实原因可能是"这个安装角永远学不出来"。

**rev.4 的处理**：

| 实现 | 位置 |
| --- | --- |
| `Rejection` 四态分类（`NONE` / `DRIVING_CONDITION` / `VANISHING_OUT_OF_RANGE` / `ROI_NOT_VISIBLE`） | `AutoCalibrationLearner:24-38` |
| 几何类拒绝累加 `consecutiveGeometricRejections`，驾驶条件类清零 | 同文件 `reject()` 第 281-290 行 |
| 阈值 `GEOMETRIC_REJECTION_HINT_THRESHOLD = 5`（≈1 s @5 Hz） | 同文件第 71 行 |
| `showAngleOutOfRangeHint()` 挂在 `WIZARD_COMPLETED` 与 `CALIBRATING` 两态 | `MainActivity:601-617` |
| 文案 `calibration_angle_out_of_range` | `strings.xml` |

**设计要点**：驾驶条件类拒绝（速度、车道质量、居中）**必须清零**而不是累加——等待正是用户该做的事，把它们计入会让正常驾驶误报"安装角超范围"。

### P2-11 `MainActivity` 未使用的 `ViewGroup` import —— ✅ rev.5 已闭环

`import android.view.ViewGroup;` 零引用（`activity_main.xml` 的 `LinearLayout` 由布局 inflate 创建，代码未直接引用该类型）。已删除，并全仓库确认无其他残留。

**P2 处理优先序**（rev.5）：**本级别全部关闭**。跨级别的其余待办：

| 项 | 状态 |
| --- | --- |
| P0-1 延迟收口 | 🔶 等实测日志 |
| 守卫阈值 / 收敛门槛实车标定 | ⏳ 需真实车道帧 |

P2-4 附带的行为变更已确认接受（测试阶段无老用户），不再列为待确认项——理由随 `43161c0` 写入 `CalibrationStore` 与 `reloadCalibrationForCamera()` 的注释。

P1-2（USB 广播可达性）因测试机按包名预授权而在本项目内关闭；代码注释已写明该结论绑定设备策略，换设备或转为正常分发时须重新验证。

~~P2-1~~、~~P2-2~~、~~P2-3~~、~~P2-4~~、~~P2-5~~、~~P2-6~~、~~P2-7~~、~~P2-9~~、~~P2-10~~、~~P2-11~~ 已闭环。

---

## 7. 测试与文档一致性偏差

### 7.1 rev.3：5 处偏差已全部同步 ✅

rev.1/rev.2 列出的 5 处文档偏差已在 `6599789` 中随两份文档一并修正：

| # | 原偏差 | 处理 |
| --- | --- | --- |
| 1 | `FCW_CONFIRM_MILLIS=400` 且"未参与判定" | 改为 200 且已参与，并写明"连续 3 个分析帧且跨度 ≥200 ms" |
| 2 | "速度 ≥45 km/h 时普通提醒为 THW≤1.2 s 或 ≤8 m" | 改为 **15 km/h**，并补低速段与停车段行为 |
| 3 | "9 个 JVM 测试类、约 75 个方法" | 历史版本曾同步为 10 类 / 81 方法；当前工作区已增至 **14 类 / 143 个 `@Test` 方法**，本轮未执行 |
| 4 | "`USBMonitor.openDevice()` 原始控制块当前未保存" | 已改写 |
| 5 | "LiteRT 连续 3 次异常…没有退避" | 已改写 |

同时同步了性能基线（31 FPS 采集、推理计时口径）与新增的 ROI 守卫说明。

> **当前同步**：历史记录中的 81 是 rev.4/rev.5 时点；当前工作区静态扫描为 14 个测试类、143 个 `@Test` 方法，不能据此声称 Gradle 测试通过。

### 7.2 归因（历史记录）

复核计划 `docs/superpowers/plans/2026-09-17-adas-project-review.md` 的 Task 1（建立当前规则与链路基线）未按代码逐行收口，7.1 中的条目系沿用旧版描述。**性能基线偏差（15–20 FPS vs 实测 31 FPS）也已一并同步**，不再列为待确认项。

### 7.3 测试覆盖缺口（rev.5 复核）

- 无 Android instrumented 测试
- 无真实 NV21 车道帧测试（`LaneDepartureDetectorTest` 用合成图；`AdasSimulator` 直接注入可用 Observation，两者不能互证）
- 无 Android instrumented UVC 热插拔、LiteRT 真模型、音频出声、Activity 重建测试；新增的 `UsbCameraSourceTest` 只验证纯 Java 的候选格式、serial 身份和 SurfaceTexture 返回契约
- 新增的 `LaneDepartureDetectorTest` 与 `LeadVehicleMotionEstimatorTest` 覆盖同侧竞争、缺端点样本和 `LOST` 重获速度重置，但仍不能替代真实 NV21/道路遮挡验证
- ~~无端到端延迟测量~~ —— **rev.6/rev.7 已补**：真机 `Age` 31 条 + 分段 `Split=` 11 条 + FCW 场景日志。管线处理 78.5 ms、排队 0、测量段 ≈180 ms、确认段 ≈400 ms、端到端 ≈580 ms
- 无"低速蠕行 HMW 行为"与"目标切换冷却"的显式用例（原 P0-1、原 P1-4 的定性分歧即源于缺少这类把产品意图写进测试的用例）
- **rev.3 新增**：`6599789` 为 ROI 守卫补了 4 个用例（`laneRoiGuardRejectsSteepPitchWhereTheFixedRoiLooksAtTheHood`、`vanishingWindowStaysInsideTheLaneRoiGuard`、`rejectsSamplesWhenTheConfiguredPitchPutsTheRoiOnTheHood`、`reachesCalibratedForATallVehicleWithinTheGuard`），把"窗口上限必须落在守卫内侧"固化为不变量
- **rev.4 新增**：`21679fa` 为拒绝分类补了 4 个用例（`reportsGeometricRejectionsSoTheUiCanAskForReaiming`、`reportsVanishingPointOutOfRangeAsGeometricToo`、`drivingConditionRejectionsDoNotCountAsGeometric`、`resetClearsRejectionTracking`）
- **rev.4 仍缺**：
  - `MIN_ROI_FAR_DISTANCE_METERS = 3.5` 的来源是几何推导 + 经验值，**缺真实图像的守卫阈值验证**
  - 新增的几何拒绝提示在模拟中无法触发（所有场景固定 4.0° 标定）——向导虽已加俯仰角入口，但 `AdasSimulator` 仍用固定标定，故**该提示路径仍只能靠实车验证**
  - 向导的 `parsePitchDegrees` 范围校验属 Activity 私有逻辑，JVM 单测无法覆盖（依赖 `android.util.Log` 等 Android 类）

---

## 8. 建议处理顺序（rev.7 历史 + 2026-09-18 工作区）

**代码侧可立即处理的项已全部清零，且唯一剩下的 P0 经实测后判定为"不改代码"。**

这句话仅描述 rev.7 历史基线。当前未提交工作区已针对同侧车道竞争、`LOST` 重获速度污染、LiteRT 运行时重启、USB 生命周期/协商、精确定位权限和报警音抢占加固；剩余工作是重新验证几何投影、运行验证与参数验收，不应再沿用“源码没有这些保护”的旧风险描述。

| 序 | 项 | 阻塞于 | 说明 |
| --- | --- | --- | --- |
| 1 | ~~P0-1 降帧数收口~~ | — | **rev.6 实测后不采纳**：确认段实测 ≈400 ms（非 600 ms），降帧数只省 ≈133 ms，而确认强度无法在模拟中验证；余量仍有 ≈1.78 s |
| 2 | ~~P0-1 替代方向：压缩单帧处理成本~~ | — | **rev.7 实测后降级**：管线仅 78.5 ms、排队为 0，车道检测增量很小，收益上限仅几十毫秒 |
| 3 | 标定参数实车标定 | 真实车道帧 | `MIN_ROI_FAR_DISTANCE_METERS`、`MAX_CONVERGENCE_MAD_DEGREES`、提示阈值 5 次 |
| 4 | 若仍要降帧数 | 真实道路 FCW 误报率基线 | 先有误报基线，才能判断"确认强度换 133 ms"是否划算 |
| 5 | 测量段精确分解（可选） | 无（可直接做） | 在 `processAdasFrame` 记录决策时刻的帧年龄并输出到心跳，去掉 250 ms 心跳相位造成的 108–322 ms 离散 |

> **已按外部输入关闭**（不计入待办）：
> - **P2-4 附带行为变更** —— 测试阶段无老用户，不做迁移（`43161c0` 已把理由写进代码注释）。
> - **P1-2 USB 广播可达性** —— 测试机按包名预授权，该路径不被走到；结论绑定设备策略。
> - **P2-8 息屏静默降级** —— 宿主是常亮车机，限频路径不可达；结论绑定宿主机制。

**已闭环（留档）**：

| 项 | 闭环于 |
| --- | --- |
| P0-2 残余 + P2-10 守卫拒绝原因进 UI | `21679fa` |
| P1-3 向导俯仰角入口 | `ee79474` |
| P2-2 / P2-5 / P2-6 / P2-7 / P2-9 / P2-11 | `8b733a0` |
| P2-4 摄像头身份校验接线 | `8b733a0`（activity）+ `c6c1b46`（store） |
| P1-1 LDW 标定门控 / P1-2 叠加层尺寸校验 / 原 P1-3 USB 打开超时 | `6599789` |
| P2-1 import 清理 / P2-3 丢帧计数分离 / 文档 5 处偏差 | `6599789` |
| 原 P0-4 模拟禁写盘 / 原 P1-1 锚点对齐 | rev.2 撤销（第 4.2.1、4.2.2 节） |
| 原 P0-1 HMW 速段 / 原 P1-4 冷却语义 | 重定性为产品权衡（第 4.2.3、4.2.4 节） |

> **rev.6 相比 rev.5**：P0-1 由"等实测"变为"实测完成、判定不改代码"。在此历史结论之外，当前工作区还需验证本轮加固的真实设备行为；剩余参数项仍分别需要真实车道帧（标定参数）、真实道路误报基线（若要降帧数）和可选的仪表精化。

---

## 9. 后续验证清单（rev.7）

**必须在目标设备完成**：
1. ~~P0-1 延迟实测~~ —— **rev.7 已完成**：模拟得确认段 `Confirm=413 ms`；真机得 `Split=1+0+78ms`（n=11）与 `Age` 94–329 ms（n=31）。⚠️ 两点口径提醒：`Age` 在模拟中恒为 0（帧由 `AdasSimulator` 即时打时间戳）；`Age` 本身含 127 ms 均值的心跳相位，**判定延迟请用 `Split=`**
2. **本轮同侧车道竞争**：用真实 NV21 帧放入同侧更亮接缝/邻近标线，确认位置连续性不会在 ±0.07 窗口内被亮度反转；冷启动和连续丢线后的重锁也要记录
3. **`LOST` 重获速度**：遮挡目标后重获同/新 ID，确认首个有效样本 closing speed 从 0 重新开始，不把遮挡间隔的距离跳变当成逼近
4. **LiteRT 运行时异常**：注入模型调用异常，确认 5/10/20/40 s 退避、解释器释放和消费线程不会快速重启打满 CPU
5. **定位权限**：仅授予 COARSE 时确认界面提示需要 FINE 且车速链路保持失效；授予 FINE 后再验证新鲜度、质量和加速度门控
6. **音频抢占**：先播放低优先级 HMW/LDW，再触发 FCW/HMW_CRITICAL，确认旧 SoundPool 流和 ToneGenerator 立即停止
7. **换摄像头后的标定失效行为**：用两台同分辨率 USB 摄像头交替接入，确认第二次接入时旧标定被失效并提示重新标定（`reloadCalibrationForCamera`）—— 对应 P2-4
8. 破坏一个 `alerts/*.wav` 后启动，确认 UI 出现"报警音不可用"而不是仍显示 READY —— 对应 P2-2
9. 长稳运行 30 min，记录分析率与漏报（车机常亮，无需专项息屏测试）—— 对应 P2-8 的关闭前提
10. 逐一尝试 1280×720、640×480、1920×1080 和 YUYV 候选，记录 AAR 实际接受格式及 NV21 尺寸校验；确认降级时叠加层不绘制错位车道
11. **USB 广播路径（条件性）**：仅当在未预授权的设备上使用、或应用转为正常分发时才需要——首次授权、拒绝后重试、运行中热插拔

**必须在实车完成**：
 12. 标定收敛性验证：确认 +12.7° 上限可用、ROI 守卫不误拒正常安装 —— 对应已闭环 P0-2 的回归
 13. **在向导中填入已知安装角**（≥14° 触发陡角提示），确认保存后提示与后续行为一致 —— 对应已闭环 P1-3
 14. **几何拒绝提示验证**：支架俯仰调至 ≥15° 后行驶，确认标定栏切换为"安装角度超出可学习范围"（该路径在模拟中无法触发，见第 7.3 节）—— 对应 `21679fa`
 15. 真实 NV21 车道帧的 LDW 可用性（白天 / 夜间 / 逆光 / 雨雾）—— 对应已闭环 P1-1 的回归
 16. 已知距离静态场景的测距精度 —— 全链路基础
 17. **守卫阈值标定**：用真实车道帧核对"远带 4 m 之外车道线是否仍可稳定检出" —— 对应 P0-2 的开放项
 18. （可选，非缺陷）低速蠕行段 HMW 提醒曲线实测，确认"8 m 米数保底"在实车上的可接受度 —— 对应第 4.2.3 节

---

## 10. 本次审查自身的缺陷记录

审查报告的价值取决于它的错误也被记录。rev.1–rev.7 期间，本报告及其配套代码出现过 4 次实质错误，其中 2 次造成过进程崩溃或误导性结论：

| # | 错误 | 性质 | 发现方式 | 修正 |
| --- | --- | --- | --- | --- |
| 1 | 原 P0-4"模拟标定会持久化污染" | **误判**：漏读 `persistCalibration = !simulationFrame` 的前置赋值 | 用户逐条复核 | rev.2 撤销（第 2.1 节） |
| 2 | 原 P1-1"带中心 ≈0.79" | **算术错误**：`(0.72+0.84)/2` 误算为 0.79，实为 0.78 | 用户逐条复核 | rev.2 撤销（第 2.1 节） |
| 3 | `Age=%.0fms` 传入 `long` | **类型不匹配 → 主线程崩溃循环**：`String.format` 抛 `IllegalFormatConversionException`，且 `renderMetrics` 无 try/catch | 用户设备实测 | `6534ca0` 修复；格式化抽到 `AdasLogFormat` 并补单测 |
| 4 | 把心跳 `Age`（202 ms）当作测量段延迟 | **口径错误 → 结论偏差约 2.6 倍**：`Age` 含 127 ms 均值的心跳相位，真实管线成本仅 78.5 ms | 用户跑出 `Split=` 分段数据 | rev.7 修正（第 3.1 节） |

**共同的方法论缺陷**：

1. **第 1、2 项**：引用代码片段时未通读所在方法的完整上下文；对算术结果未做二次核验。→ 现已改为对每个引用位置回读全方法、对每个数值用脚本复算。
2. **第 3 项**：把 `String.format` 内联在 Activity 中，使其**结构上无法被 JVM 单测覆盖**；且用"数格式符个数"代替"核对实参类型"——后者恰好看不见类型不匹配。→ 现已把日志格式化抽为纯 Java 的 `AdasLogFormat`，并由 `AdasLogFormatTest` 用真实参数类型调用 `String.format`。
3. **第 4 项**：在**未验证指标语义**的情况下用 `Age` 反推链路延迟，且未设计交叉校验。→ 现已补分段仪表；并在报告中标明 `Age` 与 `Split` 合计**不相等**、不可互校。

**结论**：这 4 项里有 3 项是**用户付出执行成本才暴露的**（编译、设备实测），说明静态审查能给出的置信度上限取决于是否有执行反馈。本报告建议：**任何涉及格式串、类型转换、时序口径的改动，都必须在设备上跑一次再判定。**

---

## 附录 A：本报告与代码的关系

- 代码/测试：审查过程只读；**报告作者按报告第 8 节执行了修复**（`ee79474`、`8b733a0`、`c6c1b46`、`fdddffc`、`6534ca0`），这些提交的内容与其中出现过的错误均在第 0 节与第 10 节记录
- 报告自身改动仅限本文件（`HCT_ADAS_Code_Review_Report.md`）
- **rev.5 基线**：HEAD `c6c1b46`；rev.4 为 `32ea041`，rev.3 为 `6599789`，rev.1/rev.2 为 `9ac35f0`
- 行号引用：凡涉及变更文件的行号均按当前基线核对；rev.1/rev.2 遗留的行号未回溯迁移，仅作历史记录

## 附录 B：审查中确认的正确实现（易被误判为缺陷）

| 项 | 位置 | 说明 |
| --- | --- | --- |
| 推理在锁外执行 | `MainActivity.onFrame:184` | 仅"转移 + 短分析"进锁，避免长时持锁 |
| native 缓冲快照 | `UsbCameraSource:261-264` | 按注释"返回即复用"做 `duplicate()` + 拷贝，只拷采样帧 |
| `lastFrameNanos` 与队列同锁 | `UsbCameraSource:265-271` | 与 `invalidatePreview` 的 `discardPending()` 原子，避免旧 token 复活 |
| 过期间隔立即重置分析态 | `MainActivity:192-204` | 750 ms 无观测即清状态，不沿用陈旧目标 |
| 重放/乱序帧防护 | `LeadVehicleTracker:41-43` | `now <= latest.timestampNanos()` 直接返回上次快照 |
| `detect()` 失败退避 | `MainActivity` | 初始化失败 5 s；运行时异常先关闭失败解释器，再按 5/10/20/40 s 有界指数退避，成功清零；避免模型损坏时高频重启 |
| 消费线程重启前释放解释器 | `FrameConsumer` / `MainActivity` | worker 的 `onStopped()` 仍负责重启清理；推理异常路径会在退避前主动关闭当前解释器 |
| `NaN` 不被当 0 使用 | 全链路 | 速度/距离/置信度无效统一静默，方向正确 |
| **模拟帧写盘守卫** | `MainActivity:614` | `persistCalibration = !simulationFrame`，两条持久化路径（第 627、634 行）均已受控——rev.1 曾误判为缺陷，见第 4.2.1 节 |
| **车道带中心与常量一致** | `LaneDepartureDetector` | 当前 ROI 为 `[0.54,0.82]`，发布锚点仍为 `Y_TOP=0.60`、`Y_BOTTOM=0.78`；缺端点时由 0.24 跨度和样本数量门控处理 |
| **低速段绝对距离保底** | `AdasDecisionEngine:161` | 无条件兜底 `distance <= 8 m`，1–15 km/h 全段有米数保护——rev.1 曾误判为断层，见第 4.2.3 节 |
| **目标切换双重防重复** | `AdasDecisionEngine:311-315` | `dangerousFrames` / `dangerSinceMillis` 复位 + 冷却保留，共同防连续蜂鸣——rev.1 曾误判为缺陷，见第 4.2.4 节 |
| **标定门控不变式** | `MainActivity:612,658` | `calibrationStatus == CALIBRATED ⇒ calibration != null`：该状态仅由 `loadStatus()`（内部含 null 检查）与 `step.status()`（learner 仅在返回非空收敛结果时置位）产生，故 `LaneObservation` 门控无需再判 null |
| **窗口与守卫边界自洽** | `AutoCalibrationLearner` | VP 兼容窗口仍受 ROI 守卫约束；真实 widthSamples 主路径使用 implied pitch/MAD，实际阈值需实车验证 |
| **守卫是物理判据而非角度上限** | `AutoCalibrationLearner:272-277` | 同为 20° 俯仰，H=1.25 被拒（远带 ROI 3.01 m < 4 m）而 H=2.0 通过（4.81 m）——按"ROI 是否还看得到路"判定，避免一刀切 |
| **模拟场景的 LDW 例外** | `MainActivity` | 门控写作 `!simulationFrame && calibrationStatus != CALIBRATED`，模拟帧有意放行：`AdasSimulator` 的 LDW 场景需要注入合成车道，不这样写会让该场景永远不告警 |
| **几何提示挂在两个前置状态** | `MainActivity:601-617` | `showAngleOutOfRangeHint()` 同时用于 `WIZARD_COMPLETED` 与 `CALIBRATING`——几何坏掉时 status 不会进入后者，只挂后者会让提示不可达（自审时修正过） |
| **`resetSamples()` 不清拒绝计数** | `AutoCalibrationLearner:345-353` | 计数清零只在接受样本、驾驶条件拒绝、`reset()` 三处。`resetAnalysisState()` 走 `resetSamples()`，若在此清计数，间歇丢帧会让提示永远到不了阈值 |
| **延迟仪表用采集时间戳** | `MainActivity:694-713` | `Age` 基于 `result.timestampNanos()`（UVC 采集时刻），因此覆盖采样节流 + 预处理 + 推理；`Confirm` 由 `[DANGER-START]` 边沿起算，目标切换与分析重置均重新武装，不会跨目标继承 |
| **同侧亮线连续性门控** | `LaneDepartureDetector:307-417` | 热启动使用上一帧位置窗口与位置惩罚；亮度只作候选证据，仍需真实道路竞争验证 |
| **LOST 重获速度清理** | `LeadVehicleMotionEstimator:19-75` | `LOST`/ID 变化/时间间隔异常时不沿用旧速度，下一有效帧从零开始估计 |
| **USB 与音频加固** | `UsbCameraSource` / `AlertAudio` | SurfaceTexture 单次释放、opening/stream watchdog、serial 绑定、多格式候选；高优先级音频主动停止低优先级流 |

---

*报告主体保留 rev.1→rev.7 的历史审查记录；当前工作区同步截至 2026-09-18，未提交、未构建、未做设备或实车验证。历史行号只用于追溯，当前加固摘要以第 0.4 节和附录 B 为准。*
