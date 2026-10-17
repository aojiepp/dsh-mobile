package com.dsh.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

/**
 * DSH Mobile 主界面：全屏 WebView 承载 DSH 完整功能（无原生标题栏）。
 * 深度移动端适配：JS 补丁层修复 DSH 在窄屏上的布局冲突（底部输入栏被
 * transform 顶出屏幕 / 顶部栏右溢出 / 详情抽屉出界 / 图片代码溢出），
 * 页面内注入悬浮菜单按钮（⚙）桥接原生控制菜单。
 */
public class MainActivity extends Activity {

    public static final String PREFS = "dsh_mobile";
    public static final String KEY_URL = "server_url";
    public static final String KEY_KEEP_ON = "keep_screen_on";
    public static final String KEY_DESKTOP = "desktop_mode";
    public static final String KEY_MODE = "server_mode";
    public static final String KEY_API_KEY = "api_key";
    public static final String MODE_LOCAL = "local";
    public static final String MODE_PC = "pc";
    public static final String DEFAULT_URL = "http://127.0.0.1:3080";
    public static final String VERSION = "2.9";

    private WebView web;
    private ProgressBar progress;
    private View errorView;
    private TextView errorText;
    private SharedPreferences prefs;
    private String lastLoadedUrl = "";
    private final Handler retryHandler = new Handler();
    private Runnable retryTask;
    private int errorCount = 0;
    private long lastPauseAt = 0;
    /** 本次加载是否已失败（onPageFinished 也会在失败后回调，需区分真成功） */
    private boolean loadFailed = false;
    /** 本地模式 401 后是否已自动换过 token（防死循环，真成功后复位） */
    private boolean retriedAuth = false;
    /** 上次 onResume 时的运行模式，用于检测用户在设置页切换了模式 */
    private String lastMode = "";

    // ---- v2.9 首启引导（解压进度）----
    /** 引导卡片：首次启动解压运行环境时显示真实进度，避免"白屏发呆" */
    private View bootView;
    private ProgressBar bootBar;
    private TextView bootTitle;
    private TextView bootPhase;
    private TextView bootHint;
    private View bootRetry;
    private final Handler bootHandler = new Handler();
    private Runnable bootTask;
    /** 引导态：为真时禁止 WebView 加载、也不让错误页覆盖引导卡片 */
    private boolean bootstrapping = false;
    /** 防止重复起「等端口」线程 */
    private boolean waitingServer = false;
    private int lastBootPct = -2;
    private long lastBootProgressAt = 0;
    private boolean bootFailed = false;

    /** 悬浮控制按钮（⚙），注入到页面右上，点击桥接原生菜单（DeepSeek 蓝单风格） */
    private static final String FAB_JS =
        "(function(){" +
        "try{" +
        "if(document.getElementById('dsh-fab'))return;" +
        "var fab=document.createElement('button');" +
        "fab.id='dsh-fab';" +
        "fab.textContent='\u2699';" +
        "fab.setAttribute('aria-label','DSH Mobile 菜单');" +
        "fab.style.cssText='position:fixed;right:12px;top:64px;z-index:9990;" +
        "width:40px;height:40px;border-radius:50%;border:1px solid rgba(77,107,254,.35);" +
        "background:rgba(255,255,255,.92);color:#4D6BFE;font-size:19px;line-height:1;" +
        "box-shadow:0 2px 10px rgba(31,41,55,.15);';" +
        "fab.onclick=function(){try{DshShell.openMenu();}catch(e){}};" +
        "document.body.appendChild(fab);" +
        "}catch(e){}" +
        "})();";

    /** 移动端布局补丁层：几何探测 + 定点修复 + MutationObserver 持续守护 */
    private static final String PATCH_JS =
        "(function(){" +
        "window.__dshmT0=window.__dshmT0||Date.now();" +
        "var vw=function(){return document.documentElement.clientWidth;};" +
        "var vh=function(){return document.documentElement.clientHeight;};" +
        "function patch(){" +
        "try{" +
        "var W=vw(),H=vh();" +
        "var all=document.querySelectorAll('body *');" +
        "for(var i=0;i<all.length;i++){" +
        "var e=all[i];" +
        "if(getComputedStyle(e).position!=='fixed')continue;" +
        "var b=e.getBoundingClientRect();" +
        "if(b.height>25&&b.height<130&&b.width>W*0.5&&(H-b.bottom)<40){" +
        "e.style.transform='none';e.style.translate='none';" +
        "e.style.top='auto';e.style.bottom='0px';" +
        "e.style.left='56px';e.style.marginLeft='0px';e.style.width=(W-56)+'px';" +
        "}else if(b.height>35&&b.top<=2&&b.width>=W-1){" +
        "var off2=(b.left>=40?b.left:0);" +
        "e.style.width=(W-off2)+'px';" +
        "}" +
        "}" +
        "var imgs=document.querySelectorAll('img');" +
        "for(var k=0;k<imgs.length;k++){" +
        "var im=imgs[k],ib=im.getBoundingClientRect();" +
        "if(ib.width>200&&ib.right>W+2){im.style.width=(W-ib.left)+'px';}" +
        "}" +
        "var bub=document.querySelectorAll('[class*=_bubble]');" +
        "for(var n=0;n<bub.length;n++){" +
        "var be=bub[n];" +
        "if(getComputedStyle(be).position!=='fixed')continue;" +
        "var bb=be.getBoundingClientRect();" +
        "if(bb.width>W-24){be.style.maxWidth=(W-24)+'px';}" +
        "if(bb.bottom>H-80&&bb.height<60){be.style.bottom='76px';be.style.top='auto';}" +
        "}" +
        "var scCol=document.querySelector('[class*=sidebarCol]');" +
        "var rootEl=scCol?scCol.querySelector('[class*=root]'):null;" +
        "var expanded=!!(rootEl&&rootEl.className.indexOf('collapsed')<0);" +
        "if(!window.__dshmBackdrop){" +
        "var dl=document.createElement('div');" +
        "dl.id='dshm-backdrop';" +
        "dl.style.cssText='position:fixed;top:0;right:0;bottom:0;left:0;z-index:599;" +
        "background:rgba(0,0,0,.45);display:none;';" +
        "dl.onclick=function(){" +
        "var t=dl.__dshmToggle;" +
        "if(t&&document.body.contains(t)){t.click();}" +
        "};" +
        "document.body.appendChild(dl);" +
        "window.__dshmBackdrop=dl;" +
        "}" +
        "var dl2=window.__dshmBackdrop;" +
        "if(expanded){" +
        "scCol.style.position='fixed';scCol.style.top='0px';scCol.style.left='0px';" +
        "scCol.style.bottom='0px';scCol.style.width='360px';scCol.style.maxWidth='100vw';" +
        "scCol.style.height='100%';scCol.style.zIndex='600';" +
        "scCol.style.overflowY='auto';scCol.style.overflowX='hidden';" +
        "var tg=scCol.querySelector('[class*=toggle]');" +
        "dl2.__dshmToggle=tg;dl2.style.display='block';" +
        "if(!window.__dshmClosed&&(Date.now()-window.__dshmT0)<8000){" +
        "window.__dshmClosed=true;if(tg)tg.click();" +
        "}" +
        "}else{" +
        "scCol.style.position='';scCol.style.top='';scCol.style.left='';scCol.style.bottom='';" +
        "scCol.style.width='';scCol.style.maxWidth='';scCol.style.height='';scCol.style.zIndex='';" +
        "scCol.style.overflowY='';scCol.style.overflowX='';" +
        "dl2.style.display='none';" +
        "}" +
        "var fabEl=document.getElementById('dsh-fab');" +
        "if(fabEl){fabEl.style.display=expanded?'none':'';}" +
        "var dw=document.querySelector('[class*=ydkMvW_root]');" +
        "if(dw){" +
        "var dtext=(dw.innerText||'').replace(/\\s+/g,'');" +
        "if(dw.getBoundingClientRect().width>50&&dtext.length>30){" +
        "dw.style.position='fixed';dw.style.top='0px';dw.style.bottom='0px';" +
        "dw.style.left='0px';dw.style.right='auto';" +
        "dw.style.maxWidth=W+'px';dw.style.zIndex='500';" +
        "}else if(dw.style.position==='fixed'){" +
        "dw.style.position='';dw.style.top='';dw.style.bottom='';" +
        "dw.style.left='';dw.style.right='';dw.style.maxWidth='';dw.style.zIndex='';" +
        "}" +
        "}" +
        "}catch(err){}" +
        "}" +
        "var st=document.createElement('style');" +
        "st.textContent='body *{max-width:100%!important;}" +
        "img,video{height:auto!important;}" +
        "pre{overflow-x:auto!important;}" +
        "[class*=_row],[class*=card]{overflow-x:auto!important;}" +
        "@media (max-width:479px){.pI_x6G_frame{grid-template-columns:56px minmax(0px,1fr) 0px!important;}" +
        "[class*=sidebarCol]:has(> * > [class*=root]:not([class*=collapsed])){" +
        "position:fixed!important;top:0!important;left:0!important;bottom:0!important;" +
        "width:360px!important;max-width:100vw!important;height:100%!important;z-index:600!important;" +
        "overflow-y:auto!important;overflow-x:hidden!important;" +
        "box-shadow:0 0 24px rgba(0,0,0,.45)!important;}}';" +
        "document.head.appendChild(st);" +
        "patch();" +
        "var t=null;" +
        "var mo=new MutationObserver(function(){if(t)clearTimeout(t);t=setTimeout(patch,300);});" +
        "mo.observe(document.body,{childList:true,subtree:true,attributes:true,attributeFilter:['style','class']});" +
        "setInterval(patch,2000);" +
        "window.addEventListener('resize',patch);" +
        "if(window.visualViewport){window.visualViewport.addEventListener('resize',patch);}" +
        "})();";

    /** 移动版 viewport 与触摸体验微调 */
    private static final String TWEAKS_MOBILE =
        "(function(){" +
        "try{" +
        "var m=document.querySelector('meta[name=viewport]');" +
        "if(!m){m=document.createElement('meta');m.name='viewport';document.head.appendChild(m);}" +
        "m.setAttribute('content','width=device-width, initial-scale=1.0, user-scalable=yes');" +
        "var s=document.createElement('style');" +
        "s.textContent='html{-webkit-text-size-adjust:100%;text-size-adjust:100%;}" +
        "body{-webkit-tap-highlight-color:transparent;}';" +
        "document.head.appendChild(s);" +
        "}catch(e){}" +
        "})();";

    /** 桌面版注入：固定 980px 布局，靠双指缩放，兜底复杂面板操作 */
    private static final String TWEAKS_DESKTOP =
        "(function(){" +
        "try{" +
        "var m=document.querySelector('meta[name=viewport]');" +
        "if(!m){m=document.createElement('meta');m.name='viewport';document.head.appendChild(m);}" +
        "m.setAttribute('content','width=980');" +
        "}catch(e){}" +
        "})();";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        web = findViewById(R.id.webview);
        progress = findViewById(R.id.progress);
        errorView = findViewById(R.id.error_view);
        errorText = findViewById(R.id.error_text);

        bootView = findViewById(R.id.boot_view);
        bootBar = findViewById(R.id.boot_bar);
        bootTitle = findViewById(R.id.boot_title);
        bootPhase = findViewById(R.id.boot_phase);
        bootHint = findViewById(R.id.boot_hint);
        bootRetry = findViewById(R.id.btn_boot_retry);

        findViewById(R.id.btn_retry).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { retryAction(); }
        });
        findViewById(R.id.btn_settings).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openSettings(); }
        });
        bootRetry.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { requestReextract(); }
        });
        findViewById(R.id.btn_boot_settings).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openSettings(); }
        });

        setupWebView();
        requestNotificationPermission();
        lastMode = localMode() ? MODE_LOCAL : MODE_PC;

        if (localMode()) {
            // 本机内置服务模式：先看运行环境是否就绪 —— 没就绪显示解压进度，
            // 就绪则等服务端口后加载页面（v2.9：不再盲等 4 分钟白屏）
            if (savedInstanceState != null) {
                web.restoreState(savedInstanceState);
                String u = web.getUrl();
                lastLoadedUrl = (u == null || u.isEmpty()) ? "" : u;
            }
            ensureLocalBoot();
        } else {
            // 连接电脑模式：停掉本机服务，把 127.0.0.1:3080 让给 adb reverse 隧道
            stopLocalServer();
            if (savedInstanceState != null) {
                web.restoreState(savedInstanceState);
                String u = web.getUrl();
                lastLoadedUrl = (u == null || u.isEmpty()) ? currentUrl() : u;
            } else {
                reload();
            }
        }
    }

    // ---------- v2.9 本地启动流程：引导页 + 真实解压进度 ----------

    private void startServerService(String action) {
        android.content.Intent svc = new android.content.Intent(this, DshServerService.class);
        svc.setAction(action);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(svc);
        } else {
            startService(svc);
        }
    }

    /** 本地模式入口：按「运行环境是否就绪」决定显示引导页还是直接等端口加载 */
    private void ensureLocalBoot() {
        startServerService(DshServerService.ACTION_START);
        if (DshServerService.isPayloadReady(this)) {
            hideBootstrap();
            waitForServerAndLoad();
        } else {
            showBootstrap();
        }
    }

    /** 显示解压引导卡片并开始轮询真实进度 */
    private void showBootstrap() {
        bootstrapping = true;
        bootFailed = false;
        lastBootPct = -2;
        lastBootProgressAt = 0;
        errorView.setVisibility(View.GONE);
        bootView.setVisibility(View.VISIBLE);
        bootTitle.setText(R.string.boot_title);
        bootBar.setIndeterminate(true);
        bootPhase.setText(R.string.boot_phase_wait);
        bootHint.setText(R.string.boot_hint);
        bootHint.setVisibility(View.VISIBLE);
        bootRetry.setVisibility(View.GONE);
        if (bootTask == null) {
            bootTask = new Runnable() {
                @Override public void run() { pollBootstrap(); }
            };
        }
        bootHandler.removeCallbacks(bootTask);
        bootHandler.post(bootTask);
    }

    private void pollBootstrap() {
        if (!bootstrapping) return;
        if (DshServerService.isPayloadReady(this)) {
            // 解压完成：切到「等服务端口」，交给 waitForServerAndLoad
            hideBootstrap();
            waitForServerAndLoad();
            return;
        }
        String pt = DshServerService.payloadState(this);
        if (pt.startsWith(DshServerService.STATE_FAILED)) {
            showBootFailure(DshServerService.extractError(this));
            return;
        }
        int pct = DshServerService.payloadPercent(this);
        long now = System.currentTimeMillis();
        if (pct >= 0) {
            if (pct != lastBootPct) {
                lastBootPct = pct;
                lastBootProgressAt = now;
            }
            bootBar.setIndeterminate(false);
            bootBar.setProgress(pct);
            String detail = DshServerService.payloadDetail(this);
            bootPhase.setText(detail == null || detail.isEmpty()
                    ? getString(R.string.boot_phase_pct, pct)
                    : detail);
            // 进度长时间不动 → 给用户一条自救路径
            if (lastBootProgressAt > 0 && now - lastBootProgressAt > 25000
                    && bootRetry.getVisibility() != View.VISIBLE) {
                bootHint.setText(R.string.boot_stalled);
                bootRetry.setVisibility(View.VISIBLE);
            }
        } else {
            bootBar.setIndeterminate(true);
            bootPhase.setText(R.string.boot_phase_wait);
        }
        bootHandler.postDelayed(bootTask, 400);
    }

    private void showBootFailure(String reason) {
        bootFailed = true;
        bootstrapping = false;
        bootHandler.removeCallbacks(bootTask);
        bootView.setVisibility(View.VISIBLE);
        bootTitle.setText(R.string.boot_failed_title);
        bootBar.setIndeterminate(false);
        bootBar.setProgress(0);
        String r = (reason == null || reason.isEmpty())
                ? getString(R.string.status_failed) : reason;
        bootPhase.setText(r);
        bootHint.setText(R.string.boot_failed_hint);
        bootHint.setVisibility(View.VISIBLE);
        bootRetry.setVisibility(View.VISIBLE);
        android.util.Log.e("DshMobile", "bootstrap failed: " + r);
    }

    private void hideBootstrap() {
        bootstrapping = false;
        bootHandler.removeCallbacks(bootTask);
        bootView.setVisibility(View.GONE);
    }

    /** 「重新解压」：清运行时并重来（dsh-home 里的会话与凭据不受影响） */
    private void requestReextract() {
        lastBootPct = -2;
        lastBootProgressAt = 0;
        startServerService(DshServerService.ACTION_RESTART_EXTRACT);
        showBootstrap();
    }

    /** 运行环境就绪后：等服务端口 → 取启动 token → 加载页面 */
    private void waitForServerAndLoad() {
        if (waitingServer) return;
        waitingServer = true;
        new Thread(new Runnable() {
            @Override public void run() {
                final boolean ok = DshServerService.waitForPort(3080, 150000);
                // 0.2.0 起 dsh web 有启动 token：从服务日志取带 token 的 URL
                final String launchUrl = ok ? readLaunchUrl(30000) : null;
                final String srv = DshServerService.serverState(MainActivity.this);
                waitingServer = false;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (ok) {
                            if (launchUrl != null) {
                                lastLoadedUrl = launchUrl;
                                errorView.setVisibility(View.GONE);
                                web.loadUrl(launchUrl);
                            } else {
                                // 没取到 token 也先试直连：401 会触发自动换 token
                                reload();
                            }
                        } else {
                            showServerFailure(srv);
                        }
                    }
                });
            }
        }).start();
    }

    private void showServerFailure(String srv) {
        bootstrapping = false;
        hideBootstrap();
        StringBuilder sb = new StringBuilder(getString(R.string.error_local));
        if (srv != null && !srv.isEmpty()) sb.append("\n\n").append(srv);
        sb.append("\n\n").append(getString(R.string.error_local_hint));
        errorText.setText(sb.toString());
        errorView.setVisibility(View.VISIBLE);
        findViewById(R.id.btn_retry).setVisibility(View.VISIBLE);
        findViewById(R.id.btn_settings).setVisibility(View.VISIBLE);
    }

    /** 重试按钮：运行环境没准备好就重解压，其余情况重载页面 */
    private void retryAction() {
        if (localMode() && !DshServerService.isPayloadReady(this)) {
            requestReextract();
            return;
        }
        if (localMode() && !DshServerService.isPortOpen(3080, 400)) {
            startServerService(DshServerService.ACTION_START);
        }
        reload();
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 100);
        }
    }

    private boolean localMode() {
        return MODE_LOCAL.equals(prefs.getString(KEY_MODE, MODE_LOCAL));
    }

    private String currentUrl() {
        String url = prefs.getString(KEY_URL, DEFAULT_URL).trim();
        if (url.isEmpty()) url = DEFAULT_URL;
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "http://" + url;
        return url;
    }

    /** 0.2.0 起 dsh web 需要启动 token：扫描服务日志里的 "dsh web: <url>" 行，
     *  取最后一条（node 重启后日志截断重写，token 随进程更新） */
    private String readLaunchUrl(long timeoutMs) {
        java.io.File log = new java.io.File(getFilesDir(), "dsh-server.log");
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (log.exists()) {
                    java.io.BufferedReader r =
                            new java.io.BufferedReader(new java.io.FileReader(log));
                    String line, found = null;
                    while ((line = r.readLine()) != null) {
                        if (line.startsWith("dsh web: http")) {
                            String u = line.substring(9).trim();
                            int sp = u.indexOf(' '); // 去掉可能跟着的 LAN 部分
                            if (sp > 0) u = u.substring(0, sp);
                            found = u;
                        }
                    }
                    r.close();
                    if (found != null) return found;
                }
            } catch (java.io.IOException ignored) {
            }
            try { Thread.sleep(500); } catch (InterruptedException e) { return null; }
        }
        return null;
    }

    private boolean desktopMode() {
        return prefs.getBoolean(KEY_DESKTOP, false);
    }

    private void setupWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setTextZoom(100);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        if (Build.VERSION.SDK_INT >= 33) {
            s.setAlgorithmicDarkeningAllowed(true);
        }
        if (Build.VERSION.SDK_INT >= 29) {
            s.setForceDark(WebSettings.FORCE_DARK_AUTO);
        }
        WebView.setWebContentsDebuggingEnabled(true);

        web.addJavascriptInterface(new ShellBridge(), "DshShell");

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (newProgress >= 100) {
                    progress.setVisibility(View.GONE);
                } else {
                    progress.setVisibility(View.VISIBLE);
                    progress.setProgress(newProgress);
                }
            }
        });

        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                loadFailed = false;
                errorView.setVisibility(View.GONE);
            }
            @Override
            public void onPageFinished(WebView view, String url) {
                progress.setVisibility(View.GONE);
                // chrome 失败页也会回调 onPageFinished：它不是成功，
                // 不能取消自动重连、不能重置退避计数
                if (url != null && url.startsWith("chrome-error://")) return;
                // 有些 WebView 失败时回调的是原地址：再用 JS 查真实地址，
                // 确认是真成功后（失败页 href 恒为 chrome-error://chromewebdata/）才收尾
                view.evaluateJavascript("location.href", new ValueCallback<String>() {
                    @Override public void onReceiveValue(String v) {
                        if (v != null && v.startsWith("\"chrome-error")) return;
                        if (loadFailed) return;
                        errorCount = 0;
                        retriedAuth = false;
                        cancelAutoRetry();
                        String tweaks = FAB_JS
                                + (desktopMode() ? TWEAKS_DESKTOP : (PATCH_JS + TWEAKS_MOBILE));
                        view.evaluateJavascript(tweaks, null);
                        // v2.9 空白页兜底：服务半启动时页面可能停在纯白，给用户出路
                        view.postDelayed(new Runnable() {
                            @Override public void run() { checkBlankPage(view); }
                        }, 6000);
                    }
                });
            }
            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    loadFailed = true;
                    showError(describeError(error));
                }
            }
            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                            android.webkit.WebResourceResponse response) {
                if (request.isForMainFrame() && response != null && response.getStatusCode() >= 400) {
                    // 0.2.0 起本地服务带启动 token：cookie 过期/丢失时用日志里的
                    // 最新 token URL 重换一次凭证，只自动重试一次防死循环
                    if (localMode() && response.getStatusCode() == 401 && !retriedAuth) {
                        loadFailed = true; // 401 错误页的 onPageFinished 不算成功
                        retriedAuth = true;
                        new Thread(new Runnable() {
                            @Override public void run() {
                                final String u = readLaunchUrl(15000);
                                if (u != null) {
                                    runOnUiThread(new Runnable() {
                                        @Override public void run() {
                                            lastLoadedUrl = u;
                                            web.loadUrl(u);
                                        }
                                    });
                                }
                            }
                        }).start();
                        return;
                    }
                    loadFailed = true;
                    showError(getString(R.string.err_http, response.getStatusCode()));
                }
            }
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }
            @Override
            public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                if (Build.VERSION.SDK_INT >= 26 && detail.didCrash()) {
                    MainActivity.this.recreate();
                    return true;
                }
                return false;
            }
        });
    }

    /** v2.9 空白页兜底：服务刚重启时可能有一瞬空白，持续空白说明渲染失败，
     *  给出「重试 / 打开设置」而不是让用户对着白屏发呆 */
    private void checkBlankPage(final WebView view) {
        if (bootstrapping || loadFailed || !localMode()) return;
        if (errorView.getVisibility() == View.VISIBLE) return;
        view.evaluateJavascript(
                "(function(){try{var b=document.body;if(!b)return 'blank';" +
                "var t=(b.innerText||'').trim();var n=b.querySelectorAll('*').length;" +
                "return (t.length===0&&n<10)?'blank':'ok';}catch(e){return 'blank';}})()",
                new ValueCallback<String>() {
                    @Override public void onReceiveValue(String v) {
                        if (v != null && v.contains("blank") && !bootstrapping) {
                            android.util.Log.w("DshMobile", "blank page detected");
                            errorText.setText(getString(R.string.err_blank_page));
                            errorView.setVisibility(View.VISIBLE);
                            findViewById(R.id.btn_retry).setVisibility(View.VISIBLE);
                            findViewById(R.id.btn_settings).setVisibility(View.VISIBLE);
                        }
                    }
                });
    }

    /** JS 桥：页面悬浮按钮 -> 原生控制菜单 */    private class ShellBridge {
        @JavascriptInterface
        public void openMenu() {
            runOnUiThread(new Runnable() {
                @Override public void run() { showControlDialog(); }
            });
        }
    }

    private void showControlDialog() {
        final String[] items = {
            getString(R.string.menu_refresh),
            getString(R.string.dlg_desktop) + (desktopMode() ? " ✓" : ""),
            getString(R.string.dlg_keep) + (prefs.getBoolean(KEY_KEEP_ON, true) ? " ✓" : ""),
            getString(R.string.menu_settings)
        };
        new AlertDialog.Builder(this)
            .setTitle(getString(R.string.app_name) + " v" + VERSION)
            .setItems(items, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int which) {
                    if (which == 0) {
                        reload();
                    } else if (which == 1) {
                        boolean v = !desktopMode();
                        prefs.edit().putBoolean(KEY_DESKTOP, v).apply();
                        Toast.makeText(MainActivity.this,
                                v ? R.string.desktop_on : R.string.desktop_off,
                                Toast.LENGTH_SHORT).show();
                        reload();
                    } else if (which == 2) {
                        boolean v = !prefs.getBoolean(KEY_KEEP_ON, true);
                        prefs.edit().putBoolean(KEY_KEEP_ON, v).apply();
                        applyKeepScreenOn();
                    } else {
                        openSettings();
                    }
                }
            })
            .show();
    }

    private void reload() {
        // 运行环境还没解压完时不要加载：WebView 会白屏，且会把引导卡片顶掉
        if (localMode() && !DshServerService.isPayloadReady(this)) {
            showBootstrap();
            return;
        }
        String url = currentUrl();
        lastLoadedUrl = url;
        errorView.setVisibility(View.GONE);
        web.loadUrl(url);
    }

    private void showError(String detail) {
        // 引导态下页面可能只是服务还没起来，别用错误页盖住进度卡片
        if (bootstrapping) return;
        StringBuilder msg = new StringBuilder(getString(R.string.error_connect));
        String hint = errorHint();
        if (!hint.isEmpty()) msg.append("\n\n").append(hint);
        if (detail != null && !detail.isEmpty()) msg.append("\n\n").append(detail);
        errorText.setText(msg.toString());
        errorView.setVisibility(View.VISIBLE);
        errorCount++;
        android.util.Log.i("DshMobile", "connect error #" + errorCount
                + " url=" + currentUrl() + " detail=" + detail);
        if (!localMode()) scheduleAutoRetry();
    }

    /** 根据运行模式与目标地址给出针对性的排查提示 */
    private String errorHint() {
        if (localMode()) return getString(R.string.error_connect_hint_local);
        String host = hostOf(currentUrl());
        boolean loopback = "127.0.0.1".equals(host) || "localhost".equals(host)
                || "::1".equals(host) || "[::1]".equals(host);
        return loopback ? getString(R.string.error_connect_hint_loopback)
                        : getString(R.string.error_connect_hint_lan);
    }

    private static String hostOf(String url) {
        try {
            String h = java.net.URI.create(url).getHost();
            return h == null ? "" : h;
        } catch (Exception e) {
            return "";
        }
    }

    /** 「连接电脑」模式断网自动重连：3s 起指数退避，上限 30s；
     *  自续循环，不依赖 onReceivedError 再次回调（WebView 对失败重试
     *  有时不再报错），真成功由 onPageFinished 的 href 校验取消 */
    private void scheduleAutoRetry() {
        cancelAutoRetry();
        if (localMode()) return;
        long delay = 3000L << Math.min(errorCount - 1, 3);
        if (delay > 30000L) delay = 30000L;
        retryTask = new Runnable() {
            @Override public void run() {
                if (web != null) {
                    web.loadUrl(lastLoadedUrl.isEmpty() ? currentUrl() : lastLoadedUrl);
                }
                // 无论本次加载成败，先安排下一次；真成功时由 onPageFinished 取消
                scheduleAutoRetry();
            }
        };
        retryHandler.postDelayed(retryTask, delay);
    }

    private void cancelAutoRetry() {
        if (retryTask != null) {
            retryHandler.removeCallbacks(retryTask);
            retryTask = null;
        }
    }

    /** 把 WebView 底层错误码翻译成可读信息 */
    private String describeError(WebResourceError e) {
        if (e == null) return "";
        String d = e.getDescription() == null ? "" : e.getDescription().toString();
        switch (e.getErrorCode()) {
            case WebViewClient.ERROR_TIMEOUT: return getString(R.string.err_timeout) + "\n" + d;
            case WebViewClient.ERROR_CONNECT: return getString(R.string.err_connect) + "\n" + d;
            case WebViewClient.ERROR_HOST_LOOKUP: return getString(R.string.err_host) + "\n" + d;
            default: return d;
        }
    }

    private void openSettings() {
        startActivity(new Intent(this, SettingsActivity.class));
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyKeepScreenOn();
        web.onResume();
        String nowMode = localMode() ? MODE_LOCAL : MODE_PC;
        boolean modeChanged = !nowMode.equals(lastMode);
        lastMode = nowMode;
        if (localMode()) {
            startServerService(DshServerService.ACTION_START);
        } else {
            stopLocalServer();
        }
        String want = currentUrl();
        if (!lastLoadedUrl.isEmpty() && !want.equals(lastLoadedUrl)) reload();

        // 从设置页回来：可能刚点过「重新解压」，重新拉起引导轮询
        if (bootstrapping) showBootstrap();

        // 「连接电脑」模式恢复策略：报错页立即重连；长时间后台后长连接/流可能已死，重载恢复
        if (!localMode()) {
            long idleMs = lastPauseAt > 0 ? System.currentTimeMillis() - lastPauseAt : 0;
            if (errorView.getVisibility() == View.VISIBLE) {
                reload();
            } else if (lastPauseAt > 0 && idleMs > 120000) {
                reload();
            }
        }
        lastPauseAt = 0;

        // 用户在设置页切换了模式：按新模式重建页面
        if (modeChanged) {
            if (localMode()) {
                ensureLocalBoot();
            } else {
                reload();
            }
        }
    }

    /** 停止本机内置服务，释放 127.0.0.1:3080（「连接电脑」模式的 adb reverse 隧道需要它） */
    private void stopLocalServer() {
        android.content.Intent stop = new android.content.Intent(this, DshServerService.class);
        stop.setAction(DshServerService.ACTION_STOP);
        startService(stop);
    }

    @Override
    protected void onPause() {
        cancelAutoRetry();
        bootHandler.removeCallbacks(bootTask); // 后台不轮询，回前台由 onResume 恢复
        lastPauseAt = System.currentTimeMillis();
        web.onPause();
        super.onPause();
    }

    private void applyKeepScreenOn() {
        if (prefs.getBoolean(KEY_KEEP_ON, true)) {
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        web.saveState(outState);
        super.onSaveInstanceState(outState);
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
