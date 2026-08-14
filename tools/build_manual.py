#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""DSH Mobile 手工构建链（零网络）：
aapt2 compile -> aapt2 link -> javac17 -> d8 -> zip 合并 dex -> zipalign -> apksigner
构建全程在 ASCII 临时目录进行（中文路径会静默弄坏 aapt2）。
用法：python tools/build_manual.py
"""
import os
import sys
import shutil
import subprocess
import zipfile

SDK = r"C:\Android"
BT = os.path.join(SDK, "build-tools", "34.0.0")
PLATFORM_JAR = os.path.join(SDK, "platforms", "android-34", "android.jar")
AAPT2 = os.path.join(BT, "aapt2.exe")
ZIPALIGN = os.path.join(BT, "zipalign.exe")
APKSIGNER_JAR = os.path.join(BT, "lib", "apksigner.jar")
D8_JAR = os.path.join(BT, "lib", "d8.jar")

VERSION_CODE = 22
VERSION_NAME = "2.6"
APK_NAME = "dsh-mobile-v%s-debug.apk" % VERSION_NAME

JAVA_HOME = os.environ.get("JAVA_HOME", "")
JAVAC = os.path.join(JAVA_HOME, "bin", "javac.exe") if JAVA_HOME else "javac"
KEYTOOL = os.path.join(JAVA_HOME, "bin", "keytool.exe") if JAVA_HOME else "keytool"

PROJECT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(PROJECT, "app", "src", "main")
OUT = os.path.join(PROJECT, "build", "apk")
KS = os.path.join(PROJECT, "tools", "debug.keystore")
STORE_PASS = "dshmobile"
TMP = os.path.join(os.environ.get("TEMP") or ".", "dshmobile-build")


def run(args):
    print(">>", " ".join(str(a) for a in args))
    r = subprocess.run(args, capture_output=True)
    if r.returncode != 0:
        out = (r.stdout or b"").decode("utf-8", "replace")
        err = (r.stderr or b"").decode("utf-8", "replace")
        print("!!! FAILED rc=%d" % r.returncode)
        print("--- stdout ---")
        print(out[-4000:])
        print("--- stderr ---")
        print(err[-4000:])
        with open(os.path.join(TMP, "last_fail.out"), "wb") as f:
            f.write(r.stdout or b"")
        with open(os.path.join(TMP, "last_fail.err"), "wb") as f:
            f.write(r.stderr or b"")
        print("raw output saved to: %s\\last_fail.{out,err}" % TMP)
        sys.exit(1)
    return r


def main():
    shutil.rmtree(TMP, ignore_errors=True)
    os.makedirs(TMP)
    app = os.path.join(TMP, "app")
    shutil.copytree(SRC, app)
    print("stage 0: sources copied to", app)

    # 1. aapt2 compile resources
    compiled = os.path.join(TMP, "compiled.zip")
    run([AAPT2, "compile", "--dir", os.path.join(app, "res"), "-o", compiled])

    # 2. aapt2 link
    gen = os.path.join(TMP, "gen")
    os.makedirs(gen)
    base_apk = os.path.join(TMP, "base.apk")
    link_args = [AAPT2, "link", "-o", base_apk,
         "-I", PLATFORM_JAR,
         "--manifest", os.path.join(app, "AndroidManifest.xml"),
         "--java", gen,
         "--min-sdk-version", "26",
         # targetSdk 28：与 Termux 同款策略 —— Android 10+ 对 targetSdk>=29 的
         # 应用禁止执行私有目录内二进制（SELinux exec 限制），28 放行。
         "--target-sdk-version", "28",
         "--version-code", str(VERSION_CODE),
         "--version-name", VERSION_NAME]
    assets = os.path.join(app, "assets")
    if os.path.isdir(assets):
        link_args += ["-A", assets]
    link_args.append(compiled)
    run(link_args)

    # 3. javac17
    classes = os.path.join(TMP, "classes")
    os.makedirs(classes)
    sources = []
    for root, _, files in os.walk(gen):
        for f in files:
            if f.endswith(".java"):
                sources.append(os.path.join(root, f))
    for root, _, files in os.walk(os.path.join(app, "java")):
        for f in files:
            if f.endswith(".java"):
                sources.append(os.path.join(root, f))
    srclist = os.path.join(TMP, "sources.txt")
    with open(srclist, "w", encoding="utf-8") as fh:
        fh.write("\n".join(sources))
    run([JAVAC, "-encoding", "UTF-8", "-source", "8", "-target", "8",
         "-nowarn", "-cp", PLATFORM_JAR, "-d", classes, "@" + srclist])

    # 4. d8 -> classes.dex（先打包成 jar 再喂 d8，兼容不支持目录输入的 d8 版本）
    classes_jar = os.path.join(TMP, "classes.jar")
    with zipfile.ZipFile(classes_jar, "w", zipfile.ZIP_DEFLATED) as zf:
        for root, _, files in os.walk(classes):
            for f in files:
                full = os.path.join(root, f)
                arc = os.path.relpath(full, classes).replace("\\", "/")
                zf.write(full, arc)
    dexdir = os.path.join(TMP, "dex")
    os.makedirs(dexdir)
    run(["java", "-cp", D8_JAR, "com.android.tools.r8.D8",
         "--lib", PLATFORM_JAR, "--min-api", "26", "--output", dexdir,
         classes_jar])

    # 5. merge classes.dex into apk
    with zipfile.ZipFile(base_apk, "a", zipfile.ZIP_DEFLATED) as zf:
        zf.write(os.path.join(dexdir, "classes.dex"), "classes.dex")

    # 6. zipalign
    aligned = os.path.join(TMP, "aligned.apk")
    run([ZIPALIGN, "-f", "-p", "4", base_apk, aligned])

    # 7. debug keystore（首次自动生成）
    if not os.path.exists(KS):
        run([KEYTOOL, "-genkeypair", "-v", "-keystore", KS,
             "-alias", "dshmobile", "-keyalg", "RSA", "-keysize", "2048",
             "-validity", "10000", "-storepass", STORE_PASS,
             "-keypass", STORE_PASS,
             "-dname", "CN=DSH Mobile Dev, OU=Dev, O=Dev, L=Dev, ST=Dev, C=CN"])

    # 8. apksigner
    os.makedirs(OUT, exist_ok=True)
    final = os.path.join(OUT, APK_NAME)
    run(["java", "-jar", APKSIGNER_JAR, "sign",
         "--ks", KS, "--ks-pass", "pass:" + STORE_PASS,
         "--ks-key-alias", "dshmobile", "--key-pass", "pass:" + STORE_PASS,
         "--out", final, aligned])

    size = os.path.getsize(final)
    print("")
    print("BUILD OK: %s (%.2f MB)" % (final, size / 1024.0 / 1024.0))


if __name__ == "__main__":
    main()
