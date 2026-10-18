# prepare_dsh_tree.py — 可复现的 DSH 应用树裁剪管线（Android 版 payload 的第 2 步）

# 从 npm 全局安装的 DeepSeek Harness checkout 复制出一份裁剪后的纯 JS 依赖树，
# 供 payload/build_payload.py 打包进 APK：
#   1. 复制 checkout 本体（不含嵌套 node_modules）
#   2. 复制嵌套 node_modules
#   3. 删除 Android 上没有原生预编译产物的桌面专用包
#   4. 写入等价 stub（sharp / node-pty / dsh-sandbox-windows-acl）
#   5. 剥离 sourcemap / 类型声明 / 测试 / 文档
#   6. 有证据的定点清理（先 grep 引用，0 引用才删；见 DEV_ASSET_CANDIDATES）
#   7. 应用 Android 文件系统补丁（link()→rename()，见 patch_android_fs.py）
#   8. 末尾输出体积报告：payload/size_report.txt（top-50 大文件 + 清理证据）
#
# 用法: python payload/prepare_dsh_tree.py [DSH_checkout_路径]
# 默认 checkout: %APPDATA%\npm\node_modules\@deepseek-ai\dsh
import os
import shutil
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import patch_android_fs  # noqa: E402

DEFAULT_CHECKOUT = os.path.join(
    os.environ.get("APPDATA", ""), "npm", "node_modules", "@deepseek-ai", "dsh")

REMOVE_PACKAGES = [
    "koffi", "sharp", "node-pty",
    "@koromix", "@img",
    # 注意：@deepseek-ai/dsh-sandbox-windows-acl **不能移除也不能打 stub**。
    # 0.2.0-rc.2 的 dsh-sandbox-local 会 `import { registerAclDiagnosisSkill,
    # ACL_DIAGNOSIS_SKILL, AclSandbox, ... }` 它，stub 少了命名导出会让
    # sandbox 插件 import 失败 → tool-bash 一直 waiting for shell →
    # agent preset 无效（真机报「新建会话失败」）。它是纯 JS 包（~220KB），
    # koffi 走 createLazyRequire 且只在 win32 路径用到，Android 上安全。
    # 0.2.0 新增的原生/桌面平台产物：
    "sherpa-onnx-win-x64",                  # 语音输入原生运行时（实验功能）
    "node-addon-require-builtin-win32-x64-msvc",
    "@deepseek-ai/libreoffice-kit-win32-x64",  # 182MB LibreOffice 运行时
    # 0.2.1 新增：dsh-loader-smoke（仅开发期 smoke 测试）拉了 tsx → esbuild，
    # 且装的是 win32-x64 的 esbuild.exe（11MB）。运行时没有任何包 import tsx，
    # Android 上反而会因为平台二进制不存在而炸，直接删掉这条工具链。
    "@esbuild", "esbuild", "tsx",
]

STUBS = {
    "sharp": {
        "package.json": '{"name":"sharp","version":"0.35.3","type":"module","main":"index.js",'
                        '"exports":{".":{"import":"./index.js","default":"./index.js"}}}',
        "index.js": (
            "const unavailable = (what) => { throw new Error('sharp: ' + what + ' is unavailable on this platform'); };\n"
            "function sharp() {\n"
            "  return {\n"
            "    metadata: async () => { throw new Error('sharp: metadata unavailable'); },\n"
            "    raw: () => ({ toBuffer: async () => { throw new Error('sharp: decode unavailable'); } }),\n"
            "    toBuffer: async () => { throw new Error('sharp: encode unavailable'); },\n"
            "    resize: () => sharp(),\n"
            "    rotate: () => sharp(),\n"
            "  };\n"
            "}\n"
            "export default sharp;\n"
            "export { sharp };\n"
        ),
    },
    "node-pty": {
        "package.json": '{"name":"node-pty","version":"1.1.0","type":"module","main":"index.js",'
                        '"exports":{".":{"import":"./index.js","default":"./index.js"}}}',
        "index.js": (
            "export function spawn() { throw new Error('node-pty: terminal sessions are unavailable on this platform'); }\n"
            "const nodePty = { spawn };\n"
            "export default nodePty;\n"
        ),
    },
}

STRIP_DIR_NAMES = {"test", "tests", "__tests__", "spec", "bench", "benchmark",
                   "docs", "examples", "fixtures", "coverage"}

REPORT_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                           "size_report.txt")

# 定点清理候选：都是「只在开发/调试期才有用」的资产。删之前必须先在整棵裁剪树里
# grep 引用证据（import / require / 动态 import 的 URL、清单引用都算），
# 只要还剩一处运行时引用就保留 —— 宁保守勿激进。
# 注意这套方法的盲区：动态拼出来的模块名（如 @vscode/ripgrep 的
# `@vscode/ripgrep-${process.platform}-${arch}`）grep 不到，所以**从不**按
# 「0 引用」去删整个包，只删明确列在这里的单个资产文件。
# 每条记录 {path, why, tokens}：tokens 是检索证据用的字面量（文件名 + 无扩展名主干）。
DEV_ASSET_CANDIDATES = [
    {
        "path": "node_modules/@deepseek-ai/dsh-experimental-inspector/lib/devtools/"
                "entrypoints/lighthouse_worker/lighthouse_worker.js",
        "why": "Chrome DevTools 的 Lighthouse worker（只有跑审计面板时才会加载）",
        "tokens": ["lighthouse_worker.js", "lighthouse_worker"],
    },
    {
        "path": "node_modules/@mixmark-io/domino/.yarn/plugins/"
                "@yarnpkg/plugin-version.cjs",
        "why": "domino 包里误带出来的 Yarn 发布插件（与运行时无关）",
        "tokens": ["plugin-version.cjs", "@yarnpkg/plugin-version"],
    },
    {
        "path": "node_modules/.package-lock.json",
        "why": "npm 安装元数据，运行时无人读取",
        "tokens": [".package-lock.json"],
    },
    {
        "path": "package-lock.json",
        "why": "npm 安装元数据，运行时无人读取",
        "tokens": ["package-lock.json"],
    },
]

# 类型声明：node 运行时不会加载 .ts/.cts/.mts 声明文件，npm 包里它们只被
# package.json 的 "types" 字段引用（给 tsc / IDE 用）。原 strip_tree 只删了
# *.d.ts，漏了 .d.cts / .d.mts（0.2.1 的 SDK 大量使用）。
TYPE_DECL_SUFFIXES = (".d.ts", ".d.cts", ".d.mts")


def _read_text(path, limit=6 * 1024 * 1024):
    """读文本文件；二进制（前 4KB 有 NUL）或过大返回 None。"""
    try:
        if os.path.getsize(path) > limit:
            return None
        with open(path, "rb") as fh:
            head = fh.read(4096)
            if b"\x00" in head:
                return None
            data = head + fh.read()
    except OSError:
        return None
    return data.decode("utf-8", "ignore")


def find_references(root, tokens, exclude=None, max_hits=5):
    """在整棵裁剪树里找 tokens 的引用证据。返回 (hits, scanned)。

    hits 元素为 (相对路径, 命中次数)，最多 max_hits 条（够写进报告就行）。
    """
    hits = []
    scanned = 0
    exclude = os.path.abspath(exclude) if exclude else None
    for dirpath, _, filenames in os.walk(root):
        for fn in filenames:
            p = os.path.join(dirpath, fn)
            if exclude and os.path.abspath(p) == exclude:
                continue
            text = _read_text(p)
            if text is None:
                continue
            scanned += 1
            n = sum(text.count(t) for t in tokens)
            if n:
                hits.append((os.path.relpath(p, root).replace("\\", "/"), n))
                if len(hits) >= max_hits:
                    return hits, scanned
    return hits, scanned


def strip_type_declarations(root):
    """删类型声明（*.d.ts / *.d.cts / *.d.mts）。返回 (文件数, 字节数)。"""
    removed = 0
    freed = 0
    for dirpath, _, filenames in os.walk(root):
        for fn in filenames:
            if not fn.endswith(TYPE_DECL_SUFFIXES):
                continue
            p = os.path.join(dirpath, fn)
            try:
                size = os.path.getsize(p)
                os.remove(p)
                removed += 1
                freed += size
            except OSError:
                pass
    print("stripped type declarations:", removed, "files (%.1f MB)" % (freed / 1048576))
    return removed, freed


def purge_dev_assets(root):
    """有证据的定点删除：0 引用才删，有引用保留并把证据记进报告。"""
    records = []
    for cand in DEV_ASSET_CANDIDATES:
        rel = cand["path"]
        p = os.path.join(root, *rel.split("/"))
        if not os.path.isfile(p):
            continue
        size = os.path.getsize(p)
        hits, scanned = find_references(root, cand["tokens"], exclude=p)
        if hits:
            shown = ", ".join("%s(x%d)" % (h, n) for h, n in hits[:3])
            records.append({
                "path": rel, "size": size, "deleted": False, "why": cand["why"],
                "evidence": "被 %d 个文件引用: %s" % (len(hits), shown),
            })
            print("keep  : %s (%.2f MB) — 有引用: %s"
                  % (rel, size / 1048576, shown))
            continue
        try:
            os.remove(p)
        except OSError as exc:
            records.append({
                "path": rel, "size": 0, "deleted": False, "why": cand["why"],
                "evidence": "删除失败: %s" % exc,
            })
            print("keep  : %s — 删除失败: %s" % (rel, exc))
            continue
        records.append({
            "path": rel, "size": size, "deleted": True, "why": cand["why"],
            "evidence": "全树扫描 %d 个文本文件，0 处引用（检索词: %s）"
                        % (scanned, ", ".join(cand["tokens"])),
        })
        print("remove: %s (%.2f MB) — 0 引用（扫描 %d 个文本文件）"
              % (rel, size / 1048576, scanned))
    return records


def collect_sizes(root):
    files = []
    for dirpath, _, filenames in os.walk(root):
        for fn in filenames:
            p = os.path.join(dirpath, fn)
            try:
                files.append((os.path.getsize(p),
                              os.path.relpath(p, root).replace("\\", "/")))
            except OSError:
                pass
    files.sort(reverse=True)
    return files


def write_size_report(root, purge_records, type_stats):
    """写 payload/size_report.txt（清理证据 + top-50），并把前 20 条打出来。"""
    files = collect_sizes(root)
    total = sum(size for size, _ in files)
    lines = [
        "# dsh-mobile 裁剪树体积报告",
        "# 生成: " + time.strftime("%Y-%m-%d %H:%M:%S"),
        "# 树: " + root,
        "# 总文件数: %d   总大小: %.1f MB" % (len(files), total / 1048576),
        "",
        "## 定点清理（先 grep 引用证据，0 引用才删）",
        "# 判定口径：命中的是 import/require、动态 import 地址或资源清单 → 保留；"
        "纯字符串提及也算命中（宁可少删）。",
    ]
    for rec in purge_records:
        lines.append("- %s %s (%.2f MB) — %s — 用途: %s" % (
            "删除" if rec["deleted"] else "保留", rec["path"],
            rec["size"] / 1048576, rec["evidence"], rec["why"]))
    removed_n, removed_bytes = type_stats
    lines.append("- 删除 类型声明 *.d.ts/*.d.cts/*.d.mts 共 %d 个 (%.2f MB) — "
                 "证据: node 运行时不加载 .ts 声明文件，仅 package.json 的 "
                 "\"types\" 字段引用（tsc/IDE 用）" % (removed_n, removed_bytes / 1048576))
    lines.append("")
    lines.append("## Top 50 大文件（相对路径 + MB）")
    for i, (size, rel) in enumerate(files[:50], 1):
        lines.append("%2d. %-86s %7.2f MB" % (i, rel, size / 1048576))
    text = "\n".join(lines) + "\n"
    try:
        with open(REPORT_PATH, "w", encoding="utf-8", newline="\n") as fh:
            fh.write(text)
        print("report written:", REPORT_PATH)
    except OSError as exc:
        print("report write failed:", exc)
    print("---- size report (top 20 of %d files) ----" % len(files))
    for line in lines[:4]:
        print(line)
    for i, (size, rel) in enumerate(files[:20], 1):
        print("%2d. %-82s %7.2f MB" % (i, rel, size / 1048576))
    print("total files:", len(files), " total: %.1f MB" % (total / 1048576))
    return files


def strip_tree(root):
    removed = 0
    for dirpath, dirnames, filenames in os.walk(root):
        rel = os.path.relpath(dirpath, root)
        for d in list(dirnames):
            if d in STRIP_DIR_NAMES:
                shutil.rmtree(os.path.join(dirpath, d), ignore_errors=True)
                dirnames.remove(d)
        for fn in list(filenames):
            p = os.path.join(dirpath, fn)
            low = fn.lower()
            if (fn.endswith(".map") or fn.endswith(".d.ts") or low.endswith(".md")
                    or low.endswith(".markdown")
                    or fn.startswith(("README", "CHANGELOG", "CHANGES", "HISTORY",
                                      "LICENSE", "NOTICE", "AUTHORS", "CONTRIBUTING",
                                      "SECURITY"))):
                try:
                    os.remove(p)
                    removed += 1
                except OSError:
                    pass
    print("stripped", removed, "files")


def main():
    # Windows 控制台编码不一定是 UTF-8（cp936/cp437）：报告里含中文/长破折号，
    # 不能因为编码不支持就抛 UnicodeEncodeError 把构建打断。
    try:
        sys.stdout.reconfigure(errors="replace")
    except (AttributeError, ValueError):
        pass
    checkout = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_CHECKOUT
    if not os.path.isdir(os.path.join(checkout, "lib")):
        raise SystemExit("checkout not found (need lib/): " + checkout)
    dst = os.path.join(os.environ.get("TEMP", "/tmp"), "dshm-trim", "app")
    if os.path.isdir(dst):
        shutil.rmtree(dst, ignore_errors=True)
    os.makedirs(dst)

    print("copy checkout (no nested node_modules):", checkout)
    shutil.copytree(checkout, dst, ignore=shutil.ignore_patterns("node_modules"),
                    symlinks=True, dirs_exist_ok=True)

    print("copy nested node_modules ...")
    shutil.copytree(os.path.join(checkout, "node_modules"),
                    os.path.join(dst, "node_modules"), symlinks=True,
                    dirs_exist_ok=True)

    nm = os.path.join(dst, "node_modules")
    for pkg in REMOVE_PACKAGES:
        p = os.path.join(nm, *pkg.split("/"))
        if os.path.isdir(p):
            shutil.rmtree(p, ignore_errors=True)
            print("removed:", pkg)

    for pkg, files in STUBS.items():
        p = os.path.join(nm, *pkg.split("/"))
        os.makedirs(p, exist_ok=True)
        for fn, content in files.items():
            with open(os.path.join(p, fn), "w", encoding="utf-8", newline="\n") as f:
                f.write(content)
        print("stubbed:", pkg)

    # 平台预编译包一并移除（Android 上没有对应产物，JS 层已有回退）
    for scope in ("@deepseek-ai",):
        scopedir = os.path.join(nm, scope)
        if os.path.isdir(scopedir):
            for d in os.listdir(scopedir):
                is_platform_pkg = (
                    d.startswith("node-addon-landlock-run-")
                    or d.startswith("node-addon-system-")
                    or (d.startswith("node-addon-require-builtin-")
                        and d != "node-addon-require-builtin")
                    or (d.startswith("libreoffice-kit-") and d != "libreoffice-kit")
                )
                if is_platform_pkg:
                    shutil.rmtree(os.path.join(scopedir, d), ignore_errors=True)
                    print("removed platform pkg:", d)

    # v2.4: ripgrep 平台包。@vscode/ripgrep 按 process.platform-process.arch 解析
    # @vscode/ripgrep-<platform>-<arch>/bin/rg。注意：Android 上的 node 报告
    # process.platform === "android"（不是 "linux"），所以要提供
    # @vscode/ripgrep-android-arm64（同时保留 linux-arm64 以防 arch 探测差异）。
    # 用 Termux 前缀里的 aarch64 rg 构造该包；其余平台的 ripgrep 包在 Android
    # 上无用，一并移除。
    rg_bin = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                          'termux', 'usr', 'bin', 'rg')
    RG_PLATFORM_PKGS = ('ripgrep-android-arm64', 'ripgrep-linux-arm64')
    vscode_dir = os.path.join(nm, '@vscode')
    for d in list(os.listdir(vscode_dir)):
        if d.startswith("ripgrep-") and d not in RG_PLATFORM_PKGS:
            shutil.rmtree(os.path.join(vscode_dir, d), ignore_errors=True)
            print("removed foreign rg platform pkg:", d)
    for pkg in RG_PLATFORM_PKGS:
        rgdir = os.path.join(vscode_dir, pkg)
        os.makedirs(os.path.join(rgdir, 'bin'), exist_ok=True)
        shutil.copy2(rg_bin, os.path.join(rgdir, 'bin', 'rg'))
        with open(os.path.join(rgdir, 'package.json'), 'w', encoding='utf-8',
                  newline='\n') as f:
            f.write('{"name":"@vscode/ripgrep-%s","version":"15.2.0"}\n' % pkg.replace('ripgrep-', ''))
        print("created rg platform pkg: @vscode/" + pkg)

    strip_tree(nm)
    # v2.12：类型声明（含 .d.cts/.d.mts）+ 有证据的定点清理
    t_strip = time.time()
    type_stats = strip_type_declarations(dst)
    print("  type-decl strip %.1fs" % (time.time() - t_strip))
    t_purge = time.time()
    purge_records = purge_dev_assets(dst)
    print("  dev-asset purge %.1fs（首次读盘会被杀毒软件拖慢，属正常）"
          % (time.time() - t_purge))

    print("apply android fs patch ...")
    patch_android_fs.patch_root(dst)

    total = 0
    for dirpath, _, filenames in os.walk(nm):
        for fn in filenames:
            total += os.path.getsize(os.path.join(dirpath, fn))
    print("DONE ->", dst, "(%.1f MB node_modules)" % (total / 1048576))

    # v2.12：体积报告（top-50 大文件 + 清理证据），落 payload/size_report.txt
    write_size_report(dst, purge_records, type_stats)


if __name__ == "__main__":
    main()
