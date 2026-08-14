# prepare_dsh_tree.py — 可复现的 DSH 应用树裁剪管线（Android 版 payload 的第 2 步）

# 从 npm 全局安装的 DeepSeek Harness checkout 复制出一份裁剪后的纯 JS 依赖树，
# 供 payload/build_payload.py 打包进 APK：
#   1. 复制 checkout 本体（不含嵌套 node_modules）
#   2. 复制嵌套 node_modules
#   3. 删除 Android 上没有原生预编译产物的桌面专用包
#   4. 写入等价 stub（sharp / node-pty / dsh-sandbox-windows-acl）
#   5. 剥离 sourcemap / 类型声明 / 测试 / 文档
#   6. 应用 Android 文件系统补丁（link()→rename()，见 patch_android_fs.py）
#
# 用法: python payload/prepare_dsh_tree.py [DSH_checkout_路径]
# 默认 checkout: %APPDATA%\npm\node_modules\@deepseek-ai\dsh
import os
import shutil
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import patch_android_fs  # noqa: E402

DEFAULT_CHECKOUT = os.path.join(
    os.environ.get("APPDATA", ""), "npm", "node_modules", "@deepseek-ai", "dsh")

REMOVE_PACKAGES = [
    "koffi", "sharp", "node-pty",
    "@koromix", "@img",
    "@deepseek-ai/dsh-sandbox-windows-acl",
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
    "@deepseek-ai/dsh-sandbox-windows-acl": {
        "package.json": '{"name":"@deepseek-ai/dsh-sandbox-windows-acl","version":"1.0.0-stub","type":"module",'
                        '"main":"index.js","exports":{".":"./index.js","./runner":"./index.js",'
                        '"./package.json":"./package.json"}}',
        "index.js": (
            "export class AclWriteGrant {\n"
            "  static create() { throw new Error('windows-acl sandbox is unavailable on this platform'); }\n"
            "}\n"
            "export function assertTempRootOutsideWorkspace() {}\n"
            "export function tempWriteSid() { return ''; }\n"
            "export function workspaceWriteSid() { return ''; }\n"
        ),
    },
}

STRIP_DIR_NAMES = {"test", "tests", "__tests__", "spec", "bench", "benchmark",
                   "docs", "examples", "fixtures", "coverage"}


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
                if d.startswith("node-addon-landlock-run-") or (
                        d.startswith("node-addon-require-builtin-")
                        and d != "node-addon-require-builtin"):
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

    print("apply android fs patch ...")
    patch_android_fs.patch_root(dst)

    total = 0
    for dirpath, _, filenames in os.walk(nm):
        for fn in filenames:
            total += os.path.getsize(os.path.join(dirpath, fn))
    print("DONE ->", dst, "(%.1f MB node_modules)" % (total / 1048576))


if __name__ == "__main__":
    main()
