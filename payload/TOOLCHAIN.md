# TOOLCHAIN.md — DSH Mobile payload 里的设备端开发工具链

> 目标：让 agent 在 **Android 设备本地**（无需 adb/PC）完成 `git clone`、`npm i`、`pnpm i`、
> `curl`、`tar` 等开发动作。所有二进制都进 APK 的 `assets/payload.zip`，
> 解压到 `files/payload/termux/usr`，跟 node/bash/rg 同一个 Termux 前缀。
>
> 变更面：`payload/extract_termux.py`（映射 + 符号链接物化 + pnpm）、
> `payload/patch_runpath.py`（遍历扩到 libexec/**）、`payload/debs/`（新增 .deb）、
> `app/src/main/java/com/dsh/mobile/ToolchainEnv.java`（新）。

## 1. 工具清单（aarch64，Termux 官方仓库）

索引：<https://packages.termux.dev/apt/termux-main/dists/stable/main/binary-aarch64/Packages>
下载：`python payload/payload_tools.py fetch <pkg>…`（按索引 `Size` + `SHA256` 强校验，直连可用，无需代理）

| .deb | 版本 | 字节数 | SHA256（前 16） |
|---|---|---|---|
| `git_2.56.0_aarch64.deb` | 2.56.0 | 4,541,040 | `dcdd22c5fdf7bdb5…` |
| `curl_8.23.0_aarch64.deb` | 8.23.0 | 246,160 | `e57a9975a828eb48…` |
| `openssh_10.6p1-2_aarch64.deb` | 10.6p1-2 | 968,680 | `415b07b437c9a62a…` |
| `openssh-sftp-server_10.6p1-2_aarch64.deb` | 10.6p1-2 | 56,648 | `3b45d587315e2804…` |
| `npm_11.21.1_all.deb` | 11.21.1 | 2,033,224 | `f2b5a5d88ec39bba…` |
| `tar_1.35-3_aarch64.deb` | 1.35-3 | 345,636 | `a3847bc259b8654f…` |
| `less_710_aarch64.deb` | 710 | 143,624 | `bff261b48553bcde…` |
| `libcurl_8.23.0_aarch64.deb` | 8.23.0 | 1,043,340 | `2011bb9709261bb3…` |
| `libexpat_2.9.0_aarch64.deb` | 2.9.0 | 100,256 | `f8a104e8227c9c18…` |
| `libnghttp2_1.70.0_aarch64.deb` | 1.70.0 | 95,924 | `ab2e0a3408fe4934…` |
| `libnghttp3_1.18.0_aarch64.deb` | 1.18.0 | 69,668 | `0be2ee96def3608a…` |
| `libngtcp2_1.25.0_aarch64.deb` | 1.25.0 | 174,068 | `f471bad7f4329b6b…` |
| `libssh2_1.11.1-2_aarch64.deb` | 1.11.1-2 | 219,220 | `1add4e0a926b8488…` |
| `ldns_1.9.2_aarch64.deb` | 1.9.2 | 305,108 | `fcc8feb86be077e5…` |
| `libedit_20260512-3.1-0_aarch64.deb` | 20260512-3.1-0 | 79,888 | `728da8f45e9c0027…` |
| `libacl_2.4.0_aarch64.deb` | 2.4.0 | 123,540 | `cb620e33405e74b5…` |
| `libandroid-glob_0.6-3_aarch64.deb` | 0.6-3 | 7,032 | `2276ae8adedf0db7…` |
| `libandroid-selinux_14.0.0.11-1_aarch64.deb` | 14.0.0.11-1 | 59,624 | `00afd8c34087c286…` |
| `libresolv-wrapper_1.1.8_aarch64.deb` | 1.1.8 | 11,248 | `41c989a4c6f85575…` |
| `krb5_1.22.2_aarch64.deb` | 1.22.2 | 902,380 | `3c97dcc7437616bb…` |
| **合计** | | **11,526,308（10.99 MiB）** | |

逐包来源 URL（`REPO = https://packages.termux.dev/apt/termux-main`，取索引里的 `Filename`）：

```
https://packages.termux.dev/apt/termux-main/pool/main/g/git/git_2.56.0_aarch64.deb
https://packages.termux.dev/apt/termux-main/pool/main/c/curl/curl_8.23.0_aarch64.deb
https://packages.termux.dev/apt/termux-main/pool/main/o/openssh/openssh_10.6p1-2_aarch64.deb
https://packages.termux.dev/apt/termux-main/pool/main/o/openssh-sftp-server/openssh-sftp-server_10.6p1-2_aarch64.deb
https://packages.termux.dev/apt/termux-main/pool/main/n/npm/npm_11.21.1_all.deb
https://packages.termux.dev/apt/termux-main/pool/main/t/tar/tar_1.35-3_aarch64.deb
https://packages.termux.dev/apt/termux-main/pool/main/l/less/less_710_aarch64.deb
https://packages.termux.dev/apt/termux-main/pool/main/libc/libcurl/libcurl_8.23.0_aarch64.deb
https://packages.termux.dev/apt/termux-main/pool/main/libe/libexpat/libexpat_2.9.0_aarch64.deb
https://packages.termux.dev/apt/termux-main/pool/main/libn/libnghttp2/libnghttp2_1.70.0_aarch64.deb
https://packages.termux.dev/apt/termux-main/pool/main/libn/libnghttp3/libnghttp3_1.18.0_aarch64.deb
https://packages.termux.dev/apt/termux-main/pool/main/libn/libngtcp2/libngtcp2_1.25.0_aarch64.deb
https://packages.termux.dev/apt/termux-main/pool/main/libs/libssh2/libssh2_1.11.1-2_aarch64.deb
https://packages.termux.dev/apt/termux-main/pool/main/l/ldns/ldns_1.9.2_aarch64.deb
https://packages.termux.dev/apt/termux-main/pool/main/libe/libedit/libedit_20260512-3.1-0_aarch64.deb
https://packages.termux.dev/apt/termux-main/pool/main/liba/libacl/libacl_2.4.0_aarch64.deb
https://packages.termux.dev/apt/termux-main/pool/main/liba/libandroid-glob/libandroid-glob_0.6-3_aarch64.deb
https://packages.termux.dev/apt/termux-main/pool/main/liba/libandroid-selinux/libandroid-selinux_14.0.0.11-1_aarch64.deb
https://packages.termux.dev/apt/termux-main/pool/main/libr/libresolv-wrapper/libresolv-wrapper_1.1.8_aarch64.deb
https://packages.termux.dev/apt/termux-main/pool/main/k/krb5/krb5_1.22.2_aarch64.deb
```

pnpm **不走 Termux 包**（官方仓库没有）：

| 来源 | 版本 | 体积 | SHA256 |
|---|---|---|---|
| `npm pack pnpm@10` → `pnpm-10.34.7.tgz`（<https://registry.npmjs.org/pnpm/-/pnpm-10.34.7.tgz>） | 10.34.7 | 4,894,868 B | `c729f6538e005d234889910466fd1c4c44affb0b28ea86d93fcf88aa1aefa694` |

> 为什么钉 10.x：pnpm 11/12 的 npm 包把入口改成 `bin/pnpm.mjs` + 可选原生二进制
> （`native-binary.mjs`、`@pnpm/exe.android-arm64`），不再是 `bin/pnpm.cjs`；
> 10.34.7（10.x 最新）仍是 `bin/pnpm.cjs` / `bin/pnpx.cjs` 布局，与本项目
> `exec node …/bin/pnpm.cjs` 的 wrapper 约定一致。`extract_termux.py` 的
> `ensure_pnpm_wrappers()` 已对 `.cjs` → `.mjs` 做兜底，升级时不用改脚本。

## 2. 依赖闭包是怎么定的

不是照抄 apt 的 `Depends`，而是**以 ELF 的 DT_NEEDED 为准**：

1. `python payload/payload_tools.py scan debs/<pkg>.deb` 打印每个 ELF 的 `DT_NEEDED`；
2. 只抽取 `verify()` 报告缺的库对应的 .deb（`extract_termux.py` 末尾会全扫 `termux/usr` 下所有 ELF）；
3. 迭代到 `VERIFY ALL OK`。

最终依赖链（→ 表示 DT_NEEDED 引入）：

- `git` → `libpcre2-8` `libz` `libiconv` `libcrypto`（都已有）；
  `libexec/git-core/git-remote-{http,https}` / `git-http-fetch|push` → **`libcurl` `libexpat`**
- `libcurl` → `libnghttp2` `libnghttp3` `libngtcp2_crypto_ossl` `libngtcp2` `libssh2` `libssl/libcrypto` `libz`
- `curl` → `libcurl` `libz`
- `ssh` → `libldns`、`libgssapi_krb5`；`gssapi` → `libkrb5` `libk5crypto` `libcom_err` `libkrb5support`
  `libandroid-glob` `libresolv_wrapper`
- `scp`/`sftp` → `libandroid-support`；`sftp` → `libedit`
- `tar` → `libacl` `libandroid-glob` `libandroid-selinux` `libiconv`
- `less` → `libncursesw` `libpcre2-8`
- `npm` → 只要已有的 `node`（包本身是纯 JS，`lib/node_modules/npm/**`）

**下载了但确认不需要抽取**（DT_NEEDED 未引用，留在 `payload/debs/` 备查，不进 payload）：
`brotli` `zstd` `libpsl` `libidn2` `libunistring`（Termux 的 libcurl 未链接它们）、
`libdb` `libevent` `liblzma`（只被 krb5 的 kdb 插件 / zstd 用到，客户端不需要）、
`termux-auth` `resolv-conf` `attr`（只有 sshd/passwd/setfattr 用）。

## 3. 抽取规则（extract_termux.py v3）

- **整子树映射**：`DEB_MAP` 的 key 以 `/` 结尾表示子树前缀，
  `'…/usr/lib/node_modules/npm/': 'lib/node_modules/npm/'`、
  `'…/usr/libexec/git-core/': 'libexec/git-core/'`、
  `'…/usr/share/git-core/': 'share/git-core/'`。
- **符号链接物化**（zip 里不能可靠带 symlink）：
  - 目标在 `lib/` → 写成目标文件的**字节副本**（动态加载器必须拿到真 ELF，不能是脚本）；
    `dedupe_libs()` 再按内容哈希只留 SONAME 名（`libkrb5.so.3` 等 5 个 krb5 库就是这样来的）。
  - 目标在 `bin/`、`libexec/` → 写 `#!/system/bin/sh` wrapper：
    - JS（`.js/.cjs/.mjs`）：`exec "$HERE/node" "$HERE/<rel>" "$@"`，
      例 `bin/npm`：
      ```sh
      #!/system/bin/sh
      HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
      exec "$HERE/node" "$HERE/../lib/node_modules/npm/bin/npm-cli.js" "$@"
      ```
    - **ELF 且文件名与目标不同**（git 的 `git-add`→`git`、`git-remote-https`→`git-remote-http`）：
      必须把 `argv[0]` 原样传回去，否则 git 丢掉子命令身份/把 https 当 http。
      用 payload 自带的 bash 做 `exec -a`：
      ```sh
      HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
      exec "$HERE/../../bin/bash" -c 'exec -a "$1" "$2" "${@:3}"' sh "git-remote-https" "$HERE/git-remote-http" "$@"
      ```
    - 其它脚本（`ssha`→`libexec/wrap-ssh-agent.sh`）直接 exec。
  - 目标没被映射（`git-shell`/`scalar`/`git-cvsserver` 没进 payload）→ 跳过并打日志，
    不生成会自指的 wrapper。
- **CA 双写**：ca-certificates 包只带 `etc/tls/cert.pem`，额外物化一份
  `etc/ssl/certs/ca-certificates.crt`（gitconfig 的 `http.sslCAInfo` 指这份），内容同源。
- 清理只清 deb 管的 `bin/ libexec/ share/ etc/` + `lib/*` 顶层 + `lib/node_modules/npm`；
  **`lib/node_modules/pnpm` 保留**（npm pack 装的，不在 deb 里）。
- 末尾自动调用 `patch_runpath.patch_tree()`：`bin/ → $ORIGIN/../lib`、
  `libexec/git-core/ → $ORIGIN/../../lib`、`lib/ → $ORIGIN`，非 ELF 自动跳过。

## 4. 一键重跑

```powershell
cd dsh-mobile
# 1) 首次/换版本时下载（已缓存的按 Size/SHA256 跳过）
python payload\payload_tools.py fetch git curl openssh openssh-sftp-server npm tar less `
  libcurl libexpat libnghttp2 libnghttp3 libngtcp2 libssh2 ldns libedit libacl `
  libandroid-glob libandroid-selinux libresolv-wrapper krb5
# 2) 抽取 + 依赖校验 + RUNPATH（末尾必须 VERIFY ALL OK）
python payload\extract_termux.py
# 3) pnpm（只需在 pnpm 版本变更时重做；tarball 放哪都行）
cd $env:TEMP; npm pack pnpm@10
# registry 不通时换镜像：npm pack pnpm@10 --registry=https://registry.npmmirror.com
cd <项目根>
python payload\extract_termux.py pnpm "$env:TEMP\pnpm-10.34.7.tgz"
# 4) 之后照旧：build_payload.py → 拷 assets（payload.zip + payload.sha256）→ 打包
```

> 注意：payload 内容变了，打包前必须 bump `tools/build_manual.py` 里的 `VERSION_CODE`
> （完成标记按 versionCode 命名，否则设备不会重解压）。本次改动没动版本号，由 lead 决定。

## 5. 体积账（raw，未压缩）

当前 `files/payload/termux/usr`：**158.24 MiB / 3298 files**；本次工具链新增 **+56.99 MiB**
（旧前缀约 101.25 MiB，由「当前总量 − 新增落盘字节」推算）。

| 组 | 体积 |
|---|---|
| pnpm（`lib/node_modules/pnpm` + bin wrapper） | 19.05 MiB |
| git（`bin/git` + `libexec/git-core` + `share/git-core`） | 17.84 MiB |
| npm（`lib/node_modules/npm` + bin wrapper） | 11.23 MiB |
| 依赖库（libcurl/nghttp*/ntcp2/ssh2/ldns/edit/acl/selinux/krb5 等 18 个 .so） | 3.98 MiB |
| openssh（bin + libexec + etc/ssh） | 3.85 MiB |
| tar / less / curl | 0.49 / 0.26 / 0.30 MiB |
| `payload/debs/` 新增 .deb 合计（不随 APK 发布，仅仓库） | 10.99 MiB |

> 体积大头是 git-core（Termux 把 `git-daemon`/`git-http-*`/`git-remote-http` 各编成 ~2 MiB
> 独立二进制，共 ~15 MiB）与 pnpm/npm 的纯 JS 树；zip 压缩后预计显著小于 raw，未单独实测。

## 6. extract/verify 输出末几行（本机实测）

```
libexec\git-core\git-http-fetch -> libcurl.so,libdl.so,libpcre2-8.so,libz.so.1,libiconv.so,libcrypto.so.3,libc.so | OK
libexec\git-core\git-http-push -> libcurl.so,libexpat.so.1,libdl.so,libpcre2-8.so,libz.so.1,libiconv.so,libcrypto.so.3,libc.so | OK
libexec\git-core\git-imap-send -> libcurl.so,libssl.so.3,libcrypto.so.3,libdl.so,libpcre2-8.so,libz.so.1,libiconv.so,libc.so | OK
libexec\git-core\git-remote-http -> libcurl.so,libdl.so,libpcre2-8.so,libz.so.1,libiconv.so,libcrypto.so.3,libc.so | OK
libexec\git-core\git-sh-i18n--envsubst -> libdl.so,libpcre2-8.so,libz.so.1,libiconv.so,libcrypto.so.3,libc.so | OK
VERIFY ALL OK
```

`all patched: 63 ELF, 205 non-ELF skipped`（bin/curl/git/node/…、libexec/git-core/* 均已改写 RUNPATH）。

## 7. Java 侧（ToolchainEnv.java）

`package com.dsh.mobile;` Java 8、无 AndroidX，只依赖 `DshServerService.payloadDir(ctx)` 与
`ctx.getFilesDir()`。`apply(Map<String,String> env, Context ctx)` 做的事：

| 变量 | 值 |
|---|---|
| `PATH` | `files/payload/termux/usr/bin:/system/bin:/system/xbin` |
| `GIT_SSL_CAINFO` | `<prefix>/etc/tls/cert.pem`（= DshServerService 已有 `SSL_CERT_FILE` 同值） |
| `GIT_EXEC_PATH` | `<prefix>/libexec/git-core`（**必需**：Termux git 把 exec-path 编死成 `/data/data/com.termux/files/usr/libexec/git-core`，不改则 `git-remote-https` 找不到，clone https 必失败） |
| `GIT_TEMPLATE_DIR` | `<prefix>/share/git-core/templates`（同上，`git init` 用） |
| `npm_config_prefix` | `files/npm-global`（mkdirs） |
| `PNPM_HOME` | `files/pnpm`（mkdirs） |

首次运行写 `files/.gitconfig`（已存在则不覆盖）：

```ini
[safe]
	directory = *
[http]
	sslCAInfo = <prefix>/etc/ssl/certs/ca-certificates.crt
```

## 8. 集成 snippet（给 lead，不改 DshServerService.java）

### ① bootLoop：`pb.environment()` 段之后插一行

`DshServerService.java` 里 `pb.environment().put("NODE_NO_WARNINGS", "1");`（当前 ~L305）
之后、`pb.redirectErrorStream(true);` 之前：

```java
                pb.environment().put("NODE_NO_WARNINGS", "1");
                // 开发工具链（git/curl/openssh/npm/pnpm）：PATH、git CA/exec-path、npm/pnpm 落盘目录
                ToolchainEnv.apply(pb.environment(), this);
                pb.redirectErrorStream(true);
```

### ② 解压后可执行位：替换固定三件套

把 `extractPayload()` 里这段（当前 ~L501-507）：

```java
                // ---- 5. 可执行位（zip 不保留权限）----
                for (String bin : new String[]{"node", "bash", "rg"}) {
                    File b = new File(new File(payloadDir(this), "termux/usr/bin"), bin);
                    if (b.exists() && !b.setExecutable(true, false)) {
                        Log.w(TAG, "setExecutable returned false: " + bin);
                    }
                }
```

换成：

```java
                // ---- 5. 可执行位（zip 不保留权限）：bin/ + libexec/（含 git-core）全量 ----
                // 不能只 chmod 三件套：git/curl/ssh/npm wrapper 以及 libexec/git-core/**
                // 里的 dashed 入口都需要可执行位。
                File usrDir = new File(payloadDir(this), "termux/usr");
                setTreeExecutable(new File(usrDir, "bin"));
                setTreeExecutable(new File(usrDir, "libexec"));
```

并在类里加辅助方法（放在 `deleteRecursively` 附近）：

```java
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
```

> 遍历 `libexec` 整棵树而不是只写 `libexec/git-core`：`sftp-server`/`ssh-keysign`/`rmt`/
> `lessecho` 也在这层，对 `git-core` 是超集，行为一致。

## 9. 真机验证点（等有 USB 时）

在 DSH 会话里用 `bash` 工具（它继承服务子进程 env）执行：

1. `git --version` → `git version 2.56.0`
2. `git clone --depth 1 https://github.com/octocat/Hello-World.git ~/hw`（HOME=filesDir）
   → 验证：`libcurl` + CA（cert.pem / `http.sslCAInfo`）+ `git-remote-https` wrapper + `GIT_EXEC_PATH`
   全链路；`git -C ~/hw log --oneline -1` 再确认
3. `npm -v` → `11.21.1`；`npm config get prefix` → `…/files/npm-global`
4. `pnpm -v` → `10.34.7`；`pnpm store path` 落在 `files/` 下（不在只读 prefix）
5. `curl -sI https://registry.npmjs.org/ | head -1` → `HTTP/2 200`
6. `tar --version | head -1`、`ssh -V`

失败对照：
- `git: 'remote-https' is not a git command` → `GIT_EXEC_PATH` 没生效或 `libexec/git-core`
  没 chmod（snippet ②）；
- `SSL certificate problem` → `GIT_SSL_CAINFO` / `.gitconfig` 指向的证书不存在；
- `npm: Permission denied` → wrapper 没可执行位或 `node` 不在同目录。

## 10. 边界 / 没做的事

- 没做真机验证（无 USB），没 commit。
- `git-shell`/`scalar`/`git-cvsserver`/`git-svn`（要 perl）/`git send-email`（要 perl）
  不在 payload → 对应命令报 not found；普通 clone/pull/push/commit 不受影响。
- 没裁 npm/pnpm 的 man、docs、test（要体积还能再省几 MB；不影响功能）。
- `ssh_config`/`moduli` 已带，但 Termux 编死的 `etc/ssh` 绝对路径在 payload 下不存在，
  ssh 会退回内置默认（host key 校验仍走 `~/.ssh/known_hosts`），git over ssh 可用。
