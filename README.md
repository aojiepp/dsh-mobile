# DSH Mobile

在 Android 手机上**独立运行完整 DeepSeek Harness（DSH）**的开源实现：一个原生 WebView 壳 + 内嵌 aarch64 Node.js 运行时 + 裁剪后的 DSH 依赖树。手机自己启动 DSH 服务（`127.0.0.1:3080`），不依赖电脑，可离线于 PC 使用。

> **状态：功能可用，UI 仍在优化中。** 核心链路（自启服务 → 对话 → agent 工具调用 → 会话落盘）已在真机端到端验证通过；v2.4 起 **bash / write / glob / grep 工具全部可用**（内置 GNU bash 5.3 + ripgrep 15.2，并修复了 Android 上 `link()` 被 SELinux 禁止导致的写文件失败）；v2.5 起内置**收纳箱**（设置页 → 收纳箱：按「工作台产物 / 会话记录」分类浏览，可提取到手机 Download 或删除）；界面细节继续迭代中，欢迎 issue / PR。

## 工作原理

```
┌───────────────────────────── DSH Mobile APK ─────────────────────────────┐
│  MainActivity (WebView 全屏)         DshServerService (前台服务)           │
│       │ 127.0.0.1:3080                    │ spawn                          │
│       ▼                                   ▼                               │
│  ┌─────────┐  首次运行解压   ┌──────────────────────────────────────────┐ │
│  │ WebView │◄───────────────│ payload/termux/usr/bin/node --expose-    │ │
│  └─────────┘                │   internals lib/bin.js web --port 3080   │ │
│                             │   (DSH_HOME = files/dsh-home)            │ │
│                             └──────────────────────────────────────────┘ │
└───────────────────────────────────────────────────────────────────────────┘
```

- **WebView 壳**：加载 `http://127.0.0.1:3080`，注入移动端补丁层（窄屏布局修复、侧边栏抽屉化、悬浮 ⚙ 菜单），不修改 DSH 前端源码。
- **内置服务**：Termux 官方仓库的 aarch64 Node + GNU bash + ripgrep 二进制及依赖 `.so`（RUNPATH 改写为 `$ORIGIN` 相对路径），由前台服务 spawn，进程随应用存活。
- **DSH 依赖树**：从 npm 全局安装的 DSH checkout 裁剪（去掉桌面专用原生模块并打 stub、剥离 sourcemap/文档、注入 Android 文件系统补丁与 ripgrep aarch64 平台包），功能与桌面版一致。
- **双模式**：设置页可切换「本机内置服务」/「连接电脑上的 DSH」（后者保留原 adb reverse / 局域网 IP 方式）。

## 目录结构

```
dsh-mobile/
├── app/src/main/
│   ├── java/com/dsh/mobile/      # MainActivity / DshServerService / SettingsActivity / ArtifactsActivity(收纳箱)
│   ├── assets/payload.zip        # 构建产物（不入库，由 payload 管线生成）
│   └── res/                      # 布局、主题（含夜间模式）
├── payload/
│   ├── prepare_dsh_tree.py       # 步骤2：裁剪 DSH 依赖树（删原生模块/打 stub/剥离）
│   ├── patch_android_fs.py       # Android 文件系统补丁（link()→rename()）
│   ├── payload_tools.py          # .deb 解包 + ELF DT_NEEDED 解析
│   ├── extract_termux.py         # 步骤1：解包 node .deb 与依赖 .so 到 termux 前缀
│   ├── patch_runpath.py          # RUNPATH 改写（Termux 绝对路径 → $ORIGIN）
│   ├── check_runpath.py          # 校验工具
│   └── build_payload.py          # 步骤3：打包 payload.zip
└── tools/
    ├── build_manual.py           # 零网络手工构建链（aapt2→javac→d8→zipalign→apksigner）
    ├── install_apk.ps1           # adb 安装 + vivo 安装确认弹窗自动点击
    ├── cdp_eval.ps1 / .mjs       # 通过 WebView DevTools 执行页面 JS 的验证工具
    └── pull_workspace.ps1        # 电脑端导出 agent 产物（备用；手机端用收纳箱即可）
```

## 构建（零 gradle，全手工链）

需要：Windows、JDK 17、Android SDK（`C:\Android`，build-tools 34 + android-34）、Python 3、网络（仅首次下载 Termux .deb）。

```powershell
# 1. 下载并解包 Termux 运行时（node + bash + ripgrep + 依赖 .so + CA 证书）
python payload\extract_termux.py extract
#    .deb 下载地址见 extract_termux.py 顶部注释；也可手动放入 payload\debs\

# 2. 裁剪 DSH 依赖树（从 npm 全局 checkout 复制并瘦身，约 87MB）
python payload\prepare_dsh_tree.py C:\Users\<you>\AppData\Roaming\npm\node_modules\@deepseek-ai\dsh

# 3. 打包 payload.zip 并放入 assets
python payload\build_payload.py
copy payload\payload.zip app\src\main\assets\payload.zip

# 4. 构建 APK（构建目录必须是 ASCII 路径，中文路径会弄坏 aapt2）
python tools\build_manual.py
# 产物: build\apk\dsh-mobile-v2.5-debug.apk
```

构建脚本首次运行会自动生成 `tools\debug.keystore`（已 gitignore，请勿提交）。

## 安装与使用

```powershell
adb install -r build\apk\dsh-mobile-v2.5-debug.apk
```

- 真机（vivo/国产 ROM）安装会弹确认框，`tools\install_apk.ps1` 会通过 uiautomator 自动点击「已了解风险」+「继续安装」。
- 首次启动解压 payload 约 1-3 分钟，之后秒开。
- **API Key**：App 设置页填写（写入 `$DSH_HOME/.env`，权限 600），或启动后在 DSH 界面「设置 → 模型」里存储（写入 `$DSH_HOME/.credentials.yaml`）。
- **收纳箱**：设置页 → 收纳箱 —— 智能体产物按「工作台产物 / 会话记录」分类列出，可「提取」到手机 `Download/dsh-export/`（文件管理器可见）或直接「删除」；应用私有目录普通文件管理器看不到，这里是唯一入口。
- 通知权限用于前台服务保活；进程随应用关闭而退出。

## 已知限制与设计取舍

| 项 | 说明 |
|---|---|
| targetSdk **28** | Android 10+ 对 targetSdk≥29 的应用禁止执行私有目录二进制（SELinux exec）；28 是 Termux 同款解法（自分发应用，无 Play 上架要求） |
| 硬链接 `link()` 不可用 | Android SELinux 禁止应用硬链接；DSH 会话/附件落盘的原子发布、write 工具的新建文件路径均已改写为 `rename()`（见 `payload/patch_android_fs.py`） |
| bash / glob / grep | v2.4 已内置 GNU bash 5.3 与 ripgrep 15.2（Termux aarch64），并注入了 `@vscode/ripgrep-android-arm64` 平台包（Android 的 node 报告 `process.platform === "android"`，非 linux）；四工具真机 PASS |
| 交互式终端 / 图像附件 / 沙箱隔离 | 仍无对应原生原语（node-pty/sharp/landlock 无 arm64 产物），已打 stub，调用时干净报错而非崩溃；bash 工具不受影响 |
| 体积 | APK 约 64MB，解压后占用约 300MB |
| 服务生命周期 | node 进程随 App 进程存活；被系统杀掉后重新打开 App 会自动拉起 |
| UI | 移动端补丁层已修主要布局冲突（输入栏/侧边栏/详情抽屉/悬浮按钮），细节仍在打磨 |

## 许可

本项目采用 [MIT License](LICENSE)。

第三方组件按各自许可分发：Termux nodejs 二进制来自 [Termux 官方仓库](https://packages.termux.dev/)；DeepSeek Harness 依赖树来自 npm 全局安装的 checkout，仅供本工具运行时使用，版权归其各自作者。
