# ADAS Pipeline Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax.

**Goal:** 修复当前审查确认的车道竞争、目标重获速度污染、推理异常重启、USB 生命周期/身份/格式兼容、定位权限和报警音抢占问题，并同步项目文档。

**Architecture:** 保留现有 5 FPS、有界队列、LiteRT、ridge 车道和状态机架构，只增加局部连续性门控、异常退避、资源释放契约和输入降级。所有安全相关状态继续 fail-closed，不能把无效观测当成 0 或正常值。

**Tech Stack:** Java 17、Android API 33+、USB UVC AAR、LiteRT、JUnit 4 JVM tests、Markdown。

**Spec:** 本轮用户授权与 2026-09-18 全链路审查结果。

## Global Constraints

- 最小修改，不替换成熟链路或引入新依赖。
- 生产逻辑变更先有回归测试；Android 设备行为用静态检查和文档标注验证缺口。
- 不主动运行 Gradle/build/packaging；完成后执行 `git diff --check`、源码/测试统计和冲突审查。
- 文档必须以最终源码为准，删除已过时的缺陷描述。

### Task 1: Lane and motion hardening

**Files:** `LaneDepartureDetector.java`, `LaneDepartureDetectorTest.java`, `LeadVehicleMotionEstimator.java`, `LeadVehicleMotionEstimatorTest.java`

- [x] Add failing tests for multi-frame same-side distractor competition and LOST reacquisition velocity reset.
- [x] Add continuity/residual or replacement hysteresis with bounded cold-start behavior.
- [x] Clear or reinitialize motion history across LOST/reacquisition.
- [x] Align lane projection, signed offset and curvature sign with the published geometry contract;
  keep ground-range and optical-axis depth separate in curvature fitting.
- [x] Verify affected JVM tests and inspect diffs (pure Java harness；当时 Gradle 启动受 loopback 阻断，后续用户已确认 Java 编译成功)。

### Task 2: USB lifecycle and camera identity/format

**Files:** `UsbCameraSource.java` and focused tests if practical.

- [x] Fix `SurfaceTexture` destruction release ownership (listener releases and returns `false`).
- [x] Use serial number when safely available; fail closed when it is unavailable（历史方案；最终基线改为按图像分辨率绑定标定，不再使用 serial）。
- [x] Expand format/size negotiation without assuming unsupported AAR methods.
- [x] Verify source/API compatibility statically and with the focused Java harness.

### Task 3: Inference failure, location permission, and audio preemption

**Files:** `MainActivity.java`, `AlertAudio.java`, related JVM tests.

- [x] Add bounded detector runtime backoff and close the failed interpreter before retrying, separate from initialization retry.
- [x] Keep coarse location fail-closed and request precise location for speed-dependent ADAS.
- [x] Stop active lower-priority SoundPool streams when a higher-priority alert starts.
- [x] Verify pure Java behavior and inspect Activity/API usage statically.

### Task 4: Documentation alignment

**Files:** `HCT_ADAS_Final_Design_Lane_Calibration_and_Outputs.md`, `HCT_ADAS_Final_Solution_and_Implementation_Plan.md`, `HCT_ADAS_Code_Review_Report.md`, `HCT_ADAS_Feasibility_and_Architecture.md`, `HCT_ADAS_Fixed_Guide_Design.md`.

- [x] Update formulas, current status, remaining risks, test count, and lifecycle/format/permission limitations to match final code.
- [x] Remove stale claims that camera binding, open watchdog, LDW calibration gating, or audio preemption are absent.
- [x] Preserve explicit statement that real-device/road validation is still required.

### Task 5: Final verification

- [x] Review all agent diffs for ownership conflicts.
- [x] Run permitted static checks and `git diff --check`; do not claim tests/build pass without fresh output.
- [x] Summarize remaining unverified device risks. 当前代码基线为 `bff24dd`；产品文档刷新按用户 2026-09-22 授权以独立文档提交保存。
