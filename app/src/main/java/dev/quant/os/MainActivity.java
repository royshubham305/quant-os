package dev.quant.os;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.webkit.JavascriptInterface;
import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.LocaleList;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
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
import android.widget.Toast;
import android.view.Gravity;

import androidx.webkit.UserAgentMetadata;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Quant OS Android shell.
 *
 * The OS itself is the same offline HTML bundle that runs in a browser.
 * Assets are served from a virtual https origin so localStorage, service
 * workers and secure APIs behave like they do on the web, while still
 * working with no network connection.
 *
 * 1.3 anonymity layer: the Q.anon module inside the bundle pushes its
 * synthetic identity through QuantNative.identity(json). Everything the
 * Identity store below changes (user-agent, cookies, geolocation, cache
 * mode) is enforced on WebSettings — real engine settings, not a JS lie.
 * Defaults are deny-first: geolocation off, third-party cookies off,
 * camera/microphone denied for every site unless explicitly allowed.
 */
public class MainActivity extends Activity {

    private static final String TAG = "QuantOS";
    private static final String ORIGIN = "appassets.androidplatform.net";
    private static final String START_URL = "https://" + ORIGIN + "/assets/quant-os.html";
    private static final int REQ_FILES = 41;
    private static final int REQ_CAMERA = 42;
    private static final int REQ_NOTIFY = 43;
    private static final int DARK = Color.parseColor("#070b0e");

    private WebView web;
    private volatile NativeSurface surface;
    private final String bridgeToken = newToken();
    private ValueCallback<Uri[]> pendingFiles;
    private ValueCallback<Uri[]> pendingSurfaceFiles;
    private boolean backPending;
    private boolean pageReady;
    private PermissionRequest pendingPermission;
    private final Handler torTick = new Handler(Looper.getMainLooper());
    private final Runnable torPoll = new Runnable() {
        @Override
        public void run() {
            TorNotification.update(getApplicationContext(), TorEngine.statusJson());
            torTick.postDelayed(this, 2000);
        }
    };
    private static long lastRendererRestart;

    private static String newToken() {
        byte[] raw = new byte[24];
        new SecureRandom().nextBytes(raw);
        StringBuilder out = new StringBuilder(48);
        for (byte b : raw) {
            out.append(Character.forDigit((b >> 4) & 15, 16));
            out.append(Character.forDigit(b & 15, 16));
        }
        return out.toString();
    }

    /* =====================================================================
       Identity store — written by QuantNative.identity(), read by both
       WebViews before every navigation. Static so NativeSurface (same
       package) can consult it without owning a context reference.
       ===================================================================== */
    static final class Identity {
        static volatile String defaultUa = null;          // null = engine default
        static volatile boolean perSiteUa = false;
        static volatile String perSiteSeed = "";
        static volatile String[] personaUas = new String[0];
        static volatile String mobileUa = null;           // mobile-mode UA from the profile
        static volatile Map<String, String> siteUas = new HashMap<>();
        static final Set<String> geoAllowed = new HashSet<>();
        static final Set<String> mediaAllowed = new HashSet<>();
        static volatile String cookieMode = "session";    // session | persist | block
        static volatile Map<String, String> siteCookies = new HashMap<>();
        static volatile boolean blockThirdParty = true;
        static volatile boolean ephemeral = false;
        static volatile String engineUa = null;           // remembered once, restore-safe
        private static volatile Boolean rwStripped = null;
        private static volatile Boolean hintsLogged = null;
        private static final java.util.regex.Pattern CHROME_MAJOR =
                java.util.regex.Pattern.compile("Chrome/(\\d+)");

        /* WebRTC, enumerateDevices, the real time zone and canvas/audio
           fingerprints are neutralised by assets/harden.js before a page's
           own scripts run (see that file for each trade-off). The OS page
           itself skips the script so its clock, alarms and calendar stay in
           the real zone; getUserMedia stays available so the camera consent
           flow still works. */
        static volatile String HARDEN_JS = "";
        private static final Set<WebView> HARDENED =
                Collections.newSetFromMap(new WeakHashMap<WebView, Boolean>());

        static void loadHarden(Context ctx) {
            try (InputStream in = ctx.getAssets().open("harden.js")) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                HARDEN_JS = out.toString("UTF-8");
            } catch (Exception e) {
                Log.e(TAG, "harden.js missing from assets", e);
            }
        }

        /** Apply HARDEN_JS at document start; repeat on each load when the
            WebView provider is too old for document-start scripts. */
        static void harden(WebView view) {
            String js = HARDEN_JS;
            if (js.isEmpty()) return;
            try {
                if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    if (HARDENED.add(view)) {
                        WebViewCompat.addDocumentStartJavaScript(view, js, Collections.singleton("*"));
                    }
                } else {
                    view.evaluateJavascript(js, null);
                }
            } catch (Throwable t) {
                Log.e(TAG, "harden", t);
            }
        }

        /** Parse the JSON payload Q.anon.applyToNative() sends. */
        static void apply(String json) {
            try {
                JSONObject o = new JSONObject(json);
                String action = o.optString("action", "apply");
                if ("burn".equals(action)) {
                    clearCookies();
                    return;
                }
                /* The contract names these keys `ua` / `seed`; Q.anon sends the
                   richer aliases `defaultUa` / `perSiteSeed`. Accept both. An
                   empty value means "fall back to the engine UA" / "no seed". */
                if (o.has("defaultUa")) {
                    String ua = o.optString("defaultUa", "");
                    defaultUa = ua.isEmpty() ? null : ua;
                } else if (o.has("ua")) {
                    String ua = o.optString("ua", "");
                    defaultUa = ua.isEmpty() ? null : ua;
                }
                perSiteUa = o.optBoolean("perSiteUa", false);
                if (o.has("perSiteSeed")) perSiteSeed = o.optString("perSiteSeed", "");
                else if (o.has("seed")) perSiteSeed = o.optString("seed", "");
                String mu = o.optString("mobileUa", "");
                mobileUa = mu.isEmpty() ? null : mu;
                JSONArray pu = o.optJSONArray("personaUas");
                if (pu != null) {
                    String[] arr = new String[pu.length()];
                    for (int i = 0; i < pu.length(); i++) arr[i] = pu.optString(i, "");
                    personaUas = arr;
                }
                Map<String, String> us = new HashMap<>();
                JSONObject su = o.optJSONObject("siteUas");
                if (su != null) {
                    for (Iterator<String> it = su.keys(); it.hasNext(); ) {
                        String k = it.next();
                        us.put(k, su.optString(k, ""));
                    }
                }
                siteUas = us;
                replace(geoAllowed, o.optJSONArray("geoAllowed"));
                replace(mediaAllowed, o.optJSONArray("mediaAllowed"));
                cookieMode = o.optString("cookieMode", "session");
                Map<String, String> sc = new HashMap<>();
                JSONObject scu = o.optJSONObject("siteCookies");
                if (scu != null) {
                    for (Iterator<String> it = scu.keys(); it.hasNext(); ) {
                        String k = it.next();
                        sc.put(k, scu.optString(k, ""));
                    }
                }
                siteCookies = sc;
                blockThirdParty = o.optBoolean("blockThirdParty", true);
                ephemeral = o.optBoolean("ephemeral", false);
                Log.i(TAG, "identity applied: ua=" + (defaultUa == null ? "engine" : "synthetic")
                        + " perSite=" + perSiteUa + " geoAllowed=" + geoAllowed.size()
                        + " mediaAllowed=" + mediaAllowed.size()
                        + " cookies=" + cookieMode + " ephemeral=" + ephemeral);
            } catch (Exception e) {
                Log.e(TAG, "identity payload rejected: " + e);
            }
        }

        private static void replace(Set<String> set, JSONArray arr) {
            synchronized (set) {
                set.clear();
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        String h = arr.optString(i, "").toLowerCase();
                        if (!h.isEmpty()) set.add(h);
                    }
                }
            }
        }

        static boolean geoAllowedFor(String host) {
            if (host == null) return false;
            synchronized (geoAllowed) {
                return geoAllowed.contains(host.toLowerCase())
                        || geoAllowed.contains(bareHost(host));
            }
        }

        /**
         * Media capture gate: deny by default; only hosts the user explicitly
         * allowed inside the Identity app may capture. The OS bundle origin
         * (ORIGIN) is allowed — that is where the built-in Camera app lives.
         */
        static boolean mediaAllowedFor(String host) {
            if (host == null) return false;
            if (ORIGIN.equalsIgnoreCase(host)) return true;
            synchronized (mediaAllowed) {
                return mediaAllowed.contains(host.toLowerCase())
                        || mediaAllowed.contains(bareHost(host));
            }
        }

        static String bareHost(String host) {
            if (host == null) return "";
            host = host.toLowerCase();
            return host.startsWith("www.") ? host.substring(4) : host;
        }

        /** Same FNV-1a rule as Q.anon.uaFor so both sides agree per host. */
        static int fnv(String s, int mod) {
            long h = 0x811c9dc5L;
            for (int i = 0; i < s.length(); i++) {
                h ^= s.charAt(i);
                h = (h * 16777619L) & 0xFFFFFFFFL;
            }
            return mod <= 0 ? 0 : (int) (h % mod);
        }

        static String uaForHost(String host) {
            String bare = bareHost(host);
            Map<String, String> us = siteUas;
            String exact = us.get(host == null ? "" : host.toLowerCase());
            if (exact == null) exact = us.get(bare);
            if (exact != null && !exact.isEmpty()) return exact;
            String[] pool = personaUas;
            if (perSiteUa && pool.length > 0 && perSiteSeed != null && !perSiteSeed.isEmpty()) {
                return pool[fnv(perSiteSeed + "|" + bare, pool.length)];
            }
            return defaultUa;
        }

        static String cookieModeForHost(String host) {
            String m = siteCookies.get(host == null ? "" : host.toLowerCase());
            if (m == null) m = siteCookies.get(bareHost(host));
            return m != null && !m.isEmpty() ? m : cookieMode;
        }

        /** Engine-level settings both WebViews share. */
        static void applyTo(WebSettings s, WebView view) {
            String ua = defaultUa;
            if (ua != null && !ua.isEmpty()) {
                if (engineUa == null) engineUa = s.getUserAgentString();
                s.setUserAgentString(ua);
            }
            applyHints(s, ua != null && !ua.isEmpty() ? ua : s.getUserAgentString(), false);
            s.setGeolocationEnabled(false);
            /* WebView stamps X-Requested-With: <package name> on every request.
               The allowlist takes origins; empty means no origin gets it. */
            try {
                if (WebViewFeature.isFeatureSupported(
                        WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
                    WebSettingsCompat.setRequestedWithHeaderOriginAllowList(
                            s, Collections.emptySet());
                    if (rwStripped == null) Log.i(TAG, "X-Requested-With header suppressed");
                    rwStripped = Boolean.TRUE;
                } else {
                    if (rwStripped == null) {
                        Log.i(TAG, "X-Requested-With header not suppressible: feature absent");
                    }
                    rwStripped = Boolean.FALSE;
                }
            } catch (Throwable t) {
                if (rwStripped == null) Log.i(TAG, "X-Requested-With header not suppressible: " + t);
                rwStripped = Boolean.FALSE;
            }
            s.setCacheMode(ephemeral ? WebSettings.LOAD_NO_CACHE : WebSettings.LOAD_DEFAULT);
            try {
                CookieManager cm = CookieManager.getInstance();
                cm.setAcceptCookie(!"block".equals(cookieMode));
                if (Build.VERSION.SDK_INT >= 21 && view != null) {
                    cm.setAcceptThirdPartyCookies(view, !blockThirdParty);
                }
            } catch (Exception ignored) {
            }
        }

        /** Per-navigation: real UA + per-site cookie mode + geolocation. */
        static void applyNavigation(WebSettings s, String url) {
            applyNavigation(s, url, false);
        }

        /** Same, with the surface's desktop/mobile rendering mode. */
        static void applyNavigation(WebSettings s, String url, boolean mobile) {
            String host = null;
            try { host = Uri.parse(url).getHost(); } catch (Exception ignored) { }
            String ua = uaForHost(host);
            if (mobile && mobileUa != null && !mobileUa.isEmpty()) ua = mobileUa;
            if (ua != null && !ua.isEmpty()) s.setUserAgentString(ua);
            else if (engineUa != null) s.setUserAgentString(engineUa);
            applyHints(s, ua != null && !ua.isEmpty() ? ua : s.getUserAgentString(), mobile);
            s.setGeolocationEnabled(geoAllowedFor(host));
            try {
                CookieManager.getInstance().setAcceptCookie(
                        !"block".equals(cookieModeForHost(host)));
            } catch (Exception ignored) {
            }
        }

        private static UserAgentMetadata.BrandVersion brand(String name, String major) {
            return new UserAgentMetadata.BrandVersion.Builder()
                    .setBrand(name).setMajorVersion(major)
                    .setFullVersion(major + ".0.0.0").build();
        }

        private static String chromeMajor(String ua) {
            if (ua == null) return null;
            java.util.regex.Matcher m = CHROME_MAJOR.matcher(ua);
            return m.find() ? m.group(1) : null;
        }

        /* Client hints are stamped by the engine; without this the network
           header says "Android WebView" whatever the UA claims. Follow the
           spoofed UA's platform and Chrome version instead. */
        static void applyHints(WebSettings s, String ua, boolean mobile) {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) return;
            String u = ua == null ? "" : ua;
            String major = chromeMajor(u);
            String platform = u.contains("Windows") ? "Windows"
                    : u.contains("Macintosh") ? "macOS"
                    : u.contains("Android") ? "Android" : "Linux";
            try {
                /* A Firefox/Safari persona sends no client hints at all, but
                   the engine stamps them regardless; an empty brand list falls
                   back to "Android WebView". A Chrome brand list at the
                   engine's version is the closest we can get. */
                String mv = major == null ? chromeMajor(engineUa) : major;
                if (mv == null) return;
                UserAgentMetadata.Builder b = new UserAgentMetadata.Builder()
                        .setBrandVersionList(java.util.Arrays.asList(
                                brand("Not_A Brand", "24"),
                                brand("Chromium", mv),
                                brand("Google Chrome", mv)))
                        .setFullVersion(mv + ".0.0.0")
                        .setPlatform(platform)
                        .setMobile(mobile || u.contains("Mobile"));
                WebSettingsCompat.setUserAgentMetadata(s, b.build());
                if (hintsLogged == null) Log.i(TAG, "client hints: " + platform + " " + mv);
                hintsLogged = Boolean.TRUE;
            } catch (Throwable t) {
                if (hintsLogged == null) Log.i(TAG, "user agent metadata not applied: " + t);
                hintsLogged = Boolean.FALSE;
            }
        }

        static void clearCookies() {
            try {
                CookieManager cm = CookieManager.getInstance();
                cm.removeAllCookies(null);
                cm.removeSessionCookies(null);
                cm.flush();
                Log.i(TAG, "cookie store cleared (identity burned)");
            } catch (Exception ignored) {
            }
        }

        /** Session mode: cookies must not survive the user leaving the app. */
        static void clearSessionOnExit() {
            if (!"session".equals(cookieMode)) return;
            try {
                CookieManager cm = CookieManager.getInstance();
                cm.removeAllCookies(null);
                cm.flush();
                Log.i(TAG, "session cookie mode: cookie store cleared on exit");
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    protected void attachBaseContext(Context base) {
        /* The WebView sends Accept-Language from the app locale, and appends
           the system list, which carries en-IN. Run the app as en-US only. */
        Locale.setDefault(Locale.US);
        if (Build.VERSION.SDK_INT >= 24) {
            LocaleList.setDefault(LocaleList.forLanguageTags("en-US"));
        }
        Configuration c = new Configuration(base.getResources().getConfiguration());
        c.setLocale(Locale.US);
        super.attachBaseContext(base.createConfigurationContext(c));
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Identity.loadHarden(this);
        if (BuildConfig.DEBUG || BuildConfig.TEST_COPY) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

        getWindow().setBackgroundDrawableResource(R.color.boot_bg);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(DARK);

        web = new WebView(this);
        web.setBackgroundColor(DARK);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setLongClickable(false);
        web.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                return true;
            }
        });

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setLoadWithOverviewMode(false);
        s.setUseWideViewPort(false);
        s.setTextZoom(100);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setSupportMultipleWindows(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        if (Build.VERSION.SDK_INT >= 33) {
            s.setAlgorithmicDarkeningAllowed(false);
        }

        // Anonymity defaults, before the OS ever pushes its identity:
        // no location, first-party cookies only.
        s.setGeolocationEnabled(false);
        CookieManager.getInstance().setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= 21) {
            CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);
        }

        // A real page surface the OS can position inside its browser window.
        // Iframes inherit a site's framing policy; this WebView does not.
        surface = new NativeSurface(this, new NativeSurface.Listener() {
            @Override
            public void onEvent(String json) {
                if (web != null) {
                    web.evaluateJavascript("window.Q&&Q.nativeViewEvent&&Q.nativeViewEvent("
                            + json + ")", null);
                }
            }

            @Override
            public void pickFiles(ValueCallback<Uri[]> callback) {
                if (pendingSurfaceFiles != null) pendingSurfaceFiles.onReceiveValue(null);
                pendingSurfaceFiles = callback;
                Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
                pick.addCategory(Intent.CATEGORY_OPENABLE);
                pick.setType("*/*");
                try {
                    startActivityForResult(Intent.createChooser(pick, "Select file"), REQ_FILES);
                } catch (ActivityNotFoundException e) {
                    pendingSurfaceFiles = null;
                }
            }

            @Override
            public void requestCamera(PermissionRequest request) {
                handleMediaRequest(request, surface == null ? null : surface.topUrl());
            }

            @Override
            public void surfaceGone() {
                Log.e(TAG, "page surface stopped");
            }
        });
        FrameLayout.LayoutParams surfaceLp = new FrameLayout.LayoutParams(1, 1);
        surfaceLp.gravity = Gravity.TOP | Gravity.START;
        root.addView(surface, surfaceLp);
        web.addJavascriptInterface(new NativeBridge(), "__QzNative");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                pageReady = false;
                // Per-navigation identity: the UA in the HTTP layer follows
                // the Q.anon profile (and, with per-site UA on, differs by host).
                Identity.applyNavigation(view.getSettings(), url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (u == null) return null;
                String host = u.getHost();
                if (host != null && !ORIGIN.equals(host) && AdBlock.blocked(host)) {
                    // Ad/tracker hosts never reach the network, even when an
                    // embedded page pulls them in as a subresource.
                    Map<String, String> headers = new HashMap<>();
                    headers.put("Cache-Control", "no-store");
                    return new WebResourceResponse("text/plain", "utf-8", 204,
                            "Blocked by Quant", headers,
                            new ByteArrayInputStream(new byte[0]));
                }
                if (!ORIGIN.equals(host)) {
                    /* Fail closed: while Tor is off or starting, no http(s)
                       request leaves on the normal connection. */
                    String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase();
                    if (("http".equals(scheme) || "https".equals(scheme)) && !TorEngine.routed()) {
                        return TorEngine.heldOff();
                    }
                    return null;
                }
                return assetResponse(u);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (u == null) return true;
                String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase();
                if ("quant-open".equals(scheme) || "quant-browse".equals(scheme)) {
                    // The OS asks the shell to hand a URL to the system browser
                    // (quant-open) or to the full in-app browser (quant-browse).
                    // Only the top document may do this: a third-party iframe
                    // must not be able to open windows on its own.
                    if (!req.isForMainFrame()) return true;
                    String target = Handoff.target(u);
                    if (target.isEmpty()) return true;
                    if ("quant-browse".equals(scheme)) {
                        try {
                            startActivity(new Intent(MainActivity.this, BrowserActivity.class)
                                    .putExtra("url", target));
                        } catch (Exception e) {
                            openExternally(Uri.parse(target));
                        }
                    } else {
                        openExternally(Uri.parse(target));
                    }
                    return true;
                }
                if (ORIGIN.equals(u.getHost())) return false;
                // Embedded frames keep navigating inside their own box; only a
                // top-level jump leaves the OS for the system browser.
                if (!req.isForMainFrame()) {
                    Identity.applyNavigation(view.getSettings(), u.toString());
                    return false;
                }
                openExternally(u);
                return true;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest req,
                                        android.webkit.WebResourceError err) {
                if (req.isForMainFrame()) {
                    Log.e(TAG, "page error: " + err.getDescription());
                }
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                // A dead renderer must never take the whole shell down. Rebuild the
                // page once, with a cooldown so a persistent crash cannot loop.
                Log.e(TAG, "renderer gone, crashed=" + detail.didCrash());
                long now = SystemClock.elapsedRealtime();
                if (now - lastRendererRestart > 15000L && !isFinishing()) {
                    lastRendererRestart = now;
                    view.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (!isFinishing()) recreate();
                        }
                    }, 250L);
                } else {
                    Toast.makeText(MainActivity.this,
                            "Renderer stopped. Reopen Quant OS.", Toast.LENGTH_LONG).show();
                }
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb,
                                             FileChooserParams params) {
                if (pendingSurfaceFiles != null) {
                    pendingSurfaceFiles.onReceiveValue(null);
                    pendingSurfaceFiles = null;
                }
                if (pendingFiles != null) pendingFiles.onReceiveValue(null);
                pendingFiles = cb;
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
                        handleMediaRequest(request, web == null ? null : web.getUrl());
                    }
                });
            }

            @Override
            public boolean onConsoleMessage(ConsoleMessage m) {
                Log.d(TAG, m.message() + " @" + m.lineNumber());
                return true;
            }

            @Override
            public boolean onJsAlert(WebView view, String url, String message,
                                     android.webkit.JsResult result) {
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                result.confirm();
                return true;
            }
        });

        root.addView(web, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        /* The native page surface is a sibling of the OS page, and the page is
           opaque: without this the WebView added last would cover the site. */
        surface.bringToFront();
        setContentView(root);
        // The window's decor view does not exist until content is attached;
        // hiding the system bars earlier crashes on API 30+.
        root.post(new Runnable() {
            @Override
            public void run() {
                goImmersive();
            }
        });

        /* The shell document keeps the real clock (the script skips it), but
           its embedded frames are treated like site pages. */
        Identity.harden(web);
        web.loadUrl(START_URL);

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
        }
        torTick.post(torPoll);
        handleTorToggle(getIntent());
    }

    /**
     * The shade notification's only button. It reuses the OS page's own
     * start/stop so the header and every surface refresh; before the page is
     * loaded the engine is called directly and the page reads that state on
     * boot.
     */
    private void handleTorToggle(Intent intent) {
        if (intent == null || !intent.getBooleanExtra("torToggle", false)) return;
        intent.removeExtra("torToggle");
        if (pageReady && web != null) {
            web.evaluateJavascript("window.Q&&Q.tor&&(Q.tor.state().state==='on'"
                    + "||Q.tor.state().state==='starting'?Q.tor.stop():Q.tor.start())", null);
        } else {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (TorEngine.routed()) TorEngine.stop();
                        else TorEngine.start(getApplicationContext());
                    } catch (Throwable e) {
                        Log.e(TAG, "tor toggle", e);
                    }
                }
            }, "qz-tor-toggle").start();
        }
        TorNotification.update(getApplicationContext(), TorEngine.statusJson());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleTorToggle(intent);
    }

    /* ---------------- native page surface bridge ---------------- */

    /**
     * Methods here run on a WebView worker thread, so every call is posted to
     * the UI thread before touching the surface. Coordinates arrive in CSS px,
     * matching the OS page's own layout.
     */
    private class NativeBridge {

        private boolean ok(String token) {
            return bridgeToken.equals(token);
        }

        @JavascriptInterface
        public void show(String token, final String url, final double x, final double y,
                         final double w, final double h) {
            if (!ok(token)) return;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (surface != null) {
                        surface.show(url, (float) x, (float) y, (float) w, (float) h);
                    }
                }
            });
        }

        @JavascriptInterface
        public void bounds(String token, final double x, final double y,
                           final double w, final double h) {
            if (!ok(token)) return;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (surface != null) {
                        surface.bounds((float) x, (float) y, (float) w, (float) h);
                    }
                }
            });
        }

        @JavascriptInterface
        public void load(String token, final String url) {
            if (!ok(token)) return;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (surface != null) surface.load(url);
                }
            });
        }

        /** Desktop or mobile page rendering for the browser app. */
        @JavascriptInterface
        public void viewMode(String token, final String mode) {
            if (!ok(token)) return;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (surface != null) surface.setMode("mobile".equals(mode));
                }
            });
        }

        @JavascriptInterface
        public void hide(String token) {
            if (!ok(token)) return;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (surface != null) surface.hide();
                }
            });
        }

        @JavascriptInterface
        public void reload(String token) {
            if (!ok(token)) return;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (surface != null) surface.reload();
                }
            });
        }

        @JavascriptInterface
        public void stop(String token) {
            if (!ok(token)) return;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (surface != null) surface.stop();
                }
            });
        }

        @JavascriptInterface
        public void reset(String token) {
            if (!ok(token)) return;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (surface != null) surface.reset();
                }
            });
        }

        /* Synchronous: the page asks "where is the surface really?", so the
           answer must come from the fields the UI thread has already written. */
        @JavascriptInterface
        public String surfaceState(String token) {
            if (!ok(token)) return "";
            NativeSurface s = surface;
            return s == null ? "" : s.stateJson();
        }

        @JavascriptInterface
        public boolean openInApp(String token, String url) {
            if (!ok(token) || url == null || url.isEmpty()) return false;
            try {
                startActivity(new Intent(MainActivity.this, BrowserActivity.class)
                        .putExtra("url", url));
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        /**
         * Q.anon.applyToNative() entry point. Carries the whole synthetic
         * identity policy (action "apply") or burns the cookie store
         * (action "burn"). Runs on a worker thread; parsing is cheap, the
         * WebSettings writes are posted to the UI thread.
         */
        @JavascriptInterface
        public void identity(String token, final String json) {
            if (!ok(token)) return;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Identity.apply(json);
                    Identity.applyTo(web.getSettings(), web);
                    if (surface != null) surface.applyIdentity();
                    if ("burn".equals(safeAction(json))) {
                        if (surface != null) surface.clearPageCache();
                    }
                }
            });
        }

        /** Write a page-produced file into Downloads (public on API 29+). */
        @JavascriptInterface
        public boolean saveFile(String token, String name, String mime, String base64) {
            if (!ok(token) || base64 == null) return false;
            try {
                byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
                String safe = name == null || name.trim().isEmpty() ? "download" : name.trim()
                        .replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
                if (safe.length() > 120) safe = safe.substring(safe.length() - 120);
                String type = mime == null || mime.isEmpty()
                        ? "application/octet-stream" : mime;
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.MediaColumns.DISPLAY_NAME, safe);
                    values.put(MediaStore.MediaColumns.MIME_TYPE, type);
                    values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                            Environment.DIRECTORY_DOWNLOADS);
                    values.put(MediaStore.MediaColumns.IS_PENDING, 1);
                    Uri item = getContentResolver()
                            .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                    if (item == null) return false;
                    try (OutputStream out = getContentResolver().openOutputStream(item)) {
                        if (out == null) return false;
                        out.write(bytes);
                    } catch (Exception e) {
                        getContentResolver().delete(item, null, null);
                        return false;
                    }
                    values.clear();
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0);
                    getContentResolver().update(item, values, null, null);
                    return true;
                }
                /* shortcut: pre-29 files land in the app's private Downloads
                   folder (no storage permission); move to public Downloads when
                   minSdk reaches 29. */
                File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                if (dir == null) return false;
                if (!dir.exists() && !dir.mkdirs()) return false;
                try (OutputStream out = new FileOutputStream(new File(dir, safe))) {
                    out.write(bytes);
                }
                return true;
            } catch (Exception e) {
                Log.e(TAG, "saveFile failed: " + e);
                return false;
            }
        }

        private String safeAction(String json) {
            try {
                return new JSONObject(json).optString("action", "apply");
            } catch (Exception e) {
                return "apply";
            }
        }

        /* ---------------- Tor bridge (Q.tor / Q.shield) ---------------- */

        @JavascriptInterface
        public String torStatus(String token) {
            return ok(token) ? TorEngine.statusJson() : "";
        }

        @JavascriptInterface
        public String torStart(String token) {
            if (!ok(token)) return "error: bad token";
            try {
                return TorEngine.start(getApplicationContext());
            } catch (Throwable e) {
                Log.e(TAG, "torStart", e);
                return "error: " + e;
            }
        }

        @JavascriptInterface
        public String torStop(String token) {
            if (!ok(token)) return "error: bad token";
            try {
                return TorEngine.stop();
            } catch (Throwable e) {
                Log.e(TAG, "torStop", e);
                return "error: " + e;
            }
        }

        @JavascriptInterface
        public String torProbe(String token) {
            return ok(token) ? TorEngine.probe() : "error: bad token";
        }

        @JavascriptInterface
        public String torNewIdentity(String token) {
            return ok(token) ? TorEngine.newIdentity() : "error: bad token";
        }
    }

    /**
     * Media capture gate. The OS origin (the built-in Camera app runs inside
     * this page) and hosts the user explicitly allowed are granted; every
     * other site gets one explicit prompt. A request from a third-party frame
     * (origin different from the page the user is looking at) is always denied.
     */
    private void handleMediaRequest(final PermissionRequest request, String topUrl) {
        String originHost = hostOf(request.getOrigin());
        String topHost = hostOf(Uri.parse(topUrl == null ? "" : topUrl));
        Log.i(TAG, "media request from " + originHost + " (top " + topHost + ") resources="
                + java.util.Arrays.toString(request.getResources()));
        if (originHost.isEmpty() || !originHost.equals(topHost)) {
            request.deny();
            Log.i(TAG, "media request denied for " + originHost
                    + " (not the page's own origin)");
            return;
        }
        if (Identity.mediaAllowedFor(originHost)) {
            Log.i(TAG, "media request allowed by saved site permission for " + originHost);
            grantMedia(request);
            return;
        }
        Log.i(TAG, "media prompt shown for " + originHost);
        new AlertDialog.Builder(this)
                .setTitle("Allow camera and microphone?")
                .setMessage(originHost + " is asking to use this device's camera or microphone.")
                .setPositiveButton("Allow", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Log.i(TAG, "media allowed by user for " + originHost);
                        grantMedia(request);
                    }
                })
                .setNegativeButton("Deny", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Log.i(TAG, "media denied by user for " + originHost);
                        request.deny();
                    }
                })
                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                    @Override
                    public void onCancel(DialogInterface dialog) {
                        Log.i(TAG, "media prompt cancelled for " + originHost);
                        request.deny();
                    }
                })
                .show();
    }

    private void grantMedia(PermissionRequest request) {
        ArrayList<String> want = new ArrayList<>();
        ArrayList<String> missingPerms = new ArrayList<>();
        String[] resources = request.getResources();
        for (String r : resources) {
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)) {
                want.add(r);
                if (checkSelfPermission(Manifest.permission.CAMERA)
                        != PackageManager.PERMISSION_GRANTED) {
                    missingPerms.add(Manifest.permission.CAMERA);
                }
            } else if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) {
                want.add(r);
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                        != PackageManager.PERMISSION_GRANTED) {
                    missingPerms.add(Manifest.permission.RECORD_AUDIO);
                }
            } else {
                // protected media-id and anything unknown: never granted
                request.deny();
                return;
            }
        }
        if (want.isEmpty()) {
            request.deny();
            return;
        }
        Log.i(TAG, "media grant: want=" + want + " missing=" + missingPerms);
        if (!missingPerms.isEmpty()) {
            if (pendingPermission != null) pendingPermission.deny();
            pendingPermission = request;
            requestPermissions(missingPerms.toArray(new String[0]), REQ_CAMERA);
        } else {
            request.grant(want.toArray(new String[0]));
        }
    }

    private static String hostOf(Uri u) {
        if (u == null) return "";
        String h = u.getHost();
        return h == null ? "" : Identity.bareHost(h);
    }

    /* ---------------- assets ---------------- */

    private WebResourceResponse assetResponse(Uri u) {
        String path = u.getPath();
        if (path == null || !path.startsWith("/assets/")) return null;
        String name = path.substring("/assets/".length());
        if (name.isEmpty() || name.contains("..")) return null;
        String mime;
        if (name.endsWith(".html")) mime = "text/html";
        else if (name.endsWith(".js")) mime = "application/javascript";
        else if (name.endsWith(".css")) mime = "text/css";
        else if (name.endsWith(".json")) mime = "application/json";
        else if (name.endsWith(".png")) mime = "image/png";
        else if (name.endsWith(".svg")) mime = "image/svg+xml";
        else mime = "application/octet-stream";
        try {
            if ("quant-os.html".equals(name)) {
                /* This document is the only place the bridge token exists.
                   A page loaded into an iframe from another origin cannot
                   read it, so it cannot drive the shell. */
                String html = new String(readAll(getAssets().open(name)), "UTF-8")
                        .replace("__QZ_TOKEN__", bridgeToken);
                Map<String, String> headers = new HashMap<>();
                headers.put("Cache-Control", "no-store");
                WebResourceResponse res = new WebResourceResponse("text/html", "UTF-8",
                        new ByteArrayInputStream(html.getBytes("UTF-8")));
                res.setResponseHeaders(headers);
                return res;
            }
            InputStream in = getAssets().open(name);
            WebResourceResponse res = new WebResourceResponse(mime, "UTF-8", in);
            Map<String, String> headers = new HashMap<>();
            headers.put("Cache-Control", "no-cache");
            res.setResponseHeaders(headers);
            return res;
        } catch (IOException e) {
            return null;
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    private void openExternally(Uri u) {
        String scheme = u.getScheme();
        if (scheme == null) return;
        scheme = scheme.toLowerCase();
        if (scheme.equals("http") || scheme.equals("https") || scheme.equals("mailto")
                || scheme.equals("tel") || scheme.equals("sms")) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, u));
            } catch (ActivityNotFoundException ignored) {
                Toast.makeText(this, "No app can open this link", Toast.LENGTH_SHORT).show();
            }
        }
    }

    /* ---------------- system bars / back ---------------- */

    private void goImmersive() {
        Window w = getWindow();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            View decor = w.getDecorView();
            if (decor == null) return;
            w.setDecorFitsSystemWindows(false);
            WindowInsetsController c = decor.getWindowInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                c.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            w.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            w.setStatusBarColor(Color.TRANSPARENT);
            w.setNavigationBarColor(Color.TRANSPARENT);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused) goImmersive();
    }

    @Override
    public void onBackPressed() {
        if (surface != null && surface.inFullscreen()) {
            surface.exitFullscreen();
            return;
        }
        if (web == null || backPending) {
            if (web == null) leaveApp();
            return;
        }
        backPending = true;
        web.evaluateJavascript(
                "(function(){try{return !!(window.Q&&Q.androidBack&&Q.androidBack());}"
                        + "catch(e){return false;}})()",
                new ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String value) {
                        backPending = false;
                        if (!"true".equals(value)) {
                            if (web != null && web.canGoBack()) web.goBack();
                            else leaveApp();
                        }
                    }
                });
    }

    private void leaveApp() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            finishAndRemoveTask();
        } else {
            finish();
        }
    }

    /* ---------------- runtime permissions ---------------- */

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == REQ_NOTIFY) {
            TorNotification.update(getApplicationContext(), TorEngine.statusJson());
            return;
        }
        if (code != REQ_CAMERA) return;
        PermissionRequest req = pendingPermission;
        pendingPermission = null;
        if (req == null) return;
        ArrayList<String> grantable = new ArrayList<>();
        for (String r : req.getResources()) {
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)
                    && checkSelfPermission(Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED) {
                grantable.add(r);
            } else if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)
                    && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED) {
                grantable.add(r);
            }
        }
        if (!grantable.isEmpty()
                && grantable.size() == req.getResources().length) {
            req.grant(grantable.toArray(new String[0]));
        } else {
            req.deny();
            Toast.makeText(this, "Camera/microphone permission is required for this site",
                    Toast.LENGTH_LONG).show();
        }
    }

    /* ---------------- file picker ---------------- */

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILES) {
            Uri[] out = null;
            if (resultCode == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int n = data.getClipData().getItemCount();
                    out = new Uri[n];
                    for (int i = 0; i < n; i++) {
                        out[i] = data.getClipData().getItemAt(i).getUri();
                    }
                } else if (data.getData() != null) {
                    out = new Uri[]{data.getData()};
                }
            }
            if (pendingFiles != null) {
                pendingFiles.onReceiveValue(out);
                pendingFiles = null;
            } else if (pendingSurfaceFiles != null) {
                pendingSurfaceFiles.onReceiveValue(out);
                pendingSurfaceFiles = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    protected void onPause() {
        if (surface != null) surface.onPause();
        if (web != null) web.onPause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
        if (surface != null) surface.onResume();
    }

    @Override
    protected void onDestroy() {
        if (isFinishing()) Identity.clearSessionOnExit();
        torTick.removeCallbacks(torPoll);
        TorNotification.cancel(this);
        if (pendingSurfaceFiles != null) {
            pendingSurfaceFiles.onReceiveValue(null);
            pendingSurfaceFiles = null;
        }
        if (surface != null) {
            surface.release();
            surface = null;
        }
        if (pendingFiles != null) {
            pendingFiles.onReceiveValue(null);
            pendingFiles = null;
        }
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
