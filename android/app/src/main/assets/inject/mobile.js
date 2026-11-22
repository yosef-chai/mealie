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

  // בתחילת המסמך ייתכן שעדיין אין <html>; מחכים לו בלי לעכב את שאר הסקריפט
  function whenRoot(fn) {
    if (document.documentElement) { fn(document.documentElement); return; }
    var mo = new MutationObserver(function () {
      if (!document.documentElement) return;
      mo.disconnect();
      fn(document.documentElement);
    });
    mo.observe(document, { childList: true });
  }

  // ---- סגנונות מובייל ----
  if (!cfg.raw && cfg.css) {
    var style = document.createElement("style");
    style.id = "mealie-app-style";
    style.textContent = cfg.css;
    whenRoot(function (root) { (document.head || root).appendChild(style); });
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

  // ---- משיכה לרענון: מותרת רק כשהדף באמת בראשו ----
  // ה-WebView יודע רק על גלילת המסמך. דיאלוג של Vuetify נועל את המסמך (position: fixed, גלילה 0),
  // אז בלי הדיווח הזה כל משיכה קטנה בתוך דיאלוג או רשימה גלולה מרעננת את הדף ומוחקת את מה שהוקלד.
  var scrolledInside = new Set();
  var lastPull = null;
  var pullFrame = 0;

  function pullAllowed(target) {
    var root = document.documentElement;
    if (!root) return true;
    if ((window.scrollY || root.scrollTop) > 0) return false;
    if (root.classList.contains("v-overlay-scroll-blocked")) return false;
    if (document.querySelector(".v-dialog.v-overlay--active, .v-navigation-drawer--temporary.v-navigation-drawer--active")) return false;
    if (target && target.closest && target.closest(".v-overlay__content")) return false;
    var blocked = false;
    scrolledInside.forEach(function (el) {
      if (!el.isConnected || el.scrollTop <= 0) { scrolledInside.delete(el); return; }
      // בלי נקודת מגע לא יודעים אם האצבע על התוכן הגלול; עדיף לחסום מאשר לרענן בטעות
      if (!target || el.contains(target)) blocked = true;
    });
    return !blocked;
  }

  function reportPull(target) {
    var allowed = pullAllowed(target);
    if (allowed === lastPull) return;
    lastPull = allowed;
    post({ type: "pull", allowed: allowed });
  }

  function schedulePull() {
    if (pullFrame) return;
    pullFrame = requestAnimationFrame(function () { pullFrame = 0; reportPull(null); });
  }

  // scroll לא מבעבע, אבל נתפס בשלב ה-capture גם מאלמנטים פנימיים
  document.addEventListener("scroll", function (e) {
    var el = e.target;
    if (el && el.nodeType === 1 && el.scrollTop > 0) scrolledInside.add(el);
    schedulePull();
  }, { capture: true, passive: true });
  document.addEventListener("touchstart", function (e) { reportPull(e.target); }, { capture: true, passive: true });
  whenRoot(function (root) {
    // Vuetify מוסיף ל-<html> מחלקה כשדיאלוג נועל את הגלילה
    new MutationObserver(schedulePull).observe(root, { attributes: true, attributeFilter: ["class"] });
  });
  window.addEventListener("pageshow", function () { lastPull = null; schedulePull(); });

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

  // ---- טיימרים: זמן בישול בשלב הכנה ("אופים 25 דקות") הופך לכפתור שמפעיל טיימר בשעון של הטלפון ----
  if (!cfg.raw && cfg.timers && window.MealieTimers) installTimers();

  // ---- תוספות ממשק: חיצי מנות, צבע תווית ברשימת קניות, תרגום חסר ----
  if (!cfg.raw) installEnhancements();

  function installTimers() {
    var finder = window.MealieTimers;
    // טקסט השלב (Markdown) ושורת הסיכום עם משך השלב (durationSeconds מ-Mealie 3.30)
    var STEP_TEXT = ".list-group-item .markdown, .list-group-item .summary-wrapper";
    var SKIP_TAGS = /^(A|BUTTON|CODE|PRE|TEXTAREA|INPUT|SELECT|SCRIPT|STYLE)$/;
    var scheduled = false;

    function isRecipePage() {
      return /\/r\/(?!create(?:\/|$))[^/]+\/?$/.test(location.pathname);
    }
    function recipeName() {
      // Mealie קובע את כותרת הדף לשם המתכון (useSeoMeta); בדף אין h1 עם השם
      var t = (document.title || "").split(" | ")[0].trim();
      if (!t || /^mealie$/i.test(t)) t = "";
      return t.slice(0, 80);
    }
    function stepNumber(el) {
      var item = el.closest(".list-group-item");
      if (!item || !item.parentElement) return null;
      var items = Array.prototype.filter.call(item.parentElement.children, function (c) { return c.classList.contains("list-group-item"); });
      var i = items.indexOf(item);
      return i >= 0 ? i + 1 : null;
    }
    function label(seconds) {
      var h = Math.floor(seconds / 3600), m = Math.floor((seconds % 3600) / 60), s = seconds % 60;
      return (h ? h + ":" + String(m).padStart(2, "0") : m) + (s ? ":" + String(s).padStart(2, "0") : "");
    }

    function decorate(container) {
      if (container.closest("[contenteditable]") || container.querySelector("textarea,input")) return;
      var walker = document.createTreeWalker(container, NodeFilter.SHOW_TEXT, {
        acceptNode: function (n) {
          for (var p = n.parentElement; p && p !== container; p = p.parentElement) {
            if (SKIP_TAGS.test(p.tagName) || p.classList.contains("mealie-timer")) return NodeFilter.FILTER_REJECT;
          }
          return n.nodeValue && /\d|[֐-׿؀-ۿ]|half|hour|minute/i.test(n.nodeValue) ? NodeFilter.FILTER_ACCEPT : NodeFilter.FILTER_SKIP;
        },
      });
      var nodes = [];
      while (walker.nextNode()) nodes.push(walker.currentNode);
      nodes.forEach(function (node) {
        var text = node.nodeValue;
        var found = finder.find(text);
        if (!found.length) return;
        var frag = document.createDocumentFragment();
        var at = 0;
        found.forEach(function (f) {
          if (f.start > at) frag.appendChild(document.createTextNode(text.slice(at, f.start)));
          var b = document.createElement("button");
          b.type = "button";
          b.className = "mealie-timer";
          b.dataset.min = String(f.min);
          b.dataset.max = String(f.max);
          b.textContent = text.slice(f.start, f.end);
          b.setAttribute("aria-label", "⏱ " + b.textContent);
          b.title = f.min === f.max ? label(f.min) : label(f.min) + "–" + label(f.max);
          frag.appendChild(b);
          at = f.end;
        });
        if (at < text.length) frag.appendChild(document.createTextNode(text.slice(at)));
        node.parentNode.replaceChild(frag, node);
      });
    }

    function scan() {
      scheduled = false;
      if (!isRecipePage()) return;
      document.querySelectorAll(STEP_TEXT).forEach(function (el) {
        // Vue מחליף את ה-HTML של השלב כשהטקסט משתנה; מסמנים לפי התוכן כדי לעבד שוב רק כשצריך
        var sig = el.textContent.length + ":" + el.childElementCount;
        if (el.dataset.mealieTimers === sig) return;
        decorate(el);
        el.dataset.mealieTimers = el.textContent.length + ":" + el.childElementCount;
      });
    }
    function schedule() {
      if (scheduled) return;
      scheduled = true;
      setTimeout(scan, 250);
    }

    var observer = new MutationObserver(function (records) {
      // שינויים שלנו (הכפתורים עצמם) לא מפעילים סריקה חוזרת
      for (var i = 0; i < records.length; i++) {
        var t = records[i].target;
        if (!(t.classList && t.classList.contains("mealie-timer"))) { schedule(); return; }
      }
    });
    whenRoot(function (root) { observer.observe(root, { childList: true, subtree: true, characterData: true }); });
    window.addEventListener("popstate", schedule);
    document.addEventListener("DOMContentLoaded", schedule);

    // לחיצה: בלי לסמן את השלב כבוצע (לחיצה על כרטיס השלב ב-Mealie מסמנת אותו)
    document.addEventListener("click", function (e) {
      var b = e.target && e.target.closest ? e.target.closest(".mealie-timer") : null;
      if (!b) return;
      e.preventDefault();
      e.stopPropagation();
      post({ type: "timer", min: +b.dataset.min, max: +b.dataset.max, recipe: recipeName(), step: stepNumber(b) });
    }, true);
  }

  function installEnhancements() {
    var scheduled = false;
    // מחרוזות ש-Mealie עדיין לא תרגם; התרגום ב-he-IL.json מגיע רק כשהשרת מתעדכן
    var MISSING_HE = { Days: "ימים", Weeks: "שבועות" };

    // השפה שנבחרה ב-Mealie (העוגייה של nuxt-i18n), ולא שפת הטלפון; ל-<html> אין lang
    function hebrew() {
      var cookie = /(?:^|;\s*)i18n_redirected=([^;]+)/.exec(document.cookie);
      var locale = (cookie && decodeURIComponent(cookie[1])) || document.documentElement.lang || navigator.language || "";
      return /^he(-|$)/i.test(locale);
    }
    function words() {
      return hebrew()
        ? { fewer: "פחות מנות", more: "יותר מנות" }
        : { fewer: "Fewer servings", more: "More servings" };
    }
    function isDisabled(btn) {
      return btn.disabled || btn.classList.contains("v-btn--disabled");
    }

    // שורת המנות של Mealie (RecipeScaleEditButton): כרטיס שפותח תפריט, ואחריו כפתורי − ו-+
    function servingRow(row) {
      var card = row.querySelector(":scope > div > .v-card[aria-haspopup]");
      var buttons = row.querySelectorAll(":scope > .v-item-group > .v-btn");
      return card && buttons.length === 2 ? { card: card, dec: buttons[0], inc: buttons[1] } : null;
    }
    function stepperFor(menuId) {
      var card = document.querySelector('[aria-controls="' + CSS.escape(menuId) + '"]');
      var row = card && card.closest("[data-mealie-stepper]");
      return row ? servingRow(row) : null;
    }

    function popButton(kind, label, menuId) {
      var b = document.createElement("button");
      b.type = "button";
      b.className = "mealie-pop-step " + kind;
      b.setAttribute("aria-label", label);
      // לוחצים על הכפתור של Mealie עצמו, כך שהחישוב והגבולות שלו נשמרים.
      // Vuetify לא סוגר את החלון על click() מהקוד: הוא סוגר רק כשהלחיצה התחילה מחוץ לחלון.
      b.addEventListener("click", function () {
        var s = stepperFor(menuId);
        var target = s && (kind === "dec" ? s.dec : s.inc);
        if (target && !isDisabled(target)) target.click();
        schedule();
      });
      return b;
    }

    function enhancePopup(menuId, s, w) {
      var menu = document.getElementById(menuId);
      var input = menu && menu.querySelector(".v-number-input");
      var line = input && input.parentElement;
      if (!line) return;
      var card = line.closest(".v-card");
      if (card && !card.hasAttribute("data-mealie-servings")) card.setAttribute("data-mealie-servings", "");
      var dec = line.querySelector(":scope > .mealie-pop-step.dec");
      if (!dec) {
        dec = popButton("dec", w.fewer, menuId);
        // בסוף השורה, אחרי הצמתים של Vue, כדי לא להפריע לעדכונים שלו; הסדר על המסך נקבע ב-CSS
        line.appendChild(dec);
        line.appendChild(popButton("inc", w.more, menuId));
      }
      dec.disabled = isDisabled(s.dec);
    }

    function enhanceServings() {
      var w = words();
      document.querySelectorAll(".text-center.d-flex.align-center").forEach(function (row) {
        var s = servingRow(row);
        if (!s) return;
        if (!row.hasAttribute("data-mealie-stepper")) row.setAttribute("data-mealie-stepper", "");
        if (s.dec.getAttribute("aria-label") !== w.fewer) s.dec.setAttribute("aria-label", w.fewer);
        if (s.inc.getAttribute("aria-label") !== w.more) s.inc.setAttribute("aria-label", w.more);
        var menuId = s.card.getAttribute("aria-controls");
        if (menuId) enhancePopup(menuId, s, w);
      });
    }

    function translateMissing() {
      if (!hebrew()) return;
      document.querySelectorAll(".range-nav ~ .v-btn-toggle .v-btn__content").forEach(function (el) {
        var walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT);
        while (walker.nextNode()) {
          var node = walker.currentNode;
          var text = node.nodeValue.trim();
          if (MISSING_HE[text]) node.nodeValue = node.nodeValue.replace(text, MISSING_HE[text]);
        }
      });
    }

    // רשימת קניות: צבע התווית יושב כרקע inline על כותרת הקבוצה; ה-CSS מציג אותו כנקודה
    function labelColors() {
      document.querySelectorAll(".shopping-list-section > .v-expansion-panel-title").forEach(function (title) {
        var color = title.style.backgroundColor;
        if (color && title.style.getPropertyValue("--mealie-label") !== color) title.style.setProperty("--mealie-label", color);
      });
    }

    function run() {
      scheduled = false;
      [enhanceServings, translateMissing, labelColors].forEach(function (fn) {
        try { fn(); } catch (e) { /* שינוי במבנה של Mealie לא שובר את שאר התוספות */ }
      });
    }
    function schedule() {
      if (scheduled) return;
      scheduled = true;
      setTimeout(run, 80);
    }

    var observer = new MutationObserver(function (records) {
      for (var i = 0; i < records.length; i++) {
        var t = records[i].target;
        if (!(t.classList && t.classList.contains("mealie-pop-step"))) { schedule(); return; }
      }
    });
    // disabled: כפתור ה-− של Mealie מושבת במנה אחת, והחץ בחלון צריך להשבת איתו
    whenRoot(function (root) {
      observer.observe(root, { childList: true, subtree: true, characterData: true, attributes: true, attributeFilter: ["disabled"] });
    });
    document.addEventListener("DOMContentLoaded", schedule);
  }
})();
