#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""compile_check.py — 并行子代理专用的编译检查（不跑完整构建、不碰共享目录）。

完整构建 build_manual.py 用固定共享临时目录，禁止并行；本脚本每个工作流用
独立临时目录（%TEMP%\\dshm-cc-<name>），跳过 assets 拷贝与 dex/签名，只验证：
  aapt2 compile -> aapt2 link（生成 R + BuildInfo）-> javac 全量编译
用法:
  python tools/compile_check.py --name <workstream> [--release]
退出码 0 = 编译通过。
"""
import os
import shutil
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import build_manual as bm  # noqa: E402


def run(args):
    print(">>", " ".join(str(a) for a in args))
    r = subprocess.run(args, capture_output=True)
    if r.returncode != 0:
        print("!!! FAILED rc=%d" % r.returncode)
        print((r.stdout or b"").decode("utf-8", "replace")[-3000:])
        print((r.stderr or b"").decode("utf-8", "replace")[-3000:])
        sys.exit(1)
    return r


def main():
    name = "anon"
    release = "--release" in sys.argv
    for i, a in enumerate(sys.argv):
        if a == "--name" and i + 1 < len(sys.argv):
            name = sys.argv[i + 1]
    tmp = os.path.join(os.environ.get("TEMP") or ".", "dshm-cc-" + name)
    shutil.rmtree(tmp, ignore_errors=True)
    os.makedirs(tmp)
    app = os.path.join(tmp, "app")
    # assets（83MB payload.zip）与编译无关，跳过拷贝
    shutil.copytree(bm.SRC, app, ignore=shutil.ignore_patterns("assets"))
    bm.prepare_manifest(app, release)

    compiled = os.path.join(tmp, "compiled.zip")
    run([bm.AAPT2, "compile", "--dir", os.path.join(app, "res"), "-o", compiled])

    gen = os.path.join(tmp, "gen")
    os.makedirs(gen)
    run([bm.AAPT2, "link", "-o", os.path.join(tmp, "base.apk"),
         "-I", bm.PLATFORM_JAR,
         "--manifest", os.path.join(app, "AndroidManifest.xml"),
         "--java", gen,
         "--min-sdk-version", "26",
         "--target-sdk-version", "28",
         "--version-code", str(bm.VERSION_CODE),
         "--version-name", bm.VERSION_NAME,
         compiled])
    bm.write_build_info(gen, release)

    classes = os.path.join(tmp, "classes")
    os.makedirs(classes)
    sources = []
    for base in (gen, os.path.join(app, "java")):
        for root, _, files in os.walk(base):
            for f in files:
                if f.endswith(".java"):
                    sources.append(os.path.join(root, f))
    srclist = os.path.join(tmp, "sources.txt")
    with open(srclist, "w", encoding="utf-8") as fh:
        fh.write("\n".join(sources))
    run([bm.JAVAC, "-encoding", "UTF-8", "-source", "8", "-target", "8",
         "-nowarn", "-cp", bm.PLATFORM_JAR, "-d", classes, "@" + srclist])
    print("COMPILE CHECK OK [%s] (%d sources)" % (name, len(sources)))


if __name__ == "__main__":
    main()
