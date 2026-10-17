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
import android.os.StatFs;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * DSH 内置服务：
 * 首次运行把 assets/payload.zip（Termux Node 运行时 + 裁剪后的 DSH 应用树）
 * 解压到私有目录，然后以前台服务身份 spawn node 启动 127.0.0.1:3080 的
 * DSH Web 服务，实现完全脱离 PC 的独立运行。
 *
 * v2.9 起解压过程带真实进度（拷贝/解压/校验三段，按 zip 中央目录的真实
 * 解压尺寸算百分比）、解压前做存储空间预检、完成后做完整性校验，
 * 失败时把原因写进 SharedPreferences 并清掉半成品，供引导页显示与重试。
 */
public class DshServerService extends Service {

    public static final String ACTION_START = "com.dsh.mobile.action.START_SERVER";
    public static final String ACTION_STOP = "com.dsh.mobile.action.STOP_SERVER";
    /** 同一实例内原子重启：杀掉旧 node 再拉起新 node，避免 STOP+START 两条命令竞态
     *  （竞态会让系统误判前台服务 5 秒未 startForeground，直接杀进程） */
    public static final String ACTION_RESTART = "com.dsh.mobile.action.RESTART_SERVER";
    /** 清掉运行时目录并重新解压（v2.9；引导页/设置页的「重新解压」按钮用） */
    public static final String ACTION_RESTART_EXTRACT = "com.dsh.mobile.action.RESTART_EXTRACT";

    /** SharedPreferences 中的状态值 */
    public static final String STATE_EXTRACTING = "extracting";
    public static final String STATE_READY = "ready";
    public static final String STATE_STARTING = "starting";
    public static final String STATE_RUNNING = "running";
    public static final String STATE_EXITED = "exited";
    public static final String STATE_FAILED = "failed";
    public static final String KEY_PAYLOAD_STATE = "payload_state";
    public static final String KEY_SERVER_STATE = "server_state";
    /** 解压阶段的一句人话（拷贝/解压 N/M 文件/校验） */
    public static final String KEY_PAYLOAD_DETAIL = "payload_detail";
    /** 最近一次进度时间戳（毫秒）。UI 用它判断「进度卡住」 */
    public static final String KEY_PAYLOAD_TS = "payload_ts";
    /** 解压失败原因（失败时写，成功时清空） */
    public static final String KEY_EXTRACT_ERROR = "extract_error";

    private static final String TAG = "DshServer";
    private static final int NOTIF_ID = 1;
    private static final String CHANNEL_ID = "dsh_server";
    private static final String PAYLOAD_ZIP = "payload.zip";
    private static final int MAX_RESTARTS = 5;
    private static final long RESTART_DELAY_MS = 3000;
    /** 解压前额外预留（数据库/tmp/日志） */
    private static final long EXTRACT_SPARE_BYTES = 64L * 1024 * 1024;
    private static final int COPY_STAGE_PCT = 8;    // 拷贝占用 0-8%
    private static final int UNZIP_STAGE_PCT = 87;  // 解压占用 8-95%

    /** payload 完成标记按 versionCode 区分：升级安装（DSH 内核换代）后旧标记失配，
     *  自动触发清理旧树 + 重新解压，避免新 APK 沿用旧内核。 */
    private static String payloadMarkerName(Context ctx) {
        try {
            int vc = ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0).versionCode;
            return "payload.app" + vc + ".ok";
        } catch (Exception e) {
            return "payload.app0.ok";
        }
    }

    private static final Object EXTRACT_LOCK = new Object();
    /** 解压中标记（同进程静态：给 UI 判断能否重启、给服务自己防重入） */
    private static volatile boolean extractingNow = false;

    private Process nodeProcess;
    private volatile boolean stopping = false;
    /** bootLoop 代际计数：stopNode/重启时递增，旧循环检测到代际变化立即退出 */
    private volatile int bootGen = 0;
    private Thread worker;
    /** 上报节流：只在百分比变化时才写 prefs/发通知 */
    private int lastReportedPct = -1;

    // ---------- 路径工具 ----------

    public static File payloadDir(Context ctx) { return new File(ctx.getFilesDir(), "payload"); }
    public static File dshAppDir(Context ctx) { return new File(payloadDir(ctx), "dsh-app"); }
    public static File nodeBin(Context ctx) {
        return new File(payloadDir(ctx), "termux" + File.separator + "usr" + File.separator + "bin" + File.separator + "node");
    }
    public static File dshHomeDir(Context ctx) { return new File(ctx.getFilesDir(), "dsh-home"); }

    public static boolean isPayloadReady(Context ctx) {
        return new File(payloadDir(ctx), payloadMarkerName(ctx)).exists();
    }

    /** 是否正在解压（运行期标志，不读 prefs：进程重启后残留的 extracting 状态不算） */
    public static boolean isExtractingNow() { return extractingNow; }

    public static String serverState(Context ctx) {
        return ctx.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
                .getString(KEY_SERVER_STATE, "");
    }

    public static String payloadState(Context ctx) {
        return ctx.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
                .getString(KEY_PAYLOAD_STATE, "");
    }

    public static String payloadDetail(Context ctx) {
        return ctx.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
                .getString(KEY_PAYLOAD_DETAIL, "");
    }

    public static long payloadUpdatedAt(Context ctx) {
        String v = ctx.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
                .getString(KEY_PAYLOAD_TS, "");
        try { return Long.parseLong(v); } catch (Exception e) { return 0; }
    }

    public static String extractError(Context ctx) {
        return ctx.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
                .getString(KEY_EXTRACT_ERROR, "");
    }

    /** 解析 "extracting:37" 里的百分比，拿不到返回 -1 */
    public static int payloadPercent(Context ctx) {
        String s = payloadState(ctx);
        if (!s.startsWith(STATE_EXTRACTING)) return -1;
        int i = s.indexOf(':');
        if (i < 0) return -1;
        try { return Integer.parseInt(s.substring(i + 1)); } catch (Exception e) { return -1; }
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
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(NOTIF_ID, buildNotification());
        if (ACTION_RESTART_EXTRACT.equals(action)) {
            if (extractingNow) {
                // 正在解压时再点重解压没有意义，直接忽略（避免删掉正在写的半成品）
                Log.w(TAG, "re-extract ignored: extraction in progress");
                return START_STICKY;
            }
            Log.i(TAG, "re-extract requested: wiping payload tree");
            stopNode();
            deleteRecursively(payloadDir(this));
            putState(this, KEY_PAYLOAD_STATE, "");
            putState(this, KEY_PAYLOAD_DETAIL, "");
            putState(this, KEY_EXTRACT_ERROR, "");
            putState(this, KEY_SERVER_STATE, STATE_STARTING);
            stopping = false;
            final int gen = ++bootGen;
            worker = new Thread(new Runnable() {
                @Override public void run() { bootLoop(gen); }
            }, "dsh-server");
            worker.start();
            return START_STICKY;
        }
        if (ACTION_RESTART.equals(action)) {
            // 解压期间不接受重启：解压线程还在写运行时树，重启只会让 UI 状态来回跳
            if (extractingNow) {
                Log.w(TAG, "restart ignored: extraction in progress");
                return START_STICKY;
            }
            // 原子重启：同实例内杀旧 node、起新 bootLoop，规避 STOP+START 竞态
            stopNode();
            stopping = false;
            final int gen = ++bootGen;
            worker = new Thread(new Runnable() {
                @Override public void run() { bootLoop(gen); }
            }, "dsh-server");
            worker.start();
            return START_STICKY;
        }
        if (nodeProcess != null && isAlive()) {
            putState(this, KEY_SERVER_STATE, STATE_RUNNING);
            return START_STICKY;
        }
        if (worker == null || !worker.isAlive()) {
            stopping = false;
            final int gen = ++bootGen;
            worker = new Thread(new Runnable() {
                @Override public void run() { bootLoop(gen); }
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

    private void bootLoop(int gen) {
        Log.i(TAG, "bootLoop start gen=" + gen);
        File nodeBin = nodeBin(this);
        File appDir = dshAppDir(this);
        File home = dshHomeDir(this);
        File tmp = new File(getFilesDir(), "tmp");
        if (!tmp.exists()) tmp.mkdirs();
        if (!home.exists()) home.mkdirs();

        if (!isPayloadReady(this)) {
            if (!extractPayload()) {
                putState(this, KEY_SERVER_STATE, STATE_FAILED);
                updateNotificationRunningFailed();
                return;
            }
        }

        putState(this, KEY_SERVER_STATE, STATE_STARTING);
        notifyServerState(getString(R.string.notif_text_starting));
        int restarts = 0;
        while (!stopping && gen == bootGen) {
            try {
                ProcessBuilder pb = new ProcessBuilder(
                        nodeBin.getAbsolutePath(),
                        "--expose-internals",
                        "lib/bin.js", "web",
                        "--host", "127.0.0.1",
                        "--port", "3080",
                        // Android 上没有可打开的浏览器，跳过 browser handoff
                        // （带 token 的 URL 仍打印到 stdout，由 MainActivity 取用）
                        "--no-open");
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
                if (stopping || gen != bootGen) return;
                restarts++;
                Log.w(TAG, "dsh server exited code=" + code + " restart=" + restarts);
                putState(this, KEY_SERVER_STATE, STATE_EXITED + ":" + code);
                if (restarts >= MAX_RESTARTS) {
                    Log.e(TAG, "too many restarts, giving up");
                    updateNotificationRunningFailed();
                    return;
                }
            } catch (IOException e) {
                Log.e(TAG, "spawn failed", e);
                putState(this, KEY_SERVER_STATE, STATE_FAILED + ":" + e.getMessage());
                restarts++;
                if (restarts >= MAX_RESTARTS) {
                    updateNotificationRunningFailed();
                    return;
                }
            } catch (InterruptedException e) {
                return;
            }
            sleepQuietly(RESTART_DELAY_MS);
        }
    }

    private void stopNode() {
        stopping = true;
        bootGen++;
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
     * EXTRACT_LOCK + 完成标记保护。返回是否成功。失败原因写 KEY_EXTRACT_ERROR。
     */
    private boolean extractPayload() {
        synchronized (EXTRACT_LOCK) {
            if (isPayloadReady(this)) return true;
            extractingNow = true;
            lastReportedPct = -1;
            Log.i(TAG, "extract: begin");
            File root = payloadDir(this);
            // 升级/上次解压失败：标记缺失即视为不可信，整树清掉重解
            if (root.exists() && !deleteRecursively(root)) {
                Log.w(TAG, "extract: stale payload cleanup incomplete");
            }
            if (!root.exists() && !root.mkdirs()) {
                failExtract(getString(R.string.extract_err_nowrite));
                extractingNow = false;
                return false;
            }
            putState(this, KEY_EXTRACT_ERROR, "");
            File tmpZip = new File(getFilesDir(), "payload.zip.tmp");
            try {
                // ---- 1. assets -> 临时 zip（assets 流不支持随机访问） ----
                long t0 = System.currentTimeMillis();
                long copied = 0;
                long zipLen = -1;
                try {
                    android.content.res.AssetFileDescriptor afd = getAssets().openFd(PAYLOAD_ZIP);
                    zipLen = afd.getLength();
                    afd.close();
                } catch (Exception ignored) {
                    // 资产被压缩存储时拿不到 fd，退化为按已拷贝量显示
                }
                reportExtractProgress(0, getString(R.string.extract_phase_copy));
                try (InputStream in = getAssets().open(PAYLOAD_ZIP);
                     FileOutputStream out = new FileOutputStream(tmpZip)) {
                    byte[] buf = new byte[256 * 1024];
                    int n;
                    int lastPct = -1;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        copied += n;
                        if (zipLen > 0) {
                            int pct = (int) (copied * COPY_STAGE_PCT / zipLen);
                            if (pct != lastPct) {
                                lastPct = pct;
                                reportExtractProgress(pct,
                                        getString(R.string.extract_phase_copy_mb, mb(copied)));
                            }
                        }
                    }
                    out.flush();
                }
                Log.i(TAG, "extract: asset copy done " + mb(copied)
                        + " in " + (System.currentTimeMillis() - t0) + "ms");

                // ---- 2. 扫中央目录：真实解压尺寸与文件数（进度分母） ----
                long expected = 0;
                int totalFiles = 0;
                try (ZipFile zf = new ZipFile(tmpZip)) {
                    Enumeration<? extends ZipEntry> en = zf.entries();
                    while (en.hasMoreElements()) {
                        ZipEntry e = en.nextElement();
                        if (e.isDirectory()) continue;
                        long s = e.getSize();
                        if (s <= 0) s = Math.max(0, e.getCompressedSize()) * 4; // 兜底估算
                        expected += s;
                        totalFiles++;
                    }
                }
                if (totalFiles <= 0 || expected <= 0) {
                    throw new IOException(getString(R.string.extract_err_emptyzip));
                }

                // ---- 3. 空间预检：解压后 + 临时 zip + 余量 ----
                long free = freeBytes(getFilesDir());
                long need = expected + tmpZip.length() + EXTRACT_SPARE_BYTES;
                if (free > 0 && free < need) {
                    throw new IOException(getString(R.string.extract_err_space, mb(need), mb(free)));
                }

                // ---- 4. 解压（带真实百分比） ----
                long t1 = System.currentTimeMillis();
                long done = 0;
                int count = 0;
                int lastUnzipPct = -1;
                try (ZipFile zf = new ZipFile(tmpZip)) {
                    Enumeration<? extends ZipEntry> en = zf.entries();
                    byte[] buf = new byte[256 * 1024];
                    while (en.hasMoreElements()) {
                        ZipEntry entry = en.nextElement();
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
                        long written = entry.getSize() > 0 ? entry.getSize() : outFile.length();
                        done += written;
                        count++;
                        int pct = (int) (COPY_STAGE_PCT
                                + done * UNZIP_STAGE_PCT / Math.max(1, expected));
                        if (pct > 94) pct = 94; // 95-100 留给校验与完成标记
                        // 百分比不变就不写 prefs（11k 个小文件，写 1 万次没必要）
                        if (pct != lastUnzipPct) {
                            lastUnzipPct = pct;
                            reportExtractProgress(pct, getString(R.string.extract_phase_unzip,
                                    count, totalFiles, mb(done), mb(expected)));
                        }
                    }
                }
                Log.i(TAG, "extract: unzip done " + count + " files, " + mb(done)
                        + " in " + (System.currentTimeMillis() - t1) + "ms");

                // ---- 5. 可执行位（zip 不保留权限）----
                for (String bin : new String[]{"node", "bash", "rg"}) {
                    File b = new File(new File(payloadDir(this), "termux/usr/bin"), bin);
                    if (b.exists() && !b.setExecutable(true, false)) {
                        Log.w(TAG, "setExecutable returned false: " + bin);
                    }
                }

                // ---- 6. 完整性校验（字节数 + 关键文件）----
                reportExtractProgress(96, getString(R.string.extract_phase_verify));
                if (done < expected - Math.max(1024, expected / 100)) {
                    throw new IOException(getString(R.string.extract_err_short,
                            mb(done), mb(expected)));
                }
                String missing = verifyTree(payloadDir(this));
                if (missing != null) {
                    throw new IOException(getString(R.string.extract_err_incomplete, missing));
                }

                // ---- 7. 完成标记（按 versionCode 命名，最后一步写）----
                File marker = new File(root, payloadMarkerName(this));
                try (FileOutputStream fos = new FileOutputStream(marker)) {
                    fos.write("ok".getBytes("UTF-8"));
                }
                reportExtractProgress(100, getString(R.string.extract_phase_done));
                putState(this, KEY_PAYLOAD_DETAIL, "");
                putState(this, KEY_PAYLOAD_STATE, STATE_READY);
                putState(this, KEY_EXTRACT_ERROR, "");
                notifyServerState(getString(R.string.notif_text_starting));
                Log.i(TAG, "extract: complete");
                return true;
            } catch (Throwable t) {
                String msg = describeExtractFailure(t);
                Log.e(TAG, "extract failed: " + msg, t);
                putState(this, KEY_EXTRACT_ERROR, msg);
                putState(this, KEY_PAYLOAD_DETAIL, "");
                putState(this, KEY_PAYLOAD_STATE, STATE_FAILED);
                // 清掉半成品，保证下次重试从干净状态开始（也避免半棵树被误判可用）
                deleteRecursively(payloadDir(this));
                return false;
            } finally {
                extractingNow = false;
                tmpZip.delete();
            }
        }
    }

    private void failExtract(String msg) {
        Log.e(TAG, "extract failed: " + msg);
        putState(this, KEY_EXTRACT_ERROR, msg);
        putState(this, KEY_PAYLOAD_DETAIL, "");
        putState(this, KEY_PAYLOAD_STATE, STATE_FAILED);
    }

    /** 写进度（节流：百分比变化才写 prefs / 刷新通知） */
    private void reportExtractProgress(int pct, String detail) {
        if (pct < 0) pct = 0;
        if (pct > 100) pct = 100;
        putState(this, KEY_PAYLOAD_TS, String.valueOf(System.currentTimeMillis()));
        putState(this, KEY_PAYLOAD_DETAIL, detail);
        putState(this, KEY_PAYLOAD_STATE, STATE_EXTRACTING + ":" + pct);
        if (pct != lastReportedPct) {
            lastReportedPct = pct;
            try { notifyProgress(pct, detail); } catch (Exception ignored) {}
        }
    }

    /** 关键文件校验：返回缺失项描述，全部齐全返回 null */
    private String verifyTree(File root) {
        String[] must = {
                "termux/usr/bin/node",
                "termux/usr/bin/bash",
                "termux/usr/bin/rg",
                "termux/usr/lib",
                "dsh-app/lib/bin.js"
        };
        for (String p : must) {
            if (!new File(root, p).exists()) return p;
        }
        File node = new File(root, "termux/usr/bin/node");
        if (!node.canExecute() && !node.setExecutable(true, false)) {
            return "termux/usr/bin/node（不可执行）";
        }
        if (!node.canExecute()) return "termux/usr/bin/node（不可执行）";
        return null;
    }

    private String describeExtractFailure(Throwable t) {
        String m = t.getMessage();
        if (t instanceof IOException && m != null && !m.isEmpty()) return m;
        String cls = t.getClass().getSimpleName();
        return (m == null || m.isEmpty()) ? cls : cls + ": " + m;
    }

    private static long freeBytes(File dir) {
        try {
            StatFs st = new StatFs(dir.getAbsolutePath());
            return st.getAvailableBytes();
        } catch (Exception e) {
            return -1;
        }
    }

    private static long mb(long bytes) {
        if (bytes < 0) return 0;
        return (bytes + 1048575) / 1048576;
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

    private static boolean deleteRecursively(File f) {
        boolean ok = true;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) ok &= deleteRecursively(k);
            }
        }
        return f.delete() && ok;
    }

    // ---------- 通知 ----------

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

    private Notification.Builder baseNotification() {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return b.setSmallIcon(R.drawable.ic_stat_dsh).setOngoing(true);
    }

    private Notification buildNotification() {
        return baseNotification()
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(getString(R.string.notif_text))
                .setOnlyAlertOnce(true)
                .build();
    }

    /** 解压期间的带进度通知（0-100 确定进度） */
    private void notifyProgress(int pct, String detail) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        Notification n = baseNotification()
                .setContentTitle(getString(R.string.notif_extract_title))
                .setContentText(detail)
                .setProgress(100, pct, false)
                .setOnlyAlertOnce(true)
                .build();
        nm.notify(NOTIF_ID, n);
    }

    private void notifyServerState(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        nm.notify(NOTIF_ID, baseNotification()
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(text)
                .setOnlyAlertOnce(true)
                .build());
    }

    private void updateNotificationRunningFailed() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        nm.notify(NOTIF_ID, baseNotification()
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(getString(R.string.notif_text_failed))
                .setOnlyAlertOnce(true)
                .build());
    }
}
