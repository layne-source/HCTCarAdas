# ADAS 项目级链路复核与方案文档收口 Implementation Plan

> **状态（2026-09-22）：** 复核和文档刷新已完成。当前代码基线为 `bff24dd`；产品文档已同步到 FCW/HMW/LVSA、固定参考带、三类声音开关和设置弹窗的收口状态。本次按用户授权以独立文档提交保存。

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 依据当前 Java 实际实现，复核采集、标定、测距、跟踪、决策、音频和 UI 链路，并让五份产品文档准确反映当前规则、限制和剩余验收边界。

**Architecture:** 代码保持只读，先从入口到报警输出建立场景矩阵，再修正文档中的实现口径和风险清单。文档明确当前是前台原型与“俯仰学习收敛”标定，不把模拟测试或单元测试扩大为实车证据。

**Tech Stack:** Java Android 16/Android 13+、USB UVC、NV21、LiteRT、GPS、SoundPool/ToneGenerator、Markdown。

**Spec:** 当前仓库中的五份根目录产品文档、`docs/superpowers/plans/` 复核记录，以及 `app/src/main/java/com/hct/adas/` 的实际实现。

## Global Constraints

- 不修改 Java/XML 生产代码；转向灯、横摆率、方向盘角度和弯道补偿不纳入本轮。
- 不主动编译、构建、打包或运行项目；只做静态核对和 Markdown/Git 检查。
- 不把室内模拟、JVM 单元测试或上一版设备数据当作实车量产验证。
- 文档中的阈值必须与代码当前值一致，并注明产品冻结前仍需实车标定。
- 文档修改完成后使用独立提交，保留可回退边界。

### Task 1: 建立当前规则与链路基线

**Files:**
- Read: `app/src/main/java/com/hct/adas/*.java`
- Read: `app/src/test/java/com/hct/adas/*.java`

- [x] **Step 1: Record the scene gates**

记录 FCW、HMW、HMW_CRITICAL、LVSA、LDW 的速度、距离/TTC、持续时间、目标可见性、冷却和模拟/真实数据差异。

- [x] **Step 2: Record non-decision risks**

记录 GPS 质量、标定身份绑定、USB/native 资源、检测器重建、音频优先级、目标 ID 与 UI 保持状态等当前缺口。

### Task 2: 收口五份产品文档

**Files:**
- Modify: `HCT_ADAS_Feasibility_and_Architecture.md`
- Modify: `HCT_ADAS_Final_Solution_and_Implementation_Plan.md`
- Modify: `HCT_ADAS_Final_Design_Lane_Calibration_and_Outputs.md`
- Modify: `HCT_ADAS_Fixed_Guide_Design.md`
- Modify: `HCT_ADAS_Code_Review_Report.md`

- [x] **Step 1: Add an implementation-status boundary**

明确当前代码可运行原型、前台生命周期、标定仅学习 pitch、固定距离 HMW、软件媒体音频和未实车验证状态。

- [x] **Step 2: Add the scene decision matrix**

把当前阈值和触发条件写成可审查表，并列出目标丢失、速度无效、标定未收敛和尺寸不匹配时的降级行为。

- [x] **Step 3: Correct UI/audio/fault-recovery claims**

删除或改写“硬件蜂鸣保底”“实车后才启用声音”“完整精确标定”等与实现不符的表述，补充 UI 状态和故障恢复限制。

- [x] **Step 4: Add a量产前验证清单**

区分已有 JVM/模拟证据、静态审查证据和必须在目标设备/实车完成的测试。

### Task 3: 静态验证与提交

**Files:**
- Verify: modified Markdown files and repository Git state

- [x] **Step 1: Run Markdown/XML/Git checks**

执行 `git diff --check`，检查 Markdown 标题、表格和 XML 解析，不运行 Gradle。

- [x] **Step 2: Review the diff**

确认只包含五份产品文档和两份复核计划，代码无意外变化。

- [x] **Step 3: Commit**

使用独立提交保存文档收口，提交信息为 `docs: align adas plan with current implementation`。

用户于 2026-09-22 授权本地提交本次文档更新。
