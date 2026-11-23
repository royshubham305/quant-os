package dev.quant.os;

import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.util.TypedValue;
import android.view.View;
import android.webkit.DownloadListener;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * A real page surface that the OS window can position over its content area.
 *
 * The web build of Quant can only embed sites in an iframe, and any site may
 * refuse that with X-Frame-Options or CSP. This surface is an ordinary
 * WebView: it renders whatever the user asks for, filters ad and tracker
 * requests before they leave the device, and reports page events back to the
 * OS so its tabs, address bar and history stay accurate.
 */
class NativeSurface extends FrameLayout {

    interface Listener {
        void onEvent(String json);
        void pickFiles(ValueCallback<Uri[]> callback);
        void requestCamera(PermissionRequest request);
        void surfaceGone();
    }

    private final WebView web;
    private final ProgressBar progress;
    private final Listener listener;
    private final float density;
    private volatile String loaded = "";
    private volatile boolean shown;
    private volatile int px, py;
    private volatile int pw = 1, ph = 1;
    private volatile String renderer = "ok";
    private volatile boolean mobile;

    NativeSurface(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        this.density = context.getResources().getDisplayMetrics().density;
        setBackgroundColor(Color.TRANSPARENT);
        setVisibility(GONE);

        LinearLayout stack = new LinearLayout(context);
        stack.setOrientation(LinearLayout.VERTICAL);
        stack.setBackgroundColor(Color.WHITE);

        progress = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgress(0);
        try {
            progress.getProgressDrawable().setColorFilter(
                    Color.parseColor("#0a84ff"), android.graphics.PorterDuff.Mode.SRC_IN);
        } catch (Exception ignored) {
        }
        stack.addView(progress, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(2)));

        web = new WebView(context);
        web.setBackgroundColor(Color.WHITE);
        configure(web);
        stack.addView(web, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        addView(stack, new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        wireWebClient(web);
        wireChromeClient(web);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configure(WebView view) {
        WebSettings s = view.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportMultipleWindows(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        if (Build.VERSION.SDK_INT >= 33) s.setAlgorithmicDarkeningAllowed(false);
        /* Anonymity defaults: no geolocation, no third-party cookies. Both are
           re-checked per navigation and whenever Q.anon pushes a new profile. */
        s.setGeolocationEnabled(false);
        MainActivity.Identity.applyTo(s, view);
        MainActivity.Identity.harden(view);
        view.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
    }

    /** Re-apply the JS-provided identity to this surface's WebView. */
    void applyIdentity() {
        MainActivity.Identity.applyTo(web.getSettings(), web);
        applyViewport();
        if (!loaded.isEmpty()) MainActivity.Identity.applyNavigation(web.getSettings(), loaded, mobile);
    }

    /**
     * Desktop (wide viewport, desktop UA) or mobile (device-width viewport,
     * the profile's mobile UA) rendering. Reloads only when the mode really
     * changed and a page is on screen.
     */
    void setMode(boolean mobileMode) {
        boolean changed = mobile != mobileMode;
        mobile = mobileMode;
        applyViewport();
        if (!loaded.isEmpty()) {
            MainActivity.Identity.applyNavigation(web.getSettings(), loaded, mobile);
            if (changed && shown) web.reload();
        }
    }

    private void applyViewport() {
        WebSettings s = web.getSettings();
        s.setUseWideViewPort(!mobile);
        s.setLoadWithOverviewMode(!mobile);
    }

    /** Burn: drop every cached response this surface may have left behind. */
    void clearPageCache() {
        web.clearCache(true);
    }

    private void wireWebClient(WebView view) {
        view.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (u == null) return null;
                String host = u.getHost();
                if (host != null && AdBlock.blocked(host)) {
                    Map<String, String> headers = new HashMap<>();
                    headers.put("Cache-Control", "no-store");
                    event("{\"type\":\"blocked\",\"rule\":\"" + esc(host) + "\"}");
                    return new WebResourceResponse("text/plain", "utf-8", 204,
                            "Blocked by Quant", headers,
                            new ByteArrayInputStream(new byte[0]));
                }
                String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase();
                if (("http".equals(scheme) || "https".equals(scheme)) && !TorEngine.routed()) {
                    return TorEngine.heldOff();
                }
                return null;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (u == null) return true;
                String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase();
                if (scheme.equals("http") || scheme.equals("https")) {
                    if (!TorEngine.routed()) {
                        loaded = u.toString();
                        renderer = "ok";
                        TorEngine.showHeldOff(v);
                        return true;
                    }
                    String host = u.getHost();
                    if (host != null && AdBlock.blocked(host)) {
                        blockedPage(u.toString(), host);
                        return true;
                    }
                    /* The engine performs this navigation itself, so the identity
                       has to be swapped in before we hand the request back. */
                    MainActivity.Identity.applyNavigation(v.getSettings(), u.toString(), mobile);
                    return false;
                }
                if (scheme.equals("mailto") || scheme.equals("tel") || scheme.equals("sms")
                        || scheme.equals("geo")) {
                    try {
                        getContext().startActivity(new Intent(Intent.ACTION_VIEW, u));
                    } catch (ActivityNotFoundException ignored) {
                    }
                    return true;
                }
                return true;
            }

            @Override
            public void onPageStarted(WebView v, String url, android.graphics.Bitmap favicon) {
                MainActivity.Identity.harden(v);
                loaded = url == null ? "" : url;
                renderer = "ok";
                Map<String, Object> m = new HashMap<>();
                m.put("type", "start");
                m.put("url", url == null ? "" : url);
                event(json(m));
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                Map<String, Object> m = new HashMap<>();
                m.put("type", "url");
                m.put("url", url == null ? "" : url);
                String t = v.getTitle();
                m.put("title", t == null ? "" : t);
                event(json(m));
            }

            @Override
            public void doUpdateVisitedHistory(WebView v, String url, boolean isReload) {
                Map<String, Object> m = new HashMap<>();
                m.put("type", "url");
                m.put("url", url == null ? "" : url);
                String t = v.getTitle();
                m.put("title", t == null ? "" : t);
                event(json(m));
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest req,
                                        android.webkit.WebResourceError err) {
                if (req == null || !req.isForMainFrame()) return;
                Map<String, Object> m = new HashMap<>();
                m.put("type", "error");
                m.put("url", req.getUrl() == null ? "" : req.getUrl().toString());
                m.put("message", err == null || err.getDescription() == null
                        ? "" : err.getDescription().toString());
                event(json(m));
            }

            @Override
            public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail detail) {
                loaded = "";
                renderer = "gone";
                hide();
                event("{\"type\":\"gone\"}");
                listener.surfaceGone();
                return true;
            }
        });
    }

    private void wireChromeClient(WebView view) {
        view.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView v, int value) {
                progress.setProgress(value);
                progress.setVisibility(value >= 100 ? GONE : VISIBLE);
                event("{\"type\":\"progress\",\"value\":" + value + "}");
            }

            @Override
            public void onReceivedTitle(WebView v, String title) {
                Map<String, Object> m = new HashMap<>();
                m.put("type", "title");
                m.put("title", title == null ? "" : title);
                event(json(m));
            }

            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb,
                                             FileChooserParams params) {
                listener.pickFiles(cb);
                return true;
            }

            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                post(new Runnable() {
                    @Override public void run() { listener.requestCamera(request); }
                });
            }
        });

        view.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String agent, String disposition,
                                        String mime, long size) {
                /* DownloadManager runs outside the WebView proxy, so any
                   download would use the normal connection instead of Tor. */
                event("{\"type\":\"download\",\"name\":\"\"," +
                        "\"error\":\"downloads are paused because they would use the normal connection\"}");
            }
        });
    }

    /* ---------------- surface control (called from the UI thread) ---------------- */

    void show(String url, float x, float y, float w, float h) {
        applyBounds(x, y, w, h);
        shown = true;
        renderer = "ok";
        setVisibility(VISIBLE);
        if (url != null && !url.isEmpty() && !url.equals(loaded)) load(url);
    }

    void bounds(float x, float y, float w, float h) {
        if (getVisibility() != VISIBLE) return;
        applyBounds(x, y, w, h);
    }

    private void applyBounds(float x, float y, float w, float h) {
        LayoutParams lp = (LayoutParams) getLayoutParams();
        if (lp == null) return;
        lp.width = Math.max(dp(1), Math.round(w * density));
        lp.height = Math.max(dp(1), Math.round(h * density));
        lp.leftMargin = Math.round(x * density);
        lp.topMargin = Math.round(y * density);
        px = lp.leftMargin;
        py = lp.topMargin;
        pw = lp.width;
        ph = lp.height;
        setLayoutParams(lp);
    }

    void load(String url) {
        if (url == null || url.isEmpty()) return;
        String host = Uri.parse(url).getHost();
        if (host != null && AdBlock.blocked(host)) {
            loaded = url;
            renderer = "ok";
            blockedPage(url, host);
            return;
        }
        loaded = url;
        renderer = "ok";
        if (!TorEngine.routed()) {
            /* No request may leave on the normal connection while Tor is off. */
            TorEngine.showHeldOff(web);
            return;
        }
        /* Per-site (or profile) user agent, chosen by the JS identity layer,
           must be on the settings before the request goes out. */
        MainActivity.Identity.applyNavigation(web.getSettings(), url, mobile);
        web.loadUrl(url);
    }

    void reload() {
        web.reload();
    }

    void stop() {
        web.stopLoading();
    }

    void hide() {
        shown = false;
        setVisibility(GONE);
        web.stopLoading();
    }

    /** Close the parked document for real: hide() keeps it warm. */
    void reset() {
        hide();
        loaded = "";
        web.loadUrl("about:blank");
        web.clearHistory();
    }

    /** Snapshot read from the bridge worker thread, so volatiles only. */
    String stateJson() {
        return "{\"visible\":" + shown
                + ",\"x\":" + px + ",\"y\":" + py
                + ",\"w\":" + pw + ",\"h\":" + ph
                + ",\"density\":" + density
                + ",\"url\":\"" + esc(loaded) + "\""
                + ",\"renderer\":\"" + renderer + "\"}";
    }

    String topUrl() {
        return loaded;
    }

    void onPause() {
        web.onPause();
    }

    void onResume() {
        web.onResume();
    }

    void release() {
        hide();
        web.destroy();
    }

    /* ---------------- helpers ---------------- */

    private void blockedPage(String url, String host) {
        String html = "<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'>"
                + "<body style='background:#0b0e12;color:#e8edf2;font-family:system-ui,sans-serif;"
                + "display:flex;align-items:center;justify-content:center;height:100vh;margin:0'>"
                + "<div style='max-width:420px;text-align:center;padding:24px'>"
                + "<div style='font-size:34px'>&#128683;</div>"
                + "<h2 style='font-weight:600'>Blocked by Quant ad blocker</h2>"
                + "<p style='color:#9aa3ad;font-size:14px;line-height:1.6'>" + esc(host)
                + " is a known ad or tracking domain. No request left the device.</p>"
                + "</div></body>";
        loaded = url;
        web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
    }

    private void event(String json) {
        post(new Runnable() {
            @Override public void run() { listener.onEvent(json); }
        });
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()));
    }

    private static String json(Map<String, Object> values) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : values.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(e.getKey()).append("\":");
            Object v = e.getValue();
            if (v instanceof Number || v instanceof Boolean) sb.append(v);
            else sb.append('"').append(esc(String.valueOf(v))).append('"');
        }
        return sb.append('}').toString();
    }

    private static String esc(String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '"' || c == '\\') sb.append('\\').append(c);
            else if (c == '\n') sb.append("\\n");
            else if (c == '\r') sb.append("");
            else if (c < 0x20) sb.append(' ');
            else sb.append(c);
        }
        return sb.toString();
    }
}
