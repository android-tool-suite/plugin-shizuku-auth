# Shizuku 授权

Android Tool Suite 的全信任 Shizuku Provider 插件。一个 format v3 包同时包含声明式授权界面、主页状态和原生 Provider，向普通插件提供受限的 `shizuku.control` 与 `accessibility.manage` Capability。

源码统一放在 `src/`：插件清单与 UI 位于根层，唯一需要 Android 编译的 Provider 位于 `src/android/`，不再维护含义重叠的 `runtime/` 与 `src/main/` 两套目录。

插件原生代码通过 Maven 坐标 `com.androidtoolsuite:plugin-sdk` 注册 Capability，通过 `dev.rikka.shizuku:api:13.1.5` 直接使用宿主共享的 Shizuku 客户端/Binder；两者使用 compileOnly，不打包第二份客户端，也不依赖宿主源码或 `TrustedPlatformBridge` 的操作方法。权限申请、系统设置和日志读取由插件实现。

当前 Shell 传输使用 Shizuku API 13 的 `IShizukuService.newProcess`，并限制执行超时、并行排空输出和保留字节数。上游已将该进程接口标为弃用并计划在 API 14 删除；插件显式检查版本，未来兼容迁移在本仓库完成，无需为新操作发布宿主/SDK。复杂 UserService 不能直接把动态插件类名传给标准启动器（其默认只加载已安装 APK），不能据此声称任意插件 UserService 已支持。

普通 Tool 仍仅能通过 Capability Router 调用受限日志/无障碍能力，不能拿到完整日志或 Shell。打包还需要 ATS CLI、Provider publisher 私钥和对应公钥：

```powershell
gradle `
  -PatsSdkRepository=<plugin-sdk Maven repository> `
  -PatsCliPath=<ats.py> `
  -PatsProviderSigningKey=<private.pem> `
  -PatsProviderPublicKey=<public.pem> `
  clean collectArtifacts
```

聚合工作区的 `tools/build-all.ps1` 会传入这些路径并把 `artifacts/shizuku-auth.atsplugin` 收集到外层产物目录。
