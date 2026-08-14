// probe_spawn.js — 在设备上验证 node 子进程 spawn 行为
const { spawnSync } = require('child_process');
const fs = require('fs');
function t(name, cmd, args, opts) {
  let r;
  try {
    r = spawnSync(cmd, args, Object.assign({ encoding: 'utf8', timeout: 8000 }, opts || {}));
  } catch (e) {
    console.log(JSON.stringify({ name, thrown: String(e) }));
    return;
  }
  console.log(JSON.stringify({
    name,
    spawnError: r.error ? (r.error.code || r.error.message) : null,
    status: r.status,
    out: (r.stdout || '').replace(/\s+/g, ' ').slice(0, 100),
    err: (r.stderr || '').replace(/\s+/g, ' ').slice(0, 100),
  }));
}
console.log('platform=' + process.platform + ' arch=' + process.arch + ' uid=' + (process.getuid ? process.getuid() : '?'));
console.log('PATH=' + (process.env.PATH || '(none)'));
t('sh-abs', '/system/bin/sh', ['-c', 'echo hi-from-sh']);
t('sh-path', 'sh', ['-c', 'echo hi-from-sh-path']);
t('bash-path', 'bash', ['-c', 'echo hi-from-bash']);
t('bash-abs', '/system/bin/bash', ['-c', 'echo hi']);
t('true-abs', '/system/bin/true', []);
t('echo-path', 'echo', ['hello-echo']);
t('mksh-check', 'sh', ['-c', 'type bash 2>&1; ls /system/bin | head -3']);
