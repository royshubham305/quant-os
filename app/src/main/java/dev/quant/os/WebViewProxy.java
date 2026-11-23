package dev.quant.os;

import android.util.Log;

import androidx.webkit.ProxyConfig;
import androidx.webkit.ProxyController;
import androidx.webkit.WebViewFeature;

import java.util.concurrent.Executor;

/**
 * Points every WebView in this process at a proxy. The rule has no DIRECT
 * fallback, so a dead tor fails pages instead of quietly using the real
 * connection.
 */
final class WebViewProxy {
    private static final String TAG = "QuantTor";
    private static final Executor DIRECT = new Executor() {
        @Override
        public void execute(Runnable r) {
            r.run();
        }
    };
    private static final Runnable DONE = new Runnable() {
        @Override
        public void run() {
        }
    };

    private WebViewProxy() {
    }

    /**
     * Pass a rule such as "socks5://127.0.0.1:9050", or null to clear.
     * Returns false when the override was not accepted: pages then use the
     * normal connection, so the caller must keep browsing closed.
     */
    static boolean apply(String rule) {
        try {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                Log.e(TAG, "this WebView does not support proxy overrides");
                return false;
            }
            if (rule == null) {
                ProxyController.getInstance().clearProxyOverride(DIRECT, DONE);
            } else {
                ProxyConfig config = new ProxyConfig.Builder().addProxyRule(rule).build();
                ProxyController.getInstance().setProxyOverride(config, DIRECT, DONE);
            }
            Log.i(TAG, rule == null ? "webview proxy cleared" : "webview proxy " + rule);
            return true;
        } catch (Throwable e) {
            Log.e(TAG, "webview proxy could not be set: " + e);
            return false;
        }
    }
}
