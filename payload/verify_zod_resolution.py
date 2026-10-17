# verify_zod_resolution.py — 校验 zod 去重前后每个 importer 解析到的版本是否一致
# 用法: python payload/verify_zod_resolution.py [dsh-app 根目录] [--save 文件]
import json
import os
import re
import sys
from collections import Counter

ZOD_IMPORT = re.compile(r"""from\s+["']zod["']|require\(["']zod["']\)|import\s*\(\s*["']zod["']\s*\)""")
SKIP_DIRS = {"node_modules", "test", "tests", "__tests__", ".git"}


def version_of(pkgdir):
    try:
        with open(os.path.join(pkgdir, "package.json"), encoding="utf-8") as f:
            return json.load(f).get("version")
    except Exception:
        return None


def resolve_zod(start, root_parent):
    """按 Node 的 node_modules 上溯规则找 zod（start 是包目录）。"""
    p = start
    while True:
        cand = os.path.join(p, "node_modules", "zod")
        if os.path.isdir(cand):
            return cand
        parent = os.path.dirname(p)
        if parent == p:
            return None
        p = parent


def uses_zod(pkgdir):
    for dp, dns, fns in os.walk(pkgdir):
        dns[:] = [d for d in dns if d not in SKIP_DIRS]
        for fn in fns:
            if fn.endswith((".js", ".mjs", ".cjs")):
                try:
                    with open(os.path.join(dp, fn), encoding="utf-8", errors="ignore") as fh:
                        if ZOD_IMPORT.search(fh.read()):
                            return True
                except Exception:
                    pass
    return False


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
        os.environ.get("TEMP", "/tmp"), "dshm-trim", "app")
    nm = os.path.join(root, "node_modules")
    mapping = {}
    for dp, dns, fns in os.walk(nm):
        if "package.json" not in fns:
            continue
        if os.path.basename(dp) == "node_modules":
            continue
        name = None
        try:
            with open(os.path.join(dp, "package.json"), encoding="utf-8") as f:
                name = json.load(f).get("name")
        except Exception:
            pass
        if not name:
            continue
        if not uses_zod(dp):
            continue
        z = resolve_zod(dp, nm)
        mapping[name + "  @  " + os.path.relpath(dp, nm)] = version_of(z) if z else "MISSING"

    c = Counter(mapping.values())
    print("importers:", len(mapping), " resolution:", dict(c))
    for k in sorted(mapping):
        print("  %-95s %s" % (k, mapping[k]))
    for i, a in enumerate(sys.argv):
        if a == "--save" and i + 1 < len(sys.argv):
            with open(sys.argv[i + 1], "w", encoding="utf-8") as f:
                json.dump(mapping, f, indent=1, sort_keys=True)
            print("saved:", sys.argv[i + 1])


if __name__ == "__main__":
    main()
