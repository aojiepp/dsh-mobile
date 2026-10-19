# dsh-mobile 项目记忆

Android 上独立跑完整 DSH 的壳工程。构建链、payload 管线、真机踩坑都记在这里。

## v2.11（vc29，2026-10-18）：安全基线 + 开发工具链 + 收纳箱重做

- **构建**：`tools\build_manual.py [--release]`（release 剥离 debuggable、BuildInfo.DEBUG=false；debug 轨构建时注入回）。
  签名口令移出源码 → `tools\signing.properties`（gitignored）或环境变量 `DSHM_STORE_PASS/DSHM_KEY_PASS`。
  `BuildInfo.java`（DEBUG/VERSION_CODE/VERSION_NAME/PAYLOAD_SHA256/BUILD_TIME）每次构建由脚本生成。
  并行开发用 `tools\compile_check.py --name <ws>`（独立临时目录，不碰共享 build）；**build_manual.py 禁止并行**。
  真机验收：`tools\verify_release.ps1 -Phase install|security|logs|perf`。
- **安全（P0/P1 已修）**：manifest 不再有 debuggable；`allowBackup=false` + `data_extraction_rules.xml`；
  DshServerService 启动时日志轮转（旧日志打码 token）、log-watcher 把带 token 的启动 URL 写进 prefs
  （`launch_url`/`launch_url_ts`，MainActivity.readLaunchUrl 是 prefs 优先、日志兜底）、删 legacy `manual.log`、
  日志/.env 刷 600（先全关再开属主位，单独 setReadable(true,true) 清不掉 group/other）。
  **cleartext 保持 true 是用户明确决策**（「连接电脑」模式局域网 http 直连在用），设置页对非回环 http 有红字警告。
  watcher 抓到 URL 行先写 prefs 再把日志里该 token **就地等长打码**（pwrite 已固化字节，不影响 node 追加）。
  Termux git 还要 `GIT_CONFIG_NOSYSTEM=1`（编死的系统 gitconfig 一读就 fatal）；
  设置页端口探测必须在后台线程（主线程 connect → NetworkOnMainThread → 恒「未监听」）。
- **工具链进 payload**：git 2.56 / curl 8.23 / openssh 10.6 / npm 11.21 / pnpm 10.34.7 / tar / less + DT_NEEDED 闭包
  （清单与版本在 `payload/TOOLCHAIN.md`）。`extract_termux.py` 支持整子树映射 + 符号链接物化 wrapper；
  `patch_runpath.py` 扩到 libexec/**。`ToolchainEnv.apply()` 在服务 spawn 前注入 PATH/GIT_EXEC_PATH/GIT_TEMPLATE_DIR/
  GIT_SSL_CAINFO/npm_config_prefix/PNPM_HOME + 首启写 files/.gitconfig。解压后 setExecutable 全量遍历 bin/+libexec/。
  pnpm 钉 10.x（12.x 改 .mjs 布局）；Termux ca-certificates 只有 cert.pem，extract 会复制一份 ca-certificates.crt 给 git。
- **收纳箱**：递归两棵根（workspace / dsh-home/sessions，深度≤6，渲染上限 600 行）、多选、目录打 zip 提取、
  SAF 导入到 files/workspace/imported/<ts>/、MediaStore 分享（无 AndroidX 不用 FileProvider）。
  **DshBridge**（JS 名仍 DshShell）：openUrl/shareText/saveToDownloads/pickFile，全部过 origin 白名单
  （回环或 server_url 同 host），saveToDownloads 校验 canonical 必须在 files/ 内。
- **性能**：PATCH_JS 重写——删 setInterval(2000)、observer 回调只排 rAF（帧内 disconnect→patch→observe 断自激）、
  读写分离 + setIfDiff、`body *` 全树扫描改 5 个定向选择器。自证桩 `__dshmPatchCount/__dshmPatchMs`（需 debug 包 + CDP 读）。
  真机实测（2026-10-18 vivo V2339A，top -b 分进程）：活跃对话 UI 进程 avg 66.9% / max 81%（旧基线 113% 单核），
  node avg 23.7%；对话后回落 avg 17.3%。自激循环已结构性消除，残量以流式渲染为主；45% 目标线未达，持续观察。
- **瘦身**：删 .d.ts/.d.cts/.d.mts（2309 个/9.82MB）+ yarn 误打包文件 + .package-lock；**lighthouse_worker.js 不能删**
  （被 devtools chunks 引用，删了 Lighthouse 面板挂）。prepare 末尾自动写 `payload/size_report.txt`（top-50）。
- **payload 校验**：build_payload.py 生成 `payload.sha256`，两个文件都要拷进 assets/；解压前校验，不匹配 failExtract。
- **体积账**：zip 82.9→101.1MB（工具链 +18MB、瘦身 -2.4MB）；文件数 15,716→16,369（工具链 +3,009、瘦身 -2,311、dedupe 若干）。
  可选再省 ~15MB raw：git-core 里只留仓库必需二进制（daemon/http-backend/imap-send 等可删），本期未裁。
- **targetSdk 28 不动**（SELinux exec 限制；升 35 需 linker 直调方案，第二期评估）。

## v2.12（vc30，2026-10-19）：权限模式解锁 —— 智能体终于能用 bash

- **根因**：DSH 0.2.1 的 file sandbox 是 **fail-closed** 的：`dsh-sandbox-local` 在 Linux 上只认
  `bwrap` 或 Landlock launcher（预编译包 `@deepseek-ai/node-addon-system-<platform>-<arch>`）。
  Android 上 bwrap 不存在、payload 也没带 landlock 的 linux-arm64 包（它是 optionalDependency，
  在 Windows 上打 payload 时根本装不到），于是默认 `workspace-write` 下 bash 工具直接抛
  `SANDBOX_UNAVAILABLE` —— 工具链（git/curl/npm）全都装了但一条命令都执行不了。
  真机铁证：`Error: sandbox mode "workspace-write" is requested but no sandbox backend is usable on this host`。
- **修复**：`DshServerService.ensurePermissionPatch()` 在 spawn 前写 `files/dsh-permission.patch.yml`，
  启动参数改成 `lib/bin.js --profile web --patch <file> --host ...`（**launcher flag 必须在 profile 名之前**，
  bin.js 遇到第一个不认识的 token 就开始把后面当 app 参数透传）。patch 覆盖
  `sandbox-policy.mode = danger-full-access` + `approval.policy = never`（手机没审批 UI，ask 会卡死）。
- **别用 `DSH_PERMISSION_MODE` 环境变量（试过，无效）**：node 进程 environ 里明明有该变量
  （`/proc/<pid>/environ` 确认），但 base profile 的
  `mode: !!js process.env.DSH_PERMISSION_MODE ?? 'workspace-write'` 在 profile 组合时求值拿不到它，
  会话仍然 pin 成 `workspace-write`。`--patch` 是字面量覆盖、不经过表达式求值，可靠。（env 保留注入，无害。）
- **验证**（真机）：智能体执行 `git --version` → `git version 2.56.0`；新建会话权限选择器默认「完全权限」。
  旧会话的 pin 仍显示「工作区内修改」（历史事件），执行按部署默认 mode 走，不影响。
- `verify_release.ps1` 的 install 阶段修了两处：versionCode 从 APK 的 `aapt2 dump badging` 读（不写死），
  装完 `dumpsys` 有延迟、查一次会误报 → 加 8 次重试。

## 图标（SVG 真源）

- 真源：`design/ic_launcher.svg`（蓝色大肥鱼 / 鲸鱼娘 Q 版头像，简笔扁平）。
- 生成：`python design/svg2vector.py` → 覆盖 `res/drawable/ic_launcher_fg.xml`；
  背景色在 `res/values/colors.xml` 的 `ic_launcher_bg`；`mipmap-anydpi-v26/*.xml` 是 adaptive-icon 壳。
- 自检：`python design/vector2svg.py out.svg` 把生成的 vector 反渲染成 SVG，用 Edge headless 截图比对，
  确认颜色/坐标没错（改过 svg2vector.py 必做）。
- 通知栏小图标 `res/drawable/ic_stat_dsh.xml` 是白色鲸鱼剪影（手写 vector，要纯白单色）。
- 内容要留在 adaptive icon 的 66/108dp 安全区内，否则启动器圆形遮罩会切掉鳍尖。

## 构建（零网络、全手工链）

```powershell
python payload\prepare_dsh_tree.py <staging>\dsh   # 需要 dsh 包 + 内嵌 node_modules
python payload\dedupe_node_modules.py              # 0.2.1 起必跑：去重 zod 等多副本
python payload\build_payload.py
copy payload\payload.zip app\src\main\assets\payload.zip
python tools\build_manual.py                        # 产物 build\apk\dsh-mobile-v<ver>-debug.apk
```

- staging 目录要按「`dsh` 包本体 + `node_modules` 嵌在它下面」摆好。现在的做法：`npm pack @deepseek-ai/dsh@<ver>`
  → 解开成 `dsh/` → 进目录 `npm install --no-audit --no-fund`。**必须全量装（含 devDependencies）**：
  桌面版 host 侧插件（host-webserver / llm-deepseek / sandbox-local 等）挂在 dsh 包的 devDependencies 里，
  `--omit=dev` 会漏装、web profile 起不来。
- `dedupe_node_modules.py`：0.2.1 起 50 个 `@deepseek-ai` 包精确锁 `zod@4.4.3`，顶层被 hoist 成 4.6.7，
  npm 于是给每个包塞一份嵌套副本（51×2.89MB）。脚本把一份 4.4.3 提升到顶层、删掉其余副本、再给
  「本来解析到 4.6.7」的 7 个 importer 塞回 4.6.7，末尾用真实 import 扫描复核**每个 importer 解析版本不变**。
  `payload/verify_zod_resolution.py` 可独立复核（`--save` 存快照对比）。
- 改 payload 后**必须 bump `VERSION_CODE`**（完成标记按 versionCode 命名 `payload.app<vc>.ok`），
  否则手机上不会重解压；只改 Java/env 时可以不 bump。
- `payload/payload.zip` 是中间产物，会被 `build_payload.py` 覆盖；assets 里那份才是随 APK 发布的。

## 真机踩坑（vivo V2339A / Android 16 / arm64）

- **OpenSSL 配置路径被 Termux 编死**：node 初始化 crypto 时读
  `/data/data/com.termux/files/usr/etc/tls/openssl.cnf`（不存在）→ 进程**退出码 13**。
  修法：启动子进程前补一份最小 `openssl.cnf` 到 `payload/termux/usr/etc/tls/` 并设 `OPENSSL_CONF`。
- **不能 stub `@deepseek-ai/dsh-sandbox-windows-acl`**：0.2.0-rc.2 的 `dsh-sandbox-local`
  会从它 import `registerAclDiagnosisSkill` / `ACL_DIAGNOSIS_SKILL` / `AclSandbox` 等命名导出，
  stub 缺导出 → sandbox 插件 import 失败 → `tool-bash` 一直 waiting for shell → agent preset 无效，
  手机端表现是「新建会话失败：agent-preset/invalid」。它是纯 JS 包（~220KB），koffi 走 lazy require
  只在 win32 路径用，Android 上原样保留即可。
- 服务状态写 SharedPreferences 时要带时间戳：UI 得忽略上一轮留下的 `exited:13` 之类陈旧状态，
  否则新一次启动会被瞬间误判为失败。
- 装 APK：vivo 安装器要点两下（风险勾选框 + 继续安装），见 `..\AGENTS.md`。

## 换内核记录

- **0.2.0-rc.2 → 0.2.1-rc.2（v2.10，2026-10-17 真机验证）**：
  - 0.2.1 的 `dsh-loader-smoke`（devDependency）拉了 `tsx` → `esbuild`，npm 装的是 **win32-x64 版
    esbuild.exe（11MB）**，Android 上没用还占体积；`prepare_dsh_tree.py` 的 `REMOVE_PACKAGES`
    已加 `@esbuild / esbuild / tsx`（运行时没有任何包 import tsx）。
  - 新增依赖里没有别的 arm64 不友好原生包；`dsh-win32-process`（import koffi）走 lazy require，安全。
  - **旧 `files/dsh-home` 不用清**：0.2.0 生成的 `profiles/web` 配置在 0.2.1 下能直接 boot，
    `dsh web` 正常打印 token URL，新建会话 + 一轮对话真机通过。
  - payload 体积：裁剪后 268.7MB raw / 82.9MB zip（0.2.0 是 213MB / 71MB），APK 83MB。

## 关键路径（运行时）

- 运行时目录：`files/payload`（termux 前缀 + `dsh-app` 裁剪树）
- 权限 patch：`files/dsh-permission.patch.yml`（每次启动由服务重写，`--patch` 传给 launcher；见 v2.12）
- 用户数据：`files/dsh-home`（会话/凭据/工作区，升级与重解压都不动它）
- 服务日志：`files/dsh-server.log`（node 的 stdout/stderr，设置页「查看服务日志」也读它）

## 第二期 backlog（用户 2026-10-19 明确：随时可做，等他想用再开工）

按价值排序（不是承诺，是待办池）：

1. **Python dev-pack（按需下载，不进包）** —— 用户最初的诉求「给智能体更好的开发环境」里唯一的真缺口：
   现在只有 git/curl/ssh/node，写不了脚本、跑不了数据处理。方案：设置页一个按钮 → 下载 termux python
   .deb 闭包（~30-50MB）解压进同一个 prefix；**必须先确认 danger-full-access 下 exec 正常**（v2.12 之后已具备）。
2. **权限模式设置项** —— 现状是硬编码 `danger-full-access`（patch 文件写死）。要做成设置页可选：
   但 read-only / workspace-write 在 Android 上会 fail-closed（没有 sandbox runner），选之前得给出警告/禁用。
3. **恢复真正的 sandbox（可选）** —— 交叉编译 `landlock-run` 的 android-arm64（需 NDK；Landlock 需要内核 5.13+，
   Android 16 满足）；或试 bwrap（大概率不行：非 root 下 user namespace 通常被禁）。做完才能重新用 workspace-write。
   体积 +几百 KB，价值是「给智能体一个有界的执行环境」，但会牺牲一部分开发便利。
4. **targetSdk 35 评估** —— 卡在 SELinux exec 限制，需要 linker 直调（`/system/bin/linker64` 显式加载）方案；工程量大。
5. **git-core 精裁** —— 只留仓库必需二进制（daemon/http-backend/imap-send 等可删），可再省 ~15MB raw。
6. **未做真机交互验证的边角**：SAF 导入流程、分享所选、全部导出、DshBridge 各 API 的 UI 触发
   （编译期全过，只是没逐个点过）。

**给后续动手的人（或我自己）**：payload 里任何「需要执行的工具」都要先确认权限模式开着，
否则就是 v2.12 之前那种「工具装了但门是锁的」——这次踩坑的完整机理见上面 v2.12 段。
