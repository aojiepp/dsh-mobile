#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""调试用：把生成的 VectorDrawable 反渲染成 SVG，确认转换没出错（颜色/坐标）。
用法：python design/vector2svg.py <out.svg> [bg]
"""
import io
import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
VEC = os.path.join(ROOT, "app", "src", "main", "res", "drawable", "ic_launcher_fg.xml")
NS = "{http://schemas.android.com/apk/res/android}"


def color(c):
    if not c:
        return "none"
    h = c.lstrip("#")
    if len(h) == 8:  # AARRGGBB
        a = int(h[0:2], 16) / 255.0
        return "rgba(%d,%d,%d,%.3f)" % (int(h[2:4], 16), int(h[4:6], 16), int(h[6:8], 16), a)
    return "#" + h


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.environ.get("TEMP", "."), "vec.svg")
    bg = sys.argv[2] if len(sys.argv) > 2 else "#FBF1E7"
    root = ET.parse(VEC).getroot()
    w = root.get(NS + "viewportWidth", "108")
    h = root.get(NS + "viewportHeight", "108")
    parts = ['<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 %s %s" width="432" height="432">' % (w, h)]
    parts.append('<rect width="%s" height="%s" fill="%s"/>' % (w, h, bg))
    for p in root:
        d = p.get(NS + "pathData")
        if not d:
            continue
        fill = color(p.get(NS + "fillColor"))
        alpha = p.get(NS + "fillAlpha")
        op = ' opacity="%s"' % alpha if alpha else ""
        stroke = p.get(NS + "strokeColor")
        sw = p.get(NS + "strokeWidth")
        st = ""
        if stroke and stroke != "#00000000":
            st = ' stroke="%s" stroke-width="%s" stroke-linecap="round" stroke-linejoin="round"' % (
                color(stroke), sw)
        parts.append('<path d="%s" fill="%s"%s%s/>' % (d, fill, op, st))
    parts.append("</svg>")
    io.open(out, "w", encoding="utf-8", newline="\n").write("\n".join(parts))
    print("vector->svg:", out)


if __name__ == "__main__":
    main()
