# payload_tools.py — deb 解包 + ELF DT_NEEDED 解析 + Termux 仓库索引/下载
#
# CLI:
#   python payload_tools.py list  <deb>
#   python payload_tools.py extract-file <deb> <inner> <out>
#   python payload_tools.py needed <deb> <inner>
#   python payload_tools.py scan <deb> [...]          # 逐 ELF 打印 DT_NEEDED
#   python payload_tools.py fetch <pkg> [...]         # 按官方 aarch64 索引下载到 debs/（SHA256 校验）
import gzip, hashlib, io, lzma, os, struct, sys, tarfile, urllib.request

REPO = 'https://packages.termux.dev/apt/termux-main'
INDEX_URL = REPO + '/dists/stable/main/binary-aarch64/Packages'
DEBS = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'debs')
_INDEX_CACHE = os.path.join(DEBS, 'Packages.aarch64')


def ar_extract(deb_path, name):
    d = open(deb_path, 'rb').read()
    i = 8
    while i < len(d):
        n = d[i:i+16].decode('ascii', 'replace').strip().rstrip('/')
        sz = int(d[i+48:i+58].strip() or 0)
        body = d[i+60:i+60+sz]
        if n == name:
            return body
        i += 60 + sz + (sz % 2)
    return None


def deb_data_tar(deb_path):
    """打开 deb 的 data.tar.*：优先 xz，其次 gz；zst 需要 zstandard 模块。"""
    d = open(deb_path, 'rb').read()
    i = 8
    members = {}
    while i + 60 <= len(d):
        n = d[i:i+16].decode('ascii', 'replace').strip().rstrip('/')
        sz = int(d[i+48:i+58].strip() or 0)
        members[n] = d[i+60:i+60+sz]
        i += 60 + sz + (sz % 2)
    if 'data.tar.xz' in members:
        return tarfile.open(fileobj=io.BytesIO(lzma.decompress(members['data.tar.xz'])))
    if 'data.tar.gz' in members:
        return tarfile.open(fileobj=io.BytesIO(gzip.decompress(members['data.tar.gz'])))
    if 'data.tar' in members:
        return tarfile.open(fileobj=io.BytesIO(members['data.tar']))
    raise SystemExit('unsupported data.tar.* in ' + deb_path + ': ' + ','.join(members))


def elf_needed(binary):
    assert binary[:4] == b'\x7fELF'
    is64 = binary[4] == 2
    if is64:
        e_phoff = struct.unpack_from('<Q', binary, 32)[0]
        e_phentsize = struct.unpack_from('<H', binary, 54)[0]
        e_phnum = struct.unpack_from('<H', binary, 56)[0]
    else:
        e_phoff = struct.unpack_from('<I', binary, 28)[0]
        e_phentsize = struct.unpack_from('<H', binary, 42)[0]
        e_phnum = struct.unpack_from('<H', binary, 44)[0]
    dynoff = None
    for k in range(e_phnum):
        off = e_phoff + k * e_phentsize
        ptype = struct.unpack_from('<I', binary, off)[0]
        if ptype == 2:  # PT_DYNAMIC
            dynoff = struct.unpack_from('<Q' if is64 else '<I', binary, off + 8)[0]
            break
    assert dynoff, 'no PT_DYNAMIC'
    fmt = '<QQ' if is64 else '<II'
    entsize = 16 if is64 else 8
    vals, strtab = [], None
    j = 0
    while True:
        tag, val = struct.unpack_from(fmt, binary, dynoff + j * entsize)
        if tag == 0:
            break
        if tag == 1:
            vals.append(val)
        elif tag == 5:
            strtab = val
        j += 1
    out = []
    for v in vals:
        s = binary[strtab+v:strtab+v+120].split(b'\0')[0].decode()
        out.append(s)
    return out


# ---------------------------------------------------------------- Termux 索引

def fetch_index(force=False):
    os.makedirs(DEBS, exist_ok=True)
    if force or not os.path.exists(_INDEX_CACHE) or os.path.getsize(_INDEX_CACHE) == 0:
        with urllib.request.urlopen(INDEX_URL, timeout=60) as r:
            data = r.read()
        open(_INDEX_CACHE, 'wb').write(data)
    return open(_INDEX_CACHE, 'rb').read().decode('utf-8', 'replace')


def parse_index(text):
    """→ {package: {field: value}}，字段名小写。"""
    out = {}
    for stanza in text.replace('\r\n', '\n').split('\n\n'):
        if not stanza.strip():
            continue
        fields = {}
        last = None
        for line in stanza.split('\n'):
            if line[:1] in (' ', '\t') and last:
                fields[last] += ' ' + line.strip()
                continue
            if ':' not in line:
                continue
            k, v = line.split(':', 1)
            last = k.strip().lower()
            fields[last] = v.strip()
        if 'package' in fields:
            out[fields['package']] = fields
    return out


def index_entry(pkg, index=None):
    index = index or parse_index(fetch_index())
    if pkg not in index:
        raise SystemExit('package not in index: ' + pkg)
    return index[pkg]


def deb_filename(pkg, index=None):
    """仓库 Filename 的最后一段，':' 换成 '_'（Windows 不允许冒号）。"""
    fn = index_entry(pkg, index)['filename'].rsplit('/', 1)[-1]
    return fn.replace(':', '_')


def download_deb(pkg, outdir=None, index=None):
    index = index or parse_index(fetch_index())
    e = index_entry(pkg, index)
    url = REPO + '/' + e['filename']
    name = deb_filename(pkg, index)
    outdir = outdir or DEBS
    os.makedirs(outdir, exist_ok=True)
    out = os.path.join(outdir, name)
    want_size = int(e.get('size', '0') or 0)
    if os.path.exists(out) and want_size and os.path.getsize(out) == want_size:
        print('cached', name, want_size)
        return out
    tmp = out + '.part'
    with urllib.request.urlopen(url, timeout=120) as r, open(tmp, 'wb') as f:
        while True:
            chunk = r.read(1 << 20)
            if not chunk:
                break
            f.write(chunk)
    data = open(tmp, 'rb').read()
    if want_size and len(data) != want_size:
        raise SystemExit('size mismatch for %s: %d != %d' % (url, len(data), want_size))
    want = (e.get('sha256') or '').lower()
    got = hashlib.sha256(data).hexdigest()
    if want and got != want:
        raise SystemExit('sha256 mismatch for %s: %s != %s' % (url, got, want))
    os.replace(tmp, out)
    print('downloaded', name, len(data), 'sha256', got[:16], '...')
    return out


def scan_deb(deb_path):
    t = deb_data_tar(deb_path)
    for m in t.getmembers():
        if not m.isfile():
            continue
        f = t.extractfile(m)
        if f is None:
            continue
        head = f.read(4)
        if head != b'\x7fELF':
            continue
        f.seek(0)
        try:
            needs = elf_needed(f.read())
        except Exception as e:
            print('PARSE FAIL', m.name, e)
            continue
        print(m.name, '->', ','.join(needs))


if __name__ == '__main__':
    mode = sys.argv[1] if len(sys.argv) > 1 else 'list'
    if mode == 'fetch':
        idx = parse_index(fetch_index())
        for pkg in sys.argv[2:]:
            download_deb(pkg, index=idx)
    elif mode == 'scan':
        for deb in sys.argv[2:]:
            print('==', deb)
            scan_deb(deb)
    else:
        deb = sys.argv[2]
        t = deb_data_tar(deb)
        if mode == 'list':
            for m in t.getmembers():
                if not m.isdir():
                    print(m.name, m.size)
        elif mode == 'links':
            for m in t.getmembers():
                if m.issym():
                    print('SYM', m.name, '->', m.linkname)
                elif m.islnk():
                    print('LNK', m.name, '->', m.linkname)
        elif mode == 'extract-file':
            inner = sys.argv[3]
            out = sys.argv[4]
            f = t.extractfile(inner)
            if f is None:
                raise SystemExit('not found: ' + inner)
            data = f.read()
            open(out, 'wb').write(data)
            print('wrote', len(data), '->', out)
        elif mode == 'needed':
            inner = sys.argv[3]
            f = t.extractfile(inner)
            data = f.read()
            print('DT_NEEDED:', elf_needed(data))
