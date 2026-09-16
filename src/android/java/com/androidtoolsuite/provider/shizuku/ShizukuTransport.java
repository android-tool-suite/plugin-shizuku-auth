package com.androidtoolsuite.provider.shizuku;

import android.content.pm.PackageManager;
import android.os.ParcelFileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import moe.shizuku.server.IShizukuService;
import moe.shizuku.server.IRemoteProcess;
import rikka.shizuku.Shizuku;

/** Plugin-owned Shizuku 13 transport. No Host or plugin-SDK privileged operations. */
final class ShizukuTransport {
    boolean isShizukuReady() { return Shizuku.pingBinder() && !Shizuku.isPreV11(); }
    boolean hasShizukuPermission() {
        return isShizukuReady() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
    }
    boolean isShizukuConnected() { return hasShizukuPermission(); }
    int shizukuUid() { return isShizukuReady() ? Shizuku.getUid() : -1; }
    String shizukuState() {
        return !isShizukuReady() ? "disconnected" : !hasShizukuPermission() ? "unauthorized" : "ready";
    }
    void requestShizukuPermission() { Shizuku.requestPermission(6104); }
    void ensureShizukuConnected() { /* Binder is supplied by Shizuku's standard client runtime. */ }

    String readSecureSetting(String name) throws IOException {
        validateSetting(name);
        return run(1024 * 1024, "/system/bin/settings", "get", "secure", name).trim();
    }
    void writeSecureSetting(String name, String value) throws IOException {
        validateSetting(name);
        run(1024 * 1024, "/system/bin/settings", "put", "secure", name, value == null ? "" : value);
    }
    String readSystemLog(int maxBytes, java.util.List<String> terms, int lookbackMinutes) throws IOException {
        // Filter before bounding output so unrelated system traffic cannot evict matching lines.
        return run(maxBytes, SystemLogSearch.logcatCommand(terms, lookbackMinutes, System.currentTimeMillis()));
    }
    private static void validateSetting(String name) throws IOException {
        if (name == null || !name.matches("[a-z0-9_]{1,80}")) throw new IOException("设置项名称无效");
    }

    private String run(int maxBytes, String... command) throws IOException {
        try { return runRemote(maxBytes, command); }
        catch (android.os.RemoteException | IllegalStateException | SecurityException error) {
            throw new IOException("Shizuku 连接失效或权限已撤销", error);
        }
    }
    private String runRemote(int maxBytes, String... command) throws IOException, android.os.RemoteException {
        if (!hasShizukuPermission()) throw new IOException("Shizuku 未连接或未授权");
        // Upstream plans to remove newProcess in API 14. Keep that compatibility decision here,
        // so a future transport migration only requires a Provider update.
        if (Shizuku.getVersion() != 13) throw new IOException("此插件的 Shell 传输需要 Shizuku API 13，请更新兼容的 Shizuku 插件");
        if (maxBytes < 1 || maxBytes > 2 * 1024 * 1024) throw new IOException("输出上限无效");
        IRemoteProcess process = IShizukuService.Stub.asInterface(Shizuku.getBinder()).newProcess(command, null, null);
        ExecutorService readers = Executors.newFixedThreadPool(2);
        try (InputStream stdout = new ParcelFileDescriptor.AutoCloseInputStream(process.getInputStream());
             InputStream stderr = new ParcelFileDescriptor.AutoCloseInputStream(process.getErrorStream());
             java.io.OutputStream stdin = new ParcelFileDescriptor.AutoCloseOutputStream(process.getOutputStream())) {
            stdin.close();
            Future<byte[]> out = readers.submit(() -> tail(stdout, maxBytes));
            Future<byte[]> err = readers.submit(() -> tail(stderr, 4096));
            if (!process.waitForTimeout(10, "SECONDS")) throw new IOException("Shizuku 命令超时");
            byte[] bytes = out.get(2, TimeUnit.SECONDS);
            err.get(2, TimeUnit.SECONDS); // Drain stderr, but never expose potentially sensitive output.
            if (process.exitValue() != 0) throw new IOException("Shizuku 命令失败，退出码 " + process.exitValue());
            int start = 0;
            while (start < bytes.length && (bytes[start] & 0xc0) == 0x80) start++;
            return new String(bytes, start, bytes.length - start, StandardCharsets.UTF_8);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Shizuku 命令被中断", error);
        } catch (ExecutionException | TimeoutException error) {
            throw new IOException("Shizuku 输出读取失败", error);
        } finally {
            try { process.destroy(); } finally { readers.shutdownNow(); }
        }
    }
    static byte[] tail(InputStream input, int limit) throws IOException {
        byte[] ring = new byte[limit];
        byte[] buffer = new byte[8192];
        long count = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            for (int i = 0; i < read; i++) ring[(int) (count++ % limit)] = buffer[i];
        }
        int length = (int) Math.min(count, limit);
        byte[] result = new byte[length];
        int start = count < limit ? 0 : (int) (count % limit);
        for (int i = 0; i < length; i++) result[i] = ring[(start + i) % limit];
        return result;
    }
}
