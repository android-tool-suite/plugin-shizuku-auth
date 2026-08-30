# Shizuku 授权

Android Tool Suite 的全信任 Shizuku Provider 插件。一个 format v3 包同时包含声明式授权界面、主页状态和原生 Provider，向普通插件提供受限的 `shizuku.control` 与 `accessibility.manage` Capability。

源码统一放在 `src/`：插件清单与 UI 位于根层，唯一需要 Android 编译的 Provider 位于 `src/android/`，不再维护含义重叠的 `runtime/` 与 `src/main/` 两套目录。

插件原生代码只依赖 Maven 坐标 `com.androidtoolsuite:plugin-sdk`，不依赖宿主工程源码。打包还需要 ATS CLI、Provider publisher 私钥和对应公钥：

```powershell
gradle `
  -PatsSdkRepository=<plugin-sdk Maven repository> `
  -PatsCliPath=<ats.py> `
  -PatsProviderSigningKey=<private.pem> `
  -PatsProviderPublicKey=<public.pem> `
  clean collectArtifacts
```

聚合工作区的 `tools/build-all.ps1` 会传入这些路径并把 `artifacts/shizuku-auth.atsplugin` 收集到外层产物目录。
