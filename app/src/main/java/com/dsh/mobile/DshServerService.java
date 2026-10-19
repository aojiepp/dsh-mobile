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
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Enumeration;
import java.util.regex.Pattern;
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
    /** server_state 最近更新时间戳（毫秒）：UI 用它区分「陈旧历史状态」与本次启动 */
    public static final String KEY_SERVER_TS = "server_ts";

    private static final String TAG = "DshServer";
    private static final int NOTIF_ID = 1;
    private static final String CHANNEL_ID = "dsh_server";
    private static final String PAYLOAD_ZIP = "payload.zip";
    /** 服务日志（node stdout/stderr；设置页「查看服务日志」也读它） */
    private static final String SERVER_LOG = "dsh-server.log";
    /** 轮转出去的上一代日志（启动时把 SERVER_LOG 改名过来并打码） */
    private static final String SERVER_LOG_ROTATED = "dsh-server.1.log";
    /** 历史遗留文件：老版本手写日志，里面可能有旧 token，启动时清掉 */
    private static final String LEGACY_MANUAL_LOG = "manual.log";
    /** log-watcher 抓到带 token 的启动 URL 后写入的 prefs key；
     *  MainActivity 优先读它（时间戳不早于本次等待起点才信），读不到再扫日志兜底 */
    public static final String KEY_LAUNCH_URL = "launch_url";
    public static final String KEY_LAUNCH_URL_TS = "launch_url_ts";
    /** 日志打码：query 里的令牌值（?token= / &api_key= / &key=）替换为 *** */
    private static final Pattern LOG_TOKEN_PATTERN =
            Pattern.compile("([?&](token|api_key|key)=)[^&\\s]+");
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

    /** 权限 patch 文件名：以 --patch 叠加到 web profile 之上，见 ensurePermissionPatch() */
    private static final String PERMISSION_PATCH = "dsh-permission.patch.yml";

    public static File permissionPatchFile(Context ctx) {
        return new File(ctx.getFilesDir(), PERMISSION_PATCH);
    }

    /**
     * 写权限 patch 文件：把部署默认权限改成 danger-full-access + approval never。
     *
     * 为什么必须走 --patch 而不是环境变量：dsh 0.2.1 的 base profile 里写的是
     * `mode: !!js process.env.DSH_PERMISSION_MODE ?? 'workspace-write'`，但那个
     * `!!js` 表达式在 profile 组合阶段求值时拿不到我们注入子进程的 env（实测：
     * node 进程 environ 里 DSH_PERMISSION_MODE 明明在，会话仍然 pin 了
     * workspace-write）。--patch 是字面量覆盖，不经过表达式求值，可靠。
     *
     * 为什么非要 danger-full-access：Android 上没有 bwrap，payload 也没带
     * Landlock launcher（@deepseek-ai/node-addon-system-linux-arm64 是可选依赖，
     * 打 payload 的机器上装不到），sandbox-local 是 fail-closed 的 —— 默认的
     * workspace-write 会让 bash 工具直接抛 SANDBOX_UNAVAILABLE，智能体一条命令
     * 都执行不了。danger-full-access 下 bash/fs/terminal 都跳过 DSH 的围栏，
     * Android 自身的 app 沙箱（UID + SELinux + 私有目录）仍是硬边界。
     * approval=never 配套：手机端没有审批 UI，ask 会把危险操作卡死。
     */
    public static void ensurePermissionPatch(Context ctx) {
        File f = permissionPatchFile(ctx);
        File workspace = new File(ctx.getFilesDir(), "workspace");
        String body = "# dsh-mobile 生成：Android 无 bwrap/Landlock runner，DSH sandbox 会 fail-closed；\n"
                + "# 这里把部署默认权限设为 danger-full-access（Android app 沙箱仍是硬边界）。\n"
                + "- id: sandbox-policy\n"
                + "  name: '@deepseek-ai/dsh-sandbox-policy'\n"
                + "  config:\n"
                + "    mode: danger-full-access\n"
                + "    workspaceRoot: " + workspace.getAbsolutePath() + "\n"
                + "- id: approval\n"
                + "  name: '@deepseek-ai/dsh-user-approval'\n"
                + "  config:\n"
                + "    policy: never\n";
        try {
            if (!workspace.exists()) workspace.mkdirs();
            java.io.FileOutputStream out = new java.io.FileOutputStream(f, false);
            try {
                out.write(body.getBytes("UTF-8"));
            } finally {
                out.close();
            }
            f.setReadable(false, false);
            f.setReadable(true, true);
        } catch (Exception e) {
            Log.w(TAG, "ensurePermissionPatch failed", e);
        }
    }

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

    /** server_state 的更新时间；0 表示从未写过 */
    public static long serverStateUpdatedAt(Context ctx) {
        String v = ctx.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
                .getString(KEY_SERVER_TS, "");
        try { return Long.parseLong(v); } catch (Exception e) { return 0; }
    }

    /** 写服务状态 + 时间戳（v2.9.1：UI 靠时间戳忽略上一轮留下的 exited:13 之类陈旧状态） */
    private static void setServerState(Context ctx, String state) {
        ctx.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_SERVER_STATE, state)
                .putString(KEY_SERVER_TS, String.valueOf(System.currentTimeMillis()))
                .apply();
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
            setServerState(this, STATE_STARTING);
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
            setServerState(this, STATE_RUNNING);
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

        // 安全基线（真机安全审计整改）：都赶在解压之前做——首启解压要几分钟，
        // 清 token 这种事不能等。
        rotateServerLog();          // 上一轮日志改名 .1 并对令牌打码
        deleteLegacyManualLog();    // 历史遗留 manual.log（含旧 token），存在才删
        hardenEnvPerms(home);       // dsh-home/.env 若存在，权限刷成 600 语义

        if (!isPayloadReady(this)) {
            if (!extractPayload()) {
                setServerState(this, STATE_FAILED);
                updateNotificationRunningFailed();
                return;
            }
        }

        setServerState(this, STATE_STARTING);
        notifyServerState(getString(R.string.notif_text_starting));
        int restarts = 0;
        while (!stopping && gen == bootGen) {
            try {
                // Termux 版 node 的 OpenSSL 把配置路径编成了
                // /data/data/com.termux/files/usr/etc/tls/openssl.cnf，
                // 在本机不存在 → 初始化 crypto 时直接以退出码 13 死掉（v2.9.1 修复）。
                // 这里补一份最小配置并用 OPENSSL_CONF 指过去。
                File opensslConf = ensureOpensslConf();
                // 权限 patch（danger-full-access + approval never）：必须在 spawn 前写好
                ensurePermissionPatch(this);
                ProcessBuilder pb = new ProcessBuilder(
                        nodeBin.getAbsolutePath(),
                        "--expose-internals",
                        "lib/bin.js",
                        // launcher flag 必须在 profile 之前：bin.js 遇到第一个不认识的
                        // token 就开始把剩下的当 app 参数透传
                        "--profile", "web",
                        "--patch", permissionPatchFile(this).getAbsolutePath(),
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
                if (opensslConf != null) {
                    pb.environment().put("OPENSSL_CONF", opensslConf.getAbsolutePath());
                }
                pb.environment().put("PATH",
                        new File(payloadDir(this), "termux/usr/bin").getAbsolutePath()
                                + ":/system/bin:/system/xbin");
                pb.environment().put("LANG", "C.UTF-8");
                pb.environment().put("LC_ALL", "C.UTF-8");
                pb.environment().put("NODE_NO_WARNINGS", "1");
                // 权限模式 = danger-full-access（v2.12）：DSH 0.2.1 的 file sandbox 是
                // fail-closed 的——sandbox-local 在 linux 上只认 bwrap 或 Landlock launcher
                // 预编译二进制，Android 上两者都不存在（payload 没有 landlock 的 linux-arm64
                // 包），于是默认的 workspace-write 会让 bash 工具直接抛 SANDBOX_UNAVAILABLE，
                // 一条命令都执行不了（git/curl/npm 全锁死）。danger-full-access 是 upstream
                // 留的逃生口：bash-sandbox/fs-sandbox/terminal 在此模式下直接裸跑，不再套
                // DSH 的围栏；Android 自身的 app 沙箱（UID/SELinux/私有目录）仍是硬边界。
                pb.environment().put("DSH_PERMISSION_MODE", "danger-full-access");
                // 开发工具链（git/curl/openssh/npm/pnpm）：PATH、git CA/exec-path、npm/pnpm 落盘目录
                ToolchainEnv.apply(pb.environment(), this);
                pb.redirectErrorStream(true);
                File serverLog = new File(getFilesDir(), SERVER_LOG);
                pb.redirectOutput(serverLog);
                nodeProcess = pb.start();
                // 新日志落盘即收紧成仅属主可读写：首屏就印着带 token 的启动 URL
                hardenOwnerOnly(serverLog);
                // log-watcher：tail 日志抓带 token 的启动 URL 写进 prefs，
                // MainActivity 直接取用，不用再等日志扫描窗口
                startLogWatcher(nodeProcess, gen);
                Log.i(TAG, "node spawned, waiting for exit");
                setServerState(this, STATE_STARTING);
                // 端口真起来了再置 RUNNING（v2.9.1：以前状态永远停在 starting）；
                // 期间 node 若自己挂了就立刻退出等待，不影响下面的退出处理
                long readyDeadline = System.currentTimeMillis() + 90000;
                while (!stopping && gen == bootGen
                        && System.currentTimeMillis() < readyDeadline) {
                    if (isPortOpen(3080, 300)) {
                        setServerState(this, STATE_RUNNING);
                        Log.i(TAG, "dsh web port 3080 is up");
                        break;
                    }
                    if (!isAlive()) break; // node 已退出，交给下面的 waitFor 统一处理
                    sleepQuietly(300);
                }
                int code = nodeProcess.waitFor();
                nodeProcess = null;
                if (stopping || gen != bootGen) return;
                restarts++;
                Log.w(TAG, "dsh server exited code=" + code + " restart=" + restarts);
                setServerState(this, STATE_EXITED + ":" + code);
                if (restarts >= MAX_RESTARTS) {
                    Log.e(TAG, "too many restarts, giving up");
                    updateNotificationRunningFailed();
                    return;
                }
            } catch (IOException e) {
                Log.e(TAG, "spawn failed", e);
                setServerState(this, STATE_FAILED + ":" + e.getMessage());
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
        setServerState(this, "stopped");
    }

    private boolean isAlive() {
        try {
            nodeProcess.exitValue();
            return false;
        } catch (IllegalThreadStateException e) {
            return true; // 还没退出
        }
    }

    private static boolean procAlive(Process p) {
        try {
            p.exitValue();
            return false;
        } catch (IllegalThreadStateException e) {
            return true; // 还没退出
        }
    }

    // ---------- 安全基线：日志轮转 / 打码 / 权限 ----------

    /** 把文件权限刷成「仅属主可读写」（600 语义）：先全关再只开属主——
     *  两步缺一不可，setReadable(true,true) 不会清掉 group/other 已有的读位。 */
    private static void hardenOwnerOnly(File f) {
        f.setReadable(false, false);
        f.setWritable(false, false);
        f.setReadable(true, true);
        f.setWritable(true, true);
    }

    /** 启动日志轮转：本次启动前把 dsh-server.log 改名为 dsh-server.1.log
     *  （先删更旧的 .1），再对 .1 做 token 打码。打码只在轮转时做——
     *  运行中的日志 node 还在追加，边写边改有竞态。 */
    private void rotateServerLog() {
        File cur = new File(getFilesDir(), SERVER_LOG);
        File old = new File(getFilesDir(), SERVER_LOG_ROTATED);
        if (old.exists() && !old.delete()) {
            Log.w(TAG, "log rotate: delete old .1 failed");
        }
        if (!cur.exists()) return;
        if (!cur.renameTo(old)) {
            Log.w(TAG, "log rotate: rename failed");
            return;
        }
        redactLogTokens(old);
        hardenOwnerOnly(old);
    }

    /** 对轮转出的旧日志逐行打码（流式读写，不把整文件装进内存）；
     *  有改动才用临时文件改名覆盖，没动过不重写。 */
    private void redactLogTokens(File f) {
        File tmp = new File(f.getParentFile(), f.getName() + ".redact");
        boolean changed = false;
        try (java.io.BufferedReader br = new java.io.BufferedReader(
                     new java.io.InputStreamReader(new FileInputStream(f), "UTF-8"));
             java.io.BufferedWriter bw = new java.io.BufferedWriter(
                     new java.io.OutputStreamWriter(new FileOutputStream(tmp), "UTF-8"))) {
            String line;
            while ((line = br.readLine()) != null) {
                String masked = LOG_TOKEN_PATTERN.matcher(line).replaceAll("$1***");
                if (!masked.equals(line)) changed = true;
                bw.write(masked);
                bw.newLine();
            }
        } catch (Exception e) {
            Log.w(TAG, "log rotate: redact failed", e);
            tmp.delete();
            return;
        }
        if (!changed) {
            tmp.delete();
            return;
        }
        if (!f.delete() || !tmp.renameTo(f)) {
            Log.w(TAG, "log rotate: replace with redacted copy failed");
            tmp.delete();
        }
    }

    /** 历史遗留清理：老版本的手写日志里可能有旧 token，存在才删 */
    private void deleteLegacyManualLog() {
        File f = new File(getFilesDir(), LEGACY_MANUAL_LOG);
        if (!f.exists()) return;
        if (f.delete()) {
            Log.i(TAG, "legacy manual.log deleted");
        } else {
            Log.w(TAG, "legacy manual.log delete failed");
        }
    }

    /** dsh-home/.env（API Key 落点）若存在，权限刷成 600 语义。
     *  设置页写入时已做过一次；服务每次启动再兜一遍，防旧版本残留。 */
    private void hardenEnvPerms(File home) {
        File env = new File(home, ".env");
        if (!env.exists()) return;
        hardenOwnerOnly(env);
        env.setExecutable(false, false);
    }

    // ---------- 安全基线：log-watcher（token URL 直达 prefs） ----------

    /** tail 服务日志，发现行首 "dsh web: http" 就把带 token 的完整 URL 写进
     *  SharedPreferences（launch_url + launch_url_ts），MainActivity 直接取用。
     *  进程退出或 bootGen 换代时线程结束。写 prefs 成功后把该行 token 就地打码
     *  （只覆盖当前文件中该行的 value 字节，不动文件长度/追加点——node 在末尾追加，
     *  我们 pwrite 前面的已固化字节，无结构竞态）；其余打码留给轮转做。 */
    private void startLogWatcher(final Process proc, final int gen) {
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                File log = new File(getFilesDir(), SERVER_LOG);
                long pos = 0;
                while (gen == bootGen && procAlive(proc) && !stopping) {
                    try {
                        pos = scanLogNewLines(log, pos, gen);
                    } catch (Exception e) {
                        Log.w(TAG, "log watcher: scan failed", e);
                    }
                    sleepQuietly(400);
                }
            }
        }, "dsh-logwatch");
        t.setDaemon(true);
        t.start();
    }

    /** 从 pos 起读新增的行，返回新的读取位置；日志被截断重写（node 重启时
     *  Redirect.to 会清空）就回到 0。文件末尾没换行的残行不消费、留给下一轮，
     *  防止抓到半个 URL。返回新位置。RAF 以 "rw" 打开供命中行就地打码。 */
    private long scanLogNewLines(File log, long pos, int gen) throws IOException {
        if (!log.exists()) return pos;
        RandomAccessFile raf = new RandomAccessFile(log, "rw");
        try {
            long len = raf.length();
            if (len < pos) pos = 0;
            raf.seek(pos);
            String line;
            while ((line = raf.readLine()) != null) {
                long ptr = raf.getFilePointer();
                if (ptr >= len) {
                    // 读到文件末尾：以换行符结尾才算整行
                    raf.seek(ptr - 1);
                    boolean terminated = raf.read() == '\n';
                    if (!terminated) break; // 残行留给下一轮
                    raf.seek(ptr);
                }
                handleLogLine(raf, pos, line, gen);
                pos = ptr;
            }
            return pos;
        } finally {
            raf.close();
        }
    }

    /** watcher 命中 "dsh web: http"：先把完整带 token URL 写进 prefs（解析规则与
     *  MainActivity 日志扫描一致：取到空格前第一段），写成功后把日志里该行的
     *  token/api_key/key 值就地覆盖成等长 ***（raf.readLine 按字节读 ASCII，
     *  字符下标即字节偏移；覆盖的是已换行固化的字节，不影响 node 继续追加）。 */
    private void handleLogLine(RandomAccessFile raf, long lineStart, String line, int gen) {
        if (!line.startsWith("dsh web: http")) return;
        String u = line.substring("dsh web: ".length()).trim();
        int sp = u.indexOf(' ');
        if (sp > 0) u = u.substring(0, sp);
        if (u.isEmpty()) return;
        getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LAUNCH_URL, u)
                .putString(KEY_LAUNCH_URL_TS, String.valueOf(System.currentTimeMillis()))
                .apply();
        Log.i(TAG, "log watcher: launch url captured (gen=" + gen + ")");
        // prefs 已落地，运行中的日志里不再保留 token 明文（就地等长打码）
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("([?&](?:token|api_key|key)=)[^&\\s]+")
                    .matcher(line);
            while (m.find()) {
                int vStart = m.start() + m.group(1).length();
                int vLen = m.end() - vStart;
                long resume = raf.getFilePointer();
                raf.seek(lineStart + vStart);
                for (int i = 0; i < vLen; i++) raf.write('*');
                raf.seek(resume);
            }
        } catch (Exception e) {
            Log.w(TAG, "log watcher: in-place redact failed", e);
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
                // ---- 0. payload 完整性校验（解压前；不一致就别白解压一遍）----
                reportExtractProgress(0, getString(R.string.extract_phase_verify));
                String checksumError = verifyPayloadChecksum();
                if (checksumError != null) {
                    failExtract(getString(R.string.extract_err_checksum, checksumError));
                    deleteRecursively(payloadDir(this));
                    return false;   // finally 会把 extractingNow 复位、删掉临时 zip
                }
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

                // ---- 5. 可执行位（zip 不保留权限）：bin/ + libexec/（含 git-core）全量 ----
                // 不能只 chmod 三件套：git/curl/ssh/npm wrapper 以及 libexec/git-core/**
                // 里的 dashed 入口都需要可执行位。
                File usrDir = new File(payloadDir(this), "termux/usr");
                setTreeExecutable(new File(usrDir, "bin"));
                setTreeExecutable(new File(usrDir, "libexec"));

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

    /**
     * v2.11：解压前校验 payload.zip 的 sha256。
     * assets/payload.sha256 由 payload/build_payload.py 生成（sha256sum 行：
     * "<64 位十六进制>  payload.zip"）。返回 null 表示校验通过或没有清单
     * （旧 APK 兼容）；否则返回失败原因。
     */
    private String verifyPayloadChecksum() {
        String expected;
        try (InputStream in = getAssets().open("payload.sha256")) {
            byte[] buf = new byte[4096];
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            String line = new String(bos.toByteArray(), "UTF-8").trim();
            int sp = line.indexOf(' ');
            expected = (sp > 0 ? line.substring(0, sp) : line).trim()
                    .toLowerCase(java.util.Locale.US);
        } catch (IOException missing) {
            return null;                    // 没有校验清单：旧包/裁剪构建，跳过
        }
        if (expected.length() != 64) return null;
        String actual;
        try (InputStream in = getAssets().open(PAYLOAD_ZIP)) {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[256 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : md.digest()) sb.append(String.format("%02x", b & 0xff));
            actual = sb.toString();
        } catch (Exception e) {
            return "读取 APK 内的 payload.zip 失败：" + e.getMessage();
        }
        if (expected.equals(actual)) return null;
        return "sha256 不匹配（清单 " + expected.substring(0, 12)
                + "…，实际 " + actual.substring(0, 12) + "…）";
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

    /** 补一份最简 openssl.cnf 并用 OPENSSL_CONF 指过去。
     *  Termux 版 node 把 OpenSSL 配置目录编死成 /data/data/com.termux/files/usr/etc/tls，
     *  在本应用里不存在 → node 初始化 crypto 时直接退出（实测退出码 13）。 */
    private File ensureOpensslConf() {
        try {
            File dir = new File(payloadDir(this), "termux/usr/etc/tls");
            if (!dir.exists() && !dir.mkdirs()) return null;
            File conf = new File(dir, "openssl.cnf");
            if (!conf.exists() || conf.length() == 0) {
                try (FileOutputStream fos = new FileOutputStream(conf)) {
                    // 只有注释 = 不覆盖任何默认值，OpenSSL 3 会自动加载 default provider
                    fos.write(("# DSH Mobile: minimal OpenSSL config\r\n"
                            + "# Termux node bakes its OpenSSL config path to\r\n"
                            + "# /data/data/com.termux/files/usr/etc/tls/openssl.cnf,\r\n"
                            + "# which does not exist inside this app. Pointing OPENSSL_CONF here\r\n"
                            + "# keeps OpenSSL on its built-in defaults.\r\n").getBytes("UTF-8"));
                }
                conf.setReadable(true, true);
            }
            if (!conf.canRead()) return null;
            return conf;
        } catch (Exception e) {
            Log.w(TAG, "write openssl.cnf failed", e);
            return null;
        }
    }

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

    /** 递归给一棵树里的所有文件置可执行位（zip 不保留 unix mode）。 */
    private static void setTreeExecutable(File dir) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File f : kids) {
            if (f.isDirectory()) setTreeExecutable(f);
            else if (!f.setExecutable(true, false)) {
                Log.w(TAG, "setExecutable returned false: " + f.getAbsolutePath());
            }
        }
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
