# patch_runpath.py — 把 Termux 二进制里硬编码的 RUNPATH 改为 $ORIGIN 相对路径
import os, struct, sys

def patch_runpath(path, new_rpath):
    with open(path, 'r+b') as f:
        d = f.read()
    assert d[:4] == b'\x7fELF'
    is64 = d[4] == 2
    if is64:
        e_phoff = struct.unpack_from('<Q', d, 32)[0]
        e_phentsize = struct.unpack_from('<H', d, 54)[0]
        e_phnum = struct.unpack_from('<H', d, 56)[0]
    else:
        e_phoff = struct.unpack_from('<I', d, 28)[0]
        e_phentsize = struct.unpack_from('<H', d, 42)[0]
        e_phnum = struct.unpack_from('<H', d, 44)[0]
    dynoff = None
    for k in range(e_phnum):
        off = e_phoff + k * e_phentsize
        ptype = struct.unpack_from('<I', d, off)[0]
        if ptype == 2:
            dynoff = struct.unpack_from('<Q' if is64 else '<I', d, off + 8)[0]
            break
    assert dynoff, 'no PT_DYNAMIC: ' + path
    entsize = 16 if is64 else 8
    strtab = None
    rpath_offsets = []
    j = 0
    while True:
        tag, val = struct.unpack_from('<QQ' if is64 else '<II', d, dynoff + j * entsize)
        if tag == 0:
            break
        if tag == 5:
            strtab = val
        if tag in (15, 29):
            rpath_offsets.append((tag, val))
        j += 1
    assert strtab is not None, 'no strtab: ' + path
    if not rpath_offsets:
        print('skip (no RUNPATH):', os.path.basename(path))
        return 0
    patched = 0
    with open(path, 'r+b') as f:
        for tag, val in rpath_offsets:
            end = d.index(b'\0', strtab + val)
            old = d[strtab + val:end].decode('ascii', 'replace')
            if len(new_rpath) > len(old):
                raise SystemExit('new rpath too long for %s: %r > %r' % (path, new_rpath, old))
            f.seek(strtab + val)
            f.write(new_rpath.encode('ascii') + b'\0' + b'\0' * (len(old) - len(new_rpath)))
            print('patched', os.path.basename(path), ':', old, '->', new_rpath)
            patched += 1
    return patched

if __name__ == '__main__':
    root = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'termux', 'usr')
    for fn in sorted(os.listdir(os.path.join(root, 'bin'))):
        patch_runpath(os.path.join(root, 'bin', fn), '$ORIGIN/../lib')
    libdir = os.path.join(root, 'lib')
    for fn in sorted(os.listdir(libdir)):
        patch_runpath(os.path.join(libdir, fn), '$ORIGIN')
    print('all patched')
