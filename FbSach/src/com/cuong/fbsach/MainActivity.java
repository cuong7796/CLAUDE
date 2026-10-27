package com.cuong.fbsach;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends Activity {

    private static final String HOME_URL = "https://www.facebook.com/home.php";
    private static final String RECENT_URL = "https://www.facebook.com/home.php?sk=h_chr";
    private static final String MESSAGES_URL = "https://www.facebook.com/messages/";
    private static final String NOTIFICATIONS_URL = "https://www.facebook.com/notifications";

    // Facebook chọn bố cục theo User-Agent. Firefox 70 Android nhận giao diện cảm ứng
    // mà bộ lọc nhắm tới (đã được SlimSocial kiểm chứng: cuộn một ngón, video ổn định).
    private static final String UA_MOBILE =
            "Mozilla/5.0 (Android 10; Mobile; rv:70.0) Gecko/70.0 Firefox/70.0";
    private static final String UA_DESKTOP =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:147.0) Gecko/20100101 Firefox/147.0";

    private static final String PREF_TOTAL = "blocked_total";
    private static final String PREF_PYMK = "hide_pymk";
    private static final String PREF_SUGGESTED = "hide_suggested";
    private static final String PREF_DESKTOP = "desktop";

    private static final int REQ_FILE = 1001;

    // Lớp 1: tên miền quảng cáo / theo dõi chặn ngay ở tầng mạng (khớp cả tên miền con).
    private static final Set<String> BLOCKED_HOSTS = new HashSet<String>(Arrays.asList(
            "an.facebook.com",
            "pixel.facebook.com",
            "connect.facebook.net",
            "doubleclick.net",
            "googlesyndication.com",
            "googleadservices.com",
            "google-analytics.com",
            "googletagmanager.com",
            "googletagservices.com",
            "adservice.google.com",
            "adnxs.com",
            "amazon-adsystem.com",
            "criteo.com",
            "criteo.net",
            "taboola.com",
            "outbrain.com",
            "scorecardresearch.com",
            "moatads.com",
            "adsrvr.org",
            "rubiconproject.com",
            "pubmatic.com",
            "openx.net",
            "casalemedia.com",
            "smartadserver.com",
            "yieldmo.com",
            "teads.tv",
            "applovin.com",
            "unityads.unity3d.com",
            "app-measurement.com"
    ));

    private static final Set<String> FB_HOSTS = new HashSet<String>(Arrays.asList(
            "facebook.com", "fb.com", "fb.me", "messenger.com", "m.me", "fbcdn.net", "fb.watch"
    ));

    private WebView web;
    private ProgressBar progress;
    private FrameLayout root;
    private SharedPreferences prefs;
    private String filterJs = "";
    private int blockedSession;
    private ValueCallback<Uri[]> fileCallback;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private boolean forceDesktop;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("fbsach", MODE_PRIVATE);
        filterJs = readAsset("adfilter.js");

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);
        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        root.addView(progress, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(4)));
        setContentView(root);

        setupWebView();
        updateSubtitle();

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        } else {
            Uri data = getIntent() != null ? getIntent().getData() : null;
            web.loadUrl(data != null && isFacebook(data.getHost()) ? data.toString() : HOME_URL);
        }
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
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setSupportMultipleWindows(false);
        applyUserAgent(prefs.getBoolean(PREF_DESKTOP, false));

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        web.addJavascriptInterface(new Bridge(), "FbSachBridge");
        web.setWebViewClient(new Client());
        web.setWebChromeClient(new Chrome());
        web.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                        String mimeType, long contentLength) {
                download(url, userAgent, contentDisposition, mimeType);
            }
        });
    }

    private void applyUserAgent(boolean desktop) {
        String ua = desktop ? UA_DESKTOP : UA_MOBILE;
        WebSettings s = web.getSettings();
        if (!ua.equals(s.getUserAgentString())) s.setUserAgentString(ua);
    }

    // ---------------------------------------------------------------- lọc

    private void injectFilter(WebView view) {
        String host = Uri.parse(String.valueOf(view.getUrl())).getHost();
        if (!isFacebook(host)) return;
        String cfg = "window.__fbsachCfg={pymk:" + prefs.getBoolean(PREF_PYMK, true)
                + ",suggested:" + prefs.getBoolean(PREF_SUGGESTED, false) + "};";
        String css = "(function(){if(document.getElementById('fbsach-css'))return;"
                + "var st=document.createElement('style');st.id='fbsach-css';"
                + "st.textContent='.fbsach-hidden{display:none!important;}';"
                + "(document.head||document.documentElement).appendChild(st);})();";
        view.evaluateJavascript(cfg + css + filterJs, null);
    }

    private static boolean hostMatches(String host, Set<String> set) {
        if (host == null) return false;
        String h = host.toLowerCase(Locale.ROOT);
        while (true) {
            if (set.contains(h)) return true;
            int dot = h.indexOf('.');
            if (dot < 0) return false;
            h = h.substring(dot + 1);
        }
    }

    private static boolean isFacebook(String host) {
        return hostMatches(host, FB_HOSTS);
    }

    private static boolean isBlockedRequest(Uri uri) {
        String host = uri.getHost();
        if (hostMatches(host, BLOCKED_HOSTS)) return true;
        if (host == null) return false;
        String path = uri.getPath() == null ? "" : uri.getPath();
        // Pixel theo dõi và các endpoint chỉ phục vụ quảng cáo trên chính facebook.com.
        if (hostMatches(host, FB_HOSTS)) {
            if (path.equals("/tr") || path.startsWith("/tr/")) return true;
            if (path.startsWith("/ajax/bz")) return true;
            if (path.startsWith("/adnw_")) return true;
            if (path.startsWith("/audiencenetwork")) return true;
        }
        return false;
    }

    private static WebResourceResponse emptyResponse() {
        return new WebResourceResponse("text/plain", "utf-8",
                new ByteArrayInputStream(new byte[0]));
    }

    private class Bridge {
        @JavascriptInterface
        public void onBlocked(final int n) {
            if (n <= 0 || n > 1000) return;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    blockedSession += n;
                    prefs.edit().putInt(PREF_TOTAL, prefs.getInt(PREF_TOTAL, 0) + n).apply();
                    updateSubtitle();
                }
            });
        }
    }

    private void updateSubtitle() {
        if (getActionBar() != null) {
            getActionBar().setSubtitle(getString(R.string.blocked_count,
                    prefs.getInt(PREF_TOTAL, 0)));
        }
    }

    // ---------------------------------------------------------------- điều hướng

    private class Client extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return handleUrl(request.getUrl());
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            if (isBlockedRequest(request.getUrl())) return emptyResponse();
            return super.shouldInterceptRequest(view, request);
        }

        @Override
        public void onPageCommitVisible(WebView view, String url) {
            injectFilter(view);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            injectFilter(view);
            CookieManager.getInstance().flush();
        }
    }

    private boolean handleUrl(Uri uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost();

        // Liên kết ra ngoài được Facebook bọc qua l.facebook.com/l.php?u=… để theo dõi:
        // bóc địa chỉ thật và mở thẳng bằng trình duyệt.
        if (host != null && (host.equals("l.facebook.com") || host.equals("lm.facebook.com"))) {
            String real = uri.getQueryParameter("u");
            if (real != null) {
                openExternal(Uri.parse(real));
                return true;
            }
        }
        if (scheme.equals("http") || scheme.equals("https")) {
            if (isFacebook(host)) return false;
            openExternal(uri);
            return true;
        }
        if (scheme.equals("intent")) {
            try {
                Intent i = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME);
                String fallback = i.getStringExtra("browser_fallback_url");
                if (fallback != null && isFacebook(Uri.parse(fallback).getHost())) {
                    web.loadUrl(fallback);
                }
            } catch (Exception ignored) {
            }
            return true;
        }
        // fb://, fb-messenger://… → ứng dụng gốc của Facebook (nếu có); không thì bỏ qua.
        if (scheme.startsWith("fb")) return true;
        openExternal(uri);
        return true;
    }

    private void openExternal(Uri uri) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, uri);
            i.addCategory(Intent.CATEGORY_BROWSABLE);
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.no_browser, Toast.LENGTH_SHORT).show();
        }
    }

    // ---------------------------------------------------------------- chrome

    private class Chrome extends WebChromeClient {
        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            progress.setProgress(newProgress);
            progress.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
            if (newProgress >= 60) injectFilter(view);
        }

        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                         FileChooserParams params) {
            if (fileCallback != null) fileCallback.onReceiveValue(null);
            fileCallback = callback;
            Intent i;
            try {
                i = params.createIntent();
                if (params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
                    i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                }
                startActivityForResult(Intent.createChooser(i, null), REQ_FILE);
            } catch (ActivityNotFoundException e) {
                fileCallback = null;
                return false;
            }
            return true;
        }

        @Override
        public void onShowCustomView(View view, CustomViewCallback callback) {
            if (customView != null) {
                callback.onCustomViewHidden();
                return;
            }
            customView = view;
            customViewCallback = callback;
            root.addView(view, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            if (getActionBar() != null) getActionBar().hide();
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }

        @Override
        public void onHideCustomView() {
            exitFullscreen();
        }
    }

    private void exitFullscreen() {
        if (customView == null) return;
        root.removeView(customView);
        customView = null;
        if (customViewCallback != null) customViewCallback.onCustomViewHidden();
        customViewCallback = null;
        if (getActionBar() != null) getActionBar().show();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE) {
            if (fileCallback != null) {
                fileCallback.onReceiveValue(
                        parseFileResult(resultCode, data));
                fileCallback = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    // parseResult chỉ đọc getData(), nên chọn nhiều ảnh (ClipData) phải tự gom.
    private static Uri[] parseFileResult(int resultCode, Intent data) {
        if (resultCode != RESULT_OK || data == null) return null;
        if (data.getClipData() != null && data.getClipData().getItemCount() > 0) {
            Uri[] uris = new Uri[data.getClipData().getItemCount()];
            for (int i = 0; i < uris.length; i++) uris[i] = data.getClipData().getItemAt(i).getUri();
            return uris;
        }
        return WebChromeClient.FileChooserParams.parseResult(resultCode, data);
    }

    private void download(String url, String userAgent, String contentDisposition, String mimeType) {
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) return;
        try {
            String name = URLUtil.guessFileName(url, contentDisposition, mimeType);
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
            r.setMimeType(mimeType);
            String cookies = CookieManager.getInstance().getCookie(url);
            if (cookies != null) r.addRequestHeader("Cookie", cookies);
            r.addRequestHeader("User-Agent", userAgent);
            r.setTitle(name);
            r.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            dm.enqueue(r);
            Toast.makeText(this, R.string.downloading, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            openExternal(Uri.parse(url));
        }
    }

    // ---------------------------------------------------------------- menu

    private static final int M_HOME = 1, M_RECENT = 2, M_MESSAGES = 3, M_NOTIF = 4,
            M_RELOAD = 5, M_TOP = 6, M_PYMK = 7, M_SUGGESTED = 8, M_DESKTOP = 9,
            M_BROWSER = 10, M_ABOUT = 11;

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, M_HOME, 0, R.string.menu_home);
        menu.add(0, M_RECENT, 1, R.string.menu_recent);
        menu.add(0, M_MESSAGES, 2, R.string.menu_messages);
        menu.add(0, M_NOTIF, 3, R.string.menu_notifications);
        menu.add(0, M_RELOAD, 4, R.string.menu_reload);
        menu.add(0, M_TOP, 5, R.string.menu_top);
        menu.add(1, M_PYMK, 6, R.string.menu_hide_pymk).setCheckable(true);
        menu.add(1, M_SUGGESTED, 7, R.string.menu_hide_suggested).setCheckable(true);
        menu.add(1, M_DESKTOP, 8, R.string.menu_desktop).setCheckable(true);
        menu.add(2, M_BROWSER, 9, R.string.menu_open_browser);
        menu.add(2, M_ABOUT, 10, R.string.menu_about);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        menu.findItem(M_PYMK).setChecked(prefs.getBoolean(PREF_PYMK, true));
        menu.findItem(M_SUGGESTED).setChecked(prefs.getBoolean(PREF_SUGGESTED, false));
        menu.findItem(M_DESKTOP).setChecked(prefs.getBoolean(PREF_DESKTOP, false));
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case M_HOME:
                goFeed(HOME_URL);
                return true;
            case M_RECENT:
                goFeed(RECENT_URL);
                return true;
            case M_MESSAGES:
                // Hộp thư trên web chỉ hoạt động đầy đủ với UA máy tính.
                forceDesktop = true;
                applyUserAgent(true);
                web.loadUrl(MESSAGES_URL);
                return true;
            case M_NOTIF:
                goFeed(NOTIFICATIONS_URL);
                return true;
            case M_RELOAD:
                web.reload();
                return true;
            case M_TOP:
                web.evaluateJavascript("window.scrollTo(0,0);", null);
                web.scrollTo(0, 0);
                return true;
            case M_PYMK:
                toggle(PREF_PYMK, true);
                web.reload();
                return true;
            case M_SUGGESTED:
                toggle(PREF_SUGGESTED, false);
                web.reload();
                return true;
            case M_DESKTOP:
                toggle(PREF_DESKTOP, false);
                forceDesktop = false;
                applyUserAgent(prefs.getBoolean(PREF_DESKTOP, false));
                web.reload();
                return true;
            case M_BROWSER:
                if (web.getUrl() != null) openExternal(Uri.parse(web.getUrl()));
                return true;
            case M_ABOUT:
                new AlertDialog.Builder(this)
                        .setTitle(R.string.app_name)
                        .setMessage(getString(R.string.about_text) + "\n\n"
                                + getString(R.string.blocked_count, prefs.getInt(PREF_TOTAL, 0))
                                + " (phiên này: " + blockedSession + ")")
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    private void goFeed(String url) {
        if (forceDesktop) {
            forceDesktop = false;
            applyUserAgent(prefs.getBoolean(PREF_DESKTOP, false));
        }
        web.loadUrl(url);
    }

    private void toggle(String key, boolean def) {
        prefs.edit().putBoolean(key, !prefs.getBoolean(key, def)).apply();
    }

    // ---------------------------------------------------------------- vòng đời

    @Override
    public void onBackPressed() {
        if (customView != null) {
            exitFullscreen();
        } else if (web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Uri data = intent.getData();
        if (data != null && isFacebook(data.getHost())) web.loadUrl(data.toString());
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    protected void onPause() {
        super.onPause();
        web.onPause();
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            root.removeView(web);
            web.destroy();
        }
        super.onDestroy();
    }

    // ---------------------------------------------------------------- tiện ích

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private String readAsset(String name) {
        try {
            InputStream in = getAssets().open(name);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }
}
