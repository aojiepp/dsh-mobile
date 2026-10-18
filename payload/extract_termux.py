# extract_termux.py — 从各 .deb 抽取运行所需文件到 termux 前缀，并校验依赖完整性
#
# 工具链 v3（开发工具链进 payload）：node/bash/rg + git/curl/openssh/npm/tar/less/pnpm
# .deb 下载：Termux 官方仓库 https://packages.termux.dev/apt/termux-main/
#   索引 dists/stable/main/binary-aarch64/Packages → Filename（用 payload_tools.py fetch 下，
#   自动按索引 SHA256 校验）。清单与体积见 TOOLCHAIN.md。
#
# 用法：
#   python payload/extract_termux.py            # 抽取 + 校验 + 自动 patch RUNPATH
#   python payload/extract_termux.py verify     # 只校验
#   python payload/extract_termux.py pnpm <pnpm-x.y.z.tgz>   # 安装 pnpm tarball 并写 wrapper
#
# 映射规则（DEB_MAP 每项 = deb 文件名 → 映射表）：
#   None                  → 抽 lib/ 下所有 *.so*（老行为，依赖库包）
#   'usr 内路径': 'usr 内目标'  → 精确映射（符号链接也可作为 key）
#   'usr 内路径/': 'usr 内目标/' → **整子树映射**（npm 的 lib/node_modules/npm/**、
#                                git 的 libexec/git-core/**、share/git-core/** 用这个）
#
# 符号链接物化：
#   * 目标在 lib/ 下 → 写成目标文件的**字节副本**（动态加载器需要真 ELF，不能是脚本）
#   * 目标在 bin/、libexec/ 下 → 写成 `#!/system/bin/sh` wrapper 脚本：
#       - JS（.js/.cjs/.mjs）→ exec "$HERE/node" "$HERE/<rel>" "$@"
#       - ELF 且文件名与目标不同（git 的 git-add→git 这类 dashed 入口）→ 用自带的
#         bash `exec -a` 传回 argv[0]，否则 git 会丢掉子命令身份
#       - 其它（shell 脚本）→ 直接 exec
import hashlib, os, posixpath, shutil, sys, tarfile
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from payload_tools import deb_data_tar, elf_needed

PAYLOAD = os.path.dirname(os.path.abspath(__file__))
DEBS = os.path.join(PAYLOAD, 'debs')
PREFIX = os.path.join(PAYLOAD, 'termux', 'usr')
U = './data/data/com.termux/files/usr'


def _usr(*parts):
    return U + '/' + '/'.join(parts)


# 只抽指定 lib 文件名（含符号链接名；链接会按目标内容物化）
def _libs(*names):
    return {_usr('lib', n): 'lib/' + n for n in names}


DEB_MAP = {
    'nodejs.deb': {
        _usr('bin', 'node'): 'bin/node',
    },
    # v2.4: bash 工具链 + ripgrep（glob/grep 用）及其运行时依赖
    'bash_5.3.15_aarch64.deb': {
        _usr('bin', 'bash'): 'bin/bash',
    },
    'ripgrep_15.2.0_aarch64.deb': {
        _usr('bin', 'rg'): 'bin/rg',
    },
    # ---- v3 开发工具链：git ----
    # bin/git 本体 + libexec/git-core（dashed 入口 & git-remote-http）+ share/git-core（模板）
    'git_2.56.0_aarch64.deb': {
        _usr('bin', 'git'): 'bin/git',
        _usr('bin', 'git-receive-pack'): 'bin/git-receive-pack',
        _usr('bin', 'git-upload-archive'): 'bin/git-upload-archive',
        _usr('bin', 'git-upload-pack'): 'bin/git-upload-pack',
        _usr('libexec', 'git-core') + '/': 'libexec/git-core/',
        _usr('share', 'git-core') + '/': 'share/git-core/',
    },
    # ---- v3：HTTP(S) 客户端 ----
    'curl_8.23.0_aarch64.deb': {
        _usr('bin', 'curl'): 'bin/curl',
    },
    # ---- v3：ssh 客户端（git 的 ssh transport 用）----
    'openssh_10.6p1-2_aarch64.deb': {
        _usr('bin', 'ssh'): 'bin/ssh',
        _usr('bin', 'scp'): 'bin/scp',
        _usr('bin', 'sftp'): 'bin/sftp',
        _usr('bin', 'ssh-add'): 'bin/ssh-add',
        _usr('bin', 'ssh-agent'): 'bin/ssh-agent',
        _usr('bin', 'ssh-keygen'): 'bin/ssh-keygen',
        _usr('bin', 'ssh-keyscan'): 'bin/ssh-keyscan',
        _usr('bin', 'ssh-copy-id'): 'bin/ssh-copy-id',
        _usr('bin', 'ssha'): 'bin/ssha',
        _usr('etc', 'ssh', 'ssh_config'): 'etc/ssh/ssh_config',
        _usr('etc', 'ssh', 'moduli'): 'etc/ssh/moduli',
        _usr('libexec', 'ssh-keysign'): 'libexec/ssh-keysign',
        _usr('libexec', 'ssh-sk-helper'): 'libexec/ssh-sk-helper',
        _usr('libexec', 'wrap-ssh-agent.sh'): 'libexec/wrap-ssh-agent.sh',
    },
    'openssh-sftp-server_10.6p1-2_aarch64.deb': {
        _usr('libexec', 'sftp-server'): 'libexec/sftp-server',
    },
    # ---- v3：npm（依赖已带的 node）----
    'npm_11.21.1_all.deb': {
        _usr('bin', 'npm'): 'bin/npm',
        _usr('bin', 'npx'): 'bin/npx',
        _usr('lib', 'node_modules', 'npm') + '/': 'lib/node_modules/npm/',
    },
    # ---- v3：tar + 分页器 ----
    'tar_1.35-3_aarch64.deb': {
        _usr('bin', 'tar'): 'bin/tar',
        _usr('libexec', 'rmt'): 'libexec/rmt',
    },
    'less_710_aarch64.deb': {
        _usr('bin', 'less'): 'bin/less',
        _usr('libexec', 'lessecho'): 'libexec/lessecho',
        _usr('libexec', 'less-osc8-open'): 'libexec/less-osc8-open',
    },
    # ---- v3 依赖库：只抽 DT_NEEDED 实际用到的 ----
    'libexpat_2.9.0_aarch64.deb': _libs('libexpat.so.1'),
    'libcurl_8.23.0_aarch64.deb': _libs('libcurl.so'),
    'libnghttp2_1.70.0_aarch64.deb': _libs('libnghttp2.so'),
    'libnghttp3_1.18.0_aarch64.deb': _libs('libnghttp3.so'),
    'libngtcp2_1.25.0_aarch64.deb': _libs('libngtcp2.so', 'libngtcp2_crypto_ossl.so'),
    'libssh2_1.11.1-2_aarch64.deb': _libs('libssh2.so'),
    'ldns_1.9.2_aarch64.deb': _libs('libldns.so'),
    'libedit_20260512-3.1-0_aarch64.deb': _libs('libedit.so'),
    'libacl_2.4.0_aarch64.deb': _libs('libacl.so'),
    'libandroid-glob_0.6-3_aarch64.deb': _libs('libandroid-glob.so'),
    'libandroid-selinux_14.0.0.11-1_aarch64.deb': _libs('libandroid-selinux.so'),
    'libresolv-wrapper_1.1.8_aarch64.deb': _libs('libresolv_wrapper.so'),
    # ssh 客户端只需要 gssapi/krb5 客户端侧 5 个库
    'krb5_1.22.2_aarch64.deb': _libs(
        'libgssapi_krb5.so.2', 'libkrb5.so.3', 'libk5crypto.so.3',
        'libcom_err.so.3', 'libkrb5support.so.0'),
    # ---- 既有依赖 ----
    'libandroid-support_29-1_aarch64.deb': None,   # 抽取所有 .so
    'libiconv_1.18-1_aarch64.deb': None,
    'readline_8.3.3_aarch64.deb': None,
    'ncurses_6.6.20260307_aarch64.deb': None,
    'pcre2_10.47_aarch64.deb': None,
    'libc++_29_aarch64.deb': None,
    'openssl.deb': None,
    'c-ares_1.34.8_aarch64.deb': None,
    'libicu_78.3_aarch64.deb': None,
    'libsqlite_3.53.4_aarch64.deb': None,
    'zlib_1.3.2_aarch64.deb': None,
    'libffi_3.5.2_aarch64.deb': None,
    'ca-certificates.deb': {
        _usr('etc', 'tls', 'cert.pem'): 'etc/tls/cert.pem',
    },
}


def _norm(name):
    n = name.replace('\\', '/')
    while n.startswith('./'):
        n = n[2:]
    return n.lstrip('/')


def _map_dest(inner, mapping):
    """inner 已规范化（无 './' 前缀）。→ 目标相对路径 or None。"""
    if mapping is None:
        base = inner.rsplit('/', 1)[-1]
        if base.startswith('lib') and '.so' in base:
            return 'lib/' + base
        return None
    dest = mapping.get(inner) or mapping.get('./' + inner)
    if dest:
        return dest
    for key, value in mapping.items():
        k = _norm(key)
        if key.endswith('/') and inner.startswith(k):
            return value + inner[len(k):]
    return None


def _resolve(entry, index):
    """把符号链接/硬链接解析成真正带数据的成员；解析不了返回 None。"""
    cur = entry
    for _ in range(16):
        if cur.isfile():
            return cur
        if not (cur.issym() or cur.islnk()):
            return None
        link = cur.linkname
        if link.startswith('/'):
            target = _norm(link)
        else:
            base = _norm(cur.name).rsplit('/', 1)[0]
            target = _norm(posixpath.normpath(posixpath.join(base, link)))
        cur = index.get(target)
        if cur is None:
            return None
    return None


def _wrapper(dest, target_dest, target_elf, target_name):
    here_dir = posixpath.dirname(dest)
    rel = posixpath.relpath(target_dest, here_dir)
    ext = target_dest.rsplit('.', 1)[-1].lower()
    if not target_elf and ext in ('js', 'cjs', 'mjs'):
        # 例：bin/npm → node ../lib/node_modules/npm/bin/npm-cli.js
        body = 'exec "$HERE/node" "$HERE/%s" "$@"' % rel
    elif target_elf and posixpath.basename(dest) != posixpath.basename(target_dest):
        # git 的 dashed 入口（git-add→git、git-remote-https→git）：必须原样传回 argv[0]，
        # 否则 git 只看得到 argv[0]=git，子命令身份丢失。借 payload 自带的 bash exec -a。
        bash_rel = posixpath.relpath('bin/bash', here_dir)
        body = ('exec "$HERE/%s" -c \'exec -a "$1" "$2" "${@:3}"\' sh "%s" "$HERE/%s" "$@"'
                % (bash_rel, posixpath.basename(dest), rel))
    else:
        body = 'exec "$HERE/%s" "$@"' % rel
    return ('#!/system/bin/sh\n'
            'HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)\n'
            + body + '\n')


PREAMBLE = '#!/system/bin/sh\nHERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)\n'


def _write(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'wb') as f:
        f.write(data)


def _clean():
    """只清 deb 管的目录；lib/node_modules/pnpm 是 npm pack 装的，保留。"""
    for sub in ('bin', 'libexec', 'share', 'etc'):
        shutil.rmtree(os.path.join(PREFIX, sub), ignore_errors=True)
    libdir = os.path.join(PREFIX, 'lib')
    os.makedirs(libdir, exist_ok=True)
    for fn in os.listdir(libdir):
        p = os.path.join(libdir, fn)
        if os.path.isfile(p):
            os.remove(p)
    shutil.rmtree(os.path.join(libdir, 'node_modules', 'npm'), ignore_errors=True)


def extract():
    os.makedirs(os.path.join(PREFIX, 'bin'), exist_ok=True)
    os.makedirs(os.path.join(PREFIX, 'lib'), exist_ok=True)
    os.makedirs(os.path.join(PREFIX, 'etc', 'ssl', 'certs'), exist_ok=True)
    _clean()
    for deb, mapping in DEB_MAP.items():
        path = os.path.join(DEBS, deb)
        if not os.path.exists(path) or os.path.getsize(path) == 0:
            print('SKIP (missing/empty):', deb)
            continue
        t = deb_data_tar(path)
        members = [m for m in t.getmembers() if not m.isdir()]
        index = {_norm(m.name): m for m in members}
        count = 0
        links = 0
        for m in members:
            inner = _norm(m.name)
            dest = _map_dest(inner, mapping)
            if not dest:
                continue
            out = os.path.join(PREFIX, dest)
            target = _resolve(m, index) if (m.issym() or m.islnk()) else m
            if target is None:
                print('  ! unresolved link', inner, '->', m.linkname)
                continue
            data = t.extractfile(target)
            data = data.read() if data is not None else b''
            if m.issym() or m.islnk():
                links += 1
                if dest.startswith('lib/'):
                    _write(out, data)  # .so 链接物化成字节副本
                else:
                    target_dest = _map_dest(_norm(target.name), mapping)
                    if not target_dest:
                        # 目标没被映射（如 git-shell/scalar/git-cvsserver 这些不含在
                        # payload 里的可选二进制）→ 不生成会自指的 wrapper
                        print('  ! skip link (target not shipped):', inner, '->', m.linkname)
                        continue
                    _write(out, _wrapper(dest, target_dest,
                                         data[:4] == b'\x7fELF',
                                         target.name).encode('utf-8'))
                continue
            _write(out, data)
            count += 1
        print('  %-42s files=%d links=%d' % (deb, count, links))
    # Termux 的 CA 包只带 etc/tls/cert.pem；git/.gitconfig 习惯用 ca-certificates.crt
    cert = os.path.join(PREFIX, 'etc', 'tls', 'cert.pem')
    if os.path.exists(cert):
        _write(os.path.join(PREFIX, 'etc', 'ssl', 'certs', 'ca-certificates.crt'),
               open(cert, 'rb').read())
    ensure_pnpm_wrappers()
    dedupe_libs()
    try:
        import patch_runpath
        patch_runpath.patch_tree(PREFIX)
    except Exception as e:  # 不让 RUNPATH 补丁失败掩盖抽取结果
        print('PATCH RUNPATH FAILED:', e)
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
                 'libc++_shared.so', 'libcares.so', 'libffi.so', 'libcharset.so',
                 'libexpat.so.1', 'libgssapi_krb5.so.2', 'libkrb5.so.3',
                 'libk5crypto.so.3', 'libcom_err.so.3', 'libkrb5support.so.0']
def dedupe_libs():
    import re
    libdir = os.path.join(PREFIX, 'lib')
    groups = {}
    for fn in os.listdir(libdir):
        p = os.path.join(libdir, fn)
        if not os.path.isfile(p):
            continue
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
    print('dedupe done, removed', removed, 'duplicate libs')


# ---------------------------------------------------------------- pnpm

PNPM_BINS = (('pnpm', ('bin/pnpm.cjs', 'bin/pnpm.mjs')),
             ('pnpx', ('bin/pnpx.cjs', 'bin/pnpx.mjs')))


def ensure_pnpm_wrappers():
    root = os.path.join(PREFIX, 'lib', 'node_modules', 'pnpm')
    if not os.path.isdir(root):
        return
    for name, candidates in PNPM_BINS:
        script = None
        for cand in candidates:
            if os.path.exists(os.path.join(root, *cand.split('/'))):
                script = cand
                break
        if not script:
            continue
        # 例：bin/pnpm → node ../lib/node_modules/pnpm/bin/pnpm.cjs
        body = (PREAMBLE
                + 'exec "$HERE/node" "$HERE/../lib/node_modules/pnpm/%s" "$@"\n' % script)
        _write(os.path.join(PREFIX, 'bin', name), body.encode('utf-8'))
        print('  + bin/%s (pnpm wrapper -> %s)' % (name, script))


def install_pnpm(tarball):
    """把 `npm pack pnpm` 的 tgz 解到 lib/node_modules/pnpm，并生成 bin/pnpm、bin/pnpx。"""
    root = os.path.join(PREFIX, 'lib', 'node_modules', 'pnpm')
    shutil.rmtree(root, ignore_errors=True)
    with tarfile.open(tarball, 'r:gz') as t:
        for m in t.getmembers():
            if not m.name.startswith('package/'):
                continue
            rel = m.name[len('package/'):]
            if not rel or m.isdir():
                continue
            if not (m.isfile() or m.issym() or m.islnk()):
                continue
            data = t.extractfile(m)
            data = data.read() if data is not None else b''
            _write(os.path.join(root, *rel.split('/')), data)
    ensure_pnpm_wrappers()
    print('pnpm installed ->', root)


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
    elif mode == 'pnpm':
        if len(sys.argv) < 3:
            raise SystemExit('usage: extract_termux.py pnpm <pnpm-x.y.z.tgz>')
        install_pnpm(sys.argv[2])
    else:
        verify()
