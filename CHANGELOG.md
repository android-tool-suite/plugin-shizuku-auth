# 更新日志

## 1.0.0 - 2026-08-28

### 新增

- 提供合并授权界面、主页状态和原生底层能力的 format v3 `trusted-provider` 插件。
- 提供受限的 `shizuku.control` 与 `accessibility.manage` Capability，不暴露通用 Shell 接口。

### 架构

- 从宿主仓库迁移为独立插件仓库；仅通过已发布的 `plugin-sdk` 编译，宿主保留 Shizuku 生命周期所需的最小平台桥。
- 插件清单、UI 与原生 Provider 统一收敛到 `src/`，其中 Android 载荷单独位于 `src/android/`。

### 兼容性

- 基于插件 SDK `1.4.0` 的稳定 `.plugin.runtime` Provider API 编译。
