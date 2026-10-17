#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 design/ic_launcher.svg 转成 Android VectorDrawable（drawable/ic_launcher_fg.xml）。
只支持本项目用到的子集：<g transform="translate/scale ...">、<path>、<circle>、<ellipse>、<rect>。
变换在 Python 里摊平到坐标里，输出扁平 path，避免 Android <group> 变换语义差异。
用法：python design/svg2vector.py [--preview out.svg]
"""
import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "design", "ic_launcher.svg")
DST = os.path.join(ROOT, "app", "src", "main", "res", "drawable", "ic_launcher_fg.xml")

KAPPA = 0.5522847498307936


# ---------- 颜色 ----------

def color_hex(c):
    """把 #rgb / #rrggbb 归一成 #RRGGBB。"""
    if not c or c == "none":
        return None
    if c.startswith("#"):
        h = c[1:]
        if len(h) == 3:
            h = "".join(ch * 2 for ch in h)
        return "#" + h.upper()
    raise SystemExit("unsupported color: " + c)


def fmt(v):
    s = ("%.3f" % v).rstrip("0").rstrip(".")
    return "0" if s in ("-0", "") else s


# ---------- 变换 ----------

def mat_mul(a, b):
    """a、b 都是 6 元组 (a,b,c,d,e,f)，返回 a∘b（先 b 后 a）。"""
    a0, a1, a2, a3, a4, a5 = a
    b0, b1, b2, b3, b4, b5 = b
    return (
        a0 * b0 + a2 * b1, a1 * b0 + a3 * b1,
        a0 * b2 + a2 * b3, a1 * b2 + a3 * b3,
        a0 * b4 + a2 * b5 + a4, a1 * b4 + a3 * b5 + a5,
    )


def parse_transform(t):
    m = (1, 0, 0, 1, 0, 0)
    for name, args in re.findall(r"(\w+)\s*\(([^)]*)\)", t or ""):
        nums = [float(x) for x in re.split(r"[,\s]+", args.strip()) if x]
        if name == "translate":
            tx, ty = nums[0], (nums[1] if len(nums) > 1 else 0.0)
            m = mat_mul(m, (1, 0, 0, 1, tx, ty))
        elif name == "scale":
            sx = nums[0]
            sy = nums[1] if len(nums) > 1 else sx
            m = mat_mul(m, (sx, 0, 0, sy, 0, 0))
        else:
            raise SystemExit("unsupported transform: " + name)
    return m


def apply(m, x, y):
    a, b, c, d, e, f = m
    return a * x + c * y + e, b * x + d * y + f


# ---------- path 解析与变换 ----------

NUM = re.compile(r"[-+]?(?:\d*\.\d+|\d+\.?)(?:[eE][-+]?\d+)?")


def tokenize(d):
    out = []
    i = 0
    while i < len(d):
        ch = d[i]
        if ch in "MmLlHhVvCcSsQqTtAaZz":
            out.append(ch)
            i += 1
        elif ch in " ,\t\r\n":
            i += 1
        else:
            m = NUM.match(d, i)
            if not m:
                raise SystemExit("bad path at %r" % d[i:i + 20])
            out.append(float(m.group(0)))
            i = m.end()
    return out


def transform_path(d, m):
    """只处理 M/L/H/V/C/Q/Z 与相对形式，足够本项目用。"""
    toks = tokenize(d)
    out = []
    i = 0
    cur = [0.0, 0.0]
    start = [0.0, 0.0]
    cmd = None

    def emit_abs(x, y):
        cur[0], cur[1] = x, y
        px, py = apply(m, x, y)
        out.append("L %s,%s" % (fmt(px), fmt(py)))

    while i < len(toks):
        t = toks[i]
        if isinstance(t, str):
            cmd = t
            i += 1
            if cmd in "Zz":
                out.append("Z")
                cur = list(start)
                continue
            continue
        c = cmd
        rel = c.islower()
        c = c.upper()
        if c == "M":
            x, y = toks[i], toks[i + 1]
            i += 2
            if rel:
                x, y = cur[0] + x, cur[1] + y
            cur = [x, y]
            start = [x, y]
            px, py = apply(m, x, y)
            out.append("M %s,%s" % (fmt(px), fmt(py)))
            cmd = "l" if rel else "L"  # 后续坐标对按 lineto 处理
        elif c == "L":
            x, y = toks[i], toks[i + 1]
            i += 2
            if rel:
                x, y = cur[0] + x, cur[1] + y
            emit_abs(x, y)
        elif c == "H":
            x = toks[i]
            i += 1
            if rel:
                x = cur[0] + x
            emit_abs(x, cur[1])
        elif c == "V":
            y = toks[i]
            i += 1
            if rel:
                y = cur[1] + y
            emit_abs(cur[0], y)
        elif c == "C":
            x1, y1, x2, y2, x, y = toks[i:i + 6]
            i += 6
            if rel:
                x1, y1, x2, y2, x, y = (cur[0] + x1, cur[1] + y1,
                                        cur[0] + x2, cur[1] + y2,
                                        cur[0] + x, cur[1] + y)
            p1 = apply(m, x1, y1)
            p2 = apply(m, x2, y2)
            p3 = apply(m, x, y)
            out.append("C %s,%s %s,%s %s,%s" % (fmt(p1[0]), fmt(p1[1]),
                                                fmt(p2[0]), fmt(p2[1]),
                                                fmt(p3[0]), fmt(p3[1])))
            cur = [x, y]
        elif c == "Q":
            x1, y1, x, y = toks[i:i + 4]
            i += 4
            if rel:
                x1, y1, x, y = (cur[0] + x1, cur[1] + y1, cur[0] + x, cur[1] + y)
            p1 = apply(m, x1, y1)
            p2 = apply(m, x, y)
            out.append("Q %s,%s %s,%s" % (fmt(p1[0]), fmt(p1[1]), fmt(p2[0]), fmt(p2[1])))
            cur = [x, y]
        else:
            raise SystemExit("unsupported path command: " + c)
    return " ".join(out)


# ---------- 基本图元转 path ----------

def circle_path(cx, cy, rx, ry=None):
    ry = rx if ry is None else ry
    kx, ky = rx * KAPPA, ry * KAPPA
    pts = []
    segs = [
        (cx, cy - ry, cx + kx, cy - ry, cx + rx, cy - ky, cx + rx, cy),
        (cx + rx, cy, cx + rx, cy + ky, cx + kx, cy + ry, cx, cy + ry),
        (cx, cy + ry, cx - kx, cy + ry, cx - rx, cy + ky, cx - rx, cy),
        (cx - rx, cy, cx - rx, cy - ky, cx - kx, cy - ry, cx, cy - ry),
    ]
    d = "M %s,%s " % (fmt(cx + rx), fmt(cy))
    for _sx, _sy, x1, y1, x2, y2, x, y in segs:
        d += "C %s,%s %s,%s %s,%s " % (fmt(x1), fmt(y1), fmt(x2), fmt(y2), fmt(x), fmt(y))
    return d + "Z"


# ---------- SVG 遍历 ----------

def attrs(node, tag):
    return dict(node.attrib), tag


def convert(svg_text):
    import xml.etree.ElementTree as ET
    root = ET.fromstring(svg_text)
    out = []

    def walk(node, m, inh):
        tag = node.tag.split("}")[-1]
        am = mat_mul(m, parse_transform(node.get("transform")))
        # 继承 SVG 的属性（fill/stroke 会从 <g> 往下传）
        cur = dict(inh)
        for k in ("fill", "stroke", "stroke-width", "opacity"):
            if node.get(k) is not None:
                cur[k] = node.get(k)
        if tag in ("g", "svg"):
            for child in node:
                walk(child, am, cur)
            return
        fill = color_hex(cur.get("fill", "#000000"))
        stroke = color_hex(cur.get("stroke"))
        sw = cur.get("stroke-width")
        opacity = float(cur.get("opacity", "1"))
        d = None
        if tag == "path":
            d = node.get("d")
        elif tag == "circle":
            d = circle_path(float(node.get("cx", 0)), float(node.get("cy", 0)),
                            float(node.get("r")))
        elif tag == "ellipse":
            d = circle_path(float(node.get("cx", 0)), float(node.get("cy", 0)),
                            float(node.get("rx")), float(node.get("ry")))
        elif tag == "rect":
            x, y = float(node.get("x", 0)), float(node.get("y", 0))
            w, h = float(node.get("width")), float(node.get("height"))
            r = min(float(node.get("rx", 0)), w / 2, h / 2)
            kr = r * KAPPA
            d = ("M %s,%s L %s,%s C %s,%s %s,%s %s,%s L %s,%s "
                 "C %s,%s %s,%s %s,%s L %s,%s C %s,%s %s,%s %s,%s L %s,%s "
                 "C %s,%s %s,%s %s,%s Z") % (
                fmt(x + r), fmt(y), fmt(x + w - r), fmt(y),
                fmt(x + w - r + kr), fmt(y), fmt(x + w), fmt(y + r - kr), fmt(x + w), fmt(y + r),
                fmt(x + w), fmt(y + h - r),
                fmt(x + w), fmt(y + h - r + kr), fmt(x + w - r + kr), fmt(y + h), fmt(x + w - r), fmt(y + h),
                fmt(x + r), fmt(y + h),
                fmt(x + r - kr), fmt(y + h), fmt(x), fmt(y + h - r + kr), fmt(x), fmt(y + h - r),
                fmt(x), fmt(y + r),
                fmt(x), fmt(y + r - kr), fmt(x + r - kr), fmt(y), fmt(x + r), fmt(y))
        else:
            return

        d = transform_path(d, am)
        a = ['<path', 'android:pathData="%s"' % d.replace('"', "'")]
        a.append('android:fillColor="%s"' % (fill or "#00000000"))
        if opacity < 1.0:
            a.append('android:fillAlpha="%s"' % fmt(opacity))
        if stroke:
            a.append('android:strokeColor="%s"' % stroke)
            a.append('android:strokeWidth="%s"' % fmt(float(sw or 1)))
            a.append('android:strokeLineCap="round"')
            a.append('android:strokeLineJoin="round"')
        a.append("/>")
        out.append("    " + " ".join(a))

    walk(root, (1, 0, 0, 1, 0, 0), {})
    head = ('<?xml version="1.0" encoding="utf-8"?>\n'
            '<!-- 由 design/svg2vector.py 从 design/ic_launcher.svg 生成，请勿手改 -->\n'
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    android:width="108dp"\n'
            '    android:height="108dp"\n'
            '    android:viewportWidth="108"\n'
            '    android:viewportHeight="108">\n')
    return head + "\n".join(out) + "\n</vector>\n"


def preview_svg(svg_text, bg="#FBF1E7"):
    """给预览用：在内容之前垫一个背景色方块（Android 里由 ic_launcher_bg 提供）。"""
    i = svg_text.index(">") + 1
    return svg_text[:i] + '<rect width="108" height="108" fill="%s"/>' % bg + svg_text[i:]


def main():
    svg = io.open(SRC, encoding="utf-8").read()
    vec = convert(svg)
    with io.open(DST, "w", encoding="utf-8", newline="\n") as f:
        f.write(vec)
    print("vector written:", DST, len(vec), "bytes")
    if "--preview" in sys.argv:
        i = sys.argv.index("--preview")
        path = sys.argv[i + 1]
        with io.open(path, "w", encoding="utf-8", newline="\n") as f:
            f.write(preview_svg(svg))
        print("preview svg:", path)


if __name__ == "__main__":
    main()
