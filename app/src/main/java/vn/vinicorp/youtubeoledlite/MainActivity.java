package vn.vinicorp.youtubeoledlite;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Insets;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

public final class MainActivity extends Activity {
    private static final String HOME_URL = "https://m.youtube.com/";
    private static final long LOAD_TIMEOUT_MS = 20_000L;
    private static final String FORCE_LIGHT_SCRIPT =
            "(function(){"
                    + "function light(){"
                    + "var nodes=[document.documentElement,document.body,document.querySelector('ytd-app'),document.querySelector('ytm-app')];"
                    + "nodes.forEach(function(n){if(!n)return;n.removeAttribute('dark');n.removeAttribute('darker-dark-theme');"
                    + "n.classList.remove('dark','dark-theme','darker-dark-theme');n.style.colorScheme='light';});"
                    + "}"
                    + "try{var raw=(document.cookie.match(/(?:^|; )PREF=([^;]*)/)||[])[1]||'';"
                    + "var pref=new URLSearchParams(decodeURIComponent(raw));pref.set('f6','40080000');"
                    + "document.cookie='PREF='+pref.toString()+'; path=/; domain=.youtube.com; max-age=31536000; SameSite=Lax';}catch(e){}"
                    + "light();"
                    + "if(!window.__oledLightObserver&&document.documentElement){"
                    + "window.__oledLightObserver=new MutationObserver(light);"
                    + "window.__oledLightObserver.observe(document.documentElement,{attributes:true,subtree:true,attributeFilter:['dark','darker-dark-theme']});"
                    + "}"
                    + "})();";
    private static final String HIDE_OPEN_APP_SCRIPT =
            "(function(){"
                    + "function hideOpenApp(){"
                    + "var names=['mở ứng dụng','open app'];"
                    + "document.querySelectorAll('button,a,ytm-button-renderer,tp-yt-paper-button').forEach(function(el){"
                    + "var text=((el.innerText||el.textContent||el.getAttribute('aria-label')||'').trim().toLowerCase()).replace(/\\s+/g,' ');"
                    + "if(names.indexOf(text)!==-1){"
                    + "var target=el.matches('button,a')?el:(el.closest('button,a')||el);"
                    + "target.style.setProperty('display','none','important');"
                    + "target.setAttribute('aria-hidden','true');"
                    + "}"
                    + "});"
                    + "}"
                    + "hideOpenApp();"
                    + "if(!window.__oledOpenAppObserver&&document.documentElement){"
                    + "var queued=false;"
                    + "window.__oledOpenAppObserver=new MutationObserver(function(){"
                    + "if(queued)return;queued=true;setTimeout(function(){queued=false;hideOpenApp();},100);"
                    + "});"
                    + "window.__oledOpenAppObserver.observe(document.documentElement,{childList:true,subtree:true});"
                    + "}"
                    + "})();";

    private WebView webView;
    private View loadingOverlay;
    private View browserContainer;
    private FrameLayout fullscreenContainer;
    private View errorPanel;
    private TextView errorDetail;
    private View fullscreenView;
    private WebChromeClient.CustomViewCallback fullscreenCallback;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean pageReady;
    private boolean compatibilityMode;

    private final Runnable loadTimeout = () -> {
        if (!pageReady) {
            showLoadError("Trang tải quá lâu hoặc WebView không dựng được giao diện YouTube.");
        }
    };

    @Override
    protected void attachBaseContext(Context newBase) {
        Configuration lightConfiguration = new Configuration(
                newBase.getResources().getConfiguration());
        lightConfiguration.uiMode = (lightConfiguration.uiMode
                & ~Configuration.UI_MODE_NIGHT_MASK) | Configuration.UI_MODE_NIGHT_NO;
        super.attachBaseContext(newBase.createConfigurationContext(lightConfiguration));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        setContentView(R.layout.activity_main);

        browserContainer = findViewById(R.id.browser_container);
        fullscreenContainer = findViewById(R.id.fullscreen_container);
        loadingOverlay = findViewById(R.id.loading_overlay);
        webView = findViewById(R.id.web_view);
        errorPanel = findViewById(R.id.error_panel);
        errorDetail = findViewById(R.id.error_detail);

        applySystemBarSafeArea();
        configureWebView();
        findViewById(R.id.error_retry_button).setOnClickListener(v -> retryPage());
        findViewById(R.id.compatibility_button).setOnClickListener(v -> enableCompatibilityMode());

        if (savedInstanceState == null) {
            webView.loadUrl(HOME_URL);
        } else {
            webView.restoreState(savedInstanceState);
        }
    }

    private void applySystemBarSafeArea() {
        Window window = getWindow();
        window.setStatusBarColor(Color.BLACK);
        window.setNavigationBarColor(Color.BLACK);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false);
        } else {
            window.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }

        browserContainer.setOnApplyWindowInsetsListener((view, windowInsets) -> {
            int left;
            int top;
            int right;
            int bottom;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Insets safeInsets = windowInsets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                left = safeInsets.left;
                top = safeInsets.top;
                right = safeInsets.right;
                bottom = safeInsets.bottom;
            } else {
                left = windowInsets.getSystemWindowInsetLeft();
                top = windowInsets.getSystemWindowInsetTop();
                right = windowInsets.getSystemWindowInsetRight();
                bottom = windowInsets.getSystemWindowInsetBottom();

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                        && windowInsets.getDisplayCutout() != null) {
                    left = Math.max(left, windowInsets.getDisplayCutout().getSafeInsetLeft());
                    top = Math.max(top, windowInsets.getDisplayCutout().getSafeInsetTop());
                    right = Math.max(right, windowInsets.getDisplayCutout().getSafeInsetRight());
                    bottom = Math.max(bottom, windowInsets.getDisplayCutout().getSafeInsetBottom());
                }
            }

            // TS8 reports false horizontal/navigation insets in landscape.
            // Only the top status-bar inset is real on this head unit.
            view.setPadding(0, top, 0, 0);
            return windowInsets;
        });
        browserContainer.requestApplyInsets();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setLoadWithOverviewMode(false);
        settings.setUseWideViewPort(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setSupportMultipleWindows(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setLoadsImagesAutomatically(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            settings.setForceDark(WebSettings.FORCE_DARK_OFF);
        }

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                pageReady = false;
                hideLoadError();
                showLoading();
                handler.removeCallbacks(loadTimeout);
                handler.postDelayed(loadTimeout, LOAD_TIMEOUT_MS);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                CookieManager.getInstance().flush();
                view.evaluateJavascript(FORCE_LIGHT_SCRIPT, null);
                view.evaluateJavascript(HIDE_OPEN_APP_SCRIPT, null);
                view.evaluateJavascript(
                        "(function(){return !!(document.body && (document.body.children.length > 0 || document.body.innerText.length > 0));})()",
                        result -> {
                            if ("true".equals(result)) {
                                pageReady = true;
                                handler.removeCallbacks(loadTimeout);
                                hideLoading();
                                hideLoadError();
                            } else {
                                showLoadError("YouTube trả về một trang trống.");
                            }
                        });
            }

            @Override
            public void onPageCommitVisible(WebView view, String url) {
                view.evaluateJavascript(FORCE_LIGHT_SCRIPT, null);
                view.evaluateJavascript(HIDE_OPEN_APP_SCRIPT, null);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    showLoadError("Lỗi mạng " + error.getErrorCode() + ": " + error.getDescription());
                }
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
                if (request.isForMainFrame() && response.getStatusCode() >= 400) {
                    showLoadError("Máy chủ YouTube trả về mã " + response.getStatusCode() + ".");
                }
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler sslHandler, SslError error) {
                sslHandler.cancel();
                showLoadError("Lỗi chứng chỉ SSL. Hãy kiểm tra ngày giờ trên màn hình.");
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleNavigation(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleNavigation(Uri.parse(url));
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (fullscreenView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                fullscreenView = view;
                fullscreenCallback = callback;
                browserContainer.setVisibility(View.GONE);
                fullscreenContainer.setVisibility(View.VISIBLE);
                fullscreenContainer.addView(view, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
                setFullscreenUi(true);
            }

            @Override
            public void onHideCustomView() {
                hideFullscreenVideo();
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                // YouTube playback does not need camera or microphone access.
                request.deny();
            }
        });

    }

    private void retryPage() {
        pageReady = false;
        hideLoadError();
        webView.reload();
    }

    private void enableCompatibilityMode() {
        compatibilityMode = true;
        webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        webView.getSettings().setCacheMode(WebSettings.LOAD_NO_CACHE);
        webView.clearCache(true);
        pageReady = false;
        hideLoadError();
        webView.loadUrl(HOME_URL + "?app=mobile&persist_app=1");
        Toast.makeText(this, "Đã bật chế độ tương thích", Toast.LENGTH_SHORT).show();
    }

    private void showLoadError(String message) {
        pageReady = false;
        handler.removeCallbacks(loadTimeout);
        hideLoading();
        webView.setVisibility(View.GONE);
        errorPanel.setVisibility(View.VISIBLE);

        String webViewInfo = "không xác định";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.content.pm.PackageInfo info = WebView.getCurrentWebViewPackage();
            if (info != null) {
                webViewInfo = info.packageName + " " + info.versionName;
            }
        }
        String mode = compatibilityMode ? "\nĐang dùng chế độ tương thích." : "";
        errorDetail.setText(message + "\nWebView: " + webViewInfo + mode
                + "\n\nNếu WebView quá cũ, hãy cập nhật Android System WebView trong CH Play.");
    }

    private void hideLoadError() {
        errorPanel.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
    }

    private void showLoading() {
        loadingOverlay.setVisibility(View.VISIBLE);
        loadingOverlay.bringToFront();
    }

    private void hideLoading() {
        loadingOverlay.setVisibility(View.GONE);
    }

    private boolean handleNavigation(Uri uri) {
        String scheme = uri.getScheme();
        if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
            return false;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException error) {
            Toast.makeText(this, "Không tìm thấy ứng dụng để mở liên kết", Toast.LENGTH_SHORT).show();
        }
        return true;
    }

    private void setFullscreenUi(boolean enabled) {
        if (enabled) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
            browserContainer.requestApplyInsets();
        }
    }

    private void hideFullscreenVideo() {
        if (fullscreenView == null) return;
        fullscreenContainer.removeView(fullscreenView);
        fullscreenView = null;
        fullscreenContainer.setVisibility(View.GONE);
        browserContainer.setVisibility(View.VISIBLE);
        setFullscreenUi(false);
        if (fullscreenCallback != null) {
            fullscreenCallback.onCustomViewHidden();
            fullscreenCallback = null;
        }
    }

    private void goBack() {
        if (fullscreenView != null) {
            hideFullscreenVideo();
        } else if (webView.canGoBack()) {
            webView.goBack();
        } else {
            stopService(new Intent(this, PlaybackService.class));
            finish();
        }
    }

    @Override
    public void onBackPressed() {
        goBack();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        webView.saveState(outState);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onPause() {
        CookieManager.getInstance().flush();
        if (!isFinishing()) {
            Intent serviceIntent = new Intent(this, PlaybackService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        stopService(new Intent(this, PlaybackService.class));
        webView.onResume();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (webView != null) {
            webView.stopLoading();
            webView.setWebChromeClient(null);
            webView.setWebViewClient(null);
            webView.destroy();
        }
        super.onDestroy();
    }
}
