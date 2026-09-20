package ng.eduintels.app;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.provider.MediaStore;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.SslErrorHandler;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final String HOME_URL = "https://eduintels.ng/";
    private static final String NATIVE_VERSION = "1.1.0";
    private static final int FILE_CHOOSER_REQUEST = 4101;
    private static final int STORAGE_PERMISSION_REQUEST = 4102;

    private WebView webView;
    private ProgressBar pageProgress;
    private View loadingOverlay;
    private View offlineOverlay;
    private FrameLayout fullscreenContainer;
    private ValueCallback<Uri[]> filePathCallback;
    private Uri pendingCameraUri;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private View customView;
    private DownloadRequest pendingDownload;
    private boolean firstPageVisible = false;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable initialLoadingFailSafe = this::hideInitialLoading;

    private static class DownloadRequest {
        String url, userAgent, contentDisposition, mimeType;
        DownloadRequest(String url, String ua, String cd, String mt) {
            this.url = url;
            this.userAgent = ua;
            this.contentDisposition = cd;
            this.mimeType = mt;
        }
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webview);
        pageProgress = findViewById(R.id.pageProgress);
        loadingOverlay = findViewById(R.id.loadingOverlay);
        offlineOverlay = findViewById(R.id.offlineOverlay);
        fullscreenContainer = findViewById(R.id.fullscreenContainer);
        Button retry = findViewById(R.id.retryButton);
        retry.setOnClickListener(v -> loadHomeOrCurrent());

        configureWebView();
        clearOldShellCacheOnce();

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
            hideInitialLoading();
        } else {
            Uri deepLink = getIntent() != null ? getIntent().getData() : null;
            String start = deepLink != null && isEduIntelsUrl(deepLink.toString())
                ? deepLink.toString()
                : HOME_URL;

            if (isOnline()) {
                webView.loadUrl(start);
                mainHandler.postDelayed(initialLoadingFailSafe, 6500);
            } else {
                showOffline();
            }
        }
    }

    private void configureWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowContentAccess(true);
        s.setAllowFileAccess(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportMultipleWindows(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setUserAgentString(s.getUserAgentString() + " EduIntelsNative/" + NATIVE_VERSION);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WebView.setWebContentsDebuggingEnabled(false);
            s.setSafeBrowsingEnabled(true);
        }

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cookies.setAcceptThirdPartyCookies(webView, true);
        }

        webView.setBackgroundColor(Color.WHITE);
        webView.setWebViewClient(new EduWebViewClient());
        webView.setWebChromeClient(new EduChromeClient());
        webView.setDownloadListener(new EduDownloadListener());
        webView.setOverScrollMode(WebView.OVER_SCROLL_NEVER);
    }

    private void clearOldShellCacheOnce() {
        String key = "native_shell_version";
        String old = getPreferences(MODE_PRIVATE).getString(key, "");
        if (!NATIVE_VERSION.equals(old)) {
            webView.clearCache(true);
            getPreferences(MODE_PRIVATE).edit().putString(key, NATIVE_VERSION).apply();
        }
    }

    private class EduWebViewClient extends WebViewClient {
        @Override public void onPageStarted(WebView view, String url, Bitmap favicon) {
            pageProgress.setVisibility(View.VISIBLE);
            offlineOverlay.setVisibility(View.GONE);
        }

        @Override public void onPageCommitVisible(WebView view, String url) {
            firstPageVisible = true;
            hideInitialLoading();
            injectNativeAppGuards(view);
            mainHandler.postDelayed(() -> injectNativeAppGuards(view), 350);
            mainHandler.postDelayed(() -> injectNativeAppGuards(view), 1200);
        }

        @Override public void onPageFinished(WebView view, String url) {
            hideInitialLoading();
            pageProgress.setVisibility(View.GONE);
            injectNativeAppGuards(view);
            CookieManager.getInstance().flush();
        }

        @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            WebResourceResponse blocked = nativeShellOverride(request.getUrl());
            return blocked != null ? blocked : super.shouldInterceptRequest(view, request);
        }

        @Override @SuppressWarnings("deprecation")
        public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
            WebResourceResponse blocked = nativeShellOverride(Uri.parse(url));
            return blocked != null ? blocked : super.shouldInterceptRequest(view, url);
        }

        @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return handleNavigation(request.getUrl());
        }

        @Override @SuppressWarnings("deprecation")
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return handleNavigation(Uri.parse(url));
        }

        @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (request.isForMainFrame()) {
                hideInitialLoading();
                if (!isOnline()) showOffline();
            }
        }

        @Override public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            handler.cancel();
            hideInitialLoading();
            Toast.makeText(
                MainActivity.this,
                "Secure connection failed. The page was not opened.",
                Toast.LENGTH_LONG
            ).show();
        }
    }

    private WebResourceResponse nativeShellOverride(Uri uri) {
        if (uri == null || uri.getHost() == null) return null;
        String host = uri.getHost().toLowerCase(Locale.US);
        if (!(host.equals("eduintels.ng") || host.equals("www.eduintels.ng"))) return null;

        String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.US);

        // The website PWA controller creates its own blurred full-screen loader.
        // The native app supplies its own loader, so disable that web-only runtime.
        if (path.endsWith("/assets/js/pwa.js") ||
            path.endsWith("/assets/mobile-app.js") ||
            path.endsWith("/assets/js/mobile-app.js")) {
            byte[] data = "/* EduIntels native app: web PWA runtime disabled */"
                .getBytes(StandardCharsets.UTF_8);
            return new WebResourceResponse(
                "application/javascript",
                "UTF-8",
                new ByteArrayInputStream(data)
            );
        }

        // Replace an old registered web service worker with a harmless no-op script
        // if Android WebView checks it again.
        if (path.endsWith("/sw.js")) {
            byte[] data = "self.addEventListener('install',e=>self.skipWaiting());self.addEventListener('activate',e=>e.waitUntil(self.clients.claim()));"
                .getBytes(StandardCharsets.UTF_8);
            return new WebResourceResponse(
                "application/javascript",
                "UTF-8",
                new ByteArrayInputStream(data)
            );
        }

        return null;
    }

    private class EduChromeClient extends WebChromeClient {
        @Override public void onProgressChanged(WebView view, int newProgress) {
            pageProgress.setProgress(newProgress);
            pageProgress.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);

            // Never leave users behind a loading screen just because a third-party
            // resource on the website is slow.
            if (newProgress >= 55 && !firstPageVisible) hideInitialLoading();
        }

        @Override public boolean onShowFileChooser(
            WebView webView,
            ValueCallback<Uri[]> filePath,
            FileChooserParams params
        ) {
            if (filePathCallback != null) filePathCallback.onReceiveValue(null);
            filePathCallback = filePath;
            openFileChooser(params);
            return true;
        }

        @Override public void onShowCustomView(View view, CustomViewCallback callback) {
            if (customView != null) {
                callback.onCustomViewHidden();
                return;
            }
            customView = view;
            customViewCallback = callback;
            fullscreenContainer.addView(
                view,
                new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            );
            fullscreenContainer.setVisibility(View.VISIBLE);
            webView.setVisibility(View.GONE);
        }

        @Override public void onHideCustomView() {
            hideCustomView();
        }

        @Override public boolean onCreateWindow(
            WebView view,
            boolean isDialog,
            boolean isUserGesture,
            Message resultMsg
        ) {
            WebView.HitTestResult result = view.getHitTestResult();
            String url = result != null ? result.getExtra() : null;
            if (url != null) {
                if (isEduIntelsUrl(url) || isTrustedPaymentUrl(url)) view.loadUrl(url);
                else openExternal(Uri.parse(url));
            }
            return false;
        }
    }

    private class EduDownloadListener implements DownloadListener {
        @Override public void onDownloadStart(
            String url,
            String userAgent,
            String contentDisposition,
            String mimetype,
            long contentLength
        ) {
            if (url == null) return;

            if (url.startsWith("blob:") || url.startsWith("data:")) {
                Toast.makeText(
                    MainActivity.this,
                    "This file uses an in-page download. If it does not start, open the page in Chrome.",
                    Toast.LENGTH_LONG
                ).show();
                return;
            }

            DownloadRequest req = new DownloadRequest(
                url,
                userAgent,
                contentDisposition,
                mimetype
            );

            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                pendingDownload = req;
                requestPermissions(
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    STORAGE_PERMISSION_REQUEST
                );
            } else {
                enqueueDownload(req);
            }
        }
    }

    private void injectNativeAppGuards(WebView view) {
        String js =
            "(function(){try{" +
            "window.__EDUINTELS_NATIVE_APP__=true;" +
            "document.documentElement.classList.add('eduintels-native-app');" +
            "var id='eduintels-native-app-guard';" +
            "var s=document.getElementById(id);" +
            "if(!s){s=document.createElement('style');s.id=id;" +
            "s.textContent='" +
            ".eiPwaLoader,.eiPwaProgress,.eiPwaInstall,.eiPwaIosSheet,.eiPwaBottomNav," +
            "[data-ei-pwa-mobile-install-wrap],[data-pwa-install],[data-pwa-install-trigger]," +
            "[data-app-install],.eiPwaInstallBackdrop,.fp-app-install-banner,.fp-app-loader{" +
            "display:none!important;opacity:0!important;visibility:hidden!important;" +
            "pointer-events:none!important;backdrop-filter:none!important}" +
            "';(document.head||document.documentElement).appendChild(s);}" +
            "document.querySelectorAll('.eiPwaLoader,.eiPwaProgress,.eiPwaInstall,.eiPwaIosSheet,.fp-app-loader')" +
            ".forEach(function(el){el.classList.remove('is-open','is-visible','is-loading');});" +
            "if('serviceWorker' in navigator){navigator.serviceWorker.getRegistrations()" +
            ".then(function(rs){rs.forEach(function(r){try{r.unregister();}catch(e){}});}).catch(function(){});}" +
            "if(window.caches&&caches.keys){caches.keys().then(function(keys){keys.forEach(function(k){" +
            "if(/eduintels|pwa|app-shell/i.test(k)){caches.delete(k);}});}).catch(function(){});}" +
            "}catch(e){}})();";

        view.evaluateJavascript(js, null);
    }

    private void hideInitialLoading() {
        mainHandler.removeCallbacks(initialLoadingFailSafe);
        if (loadingOverlay == null || loadingOverlay.getVisibility() != View.VISIBLE) return;

        loadingOverlay.animate()
            .alpha(0f)
            .setDuration(140)
            .withEndAction(() -> {
                loadingOverlay.setVisibility(View.GONE);
                loadingOverlay.setAlpha(1f);
            })
            .start();
    }

    private boolean handleNavigation(Uri uri) {
        if (uri == null) return false;
        String scheme = uri.getScheme() == null
            ? ""
            : uri.getScheme().toLowerCase(Locale.US);
        String url = uri.toString();

        if ("http".equals(scheme) || "https".equals(scheme)) {
            if (isEduIntelsUrl(url) || isTrustedPaymentUrl(url)) return false;
            openExternal(uri);
            return true;
        }

        if ("intent".equals(scheme)) {
            try {
                Intent intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                if (intent.resolveActivity(getPackageManager()) != null) {
                    startActivity(intent);
                } else if (intent.getStringExtra("browser_fallback_url") != null) {
                    webView.loadUrl(intent.getStringExtra("browser_fallback_url"));
                }
            } catch (Exception e) {
                Toast.makeText(this, "No compatible app found.", Toast.LENGTH_SHORT).show();
            }
            return true;
        }

        if ("tel".equals(scheme) ||
            "mailto".equals(scheme) ||
            "sms".equals(scheme) ||
            "market".equals(scheme) ||
            "whatsapp".equals(scheme)) {
            openExternal(uri);
            return true;
        }

        return false;
    }

    private boolean isEduIntelsUrl(String url) {
        try {
            Uri u = Uri.parse(url);
            String host = u.getHost();
            if (host == null) return false;
            host = host.toLowerCase(Locale.US);
            return host.equals("eduintels.ng") ||
                   host.equals("www.eduintels.ng") ||
                   host.endsWith(".eduintels.ng");
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isTrustedPaymentUrl(String url) {
        try {
            String host = Uri.parse(url).getHost();
            if (host == null) return false;
            host = host.toLowerCase(Locale.US);
            return host.equals("checkout.paystack.com") ||
                   host.endsWith(".paystack.co") ||
                   host.endsWith(".paystack.com");
        } catch (Exception e) {
            return false;
        }
    }

    private void openExternal(Uri uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No app can open this link.", Toast.LENGTH_SHORT).show();
        }
    }

    private void openFileChooser(WebChromeClient.FileChooserParams params) {
        Intent content = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        content.addCategory(Intent.CATEGORY_OPENABLE);
        content.setType("*/*");

        if (params != null) {
            String[] accept = params.getAcceptTypes();
            if (accept != null &&
                accept.length == 1 &&
                accept[0] != null &&
                !accept[0].isEmpty()) {
                content.setType(accept[0]);
            }
            if (accept != null && accept.length > 1) {
                content.putExtra(Intent.EXTRA_MIME_TYPES, accept);
            }
            content.putExtra(
                Intent.EXTRA_ALLOW_MULTIPLE,
                params.getMode() == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE
            );
        }

        Intent camera = null;
        try {
            File dir = new File(getCacheDir(), "camera");
            if (!dir.exists()) dir.mkdirs();

            String name = "IMG_" +
                new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) +
                ".jpg";

            File image = new File(dir, name);
            if (!image.exists()) image.createNewFile();

            pendingCameraUri = new Uri.Builder()
                .scheme("content")
                .authority(getPackageName() + ".fileprovider")
                .appendPath(name)
                .build();

            camera = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            camera.putExtra(MediaStore.EXTRA_OUTPUT, pendingCameraUri);
            camera.addFlags(
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            );

            if (camera.resolveActivity(getPackageManager()) == null) camera = null;
        } catch (IOException e) {
            pendingCameraUri = null;
        }

        Intent chooser = new Intent(Intent.ACTION_CHOOSER);
        chooser.putExtra(Intent.EXTRA_INTENT, content);
        chooser.putExtra(Intent.EXTRA_TITLE, "Choose a file");
        if (camera != null) {
            chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{camera});
        }

        try {
            startActivityForResult(chooser, FILE_CHOOSER_REQUEST);
        } catch (Exception e) {
            if (filePathCallback != null) filePathCallback.onReceiveValue(null);
            filePathCallback = null;
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != FILE_CHOOSER_REQUEST || filePathCallback == null) return;

        Uri[] result = null;

        if (resultCode == RESULT_OK) {
            if (data == null || data.getData() == null) {
                if (pendingCameraUri != null) result = new Uri[]{pendingCameraUri};
            } else {
                result = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
            }
        }

        filePathCallback.onReceiveValue(result);
        filePathCallback = null;
        pendingCameraUri = null;
    }

    private void enqueueDownload(DownloadRequest d) {
        try {
            Uri uri = Uri.parse(d.url);
            String filename = URLUtil.guessFileName(
                d.url,
                d.contentDisposition,
                d.mimeType
            );

            DownloadManager.Request r = new DownloadManager.Request(uri);
            r.setTitle(filename);
            r.setDescription("Downloading from EduIntels");

            if (d.mimeType != null && !d.mimeType.isEmpty()) r.setMimeType(d.mimeType);
            if (d.userAgent != null) r.addRequestHeader("User-Agent", d.userAgent);

            String cookie = CookieManager.getInstance().getCookie(d.url);
            if (cookie != null) r.addRequestHeader("Cookie", cookie);

            r.setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            );
            r.setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                filename
            );

            DownloadManager dm =
                (DownloadManager)getSystemService(DOWNLOAD_SERVICE);
            dm.enqueue(r);

            Toast.makeText(this, "Download started", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Could not start download.", Toast.LENGTH_LONG).show();
        }
    }

    private boolean isOnline() {
        ConnectivityManager cm =
            (ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);

        if (cm == null) return false;
        Network n = cm.getActiveNetwork();
        if (n == null) return false;

        NetworkCapabilities c = cm.getNetworkCapabilities(n);
        return c != null &&
               c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private void showOffline() {
        hideInitialLoading();
        pageProgress.setVisibility(View.GONE);
        offlineOverlay.setVisibility(View.VISIBLE);
    }

    private void loadHomeOrCurrent() {
        if (!isOnline()) {
            showOffline();
            return;
        }

        offlineOverlay.setVisibility(View.GONE);
        if (!firstPageVisible) {
            loadingOverlay.setVisibility(View.VISIBLE);
            mainHandler.postDelayed(initialLoadingFailSafe, 6500);
        }

        String current = webView.getUrl();
        webView.loadUrl(
            current != null && current.startsWith("https://")
                ? current
                : HOME_URL
        );
    }

    private void hideCustomView() {
        if (customView == null) return;

        fullscreenContainer.removeView(customView);
        fullscreenContainer.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);

        if (customViewCallback != null) customViewCallback.onCustomViewHidden();

        customView = null;
        customViewCallback = null;
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);

        Uri data = intent != null ? intent.getData() : null;
        if (data != null && isEduIntelsUrl(data.toString()) && webView != null) {
            webView.loadUrl(data.toString());
        }
    }

    @Override public void onBackPressed() {
        if (customView != null) {
            hideCustomView();
            return;
        }

        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        webView.saveState(outState);
        super.onSaveInstanceState(outState);
    }

    @Override protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
    }

    @Override protected void onPause() {
        if (webView != null) webView.onPause();
        super.onPause();
    }

    @Override protected void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);

        if (webView != null) {
            webView.stopLoading();
            webView.setWebChromeClient(null);
            webView.setWebViewClient(null);
            webView.destroy();
        }

        super.onDestroy();
    }

    @Override public void onRequestPermissionsResult(
        int requestCode,
        String[] permissions,
        int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == STORAGE_PERMISSION_REQUEST &&
            pendingDownload != null) {
            DownloadRequest d = pendingDownload;
            pendingDownload = null;

            if (grantResults.length > 0 &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                enqueueDownload(d);
            } else {
                Toast.makeText(
                    this,
                    "Storage permission is required to save this download on this Android version.",
                    Toast.LENGTH_LONG
                ).show();
            }
        }
    }
}
