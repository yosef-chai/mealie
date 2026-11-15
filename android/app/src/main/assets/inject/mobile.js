/*
 * שכבת ההתאמה של אפליקציית Mealie לאנדרואיד.
 * מוזרק עם addDocumentStartJavaScript, כלומר רץ לפני שהממשק של Nuxt מצייר משהו.
 * מקבל את window.__mealieApp מהאפליקציה (ראה WebController.buildScript).
 * מתקשר עם האפליקציה רק דרך MealieNative (addWebMessageListener, מוגבל ל-origin של השרת).
 */
(function () {
  "use strict";
  if (window.__mealieAppInstalled) return;
  window.__mealieAppInstalled = true;

  var cfg = window.__mealieApp || {};
  var bridge = window.MealieNative;
  if (!bridge) return;

  function post(msg) {
    try { bridge.postMessage(typeof msg === "string" ? msg : JSON.stringify(msg)); } catch (e) { /* האפליקציה לא זמינה */ }
  }

  // ---- ערכת נושא: האפליקציה קובעת, לפני שהממשק נטען (בלי הבהוב) ----
  if (cfg.theme) {
    try { localStorage.setItem("vueuse-color-scheme", cfg.theme); } catch (e) { /* localStorage חסום */ }
  }

  // ---- סגנונות מובייל ----
  if (!cfg.raw && cfg.css) {
    var style = document.createElement("style");
    style.id = "mealie-app-style";
    style.textContent = cfg.css;
    (document.head || document.documentElement).appendChild(style);
  }

  // ---- נתיב: Nuxt מנווט ב-pushState, שלא מגיע ל-shouldOverrideUrlLoading ----
  var lastPath = null;
  function reportRoute() {
    var path = location.pathname + location.search;
    if (path === lastPath) return;
    lastPath = path;
    post({ type: "route", path: path });
    // הכותרת מופיעה או משתנה בין מסכים (למשל אחרי הכניסה), ולכן מחשבים שוב את הצבעים.
    setTimeout(reportTheme, 50);
    setTimeout(reportTheme, 500);
  }
  ["pushState", "replaceState"].forEach(function (name) {
    var original = history[name];
    history[name] = function () {
      var result = original.apply(this, arguments);
      setTimeout(reportRoute, 0);
      return result;
    };
  });
  window.addEventListener("popstate", reportRoute);
  window.addEventListener("hashchange", reportRoute);
  document.addEventListener("DOMContentLoaded", reportRoute);

  function vueRouter() {
    var root = document.querySelector("#__nuxt");
    var app = root && root.__vue_app__;
    return app && app.config && app.config.globalProperties && app.config.globalProperties.$router;
  }

  // ---- מוכנות: האפליקציה מחכה לרגע שה-router קיים ----
  var readyTries = 0;
  (function waitReady() {
    if (vueRouter()) { post({ type: "ready", router: true }); reportRoute(); reportTheme(); return; }
    if (++readyTries > 100) { post({ type: "ready", router: false }); return; }
    setTimeout(waitReady, 100);
  })();

  // ---- ערכת נושא בפועל (המשתמש יכול להחליף גם מתוך הממשק) ----
  var lastTheme = null;
  // הצבע של הכותרת יכול לשבת על אלמנט פנימי; לוקחים את הרקע האטום הראשון.
  function solidBackground(el) {
    if (!el) return null;
    var nodes = [el].concat(Array.prototype.slice.call(el.querySelectorAll("*"), 0, 20));
    for (var i = 0; i < nodes.length; i++) {
      var c = getComputedStyle(nodes[i]).backgroundColor;
      if (c && c !== "transparent" && !/rgba\([^)]*,\s*0\)$/.test(c)) return c;
    }
    return null;
  }
  function reportTheme() {
    var appEl = document.querySelector(".v-application");
    if (!appEl) return;
    var dark = appEl.classList.contains("v-theme--dark");
    var bar = solidBackground(document.querySelector("header"));
    var bg = getComputedStyle(appEl).backgroundColor;
    var key = dark + "|" + bar + "|" + bg;
    if (key === lastTheme) return;
    lastTheme = key;
    post({ type: "theme", dark: dark, bar: bar, background: bg });
  }
  var themeObserver = new MutationObserver(function () { reportTheme(); });
  (function watchTheme() {
    var appEl = document.querySelector(".v-application");
    if (!appEl) { setTimeout(watchTheme, 200); return; }
    themeObserver.observe(appEl, { attributes: true, attributeFilter: ["class"] });
    reportTheme();
  })();

  // ---- פקודות מהאפליקציה ----
  bridge.onmessage = function (event) {
    var msg;
    try { msg = JSON.parse(event.data); } catch (e) { return; }
    switch (msg.cmd) {
      case "navigate": {
        var router = vueRouter();
        if (!router) { post({ type: "nav", ok: false, path: msg.path, seq: msg.seq }); return; }
        router.push(msg.path).then(function () {
          post({ type: "nav", ok: true, path: msg.path, seq: msg.seq });
        }, function () {
          post({ type: "nav", ok: false, path: msg.path, seq: msg.seq });
        });
        break;
      }
      case "scrollTop": {
        var atTop = (window.scrollY || document.documentElement.scrollTop) < 4;
        if (!atTop) window.scrollTo({ top: 0, behavior: "smooth" });
        post({ type: "scrolled", wasAtTop: atTop, seq: msg.seq });
        break;
      }
      case "openDrawer": {
        var btn = document.querySelector("header button");
        if (btn) btn.click();
        post({ type: "drawer", ok: !!btn });
        break;
      }
      case "theme": {
        try { localStorage.setItem("vueuse-color-scheme", msg.value); } catch (e) { /* ignore */ }
        break;
      }
    }
  };

  // ---- Wake Lock: ה-WebView לא תמיד מממש אותו; "מסך דולק" במצב בישול עובר לאפליקציה ----
  if (!("wakeLock" in navigator)) {
    var held = 0;
    function Sentinel() {
      this.released = false;
      this.type = "screen";
      this.onrelease = null;
    }
    Sentinel.prototype.release = function () {
      if (this.released) return Promise.resolve();
      this.released = true;
      held = Math.max(0, held - 1);
      if (held === 0) post({ type: "wakeLock", on: false });
      if (typeof this.onrelease === "function") this.onrelease(new Event("release"));
      return Promise.resolve();
    };
    Sentinel.prototype.addEventListener = function (name, fn) { if (name === "release") this.onrelease = fn; };
    Sentinel.prototype.removeEventListener = function () { this.onrelease = null; };
    Object.defineProperty(navigator, "wakeLock", {
      configurable: true,
      value: {
        request: function () {
          held += 1;
          post({ type: "wakeLock", on: true });
          return Promise.resolve(new Sentinel());
        },
      },
    });
  }

  // ---- הורדות: Mealie יוצר blob ומבטל אותו מיד אחרי click(), ולכן לוכדים את ה-Blob עצמו ----
  var blobs = new Map();
  var originalCreate = URL.createObjectURL;
  URL.createObjectURL = function (obj) {
    var url = originalCreate.call(URL, obj);
    if (obj instanceof Blob) {
      blobs.set(url, obj);
      setTimeout(function () { blobs.delete(url); }, 60000);
    }
    return url;
  };

  var CHUNK = 2 * 1024 * 1024;
  var nextId = 1;

  function sendBlob(blob, name) {
    var id = nextId++;
    var total = Math.max(1, Math.ceil(blob.size / CHUNK));
    post({ type: "download-begin", id: id, name: name || "download", mime: blob.type || "application/octet-stream", size: blob.size, chunks: total, binary: !!cfg.arrayBuffer });
    var index = 0;
    function next() {
      if (index >= total) return;
      var part = blob.slice(index * CHUNK, Math.min(blob.size, (index + 1) * CHUNK));
      var i = index++;
      part.arrayBuffer().then(function (buf) {
        if (cfg.arrayBuffer) {
          // כותרת קבועה של 16 בתים: transferId, chunkIndex, totalChunks, flags (uint32, big-endian).
          var framed = new Uint8Array(16 + buf.byteLength);
          var view = new DataView(framed.buffer);
          view.setUint32(0, id);
          view.setUint32(4, i);
          view.setUint32(8, total);
          view.setUint32(12, 0);
          framed.set(new Uint8Array(buf), 16);
          bridge.postMessage(framed.buffer);
        } else {
          var bytes = new Uint8Array(buf);
          var bin = "";
          for (var k = 0; k < bytes.length; k += 0x8000) {
            bin += String.fromCharCode.apply(null, bytes.subarray(k, k + 0x8000));
          }
          post({ type: "download-chunk", id: id, index: i, total: total, data: btoa(bin) });
        }
        next();
      }, function () {
        post({ type: "download-error", id: id });
      });
    }
    next();
  }

  function handleAnchor(a) {
    var href = a.href || "";
    var name = a.getAttribute("download") || "";
    if (href.indexOf("blob:") === 0) {
      var blob = blobs.get(href);
      if (blob) { sendBlob(blob, name); return true; }
      return false;
    }
    if (href.indexOf("data:") === 0) {
      fetch(href).then(function (r) { return r.blob(); }).then(function (b) { sendBlob(b, name); });
      return true;
    }
    if (a.hasAttribute("download") && /^https?:/.test(href)) {
      post({ type: "download-url", url: href, name: name });
      return true;
    }
    return false;
  }

  var originalClick = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function () {
    if (handleAnchor(this)) return;
    return originalClick.apply(this, arguments);
  };
  document.addEventListener("click", function (e) {
    var a = e.target && e.target.closest ? e.target.closest("a[href]") : null;
    if (a && (a.hasAttribute("download") || a.href.indexOf("blob:") === 0) && handleAnchor(a)) {
      e.preventDefault();
      e.stopPropagation();
    }
  }, true);
})();
