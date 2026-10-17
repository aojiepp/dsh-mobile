# dsh-mobile 项目记忆

Android 上独立跑完整 DSH 的壳工程。构建链、payload 管线、真机踩坑都记在这里。

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
- 用户数据：`files/dsh-home`（会话/凭据/工作区，升级与重解压都不动它）
- 服务日志：`files/dsh-server.log`（node 的 stdout/stderr，设置页「查看服务日志」也读它）
