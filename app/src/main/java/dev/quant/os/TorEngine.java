package dev.quant.os;

import android.content.Context;
import android.util.Log;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;

import org.json.JSONObject;
import org.torproject.jni.TorService;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Runs the bundled tor in this process and points the WebView at its SOCKS
 * port. No VPN: the shell is one WebView, so a per-app proxy carries all of its
 * traffic while Tor is on, and a failed proxy fails closed (no DIRECT fallback
 * is added). While Tor is not routed (off, starting, failed) every http(s)
 * request in the app is replaced with a held-off page, so no site can see the
 * phone's real address.
 *
 * 1.8.3 could not start tor because its local relay passed empty
 * Socks5ProxyUsername/Password, which tor rejects as invalid config. This
 * engine has no relay step, so that whole failure was removed rather than
 * patched.
 */
public final class TorEngine {
    private static final String TAG = "QuantTor";
    private static final String VERSION = "0.4.9.17";
    private static final int BOOT_TIMEOUT_MS = 120_000;
    private static final Pattern PROGRESS = Pattern.compile("PROGRESS=(\\d+)");
    private static final Pattern PHASE_TAG = Pattern.compile("TAG=(\\S+)");

    private static final Object LOCK = new Object();

    private static volatile String state = "off";
    private static volatile String phase = "";
    private static volatile int progress = 0;
    private static volatile String error = "";
    private static volatile String probeError = "";
    private static volatile String exitIp = "";
    private static volatile Boolean verified = null;
    private static volatile long probeAt = 0;
    private static volatile boolean socksOpen = false;
    private static volatile boolean proxyArmed = false;
    private static volatile int socksPort = 0;
    private static volatile int controlPort = 0;
    private static volatile boolean stopRequested = false;

    private static File dir;
    private static File logFile;
    private static File cookieFile;
    private static Thread torThread;
    private static TorService jni;

    private TorEngine() {
    }

    static String start(Context ctx) {
        synchronized (LOCK) {
            if (torThread != null && torThread.isAlive()) return state;
            try {
                dir = new File(ctx.getFilesDir(), "tor");
                if (!dir.isDirectory() && !dir.mkdirs()) {
                    return failNow("cannot create " + dir);
                }
                logFile = new File(dir, "notices.log");
                cookieFile = new File(dir, "control_auth_cookie");
                socksPort = freePort();
                controlPort = freePort();
                if (socksPort <= 0 || controlPort <= 0 || socksPort == controlPort) {
                    return failNow("no free local port for tor");
                }
            } catch (Exception e) {
                return failNow(String.valueOf(e));
            }
            state = "starting";
            phase = "";
            progress = 0;
            error = "";
            probeError = "";
            exitIp = "";
            verified = null;
            probeAt = 0;
            socksOpen = false;
            stopRequested = false;
            /* Armed now, not when bootstrap finishes: traffic must fail closed
               rather than leak direct while tor comes up. */
            proxyArmed = WebViewProxy.apply("socks5://127.0.0.1:" + socksPort);
            if (!proxyArmed) {
                return failNow("this WebView cannot force the Tor proxy, so Tor cannot start");
            }
            torThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    runTor();
                }
            }, "qz-tor");
            torThread.start();
            Thread watch = new Thread(new Runnable() {
                @Override
                public void run() {
                    watch();
                }
            }, "qz-tor-watch");
            watch.start();
            return "ok";
        }
    }

    static String stop() {
        synchronized (LOCK) {
            if (torThread == null || !torThread.isAlive()) {
                state = "off";
                WebViewProxy.apply(null);
                proxyArmed = false;
                return "off";
            }
            stopRequested = true;
            state = "stopping";
        }
        ctl("SIGNAL SHUTDOWN");
        try {
            torThread.join(6000);
        } catch (InterruptedException ignored) {
        }
        if (torThread != null && torThread.isAlive()) ctl("SIGNAL HALT");
        try {
            if (torThread != null) torThread.join(3000);
        } catch (InterruptedException ignored) {
        }
        state = "off";
        phase = "";
        progress = 0;
        exitIp = "";
        verified = null;
        probeAt = 0;
        socksOpen = false;
        socksPort = 0;
        WebViewProxy.apply(null);
        proxyArmed = false;
        return "off";
    }

    static String newIdentity() {
        if (!"on".equals(state)) return "off".equals(state) ? "no-tor" : "not-ready";
        String reply = ctl("SIGNAL NEWNYM");
        if (reply.contains("515")) return "no-control";
        if (!reply.contains("250")) return "error: no reply from the control port";
        exitIp = "";
        verified = null;
        probeAt = 0;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException ignored) {
                }
                runProbe();
            }
        }, "qz-tor-newnym-probe");
        t.start();
        return "ok";
    }

    static String probe() {
        if (!"on".equals(state)) return "not-ready";
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                runProbe();
            }
        }, "qz-tor-probe");
        t.start();
        return "ok";
    }

    /** True only while the SOCKS override really is in force (starting or on). */
    static boolean routed() {
        return proxyArmed && ("starting".equals(state) || "on".equals(state));
    }

    private static final String HELD_OFF_HTML =
            "<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'>"
                    + "<body style='background:#0b0e12;color:#e8edf2;font-family:system-ui,sans-serif;"
                    + "display:flex;align-items:center;justify-content:center;height:100vh;margin:0'>"
                    + "<div style='max-width:420px;text-align:center;padding:24px'>"
                    + "<div style='font-size:34px'>&#128274;</div>"
                    + "<h2 style='font-weight:600'>Tor is not running</h2>"
                    + "<p style='color:#9aa3ad;font-size:14px;line-height:1.6'>"
                    + "Pages stay closed while Tor is off, so no site can see this device&#8217;s real "
                    + "address. Turn Tor on in the Network or Identity app, then reload.</p>"
                    + "</div></body>";

    /** The page used in place of any site while Tor is not routed. */
    static WebResourceResponse heldOff() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Cache-Control", "no-store");
        return new WebResourceResponse("text/html", "utf-8", 200, "Held off", headers,
                new ByteArrayInputStream(HELD_OFF_HTML.getBytes(StandardCharsets.UTF_8)));
    }

    /** Navigate a WebView to the held-off page (for a click or a typed address). */
    static void showHeldOff(WebView view) {
        view.loadDataWithBaseURL(null, HELD_OFF_HTML, "text/html", "utf-8", null);
    }

    static String statusJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("available", true);
            o.put("state", state);
            o.put("builtin", true);
            o.put("builtinVersion", VERSION);
            o.put("builtinPhase", phase);
            o.put("builtinProgress", progress);
            o.put("builtinError", error);
            o.put("builtinLog", "error".equals(state) ? logTail() : "");
            o.put("error", error);
            o.put("orbot", false);
            if (socksPort > 0) {
                o.put("socksHost", "127.0.0.1");
                o.put("socksPort", socksPort);
                o.put("socksOpen", socksOpen);
            }
            if (!exitIp.isEmpty()) {
                o.put("IP", exitIp);
                o.put("exitIp", exitIp);
            }
            if (verified != null) o.put("torVerified", verified.booleanValue());
            if (probeAt > 0) o.put("probeAgeMs", System.currentTimeMillis() - probeAt);
            if (!probeError.isEmpty()) o.put("probeError", probeError);
        } catch (Exception ignored) {
        }
        return o.toString();
    }

    /* ---------------- tor thread ---------------- */

    private static void runTor() {
        TorService t = null;
        boolean created = false;
        int rc = -1;
        try {
            t = new TorService();
            created = t.createTorConfiguration();
            if (!created) {
                throw new IllegalStateException("libtor could not allocate a configuration");
            }
            if (!t.mainConfigurationSetCommandLine(arguments())) {
                throw new IllegalStateException("libtor rejected the command line");
            }
            rc = t.runMain();
        } catch (Throwable e) {
            Log.e(TAG, "tor failed", e);
            if (!stopRequested) fail(String.valueOf(e));
        } finally {
            if (created) {
                try {
                    t.mainConfigurationFree();
                } catch (Throwable ignored) {
                }
            }
            if (stopRequested) {
                state = "off";
            } else if (!"error".equals(state)) {
                if (rc != 0) {
                    fail("tor exited with code " + rc + " — " + lastLogLine());
                } else if (!"on".equals(state)) {
                    fail("tor stopped before it finished bootstrapping — " + lastLogLine());
                }
            }
        }
    }

    private static String[] arguments() {
        return new String[]{
                "tor",
                "--RunAsDaemon", "0",
                "--ignore-missing-torrc",
                "--DataDirectory", dir.getAbsolutePath(),
                "--ControlPort", "127.0.0.1:" + controlPort,
                "--CookieAuthentication", "1",
                "--CookieAuthFile", cookieFile.getAbsolutePath(),
                "--SocksPort", "127.0.0.1:" + socksPort,
                "--ClientOnly", "1",
                "--AvoidDiskWrites", "1",
                "--SafeLogging", "1",
                "--TruncateLogFile", "1",
                "--Log", "notice file " + logFile.getAbsolutePath()
        };
    }

    /* ---------------- bootstrap monitor ---------------- */

    private static void watch() {
        long deadline = System.currentTimeMillis() + BOOT_TIMEOUT_MS;
        while (torThread != null && torThread.isAlive()) {
            try {
                if ("starting".equals(state)) {
                    String reply = ctl("GETINFO status/bootstrap-phase");
                    Matcher m = PROGRESS.matcher(reply);
                    if (m.find()) {
                        int p = Integer.parseInt(m.group(1));
                        if (p > progress) progress = p;
                        Matcher t = PHASE_TAG.matcher(reply);
                        if (t.find()) phase = t.group(1);
                        if (progress >= 100) {
                            state = "on";
                            probe();
                        }
                    }
                    if (System.currentTimeMillis() > deadline) {
                        fail("tor did not finish bootstrapping within two minutes — " + lastLogLine());
                        break;
                    }
                }
                socksOpen = canConnect(socksPort);
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    /* ---------------- control port ---------------- */

    private static String ctl(String command) {
        if (controlPort <= 0) return "!control port not set";
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress("127.0.0.1", controlPort), 2000);
            s.setSoTimeout(500);
            String cookie = cookieHex();
            OutputStream out = s.getOutputStream();
            out.write(("AUTHENTICATE " + cookie + "\r\n" + command + "\r\n")
                    .getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            BufferedReader in = new BufferedReader(new InputStreamReader(
                    s.getInputStream(), StandardCharsets.ISO_8859_1));
            StringBuilder sb = new StringBuilder();
            while (sb.length() < 4096) {
                String line;
                try {
                    line = in.readLine();
                } catch (IOException timeout) {
                    break;
                }
                if (line == null) break;
                sb.append(line).append('\n');
                if (line.startsWith("5")) break;
            }
            return sb.toString();
        } catch (Exception e) {
            return "!" + e;
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static String cookieHex() {
        try (InputStream in = new FileInputStream(cookieFile)) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[64];
            int n;
            while ((n = in.read(b)) > 0) buf.write(b, 0, n);
            byte[] raw = buf.toByteArray();
            StringBuilder sb = new StringBuilder(raw.length * 2);
            for (byte x : raw) sb.append(String.format("%02x", x & 0xff));
            return sb.toString();
        } catch (IOException e) {
            return "";
        }
    }

    /* ---------------- exit check ---------------- */

    private static void runProbe() {
        Socket s = new Socket();
        verified = null;
        probeError = "";
        try {
            s.connect(new InetSocketAddress("127.0.0.1", socksPort), 5000);
            s.setSoTimeout(30000);
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();

            out.write(new byte[]{5, 1, 0});
            out.flush();
            byte[] greet = new byte[2];
            readFully(in, greet, 2);
            if (greet[0] != 5 || greet[1] != 0) throw new IOException("SOCKS greeting refused");

            byte[] host = "check.torproject.org".getBytes(StandardCharsets.US_ASCII);
            byte[] req = new byte[5 + host.length + 2];
            req[0] = 5;
            req[1] = 1;
            req[2] = 0;
            req[3] = 3;
            req[4] = (byte) host.length;
            System.arraycopy(host, 0, req, 5, host.length);
            req[5 + host.length] = 0x01;
            req[6 + host.length] = (byte) 0xbb;
            out.write(req);
            out.flush();

            byte[] head = new byte[4];
            readFully(in, head, 4);
            if (head[0] != 5 || head[1] != 0) throw new IOException("SOCKS connect refused (code " + head[1] + ")");
            int skip = head[3] == 1 ? 4 : head[3] == 4 ? 16 : in.read() & 0xff;
            readFully(in, new byte[skip + 2], skip + 2);

            SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            SSLSocket tls = (SSLSocket) factory.createSocket(s, "check.torproject.org", 443, true);
            tls.startHandshake();
            tls.getOutputStream().write(("GET /api/ip HTTP/1.1\r\n"
                    + "Host: check.torproject.org\r\n"
                    + "User-Agent: Quant-OS\r\n"
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));

            ByteArrayOutputStream body = new ByteArrayOutputStream();
            byte[] b = new byte[2048];
            int n;
            while ((n = tls.getInputStream().read(b)) > 0) {
                body.write(b, 0, n);
                if (body.size() > 64 * 1024) break;
            }
            String text = body.toString("UTF-8");
            int open = text.indexOf('{');
            int close = text.lastIndexOf('}');
            if (open < 0 || close <= open) throw new IOException("no JSON from check.torproject.org");
            JSONObject o = new JSONObject(text.substring(open, close + 1));
            String ip = o.optString("IP", o.optString("real_ip", ""));
            boolean isTor = o.optBoolean("IsTor", false) || o.optBoolean("tor", false);
            exitIp = ip;
            verified = isTor;
            probeAt = System.currentTimeMillis();
            if (!isTor) probeError = "check.torproject.org answered from " + ip + " with IsTor=false";
        } catch (Exception e) {
            Log.w(TAG, "probe failed: " + e);
            verified = null;
            probeError = String.valueOf(e);
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    /* ---------------- helpers ---------------- */

    private static void readFully(InputStream in, byte[] b, int len) throws IOException {
        int off = 0;
        while (off < len) {
            int n = in.read(b, off, len - off);
            if (n < 0) throw new IOException("SOCKS stream closed");
            off += n;
        }
    }

    private static boolean canConnect(int port) {
        if (port <= 0) return false;
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress("127.0.0.1", port), 400);
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static int freePort() {
        try (ServerSocket s = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            return s.getLocalPort();
        } catch (IOException e) {
            return -1;
        }
    }

    private static void fail(String why) {
        if (!"error".equals(state)) Log.e(TAG, why);
        error = why;
        state = "error";
        socksOpen = false;
    }

    private static String failNow(String why) {
        error = why;
        state = "error";
        return "error: " + why;
    }

    private static String lastLogLine() {
        String tail = logTail();
        int at = tail.lastIndexOf('\n', tail.length() - 2);
        return at < 0 ? tail : tail.substring(at + 1).trim();
    }

    private static String logTail() {
        try (InputStream in = new FileInputStream(logFile)) {
            byte[] all = new byte[(int) Math.min(logFile.length(), 64 * 1024)];
            int off = 0;
            while (off < all.length) {
                int n = in.read(all, off, all.length - off);
                if (n < 0) break;
                off += n;
            }
            String text = new String(all, 0, off, StandardCharsets.UTF_8).trim();
            if (text.length() > 1500) text = text.substring(text.length() - 1500);
            return text;
        } catch (IOException e) {
            return "";
        }
    }
}
