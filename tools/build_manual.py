#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""DSH Mobile 手工构建链（零网络）：
aapt2 compile -> aapt2 link -> javac17 -> d8 -> zip 合并 dex -> zipalign -> apksigner
构建全程在 ASCII 临时目录进行（中文路径会静默弄坏 aapt2）。

用法：
  python tools/build_manual.py            # debug 构建（暂存 manifest 注入 debuggable=true）
  python tools/build_manual.py --release  # release 构建（无 debuggable，BuildInfo.DEBUG=false）

签名口令读取顺序：tools/signing.properties（gitignored，不上库）>
环境变量 DSHM_STORE_PASS / DSHM_KEY_PASS > 内置默认 "dshmobile"（打印警告）。
注意：此脚本的临时目录是共享的 %TEMP%\\dshmobile-build，禁止并行跑两个实例；
并行子代理的语法检查请用 tools/compile_check.py。
"""
import datetime
import hashlib
import os
import re
import shutil
import subprocess
import sys

SDK = r"C:\Android"
BT = os.path.join(SDK, "build-tools", "34.0.0")
PLATFORM_JAR = os.path.join(SDK, "platforms", "android-34", "android.jar")
AAPT2 = os.path.join(BT, "aapt2.exe")
ZIPALIGN = os.path.join(BT, "zipalign.exe")
APKSIGNER_JAR = os.path.join(BT, "lib", "apksigner.jar")
# d8 用 36.0.0：34.0.0 自带的 R8 8.2.2 dex 内部类时会 NPE（DexString 空指针 bug），
# 新版已修复；aapt2/zipalign/apksigner 仍用 34.0.0 不动
D8_JAR = os.path.join(SDK, "build-tools", "36.0.0", "lib", "d8.jar")

# versionCode 29：v2.11（安全基线 + 开发工具链 + 收纳箱重做），payload 变了必须 bump
# （完成标记按 versionCode 命名，旧标记失配才会触发重解压）。
VERSION_CODE = 29
VERSION_NAME = "2.11"

JAVA_HOME = os.environ.get("JAVA_HOME", "")
JAVAC = os.path.join(JAVA_HOME, "bin", "javac.exe") if JAVA_HOME else "javac"
KEYTOOL = os.path.join(JAVA_HOME, "bin", "keytool.exe") if JAVA_HOME else "keytool"

PROJECT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(PROJECT, "app", "src", "main")
OUT = os.path.join(PROJECT, "build", "apk")
KS = os.path.join(PROJECT, "tools", "debug.keystore")
SIGNING_PROPS = os.path.join(PROJECT, "tools", "signing.properties")
TMP = os.path.join(os.environ.get("TEMP") or ".", "dshmobile-build")


def load_signing():
    """返回 (store_pass, key_pass)。signing.properties > 环境变量 > 内置默认。"""
    store_pass = key_pass = None
    if os.path.exists(SIGNING_PROPS):
        with open(SIGNING_PROPS, "r", encoding="utf-8") as fh:
            for line in fh:
                line = line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                k, v = line.split("=", 1)
                k, v = k.strip(), v.strip()
                if k == "storePassword":
                    store_pass = v
                elif k == "keyPassword":
                    key_pass = v
    store_pass = os.environ.get("DSHM_STORE_PASS") or store_pass
    key_pass = os.environ.get("DSHM_KEY_PASS") or key_pass
    if not store_pass:
        print("!! warning: signing.properties 缺失且未设环境变量，使用内置默认口令"
              "（仅自用可接受；公开分发前请配置 tools/signing.properties）")
        store_pass = key_pass = "dshmobile"
    return store_pass, (key_pass or store_pass)


def prepare_manifest(app_dir, release):
    """规范化暂存副本里的 debuggable：release 剥离、debug 注入。幂等。"""
    mpath = os.path.join(app_dir, "AndroidManifest.xml")
    with open(mpath, "r", encoding="utf-8") as fh:
        text = fh.read()
    text = re.sub(r'\s*android:debuggable="[^"]*"', "", text)
    if not release:
        text = text.replace("<application",
                            '<application\n        android:debuggable="true"', 1)
    with open(mpath, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(text)


def payload_sha256():
    """assets/payload.zip 的 sha256（构建时刻权威值；缺文件返回 unknown）。"""
    zp = os.path.join(SRC, "assets", "payload.zip")
    if not os.path.exists(zp):
        return "unknown"
    h = hashlib.sha256()
    with open(zp, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def write_build_info(gen_dir, release):
    """手工链没有 BuildConfig（aapt2 --java 只生成 R）。这里补 BuildInfo，
    供 WebView 调试开关、诊断页展示 versionCode/payload 校验和。"""
    d = os.path.join(gen_dir, "com", "dsh", "mobile")
    if not os.path.isdir(d):
        os.makedirs(d)
    now = datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    content = (
        "package com.dsh.mobile;\n"
        "/** 构建脚本生成，勿手改（tools/build_manual.py 每次构建重新生成） */\n"
        "public final class BuildInfo {\n"
        "    public static final boolean DEBUG = %s;\n"
        "    public static final int VERSION_CODE = %d;\n"
        "    public static final String VERSION_NAME = \"%s\";\n"
        "    public static final String PAYLOAD_SHA256 = \"%s\";\n"
        "    public static final String BUILD_TIME = \"%s\";\n"
        "}\n" % ("false" if release else "true", VERSION_CODE, VERSION_NAME,
                 payload_sha256(), now)
    )
    with open(os.path.join(d, "BuildInfo.java"), "w", encoding="utf-8",
              newline="\n") as fh:
        fh.write(content)


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
        if not os.path.isdir(TMP):
            os.makedirs(TMP)
        with open(os.path.join(TMP, "last_fail.out"), "wb") as f:
            f.write(r.stdout or b"")
        with open(os.path.join(TMP, "last_fail.err"), "wb") as f:
            f.write(r.stderr or b"")
        print("raw output saved to: %s\\last_fail.{out,err}" % TMP)
        sys.exit(1)
    return r


def main():
    release = "--release" in sys.argv[1:]
    apk_name = "dsh-mobile-v%s-%s.apk" % (VERSION_NAME,
                                          "release" if release else "debug")
    store_pass, key_pass = load_signing()

    shutil.rmtree(TMP, ignore_errors=True)
    os.makedirs(TMP)
    app = os.path.join(TMP, "app")
    shutil.copytree(SRC, app)
    print("stage 0: sources copied to", app)
    prepare_manifest(app, release)
    print("stage 0: manifest prepared (%s track)" % ("release" if release else "debug"))

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

    # 2.5 生成 BuildInfo（aapt2 只生成 R.java；DEBUG/版本/payload 校验和走这里）
    write_build_info(gen, release)
    print("stage 2.5: BuildInfo.java generated (DEBUG=%s, sha256=%s)"
          % (not release, payload_sha256()[:12]))

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

    # 7. debug keystore（首次自动生成；口令来自 load_signing）
    if not os.path.exists(KS):
        run([KEYTOOL, "-genkeypair", "-v", "-keystore", KS,
             "-alias", "dshmobile", "-keyalg", "RSA", "-keysize", "2048",
             "-validity", "10000", "-storepass", store_pass,
             "-keypass", key_pass,
             "-dname", "CN=DSH Mobile Dev, OU=Dev, O=Dev, L=Dev, ST=Dev, C=CN"])

    # 8. apksigner
    os.makedirs(OUT, exist_ok=True)
    final = os.path.join(OUT, apk_name)
    run(["java", "-jar", APKSIGNER_JAR, "sign",
         "--ks", KS, "--ks-pass", "pass:" + store_pass,
         "--ks-key-alias", "dshmobile", "--key-pass", "pass:" + key_pass,
         "--out", final, aligned])

    size = os.path.getsize(final)
    print("")
    print("BUILD OK [%s]: %s (%.2f MB)"
          % ("release" if release else "debug", final, size / 1024.0 / 1024.0))


import zipfile  # noqa: E402  (保持与原脚本相同的懒导入位置习惯)

if __name__ == "__main__":
    main()
