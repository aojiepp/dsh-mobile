# extract_termux.py — 从各 .deb 抽取运行所需文件到 termux 前缀，并校验依赖完整性
# .deb 下载：Termux 官方仓库 https://packages.termux.dev/apt/termux-main/
# （dists/stable/main/binary-aarch64/Packages 索引里找 Filename；文件名以索引为准）
# 当前需要：nodejs、bash、ripgrep + 依赖（libandroid-support/libiconv/readline/
# ncurses/pcre2/libc++/openssl/c-ares/libicu/libsqlite/zlib/libffi/ca-certificates）
import os, sys, tarfile, hashlib
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from payload_tools import deb_data_tar, elf_needed

PAYLOAD = os.path.dirname(os.path.abspath(__file__))
DEBS = os.path.join(PAYLOAD, 'debs')
PREFIX = os.path.join(PAYLOAD, 'termux', 'usr')

DEB_MAP = {
    'nodejs.deb': {
        './data/data/com.termux/files/usr/bin/node': 'bin/node',
    },
    # v2.4: bash 工具链 + ripgrep（glob/grep 用）及其运行时依赖
    'bash_5.3.15_aarch64.deb': {
        './data/data/com.termux/files/usr/bin/bash': 'bin/bash',
    },
    'ripgrep_15.2.0_aarch64.deb': {
        './data/data/com.termux/files/usr/bin/rg': 'bin/rg',
    },
    'libandroid-support_29-1_aarch64.deb': None,   # 抽取所有 .so
    'libiconv_1.18-1_aarch64.deb': None,
    'readline_8.3.3_aarch64.deb': None,
    'ncurses_6.6.20260307_aarch64.deb': None,
    'pcre2_10.47_aarch64.deb': None,
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
    # 清空 lib/bin（防止旧提取的重复副本残留）
    for sub in ('lib', 'bin'):
        for fn in os.listdir(os.path.join(PREFIX, sub)):
            os.remove(os.path.join(PREFIX, sub, fn))
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
    dedupe_libs()
    print('extract done')

# deb 里同名库常带三份名字（.so / .so.N / .so.N.M），内容相同；加载器只按
# DT_NEEDED 的 SONAME 查找 —— 按内容哈希去重，每组保留 SONAME 形式（.so.N）。
DROP_LIBS = {'libpcre2-16.so', 'libpcre2-32.so', 'libpcre2-posix.so',
             'libsqlite3.53.4.so'}
# 去重时每组优先保留这些被其它 ELF 的 DT_NEEDED 引用的名字
KEEP_PRIORITY = ['libncursesw.so.6', 'libreadline.so.8', 'libhistory.so.8',
                 'libsqlite3.so', 'libz.so.1', 'libcrypto.so.3', 'libssl.so.3',
                 'libicudata.so.78', 'libicui18n.so.78', 'libicuuc.so.78',
                 'libicuio.so.78', 'libicutu.so.78', 'libicutest.so.78',
                 'libpcre2-8.so', 'libandroid-support.so', 'libiconv.so',
                 'libc++_shared.so', 'libcares.so', 'libffi.so', 'libcharset.so']
def dedupe_libs():
    import re
    libdir = os.path.join(PREFIX, 'lib')
    groups = {}
    for fn in os.listdir(libdir):
        p = os.path.join(libdir, fn)
        h = hashlib.sha1(open(p, 'rb').read()).hexdigest()
        groups.setdefault(h, []).append(fn)
    removed = 0
    for names in groups.values():
        if len(names) == 1 and names[0] not in DROP_LIBS:
            continue
        keep = None
        for want in KEEP_PRIORITY:
            if want in names:
                keep = want
                break
        if keep is None:
            for n in names:
                if re.match(r'^lib.+\.so\.\d+$', n):
                    keep = n
                    break
        if keep is None:
            keep = sorted(names, key=len)[0]
        for n in names:
            if n != keep or n in DROP_LIBS:
                os.remove(os.path.join(libdir, n))
                removed += 1
                print('  dedupe -', n)
    print('dedupe done, removed', removed, 'duplicate libs')

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
