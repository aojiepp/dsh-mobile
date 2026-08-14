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
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** 设置页：运行模式（本机内置服务 / PC 服务器）、API Key、服务状态与重启 */
public class SettingsActivity extends Activity {

    private RadioButton radioLocal;
    private RadioButton radioPc;
    private EditText urlInput;
    private EditText apiKeyInput;
    private CheckBox keepOn;
    private TextView statusText;
    private TextView apiKeyHint;
    private Button btnRestart;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);

        radioLocal = findViewById(R.id.radio_local);
        radioPc = findViewById(R.id.radio_pc);
        urlInput = findViewById(R.id.url_input);
        apiKeyInput = findViewById(R.id.api_key_input);
        apiKeyHint = findViewById(R.id.api_key_hint);
        keepOn = findViewById(R.id.keep_on);
        statusText = findViewById(R.id.server_status);
        btnRestart = findViewById(R.id.btn_restart);

        boolean local = MainActivity.MODE_LOCAL.equals(
                prefs.getString(MainActivity.KEY_MODE, MainActivity.MODE_LOCAL));
        radioLocal.setChecked(local);
        radioPc.setChecked(!local);

        urlInput.setText(prefs.getString(MainActivity.KEY_URL, MainActivity.DEFAULT_URL));
        apiKeyInput.setText(prefs.getString(MainActivity.KEY_API_KEY, ""));
        keepOn.setChecked(prefs.getBoolean(MainActivity.KEY_KEEP_ON, true));

        findViewById(R.id.btn_save).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { save(); }
        });

        btnRestart.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { restartServer(); }
        });

        refreshStatus();

        TextView version = findViewById(R.id.version);
        version.setText(getString(R.string.app_name) + " v" + MainActivity.VERSION);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private boolean localChecked() { return radioLocal.isChecked(); }

    private void refreshStatus() {
        if (!localChecked()) {
            statusText.setText(R.string.status_no_info);
            btnRestart.setEnabled(false);
            apiKeyInput.setEnabled(false);
            apiKeyHint.setEnabled(false);
            return;
        }
        btnRestart.setEnabled(true);
        apiKeyInput.setEnabled(true);
        apiKeyHint.setEnabled(true);
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
        String url = urlInput.getText().toString().trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "http://" + url;
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
        } else {
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show();
        }
        finish();
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
        if (localChecked() && DshServerService.isPayloadReady(this)) {
            Intent stop = new Intent(this, DshServerService.class);
            stop.setAction(DshServerService.ACTION_STOP);
            startService(stop);
            Intent start = new Intent(this, DshServerService.class);
            start.setAction(DshServerService.ACTION_START);
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(start);
            } else {
                startService(start);
            }
            statusText.setText(R.string.status_starting);
        } else if (localChecked()) {
            Intent start = new Intent(this, DshServerService.class);
            start.setAction(DshServerService.ACTION_START);
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(start);
            } else {
                startService(start);
            }
            statusText.setText(R.string.status_extracting);
        }
    }
}
