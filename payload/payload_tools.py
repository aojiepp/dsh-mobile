# payload_tools.py — deb 解包 + ELF DT_NEEDED 解析
import io, lzma, struct, sys, tarfile

def ar_extract(deb_path, name):
    d = open(deb_path, 'rb').read()
    i = 8
    while i < len(d):
        n = d[i:i+16].decode('ascii', 'replace').strip()
        sz = int(d[i+48:i+58].strip() or 0)
        body = d[i+60:i+60+sz]
        if n == name:
            return body
        i += 60 + sz + (sz % 2)
    return None

def deb_data_tar(deb_path):
    return tarfile.open(fileobj=io.BytesIO(lzma.decompress(ar_extract(deb_path, 'data.tar.xz/'))))

def elf_needed(binary):
    assert binary[:4] == b'\x7fELF'
    e_phoff = struct.unpack_from('<Q', binary, 32)[0]
    e_phentsize = struct.unpack_from('<H', binary, 54)[0]
    e_phnum = struct.unpack_from('<H', binary, 56)[0]
    dynoff = None
    for k in range(e_phnum):
        off = e_phoff + k * e_phentsize
        ptype = struct.unpack_from('<I', binary, off)[0]
        if ptype == 2:  # PT_DYNAMIC
            dynoff = struct.unpack_from('<Q', binary, off + 8)[0]
            break
    assert dynoff, 'no PT_DYNAMIC'
    vals, strtab = [], None
    j = 0
    while True:
        tag, val = struct.unpack_from('<QQ', binary, dynoff + j * 16)
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

if __name__ == '__main__':
    deb = sys.argv[1]
    mode = sys.argv[2] if len(sys.argv) > 2 else 'list'
    t = deb_data_tar(deb)
    if mode == 'list':
        for m in t.getmembers():
            if not m.isdir():
                print(m.name, m.size)
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
