# patch_runpath.py — 把 Termux 二进制里硬编码的 RUNPATH 改为 $ORIGIN 相对路径
#
# v3：遍历范围扩到 libexec/**（git 子命令二进制在 libexec/git-core/，非 ELF 自动跳过）。
#   bin/xxx                  → $ORIGIN/../lib
#   libexec/xxx              → $ORIGIN/../lib
#   libexec/git-core/xxx     → $ORIGIN/../../lib
#   lib/*.so                 → $ORIGIN
# 独立运行：python payload/patch_runpath.py
import os, struct, sys


def patch_runpath(path, new_rpath, label=None):
    label = label or path
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
            print('patched', label, ':', old, '->', new_rpath)
            patched += 1
    return patched


def target_rpath(rel_dir):
    """相对前缀的目录 → $ORIGIN 形式的 RUNPATH。"""
    rel_dir = rel_dir.replace('\\', '/').strip('/')
    if rel_dir == 'lib':
        return '$ORIGIN'
    depth = 0 if rel_dir in ('', '.') else rel_dir.count('/') + 1
    return '$ORIGIN/' + ('../' * depth) + 'lib'


def patch_tree(root):
    """遍历 bin/、libexec/（递归）、lib/，只处理 ELF。返回 (patched, skipped)。"""
    patched = skipped = 0
    targets = [os.path.join(root, 'bin')]
    libexec = os.path.join(root, 'libexec')
    for dirpath, _, files in os.walk(libexec):
        targets.append(dirpath)
    targets.append(os.path.join(root, 'lib'))
    for base in targets:
        if not os.path.isdir(base):
            continue
        for fn in sorted(os.listdir(base)):
            p = os.path.join(base, fn)
            if not os.path.isfile(p):
                continue
            with open(p, 'rb') as f:
                if f.read(4) != b'\x7fELF':
                    skipped += 1           # wrapper 脚本 / 数据文件
                    continue
            new = target_rpath(os.path.relpath(base, root).replace('\\', '/'))
            patched += patch_runpath(p, new, os.path.relpath(p, root))
    print('all patched: %d ELF, %d non-ELF skipped' % (patched, skipped))
    return patched, skipped


if __name__ == '__main__':
    prefix = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'termux', 'usr')
    patch_tree(prefix)
