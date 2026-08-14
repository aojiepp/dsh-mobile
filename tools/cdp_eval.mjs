// cdp_eval.mjs — 通过 WebView DevTools Protocol 在 DSH Mobile 的 WebView 里执行 JS
// 用法: node tools/cdp_eval.mjs "<js expression>"
// 前置: adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>
const expr = process.argv[2] || 'document.title';
const targets = await (await fetch('http://127.0.0.1:9222/json')).json();
const page = targets.find((t) => t.type === 'page');
if (!page) {
  console.error('NO_PAGE_TARGET');
  process.exit(3);
}
const ws = new WebSocket(page.webSocketDebuggerUrl);
const timer = setTimeout(() => {
  console.error('TIMEOUT');
  process.exit(2);
}, 10000);
ws.onopen = () => {
  ws.send(JSON.stringify({
    id: 1,
    method: 'Runtime.evaluate',
    params: { expression: expr, returnByValue: true },
  }));
};
ws.onmessage = (e) => {
  const m = JSON.parse(e.data);
  if (m.id === 1) {
    clearTimeout(timer);
    if (m.result && m.result.exceptionDetails) {
      console.error('EXCEPTION: ' + JSON.stringify(m.result.exceptionDetails));
      process.exit(4);
    }
    console.log(JSON.stringify(m.result && m.result.result ? m.result.result.value : null));
    ws.close();
    process.exit(0);
  }
};
ws.onerror = () => {
  console.error('WS_ERROR');
  process.exit(1);
};
