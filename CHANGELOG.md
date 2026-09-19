# 更新日志

## 1.0.0（2026-09-19）

### 界面与交互

- 授权页显示启动服务、授权应用、连接系统服务三个步骤，按当前状态突出请求授权或连接服务。
- 增加重新检查状态，未运行时给出启动指引；保留完全信任的权限范围说明。
- `system.logs.search` 支持可选 `lookbackMinutes`（0 为系统保留的全部），在 Provider 中按设备时钟筛选并返回实际范围；保留关键词、手势、大小与条数限制。
- 近期日志先按已授权关键词进行字面量预筛选，再限制返回大小与条数，避免云游戏链接被最后 2500 行无关系统日志挤出；保持用户手势、关键词 scope 和返回数量限制。

### 新增

- 新增通用、受限的 `system.logs` Capability，在用户主动操作后按 manifest scope 中声明的检索词读取有界近期系统日志匹配行。
- 提供合并授权界面、主页状态和原生底层能力的 format v3 `trusted-provider` 插件。
- 提供受限的 `shizuku.control` 与 `accessibility.manage` Capability，不暴露通用 Shell 接口。

### 安全与兼容性

- 仍不向普通插件暴露 Shell 或完整原始日志；Provider 只返回同时或任一命中已授权检索词的有限日志行。
- 通过 Shizuku 13 客户端/Binder 自行申请授权、读取日志与读写系统设置，不调用 Host/SDK 特权桥；输出上限、双流排空、超时与上游接口版本兼容由插件负责。普通消费者仍受 scope 与手势限制。
- 使用插件 SDK 2.0.0 的 Provider 注册契约，不依赖已移除的 API1 Tool 接口；最低宿主 versionCode 为 23。Shizuku 通用进程接口已被上游弃用，当前明确支持 API 13，未来传输适配仅需更新本插件。

### 架构

- 从宿主仓库迁移为独立插件仓库；通过 `plugin-sdk` 2.0.0 编译，宿主保留 Shizuku 生命周期所需的最小平台桥。
- 插件清单、UI 与原生 Provider 统一收敛到 `src/`，其中 Android 载荷单独位于 `src/android/`。
