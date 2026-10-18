package com.dsh.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 收纳箱（v2.11）：应用私有目录里 agent 产物的唯一出口。
 *
 * <p>两个根：{@code files/workspace} 与 {@code files/dsh-home/sessions}，
 * 递归扫描（深度上限 6 层），按「目录」分组平铺列出**每一个文件**（旧版只看顶层，
 * 嵌套产物完全不可见）。每行：勾选框 + 文件名 + 相对路径/大小/修改时间。</p>
 *
 * <p>能进也能出：提取（目录/多选自动打 zip）、删除、全部导出（整个 workspace 一个 zip）、
 * 导入（SAF 多选 → files/workspace/imported/&lt;时间戳&gt;/）、分享（先复制到
 * MediaStore.Downloads 拿 content Uri，再 ACTION_SEND）。
 * 无 AndroidX，故不用 FileProvider。</p>
 */
public class ArtifactsActivity extends Activity {

    private static final int REQ_STORAGE = 41;
    private static final int REQ_IMPORT = 42;

    /** 递归深度上限（相对每个根） */
    private static final int MAX_DEPTH = 6;
    /** 列表一次最多渲染的行数（防超大目录把主线程拖死）；仍计入总数 */
    private static final int MAX_ROWS = 600;
    /** 一次分享的文件数上限 */
    private static final int MAX_SHARE = 50;
    /** 单文件提取前的确认阈值 */
    private static final long BIG_FILE_BYTES = 200L * 1024L * 1024L;

    private static final String EXPORT_SUBDIR = "dsh-export";
    private static final String WORKSPACE_PREFIX = "workspace/";

    private static final Comparator<File> BY_MTIME_DESC = new Comparator<File>() {
        @Override public int compare(File a, File b) {
            return Long.compare(b.lastModified(), a.lastModified());
        }
    };
    private static final Comparator<File> BY_NAME_ASC = new Comparator<File>() {
        @Override public int compare(File a, File b) {
            return a.getName().compareToIgnoreCase(b.getName());
        }
    };

    /** 一个可勾选的条目 */
    private static class Item {
        final File file;
        /** 展示与 zip 内路径统一用这个相对路径，如 workspace/notes/a.md */
        final String rel;
        final long mtime;
        CheckBox box; // 未渲染（超出行数上限）时为 null

        Item(File file, String rel) {
            this.file = file;
            this.rel = rel;
            this.mtime = file.lastModified();
        }
    }

    /** 目录分组（每个有文件的目录一节） */
    private static class Section {
        final String rootLabel;
        final String rootRel;
        final String dirRel;
        final List<Item> items = new ArrayList<>();

        Section(String rootLabel, String rootRel, String dirRel) {
            this.rootLabel = rootLabel;
            this.rootRel = rootRel;
            this.dirRel = dirRel;
        }
    }

    /** 后台任务：返回给用户的提示（null = 不提示），抛异常 = 失败 */
    private interface Task {
        String run() throws Exception;
    }

    private LinearLayout container;
    private TextView statView;
    private TextView statusView;

    private final List<Section> sections = new ArrayList<>();
    private final List<Item> items = new ArrayList<>();
    private boolean depthTruncated;
    private boolean busy;
    private int lastZipSkipped;

    private Runnable pendingPermAction;
    private List<Uri> pendingShareUris;
    private String pendingShareMime = "application/octet-stream";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_artifacts);
        container = findViewById(R.id.artifacts_container);
        statView = findViewById(R.id.artifacts_stat);
        statusView = findViewById(R.id.artifacts_status);

        findViewById(R.id.btn_select_all).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setAllChecked(true); }
        });
        findViewById(R.id.btn_select_none).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setAllChecked(false); }
        });
        findViewById(R.id.btn_extract_sel).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onExtractSelected(); }
        });
        findViewById(R.id.btn_delete_sel).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onDeleteSelected(); }
        });
        findViewById(R.id.btn_export_ws).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onExportWorkspace(); }
        });
        findViewById(R.id.btn_import).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startImport(); }
        });
        findViewById(R.id.btn_share_sel).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onShareSelected(); }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    // ---------- 扫描 / 列表构建 ----------

    private void refresh() {
        sections.clear();
        items.clear();
        depthTruncated = false;
        container.removeAllViews();

        File workspace = new File(getFilesDir(), "workspace");
        File sessions = new File(DshServerService.dshHomeDir(this), "sessions");
        walk(workspace, "workspace", getString(R.string.cat_workspace), "workspace", 0);
        walk(sessions, "sessions", getString(R.string.cat_sessions), "sessions", 0);

        render();
        updateStat();
    }

    /** 递归收集：每个「有文件的目录」成为一节 */
    private void walk(File dir, String rel, String rootLabel, String rootRel, int depth) {
        if (depth > MAX_DEPTH) {
            depthTruncated = true;
            return;
        }
        File[] kids = dir.listFiles();
        if (kids == null) return;
        List<File> dirs = new ArrayList<>();
        List<File> files = new ArrayList<>();
        for (File k : kids) {
            if (k.isDirectory()) dirs.add(k);
            else if (k.isFile()) files.add(k);
        }
        if (!files.isEmpty()) {
            Collections.sort(files, BY_MTIME_DESC);
            Section s = new Section(rootLabel, rootRel, rel);
            for (File f : files) {
                Item it = new Item(f, rel + "/" + f.getName());
                s.items.add(it);
                items.add(it);
            }
            sections.add(s);
        }
        Collections.sort(dirs, BY_NAME_ASC);
        for (File d : dirs) walk(d, rel + "/" + d.getName(), rootLabel, rootRel, depth + 1);
    }

    private void render() {
        container.removeAllViews();
        if (items.isEmpty()) {
            addEmpty(getString(R.string.artifacts_empty));
            return;
        }
        String lastLabel = null;
        int rendered = 0;
        for (Section s : sections) {
            if (rendered >= MAX_ROWS) break;
            if (!s.rootLabel.equals(lastLabel)) {
                addRootHeader(s.rootLabel, rootSection(s.rootRel));
                lastLabel = s.rootLabel;
            }
            if (!s.dirRel.equals(s.rootRel)) addSectionHeader(s);
            for (Item it : s.items) {
                if (rendered >= MAX_ROWS) break;
                addRow(it);
                rendered++;
            }
        }
        if (depthTruncated) addNote(getString(R.string.artifacts_depth_note));
        if (rendered >= MAX_ROWS && items.size() > rendered) {
            addNote(getString(R.string.artifacts_trunc_note, rendered));
        }
    }

    private Section rootSection(String rootRel) {
        for (Section s : sections) {
            if (s.dirRel.equals(rootRel)) return s;
        }
        return null;
    }

    private void addRootHeader(String label, final Section rootSec) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(14);
        lp.bottomMargin = dp(8);
        row.setLayoutParams(lp);

        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(15);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setTextColor(getColor(R.color.text_primary));
        tv.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(tv);

        if (rootSec != null) {
            row.addView(makeButton(R.string.btn_zip, R.style.DshButtonSecondary,
                    new View.OnClickListener() {
                        @Override public void onClick(View v) { onZipSection(rootSec); }
                    }));
        }
        container.addView(row);
    }

    private void addSectionHeader(final Section s) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        lp.bottomMargin = dp(6);
        row.setLayoutParams(lp);

        TextView tv = new TextView(this);
        tv.setText(s.dirRel + "/");
        tv.setTextSize(12);
        tv.setSingleLine(true);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tv.setTextColor(getColor(R.color.text_secondary));
        tv.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(tv);
        row.addView(makeButton(R.string.btn_zip, R.style.DshButtonSecondary,
                new View.OnClickListener() {
                    @Override public void onClick(View v) { onZipSection(s); }
                }));
        container.addView(row);
    }

    private void addRow(final Item it) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(8), dp(10), dp(8));
        row.setBackgroundResource(R.drawable.bg_card);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = dp(6);
        row.setLayoutParams(rlp);

        final CheckBox cb = new CheckBox(this);
        cb.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { updateStat(); }
        });
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.rightMargin = dp(2);
        row.addView(cb, clp);
        it.box = cb;

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView name = new TextView(this);
        name.setText(it.file.getName());
        name.setTextSize(13);
        name.setTextColor(getColor(R.color.text_primary));
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        left.addView(name);

        TextView meta = new TextView(this);
        meta.setText(it.rel + " · " + fmtSize(it.file.length()) + " · " + fmtDate(it.mtime));
        meta.setTextSize(11);
        meta.setTextColor(getColor(R.color.text_secondary));
        meta.setSingleLine(true);
        meta.setEllipsize(android.text.TextUtils.TruncateAt.END);
        left.addView(meta);

        row.addView(left);
        // 点行任意空白处也能勾选（勾选框本身自己处理点击）
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                cb.setChecked(!cb.isChecked());
                updateStat();
            }
        });
        container.addView(row);
    }

    private Button makeButton(int textRes, int styleRes, View.OnClickListener listener) {
        Button b = new Button(new android.view.ContextThemeWrapper(this, styleRes));
        b.setText(textRes);
        b.setTextSize(12);
        b.setMinWidth(0);
        b.setMinHeight(0);
        b.setPadding(dp(12), dp(4), dp(12), dp(4));
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
        tv.setTextColor(getColor(R.color.text_secondary));
        tv.setPadding(dp(4), dp(4), 0, dp(8));
        container.addView(tv);
    }

    private void addNote(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(11);
        tv.setTextColor(getColor(R.color.text_secondary));
        tv.setPadding(dp(4), dp(8), 0, 0);
        container.addView(tv);
    }

    private void setAllChecked(boolean v) {
        for (Item it : items) {
            if (it.box != null) it.box.setChecked(v);
        }
        updateStat();
    }

    private List<Item> selected() {
        List<Item> out = new ArrayList<>();
        for (Item it : items) {
            if (it.box != null && it.box.isChecked()) out.add(it);
        }
        return out;
    }

    private void updateStat() {
        if (statView == null) return;
        statView.setText(getString(R.string.artifacts_stat_sel, items.size(), selected().size()));
    }

    // ---------- 提取（单文件直出 / 多选与目录打 zip） ----------

    private void onExtractSelected() {
        final List<Item> sel = selected();
        if (sel.isEmpty()) {
            toast(R.string.toast_none_sel);
            return;
        }
        ensureStorage(new Runnable() {
            @Override public void run() {
                if (sel.size() == 1) {
                    final Item only = sel.get(0);
                    if (only.file.length() > BIG_FILE_BYTES) {
                        new AlertDialog.Builder(ArtifactsActivity.this)
                                .setTitle(R.string.extract_big_title)
                                .setMessage(getString(R.string.extract_big_msg,
                                        only.file.getName(), fmtSize(only.file.length())))
                                .setPositiveButton(R.string.dlg_continue,
                                        (dialog, which) -> extractItems(sel))
                                .setNegativeButton(R.string.dlg_cancel, null)
                                .show();
                        return;
                    }
                }
                extractItems(sel);
            }
        });
    }

    /**
     * 1 个文件 → 按相对路径直出；多个 → 打一个 zip（zip 内用相对路径）。
     */
    private void extractItems(final List<Item> sel) {
        lastZipSkipped = 0;
        runTask(getString(R.string.extract_progress), new Task() {
            @Override public String run() throws Exception {
                if (sel.size() == 1) {
                    Item it = sel.get(0);
                    publishToDownloads(it.file, it.rel);
                    return getString(R.string.extract_ok, it.rel);
                }
                String zipName = "bundle-" + stamp() + ".zip";
                File zip = zipItems(sel, false);
                try {
                    publishToDownloads(zip, zipName);
                } finally {
                    deleteQuietly(zip);
                }
                return getString(R.string.extract_zip_ok, sel.size(), zipName) + skippedNote();
            }
        }, null);
    }

    /** 某一节（目录）整包提取 */
    private void onZipSection(final Section s) {
        if (s.items.isEmpty()) {
            toast(R.string.export_none);
            return;
        }
        ensureStorage(new Runnable() {
            @Override public void run() {
                lastZipSkipped = 0;
                runTask(getString(R.string.extract_progress), new Task() {
                    @Override public String run() throws Exception {
                        String zipName = safeName(lastSegment(s.dirRel))
                                + "-" + stamp() + ".zip";
                        File zip = zipItems(s.items, false);
                        try {
                            publishToDownloads(zip, zipName);
                        } finally {
                            deleteQuietly(zip);
                        }
                        return getString(R.string.extract_zip_ok, s.items.size(), zipName)
                                + skippedNote();
                    }
                }, null);
            }
        });
    }

    /** 整个 workspace 打成一个 zip（zip 内路径相对 workspace 根） */
    private void onExportWorkspace() {
        final List<Item> ws = new ArrayList<>();
        for (Item it : items) {
            if (it.rel.startsWith(WORKSPACE_PREFIX)) ws.add(it);
        }
        if (ws.isEmpty()) {
            toast(R.string.export_none);
            return;
        }
        ensureStorage(new Runnable() {
            @Override public void run() {
                lastZipSkipped = 0;
                runTask(getString(R.string.export_progress), new Task() {
                    @Override public String run() throws Exception {
                        String zipName = "workspace-" + stamp() + ".zip";
                        File zip = zipItems(ws, true);
                        try {
                            publishToDownloads(zip, zipName);
                        } finally {
                            deleteQuietly(zip);
                        }
                        return getString(R.string.export_ws_ok, zipName) + skippedNote();
                    }
                }, null);
            }
        });
    }

    private String skippedNote() {
        return lastZipSkipped > 0 ? " " + getString(R.string.zip_skipped, lastZipSkipped) : "";
    }

    /** 打临时 zip（缓存目录），DEFLATED level 1：速度优先 */
    private File zipItems(List<Item> sel, boolean stripWorkspacePrefix) throws IOException {
        File tmp = new File(getCacheDir(), "dsh-zip-" + System.currentTimeMillis() + ".zip");
        ZipOutputStream zos = null;
        byte[] buf = new byte[128 * 1024];
        try {
            OutputStream fos = new BufferedOutputStream(new FileOutputStream(tmp), 128 * 1024);
            zos = new ZipOutputStream(fos);
            zos.setLevel(Deflater.BEST_SPEED); // = 1
            for (Item it : sel) {
                String entry = it.rel;
                if (stripWorkspacePrefix && entry.startsWith(WORKSPACE_PREFIX)) {
                    entry = entry.substring(WORKSPACE_PREFIX.length());
                }
                if (entry.isEmpty()) continue;
                FileInputStream fis;
                try {
                    fis = new FileInputStream(it.file);
                } catch (IOException e) {
                    lastZipSkipped++;
                    continue;
                }
                try {
                    ZipEntry ze = new ZipEntry(entry);
                    ze.setTime(it.mtime);
                    zos.putNextEntry(ze);
                    int n;
                    while ((n = fis.read(buf)) > 0) zos.write(buf, 0, n);
                    zos.closeEntry();
                } finally {
                    closeQuietly(fis);
                }
            }
        } catch (IOException e) {
            deleteQuietly(tmp);
            throw e;
        } finally {
            closeQuietly(zos);
        }
        return tmp;
    }

    // ---------- 落盘（Download/dsh-export，MediaStore 兜底） ----------

    private File exportBase() {
        return new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), EXPORT_SUBDIR);
    }

    /** 复制到 Download/dsh-export/&lt;rel&gt;；旧存储模型失败时退回 MediaStore */
    private void publishToDownloads(File src, String rel) throws Exception {
        File out = new File(exportBase(), rel);
        Exception direct = null;
        try {
            copyFile(src, out);
            return;
        } catch (Exception e) {
            direct = e;
        }
        if (Build.VERSION.SDK_INT >= 29) {
            if (insertMediaStore(src, new File(rel).getName(), mimeOf(rel)) != null) return;
        }
        throw direct;
    }

    /** 插入 MediaStore 并写入内容，返回 content Uri（分享用；API 26-28 走 Files 表） */
    private Uri insertMediaStore(File src, String displayName, String mime) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
            cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            cv.put(MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/" + EXPORT_SUBDIR);
            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) throw new IOException("insert MediaStore.Downloads failed");
            OutputStream os = getContentResolver().openOutputStream(uri);
            if (os == null) throw new IOException("openOutputStream returned null");
            try {
                InputStream in = new FileInputStream(src);
                try {
                    copyStream(in, os);
                } finally {
                    closeQuietly(in);
                }
            } finally {
                closeQuietly(os);
            }
            return uri;
        }
        // API 26-28：没有 Downloads 集合，走 legacy 路径 + Files 表登记
        File dst = new File(exportBase(), displayName);
        copyFile(src, dst);
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.MediaColumns.DATA, dst.getAbsolutePath());
        cv.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
        cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        return getContentResolver().insert(MediaStore.Files.getContentUri("external"), cv);
    }

    private static void copyFile(File src, File dst) throws IOException {
        File parent = dst.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("mkdir failed: " + parent);
        }
        InputStream in = new FileInputStream(src);
        try {
            OutputStream out = new FileOutputStream(dst);
            try {
                copyStream(in, out);
            } finally {
                closeQuietly(out);
            }
        } finally {
            closeQuietly(in);
        }
    }

    private static void copyFile(InputStream in, File dst) throws IOException {
        File parent = dst.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("mkdir failed: " + parent);
        }
        OutputStream out = new FileOutputStream(dst);
        try {
            copyStream(in, out);
        } finally {
            closeQuietly(out);
        }
    }

    private static void copyStream(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[128 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        out.flush();
    }

    // ---------- 删除 ----------

    private void onDeleteSelected() {
        final List<Item> sel = selected();
        if (sel.isEmpty()) {
            toast(R.string.toast_none_sel);
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.dlg_delete)
                .setMessage(getString(R.string.delete_sel_msg, sel.size()))
                .setPositiveButton(R.string.dlg_delete, (dialog, which) -> {
                    runTask(null, new Task() {
                        @Override public String run() {
                            int ok = 0;
                            for (Item it : sel) {
                                if (deleteRecursive(it.file)) ok++;
                            }
                            return getString(R.string.delete_sel_ok, ok);
                        }
                    }, new Runnable() {
                        @Override public void run() { refresh(); }
                    });
                })
                .setNegativeButton(R.string.dlg_cancel, null)
                .show();
    }

    private static boolean deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) for (File c : children) deleteRecursive(c);
        }
        return f.delete();
    }

    // ---------- 导入（SAF 多选） ----------

    private void startImport() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(i, REQ_IMPORT);
        } catch (Exception e) {
            toast(getString(R.string.import_pick_fail, describe(e)));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_IMPORT) return;
        final List<Uri> uris = new ArrayList<>();
        if (resultCode == RESULT_OK && data != null) {
            ClipData clip = data.getClipData();
            if (clip != null) {
                for (int i = 0; i < clip.getItemCount(); i++) {
                    Uri u = clip.getItemAt(i).getUri();
                    if (u != null) uris.add(u);
                }
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
        }
        if (uris.isEmpty()) {
            toast(R.string.import_none);
            return;
        }
        final String ts = stamp();
        final String relDir = "workspace/imported/" + ts;
        runTask(getString(R.string.import_progress), new Task() {
            @Override public String run() throws Exception {
                File dir = new File(getFilesDir(), relDir);
                if (!dir.exists() && !dir.mkdirs()) {
                    throw new IOException("mkdir failed: " + dir);
                }
                int ok = 0;
                String lastErr = null;
                for (Uri u : uris) {
                    try {
                        copyUriTo(u, dir);
                        ok++;
                    } catch (Exception e) {
                        lastErr = describe(e);
                    }
                }
                if (ok == 0) throw new IOException(lastErr == null ? "copy failed" : lastErr);
                return getString(R.string.import_done, ok, relDir + "/");
            }
        }, new Runnable() {
            @Override public void run() { refresh(); }
        });
    }

    private File copyUriTo(Uri uri, File dir) throws IOException {
        String name = safeName(displayName(uri));
        File out = new File(dir, name);
        int i = 1;
        while (out.exists() && i < 1000) {
            out = new File(dir, numbered(name, i));
            i++;
        }
        InputStream in = getContentResolver().openInputStream(uri);
        if (in == null) throw new IOException("openInputStream returned null");
        try {
            copyFile(in, out);
        } finally {
            closeQuietly(in);
        }
        return out;
    }

    private String displayName(Uri uri) {
        String name = null;
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME},
                    null, null, null);
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) name = c.getString(idx);
            }
        } catch (Exception ignored) {
        } finally {
            closeQuietly(c);
        }
        if (name == null || name.isEmpty()) {
            String last = uri.getLastPathSegment();
            name = (last == null || last.isEmpty()) ? "imported-file" : last;
        }
        return name;
    }

    // ---------- 分享（MediaStore content Uri + FLAG_GRANT_READ_URI_PERMISSION） ----------

    private void onShareSelected() {
        final List<Item> sel = selected();
        if (sel.isEmpty()) {
            toast(R.string.toast_none_sel);
            return;
        }
        if (sel.size() > MAX_SHARE) {
            toast(getString(R.string.share_limit, MAX_SHARE));
            return;
        }
        if (Build.VERSION.SDK_INT < 29) {
            // 旧系统写 Download 需要存储权限
            ensureStorage(new Runnable() {
                @Override public void run() { doShare(sel); }
            });
        } else {
            doShare(sel);
        }
    }

    private void doShare(final List<Item> sel) {
        runTask(getString(R.string.share_progress), new Task() {
            @Override public String run() throws Exception {
                List<Uri> uris = new ArrayList<>();
                String firstMime = null;
                boolean mixed = false;
                for (Item it : sel) {
                    String mime = mimeOf(it.file.getName());
                    if (firstMime == null) firstMime = mime;
                    else if (!firstMime.equals(mime)) mixed = true;
                    Uri u = insertMediaStore(it.file, it.file.getName(), mime);
                    if (u != null) uris.add(u);
                }
                if (uris.isEmpty()) throw new IOException(getString(R.string.share_none_uri));
                pendingShareUris = uris;
                pendingShareMime = mixed ? "*/*"
                        : (firstMime == null ? "application/octet-stream" : firstMime);
                return null;
            }
        }, new Runnable() {
            @Override public void run() { launchShareChooser(); }
        });
    }

    private void launchShareChooser() {
        List<Uri> uris = pendingShareUris;
        pendingShareUris = null;
        if (uris == null || uris.isEmpty()) {
            toast(R.string.share_none_uri);
            return;
        }
        Intent send = new Intent(uris.size() == 1
                ? Intent.ACTION_SEND : Intent.ACTION_SEND_MULTIPLE);
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        send.setType(pendingShareMime);
        if (uris.size() == 1) {
            send.putExtra(Intent.EXTRA_STREAM, uris.get(0));
        } else {
            send.putParcelableArrayListExtra(Intent.EXTRA_STREAM, new ArrayList<Uri>(uris));
        }
        try {
            startActivity(Intent.createChooser(send, getString(R.string.share_choose)));
        } catch (Exception e) {
            toast(getString(R.string.op_fail, describe(e)));
        }
    }

    // ---------- 后台任务 ----------

    private void runTask(final CharSequence progress, final Task task,
                         final Runnable onSuccessUi) {
        if (busy) {
            toast(R.string.toast_busy);
            return;
        }
        busy = true;
        if (statusView != null && progress != null) {
            statusView.setText(progress);
            statusView.setVisibility(View.VISIBLE);
        }
        new Thread(new Runnable() {
            @Override public void run() {
                String msg = null;
                Exception err = null;
                try {
                    msg = task.run();
                } catch (Exception e) {
                    err = e;
                }
                final String fmsg = msg;
                final Exception ferr = err;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        busy = false;
                        if (statusView != null) statusView.setVisibility(View.GONE);
                        if (isFinishing()) return;
                        if (ferr != null) {
                            Toast.makeText(ArtifactsActivity.this,
                                    getString(R.string.op_fail, describe(ferr)),
                                    Toast.LENGTH_LONG).show();
                            return;
                        }
                        if (onSuccessUi != null) onSuccessUi.run();
                        if (fmsg != null) {
                            Toast.makeText(ArtifactsActivity.this, fmsg, Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        }).start();
    }

    // ---------- 权限 ----------

    private boolean hasStoragePermission() {
        return Build.VERSION.SDK_INT < 23
                || checkSelfPermission("android.permission.WRITE_EXTERNAL_STORAGE")
                == PackageManager.PERMISSION_GRANTED;
    }

    private void ensureStorage(Runnable action) {
        if (hasStoragePermission()) {
            action.run();
            return;
        }
        pendingPermAction = action;
        if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(new String[]{"android.permission.WRITE_EXTERNAL_STORAGE"},
                    REQ_STORAGE);
        } else {
            pendingPermAction = null;
            action.run();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_STORAGE) return;
        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        Runnable action = pendingPermAction;
        pendingPermAction = null;
        if (granted) {
            if (action != null) action.run();
        } else {
            Toast.makeText(this, R.string.perm_storage, Toast.LENGTH_LONG).show();
        }
    }

    // ---------- 工具 ----------

    private void toast(int res) {
        Toast.makeText(this, res, Toast.LENGTH_SHORT).show();
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }

    private static String stamp() {
        return new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
    }

    private static String lastSegment(String rel) {
        if (rel == null) return "export";
        int i = rel.lastIndexOf('/');
        return i < 0 ? rel : rel.substring(i + 1);
    }

    private static String safeName(String n) {
        if (n == null || n.isEmpty()) return "export";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (c == '/' || c == '\\' || c == ':' || c == '*' || c == '?'
                    || c == '"' || c == '<' || c == '>' || c == '|' || c < 0x20) {
                b.append('_');
            } else {
                b.append(c);
            }
        }
        String s = b.toString().trim();
        if (s.isEmpty() || s.equals(".") || s.equals("..")) s = "export";
        if (s.length() > 60) s = s.substring(0, 60);
        return s;
    }

    private static String numbered(String name, int i) {
        int dot = name.lastIndexOf('.');
        if (dot <= 0) return name + "-" + i;
        return name.substring(0, dot) + "-" + i + name.substring(dot);
    }

    private static String mimeOf(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.US);
        int dot = n.lastIndexOf('.');
        String ext = dot < 0 ? "" : n.substring(dot + 1);
        switch (ext) {
            case "md": case "markdown": case "txt": case "log":
                return "text/plain";
            case "json": return "application/json";
            case "jsonl": case "zstd": return "application/octet-stream";
            case "csv": return "text/csv";
            case "html": case "htm": return "text/html";
            case "xml": return "text/xml";
            case "png": return "image/png";
            case "jpg": case "jpeg": return "image/jpeg";
            case "webp": return "image/webp";
            case "gif": return "image/gif";
            case "svg": return "image/svg+xml";
            case "pdf": return "application/pdf";
            case "zip": return "application/zip";
            case "apk": return "application/vnd.android.package-archive";
            case "mp4": case "m4v": return "video/mp4";
            case "mp3": return "audio/mpeg";
            case "wav": return "audio/wav";
            case "doc": return "application/msword";
            case "docx":
                return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xls": return "application/vnd.ms-excel";
            case "xlsx":
                return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "ppt": return "application/vnd.ms-powerpoint";
            case "pptx":
                return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            default: return "application/octet-stream";
        }
    }

    private static String describe(Throwable t) {
        if (t == null) return "unknown";
        String m = t.getMessage();
        return (m == null || m.isEmpty()) ? t.getClass().getSimpleName() : m;
    }

    private static void deleteQuietly(File f) {
        if (f != null) {
            try {
                f.delete();
            } catch (Exception ignored) {
            }
        }
    }

    private static void closeQuietly(Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static String fmtSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) {
            return String.format(Locale.US, "%.1f MB", bytes / 1048576.0);
        }
        return String.format(Locale.US, "%.2f GB", bytes / 1073741824.0);
    }

    private static String fmtDate(long ts) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date(ts));
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
