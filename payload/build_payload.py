# build_payload.py — 打包 termux 运行时前缀 + 裁剪后的 DSH 应用树为 payload.zip
# v2.12 起同时写出 payload.sha256（sha256sum 格式），随 APK 一起放进 assets，
# 供 DshServerService 解压前做完整性校验（见 AGENTS.md「payload 校验」）。
import hashlib
import os, sys, zipfile

ROOT = os.path.dirname(os.path.abspath(__file__))
TRIM_APP = os.path.join(os.environ.get('TEMP', '/tmp'), 'dshm-trim', 'app')
TERMUX = os.path.join(ROOT, 'termux')
OUT = os.path.join(ROOT, 'payload.zip')
SIDECAR = os.path.join(ROOT, 'payload.sha256')

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

def sha256_file(path):
    digest = hashlib.sha256()
    with open(path, 'rb') as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()

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

    # sha256sum 格式：<64 位十六进制><两个空格><文件名>
    digest = sha256_file(OUT)
    with open(SIDECAR, 'w', encoding='ascii', newline='\n') as fh:
        fh.write('%s  payload.zip\n' % digest)
    print('sha256:', digest)
    print('wrote:', SIDECAR)
    print('next: copy payload.zip AND payload.sha256 into app/src/main/assets/')

if __name__ == '__main__':
    main()
