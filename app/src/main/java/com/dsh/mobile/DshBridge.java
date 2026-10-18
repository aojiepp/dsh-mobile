package com.dsh.mobile;

import android.app.Activity;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
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
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 原生能力桥（页面侧仍叫 {@code DshShell}，由 MainActivity 用
 * {@code web.addJavascriptInterface(new DshBridge(...), "DshShell")} 挂上）。
 *
 * <p>每个 {@code @JavascriptInterface} 方法都先把 origin 过白名单再执行：
 * 只接受回环地址（127.0.0.1 / localhost / ::1）或与 SharedPreferences
 * {@code dsh_mobile.server_url} 同 host 的页面，其它一律 {@code Log.w} 后丢弃。</p>
 *
 * <p>提供：打开外部浏览器、分享文本、把沙箱内文件存进 Download/dsh-export/、
 * 调起 SAF 多选并把文件收进 {@code files/workspace/imported/<时间戳>/}
 * （完成后通过 {@code window.__dshm_onImport([...绝对路径])} 回调页面）。</p>
 */
public class DshBridge {

    private static final String TAG = "DshShell";
    private static final String EXPORT_SUBDIR = "dsh-export";

    /** MainActivity 需要把这个 requestCode 的 onActivityResult 转给 {@link #onActivityResult} */
    public static final int REQ_PICK_FILE = 43;

    private static final String[] LOOPBACK_HOSTS = {"127.0.0.1", "localhost", "::1", "[::1]"};

    /** JS 可调的打开原生菜单回调（MainActivity 一般传 showControlDialog 的 Runnable） */
    public interface MenuHandler {
        void show();
    }

    private final Activity activity;
    private final WebView web;
    private final MenuHandler menuHandler;

    /** SAF 进行中标志（防重复弹选择器） */
    private boolean picking;

    public DshBridge(Activity activity, WebView web) {
        this(activity, web, null);
    }

    public DshBridge(Activity activity, WebView web, MenuHandler menuHandler) {
        this.activity = activity;
        this.web = web;
        this.menuHandler = menuHandler;
    }

    // ---------- @JavascriptInterface ----------

    /** 打开原生控制菜单（无 MenuHandler 时忽略） */
    @JavascriptInterface
    public void openMenu() {
        post("openMenu", new Runnable() {
            @Override public void run() {
                if (!checkOrigin("openMenu")) return;
                if (menuHandler != null) menuHandler.show();
            }
        });
    }

    /** 仅 http/https，交给外部浏览器 */
    @JavascriptInterface
    public void openUrl(final String url) {
        post("openUrl", new Runnable() {
            @Override public void run() {
                if (!checkOrigin("openUrl")) return;
                doOpenUrl(url);
            }
        });
    }

    /** 分享一段纯文本 */
    @JavascriptInterface
    public void shareText(final String text) {
        post("shareText", new Runnable() {
            @Override public void run() {
                if (!checkOrigin("shareText")) return;
                doShareText(text);
            }
        });
    }

    /** 把沙箱内（files/ 下）的文件复制到 Download/dsh-export/ */
    @JavascriptInterface
    public void saveToDownloads(final String absPath) {
        post("saveToDownloads", new Runnable() {
            @Override public void run() {
                if (!checkOrigin("saveToDownloads")) return;
                if (!isInsideSandbox(absPath)) {
                    Log.w(TAG, "saveToDownloads rejected (outside files dir): " + absPath);
                    return;
                }
                final File src = new File(absPath);
                if (!src.isFile() || !src.canRead()) {
                    Log.w(TAG, "saveToDownloads rejected (not a readable file): " + absPath);
                    return;
                }
                new Thread(new Runnable() {
                    @Override public void run() {
                        String msg;
                        try {
                            publish(src, src.getName());
                            msg = activity.getString(R.string.save_ok, src.getName());
                        } catch (Exception e) {
                            msg = activity.getString(R.string.op_fail, describe(e));
                        }
                        toastOnUi(msg);
                    }
                }).start();
            }
        });
    }

    /** 起 SAF 多选，把选中的文件收进 files/workspace/imported/<时间戳>/ 并回调页面 */
    @JavascriptInterface
    public void pickFile() {
        post("pickFile", new Runnable() {
            @Override public void run() {
                if (!checkOrigin("pickFile")) {
                    // 页面端 promise 不该悬挂：白名单外也回一个空数组（不执行任何文件操作）
                    callbackImport(new ArrayList<String>());
                    return;
                }
                if (picking) return;
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                try {
                    picking = true;
                    activity.startActivityForResult(i, REQ_PICK_FILE);
                } catch (Exception e) {
                    picking = false;
                    Log.w(TAG, "pickFile failed: " + e);
                    callbackImport(new ArrayList<String>());
                }
            }
        });
    }

    // ---------- SAF 结果（由 Activity 转发） ----------

    /**
     * @return true = 这个 requestCode 归本桥处理（Activity 不必再走 super）
     */
    public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_PICK_FILE) return false;
        picking = false;
        final List<Uri> uris = new ArrayList<>();
        if (resultCode == Activity.RESULT_OK && data != null) {
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
            callbackImport(new ArrayList<String>());
            return true;
        }
        final String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
                .format(new Date());
        new Thread(new Runnable() {
            @Override public void run() {
                List<String> paths = new ArrayList<>();
                try {
                    File dir = new File(activity.getFilesDir(), "workspace/imported/" + stamp);
                    if (!dir.exists() && !dir.mkdirs()) {
                        Log.w(TAG, "pickFile: mkdir failed " + dir);
                        callbackImport(paths);
                        return;
                    }
                    for (Uri u : uris) {
                        try {
                            paths.add(copyUri(u, dir).getAbsolutePath());
                        } catch (Exception e) {
                            Log.w(TAG, "pickFile: copy failed " + u + " : " + e);
                        }
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "pickFile: import error", t);
                }
                callbackImport(paths);
            }
        }).start();
        return true;
    }

    // ---------- origin 白名单 ----------

    /** 必须在 UI 线程调用（读 WebView.getUrl()） */
    private boolean checkOrigin(String op) {
        String url = web == null ? null : web.getUrl();
        String host = hostOf(url);
        if (host != null && isLoopback(host)) return true;
        String configured = prefs().getString(MainActivity.KEY_URL, MainActivity.DEFAULT_URL);
        String configuredHost = hostOf(configured);
        if (host != null && configuredHost != null && configuredHost.equalsIgnoreCase(host)) {
            return true;
        }
        Log.w(TAG, op + " rejected: origin host=" + host + " url=" + url);
        return false;
    }

    private static boolean isLoopback(String host) {
        for (String h : LOOPBACK_HOSTS) {
            if (h.equalsIgnoreCase(host)) return true;
        }
        return false;
    }

    private static String hostOf(String url) {
        if (url == null || url.isEmpty()) return null;
        try {
            String h = java.net.URI.create(url).getHost();
            return (h == null || h.isEmpty()) ? null : h;
        } catch (Exception e) {
            return null;
        }
    }

    private SharedPreferences prefs() {
        return activity.getSharedPreferences(MainActivity.PREFS, Activity.MODE_PRIVATE);
    }

    // ---------- 各动作 ----------

    private void doOpenUrl(String url) {
        String raw = url == null ? "" : url.trim();
        Uri uri;
        try {
            uri = Uri.parse(raw);
        } catch (Exception e) {
            Log.w(TAG, "openUrl: bad uri " + raw);
            return;
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.US);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            Log.w(TAG, "openUrl rejected (scheme=" + scheme + "): " + raw);
            toastOnUi(activity.getString(R.string.url_blocked));
            return;
        }
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Exception e) {
            Log.w(TAG, "openUrl failed: " + e);
            toastOnUi(activity.getString(R.string.op_fail, describe(e)));
        }
    }

    private void doShareText(String text) {
        if (text == null) text = "";
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, text);
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            activity.startActivity(Intent.createChooser(send,
                    activity.getString(R.string.share_choose)));
        } catch (Exception e) {
            Log.w(TAG, "shareText failed: " + e);
            toastOnUi(activity.getString(R.string.op_fail, describe(e)));
        }
    }

    /** canonical 路径必须以 files/ 的 canonical 前缀开头，且确实是个文件 */
    private boolean isInsideSandbox(String absPath) {
        if (absPath == null || absPath.isEmpty()) return false;
        try {
            String root = activity.getFilesDir().getCanonicalPath();
            String canonical = new File(absPath).getCanonicalPath();
            if (canonical.equals(root)) return false;
            return canonical.startsWith(root + File.separator);
        } catch (Exception e) {
            return false;
        }
    }

    // ---------- 落盘 ----------

    private File exportBase() {
        return new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), EXPORT_SUBDIR);
    }

    private void publish(File src, String displayName) throws Exception {
        File out = new File(exportBase(), displayName);
        IOException direct = null;
        try {
            File parent = out.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new IOException("mkdir failed: " + parent);
            }
            copyFile(src, out);
            return;
        } catch (IOException e) {
            direct = e;
        }
        if (Build.VERSION.SDK_INT >= 29) {
            Uri uri = insertDownloads(src, displayName, mimeOf(displayName));
            if (uri != null) return;
        }
        throw direct;
    }

    private Uri insertDownloads(File src, String displayName, String mime) throws IOException {
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
        cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        cv.put(MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/" + EXPORT_SUBDIR);
        Uri uri = activity.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
        if (uri == null) throw new IOException("insert MediaStore.Downloads failed");
        OutputStream os = activity.getContentResolver().openOutputStream(uri);
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

    // ---------- SAF 复制 ----------

    private File copyUri(Uri uri, File dir) throws IOException {
        String name = safeName(displayName(uri));
        File out = new File(dir, name);
        int i = 1;
        while (out.exists() && i < 1000) {
            out = new File(dir, numbered(name, i));
            i++;
        }
        InputStream in = activity.getContentResolver().openInputStream(uri);
        if (in == null) throw new IOException("openInputStream returned null");
        try {
            copyToFile(in, out);
        } finally {
            closeQuietly(in);
        }
        return out;
    }

    private String displayName(Uri uri) {
        String name = null;
        Cursor c = null;
        try {
            c = activity.getContentResolver().query(uri,
                    new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
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

    private void callbackImport(List<String> paths) {
        StringBuilder arr = new StringBuilder("[");
        for (int i = 0; i < paths.size(); i++) {
            if (i > 0) arr.append(',');
            arr.append(jsonStr(paths.get(i)));
        }
        arr.append(']');
        final String js = "if(window.__dshm_onImport){window.__dshm_onImport(" + arr + ");}";
        activity.runOnUiThread(new Runnable() {
            @Override public void run() {
                if (web == null) return;
                try {
                    web.evaluateJavascript(js, null);
                } catch (Throwable t) {
                    Log.w(TAG, "callback failed", t);
                }
            }
        });
    }

    // ---------- 工具 ----------

    private void post(final String op, final Runnable body) {
        if (activity == null) return;
        activity.runOnUiThread(new Runnable() {
            @Override public void run() {
                if (activity.isFinishing()) return;
                try {
                    body.run();
                } catch (Throwable t) {
                    Log.w(TAG, op + " failed", t);
                }
            }
        });
    }

    private void toastOnUi(final String msg) {
        activity.runOnUiThread(new Runnable() {
            @Override public void run() {
                try {
                    Toast.makeText(activity, msg, Toast.LENGTH_LONG).show();
                } catch (Exception ignored) {
                }
            }
        });
    }

    /** JSON 字符串转义（也供页面侧复用：/^[...]$/ 之外一律走这里） */
    public static String jsonStr(String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                case '\b': b.append("\\b"); break;
                case '\f': b.append("\\f"); break;
                default:
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) {
                        b.append(String.format(Locale.US, "\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
            }
        }
        return b.append('"').toString();
    }

    private static String safeName(String n) {
        if (n == null || n.isEmpty()) return "imported-file";
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
        if (s.isEmpty() || s.equals(".") || s.equals("..")) s = "imported-file";
        if (s.length() > 80) s = s.substring(0, 80);
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

    private static void copyFile(File src, File dst) throws IOException {
        File parent = dst.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("mkdir failed: " + parent);
        }
        InputStream in = new FileInputStream(src);
        try {
            copyToFile(in, dst);
        } finally {
            closeQuietly(in);
        }
    }

    private static void copyToFile(InputStream in, File dst) throws IOException {
        File parent = dst.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("mkdir failed: " + parent);
        }
        OutputStream out = new BufferedOutputStream(new FileOutputStream(dst), 128 * 1024);
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

    private static void closeQuietly(Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Exception ignored) {
            }
        }
    }
}
