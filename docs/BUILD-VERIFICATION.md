# Build verification — Quant OS APK 1.9.6

Date: 2026-11-22
Environment: Windows 10 x64, JDK 17.0.12, Gradle 8.11.1 (cached locally;
the wrapper pins 8.10 and its download was blocked by the network here),
AGP 8.5.2, Android SDK platform 34, build-tools 34.0.0.

## Build commands actually run

```
gradle :app:testReleaseUnitTest                 -> BUILD SUCCESSFUL (5/5 tests)
gradle assembleRelease                          -> BUILD SUCCESSFUL
apksigner verify -v dist/Quant-OS-1.9.6.apk
zipalign -c 4 dist/Quant-OS-1.9.6.apk
aapt2 dump badging dist/Quant-OS-1.9.6.apk
unzip -p dist/Quant-OS-1.9.6.apk assets/quant-os.html | md5sum
unzip -p dist/Quant-OS-1.9.6.apk assets/harden.js | md5sum
```

## Build results

| Check | Result |
|---|---|
| APK size | 4,502,322 B (libtor 7.6 MB → 3.1 MB compressed; HTML 1.38 MB → 0.35 MB) |
| APK md5 | `00e99d06c9df7d2001dd5b3969333495` (delivered `dist/Quant-OS-1.9.6.apk`) |
| Signature | v1 true, v2 true, v3 true; single signer `CN=Quartz OS, O=Quartz, C=XX` |
| Signer SHA-256 | `d7c5e36eff3bc95a029edd6311925b0fed72c3bafdce8e8a94f12f67e3f0b4a4` |
| zipalign -c 4 | successful |
| Package | `dev.quartz.os`, versionCode 196, versionName 1.9.6 |
| SDK | min 23, target 34, compile 34 |
| Native libraries | `libtor.so`, arm64-v8a only (emulator builds: add `-PwithX86`); compressed in the APK, `extractNativeLibs=true` so the installer unpacks them |
| Classes in classes.dex | MainActivity, NativeSurface, BrowserActivity, AdBlock, Handoff, TorEngine, WebViewProxy, TorNotification, org.torproject.jni.TorService |
| Embedded assets | `assets/quant-os.html` md5 `9829074bd28c4925309c5223e361855d`; `assets/harden.js` md5 `561f47a07c53ea97a24e93dbfe68db63` = source files |
| AdBlock unit test | 5/5 passed (exact host, subdomain, uppercase, near-miss, null/empty) |

The earlier 1,459,564 B figure in this file described a build made before
libtor was bundled; it is not the shipped shape.

## Device run

Device: Xiaomi 25113PN0EI (`pudding_in`), Android 14, 1220x2656 at 520 dpi,
on 2026-11-22. The APK was installed as a side-by-side copy
(`dev.quartz.os.qtest`, built with `-PtestCopy`) so the store-installed
`dev.quartz.os` was left untouched.

| Test | Result |
|---|---|
| Cold start | `am start -W` reported TotalTime 187 ms (1.9.5 run); OS desktop rendered, title bar shows v1.9.6 |
| In-window browser | Web app opened inside its OS window (toolbar, tabs and taskbar visible), no jump to a full-screen activity |
| Real page in window | `example.com` rendered in the native surface |
| Navigation | DuckDuckGo loaded and searched; results rendered in the same window |
| Camera consent | `webrtc.github.io` getUserMedia produced the per-site dialog "Allow camera and microphone?" |
| Deny | Log `media denied by user`; page showed `NotAllowedError`; camera stayed off; app CAMERA permission remained `granted=false` |
| Allow chain | Log `media allowed by user`, then the Android system prompt "Allow Quant OS to take pictures and record video?"; denying there left the camera off |
| Download | Editor's download button wrote `untitled.txt` (17 B, "hello from quant") to `/sdcard/Download`, content verified with `cat` |
| Tor identity | 1.9.1-qtest: tor bootstrapped; check.torproject.org reported IsTor=true through the identity probe and through the WebView. After the size work (compressed, extracted `libtor.so`) a qtest build bootstrapped to 100% and the exit probe verified (exit 171.25.193.36) |
| WebRTC leak test | browserleaks.com/webrtc loaded in the OS browser: RTCPeerConnection `False`, RTCDataChannel `False`, Local IP `-`, Public IP `-`, verdict "No Leak". A hidden sandboxed frame appended by a script returned `undefined` for RTCPeerConnection, so the constructor is not borrowable through `contentWindow` |
| Request headers | A header-echo server reached over `adb reverse` (the phone's Chromium DNS was broken that day, so no public site) saw: `Accept-Language: en-US,en;q=0.9`; `sec-ch-ua: "Not_A Brand";v="24", "Chromium";v="158", "Google Chrome";v="158"` with `sec-ch-ua-platform: "Linux"` matching the Firefox persona (before 1.9.1 it said "Android WebView", platform "Android"); `navigator.userAgentData` in page JS matched the headers; `X-Requested-With: dev.quartz.os.qtest` could not be removed |
| Touch game controls | Through the debug bridge on the test copy: holding right on Tetris stepped the piece 4 cells in 700 ms, holding down soft-dropped 7 rows in 650 ms, holding up produced exactly one rotation (up is excluded from repeat). Snake ran while a direction was held and turned normally. Swipes and taps still route through the same press/release path |
| Tor-off gate (1.9.4) | With Tor off, the Web app's page surface stayed on "Tor is not running — Pages stay closed while Tor is off, so no site can see this device's real address" while the address bar kept `https://example.com`; the full-screen browser ("OS browser window") showed the same held-off page. The 1.9.2 run of the same gate also survived an app restart |
| Internet off while Tor is off (1.9.5) | With Tor off, the title bar and System card read OFFLINE / "Tor is off · internet off" and the Network hero read "Offline · Tor is off · internet off · 6.85.92.70" with Location, Provider and Latency empty and Last check "never". Test answered with the red note "internet is off while Tor is off — turn Tor on in the Identity app" and Ping refused with the same message; no request was attempted |
| Tor-on routing (1.9.5) | After Start in the Identity app the header returned to WIFI and the Network app measured `45.66.35.22` (a Tor exit: SABOTAGE LLC, Amsterdam, 1798 ms), through the SOCKS probe/pages. On 1.9.4, `check.torproject.org` inside the Web app answered "This browser is configured to use Tor" with "Your IP address appears to be: 109.70.100.1", the same exit the probe reported, and the held-off tab reloaded into the real page by itself once Tor came on |
| Virtual egress (1.9.5) | Network, Identity and the desktop card show the per-identity virtual address `6.85.92.70` while Tor is off, labelled as a placeholder; with Tor on the Network app shows the measured exit (`45.66.35.22` this run). On 1.9.2, `anon rotate` in the Terminal changed the placeholder from `8.70.9.31` to `161.203.79.12` |
| Notification shortcut (1.9.6) | The shade showed an ongoing notification "Quant OS internet — Internet is off (Tor is off)" with one action "Turn internet on". The action's exact intent (activity with `torToggle=true`) started Tor: the notification read "Internet is on through Tor", the title bar returned to WIFI and the Identity card showed `torVerified:true` at exit `204.8.96.158`. The same intent then stopped Tor and the notification returned to "Internet is off (Tor is off)". The physical tap on the action bubble was not exercised: a live call kept changing the shade layout during the run |
| Crashes | Empty crash buffer for the test copy during the run |

During device testing the code gained media-consent log lines on tag `QuantOS`
(request, prompt, allow, deny, grant). They are inert otherwise.

## Rebuild note

The 1.9.6 build was made once from this tree (md5
`00e99d06c9df7d2001dd5b3969333495`). The earlier 1.9.2 pair of builds matched
byte for byte; that claim stays with 1.9.2. Trust the content checks (asset
md5, class list, manifest) first.

## Not verified

- The full-screen `BrowserActivity` (reachable only through an in-app
  hand-off) was not exercised beyond its WebView configuration.
- File picker / upload, link downloads handled by DownloadManager, and
  geolocation (the app has no location permission) were not exercised.
- The store-installed package was not updated or tested; the device run used
  the temporary `dev.quartz.os.qtest` copy.
- Both Tor states were device-tested on 1.9.5: off shows OFFLINE and the
  Network tools refuse; on measures through a verified Tor exit. The page
  gate itself was device-tested on 1.9.4 (Java unchanged). The Terminal
  commands share the same `Q.net` gate but could not be typed into the
  on-device Terminal from adb in this session, so `netcheck`/`curl` were
  checked by code only. 1.9.3 briefly removed the gate and is superseded.
- `X-Requested-With` could not be removed: WebView 158.0.8127.46 on this phone
  does not advertise `REQUESTED_WITH_HEADER_ALLOW_LIST` (the string exists
  only in `libwebviewchromium.so`). The release build sends `dev.quartz.os`.
- Client-hint override was tested with the Firefox persona only. A Firefox or
  Safari persona still carries a Chrome `sec-ch-ua` brand list, because an
  empty list makes the engine fall back to "Android WebView" — the header
  cannot be removed from a WebView. A Chrome/Edge persona should produce
  matching hints but was not device-tested.
- The phone's WebView DNS was broken during the header test (system Chromium
  showed `Failed to read DnsConfig` and `ERR_NAME_NOT_RESOLVED` in every app);
  the echo server was reached over `adb reverse` instead of the internet.
- x86_64 was cut from the release APK to save 8.4 MB. Emulator builds need
  `-PwithX86`; only the arm64 build was installed on a device.
