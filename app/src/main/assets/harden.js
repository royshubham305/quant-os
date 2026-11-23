/* Document-start hardening, injected by the shell into every WebView it
   opens for web content and into this OS page's frames. The OS page itself
   keeps the real clock (tiles, alarms, calendar), so it skips its own
   top-level document below. */
(function () {
  try {
    if (location.host === 'appassets.androidplatform.net' &&
        location.pathname === '/assets/quant-os.html') return;
  } catch (e) {}

  /* WebRTC exposes the real LAN/public address through ICE, and
     enumerateDevices lists hardware without a prompt. getUserMedia stays. */
  try {
    Object.defineProperty(window, 'RTCPeerConnection',
      { value: undefined, configurable: false, writable: false });
    Object.defineProperty(window, 'webkitRTCPeerConnection',
      { value: undefined, configurable: false, writable: false });
  } catch (e) {}
  try {
    var md = navigator.mediaDevices;
    if (md && md.enumerateDevices) {
      Object.defineProperty(md, 'enumerateDevices',
        { value: function () { return Promise.resolve([]); }, configurable: false, writable: false });
    }
  } catch (e) {}

  /* A sandboxed frame with no src (browserleaks.com/webrtc uses one) runs no
     scripts, so this injection never reaches it, yet its parent can still
     borrow the real RTCPeerConnection through contentWindow. Strip any child
     window a page can reach. shortcut: a frame appended and read through
     window[i] in the same synchronous task can still win the race. */
  var killRtc = function (w) {
    try {
      if (!w) return;
      if (w.RTCPeerConnection) {
        Object.defineProperty(w, 'RTCPeerConnection',
          { value: undefined, configurable: false, writable: false });
        Object.defineProperty(w, 'webkitRTCPeerConnection',
          { value: undefined, configurable: false, writable: false });
      }
      var md = w.navigator && w.navigator.mediaDevices;
      if (md && md.enumerateDevices) {
        Object.defineProperty(md, 'enumerateDevices',
          { value: function () { return Promise.resolve([]); }, configurable: false, writable: false });
      }
    } catch (e) {}
  };
  try {
    var cw = Object.getOwnPropertyDescriptor(HTMLIFrameElement.prototype, 'contentWindow');
    if (cw && cw.get) {
      Object.defineProperty(HTMLIFrameElement.prototype, 'contentWindow', {
        configurable: true, enumerable: cw.enumerable,
        get: function () { var w = cw.get.call(this); killRtc(w); return w; }
      });
    }
    var cd = Object.getOwnPropertyDescriptor(HTMLIFrameElement.prototype, 'contentDocument');
    if (cd && cd.get) {
      Object.defineProperty(HTMLIFrameElement.prototype, 'contentDocument', {
        configurable: true, enumerable: cd.enumerable,
        get: function () {
          var d = cd.get.call(this);
          try { killRtc(d && d.defaultView); } catch (e) {}
          return d;
        }
      });
    }
  } catch (e) {}
  try {
    new MutationObserver(function (ms) {
      for (var i = 0; i < ms.length; i++) {
        var ns = ms[i].addedNodes;
        for (var j = 0; j < ns.length; j++) {
          var n = ns[j];
          if (n && (n.tagName === 'IFRAME' || n.tagName === 'FRAME')) {
            try { killRtc(n.contentWindow); } catch (e) {}
          }
        }
      }
    }).observe(document, { childList: true, subtree: true });
  } catch (e) {}

  /* Chromium-only heap numbers are a weak but free signal, and the Firefox
     persona should not expose a Chromium API at all. */
  try {
    delete Performance.prototype.memory;
    if (performance.memory !== undefined) {
      Object.defineProperty(performance, 'memory', { value: undefined, configurable: true });
    }
  } catch (e) {}

  /* Time: present UTC everywhere so Intl, Date getters and toLocale* strings
     agree with each other and no real zone is readable. */
  try {
    var DTF = Intl.DateTimeFormat;
    var F = function (locales, options) {
      if (!options) options = {};
      if (!options.timeZone) {
        var o = {};
        for (var k in options) o[k] = options[k];
        o.timeZone = 'UTC';
        options = o;
      }
      return new DTF(locales, options);
    };
    F.prototype = DTF.prototype;
    F.supportedLocalesOf = DTF.supportedLocalesOf;
    Intl.DateTimeFormat = F;
  } catch (e) {}
  try {
    var utcOpts = function (options) {
      if (!options) options = {};
      if (options.timeZone) return options;
      var o = {};
      for (var k in options) o[k] = options[k];
      o.timeZone = 'UTC';
      return o;
    };
    ['toLocaleString', 'toLocaleDateString', 'toLocaleTimeString'].forEach(function (name) {
      var orig = Date.prototype[name];
      Object.defineProperty(Date.prototype, name, {
        configurable: true, writable: true,
        value: function (locales, options) { return orig.call(this, locales, utcOpts(options)); }
      });
    });
    ['getFullYear', 'getMonth', 'getDate', 'getDay', 'getHours', 'getMinutes', 'getSeconds'].forEach(function (name) {
      var utc = 'getUTC' + name.slice(3);
      Object.defineProperty(Date.prototype, name, {
        configurable: true, writable: true,
        value: function () { return this[utc](); }
      });
    });
    Object.defineProperty(Date.prototype, 'getYear', {
      configurable: true, writable: true,
      value: function () { return this.getUTCFullYear() - 1900; }
    });
    Object.defineProperty(Date.prototype, 'getTimezoneOffset', {
      configurable: true, writable: true, value: function () { return 0; }
    });
    Object.defineProperty(Date.prototype, 'toString', {
      configurable: true, writable: true, value: function () { return this.toUTCString(); }
    });
    Object.defineProperty(Date.prototype, 'toTimeString', {
      configurable: true, writable: true, value: function () { return this.toUTCString().slice(17); }
    });
    Object.defineProperty(Date.prototype, 'toDateString', {
      configurable: true, writable: true, value: function () { return this.toUTCString().slice(0, 16); }
    });
  } catch (e) {}

  /* Canvas: ±1 noise on pixel readback and image exports. On-screen pixels
     are untouched, and saved images lose nothing a human can see. */
  try {
    var seed = (Math.random() * 0xffffffff) >>> 0 || 1;
    var rnd = function () { seed ^= seed << 13; seed ^= seed >>> 17; seed ^= seed << 5; return seed >>> 0; };
    var sprinkle = function (d) {
      for (var i = 0; i < d.length; i += 4) {
        if ((rnd() & 31) === 0) {
          for (var c = 0; c < 3; c++) {
            var v = d[i + c] + ((rnd() & 1) ? 1 : -1);
            d[i + c] = v < 0 ? 0 : v > 255 ? 255 : v;
          }
        }
      }
    };
    var gid = CanvasRenderingContext2D.prototype.getImageData;
    Object.defineProperty(CanvasRenderingContext2D.prototype, 'getImageData', {
      configurable: true, writable: true,
      value: function () {
        var img = gid.apply(this, arguments);
        try { sprinkle(img.data); } catch (e) {}
        return img;
      }
    });
    var noisy = function (c) {
      try {
        if (!c.width || !c.height) return c;
        var t = document.createElement('canvas');
        t.width = c.width;
        t.height = c.height;
        var g = t.getContext('2d');
        if (!g) return c;
        g.drawImage(c, 0, 0);
        var img = gid.call(g, 0, 0, t.width, t.height);
        sprinkle(img.data);
        g.putImageData(img, 0, 0);
        return t;
      } catch (e) { return c; }
    };
    var durl = HTMLCanvasElement.prototype.toDataURL;
    Object.defineProperty(HTMLCanvasElement.prototype, 'toDataURL', {
      configurable: true, writable: true,
      value: function () { return durl.apply(noisy(this), arguments); }
    });
    if (HTMLCanvasElement.prototype.toBlob) {
      var tblob = HTMLCanvasElement.prototype.toBlob;
      Object.defineProperty(HTMLCanvasElement.prototype, 'toBlob', {
        configurable: true, writable: true,
        value: function () { return tblob.apply(noisy(this), arguments); }
      });
    }
  } catch (e) {}

  /* Audio: ±1e-7 (-140 dB, inaudible) reshuffle of buffer reads, so the
     rendered waveform no longer hashes to a stable device fingerprint. */
  try {
    if (window.AudioBuffer && AudioBuffer.prototype.getChannelData) {
      var gcd = AudioBuffer.prototype.getChannelData;
      Object.defineProperty(AudioBuffer.prototype, 'getChannelData', {
        configurable: true, writable: true,
        value: function () {
          var a = gcd.apply(this, arguments);
          try { for (var i = 0; i < a.length; i++) a[i] += (Math.random() - 0.5) * 2e-7; } catch (e) {}
          return a;
        }
      });
    }
  } catch (e) {}
})();
