package ng.eduintels.app;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
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
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.Toast;
import android.net.http.SslError;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final String HOME_URL = "https://eduintels.ng/";
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

    private static class DownloadRequest {
        String url, userAgent, contentDisposition, mimeType;
        DownloadRequest(String url, String ua, String cd, String mt) {
            this.url=url; this.userAgent=ua; this.contentDisposition=cd; this.mimeType=mt;
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

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
            loadingOverlay.setVisibility(View.GONE);
        } else {
            Uri deepLink = getIntent() != null ? getIntent().getData() : null;
            String start = deepLink != null && isEduIntelsUrl(deepLink.toString()) ? deepLink.toString() : HOME_URL;
            if (isOnline()) webView.loadUrl(start);
            else showOffline();
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
        s.setUserAgentString(s.getUserAgentString() + " EduIntelsAndroid/1.0");
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

        webView.setWebViewClient(new EduWebViewClient());
        webView.setWebChromeClient(new EduChromeClient());
        webView.setDownloadListener(new EduDownloadListener());
        webView.setOverScrollMode(WebView.OVER_SCROLL_NEVER);
    }

    private class EduWebViewClient extends WebViewClient {
        @Override public void onPageStarted(WebView view, String url, Bitmap favicon) {
            pageProgress.setVisibility(View.VISIBLE);
            offlineOverlay.setVisibility(View.GONE);
        }

        @Override public void onPageFinished(WebView view, String url) {
            loadingOverlay.animate().alpha(0f).setDuration(180).withEndAction(() -> {
                loadingOverlay.setVisibility(View.GONE);
                loadingOverlay.setAlpha(1f);
            }).start();
            pageProgress.setVisibility(View.GONE);
            injectNativeAppCss();
            CookieManager.getInstance().flush();
        }

        @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return handleNavigation(request.getUrl());
        }

        @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return handleNavigation(Uri.parse(url));
        }

        @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (request.isForMainFrame()) showOffline();
        }

        @Override public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            handler.cancel();
            Toast.makeText(MainActivity.this, "Secure connection failed. The page was not opened.", Toast.LENGTH_LONG).show();
        }
    }

    private class EduChromeClient extends WebChromeClient {
        @Override public void onProgressChanged(WebView view, int newProgress) {
            pageProgress.setProgress(newProgress);
            pageProgress.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
        }

        @Override public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePath, FileChooserParams params) {
            if (filePathCallback != null) filePathCallback.onReceiveValue(null);
            filePathCallback = filePath;
            openFileChooser(params);
            return true;
        }

        @Override public void onShowCustomView(View view, CustomViewCallback callback) {
            if (customView != null) { callback.onCustomViewHidden(); return; }
            customView = view;
            customViewCallback = callback;
            fullscreenContainer.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ));
            fullscreenContainer.setVisibility(View.VISIBLE);
            webView.setVisibility(View.GONE);
        }

        @Override public void onHideCustomView() {
            hideCustomView();
        }

        @Override public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
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
        @Override public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimetype, long contentLength) {
            if (url == null) return;
            if (url.startsWith("blob:") || url.startsWith("data:")) {
                Toast.makeText(MainActivity.this, "This file uses an in-page download. If it does not start, open the page in Chrome.", Toast.LENGTH_LONG).show();
                return;
            }
            DownloadRequest req = new DownloadRequest(url, userAgent, contentDisposition, mimetype);
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                pendingDownload = req;
                requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, STORAGE_PERMISSION_REQUEST);
            } else {
                enqueueDownload(req);
            }
        }
    }

    private boolean handleNavigation(Uri uri) {
        if (uri == null) return false;
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.US);
        String url = uri.toString();

        if ("http".equals(scheme) || "https".equals(scheme)) {
            if (isEduIntelsUrl(url) || isTrustedPaymentUrl(url)) return false;
            openExternal(uri);
            return true;
        }

        if ("intent".equals(scheme)) {
            try {
                Intent intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                if (intent.resolveActivity(getPackageManager()) != null) startActivity(intent);
                else if (intent.getStringExtra("browser_fallback_url") != null) {
                    webView.loadUrl(intent.getStringExtra("browser_fallback_url"));
                }
            } catch (Exception e) {
                Toast.makeText(this, "No compatible app found.", Toast.LENGTH_SHORT).show();
            }
            return true;
        }

        if ("tel".equals(scheme) || "mailto".equals(scheme) || "sms".equals(scheme) ||
            "market".equals(scheme) || "whatsapp".equals(scheme)) {
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
            if (accept != null && accept.length == 1 && accept[0] != null && !accept[0].isEmpty()) {
                content.setType(accept[0]);
            }
            if (accept != null && accept.length > 1) content.putExtra(Intent.EXTRA_MIME_TYPES, accept);
            content.putExtra(
                Intent.EXTRA_ALLOW_MULTIPLE,
                params.getMode() == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE
            );
        }

        Intent camera = null;
        try {
            File dir = new File(getCacheDir(), "camera");
            if (!dir.exists()) dir.mkdirs();
            String name = "IMG_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".jpg";
            File image = new File(dir, name);
            if (!image.exists()) image.createNewFile();
            pendingCameraUri = new Uri.Builder()
                .scheme("content")
                .authority(getPackageName()+".fileprovider")
                .appendPath(name)
                .build();

            camera = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            camera.putExtra(MediaStore.EXTRA_OUTPUT, pendingCameraUri);
            camera.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (camera.resolveActivity(getPackageManager()) == null) camera = null;
        } catch (IOException e) {
            pendingCameraUri = null;
        }

        Intent chooser = new Intent(Intent.ACTION_CHOOSER);
        chooser.putExtra(Intent.EXTRA_INTENT, content);
        chooser.putExtra(Intent.EXTRA_TITLE, "Choose a file");
        if (camera != null) chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{camera});

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
            String filename = URLUtil.guessFileName(d.url, d.contentDisposition, d.mimeType);
            DownloadManager.Request r = new DownloadManager.Request(uri);
            r.setTitle(filename);
            r.setDescription("Downloading from EduIntels");
            if (d.mimeType != null && !d.mimeType.isEmpty()) r.setMimeType(d.mimeType);
            if (d.userAgent != null) r.addRequestHeader("User-Agent", d.userAgent);

            String cookie = CookieManager.getInstance().getCookie(d.url);
            if (cookie != null) r.addRequestHeader("Cookie", cookie);

            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename);

            DownloadManager dm = (DownloadManager)getSystemService(DOWNLOAD_SERVICE);
            dm.enqueue(r);
            Toast.makeText(this, "Download started", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Could not start download.", Toast.LENGTH_LONG).show();
        }
    }

    private void injectNativeAppCss() {
        String js = "(function(){try{" +
                "document.documentElement.classList.add('eduintels-native-app');" +
                "var s=document.createElement('style');s.id='eduintels-native-hide-install';" +
                "s.textContent='[data-pwa-install],[data-pwa-install-trigger],[data-app-install],.eiPwaInstallBackdrop,.fp-app-install-banner{display:none!important}';" +
                "if(!document.getElementById(s.id))document.head.appendChild(s);" +
                "}catch(e){}})();";
        webView.evaluateJavascript(js, null);
    }

    private boolean isOnline() {
        ConnectivityManager cm = (ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        Network n = cm.getActiveNetwork();
        if (n == null) return false;
        NetworkCapabilities c = cm.getNetworkCapabilities(n);
        return c != null && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private void showOffline() {
        loadingOverlay.setVisibility(View.GONE);
        pageProgress.setVisibility(View.GONE);
        offlineOverlay.setVisibility(View.VISIBLE);
    }

    private void loadHomeOrCurrent() {
        if (!isOnline()) {
            showOffline();
            return;
        }
        offlineOverlay.setVisibility(View.GONE);
        loadingOverlay.setVisibility(View.VISIBLE);
        String current = webView.getUrl();
        webView.loadUrl(current != null && current.startsWith("https://") ? current : HOME_URL);
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
        if (webView != null) {
            webView.stopLoading();
            webView.setWebChromeClient(null);
            webView.setWebViewClient(null);
            webView.destroy();
        }
        super.onDestroy();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == STORAGE_PERMISSION_REQUEST && pendingDownload != null) {
            DownloadRequest d = pendingDownload;
            pendingDownload = null;
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
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
