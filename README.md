# Quant OS — Android shell

Buildable Android project for the Quant OS WebView wrapper. The OS itself is
`app/src/main/assets/quant-os.html` (the 1.9.6 single-file build); the Java in
`app/src/main/java/dev/quant/os/` only provides the shell: a WebView on a
virtual https origin, a native page surface for the OS browser app, an identity
store, an ad/tracking blocklist, a full in-app browser activity, and a
notification-shade internet switch.

Code package `dev.quant.os`; the install ID (applicationId) stays
`dev.quartz.os` so a phone that already has the app updates in place instead of
installing a second copy. versionName `1.9.6`, versionCode `196`, minSdk 23,
target/compile SDK 34.

## Build

Requirements: JDK 17+ and Android SDK platform 34 + build-tools 34.0.0.
Point the SDK either with `ANDROID_HOME` or a `local.properties` containing
`sdk.dir=/path/to/sdk` (not shipped).

```
./gradlew assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk`, signed v1+v2+v3 when
release signing material is present; otherwise `app-release-unsigned.apk`.

Debug build with no signing setup: `./gradlew assembleDebug`.

## Signing

The release keystore and `signing.properties` are deliberately kept out of
version control (see `.gitignore`): whoever holds both can sign an update that
Android accepts as this app. Without them a release build is simply unsigned.

The shipped app is signed with a key generated for this project (the original
1.x key was lost), so it is a new package identity — Android refuses to
install it over an older Quant build signed by the old key; uninstall that
copy first. Keep your keystore safe: losing it means no future update can
install over the app. Contributors can use their own key; a debug-signed or
differently signed APK needs the old copy uninstalled before installing.

## Layout

```
app/src/main/AndroidManifest.xml          package, permissions, both activities
app/src/main/java/dev/quant/os/MainActivity.java     shell, asset origin, identity store
app/src/main/java/dev/quant/os/NativeSurface.java    native WebView surface for the OS browser app
app/src/main/java/dev/quant/os/BrowserActivity.java  full in-app browser (quant-browse: links)
app/src/main/java/dev/quant/os/AdBlock.java          static host blocklist
app/src/main/java/dev/quant/os/Handoff.java          quant-open:/quant-browse: URL parsing
app/src/main/java/dev/quant/os/TorEngine.java        runs libtor, control port, exit probe
app/src/main/java/dev/quant/os/WebViewProxy.java     points every WebView at tor's SOCKS port
app/src/main/java/dev/quant/os/TorNotification.java  ongoing shade notification that switches Tor
app/src/main/java/org/torproject/jni/TorService.java  JNI holder for libtor (fixed package name)
app/src/main/jniLibs/                     libtor.so, arm64-v8a and x86_64
app/src/main/assets/quant-os.html        the OS bundle
app/src/main/assets/harden.js            document-start page hardening (see Behaviour notes)
app/src/main/res/                         launcher icons, theme, boot colour, app name
app/src/test/java/dev/quant/os/AdBlockTest.java      JVM unit test for the blocklist
docs/BUILD-VERIFICATION.md                what was actually built and checked here
```

## Behaviour notes

- The page is served from `https://appassets.androidplatform.net` by a client
  that reads `assets/` directly, so localStorage, secure-context APIs and
  service workers behave as on the web with no network connection.
- `assets/quant-os.html` at 1.9.6 has md5 `9829074bd28c4925309c5223e361855d`.
  Since 1.9.1 this file is the APK build, not a byte copy of the web edition:
  the APK replaces the `__QZ_TOKEN__` marker with a random token on every
  launch. Drop the same file on a web server and the bridge stays dormant.
- Native bridge: the page calls `window.QuantNative`, which forwards to the
  Java object `__QzNative` with the launch token as the first argument. Every
  Java method checks the token, so a third-party iframe can reach the object
  but not the token, and its calls are ignored.
- Files: the OS Save/Download buttons write to the public Downloads folder
  through MediaStore on Android 10+. Below Android 10 they write to the app's
  own external Downloads folder, which needs no storage permission.
- Camera and microphone: a site gets the camera only after an Android dialog.
  Remembered choices are per site, and only the top-level site can ask.
- Browser rendering: the Web app's chrome carries a Desktop | Mobile switch
  (APK only). Desktop uses the profile persona's desktop UA with a wide
  viewport; Mobile uses the profile's mobile persona UA with a device-width
  viewport and reloads the page. The choice is remembered across launches.
- Cookies: the OS identity settings are applied to both the shell and the
  in-app browser — session-only, persistent, or blocked per site.
- WebRTC, `navigator.mediaDevices.enumerateDevices`, `performance.memory`,
  the real time zone (sites get UTC via `Intl` and `Date`), canvas pixels
  (±1 noise on reads and exports) and rendered audio (±1e-7 noise on buffer
  reads) are neutralised by `assets/harden.js`, injected at document start
  into every page and frame the app opens. WebRTC and the device list are also
  stripped from child windows a page can reach through `iframe.contentWindow`:
  a script-less sandboxed frame keeps its own copy of the constructor, and
  leak tests (browserleaks.com/webrtc) borrow it from there. A page that
  appends a frame and reads `windows[i]` in the same synchronous task can
  still beat the sweep — Chromium has no coarser switch. The OS page itself
  skips that script: its clock, alarms and
  calendar keep the real zone. Trade-offs: video calls inside Quant will not
  work; sites show UTC times; saved canvas images and audio differ by amounts
  no one can see or hear. Camera/microphone capture itself still works, behind
  the Android consent dialog.
- Notification shortcut (1.9.6): while the app is running, an ongoing
  notification "Quant OS internet" shows whether Tor (and so internet) is on,
  with one button, "Turn internet on" / "Turn internet off". The button opens
  the app and switches Tor through the OS page's own start/stop, so the title
  bar, Network app and Identity card refresh. The text tracks the real state
  every two seconds ("Tor is starting (N%)" included). On Android 13+ the app
  asks once for notification permission; if denied, the shortcut is absent and
  nothing else changes. The notification is cancelled when the app is closed
  (tor dies with it, so there is nothing left to switch).
- Tor: the APK bundles libtor 0.4.9.17 (`jniLibs/`, arm64-v8a; add
  `-PwithX86` to include x86_64 for emulators) and starts it in-process;
  `TorEngine` points every WebView in the app at
  tor's SOCKS port through `ProxyController`, so page loads, fetches and the
  Identity probe leave through a Tor exit. There is no VPN service: apps
  outside this one are not routed. If Tor stops, the proxy is cleared; if tor
  dies, pages fail rather than fall back to the direct connection.
  Since 1.9.4 the fail-closed gate is back (1.9.3 briefly let pages use the
  normal connection): while Tor is off or starting, every http(s) navigation
  and subresource is blocked in the shell and both browser surfaces (a "Tor is
  not running" page instead of the site), so no page can ever see the phone's
  real address. Since 1.9.5 the OS treats Tor-off as a real outage: the title
  bar and Network app show Offline ("Tor is off · internet off"), and Test,
  DNS lookup, Ping, Speed test, curl/wget, Weather, Maps, the reader and the
  agent network tools refuse with one message ("internet is off while Tor is
  off") instead of attempting a request. The Network and Identity apps show
  a per-identity virtual egress address (deterministic from the identity
  seed, re-rolled on rotate/burn), labelled as a placeholder. Downloads are
  always paused because `DownloadManager` cannot use the proxy. No GeoIP
  files are bundled, so the card shows no exit country.
  Tor identity (1.9.1-qtest, 2026-11-22): start → bootstrap → check.torproject.org
  reported IsTor=true through the probe and through the WebView itself.
- `BrowserActivity` and `AdBlock` were reconstructed from the 1.3.1 APK
  bytecode because those two source files were not in the surviving source
  snapshot. Their behaviour (toolbar, ad-block interception, downloads, file
  and camera hand-offs, immersive mode) is that of the shipped app; decompiler
  artifacts were corrected and the blocklist was verified host-by-host against
  the original 139 entries.

## Verification performed

See `docs/BUILD-VERIFICATION.md`. Short version: the blocklist unit test passed
(5/5), the 1.9.6 APK built (4,502,322 B — arm64-only, libraries and the HTML
bundle compressed inside the APK, signed v1+v2+v3, zip-aligned, versionCode 196
/ versionName 1.9.6) and is copied to `dist/Quant-OS-1.9.6.apk` (md5
`00e99d06c9df7d2001dd5b3969333495`). It was also
installed on a real phone as a side-by-side test copy: the desktop boots, sites
render inside the OS window, the camera consent dialog works for both Allow and
Deny, the Editor's download wrote a file to the phone's Downloads folder, and
browserleaks.com/webrtc reported RTCPeerConnection False, no local or public IP,
"No Leak" — including the hidden-frame trick the site uses to borrow the
constructor. The full-screen in-app browser and the file picker were not
exercised. The 1.9.6 qtest build showed the ongoing "Quant OS internet"
notification in the shade reading "Internet is off (Tor is off)" with the
action "Turn internet on"; the action's exact intent (the activity with
`torToggle=true`) turned Tor on — notification "Internet is on through Tor",
title bar WIFI, Identity card verified exit `204.8.96.158` — and toggling again
returned it to off. The physical tap on the action bubble itself was not
reliably exercised because a live call kept changing the shade layout; the
intent behind the button was used instead. The 1.9.5 qtest build showed, with
Tor off, the title bar and System
card reading OFFLINE / "Tor is off · internet off", the Network app hero reading
"Offline" with the per-identity placeholder `6.85.92.70` (Location, Provider and
Latency empty, Last check "never"), the Test button answering with "internet is
off while Tor is off — turn Tor on in the Identity app" and Ping refusing with
the same message. With Tor started, the header returned to WIFI, the Network app
measured `45.66.35.22` (a Tor exit, SABOTAGE LLC, Amsterdam, 1798 ms) and the
SOCKS probe verified. The 1.9.4 qtest build had also shown, with Tor off, that
the Web app's page surface and the full-screen browser both stay on the "Tor is
not running — Pages stay closed while Tor is off" page instead of the site (a
typed example.com kept its address in the Web app's bar). With Tor on,
`check.torproject.org` loaded in the Web app answered "This browser is
configured to use Tor" with "Your IP address appears to be: 109.70.100.1" — the
same exit the probe reported, so page traffic leaves through Tor. The held-off
tab reloaded into the real page by itself once Tor came on.
The 1.9.2 run of the same gate also survived an
app restart, and rotating the identity re-rolled the placeholder from
`8.70.9.31` to `161.203.79.12` (`anon rotate`).

Header tells closed in 1.9.1: `Accept-Language` is now `en-US,en;q=0.9`
(app locale and system locale list pinned to US English) and client hints
(`sec-ch-ua`, `sec-ch-ua-platform`, `navigator.userAgentData`) follow the
spoofed persona instead of saying "Android WebView". `X-Requested-With` still
carries the package name: WebView 158 on the test phone has no API to turn it
off (its `REQUESTED_WITH_HEADER_ALLOW_LIST` feature is absent); the release
build sends `dev.quartz.os`. A Firefox or Safari persona cannot fully hide
`sec-ch-ua` either — the engine always stamps a Chrome brand list, so those
personas send Chrome hints next to their Firefox/Safari UA.

## License

The project is MIT-licensed; see `LICENSE`. Bundled third-party parts keep
their own licenses:

- libtor 0.4.9.17 (`jniLibs/libtor.so`) — BSD-3-Clause, The Tor Project.
- `org.torproject.jni.TorService` (JNI holder) — follows libtor's BSD-3-Clause.
- androidx.webkit 1.13.0 — Apache-2.0, The Android Open Source Project.
- JUnit 4.13.2 (unit tests only) — Eclipse Public License 1.0.

