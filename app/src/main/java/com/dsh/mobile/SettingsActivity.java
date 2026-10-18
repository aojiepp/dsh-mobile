package com.dsh.mobile;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

import javax.net.ssl.SSLException;

/** 设置页：运行模式（本机内置服务 / PC 服务器）、连接检测、API Key、服务状态与重启、诊断 */
public class SettingsActivity extends Activity {

    private RadioGroup modeGroup;
    private RadioButton radioLocal;
    private RadioButton radioPc;
    private EditText urlInput;
    private EditText apiKeyInput;
    private CheckBox keepOn;
    private TextView statusText;
    private TextView apiKeyHint;
    private TextView pcStatus;
    private TextView hintConn;
    private Button btnRestart;
    private Button btnReextract;
    private Button btnTest;
    private SharedPreferences prefs;
    /** v2.12 诊断区块 */
    private TextView diagInfo;
    private TextView diagPort;
    private TextView diagLog;
    /** 连接电脑：非回环 http 明文传输提醒（默认隐藏，TextWatcher 切换） */
    private TextView urlWarn;
    /** v2.9：状态每秒刷新（解压百分比/停滞判断需要实时值，不能只在 onResume 刷一次） */
    private final android.os.Handler statusHandler = new android.os.Handler();
    private Runnable statusTask;

    /** 日志/地址里的凭据打码：([?&](token|api_key|key)=)[^&\s]+ → $1*** */
    private static final Pattern TOKEN_MASK =
            Pattern.compile("([?&](token|api_key|key)=)[^&\\s]+");

    private static String maskTokens(String text) {
        if (text == null || text.isEmpty()) return "";
        return TOKEN_MASK.matcher(text).replaceAll("$1***");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);

        radioLocal = findViewById(R.id.radio_local);
        radioPc = findViewById(R.id.radio_pc);
        modeGroup = findViewById(R.id.mode_group);
        urlInput = findViewById(R.id.url_input);
        apiKeyInput = findViewById(R.id.api_key_input);
        apiKeyHint = findViewById(R.id.api_key_hint);
        keepOn = findViewById(R.id.keep_on);
        statusText = findViewById(R.id.server_status);
        pcStatus = findViewById(R.id.pc_status);
        hintConn = findViewById(R.id.hint_conn);
        btnRestart = findViewById(R.id.btn_restart);
        btnReextract = findViewById(R.id.btn_reextract);
        btnTest = findViewById(R.id.btn_test);
        diagInfo = findViewById(R.id.diag_info);
        diagPort = findViewById(R.id.diag_port);
        diagLog = findViewById(R.id.diag_log);
        urlWarn = findViewById(R.id.url_warn);

        boolean local = MainActivity.MODE_LOCAL.equals(
                prefs.getString(MainActivity.KEY_MODE, MainActivity.MODE_LOCAL));
        radioLocal.setChecked(local);
        radioPc.setChecked(!local);

        urlInput.setText(prefs.getString(MainActivity.KEY_URL, MainActivity.DEFAULT_URL));
        apiKeyInput.setText(prefs.getString(MainActivity.KEY_API_KEY, ""));
        keepOn.setChecked(prefs.getBoolean(MainActivity.KEY_KEEP_ON, true));

        modeGroup.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(RadioGroup group, int checkedId) {
                toggleModeUi();
            }
        });

        findViewById(R.id.btn_save).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { save(); }
        });

        btnRestart.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { restartServer(); }
        });

        btnReextract.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { confirmReextract(); }
        });

        findViewById(R.id.btn_view_log).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showServerLog(); }
        });

        btnTest.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { testConnection(); }
        });

        findViewById(R.id.btn_artifacts).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(SettingsActivity.this, ArtifactsActivity.class));
            }
        });

        toggleModeUi();

        TextView version = findViewById(R.id.version);
        version.setText(getString(R.string.app_name) + " v" + MainActivity.VERSION);

        // v2.12 诊断：版本/校验和/构建时间 + 日志关键字；明文传输提醒跟着输入框走
        initDiagnostics();
        urlInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { updateUrlWarning(); }
        });
        updateUrlWarning();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
        if (statusTask == null) {
            statusTask = new Runnable() {
                @Override public void run() {
                    refreshStatus();
                    statusHandler.postDelayed(statusTask, 1000);
                }
            };
        }
        statusHandler.removeCallbacks(statusTask);
        statusHandler.postDelayed(statusTask, 1000);
    }

    @Override
    protected void onPause() {
        statusHandler.removeCallbacks(statusTask);
        super.onPause();
    }

    private boolean localChecked() { return radioLocal.isChecked(); }

    /** 按模式切换界面：本机模式显示重启/API Key，连接电脑模式显示检测按钮与指引 */
    private void toggleModeUi() {
        boolean local = localChecked();
        btnRestart.setVisibility(local ? View.VISIBLE : View.GONE);
        btnReextract.setVisibility(local ? View.VISIBLE : View.GONE);
        apiKeyInput.setEnabled(local);
        apiKeyHint.setEnabled(local);
        btnTest.setVisibility(local ? View.GONE : View.VISIBLE);
        pcStatus.setVisibility(local ? View.GONE : View.VISIBLE);
        hintConn.setVisibility(local ? View.GONE : View.VISIBLE);
        refreshStatus();
    }

    private void refreshStatus() {
        // 诊断：端口 3080 实时状态（每秒一次，和下面的服务状态共用一次探测）
        boolean portOpen = DshServerService.isPortOpen(3080, 300);
        if (diagPort != null) {
            diagPort.setText(getString(R.string.diag_port_label,
                    getString(portOpen ? R.string.diag_port_open : R.string.diag_port_closed)));
        }
        if (!localChecked()) {
            statusText.setText(R.string.status_pc_note);
            return;
        }
        String st = DshServerService.serverState(this);
        String pt = DshServerService.payloadState(this);
        boolean extracting = pt.startsWith(DshServerService.STATE_EXTRACTING);
        // 解压期间禁止「重启」（解压线程还在写运行时树，重启只会让状态乱跳）
        btnRestart.setEnabled(!extracting);
        btnReextract.setEnabled(!extracting);
        btnRestart.setText(extracting ? R.string.btn_restart_disabled : R.string.btn_restart_server);

        if (extracting) {
            int pct = DshServerService.payloadPercent(this);
            StringBuilder sb = new StringBuilder(pct >= 0
                    ? getString(R.string.status_extracting_pct, pct)
                    : getString(R.string.status_extracting));
            String detail = DshServerService.payloadDetail(this);
            if (detail != null && !detail.isEmpty()) sb.append("\n").append(detail);
            long ts = DshServerService.payloadUpdatedAt(this);
            if (ts > 0 && System.currentTimeMillis() - ts > 25000) {
                sb.append("\n").append(getString(R.string.status_extract_stalled));
            }
            statusText.setText(sb.toString());
        } else if (pt.startsWith(DshServerService.STATE_FAILED)) {
            String err = DshServerService.extractError(this);
            statusText.setText(getString(R.string.status_failed)
                    + (err == null || err.isEmpty() ? "" : "\n" + err));
        } else if (st == null || st.isEmpty()) {
            statusText.setText(DshServerService.isPayloadReady(this)
                    ? R.string.status_ready : R.string.status_no_info);
        } else if (st.equals(DshServerService.STATE_RUNNING)) {
            statusText.setText(R.string.status_running);
        } else if (st.startsWith(DshServerService.STATE_EXITED)) {
            // 陈旧状态（上一轮退出）不再吓人：端口在监听就按运行中处理
            if (portOpen) {
                statusText.setText(R.string.status_running);
            } else {
                statusText.setText(getString(R.string.status_exited) + " (" + st + ")");
            }
        } else if (st.equals("stopped")) {
            statusText.setText(R.string.status_stopped);
        } else if (st.startsWith(DshServerService.STATE_FAILED)) {
            String d = st;
            String p = DshServerService.STATE_FAILED + ":";
            if (d.startsWith(p)) d = d.substring(p.length());
            statusText.setText(getString(R.string.status_failed) + "\n" + d);
        } else {
            statusText.setText(getString(R.string.status_starting));
        }
    }

    // ---------- v2.12 诊断区块 ----------

    /** 一次性填充：versionName(versionCode) + BuildInfo 的 payload 校验和/构建时间 + 日志关键字 */
    private void initDiagnostics() {
        String verName = MainActivity.VERSION;
        int verCode = BuildInfo.VERSION_CODE;
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            if (pi.versionName != null && !pi.versionName.isEmpty()) verName = pi.versionName;
            verCode = pi.versionCode;
        } catch (Exception ignored) {
            // 包信息读不到就退回 BuildInfo 的编译期常量
        }
        String sha = BuildInfo.PAYLOAD_SHA256 == null ? "" : BuildInfo.PAYLOAD_SHA256;
        if (sha.length() > 12) sha = sha.substring(0, 12) + "…";

        StringBuilder sb = new StringBuilder();
        sb.append(getString(R.string.diag_version, verName, verCode));
        sb.append('\n').append(getString(R.string.diag_payload_sha, sha));
        sb.append('\n').append(getString(R.string.diag_build_time, BuildInfo.BUILD_TIME));
        if (BuildInfo.DEBUG) sb.append('\n').append(getString(R.string.diag_debug_build));
        diagInfo.setText(maskTokens(sb.toString()));
        diagLog.setText(readLogKeywords());
    }

    /** 读 dsh-server.log 尾部，取最后 20 行里含 plugin / error 的行（没有则「无」） */
    private String readLogKeywords() {
        File log = new File(getFilesDir(), "dsh-server.log");
        if (!log.exists() || log.length() == 0) return getString(R.string.diag_log_none);
        try {
            long len = log.length();
            long start = Math.max(0, len - 16384);
            byte[] buf = new byte[(int) (len - start)];
            try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(log, "r")) {
                raf.seek(start);
                raf.readFully(buf);
            }
            String[] lines = new String(buf, StandardCharsets.UTF_8).split("\n");
            int from = Math.max(0, lines.length - 20);
            StringBuilder sb = new StringBuilder();
            for (int i = from; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.isEmpty()) continue;
                String low = line.toLowerCase(Locale.US);
                if (low.contains("plugin") || low.contains("error")) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(line);
                }
            }
            return sb.length() == 0
                    ? getString(R.string.diag_log_none) : maskTokens(sb.toString());
        } catch (Exception e) {
            return getString(R.string.diag_log_fail, String.valueOf(e.getMessage()));
        }
    }

    /** 「连接电脑」地址栏下方的明文传输提醒：仅当输入是「非回环 http://」时可见 */
    private void updateUrlWarning() {
        if (urlWarn == null) return;
        urlWarn.setVisibility(isPlainHttpNonLoopback(urlInput.getText().toString())
                ? View.VISIBLE : View.GONE);
    }

    /** 非回环地址 + http 明文 = 同一网络里可被嗅探（https / 回环地址不提醒） */
    static boolean isPlainHttpNonLoopback(String raw) {
        String url = normalizeUrl(raw).toLowerCase(Locale.US);
        if (!url.startsWith("http://")) return false;
        String rest = url.substring(7);
        int cut = rest.length();
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == '/' || c == '?' || c == '#') { cut = i; break; }
        }
        String host = rest.substring(0, cut);
        int at = host.lastIndexOf('@');
        if (at >= 0) host = host.substring(at + 1);
        if (host.startsWith("[")) {
            int close = host.indexOf(']');
            if (close >= 0) host = host.substring(0, close + 1);
        } else {
            int colon = host.indexOf(':');
            if (colon >= 0) host = host.substring(0, colon);
        }
        return !(host.equals("127.0.0.1") || host.equals("localhost")
                || host.equals("[::1]") || host.equals("::1")
                || host.equals("0:0:0:0:0:0:0:1"));
    }

    /** 二次确认后清运行时重解压（会话/凭据在 dsh-home，不动） */
    /** 查看内置服务的运行日志（node 的 stdout/stderr，v2.9.1 起可直接在手机上排障） */
    private void showServerLog() {
        String text;
        File log = new File(getFilesDir(), "dsh-server.log");
        try {
            if (!log.exists() || log.length() == 0) {
                text = getString(R.string.log_empty);
            } else {
                long len = log.length();
                long start = Math.max(0, len - 12288);
                byte[] buf = new byte[(int) (len - start)];
                try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(log, "r")) {
                    raf.seek(start);
                    raf.readFully(buf);
                }
                text = new String(buf, StandardCharsets.UTF_8);
                if (start > 0) text = "…\n" + text;
            }
        } catch (Exception e) {
            text = "读取日志失败：" + e.getMessage();
        }
        TextView tv = new TextView(this);
        // v2.12：日志里可能带 token/api_key，显示前统一打码
        tv.setText(maskTokens(text));
        tv.setTextSize(11);
        tv.setTextIsSelectable(true);
        tv.setPadding(32, 24, 32, 24);
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(tv);
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.dialog_log)
                .setView(sv)
                .setPositiveButton(R.string.dlg_cancel, null)
                .show();
    }

    private void confirmReextract() {
        if (!localChecked()) return;
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.btn_reextract)
                .setMessage(R.string.reextract_confirm)
                .setNegativeButton(R.string.dlg_cancel, null)
                .setPositiveButton(R.string.btn_reextract, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        android.content.Intent it = new android.content.Intent(
                                SettingsActivity.this, DshServerService.class);
                        it.setAction(DshServerService.ACTION_RESTART_EXTRACT);
                        if (Build.VERSION.SDK_INT >= 26) {
                            startForegroundService(it);
                        } else {
                            startService(it);
                        }
                        statusText.setText(R.string.status_extracting);
                        btnRestart.setEnabled(false);
                        btnReextract.setEnabled(false);
                    }
                })
                .show();
    }

    private void save() {
        boolean local = localChecked();
        String url = normalizeUrl(urlInput.getText().toString().trim());
        String apiKey = apiKeyInput.getText().toString().trim();

        prefs.edit()
                .putString(MainActivity.KEY_MODE, local ? MainActivity.MODE_LOCAL : MainActivity.MODE_PC)
                .putString(MainActivity.KEY_URL, url)
                .putString(MainActivity.KEY_API_KEY, apiKey)
                .putBoolean(MainActivity.KEY_KEEP_ON, keepOn.isChecked())
                .apply();

        if (local) {
            writeApiKeyEnv(apiKey);
            restartServer();
            Toast.makeText(this, R.string.saved_local, Toast.LENGTH_SHORT).show();
            finish();
        } else {
            // 切到「连接电脑」：停掉本机服务，释放 127.0.0.1:3080 给 adb reverse 隧道
            Intent stop = new Intent(this, DshServerService.class);
            stop.setAction(DshServerService.ACTION_STOP);
            startService(stop);
            // 连接电脑模式：留在设置页，让用户直接看到检测结果
            Toast.makeText(this, R.string.saved_testing, Toast.LENGTH_SHORT).show();
            testConnection();
        }
    }

    private static String normalizeUrl(String url) {
        if (url == null) url = "";
        url = url.trim();
        if (url.isEmpty()) url = MainActivity.DEFAULT_URL;
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "http://" + url;
        return url;
    }

    /** 连接探测结果：类别用于决定是否重试（切换模式的瞬间连接会被重置/拒绝） */
    private static class ProbeResult {
        final String kind;   // OK / OK_UNKNOWN / HTTP / REFUSED / TIMEOUT / HOST / SSL / ERR
        final String text;
        ProbeResult(String kind, String text) { this.kind = kind; this.text = text; }
    }

    /** 「连接电脑」模式的连接检测：后台 HTTP 探测，把失败原因翻译成人话 */
    private void testConnection() {
        final String url = normalizeUrl(urlInput.getText().toString());
        pcStatus.setText(R.string.pc_status_testing);
        new Thread(new Runnable() {
            @Override public void run() {
                ProbeResult r = probeUrl(url);
                // 保存后隧道（adb reverse / 本机服务让位）可能在数秒内才就绪，
                // 对瞬时性失败（拒绝/连接重置）稍候重试两次，避免误报
                int tries = 0;
                while ((r.kind.equals("REFUSED") || r.kind.equals("ERR")) && tries < 2) {
                    try { Thread.sleep(2000); } catch (InterruptedException e) { break; }
                    r = probeUrl(url);
                    tries++;
                }
                final String text = r.text;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        pcStatus.setText(text);
                    }
                });
            }
        }).start();
    }

    private ProbeResult probeUrl(String url) {
        HttpURLConnection conn = null;
        try {
            URL u = new URL(url);
            conn = (HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(5000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "DSH-Mobile-ConnectionTest/2.7");
            int code = conn.getResponseCode();
            if (code == 200) {
                InputStream in = conn.getInputStream();
                StringBuilder sb = new StringBuilder();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0 && sb.length() < 300000) {
                    sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                }
                in.close();
                String body = sb.toString();
                if (body.contains("__DSH_BOOT__") || body.contains("DeepSeek Harness")) {
                    return new ProbeResult("OK", getString(R.string.pc_status_ok));
                }
                return new ProbeResult("OK_UNKNOWN", getString(R.string.pc_status_ok_unknown));
            }
            return new ProbeResult("HTTP", getString(R.string.pc_status_http, code));
        } catch (UnknownHostException e) {
            return new ProbeResult("HOST", getString(R.string.pc_status_host));
        } catch (SocketTimeoutException e) {
            return new ProbeResult("TIMEOUT", getString(R.string.pc_status_timeout));
        } catch (ConnectException e) {
            return new ProbeResult("REFUSED", getString(R.string.pc_status_refused));
        } catch (SSLException e) {
            return new ProbeResult("SSL", getString(R.string.pc_status_ssl));
        } catch (IOException e) {
            return new ProbeResult("ERR", getString(R.string.pc_status_err, String.valueOf(e.getMessage())));
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 把 API Key 写入 $DSH_HOME/.env（DSH 的 .env 后备层会读取 DEEPSEEK_API_KEY） */
    private void writeApiKeyEnv(String apiKey) {
        File home = DshServerService.dshHomeDir(this);
        if (!home.exists()) home.mkdirs();
        File env = new File(home, ".env");
        try {
            String content = apiKey.isEmpty()
                    ? ""
                    : "# written by DSH Mobile settings\nDEEPSEEK_API_KEY=" + apiKey + "\n";
            try (FileOutputStream fos = new FileOutputStream(env)) {
                fos.write(content.getBytes(StandardCharsets.UTF_8));
            }
            // DSH 对凭据类文件要求仅属主可读写（mode 600）：
            // 必须先全关再开属主位（setReadable(true,true) 单独用清不掉 group/other 已有读位）
            env.setReadable(false, false);
            env.setReadable(true, true);
            env.setWritable(false, false);
            env.setWritable(true, true);
            env.setExecutable(false, false);
        } catch (Exception e) {
            Toast.makeText(this, "写入 .env 失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void restartServer() {
        if (!localChecked()) return;
        // 解压期间不允许重启：解压线程还在写运行时树（服务端也会忽略该命令）
        if (DshServerService.isExtractingNow()
                || DshServerService.payloadState(this).startsWith(DshServerService.STATE_EXTRACTING)) {
            statusText.setText(R.string.status_extracting);
            return;
        }
        // 单条 RESTART 命令原子重启（服务内部杀旧 node 再拉新），
        // 避免 STOP 后立刻 startForegroundService 的竞态导致系统杀进程
        Intent restart = new Intent(this, DshServerService.class);
        restart.setAction(DshServerService.ACTION_RESTART);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(restart);
        } else {
            startService(restart);
        }
        statusText.setText(R.string.status_starting);
    }
}
