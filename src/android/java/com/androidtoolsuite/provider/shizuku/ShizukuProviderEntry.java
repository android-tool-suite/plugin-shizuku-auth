package com.androidtoolsuite.provider.shizuku;

import android.Manifest;
import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import com.androidtoolsuite.app.plugin.runtime.CapabilityCall;
import com.androidtoolsuite.app.plugin.runtime.CapabilityFailure;
import com.androidtoolsuite.app.plugin.runtime.CapabilityProvider;
import com.androidtoolsuite.app.plugin.runtime.CapabilityRegistrar;
import com.androidtoolsuite.app.plugin.runtime.NativeProviderEntry;
import com.androidtoolsuite.app.plugin.runtime.ProviderContext;


import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Fully trusted Provider entry. It exports narrow capabilities and never exposes raw shell. */
public final class ShizukuProviderEntry implements NativeProviderEntry {
    @Override
    public AutoCloseable register(ProviderContext context, CapabilityRegistrar registrar) throws Exception {
        ShizukuTransport bridge = new ShizukuTransport();
        List<AutoCloseable> effects = new ArrayList<>();
        try {
            effects.add(registrar.register(new ShizukuControlProvider(context.applicationContext(), bridge)));
            effects.add(registrar.register(new AccessibilityProvider(context.applicationContext(), bridge)));
            effects.add(registrar.register(new SystemLogsProvider(bridge)));
            return () -> closeReverse(effects);
        } catch (Exception error) {
            closeReverse(effects);
            throw error;
        }
    }

    private static void closeReverse(List<AutoCloseable> effects) {
        for (int index = effects.size() - 1; index >= 0; index--) {
            try { effects.get(index).close(); } catch (Exception ignored) { }
        }
    }

    private abstract static class Provider implements CapabilityProvider {
        private final String id;
        private final Set<String> methods;

        Provider(String id, String... methods) {
            this.id = id;
            this.methods = Set.of(methods);
        }

        @Override public final String capabilityId() { return id; }
        @Override public final String version() { return "1.0.0"; }
        @Override public final Set<String> methods() { return methods; }
    }

    private static final class ShizukuControlProvider extends Provider {
        private final ShizukuTransport bridge;
        private final Context context;

        ShizukuControlProvider(Context context, ShizukuTransport bridge) {
            super("shizuku.control", "shizuku.getConnection", "shizuku.requestPermission", "shizuku.connect", "shizuku.openApp");
            this.context = context.getApplicationContext();
            this.bridge = bridge;
        }

        @Override
        public JSONObject call(CapabilityCall call) throws CapabilityFailure {
            switch (call.method) {
                case "shizuku.getConnection":
                    return status();
                case "shizuku.openApp":
                    requireGesture(call);
                    Intent launch = context.getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
                    if (launch == null) {
                        throw new CapabilityFailure("NOT_SUPPORTED", "设备上未安装可打开的 Shizuku 应用", false);
                    }
                    try {
                        context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    } catch (RuntimeException error) {
                        throw new CapabilityFailure("NOT_SUPPORTED", "无法打开 Shizuku，请从设备桌面手动打开", true);
                    }
                    return object("opened", true);
                case "shizuku.requestPermission":
                    requireGesture(call);
                    if (!bridge.isShizukuReady()) throw offline("请先在设备上启动 Shizuku");
                    if (!bridge.hasShizukuPermission()) {
                        bridge.requestShizukuPermission();
                        return object("requested", true);
                    }
                    return object("requested", false);
                case "shizuku.connect":
                    requireGesture(call);
                    if (!bridge.isShizukuReady()) throw offline("请先在设备上启动 Shizuku");
                    if (!bridge.hasShizukuPermission()) {
                        throw new CapabilityFailure("CONSENT_REQUIRED", "请先批准本应用的 Shizuku 授权", true);
                    }
                    bridge.ensureShizukuConnected();
                    return object("connecting", !bridge.isShizukuConnected());
                default:
                    throw CapabilityFailure.invalid("Unknown shizuku.control method");
            }
        }

        private JSONObject status() throws CapabilityFailure {
            boolean ready = bridge.isShizukuReady();
            boolean granted = bridge.hasShizukuPermission();
            boolean connected = bridge.isShizukuConnected();
            String state;
            String title;
            String detail;
            if (!ready) {
                state = "offline";
                title = "Shizuku 尚未运行";
                detail = "先启动设备上的 Shizuku，再返回这里检查。";
            } else if (!granted) {
                state = "unauthorized";
                title = "等待允许本应用";
                detail = "服务已运行，还需要允许本应用连接。";
            } else if (!connected) {
                state = "connecting";
                title = "正在建立系统连接";
                detail = "已获得授权，可以重新连接系统服务。";
            } else {
                state = "ready";
                title = "连接正常";
                detail = "需要系统能力的工具现在可以继续使用。";
            }
            try {
                return new JSONObject()
                        .put("state", state)
                        .put("title", title)
                        .put("detail", detail)
                        .put("ready", ready)
                        .put("granted", granted)
                        .put("connected", connected)
                        .put("mode", connected ? (bridge.shizukuUid() == 0 ? "Root" : bridge.shizukuUid() == 2000 ? "ADB" : "UID " + bridge.shizukuUid()) : "未连接")
                        .put("uid", bridge.shizukuUid());
            } catch (JSONException error) {
                throw internal(error);
            }
        }
    }

    private static final class AccessibilityProvider extends Provider {
        private static final String ENABLED_SERVICES = "enabled_accessibility_services";
        private static final String ACCESSIBILITY_ENABLED = "accessibility_enabled";
        private final Context context;
        private final ShizukuTransport bridge;

        AccessibilityProvider(Context context, ShizukuTransport bridge) {
            super("accessibility.manage", "accessibility.getConnection", "accessibility.listServices",
                    "accessibility.setEnabled");
            this.context = context.getApplicationContext();
            this.bridge = bridge;
        }

        @Override
        public synchronized JSONObject call(CapabilityCall call) throws CapabilityFailure {
            // Service toggles and scheduled restores share one read-modify-write setting.
            // Serialize them so independent row actions cannot overwrite each other.
            String state = bridge.shizukuState();
            if ("accessibility.getConnection".equals(call.method)) {
                if ("connecting".equals(state)) bridge.ensureShizukuConnected();
                return object("state", state);
            }
            if (!"ready".equals(state)) {
                if ("connecting".equals(state)) bridge.ensureShizukuConnected();
                String code = "unauthorized".equals(state) ? "CONSENT_REQUIRED" : "PROVIDER_OFFLINE";
                String message;
                if ("unauthorized".equals(state)) {
                    message = "请先在“Shizuku 授权”工具中允许本应用";
                } else if ("connecting".equals(state)) {
                    message = "Shizuku 正在连接系统服务，请稍候";
                } else {
                    message = "请先启动 Shizuku";
                }
                throw new CapabilityFailure(code, message, true);
            }
            try {
                LinkedHashMap<String, ServiceInfo> services = queryServices();
                if ("accessibility.listServices".equals(call.method)) {
                    Set<String> enabled = enabledServices();
                    JSONArray values = new JSONArray();
                    for (ServiceInfo service : services.values()) {
                        values.put(new JSONObject()
                                .put("appLabel", service.appLabel)
                                .put("serviceLabel", service.serviceLabel)
                                .put("component", service.component)
                                .put("enabled", enabled.contains(service.component)));
                    }
                    return new JSONObject()
                            .put("services", values)
                            .put("state", "ready")
                            .put("connected", true)
                            .put("title", services.size() + " 个服务")
                            .put("detail", enabled.size() + " 个已启用")
                            .put("value", enabled.size());
                }
                if (!call.userGesture && !call.scopes.optBoolean("allowBackground", false)) {
                    throw new CapabilityFailure("CONSENT_REQUIRED",
                            "A recent user gesture or declared background scope is required", true);
                }
                String component = requiredString(call.payload, "component", 512);
                if (!services.containsKey(component)) throw CapabilityFailure.invalid("Unknown accessibility service");
                Object rawEnabled = call.payload.opt("enabled");
                if (!(rawEnabled instanceof Boolean)) throw CapabilityFailure.invalid("enabled must be boolean");
                Set<String> enabled = enabledServices();
                if ((Boolean) rawEnabled) enabled.add(component); else enabled.remove(component);
                bridge.writeSecureSetting(ENABLED_SERVICES, String.join(":", enabled));
                bridge.writeSecureSetting(ACCESSIBILITY_ENABLED, enabled.isEmpty() ? "0" : "1");
                waitForSettingToSettle();
                boolean actual = enabledServices().contains(component);
                if (actual != (Boolean) rawEnabled) {
                    throw new CapabilityFailure(
                            "SYSTEM_REJECTED",
                            (Boolean) rawEnabled
                                    ? "系统阻止了此无障碍服务，请先在系统安全设置中允许该应用使用无障碍功能"
                                    : "系统没有停用此无障碍服务，请在系统设置中手动处理",
                            false
                    );
                }
                return new JSONObject().put("enabled", rawEnabled);
            } catch (CapabilityFailure failure) {
                throw failure;
            } catch (IOException | JSONException | RuntimeException error) {
                throw new CapabilityFailure("PROVIDER_OFFLINE",
                        "Accessibility provider failed: " + safeMessage(error), true);
            }
        }

        private Set<String> enabledServices() throws IOException {
            String raw = bridge.readSecureSetting(ENABLED_SERVICES);
            LinkedHashSet<String> values = new LinkedHashSet<>();
            if (!raw.isEmpty() && !"null".equalsIgnoreCase(raw)) {
                for (String item : raw.split(":")) if (!item.trim().isEmpty()) values.add(item.trim());
            }
            return values;
        }

        private static void waitForSettingToSettle() throws CapabilityFailure {
            try {
                Thread.sleep(800L);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new CapabilityFailure("CANCELLED", "操作已取消", true);
            }
        }

        private LinkedHashMap<String, ServiceInfo> queryServices() {
            PackageManager manager = context.getPackageManager();
            int flags = PackageManager.GET_META_DATA | PackageManager.MATCH_DISABLED_COMPONENTS
                    | PackageManager.MATCH_DIRECT_BOOT_AWARE | PackageManager.MATCH_DIRECT_BOOT_UNAWARE;
            List<ResolveInfo> resolved;
            Intent intent = new Intent(AccessibilityService.SERVICE_INTERFACE);
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                resolved = manager.queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(flags));
            } else {
                //noinspection deprecation
                resolved = manager.queryIntentServices(intent, flags);
            }
            LinkedHashMap<String, ServiceInfo> result = new LinkedHashMap<>();
            for (ResolveInfo resolve : resolved) {
                android.content.pm.ServiceInfo info = resolve.serviceInfo;
                if (info == null || !Manifest.permission.BIND_ACCESSIBILITY_SERVICE.equals(info.permission)) continue;
                String component = new ComponentName(info.packageName, info.name).flattenToString();
                result.put(component, new ServiceInfo(
                        String.valueOf(info.applicationInfo.loadLabel(manager)),
                        String.valueOf(resolve.loadLabel(manager)),
                        component
                ));
            }
            return result;
        }
    }

    private static final class SystemLogsProvider extends Provider {
        private final ShizukuTransport bridge;

        SystemLogsProvider(ShizukuTransport bridge) {
            super("system.logs", "system.logs.search");
            this.bridge = bridge;
        }

        @Override
        public JSONObject call(CapabilityCall call) throws CapabilityFailure {
            requireGesture(call);
            JSONArray requested = call.payload.optJSONArray("terms");
            JSONArray allowed = call.scopes.optJSONArray("terms");
            if (requested == null || requested.length() < 1 || requested.length() > 8) {
                throw CapabilityFailure.invalid("terms must contain 1-8 entries");
            }
            List<String> terms = new ArrayList<>();
            for (int index = 0; index < requested.length(); index++) {
                String term = requiredString(requested, index, 80);
                if (!allowed(allowed, term)) {
                    throw new CapabilityFailure("CAPABILITY_UNDECLARED", "Search term is outside declared scope", false);
                }
                terms.add(term.toLowerCase(java.util.Locale.ROOT));
            }
            String matchMode = call.payload.optString("matchMode", "all");
            if (!Set.of("all", "any").contains(matchMode)) {
                throw CapabilityFailure.invalid("matchMode must be all or any");
            }
            int scopeLimit = Math.max(1, Math.min(200, call.scopes.optInt("maxLines", 100)));
            int maxLines = Math.max(1, Math.min(scopeLimit, call.payload.optInt("maxLines", scopeLimit)));
            int lookbackMinutes = 0;
            if (call.payload.has("lookbackMinutes")) {
                Object value = call.payload.opt("lookbackMinutes");
                if (!(value instanceof Number) || ((Number) value).doubleValue() != ((Number) value).intValue()
                        || ((Number) value).intValue() < 0 || ((Number) value).intValue() > 10080) {
                    throw CapabilityFailure.invalid("lookbackMinutes must be an integer from 0 to 10080");
                }
                lookbackMinutes = ((Number) value).intValue();
            }
            if (!"ready".equals(bridge.shizukuState())) {
                bridge.ensureShizukuConnected();
                throw offline("Shizuku 系统服务尚未连接");
            }
            try {
                SystemLogSearch.Result result = SystemLogSearch.search(
                        bridge.readSystemLog(2 * 1024 * 1024, terms, lookbackMinutes),
                        terms,
                        "all".equals(matchMode),
                        maxLines
                );
                JSONArray lines = new JSONArray();
                for (String line : result.lines) lines.put(line);
                return new JSONObject().put("lines", lines).put("truncated", result.truncated)
                        .put("lookbackMinutes", lookbackMinutes);
            } catch (IOException | JSONException | RuntimeException error) {
                throw new CapabilityFailure("PROVIDER_OFFLINE", "无法读取游戏日志：" + safeMessage(error), true);
            }
        }

        private static boolean allowed(JSONArray values, String target) {
            if (values == null) return false;
            for (int index = 0; index < values.length(); index++) {
                if (target.equalsIgnoreCase(values.optString(index))) return true;
            }
            return false;
        }

    }

    private static final class ServiceInfo {
        final String appLabel;
        final String serviceLabel;
        final String component;

        ServiceInfo(String appLabel, String serviceLabel, String component) {
            this.appLabel = appLabel;
            this.serviceLabel = serviceLabel;
            this.component = component;
        }
    }

    private static void requireGesture(CapabilityCall call) throws CapabilityFailure {
        if (!call.userGesture) throw new CapabilityFailure("CONSENT_REQUIRED", "A recent user gesture is required", true);
    }

    private static String requiredString(JSONObject value, String name, int maxLength) throws CapabilityFailure {
        Object raw = value.opt(name);
        if (!(raw instanceof String)) throw CapabilityFailure.invalid("Missing string field " + name);
        String text = ((String) raw).trim();
        if (text.isEmpty() || text.length() > maxLength) throw CapabilityFailure.invalid("Invalid field " + name);
        return text;
    }

    private static String requiredString(JSONArray value, int index, int maxLength) throws CapabilityFailure {
        Object raw = value.opt(index);
        if (!(raw instanceof String)) throw CapabilityFailure.invalid("Search term must be a string");
        String text = ((String) raw).trim();
        if (text.isEmpty() || text.length() > maxLength) throw CapabilityFailure.invalid("Invalid search term");
        return text;
    }

    private static JSONObject object(String name, Object value) throws CapabilityFailure {
        try { return new JSONObject().put(name, value); } catch (JSONException error) { throw internal(error); }
    }

    private static CapabilityFailure offline(String message) {
        return new CapabilityFailure("PROVIDER_OFFLINE", message, true);
    }

    private static CapabilityFailure internal(Throwable error) {
        return new CapabilityFailure("INTERNAL", safeMessage(error), true);
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? error.getClass().getSimpleName() : message;
    }
}
