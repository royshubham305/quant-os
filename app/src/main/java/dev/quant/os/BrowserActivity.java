package dev.quant.os;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.LocaleList;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
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
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.Locale;

/**
 * Full in-app browser, opened by the OS through a quant-browse: link.
 * It exists because an iframe inherits the framed site's policies; a real
 * WebView gives the browser app its own engine, its own tab history and
 * working file/camera hand-offs. Requests to known ad/tracking hosts are
 * stopped before they leave the device.
 */
public class BrowserActivity extends Activity {

    private static final String TAG = "QuantBrowser";
    private static final int DARK = Color.parseColor("#070b0e");
    private static final int CHROME = Color.parseColor("#15181c");
    private static final int FIELD = Color.parseColor("#22262c");
    private static final int HINT = Color.parseColor("#7a828c");
    private static final int REQ_FILES = 61;
    private static final int REQ_CAMERA = 62;

    private WebView web;
    private EditText urlBar;
    private ProgressBar progress;
    private ValueCallback<Uri[]> pendingFiles;
    private PermissionRequest pendingCamera;
    private View fullView;
    private WebChromeClient.CustomViewCallback fullCallback;

    @Override
    protected void attachBaseContext(Context base) {
        /* Same reason as MainActivity: the Accept-Language header follows the
           app locale, and en-IN would name the phone's region. */
        Locale.setDefault(Locale.US);
        if (Build.VERSION.SDK_INT >= 24) {
            LocaleList.setDefault(LocaleList.forLanguageTags("en-US"));
        }
        Configuration c = new Configuration(base.getResources().getConfiguration());
        c.setLocale(Locale.US);
        super.attachBaseContext(base.createConfigurationContext(c));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        getWindow().setBackgroundDrawableResource(R.color.boot_bg);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(DARK);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(CHROME);
        bar.setPadding(dp(6), dp(6), dp(6), dp(6));

        TextView back = barButton("\u2039");
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (web != null && web.canGoBack()) web.goBack();
                else finish();
            }
        });
        bar.addView(back, new LinearLayout.LayoutParams(dp(38), dp(38)));

        urlBar = new EditText(this);
        urlBar.setSingleLine(true);
        urlBar.setTextColor(Color.WHITE);
        urlBar.setHintTextColor(HINT);
        urlBar.setHint("Search or enter address");
        urlBar.setTextSize(13f);
        urlBar.setBackgroundColor(FIELD);
        urlBar.setPadding(dp(10), 0, dp(10), 0);
        urlBar.setImeOptions(2);
        urlBar.setInputType(16);
        LinearLayout.LayoutParams fieldLp =
                new LinearLayout.LayoutParams(0, dp(38), 1f);
        fieldLp.setMargins(dp(6), 0, dp(6), 0);
        bar.addView(urlBar, fieldLp);

        TextView go = barButton("Go");
        go.setTextSize(12f);
        go.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                submit(urlBar.getText().toString());
            }
        });
        bar.addView(go, new LinearLayout.LayoutParams(dp(44), dp(38)));

        TextView close = barButton("\u2715");
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        bar.addView(close, new LinearLayout.LayoutParams(dp(38), dp(38)));

        urlBar.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId != 2) return false;
                submit(v.getText().toString());
                return true;
            }
        });

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgress(0);
        progress.setVisibility(View.GONE);

        web = new WebView(this);
        web.setBackgroundColor(DARK);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(false);
        s.setUseWideViewPort(false);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportMultipleWindows(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        if (Build.VERSION.SDK_INT >= 33) s.setAlgorithmicDarkeningAllowed(false);
        /* Same policy the OS page uses: the profile's UA, deny-first
           geolocation, first-party cookies, per-site cookie mode. */
        MainActivity.Identity.applyTo(s, web);
        MainActivity.Identity.harden(web);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView v, String url, android.graphics.Bitmap favicon) {
                MainActivity.Identity.harden(v);
                MainActivity.Identity.applyNavigation(v.getSettings(), url, true);
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest req) {
                Uri url = req.getUrl();
                if (url == null) return null;
                String scheme = url.getScheme() == null ? "" : url.getScheme().toLowerCase();
                if (("http".equals(scheme) || "https".equals(scheme)) && !TorEngine.routed()) {
                    return TorEngine.heldOff();
                }
                if (!AdBlock.blocked(url.getHost())) return null;
                HashMap<String, String> headers = new HashMap<>();
                headers.put("Cache-Control", "no-store");
                return new WebResourceResponse("text/plain", "utf-8", 204,
                        "Blocked by Quant", headers, new ByteArrayInputStream(new byte[0]));
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                Uri url = req.getUrl();
                if (url == null) return true;
                String scheme = url.getScheme() == null ? "" : url.getScheme().toLowerCase();
                if (!scheme.equals("http") && !scheme.equals("https")) {
                    openExternal(url, scheme);
                    return true;
                }
                if (!TorEngine.routed()) {
                    TorEngine.showHeldOff(web);
                    return true;
                }
                if (!AdBlock.blocked(url.getHost())) return false;
                blockedPage(url.toString());
                return true;
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                if (urlBar == null || urlBar.hasFocus() || url == null) return;
                urlBar.setText(url);
            }

            @Override
            public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail detail) {
                Log.e(TAG, "renderer gone, crashed=" + detail.didCrash());
                Toast.makeText(BrowserActivity.this,
                        "The page renderer stopped. Reopen the page.",
                        Toast.LENGTH_SHORT).show();
                finish();
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView v, int value) {
                progress.setProgress(value);
                progress.setVisibility(value >= 100 ? View.GONE : View.VISIBLE);
            }

            /* HTML5 fullscreen (a video's fullscreen button) needs these two,
               or the page believes fullscreen is unsupported. */
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                ViewGroup content = findViewById(android.R.id.content);
                if (fullView != null || content == null) {
                    callback.onCustomViewHidden();
                    return;
                }
                fullView = view;
                fullCallback = callback;
                content.addView(view, new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT));
                hideAllBars();
            }

            @Override
            public void onHideCustomView() {
                hideFullscreen();
            }

            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (pendingFiles != null) pendingFiles.onReceiveValue(null);
                pendingFiles = callback;
                Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
                pick.addCategory(Intent.CATEGORY_OPENABLE);
                pick.setType("*/*");
                try {
                    startActivityForResult(Intent.createChooser(pick, "Select file"), REQ_FILES);
                    return true;
                } catch (ActivityNotFoundException e) {
                    pendingFiles = null;
                    return false;
                }
            }

            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        String originHost = hostOf(request.getOrigin());
                        String topHost = hostOf(Uri.parse(web == null || web.getUrl() == null
                                ? "" : web.getUrl()));
                        boolean wantsCamera = false;
                        for (String r : request.getResources()) {
                            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)) {
                                wantsCamera = true;
                            }
                        }
                        if (!wantsCamera || originHost.isEmpty() || !originHost.equals(topHost)) {
                            request.deny();
                            return;
                        }
                        if (MainActivity.Identity.mediaAllowedFor(originHost)) {
                            grantCamera(request);
                            return;
                        }
                        new AlertDialog.Builder(BrowserActivity.this)
                                .setTitle("Allow camera?")
                                .setMessage(originHost + " is asking to use the camera.")
                                .setPositiveButton("Allow", new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface dialog, int which) {
                                        grantCamera(request);
                                    }
                                })
                                .setNegativeButton("Deny", new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface dialog, int which) {
                                        request.deny();
                                    }
                                })
                                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                                    @Override
                                    public void onCancel(DialogInterface dialog) {
                                        request.deny();
                                    }
                                })
                                .show();
                    }
                });
            }
        });

        web.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String ua, String disposition,
                                        String mime, long size) {
                download(url, disposition, mime);
            }
        });

        root.addView(bar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(progress, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(2)));
        root.addView(web, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        root.post(new Runnable() {
            @Override
            public void run() {
                immersive();
            }
        });

        String url = getIntent() != null ? getIntent().getStringExtra("url") : null;
        if (url == null || url.isEmpty()) url = "https://example.com";
        submit(url);
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    private TextView barButton(String label) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(20f);
        tv.setGravity(android.view.Gravity.CENTER);
        tv.setBackgroundColor(FIELD);
        return tv;
    }

    private void submit(String input) {
        String url = normalize(input);
        if (url == null || url.isEmpty()) return;
        urlBar.setText(url);
        if (!TorEngine.routed()) {
            TorEngine.showHeldOff(web);
            return;
        }
        if (AdBlock.blocked(Uri.parse(url).getHost())) {
            blockedPage(url);
            return;
        }
        urlBar.clearFocus();
        MainActivity.Identity.applyNavigation(web.getSettings(), url, true);
        web.loadUrl(url);
    }

    private String normalize(String input) {
        String text = input == null ? "" : input.trim();
        if (text.isEmpty()) return "";
        if (text.matches("(?i)^(https?)://.*")) return text;
        if (text.matches("^[\\w-]+(\\.[\\w-]+)+(/.*)?$")
                || text.matches("^localhost(:\\d+)?(/.*)?$")) {
            return "https://" + text;
        }
        return "https://duckduckgo.com/?q=" + Uri.encode(text);
    }

    private void blockedPage(String url) {
        String host = Uri.parse(url).getHost();
        web.loadDataWithBaseURL(null,
                "<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'>"
                        + "<body style='background:#0b0e12;color:#e8edf2;font-family:system-ui,sans-serif;"
                        + "display:flex;align-items:center;justify-content:center;height:100vh;margin:0'>"
                        + "<div style='max-width:420px;text-align:center;padding:24px'>"
                        + "<div style='font-size:34px'>&#128683;</div>"
                        + "<h2 style='font-weight:600'>Blocked by Quant ad blocker</h2>"
                        + "<p style='color:#9aa3ad;font-size:14px;line-height:1.6'>"
                        + TextUtils.htmlEncode(host == null ? "" : host)
                        + " is a known ad or tracking domain. No request left the device.</p>"
                        + "</div></body>",
                "text/html", "utf-8", null);
        if (urlBar != null) urlBar.setText(url);
    }

    private void openExternal(Uri uri, String scheme) {
        if (scheme.equals("mailto") || scheme.equals("tel") || scheme.equals("sms")
                || scheme.equals("http") || scheme.equals("https")) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, uri));
            } catch (ActivityNotFoundException e) {
                Toast.makeText(this, "No app can open this link", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void download(String url, String disposition, String mime) {
        /* DownloadManager runs outside the WebView proxy, so any download
           would use the normal connection instead of Tor. */
        Toast.makeText(this,
                "Downloads are paused because they would use the normal connection, not Tor",
                Toast.LENGTH_LONG).show();
    }

    /** Remove the page's fullscreen view and tell the page it is gone. */
    private void hideFullscreen() {
        if (fullView == null) return;
        ViewGroup parent = (ViewGroup) fullView.getParent();
        if (parent != null) parent.removeView(fullView);
        fullView = null;
        WebChromeClient.CustomViewCallback cb = fullCallback;
        fullCallback = null;
        if (cb != null) cb.onCustomViewHidden();
        immersive();
    }

    private void hideAllBars() {
        Window window = getWindow();
        if (Build.VERSION.SDK_INT >= 30) {
            View decor = window.getDecorView();
            WindowInsetsController controller = decor == null ? null : decor.getWindowInsetsController();
            if (controller == null) return;
            controller.hide(WindowInsets.Type.systemBars());
            controller.setSystemBarsBehavior(
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            return;
        }
        window.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private void immersive() {
        Window window = getWindow();
        if (Build.VERSION.SDK_INT >= 30) {
            View decor = window.getDecorView();
            WindowInsetsController controller = decor == null ? null : decor.getWindowInsetsController();
            if (controller == null) return;
            controller.hide(WindowInsets.Type.statusBars());
            return;
        }
        window.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_FULLSCREEN);
    }

    private void grantCamera(PermissionRequest request) {
        if (checkSelfPermission(android.Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            if (pendingCamera != null) pendingCamera.deny();
            pendingCamera = request;
            requestPermissions(new String[]{android.Manifest.permission.CAMERA}, REQ_CAMERA);
            return;
        }
        request.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
    }

    private static String hostOf(Uri u) {
        if (u == null) return "";
        String h = u.getHost();
        return h == null ? "" : MainActivity.Identity.bareHost(h);
    }

    @Override
    public void onBackPressed() {
        if (fullView != null) {
            hideFullscreen();
            return;
        }
        if (web == null || !web.canGoBack()) super.onBackPressed();
        else web.goBack();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQ_CAMERA) return;
        PermissionRequest request = pendingCamera;
        pendingCamera = null;
        if (request == null) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            request.grant(new String[]{"android.webkit.resource.VIDEO_CAPTURE"});
        } else {
            request.deny();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_FILES) {
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }
        Uri[] uris = null;
        if (resultCode == RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                int count = data.getClipData().getItemCount();
                uris = new Uri[count];
                for (int i = 0; i < count; i++) {
                    uris[i] = data.getClipData().getItemAt(i).getUri();
                }
            } else if (data.getData() != null) {
                uris = new Uri[]{data.getData()};
            }
        }
        if (pendingFiles != null) {
            pendingFiles.onReceiveValue(uris);
            pendingFiles = null;
        }
    }

    @Override
    protected void onDestroy() {
        if (pendingFiles != null) {
            pendingFiles.onReceiveValue(null);
            pendingFiles = null;
        }
        if (pendingCamera != null) {
            pendingCamera.deny();
            pendingCamera = null;
        }
        if (fullView != null) hideFullscreen();
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
