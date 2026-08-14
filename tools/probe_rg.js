// probe_rg.js — 在设备上定位 grep 工具的 ripgrep 解析/启动失败原因
const { spawnSync } = require('child_process');
const fs = require('fs');
(async () => {
  try {
    const mod = await import('@vscode/ripgrep');
    console.log('IMPORT OK rgPath=' + mod.rgPath);
    console.log('exists=' + fs.existsSync(mod.rgPath));
    const r = spawnSync(mod.rgPath, ['--version'], { encoding: 'utf8', timeout: 8000 });
    console.log(JSON.stringify({ spawnError: r.error ? (r.error.code || r.error.message) : null, status: r.status, out: (r.stdout || '').slice(0, 60), err: (r.stderr || '').slice(0, 120) }));
  } catch (e) {
    console.log('IMPORT ERR: ' + (e && e.message));
  }
  // 顺便看父进程 env 里 LD_LIBRARY_PATH 是否存在（子进程继承情况由 scrubbedParentEnv 决定）
  console.log('parent LD_LIBRARY_PATH=' + (process.env.LD_LIBRARY_PATH || '(unset)'));
})();
