# build_payload.py — 打包 termux 运行时前缀 + 裁剪后的 DSH 应用树为 payload.zip
import os, sys, zipfile

ROOT = os.path.dirname(os.path.abspath(__file__))
TRIM_APP = os.path.join(os.environ.get('TEMP', '/tmp'), 'dshm-trim', 'app')
TERMUX = os.path.join(ROOT, 'termux')
OUT = os.path.join(ROOT, 'payload.zip')

def walk(base):
    for dirpath, dirnames, filenames in os.walk(base):
        rel = os.path.relpath(dirpath, base)
        if rel == '.':
            rel = ''
        for fn in filenames:
            if fn.endswith('.map'):
                continue
            p = os.path.join(dirpath, fn)
            arc = fn if not rel else rel.replace('\\', '/') + '/' + fn
            yield p, arc

def main():
    count = 0
    total = 0
    with zipfile.ZipFile(OUT, 'w', zipfile.ZIP_DEFLATED, compresslevel=6) as z:
        for p, arc in walk(TERMUX):
            z.write(p, 'termux/' + arc)
            total += os.path.getsize(p); count += 1
        for p, arc in walk(TRIM_APP):
            z.write(p, 'dsh-app/' + arc)
            total += os.path.getsize(p); count += 1
    print('zip done:', count, 'files,', round(total / 1048576, 1), 'MB raw ->',
          round(os.path.getsize(OUT) / 1048576, 1), 'MB zip ->', OUT)

if __name__ == '__main__':
    main()
