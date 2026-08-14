# extract_termux.py — 从各 .deb 抽取运行所需文件到 termux 前缀，并校验依赖完整性
import os, sys, tarfile
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from payload_tools import deb_data_tar, elf_needed

PAYLOAD = os.path.dirname(os.path.abspath(__file__))
DEBS = os.path.join(PAYLOAD, 'debs')
PREFIX = os.path.join(PAYLOAD, 'termux', 'usr')

DEB_MAP = {
    'nodejs.deb': {
        './data/data/com.termux/files/usr/bin/node': 'bin/node',
    },
    'libc++_29_aarch64.deb': None,          # 抽取所有 .so
    'openssl.deb': None,
    'c-ares_1.34.8_aarch64.deb': None,
    'libicu_78.3_aarch64.deb': None,
    'libsqlite_3.53.4_aarch64.deb': None,
    'zlib_1.3.2_aarch64.deb': None,
    'libffi_3.5.2_aarch64.deb': None,
    'ca-certificates.deb': {
        './data/data/com.termux/files/usr/etc/ssl/certs/ca-certificates.crt':
            'etc/ssl/certs/ca-certificates.crt',
        './data/data/com.termux/files/usr/etc/ssl/certs/ca-certificates.crt':
            'etc/ssl/certs/ca-certificates.crt',
    },
}

def extract():
    os.makedirs(os.path.join(PREFIX, 'bin'), exist_ok=True)
    os.makedirs(os.path.join(PREFIX, 'lib'), exist_ok=True)
    os.makedirs(os.path.join(PREFIX, 'etc', 'ssl', 'certs'), exist_ok=True)
    for deb, mapping in DEB_MAP.items():
        path = os.path.join(DEBS, deb)
        if not os.path.exists(path) or os.path.getsize(path) == 0:
            print('SKIP (missing/empty):', deb)
            continue
        t = deb_data_tar(path)
        for m in t.getmembers():
            if m.isdir():
                continue
            inner = m.name
            if mapping:
                outname = mapping.get(inner)
                if not outname:
                    continue
            else:
                base = inner.rsplit('/', 1)[-1]
                if not (base.startswith('lib') and '.so' in base):
                    continue
                outname = os.path.join('lib', base)
            f = t.extractfile(inner)
            data = f.read()
            out = os.path.join(PREFIX, outname)
            os.makedirs(os.path.dirname(out), exist_ok=True)
            open(out, 'wb').write(data)
            print('  +', outname, len(data))
    print('extract done')

def verify():
    libdir = os.path.join(PREFIX, 'lib')
    system = {'libc.so.6', 'libm.so.6', 'libdl.so.2', 'libpthread.so.0',
              'libc.so', 'libm.so', 'libdl.so', 'liblog.so', 'libandroid.so',
              'ld-linux-aarch64.so.1', 'libgcc_s.so.1', 'libzstd.so.1',
              'libunwind.so.1'}
    shipped = set(os.listdir(libdir))
    shipped |= {'libc.so', 'libm.so', 'libdl.so'}
    all_ok = True
    for root, _, files in os.walk(PREFIX):
        for fn in files:
            p = os.path.join(root, fn)
            with open(p, 'rb') as f:
                head = f.read(4)
            if head != b'\x7fELF':
                continue
            data = open(p, 'rb').read()
            try:
                needs = elf_needed(data)
            except Exception as e:
                print('PARSE FAIL', p, e)
                continue
            missing = [n for n in needs if n not in shipped and n not in system]
            rel = os.path.relpath(p, PREFIX)
            status = 'OK' if not missing else 'MISSING: ' + ','.join(missing)
            if missing:
                all_ok = False
            print(rel, '->', ','.join(needs), '|', status)
    print('VERIFY', 'ALL OK' if all_ok else 'HAS MISSING')

if __name__ == '__main__':
    mode = sys.argv[1] if len(sys.argv) > 1 else 'extract'
    if mode == 'extract':
        extract()
        verify()
    else:
        verify()
