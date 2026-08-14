package com.dsh.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 收纳箱（v2.5）：把智能体产物按「工作台产物 / 会话记录」分类列出，
 * 每个文件可以「提取」（复制到手机 Download/dsh-export/，文件管理器可见）
 * 或「删除」。数据都在应用私有目录里，普通文件管理器看不到，这里提供唯一入口。
 */
public class ArtifactsActivity extends Activity {

    private static final int REQ_STORAGE = 41;

    private LinearLayout container;
    private File pendingExtract;
    private String pendingRel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_artifacts);
        container = findViewById(R.id.artifacts_container);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    // ---------- 列表构建 ----------

    private void refresh() {
        container.removeAllViews();
        File workspace = new File(getFilesDir(), "workspace");
        File sessions = new File(DshServerService.dshHomeDir(this), "sessions");
        int count = 0;

        // 分类 1：工作台产物
        addCategoryHeader(getString(R.string.cat_workspace), workspace, "workspace");
        List<File> wsFiles = listFiles(workspace);
        if (wsFiles.isEmpty()) {
            addEmpty(getString(R.string.artifacts_empty));
        } else {
            for (File f : wsFiles) {
                addRow(f, "workspace/" + f.getName());
                count++;
            }
        }

        // 分类 2：会话记录（workspace 目录 → session-xxx → 文件）
        addCategoryHeader(getString(R.string.cat_sessions), null, null);
        int sessionCount = 0;
        File[] wsDirs = sessions.listFiles();
        if (wsDirs != null) {
            for (File wsDir : wsDirs) {
                if (!wsDir.isDirectory()) continue;
                File[] sessionDirs = wsDir.listFiles();
                if (sessionDirs == null) continue;
                for (File sd : sessionDirs) {
                    if (!sd.isDirectory()) continue;
                    List<File> sfiles = listFiles(sd);
                    if (sfiles.isEmpty()) continue;
                    sessionCount++;
                    addSessionHeader(sd, sfiles, wsDir.getName());
                    for (File f : sfiles) {
                        String rel = "sessions/" + wsDir.getName() + "/" + sd.getName() + "/" + f.getName();
                        addRow(f, rel);
                        count++;
                    }
                }
            }
        }
        if (sessionCount == 0) addEmpty(getString(R.string.artifacts_empty));

        TextView stat = findViewById(R.id.artifacts_stat);
        stat.setText(getString(R.string.artifacts_stat, count));
    }

    private List<File> listFiles(File dir) {
        List<File> out = new ArrayList<>();
        File[] files = dir.listFiles();
        if (files == null) return out;
        for (File f : files) {
            if (f.isFile()) out.add(f);
        }
        Collections.sort(out, new Comparator<File>() {
            @Override public int compare(File a, File b) {
                return Long.compare(b.lastModified(), a.lastModified());
            }
        });
        return out;
    }

    private void addCategoryHeader(String text, File root, String relPrefix) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(15);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(18);
        lp.bottomMargin = dp(6);
        tv.setLayoutParams(lp);
        container.addView(tv);
        if (root != null) {
            Button all = new Button(this);
            all.setText(R.string.btn_extract_all);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            blp.bottomMargin = dp(6);
            all.setLayoutParams(blp);
            final String prefix = relPrefix;
            all.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    extractTree(root, prefix);
                }
            });
            container.addView(all);
        }
    }

    private void addSessionHeader(final File sessionDir, List<File> files, String wsName) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(6), 0, dp(2));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        left.setLayoutParams(llp);

        TextView name = new TextView(this);
        name.setText("会话 " + shortId(sessionDir.getName()));
        name.setTextSize(13);
        name.setTypeface(null, android.graphics.Typeface.BOLD);
        left.addView(name);

        TextView meta = new TextView(this);
        long total = 0;
        for (File f : files) total += f.length();
        meta.setText(fmtDate(sessionDir.lastModified()) + " · " + files.size() + " 个文件 · " + fmtSize(total));
        meta.setTextSize(11);
        meta.setTextColor(0xFF888888);
        left.addView(meta);

        row.addView(left);

        final String prefix = "sessions/" + wsName + "/" + sessionDir.getName();
        row.addView(makeButton(R.string.btn_extract, new View.OnClickListener() {
            @Override public void onClick(View v) { extractTree(sessionDir, prefix); }
        }));
        row.addView(makeButton(R.string.btn_delete, new View.OnClickListener() {
            @Override public void onClick(View v) { confirmDelete(sessionDir, "会话 " + shortId(sessionDir.getName()), true); }
        }));
        container.addView(row);
        addDivider();
    }

    private void addRow(final File f, final String rel) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(16), dp(6), 0, dp(6));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        left.setLayoutParams(llp);

        TextView name = new TextView(this);
        name.setText(f.getName());
        name.setTextSize(13);
        left.addView(name);

        TextView meta = new TextView(this);
        meta.setText(fmtDate(f.lastModified()) + " · " + fmtSize(f.length()));
        meta.setTextSize(11);
        meta.setTextColor(0xFF888888);
        left.addView(meta);

        row.addView(left);
        row.addView(makeButton(R.string.btn_extract, new View.OnClickListener() {
            @Override public void onClick(View v) { extractOne(f, rel); }
        }));
        row.addView(makeButton(R.string.btn_delete, new View.OnClickListener() {
            @Override public void onClick(View v) { confirmDelete(f, f.getName(), false); }
        }));
        container.addView(row);
        addDivider();
    }

    private Button makeButton(int textRes, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(textRes);
        b.setTextSize(12);
        b.setMinWidth(0);
        b.setMinHeight(0);
        b.setPadding(dp(10), dp(2), dp(10), dp(2));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.leftMargin = dp(8);
        b.setLayoutParams(blp);
        b.setOnClickListener(listener);
        return b;
    }

    private void addEmpty(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setTextColor(0xFF888888);
        tv.setPadding(dp(16), 0, 0, dp(8));
        container.addView(tv);
    }

    private void addDivider() {
        View v = new View(this);
        v.setBackgroundColor(0x22000000);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1);
        v.setLayoutParams(lp);
        container.addView(v);
    }

    // ---------- 提取 ----------

    private void extractOne(File src, String rel) {
        if (!hasStoragePermission()) {
            pendingExtract = src;
            pendingRel = rel;
            if (Build.VERSION.SDK_INT >= 23) {
                requestPermissions(new String[]{"android.permission.WRITE_EXTERNAL_STORAGE"}, REQ_STORAGE);
            }
            return;
        }
        doExtract(src, rel);
    }

    private void extractTree(File root, String relPrefix) {
        if (!hasStoragePermission()) {
            Toast.makeText(this, R.string.perm_storage, Toast.LENGTH_SHORT).show();
            if (Build.VERSION.SDK_INT >= 23) {
                requestPermissions(new String[]{"android.permission.WRITE_EXTERNAL_STORAGE"}, REQ_STORAGE);
            }
            return;
        }
        List<File> files = listFiles(root);
        int ok = 0;
        for (File f : files) {
            if (doExtract(f, relPrefix + "/" + f.getName())) ok++;
        }
        Toast.makeText(this, getString(R.string.extract_all_ok, ok, files.size()),
                Toast.LENGTH_LONG).show();
    }

    private boolean hasStoragePermission() {
        return Build.VERSION.SDK_INT < 23
                || checkSelfPermission("android.permission.WRITE_EXTERNAL_STORAGE")
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean doExtract(File src, String rel) {
        // 首选直接写 Download/dsh-export（targetSdk 28 走 legacy 存储模型）
        File out = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), "dsh-export/" + rel);
        try {
            File parent = out.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            copyStream(new FileInputStream(src), new FileOutputStream(out));
            Toast.makeText(this, getString(R.string.extract_ok, rel), Toast.LENGTH_SHORT).show();
            return true;
        } catch (Exception e1) {
            // 兜底：MediaStore Downloads 集合（Android 10+ 的 scoped storage 也兼容）
            if (Build.VERSION.SDK_INT >= 29) {
                try {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.MediaColumns.DISPLAY_NAME, out.getName());
                    cv.put(MediaStore.MediaColumns.RELATIVE_PATH,
                            Environment.DIRECTORY_DOWNLOADS + "/dsh-export");
                    cv.put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream");
                    Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                    if (uri != null) {
                        try (InputStream in = new FileInputStream(src);
                             OutputStream os = getContentResolver().openOutputStream(uri)) {
                            copyStream(in, os);
                        }
                        Toast.makeText(this, getString(R.string.extract_ok, rel),
                                Toast.LENGTH_SHORT).show();
                        return true;
                    }
                } catch (Exception e2) {
                    Toast.makeText(this, getString(R.string.extract_fail, e2.getMessage()),
                            Toast.LENGTH_LONG).show();
                    return false;
                }
            }
            Toast.makeText(this, getString(R.string.extract_fail, e1.getMessage()),
                    Toast.LENGTH_LONG).show();
            return false;
        }
    }

    private void copyStream(InputStream in, OutputStream out) throws Exception {
        try (InputStream i = in; OutputStream o = out) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = i.read(buf)) > 0) o.write(buf, 0, n);
            o.flush();
        }
    }

    // ---------- 删除 ----------

    private void confirmDelete(final File target, final String displayName, final boolean recursive) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.dlg_delete)
                .setMessage(getString(R.string.delete_confirm, displayName))
                .setPositiveButton(R.string.dlg_delete, (dialog, which) -> {
                    boolean ok = recursive ? deleteRecursive(target) : target.delete();
                    Toast.makeText(this, ok ? R.string.delete_ok : R.string.delete_fail,
                            Toast.LENGTH_SHORT).show();
                    refresh();
                })
                .setNegativeButton(R.string.dlg_cancel, null)
                .show();
    }

    private boolean deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) for (File c : children) deleteRecursive(c);
        }
        return f.delete();
    }

    // ---------- 工具 ----------

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_STORAGE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (pendingExtract != null) {
                    doExtract(pendingExtract, pendingRel);
                    pendingExtract = null;
                    pendingRel = null;
                }
            } else {
                Toast.makeText(this, R.string.perm_storage, Toast.LENGTH_LONG).show();
            }
        }
    }

    private static String shortId(String name) {
        int dash = name.lastIndexOf('-');
        return name.substring(Math.max(0, dash + 1), Math.min(name.length(), dash + 9));
    }

    private static String fmtSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f MB", bytes / 1048576.0);
        return String.format(Locale.US, "%.2f GB", bytes / 1073741824.0);
    }

    private static String fmtDate(long ts) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date(ts));
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
