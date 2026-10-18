package com.dsh.mobile;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Map;

/**
 * ToolchainEnv — 把 payload 里的 Termux 开发工具链（git/curl/openssh/npm/pnpm/tar）
 * 接进 DSH 服务子进程的环境。
 *
 * <p>只改传进去的 Map（ProcessBuilder.environment()），不碰全局环境；由
 * DshServerService.bootLoop 在 pb.environment() 设置完之后调用一次。</p>
 *
 * <p>路径全部从 {@link DshServerService#payloadDir(Context)} 与
 * {@code ctx.getFilesDir()} 推导，跟随 APK 的私有目录，不写死 /data/data 包名。</p>
 */
public final class ToolchainEnv {

    private static final String TAG = "DshToolchain";

    /** 只写一次，进程内并发调用不重复写。 */
    private static final Object GITCONFIG_LOCK = new Object();

    private ToolchainEnv() {
    }

    /**
     * 注入工具链环境变量，并在首次运行时准备用户级配置。
     *
     * @param env ProcessBuilder.environment()（不可为 null）
     * @param ctx Service/Application Context
     */
    public static void apply(Map<String, String> env, Context ctx) {
        if (env == null || ctx == null) return;

        File files = ctx.getFilesDir();
        File prefix = new File(DshServerService.payloadDir(ctx), "termux/usr");
        File bin = new File(prefix, "bin");

        // 1. PATH：payload 的 bin 优先，其后系统 shell 工具
        env.put("PATH", bin.getAbsolutePath() + ":/system/bin:/system/xbin");

        // 2. TLS：与 DshServerService 已设的 SSL_CERT_FILE 同值（Termux ca-certificates 包
        //    只提供 etc/tls/cert.pem；extract_termux.py 另外物化了一份
        //    etc/ssl/certs/ca-certificates.crt 给 git 的 http.sslCAInfo 用）
        String sslCert = env.get("SSL_CERT_FILE");
        if (sslCert == null || sslCert.isEmpty()) {
            sslCert = new File(prefix, "etc/tls/cert.pem").getAbsolutePath();
        }
        env.put("GIT_SSL_CAINFO", sslCert);

        // 3. git 的exec-path / templates：Termux 版 git 把这两个路径编成了
        //    /data/data/com.termux/files/usr/...，必须指回 payload 里的副本，
        //    否则 git-remote-https 等 dashed 命令找不到（git clone https 会失败）
        env.put("GIT_EXEC_PATH",
                new File(prefix, "libexec/git-core").getAbsolutePath());
        env.put("GIT_TEMPLATE_DIR",
                new File(prefix, "share/git-core/templates").getAbsolutePath());
        // Termux git 还会读编死的系统级 gitconfig（/data/data/com.termux/.../etc/gitconfig），
        // 在本应用沙箱里不可读且一读就 fatal——直接禁用系统级 gitconfig（真机实测踩到）。
        env.put("GIT_CONFIG_NOSYSTEM", "1");

        // 4. 包管理器可写目录：termux 前缀在 APK 私有目录里只读语义，
        //    全局安装落到 files/ 下，避免写 prefix
        File npmPrefix = new File(files, "npm-global");
        File pnpmHome = new File(files, "pnpm");
        env.put("npm_config_prefix", npmPrefix.getAbsolutePath());
        env.put("PNPM_HOME", pnpmHome.getAbsolutePath());
        mkdirs(npmPrefix);
        mkdirs(pnpmHome);

        // 5. 首次运行写 ~/.gitconfig（HOME=filesDir）
        ensureGitConfig(files, prefix);
    }

    /** 首次运行写 .gitconfig：safe.directory=* + http.sslCAInfo（已存在则不覆盖）。 */
    private static void ensureGitConfig(File files, File prefix) {
        File cfg = new File(files, ".gitconfig");
        synchronized (GITCONFIG_LOCK) {
            if (cfg.exists()) return;
            String ca = new File(prefix, "etc/ssl/certs/ca-certificates.crt")
                    .getAbsolutePath();
            String body = "# written by DSH Mobile ToolchainEnv (first run only)\n"
                    + "[safe]\n"
                    + "\tdirectory = *\n"
                    + "[http]\n"
                    + "\tsslCAInfo = " + ca + "\n";
            FileOutputStream fos = null;
            try {
                fos = new FileOutputStream(cfg);
                fos.write(body.getBytes("UTF-8"));
                fos.flush();
                Log.i(TAG, "wrote " + cfg.getAbsolutePath());
            } catch (Exception e) {
                Log.w(TAG, "write .gitconfig failed", e);
            } finally {
                if (fos != null) {
                    try {
                        fos.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        }
    }

    private static void mkdirs(File dir) {
        if (dir != null && !dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "mkdirs failed: " + dir.getAbsolutePath());
        }
    }
}
