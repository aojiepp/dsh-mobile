package com.dsh.mobile;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
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

import javax.net.ssl.SSLException;

/** 设置页：运行模式（本机内置服务 / PC 服务器）、连接检测、API Key、服务状态与重启 */
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
    private Button btnTest;
    private SharedPreferences prefs;

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
        btnTest = findViewById(R.id.btn_test);

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
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private boolean localChecked() { return radioLocal.isChecked(); }

    /** 按模式切换界面：本机模式显示重启/API Key，连接电脑模式显示检测按钮与指引 */
    private void toggleModeUi() {
        boolean local = localChecked();
        btnRestart.setVisibility(local ? View.VISIBLE : View.GONE);
        apiKeyInput.setEnabled(local);
        apiKeyHint.setEnabled(local);
        btnTest.setVisibility(local ? View.GONE : View.VISIBLE);
        pcStatus.setVisibility(local ? View.GONE : View.VISIBLE);
        hintConn.setVisibility(local ? View.GONE : View.VISIBLE);
        refreshStatus();
    }

    private void refreshStatus() {
        if (!localChecked()) {
            statusText.setText(R.string.status_pc_note);
            return;
        }
        btnRestart.setEnabled(true);
        String st = DshServerService.serverState(this);
        String pt = DshServerService.payloadState(this);
        if (pt.startsWith(DshServerService.STATE_EXTRACTING)) {
            String pct = pt.length() > pt.indexOf(':') + 1 ? pt.substring(pt.indexOf(':') + 1) : "";
            statusText.setText(getString(R.string.status_extracting) + " " + pct + "%");
        } else if (pt.startsWith(DshServerService.STATE_FAILED)) {
            statusText.setText(getString(R.string.status_failed));
        } else if (st == null || st.isEmpty()) {
            statusText.setText(DshServerService.isPayloadReady(this)
                    ? R.string.status_ready : R.string.status_extracting);
        } else if (st.equals(DshServerService.STATE_RUNNING)) {
            statusText.setText(R.string.status_running);
        } else if (st.startsWith(DshServerService.STATE_EXITED)) {
            statusText.setText(getString(R.string.status_exited) + " (" + st + ")");
        } else if (st.equals("stopped")) {
            statusText.setText(R.string.status_stopped);
        } else if (st.startsWith(DshServerService.STATE_FAILED)) {
            statusText.setText(getString(R.string.status_failed) + "\n" + st);
        } else {
            statusText.setText(getString(R.string.status_starting));
        }
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
            // DSH 对凭据类文件要求仅属主可读写（mode 600）
            env.setReadable(true, true);
            env.setWritable(true, true);
            env.setExecutable(false, false);
        } catch (Exception e) {
            Toast.makeText(this, "写入 .env 失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void restartServer() {
        if (!localChecked()) return;
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
