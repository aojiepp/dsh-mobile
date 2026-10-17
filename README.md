# DSH Mobile

在 Android 手机上**独立运行完整 DeepSeek Harness（DSH）**的开源实现：一个原生 WebView 壳 + 内嵌 aarch64 Node.js 运行时 + 裁剪后的 DSH 依赖树。手机自己启动 DSH 服务（`127.0.0.1:3080`），不依赖电脑，可离线于 PC 使用。

> **设计原则：尽量保持 DSH 原版风格。** 手机上跑的就是原样的 DSH 前端与后端，本项目**不重写界面、不做「精简版 / 移动特供版」**：只在 WebView 层注入一层窄屏补丁修布局冲突（输入栏被顶出屏幕、侧边栏 / 详情抽屉出界等），其余一律跟随 DSH 自身；原生壳只负责系统级能力（前台服务保活、payload 解压、通知、收纳箱）。DSH 内核升级，界面就跟着一起升级，不维护 UI 分叉。

> **状态：功能可用，UI 仍在优化中。** 核心链路（自启服务 → 对话 → agent 工具调用 → 会话落盘）已在真机端到端验证通过；v2.4 起 **bash / write / glob / grep 工具全部可用**（内置 GNU bash 5.3 + ripgrep 15.2，并修复了 Android 上 `link()` 被 SELinux 禁止导致的写文件失败）；v2.5 起内置**收纳箱**（设置页 → 收纳箱：按「工作台产物 / 会话记录」分类浏览，可提取到手机 Download 或删除）；v2.6 起原生界面**统一为 DeepSeek 灰色单风格**（灰底白卡 + DeepSeek 蓝 `#4D6BFE`，深浅合一只保留一套，夜间模式不再切换）；v2.7 起「连接电脑」模式**修复**：一键连接检测、针对性错误提示、断连自动重连（3s 起指数退避，上限 30s）+ 随仓库提供 `tools\pc_bridge.bat` 隧道自动维持工具；v2.8 起内嵌内核升级为 **DSH 0.2.0-rc.2**（适配 0.2.0 的 web 启动 token 鉴权与新的原生依赖，升级安装后自动重解压 payload）；v2.9 起**首启解压有真实进度条**（拷贝 / 解压 N/M 文件 / 校验三段，百分比按 zip 中央目录的真实解压尺寸算），解压前做**存储空间预检**、完成后做**完整性校验**，失败把原因直接显示并提供「重新解压」；解压期间**禁止重启内置服务**（避免两条命令打架），并加了**空白页兜底**。v2.9.1–v2.9.3 在真机（vivo / Android 16 / arm64）上修通了内置服务启动（OpenSSL 配置路径、sandbox 插件缺失导出两个坑，详见 `AGENTS.md`），并把图标换成蓝色大肥鱼的简笔 SVG。**v2.10 起内嵌内核跟随桌面版升级到 DSH 0.2.1-rc.2**：payload 管线新增依赖去重步骤（npm 在 50 个包里各塞了一份 `zod@4.4.3`，去重省下约 140MB 解压体积）、裁掉 0.2.1 新引入的 win32 版 `tsx/esbuild` 工具链，已在真机验证「升级安装 → 自动重解压 → 服务启动 → 新建会话 → 正常对话」。界面细节继续迭代中，欢迎 issue / PR。

## 工作原理

```
┌───────────────────────────── DSH Mobile APK ─────────────────────────────┐
│  MainActivity (WebView 全屏)         DshServerService (前台服务)           │
│       │ 127.0.0.1:3080                    │ spawn                          │
│       ▼                                   ▼                               │
│  ┌─────────┐  首次运行解压   ┌──────────────────────────────────────────┐ │
│  │ WebView │◄───────────────│ payload/termux/usr/bin/node --expose-    │ │
│  └─────────┘                │   internals lib/bin.js web --port 3080   │ │
│                             │   --no-open  (DSH_HOME = files/dsh-home) │ │
│                             └──────────────────────────────────────────┘ │
└───────────────────────────────────────────────────────────────────────────┘
```

- **WebView 壳**：加载 `http://127.0.0.1:3080`，注入移动端补丁层（窄屏布局修复、侧边栏抽屉化、悬浮 ⚙ 菜单），不修改 DSH 前端源码。DSH 0.2.0 起 web 服务有启动 token 鉴权：App 从服务日志解析 `dsh web:` 行拿到带 token 的 URL 完成首次认证（换取持久 cookie），401 时自动重取一次。
- **内置服务**：Termux 官方仓库的 aarch64 Node + GNU bash + ripgrep 二进制及依赖 `.so`（RUNPATH 改写为 `$ORIGIN` 相对路径），由前台服务 spawn，进程随应用存活。
- **DSH 依赖树**：从 npm（镜像）拉取的 DSH 依赖树经舞台拼装后裁剪（去掉桌面专用原生模块并打 stub、剥离 sourcemap/文档、注入 Android 文件系统补丁与 ripgrep aarch64 平台包），功能与桌面版一致。
- **双模式**：设置页可切换「本机内置服务」/「连接电脑上的 DSH」；后者提供一键连接检测与断连自动重连，推荐 USB + adb reverse 隧道（见下文「连接电脑」）。

## 目录结构

```
dsh-mobile/
├── app/src/main/
│   ├── java/com/dsh/mobile/      # MainActivity / DshServerService / SettingsActivity / ArtifactsActivity(收纳箱)
│   ├── assets/payload.zip        # 构建产物（不入库，由 payload 管线生成）
│   └── res/                      # 布局、主题（含夜间模式）
├── design/
│   ├── ic_launcher.svg           # 应用图标真源（蓝色大肥鱼 / 鲸鱼娘，简笔扁平）
│   ├── svg2vector.py             # SVG → VectorDrawable（生成 res/drawable/ic_launcher_fg.xml）
│   └── vector2svg.py             # 反渲染自检（改过转换器后跑，确认颜色/坐标）
├── payload/
│   ├── prepare_dsh_tree.py       # 步骤2：裁剪 DSH 依赖树（删原生模块/打 stub/剥离）
│   ├── dedupe_node_modules.py    # 步骤2.5：去重 npm 嵌套大副本（zod 等，0.2.1 起）
│   ├── verify_zod_resolution.py  # 去重前后每个 importer 解析版本一致性校验
│   ├── patch_android_fs.py       # Android 补丁（link()→rename()、flock 放宽、require-builtin 回退）
│   ├── payload_tools.py          # .deb 解包 + ELF DT_NEEDED 解析
│   ├── extract_termux.py         # 步骤1：解包 node .deb 与依赖 .so 到 termux 前缀
│   ├── patch_runpath.py          # RUNPATH 改写（Termux 绝对路径 → $ORIGIN）
│   ├── check_runpath.py          # 校验工具
│   └── build_payload.py          # 步骤3：打包 payload.zip
└── tools/
    ├── build_manual.py           # 零网络手工构建链（aapt2→javac→d8→zipalign→apksigner）
    ├── install_apk.ps1           # adb 安装 + vivo 安装确认弹窗自动点击
    ├── pc_bridge.bat / .ps1      # 「连接电脑」USB 隧道自动维持（adb reverse 每 3 秒重建）
    ├── cdp_eval.ps1 / .mjs       # 通过 WebView DevTools 执行页面 JS 的验证工具
    └── pull_workspace.ps1        # 电脑端导出 agent 产物（备用；手机端用收纳箱即可）
```

## 构建（零 gradle，全手工链）

需要：Windows、JDK 17、Android SDK（`C:\Android`，build-tools 34 + android-34）、Python 3、网络（仅首次下载 Termux .deb）。

```powershell
# 1. 下载并解包 Termux 运行时（node + bash + ripgrep + 依赖 .so + CA 证书）
python payload\extract_termux.py extract
#    .deb 下载地址见 extract_termux.py 顶部注释；也可手动放入 payload\debs\

# 2. 拉取 DSH 依赖树并按「包本体 + 内嵌 node_modules」摆好舞台
#    注意是 tarball 里直接 npm install（全量，含 devDependencies）：桌面版 host 侧插件
#    （host-webserver / llm-deepseek / sandbox-local 等）都挂在 dsh 包的 devDependencies 里，
#    --omit=dev 会漏装，web profile 起不来。
mkdir %TEMP%\dshm-stage; cd %TEMP%\dshm-stage
npm pack @deepseek-ai/dsh@0.2.1-rc.2
tar -xzf deepseek-ai-dsh-0.2.1-rc.2.tgz
move package dsh
cd dsh; npm install --no-audit --no-fund
cd <本仓库>\dsh-mobile

# 3. 裁剪依赖树（删桌面原生包/打 stub/剥离/打 Android 补丁）
python payload\prepare_dsh_tree.py %TEMP%\dshm-stage\dsh

# 4. 去重嵌套大副本（0.2.1 起 npm 在 50 个包下各塞一份 zod@4.4.3，约 140MB）
python payload\dedupe_node_modules.py %TEMP%\dshm-trim\app

# 5. 打包 payload.zip 并放入 assets（约 87MB zip / 269MB 解压后）
python payload\build_payload.py
copy payload\payload.zip app\src\main\assets\payload.zip

# 6. 构建 APK（构建目录必须是 ASCII 路径，中文路径会弄坏 aapt2）
python tools\build_manual.py
# 产物: build\apk\dsh-mobile-v2.10-debug.apk
```

换内核时**必须 bump `tools\build_manual.py` 里的 `VERSION_CODE`**（payload 完成标记按 versionCode 命名，不 bump 手机不会重解压）。

构建脚本首次运行会自动生成 `tools\debug.keystore`（已 gitignore，请勿提交）。

## 发布（推送到 GitHub）

`ghproxy.net` 只是**只读镜像**：用它 push 会回 `Invalid username or token`（代理把请求转给 GitHub，但凭证对不上）。推送必须直连 github.com：

```powershell
# 1) 确保能直连 GitHub（本机需要先起代理，例如 Clash Verge 的 TUN/系统代理）
# 2) 用带 :443 的 URL 推送，绕开全局 .gitconfig 里的 ghproxy insteadOf 改写
git push https://github.com:443/aojiepp/dsh-mobile.git main:main
```

- `https://github.com:443/...` 不会命中 `url.https://ghproxy.net/https://github.com/.insteadOf`（改写只匹配 `https://github.com/` 前缀），同时 GCM 仍按 host `github.com` 找到已存 token。
- 推送前可先用 `curl https://api.github.com/repos/aojiepp/dsh-mobile` 确认直连可用。
- 推完把代理**正常退出**（托盘 → 退出），别强杀，否则内核/TUN/系统代理会残留。

## 安装与使用

```powershell
adb install -r build\apk\dsh-mobile-v2.10-debug.apk
```

- 真机（vivo/国产 ROM）安装会弹确认框，`tools\install_apk.ps1` 会通过 uiautomator 自动点击「已了解风险」+「继续安装」。
- 首次启动解压 payload 约 1-3 分钟，之后秒开。**升级安装**（如 v2.9 → v2.10）会自动检测版本并重新解压 payload，同样约 1-3 分钟。**v2.9 起解压过程有进度条**（标题/通知栏都会显示百分比与当前文件数），失败会直接给出原因（如「存储空间不足：需要约 290MB，当前可用 60MB」）并提供「重新解压」按钮。
- **空间要求**：解压后运行时约 270MB；首次解压期间还要放一份临时 zip（约 87MB），建议手机预留 **450MB 以上**空闲空间。
- **API Key**：App 设置页填写（写入 `$DSH_HOME/.env`，权限 600），或启动后在 DSH 界面「设置 → 模型」里存储（写入 `$DSH_HOME/.credentials.yaml`）。
- **收纳箱**：设置页 → 收纳箱 —— 智能体产物按「工作台产物 / 会话记录」分类列出，可「提取」到手机 `Download/dsh-export/`（文件管理器可见）或直接「删除」；应用私有目录普通文件管理器看不到，这里是唯一入口。
- 通知权限用于前台服务保活；进程随应用关闭而退出。

## 连接电脑（模式二）

电脑上的 DSH Web **默认只监听本机 127.0.0.1**（安全设计：0.0.0.0 绑定被明确拒绝，避免把控制能力暴露给网络），所以手机连电脑有两条路：

**① USB + adb reverse（推荐，最稳）**

1. 手机用数据线连电脑（adb 可用）；
2. 电脑执行 `adb reverse tcp:3080 tcp:3080`，或**双击 `tools\pc_bridge.bat`** 常驻维持隧道（设备重插/adb 重启后自动重建）；
3. App 设置页模式选「连接电脑上的 DSH」，服务地址保持 `http://127.0.0.1:3080`，点「测试连接」应显示 ✅；
4. 隧道断开时 App 显示针对性错误提示并**自动重连**（3s 起指数退避，上限 30s），无需手动刷新。

**② 无线局域网（需要电脑端配合）**

电脑端重启 DSH Web 时绑定局域网地址（会绕过「仅本机」安全保护，仅在可信网络使用）：

```
dsh web --host <电脑局域网IP> --trusted-host <电脑局域网IP>:3080
```

手机填 `http://<电脑局域网IP>:3080`。若直连不通，检查电脑防火墙是否放行 3080 端口。

> 设置页的「测试连接」会把失败原因翻译成人话（拒绝/超时/解析失败/非 DSH 服务），排查时以它为准。

## 已知限制与设计取舍

| 项 | 说明 |
|---|---|
| targetSdk **28** | Android 10+ 对 targetSdk≥29 的应用禁止执行私有目录二进制（SELinux exec）；28 是 Termux 同款解法（自分发应用，无 Play 上架要求） |
| 硬链接 `link()` 不可用 | Android SELinux 禁止应用硬链接；DSH 会话/附件落盘的原子发布、write 工具的新建文件路径均已改写为 `rename()`（见 `payload/patch_android_fs.py`） |
| 会话写锁 flock | 0.2.0 起会话落盘用原生 flock 加锁，Android 无对应预编译产物；补丁在 `ERR_FLOCK_UNSUPPORTED_PLATFORM` 时按无锁放行（手机端单进程单用户，放宽可接受） |
| node-addon-require-builtin | 0.2.0 的插件路由拦截需读取 Node 内部 loader，官方途径是原生 addon（无 Android 产物）；补丁改为走 `--expose-internals` 直接 require（与 cordis-plugin-loader 的官方回退同款），功能无退化 |
| web 启动 token | 0.2.0 起 `dsh web` 需带 token 的 URL 首次认证换 cookie；本机模式 App 自动从服务日志取 token（401 自动重试一次）；「连接电脑」模式若对端也是 0.2.0+，需把电脑端 `dsh web` 打印的完整带 token URL 填进服务地址 |
| bash / glob / grep | v2.4 已内置 GNU bash 5.3 与 ripgrep 15.2（Termux aarch64），并注入了 `@vscode/ripgrep-android-arm64` 平台包（Android 的 node 报告 `process.platform === "android"`，非 linux）；四工具真机 PASS |
| 首启解压 | v2.9 起有真实进度（拷贝/解压/校验三段）、空间预检、完整性校验与失败原因；解压中「重启内置服务」会被服务端忽略（UI 同时置灰），避免解压线程与重启打架 |
| 交互式终端 / 图像附件 / 沙箱隔离 | 仍无对应原生原语（node-pty/sharp/landlock 无 arm64 产物），已打 stub，调用时干净报错而非崩溃；bash 工具不受影响 |
| 体积 | APK 约 83MB，解压后运行时约 270MB（首解压期间额外一份 ~87MB 的 zip，建议预留 450MB+） |
| 服务生命周期 | node 进程随 App 进程存活；被系统杀掉后重新打开 App 会自动拉起 |
| 连接电脑 | 电脑端 DSH Web 默认仅监听 127.0.0.1（上游安全设计）；手机经 adb reverse 隧道接入，App 内置连接检测 + 断连自动重连，`tools\pc_bridge.bat` 维持隧道 |
| UI | 移动端补丁层已修主要布局冲突（输入栏/侧边栏/详情抽屉/悬浮按钮）；原生界面（设置/收纳箱/菜单/启动页）统一 DeepSeek 灰色单风格，深浅模式同一套 |

## 许可

本项目采用 [MIT License](LICENSE)。

第三方组件按各自许可分发：Termux nodejs 二进制来自 [Termux 官方仓库](https://packages.termux.dev/)；DeepSeek Harness 依赖树来自 npm 官方 registry（经镜像加速），仅供本工具运行时使用，版权归其各自作者。
