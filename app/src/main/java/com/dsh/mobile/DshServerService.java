package com.dsh.mobile;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * DSH 内置服务（v2.0）：
 * 首次运行把 assets/payload.zip（Termux Node 运行时 + 裁剪后的 DSH 应用树）
 * 解压到私有目录，然后以前台服务身份 spawn node 启动 127.0.0.1:3080 的
 * DSH Web 服务，实现完全脱离 PC 的独立运行。
 */
public class DshServerService extends Service {

    public static final String ACTION_START = "com.dsh.mobile.action.START_SERVER";
    public static final String ACTION_STOP = "com.dsh.mobile.action.STOP_SERVER";

    /** SharedPreferences 中的状态值 */
    public static final String STATE_EXTRACTING = "extracting";
    public static final String STATE_READY = "ready";
    public static final String STATE_STARTING = "starting";
    public static final String STATE_RUNNING = "running";
    public static final String STATE_EXITED = "exited";
    public static final String STATE_FAILED = "failed";
    public static final String KEY_PAYLOAD_STATE = "payload_state";
    public static final String KEY_SERVER_STATE = "server_state";

    private static final String TAG = "DshServer";
    private static final int NOTIF_ID = 1;
    private static final String CHANNEL_ID = "dsh_server";
    private static final String PAYLOAD_ZIP = "payload.zip";
    private static final String PAYLOAD_MARKER = "payload.v1.ok";
    private static final int MAX_RESTARTS = 5;
    private static final long RESTART_DELAY_MS = 3000;

    private static final Object EXTRACT_LOCK = new Object();

    private Process nodeProcess;
    private volatile boolean stopping = false;
    private Thread worker;

    // ---------- 路径工具 ----------

    public static File payloadDir(Context ctx) { return new File(ctx.getFilesDir(), "payload"); }
    public static File dshAppDir(Context ctx) { return new File(payloadDir(ctx), "dsh-app"); }
    public static File nodeBin(Context ctx) {
        return new File(payloadDir(ctx), "termux" + File.separator + "usr" + File.separator + "bin" + File.separator + "node");
    }
    public static File dshHomeDir(Context ctx) { return new File(ctx.getFilesDir(), "dsh-home"); }

    public static boolean isPayloadReady(Context ctx) {
        return new File(payloadDir(ctx), PAYLOAD_MARKER).exists();
    }

    public static String serverState(Context ctx) {
        return ctx.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
                .getString(KEY_SERVER_STATE, "");
    }

    public static String payloadState(Context ctx) {
        return ctx.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
                .getString(KEY_PAYLOAD_STATE, "");
    }

    private static void putState(Context ctx, String key, String value) {
        ctx.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
                .edit().putString(key, value).apply();
    }

    // ---------- Service 生命周期 ----------

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : ACTION_START;
        if (ACTION_STOP.equals(action)) {
            stopNode();
            return START_NOT_STICKY;
        }
        startForeground(NOTIF_ID, buildNotification());
        if (nodeProcess != null && isAlive()) {
            putState(this, KEY_SERVER_STATE, STATE_RUNNING);
            return START_STICKY;
        }
        if (worker == null || !worker.isAlive()) {
            stopping = false;
            worker = new Thread(new Runnable() {
                @Override public void run() { bootLoop(); }
            }, "dsh-server");
            worker.start();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopping = true;
        stopNode();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ---------- 启动循环 ----------

    private void bootLoop() {
        Log.i(TAG, "bootLoop start");
        File nodeBin = nodeBin(this);
        File appDir = dshAppDir(this);
        File home = dshHomeDir(this);
        File tmp = new File(getFilesDir(), "tmp");
        if (!tmp.exists()) tmp.mkdirs();
        if (!home.exists()) home.mkdirs();

        if (!isPayloadReady(this)) {
            if (!extractPayload()) {
                putState(this, KEY_SERVER_STATE, STATE_FAILED);
                return;
            }
        }

        putState(this, KEY_SERVER_STATE, STATE_STARTING);
        int restarts = 0;
        while (!stopping) {
            try {
                ProcessBuilder pb = new ProcessBuilder(
                        nodeBin.getAbsolutePath(),
                        "--expose-internals",
                        "lib/bin.js", "web",
                        "--host", "127.0.0.1",
                        "--port", "3080");
                pb.directory(appDir);
                pb.environment().put("LD_LIBRARY_PATH",
                        new File(payloadDir(this), "termux/usr/lib").getAbsolutePath());
                pb.environment().put("HOME", getFilesDir().getAbsolutePath());
                pb.environment().put("DSH_HOME", home.getAbsolutePath());
                pb.environment().put("TMPDIR", tmp.getAbsolutePath());
                pb.environment().put("TMP", tmp.getAbsolutePath());
                pb.environment().put("SSL_CERT_FILE",
                        new File(payloadDir(this), "termux/usr/etc/tls/cert.pem").getAbsolutePath());
                pb.environment().put("SSL_CERT_DIR",
                        new File(payloadDir(this), "termux/usr/etc/tls").getAbsolutePath());
                pb.environment().put("PATH",
                        new File(payloadDir(this), "termux/usr/bin").getAbsolutePath()
                                + ":/system/bin:/system/xbin");
                pb.environment().put("LANG", "C.UTF-8");
                pb.environment().put("LC_ALL", "C.UTF-8");
                pb.environment().put("NODE_NO_WARNINGS", "1");
                pb.redirectErrorStream(true);
                pb.redirectOutput(new File(getFilesDir(), "dsh-server.log"));
                nodeProcess = pb.start();
                Log.i(TAG, "node spawned, waiting for exit");
                putState(this, KEY_SERVER_STATE, STATE_STARTING);
                int code = nodeProcess.waitFor();
                nodeProcess = null;
                if (stopping) return;
                restarts++;
                Log.w(TAG, "dsh server exited code=" + code + " restart=" + restarts);
                putState(this, KEY_SERVER_STATE, STATE_EXITED + ":" + code);
                if (restarts >= MAX_RESTARTS) {
                    Log.e(TAG, "too many restarts, giving up");
                    return;
                }
            } catch (IOException e) {
                Log.e(TAG, "spawn failed", e);
                putState(this, KEY_SERVER_STATE, STATE_FAILED + ":" + e.getMessage());
                restarts++;
                if (restarts >= MAX_RESTARTS) return;
            } catch (InterruptedException e) {
                return;
            }
            sleepQuietly(RESTART_DELAY_MS);
        }
    }

    private void stopNode() {
        stopping = true;
        if (nodeProcess != null) {
            try {
                nodeProcess.destroy();
                // 给 1 秒优雅退出，随后强杀
                new Thread(new Runnable() {
                    @Override public void run() {
                        try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                        if (nodeProcess != null && isAlive()) nodeProcess.destroyForcibly();
                    }
                }).start();
            } catch (Exception e) { Log.w(TAG, "stop node failed", e); }
        }
        nodeProcess = null;
        putState(this, KEY_SERVER_STATE, "stopped");
        stopSelf();
    }

    private boolean isAlive() {
        try {
            nodeProcess.exitValue();
            return false;
        } catch (IllegalThreadStateException e) {
            return true; // 还没退出
        }
    }

    // ---------- payload 解压 ----------

    /**
     * 把 assets/payload.zip 解压到 filesDir/payload。幂等；多线程/多进程竞争由
     * EXTRACT_LOCK + 完成标记保护。返回是否成功。
     */
    private boolean extractPayload() {
        synchronized (EXTRACT_LOCK) {
            if (isPayloadReady(this)) return true;
            Log.i(TAG, "extract: begin");
            File root = payloadDir(this);
            if (!root.exists() && !root.mkdirs()) {
                putState(this, KEY_PAYLOAD_STATE, STATE_FAILED);
                return false;
            }
            putState(this, KEY_PAYLOAD_STATE, STATE_EXTRACTING + ":0");
            File tmpZip = new File(getFilesDir(), "payload.zip.tmp");
            try {
                // 1. 先把 zip 从 assets 拷出（assets 流不支持随机访问）
                long t0 = System.currentTimeMillis();
                try (InputStream in = getAssets().open(PAYLOAD_ZIP);
                     FileOutputStream out = new FileOutputStream(tmpZip)) {
                    byte[] buf = new byte[256 * 1024];
                    int n;
                    long copied = 0;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        copied += n;
                        if (copied % (20L * 1024 * 1024) < 256 * 1024) {
                            Log.i(TAG, "extract: asset copy " + (copied / 1048576) + "MB");
                        }
                    }
                    out.flush();
                    Log.i(TAG, "extract: asset copy done " + (copied / 1048576)
                            + "MB in " + (System.currentTimeMillis() - t0) + "ms");
                }
                // 2. 解压（ZipFile 随机访问，15k 小文件也快）
                long total = tmpZip.length();
                long done = 0;
                int count = 0;
                java.util.zip.ZipFile zf = new java.util.zip.ZipFile(tmpZip);
                try {
                    java.util.Enumeration<? extends ZipEntry> entries = zf.entries();
                    byte[] buf = new byte[256 * 1024];
                    long t1 = System.currentTimeMillis();
                    while (entries.hasMoreElements()) {
                        ZipEntry entry = entries.nextElement();
                        if (entry.isDirectory()) continue;
                        File outFile = new File(root, entry.getName());
                        File parent = outFile.getParentFile();
                        if (parent != null && !parent.exists() && !parent.mkdirs()) {
                            throw new IOException("mkdir failed: " + parent);
                        }
                        try (InputStream zin = zf.getInputStream(entry);
                             FileOutputStream fos = new FileOutputStream(outFile)) {
                            int n;
                            while ((n = zin.read(buf)) > 0) fos.write(buf, 0, n);
                            fos.flush();
                        }
                        done += entry.getSize();
                        count++;
                        if (count % 1000 == 0) {
                            Log.i(TAG, "extract: " + count + " files, " + (done / 1048576) + "MB");
                        }
                        int pct = (int) (done * 100 / Math.max(1, total));
                        if (pct % 5 == 0) {
                            putState(this, KEY_PAYLOAD_STATE, STATE_EXTRACTING + ":" + pct);
                        }
                    }
                    Log.i(TAG, "extract: unzip done " + count + " files, " + (done / 1048576)
                            + "MB in " + (System.currentTimeMillis() - t1) + "ms");
                } finally {
                    zf.close();
                }
                // 3. 可执行位：node + bash + rg（zip 解压不保留权限）
                for (String bin : new String[]{"node", "bash", "rg"}) {
                    File b = new File(new File(payloadDir(this), "termux/usr/bin"), bin);
                    if (b.exists() && !b.setExecutable(true, false)) {
                        Log.w(TAG, "setExecutable returned false: " + bin);
                    }
                }
                // 4. 完成标记
                File marker = new File(root, PAYLOAD_MARKER);
                try (FileOutputStream fos = new FileOutputStream(marker)) {
                    fos.write("ok".getBytes("UTF-8"));
                }
                putState(this, KEY_PAYLOAD_STATE, STATE_READY);
                Log.i(TAG, "extract: complete");
                return true;
            } catch (Exception e) {
                Log.e(TAG, "extract failed", e);
                putState(this, KEY_PAYLOAD_STATE, STATE_FAILED + ":" + e.getMessage());
                return false;
            } finally {
                tmpZip.delete();
            }
        }
    }

    // ---------- 工具 ----------

    /** 轮询等待端口可用。 */
    public static boolean waitForPort(int port, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (isPortOpen(port, 400)) return true;
            try { Thread.sleep(500); } catch (InterruptedException e) { return false; }
        }
        return isPortOpen(port, 600);
    }

    public static boolean isPortOpen(int port, int timeoutMs) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void sleepQuietly(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL_ID, getString(R.string.notif_channel),
                        NotificationManager.IMPORTANCE_LOW);
                ch.setDescription(getString(R.string.notif_channel_desc));
                nm.createNotificationChannel(ch);
            }
        }
    }

    private Notification buildNotification() {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return b.setSmallIcon(R.drawable.ic_stat_dsh)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(getString(R.string.notif_text))
                .setOngoing(true)
                .build();
    }
}
