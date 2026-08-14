# patch_android_fs.py — DSH 树 Android 文件系统补丁（link()→rename()）
# Android SELinux 禁止应用硬链接（EACCES），DSH 的会话落盘/附件存储用
# link()+unlink() 做原子发布 —— 在 Android 上等价替换为 rename()。
# 用法: python patch_android_fs.py <dsh-app 根目录>
import os, sys

IMPORT_OLD = 'import { link, mkdir, mkdtemp, open, readFile, readdir, realpath, rm, stat, truncate } from "node:fs/promises";'
IMPORT_NEW = 'import { mkdir, mkdtemp, open, readFile, readdir, realpath, rename, rm, stat, truncate } from "node:fs/promises";'
LINK_OLD = '\t\tawait link(tmp, finalPath);'
LINK_NEW = ('\t\t/* Android: apps cannot hardlink (SELinux EACCES); rename is atomic on the same fs */\n'
            '\t\tawait rename(tmp, finalPath);')

IMPORT_OLD2 = 'import { chmod, link, mkdir, open, readFile, unlink } from "node:fs/promises";'
IMPORT_NEW2 = 'import { chmod, mkdir, open, readFile, rename, unlink } from "node:fs/promises";'
LINK_OLD2 = '\t\t\tawait link(temporary, target);'
LINK_NEW2 = ('\t\t\t/* Android: apps cannot hardlink (SELinux EACCES); rename is atomic on the same fs */\n'
             '\t\t\tawait rename(temporary, target);')
UNLINK_OLD2 = '\t\tawait unlink(temporary);'
UNLINK_NEW2 = ('\t\t/* renamed above; the temp path no longer exists */\n'
               '\t\tawait unlink(temporary).catch(() => {});')

# 3. dsh-fs-local 的 write 工具原子发布：新文件创建走 link()（no-replace 语义），
#    在 Android 上 EACCES —— 回退为 rename()（同目录原子，单用户应用可放宽）。
FSL_OLD = '\tconst linkFile = internals.linkFile ?? link;'
FSL_NEW = ('\tconst linkFile = internals.linkFile ?? (async (from, to) => {\n'
           '\t\t/* Android: apps cannot hardlink (SELinux EACCES); rename is atomic on\n'
           '\t\t   the same fs. The no-replace guarantee is relaxed (single-user app). */\n'
           '\t\ttry {\n'
           '\t\t\treturn await link(from, to);\n'
           '\t\t} catch (error) {\n'
           '\t\t\tif (error.code === "EACCES" || error.code === "EPERM" || error.code === "EOPNOTSUPP")\n'
           '\t\t\t\treturn rename(from, to);\n'
           '\t\t\tthrow error;\n'
           '\t\t}\n'
           '\t});')

def patch_file(path, pairs):
    if not os.path.exists(path):
        print('  SKIP (absent):', path)
        return 0
    with open(path, 'r', encoding='utf-8') as f:
        s = f.read()
    applied = 0
    for old, new in pairs:
        if old in s:
            s = s.replace(old, new, 1)
            applied += 1
        else:
            print('  WARN: pattern not found:', old[:60].replace('\t', '\\t'))
    if applied:
        with open(path, 'w', encoding='utf-8', newline='') as f:
            f.write(s)
    return applied

def patch_root(root):
    sess = os.path.join(root, 'node_modules', '@deepseek-ai',
                        'dsh-session-persistence-jsonl', 'lib', 'index.js')
    att = os.path.join(root, 'node_modules', '@deepseek-ai',
                       'dsh-attachment-local', 'lib', 'index.js')
    fsl = os.path.join(root, 'node_modules', '@deepseek-ai',
                       'dsh-fs-local', 'lib', 'index.js')
    n = 0
    print('patch session persistence:', sess)
    n += patch_file(sess, [(IMPORT_OLD, IMPORT_NEW), (LINK_OLD, LINK_NEW)])
    print('patch attachment local:', att)
    n += patch_file(att, [(IMPORT_OLD2, IMPORT_NEW2), (LINK_OLD2, LINK_NEW2),
                          (UNLINK_OLD2, UNLINK_NEW2)])
    print('patch fs-local (write tool):', fsl)
    n += patch_file(fsl, [(FSL_OLD, FSL_NEW)])
    print('done, %d replacements applied' % n)
    return n


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
        os.environ.get('TEMP', '/tmp'), 'dshm-trim', 'app')
    patch_root(root)


if __name__ == '__main__':
    main()
