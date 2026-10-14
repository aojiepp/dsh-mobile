# patch_android_fs.py — DSH 树 Android 文件系统补丁（适配 dsh 0.2.0-rc.2）
# Android SELinux 禁止应用硬链接（EACCES），DSH 的会话落盘/附件存储用
# link() 做原子发布 —— 在 Android 上回退为 rename()/copyFile()。
# 0.2.0 新增：会话写锁改用原生 flock（@deepseek-ai/node-addon-system），
# Android 无对应预编译产物，补丁让锁获取在 ERR_FLOCK_UNSUPPORTED_PLATFORM 时
# 放行（手机端单进程单用户，放宽可接受）。
# 0.2.0 新增：dsh-app-boot 的 internalModules() 硬性加载原生 addon
# node-addon-require-builtin 拿 Node 内部 ESM/CJS loader（插件路由拦截），
# Android 无对应预编译产物。App 启动 node 本就带 --expose-internals，
# 补丁照抄 cordis-plugin-loader 的官方回退模式：优先直接 require 内部模块，
# 失败再走 addon。功能无退化。
# 用法: python patch_android_fs.py <dsh-app 根目录>
import os, sys

# ---------- dsh-session-persistence-jsonl ----------
# import 行：link 改名为 posixLink，引入 rename，并在其后注入带回退的 link 包装。
# 这样 3142 行的裸 link(tmp, finalPath) 与 defaultFileSystem.link（internals.fs.link）
# 会同时走 Android 回退。
SESS_IMPORT_OLD = 'import { link, lstat, mkdir, mkdtemp, open, readFile, readdir, realpath, rm, stat, truncate } from "node:fs/promises";'
SESS_IMPORT_NEW = (
    'import { link as posixLink, lstat, mkdir, mkdtemp, open, readFile, readdir, realpath, rename, rm, stat, truncate } from "node:fs/promises";\n'
    '/* Android: apps cannot hardlink (SELinux EACCES); rename is atomic on the same fs.\n'
    '   The no-replace guarantee is relaxed (single-user app). */\n'
    'const link = async (from, to) => {\n'
    '\ttry {\n'
    '\t\treturn await posixLink(from, to);\n'
    '\t} catch (error) {\n'
    '\t\tif (error?.code === "EACCES" || error?.code === "EPERM" || error?.code === "EOPNOTSUPP") return rename(from, to);\n'
    '\t\tthrow error;\n'
    '\t}\n'
    '};'
)

# flock：非 win32 平台走 tryLockExclusive；Android 上该原生 addon 没有产物，
# 抛 ERR_FLOCK_UNSUPPORTED_PLATFORM —— 放行，按无锁继续（单进程单用户）。
SESS_FLOCK_OLD = (
    '\t\t\t\ttry {\n'
    '\t\t\t\t\tawait tryLockExclusive(handle.fd);\n'
    '\t\t\t\t} catch (error) {\n'
    '\t\t\t\t\tif (isLockContention(error)) throw new SessionAlreadyOwnedError(id);\n'
    '\t\t\t\t\tthrow error;\n'
    '\t\t\t\t}'
)
SESS_FLOCK_NEW = (
    '\t\t\t\ttry {\n'
    '\t\t\t\t\tawait tryLockExclusive(handle.fd);\n'
    '\t\t\t\t} catch (error) {\n'
    '\t\t\t\t\tif (isLockContention(error)) throw new SessionAlreadyOwnedError(id);\n'
    '\t\t\t\t\t/* Android: flock addon has no android build; the app is single-process,\n'
    '\t\t\t\t\t   so proceed without the kernel lock. */\n'
    '\t\t\t\t\tif (error?.code !== "ERR_FLOCK_UNSUPPORTED_PLATFORM") throw error;\n'
    '\t\t\t\t}'
)

# ---------- dsh-attachment-local ----------
ATT_IMPORT_OLD = 'import { chmod, link, mkdir, open, readFile, rename, rm, unlink, writeFile } from "node:fs/promises";'
ATT_IMPORT_NEW = (
    'import { chmod, copyFile, link, mkdir, open, readFile, rename, rm, unlink, writeFile } from "node:fs/promises";\n'
    '/* Android: apps cannot hardlink (SELinux EACCES). Alias publishes fall back to a\n'
    '   copy (object is immutable, so a copy is equivalent); staged publishes fall back\n'
    '   to an atomic rename. The no-replace guarantee is relaxed (single-user app). */\n'
    'const linkAliasOrCopy = async (source, target) => {\n'
    '\ttry {\n'
    '\t\treturn await link(source, target);\n'
    '\t} catch (error) {\n'
    '\t\tif (error?.code === "EACCES" || error?.code === "EPERM" || error?.code === "EOPNOTSUPP")\n'
    '\t\t\treturn copyFile(source, target, constants.COPYFILE_EXCL);\n'
    '\t\tthrow error;\n'
    '\t}\n'
    '};\n'
    'const linkStagedOrRename = async (from, to) => {\n'
    '\ttry {\n'
    '\t\treturn await link(from, to);\n'
    '\t} catch (error) {\n'
    '\t\tif (error?.code === "EACCES" || error?.code === "EPERM" || error?.code === "EOPNOTSUPP")\n'
    '\t\t\treturn rename(from, to);\n'
    '\t\tthrow error;\n'
    '\t}\n'
    '};'
)
ATT_ALIAS_OLD = '\t\t\tawait link(source, target);'
ATT_ALIAS_NEW = '\t\t\tawait linkAliasOrCopy(source, target);'
ATT_STAGED_OLD = '\t\t\tawait link(staged.path, target);'
ATT_STAGED_NEW = '\t\t\tawait linkStagedOrRename(staged.path, target);'
ATT_UNLINK_OLD = '\t\tawait unlink(staged.path);'
ATT_UNLINK_NEW = ('\t\t/* renamed above; the temp path no longer exists */\n'
                  '\t\tawait unlink(staged.path).catch(() => {});')

# ---------- dsh-fs-local（write 工具原子发布，0.2.0 与旧版同形） ----------
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


# ---------- dsh-app-boot（internalModules 拿内部 loader，0.2.0 新增） ----------
BOOT_OLD = '\tconst addon = createRequire(import.meta.url)("node-addon-require-builtin");'
BOOT_NEW = ('\tconst req = createRequire(import.meta.url);\n'
            '\t/* Android: node-addon-require-builtin has no android build; the app launches\n'
            '\t   node with --expose-internals, so internal modules are directly requirable\n'
            '\t   (same fallback as cordis-plugin-loader\'s requireInternal). */\n'
            '\tconst addon = {\n'
            '\t\trequireBuiltin: (id) => {\n'
            '\t\t\tif (process.execArgv.includes("--expose-internals")) try {\n'
            '\t\t\t\treturn req(id);\n'
            '\t\t\t} catch {}\n'
            '\t\t\treturn req("node-addon-require-builtin").requireBuiltin(id);\n'
            '\t\t}\n'
            '\t};')


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
    boot = os.path.join(root, 'node_modules', '@deepseek-ai',
                        'dsh-app-boot', 'lib', 'index.js')
    n = 0
    print('patch session persistence:', sess)
    n += patch_file(sess, [(SESS_IMPORT_OLD, SESS_IMPORT_NEW),
                           (SESS_FLOCK_OLD, SESS_FLOCK_NEW)])
    print('patch attachment local:', att)
    n += patch_file(att, [(ATT_IMPORT_OLD, ATT_IMPORT_NEW),
                          (ATT_ALIAS_OLD, ATT_ALIAS_NEW),
                          (ATT_STAGED_OLD, ATT_STAGED_NEW),
                          (ATT_UNLINK_OLD, ATT_UNLINK_NEW)])
    print('patch fs-local (write tool):', fsl)
    n += patch_file(fsl, [(FSL_OLD, FSL_NEW)])
    print('patch app-boot (internalModules fallback):', boot)
    n += patch_file(boot, [(BOOT_OLD, BOOT_NEW)])
    print('done, %d replacements applied' % n)
    return n


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
        os.environ.get('TEMP', '/tmp'), 'dshm-trim', 'app')
    patch_root(root)


if __name__ == '__main__':
    main()
