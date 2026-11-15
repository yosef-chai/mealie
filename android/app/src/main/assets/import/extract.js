/*
 * חילוץ מתכון מדף אינטרנט, בתוך דפדפן אמיתי (WebView נסתר באפליקציה, או Chromium בבדיקות).
 * מחזיר JSON עם schema.org Recipe מלא ככל האפשר, ורשימת מקטעים (כותרות כמו "לבצק:") לשחזור אחרי היבוא.
 *
 * סדר העדיפויות:
 *   1. JSON-LD של Recipe (גם בתוך @graph, mainEntity, מערכים)
 *   2. Microdata (itemtype=schema.org/Recipe)
 *   3. תוספי מתכונים נפוצים ב-WordPress (WPRM, Tasty, Mediavine Create, Zip, EasyRecipe)
 *   4. מבנה הדף: כותרות "מצרכים" / "אופן ההכנה" בכל השפות, והרשימות שאחריהן
 * שדה שחסר במקור אחד נלקח מהבא אחריו; ההוראות מפוצלות לשלבים אם הגיעו כגוש אחד.
 */
(function () {
  "use strict";

  // ---- עזרים ----
  function clean(s) {
    return String(s == null ? "" : s)
      .replace(/<[^>]*>/g, " ")
      .replace(/&nbsp;| /g, " ")
      .replace(/&amp;/g, "&").replace(/&quot;/g, "\"").replace(/&#39;|&apos;/g, "'").replace(/&lt;/g, "<").replace(/&gt;/g, ">")
      .replace(/[‎‏‪-‮]/g, "")
      .replace(/[ \t\r\f\v]+/g, " ")
      .replace(/ *\n */g, "\n")
      .trim();
  }
  function oneLine(s) { return clean(s).replace(/\n+/g, " ").trim(); }
  function types(o) {
    var t = o && o["@type"];
    return (Array.isArray(t) ? t : [t]).map(function (x) { return String(x || "").replace(/^.*[/#]/, ""); });
  }
  function isType(o, name) { return types(o).indexOf(name) >= 0; }
  function arr(v) { return v == null ? [] : Array.isArray(v) ? v : [v]; }
  // יחסית ל-<base href> של הדף, כמו שהדפדפן עצמו מפרש קישורים
  function abs(u) { try { return u ? new URL(u, document.baseURI || location.href).href : null; } catch (e) { return null; } }
  function meta(name) {
    var el = document.querySelector('meta[property="' + name + '"],meta[name="' + name + '"]');
    return el ? oneLine(el.getAttribute("content")) : "";
  }
  function visible(el) {
    if (!el || !el.getClientRects().length) return false;
    var cs = getComputedStyle(el);
    return cs.visibility !== "hidden" && cs.display !== "none";
  }
  // טקסט של אלמנט כולל שבירות שורה (br, p, li), בלי סקריפטים.
  function textOf(el) {
    var out = "";
    (function walk(n) {
      if (n.nodeType === 3) { out += n.nodeValue; return; }
      if (n.nodeType !== 1) return;
      var tag = n.tagName;
      if (/^(SCRIPT|STYLE|NOSCRIPT|BUTTON|svg|SVG|IFRAME|FORM|SELECT)$/.test(tag)) return;
      if (tag === "BR") { out += "\n"; return; }
      var block = /^(P|DIV|LI|H[1-6]|TR|DT|DD|SECTION|ARTICLE|BLOCKQUOTE|UL|OL)$/.test(tag);
      if (block) out += "\n";
      for (var c = n.firstChild; c; c = c.nextSibling) walk(c);
      if (block) out += "\n";
    })(el);
    return clean(out);
  }

  // ---- 1. JSON-LD ----
  function fromJsonLd() {
    var found = null;
    function visit(node, depth) {
      if (!node || found || depth > 8) return;
      if (Array.isArray(node)) { node.forEach(function (n) { visit(n, depth + 1); }); return; }
      if (typeof node !== "object") return;
      if (isType(node, "Recipe")) { found = node; return; }
      ["@graph", "mainEntity", "mainEntityOfPage", "itemListElement", "item", "hasPart", "about"].forEach(function (k) { visit(node[k], depth + 1); });
    }
    document.querySelectorAll('script[type="application/ld+json"]').forEach(function (s) {
      if (found) return;
      var txt = (s.textContent || "").trim().replace(/^<!--|-->$/g, "");
      var data = null;
      try { data = JSON.parse(txt); } catch (e) {
        // אתרים עם פסיקים מיותרים או שורות שבורות בתוך מחרוזות
        try { data = JSON.parse(txt.replace(/,\s*([}\]])/g, "$1").replace(/[\n\r\t]+/g, " ")); } catch (e2) { data = null; }
      }
      visit(data, 0);
    });
    return found;
  }

  // ---- 2. Microdata ----
  function fromMicrodata() {
    var root = document.querySelector('[itemtype*="schema.org/Recipe" i]');
    if (!root) return null;
    function props(name) {
      return Array.prototype.filter.call(root.querySelectorAll('[itemprop~="' + name + '"]'), function (el) {
        return el.closest("[itemscope]") === root || el.parentElement.closest("[itemscope]") === root;
      });
    }
    function val(el) {
      if (!el) return "";
      if (el.tagName === "META") return el.getAttribute("content") || "";
      if (el.tagName === "IMG") return el.getAttribute("src") || el.getAttribute("data-src") || "";
      if (el.tagName === "A" || el.tagName === "LINK") return el.getAttribute("href") || "";
      if (el.tagName === "TIME") return el.getAttribute("datetime") || textOf(el);
      return el.getAttribute("content") || textOf(el);
    }
    var r = { "@type": "Recipe" };
    var name = props("name")[0];
    if (name) r.name = oneLine(val(name));
    var ing = props("recipeIngredient").concat(props("ingredients")).map(function (e) { return oneLine(val(e)); }).filter(Boolean);
    if (ing.length) r.recipeIngredient = ing;
    var ins = props("recipeInstructions").map(function (e) {
      var steps = e.querySelectorAll('[itemprop="text"]');
      if (steps.length) return Array.prototype.map.call(steps, function (s) { return textOf(s); }).join("\n");
      return textOf(e);
    }).filter(Boolean);
    if (ins.length) r.recipeInstructions = ins.join("\n");
    ["image", "description", "recipeYield", "prepTime", "cookTime", "totalTime"].forEach(function (k) {
      var e = props(k)[0];
      if (e && val(e)) r[k] = k === "image" ? abs(val(e)) : oneLine(val(e));
    });
    return r;
  }

  // ---- 3. תוספי WordPress ----
  var PLUGINS = [
    { root: ".wprm-recipe", ing: ".wprm-recipe-ingredient", ingGroup: ".wprm-recipe-ingredient-group", ingGroupName: ".wprm-recipe-group-name", step: ".wprm-recipe-instruction-text", stepGroup: ".wprm-recipe-instruction-group", stepGroupName: ".wprm-recipe-group-name", name: ".wprm-recipe-name" },
    { root: ".tasty-recipes", ing: ".tasty-recipes-ingredients li", step: ".tasty-recipes-instructions li, .tasty-recipes-instructions p", name: ".tasty-recipes-title" },
    { root: ".mv-create-card", ing: ".mv-create-ingredients li", step: ".mv-create-instructions li", name: ".mv-create-title" },
    { root: ".zrdn-recipe-container,#zlrecipe-container-wrapper", ing: ".zrdn-ingredient,.ingredient", step: ".zrdn-instruction,.instruction", name: ".zrdn-recipe-title,#zlrecipe-title" },
    { root: ".easyrecipe", ing: ".ingredient", step: ".instruction", name: ".ERSName" },
    { root: ".recipe-card,.recipe-box", ing: ".ingredients li,.recipe-ingredients li", step: ".instructions li,.recipe-instructions li,.method li", name: ".recipe-title,.recipe-name" },
  ];
  function fromPlugins() {
    for (var i = 0; i < PLUGINS.length; i++) {
      var p = PLUGINS[i];
      var root = document.querySelector(p.root);
      if (!root) continue;
      var sections = { ingredients: [], instructions: [] };
      var ing = groups(root, p.ingGroup, p.ingGroupName, p.ing, sections.ingredients);
      var steps = groups(root, p.stepGroup, p.stepGroupName, p.step, sections.instructions);
      if (ing.length < 2 && steps.length < 1) continue;
      var nameEl = root.querySelector(p.name);
      return { recipe: { "@type": "Recipe", name: nameEl ? oneLine(nameEl.textContent) : "", recipeIngredient: ing, recipeInstructions: steps }, sections: sections };
    }
    return null;
  }
  function groups(root, groupSel, nameSel, itemSel, sectionsOut) {
    var items = [];
    var groupEls = groupSel ? root.querySelectorAll(groupSel) : [];
    if (groupEls.length) {
      groupEls.forEach(function (g) {
        var title = nameSel && g.querySelector(nameSel) ? oneLine(g.querySelector(nameSel).textContent) : "";
        var list = Array.prototype.map.call(g.querySelectorAll(itemSel), function (e) { return oneLine(textOf(e)); }).filter(Boolean);
        if (title && list.length) sectionsOut.push({ title: title, index: items.length });
        items = items.concat(list);
      });
      return items;
    }
    return Array.prototype.map.call(root.querySelectorAll(itemSel), function (e) { return oneLine(textOf(e)); }).filter(Boolean);
  }

  // ---- 4. מבנה הדף ----
  // הכותרת כולה היא מילת המפתח, אולי עם סוגריים ("מרכיבים (תבנית 20x30)") או יעד ("מצרכים לבצק", "Ingredients for 4").
  // \b לא עובד עם עברית, ולכן הגבול הוא "אין אות אחרי".
  function headRe(words) {
    var tail = String.raw`(?![\p{L}\p{M}])\s*(?:\(([^)]{0,60})\))?\s*(?:(?:ל|ל־|ל-|for|für|pour|para|per|для|لـ|ل)\s?\S.{0,30})?$`;
    return new RegExp("^(?:" + words + ")" + tail, "iu");
  }
  var ING_HEAD = headRe("ה?מצרכים|ה?רכיבים|ה?מרכיבים|ה?חומרים|מה צריך|רשימת (?:ה)?מצרכים|מצרכים עיקריים|ingredients?|ingredient list|what you(?:'ll| will)? need|you will need|المكونات|المقادير|zutaten|ingrédients|ingredientes|ingredienti|ingrediënten|ingrediente|ингредиенты|інгредієнти|składniki|ingredienser|ainekset|hozzávalók|suroviny|přísady|malzemeler|υλικά|材料|配料|재료|nguyên liệu|sastojci");
  var STEP_HEAD = headRe("אופן ה?הכנה|הוראות ה?הכנה|דרך ה?הכנה|שלבי ה?הכנה|ה?הכנה|איך מכינים|כך מכינים|instructions?|directions?|method|preparation|steps|how to make(?: it)?|طريقة (?:التحضير|العمل)|التحضير|zubereitung|anleitung|préparation|étapes|instrucciones|preparación|elaboración|pasos|modo de preparo|preparo|procedimento|preparazione|bereiding(?:swijze)?|приготовление|способ приготовления|приготування|przygotowanie|sposób przygotowania|tillagning|gör så här|valmistus|elkészítés|postup|hazırlanışı|yapılışı|εκτέλεση|οδηγίες|作り方|做法|步骤|만드는 법|조리 방법|cách làm|priprema|postopek");
  var NOTE_HEAD = headRe("טיפ(?:ים)?|הערות?|tips?|notes?|ملاحظات|hinweise|astuces|consejos|consigli|советы");
  // כותרת משנה בתוך רשימה: "לבצק:", "For the dough:", "Für den Teig:"
  var SUB_HEAD = /^(?:ל|ל־|ל-)?[^\d\s].{0,38}:$|^(?:for the|für den|für die|pour la|pour le|para la|para el|per la|per il|для)\s.{1,35}:?$/i;
  var SKIP = "script,style,noscript,aside,nav,footer,form,figure,iframe,ins,button,select,svg,video,[class*=share],[class*=related],[class*=advert],[class*=-ad-],[id*=taboola],[class*=taboola],[class*=outbrain],[aria-hidden=true],[class*=comment],[id*=comment],[class*=newsletter],[class*=subscribe],[class*=breadcrumb],[class*=wp-caption],figcaption";

  function headingText(el) {
    var t = oneLine(el.textContent).replace(/[:：\s]+$/, "").replace(/^[\d.\-–•*#\s]+/, "");
    return t.length <= (t.indexOf("(") > 0 ? 70 : 45) ? t : "";
  }
  function candidateHeadings() {
    var els = document.querySelectorAll("h1,h2,h3,h4,h5,h6,strong,b,p,dt,span,div,summary,label,th,u,em");
    var out = [];
    for (var i = 0; i < els.length; i++) {
      var el = els[i];
      if (el.children.length > 3 || el.closest(SKIP)) continue;
      if (!visible(el)) continue;
      var t = headingText(el);
      if (!t) continue;
      var m;
      if ((m = t.match(ING_HEAD))) out.push({ el: el, kind: "ing", note: m[1] });
      else if (STEP_HEAD.test(t)) out.push({ el: el, kind: "step" });
      else if (NOTE_HEAD.test(t)) out.push({ el: el, kind: "note" });
    }
    // אלמנט פנימי עדיף על העוטף שלו
    return out.filter(function (h) { return !out.some(function (o) { return o !== h && h.el.contains(o.el); }); });
  }

  // כל התוכן שבין הכותרת לכותרת הבאה, בלי קשר למבנה: אחים, טקסט אחרי <br>, או פסקאות בהמשך.
  function scopeOf(el) {
    return el.closest('article,[itemprop="articleBody"],.entry-content,.article-body,.article-content,.post-content,.recipe,main') || document.body;
  }
  function between(start, end) {
    var scope = scopeOf(start);
    var r = document.createRange();
    r.setStartAfter(start);
    if (end && scope.contains(end)) r.setEndBefore(end); else r.setEnd(scope, scope.childNodes.length);
    var div = document.createElement("div");
    div.appendChild(r.cloneContents());
    div.querySelectorAll(SKIP).forEach(function (x) { x.remove(); });
    // קישור שהוא כל הטקסט של הבלוק שלו = טיזר לכתבה אחרת
    div.querySelectorAll("a").forEach(function (a) {
      var t = oneLine(a.textContent);
      var parent = a.parentElement;
      if (t.length > 25 && parent && oneLine(parent.textContent) === t) parent.remove();
    });
    return textOf(div).split("\n").map(function (l) { return l.replace(/^[\s•·▪●◦*\-–—✓✔☐□]+/, "").trim(); }).filter(Boolean);
  }

  var JUNK = /^(?:שתפו|שיתוף|הדפסה|הדפס|לחצו|קראו (?:עוד|גם)|כתבות נוספות|עוד באתר|פרסומת|לכל המתכונים|אהבתם\?|רוצים לקבל|הירשמו|אני מאשר|advertisement|share|print|pin it|jump to|read more|related|תגיות|tags?|צילום|photo|subscribe|sign up)(?![\p{L}])|^טבלת (?:ה)?(?:המרות|מידות|משקלות)|^\p{Extended_Pictographic}|תנאי שימוש|מדיניות (?:ה)?פרטיות|בהתחברות|שדרגו את|privacy policy|terms of (?:use|service)|ניוזלטר|newsletter|\d{1,2}\.\d{1,2}\.\d{2,4}\s*$/iu;
  // סוף המתכון: מכאן מתחילות תגובות, קרדיטים או כתבות אחרות.
  // כותרת משנה של שלבים: "הכנת המלית", "הרכבת הלזניה"
  var STEP_PART = /^(?:הכנת|הרכבת|אופן הכנת|הכנה של|מכינים את)\s\S.{0,30}$/u;
  var END = /^(?:בתאבון|שיהיה (?:לכם |לכן )?בתאבון|בהצלחה|חג שמח|באהבה|הכנתם את|good luck|happy (?:baking|cooking)|מוזמנים לשתף|תגובות|הוסיפו תגובה|כתבו תגובה|טעינו\?|מקור|באדיבות|קרדיט|לכל המתכונים|עוד מתכונים|מתכונים נוספים|אולי יעניין|כתבות נוספות|enjoy!?$|bon app[ée]tit|comments?|leave a (?:comment|reply)|you may also like|more recipes|guten appetit|buon appetito|¡?buen provecho)(?![\p{L}])/iu;
  // מצרך: כמות (מספר, שבר) או יחידה מוכרת
  var QTY = /[\d½¼¾⅓⅔⅛]|(?:^|\s)(?:כוס|כוסות|כף|כפות|כפית|כפיות|גרם|ק"ג|קילו|מ"ל|ליטר|חבילה|חבילות|קופסה|קופסת|יחידות|שן|שיני|צרור|קורט|קמצוץ|חופן|פרוסות|ביצים|ביצה|cups?|tbsp|tsp|tablespoons?|teaspoons?|grams?|g|kg|ml|l|oz|lbs?|pounds?|pinch|cloves?|tasse|cuillères?|gramm|el|tl|prise|cucchiai[oe]?|cucharadas?)(?![\p{L}])/iu;
  var QTY_START = /^(?:כ-?|~)?[\d½¼¾⅓⅔⅛]/;
  var DATE = /\d{1,2}[\/.]\d{1,2}[\/.]\d{2,4}|\d{2}\/\d{4}/;
  function strongQty(l) { return !DATE.test(l) && (QTY_START.test(l) || (QTY.test(l) && !/^\d+\.\s/.test(l))); }

  // ---- כמות וזמן משורות "תווית: ערך" ("מספר מנות במתכון: 18", "זמן הכנה: 40 דקות", "Serves 4") ----
  var YIELD_LINE = /^(?:מספר (?:ה)?מנות|מנות|כמות|תפוקה|יוצאות|יוצאים|יוצא|מתאים ל|servings?|serves|yields?|makes|portions?|porciones|portionen|porzioni|personnes)(?![\p{L}])([^:：\d]{0,30})[:：]?\s*(?:כ-?|~|about\s|approx\.?\s)?(\d+(?:[.,]\d+)?)(?:\s*[-–]\s*\d+)?\s*([^\d]{0,25})$/iu;
  var SERVING_WORD = /מנ(?:ה|ות)|סועד|serv|portion|person|porzion|porcion/i;
  // "18 servings" ו-"18 מנות" נקראים בשרת כמספר מנות; "24 עוגיות" נשמר ככמות עם יחידה
  function yieldOf(line) {
    var t = oneLine(line), m = t.match(YIELD_LINE);
    if (!m) return null;
    var n = m[2].replace(",", "."), rest = m[3].replace(/[.)\s]+$/, "").trim();
    return SERVING_WORD.test(t) || !rest ? n + " servings" : n + " " + rest;
  }
  function isYieldLine(l) { return l.length <= 70 && !QTY_START.test(l) && !!yieldOf(l); }

  var TIME_LINE = /^(זמן(?:\s+[\p{L}"'׳״]{2,12}){0,2}|סה"כ(?:\s+זמן)?|total(?:\s+time)?|prep(?:aration)?(?:\s+time)?|cook(?:ing)?(?:\s+time)?|bak(?:e|ing)(?:\s+time)?|time|ready in|zubereitungszeit|backzeit|kochzeit|temps(?:\s+de\s+\p{L}+)?)\s*[:：]\s*(.{1,40})$/iu;
  function timeKind(label) {
    if (/קריאה|צפייה|read|watch/i.test(label)) return null; // "זמן קריאה: 3 דקות" אינו זמן של המתכון
    if (/הכנה|עבודה|prep|zubereitung|préparation/i.test(label)) return "prepTime";
    if (/אפי|בישול|טיגון|צלי|cook|bak|koch|cuisson/i.test(label)) return "cookTime";
    return "totalTime";
  }
  // "40 דקות", "שעה וחצי", "1-2 שעות", "1 hr 15 min" ← משך ב-ISO 8601 ("PT1H30M"), שהשרת מבין
  function isoDuration(s) {
    s = oneLine(s).replace(/½/g, ".5");
    var min = 0, hours = false, m;
    var NUM = "(\\d+(?:[.,]\\d+)?)(?:\\s*[-–]\\s*(\\d+(?:[.,]\\d+)?))?\\s*";
    // בטווח ("1-2 שעות") לוקחים את הגבול העליון: זה הזמן שצריך לתכנן
    function num(x) { return parseFloat((x[2] || x[1]).replace(",", ".")); }
    if (/חצי שעה|half an hour/i.test(s)) { min += 30; }
    else if ((m = s.match(new RegExp(NUM + "(?:שעות|שעה|ש['׳]|hours?|hrs?|h|stunden?|std\\.?|heures?)(?![\\p{L}])", "iu")))) { min += num(m) * 60; hours = true; }
    else if (/שעתיים/.test(s)) { min += 120; hours = true; }
    else if (/(?:^|\s)שעה(?![\p{L}])|an hour|one hour/iu.test(s)) { min += 60; hours = true; }
    if (hours && /וחצי|and a half/i.test(s)) min += 30;
    if ((m = s.match(new RegExp(NUM + "(?:דקות|דקה|דק['׳]?|minutes?|mins?|minuten|m)(?![\\p{L}])", "iu")))) min += num(m);
    min = Math.round(min);
    if (min <= 0 || min > 60 * 24 * 7) return null;
    return "PT" + (min >= 60 ? Math.floor(min / 60) + "H" : "") + (min % 60 ? (min % 60) + "M" : "");
  }
  // שורות קצרות בדף: "זמן: 40 דקות", "מספר מנות: 18". הראשונה מכל סוג קובעת.
  function pageFacts() {
    var facts = {};
    var scope = document.querySelector("article, main, [role=main]") || document.body;
    var els = scope.querySelectorAll("li,p,dt,dd,td,span,div,strong,b,small,time");
    for (var i = 0; i < els.length; i++) {
      var el = els[i];
      if (el.children.length > 4 || el.closest(SKIP)) continue;
      var t = oneLine(el.textContent);
      if (!t || t.length > 70 || !visible(el)) continue;
      if (!facts.recipeYield && isYieldLine(t)) facts.recipeYield = yieldOf(t);
      var m = t.match(TIME_LINE), k = m && timeKind(m[1]);
      if (k && !facts[k]) { var d = isoDuration(m[2]); if (d) facts[k] = d; }
    }
    return facts;
  }
  function ingredientLike(l) { return l.length <= 140 && (QTY.test(l) || (l.length <= 30 && !/[.!?]$/.test(l))); }
  // כותרת משנה בלי נקודתיים: "לבצק", "למילוי", "תערובת הבשר -"
  var PART = /^(?:ל|ל-|ל־)?ה?(?:בצק|מילוי|רוטב|ציפוי|הגשה|קישוט|בלילה|קרם|סירופ|תערובת|טיגון|מרינדה|תיבול|ציר|קרמבל|גנאש|זיגוג|בסיס|שכבה|קציצות|סלט|ויניגרט|פירורים|עוף|בשר|תוספות|קצפת|מלית|ממרח|מעטפת|כדורים|דגים|ירקות)(?![\p{L}])/u;

  function sectionize(lines, isIngredients) {
    var items = [], sections = [];
    lines.forEach(function (l) {
      var sub = l.length <= 40 && (SUB_HEAD.test(l) || (isIngredients && (/:$/.test(l) || / [-–]$/.test(l) || (!/\d/.test(l) && PART.test(l) && l.length <= 25))) ||
        (!isIngredients && !/\d/.test(l) && STEP_PART.test(l.replace(/[:：\s]+$/, ""))));
      if (sub) {
        var t = l.replace(/[:：\-–\s]+$/, "").trim();
        if (t) sections.push({ title: t, index: items.length });
        return;
      }
      items.push(l);
    });
    // כותרת משנה בלי פריטים אחריה אינה מקטע
    sections = sections.filter(function (s, i) {
      var next = sections[i + 1];
      return s.index < items.length && (!next || next.index > s.index);
    });
    return { items: items, sections: sections };
  }

  // רשימה שחוזרת פעמיים ברצף (גרסת הדפסה, תצוגה למובייל) נשמרת פעם אחת.
  function dedupeRepeat(list) {
    for (var k = Math.floor(list.length / 2); k >= 2; k--) {
      var same = true;
      for (var i = 0; i < k && same; i++) same = list[i] === list[i + k];
      if (same) return list.slice(0, k).concat(list.slice(2 * k));
    }
    return list;
  }
  function untilEnd(lines) {
    var out = [];
    for (var i = 0; i < lines.length; i++) { if (END.test(lines[i])) break; out.push(lines[i]); }
    return out;
  }
  // שלבים: בלי שורות קצרות בסוף (שם הכותבת, קטגוריות, שם המתכון הבא)
  function trimSteps(lines) {
    while (lines.length > 1 && lines[lines.length - 1].length < 40 && !/[.!:]$/.test(lines[lines.length - 1])) lines.pop();
    return lines.filter(function (l) { return (l.length > 1 && l.indexOf("|") < 0) || l.length > 60; });
  }
  function linesIn(range) {
    var div = document.createElement("div");
    div.appendChild(range.cloneContents());
    div.querySelectorAll(SKIP + ",h1").forEach(function (x) { x.remove(); });
    return textOf(div).split("\n").map(function (l) { return l.replace(/^[\s•·▪●◦*\-–—✓✔☐□]+/, "").trim(); }).filter(Boolean);
  }
  // שורות לפני כותרת ההכנה שנראות כמו מצרכים (כשאין כותרת "מצרכים")
  function ingredientsBefore(stepEl, heads) {
    var prev = heads.filter(function (o) { return o.el !== stepEl && (o.el.compareDocumentPosition(stepEl) & Node.DOCUMENT_POSITION_FOLLOWING); }).pop();
    var scope = scopeOf(stepEl);
    var r = document.createRange();
    if (prev) r.setStartAfter(prev.el); else r.setStart(scope, 0);
    r.setEndBefore(stepEl);
    var lines = linesIn(r);
    var run = [];
    for (var i = lines.length - 1; i >= 0; i--) {
      if (ingredientLike(lines[i])) run.unshift(lines[i]);
      else if (run.length >= 3) break;
      else run = [];
    }
    var h1 = oneLine((document.querySelector("h1") || {}).textContent || "");
    while (run.length && (!strongQty(run[0]) || (h1 && (run[0].indexOf(h1) >= 0 || h1.indexOf(run[0]) >= 0)) || ING_HEAD.test(run[0].replace(/[:：\s]+$/, "")))) {
      if (ING_HEAD.test(run[0].replace(/[:：\s]+$/, ""))) { run.shift(); break; }
      run.shift();
    }
    run = run.filter(function (l) { return !DATE.test(l); });
    return run.filter(strongQty).length >= 3 ? run : [];
  }
  function listItems(l) {
    return Array.prototype.map.call(l.querySelectorAll(":scope > li"), function (li) { return oneLine(textOf(li)); }).filter(Boolean);
  }
  // דף בלי כותרות בכלל: רשימת המצרכים היא ה-ul/ol שרוב השורות בה הן כמויות, וההכנה היא רשימה של משפטים אחריה.
  function fromLists() {
    var lists = Array.prototype.filter.call(document.querySelectorAll("ul,ol"), function (l) { return visible(l) && !l.closest(SKIP + ",header,nav,footer,menu"); });
    var best = null, bestScore = 0;
    lists.forEach(function (l) {
      var items = listItems(l);
      var q = items.filter(function (x) { return strongQty(x) && x.length <= 140; }).length;
      if (items.length >= 3 && q / items.length >= 0.6 && q > bestScore) { best = { el: l, items: items }; bestScore = q; }
    });
    if (!best) return null;
    var steps = null;
    lists.forEach(function (l) {
      if (steps || l === best.el || !(best.el.compareDocumentPosition(l) & Node.DOCUMENT_POSITION_FOLLOWING)) return;
      var items = listItems(l);
      var avg = items.join("").length / Math.max(1, items.length);
      if (items.length >= 2 && avg >= 35) steps = items;
    });
    return { recipe: { "@type": "Recipe", recipeIngredient: best.items, recipeInstructions: steps || [] }, sections: { ingredients: [], instructions: [] } };
  }

  function fromStructure() {
    var heads = candidateHeadings();
    // המתכון הראשון בעמוד: כותרת מצרכים ראשונה, ואחריה כותרת ההכנה הראשונה שאחריה
    var ing = heads.filter(function (h) { return h.kind === "ing"; })[0];
    var step = heads.filter(function (h) {
      return h.kind === "step" && (!ing || (ing.el.compareDocumentPosition(h.el) & Node.DOCUMENT_POSITION_FOLLOWING));
    })[0] || heads.filter(function (h) { return h.kind === "step"; })[0];
    if (!ing && !step) return fromLists();
    function nextHead(h) {
      return heads.filter(function (o) { return o !== h && (h.el.compareDocumentPosition(o.el) & Node.DOCUMENT_POSITION_FOLLOWING); })[0];
    }
    var ingLines = { items: [], sections: [] }, stepLines = { items: [], sections: [] };
    var yieldNote = ing && ing.note;
    if (ing) {
      var raw = untilEnd(between(ing.el, nextHead(ing) && nextHead(ing).el)).filter(function (l) { return !JUNK.test(l); });
      // "(ל-15-17 יחידות)" בשורה נפרדת מתחת לכותרת
      if (raw.length && /^\(.*\)$/.test(raw[0])) { yieldNote = yieldNote || raw[0].slice(1, -1); raw.shift(); }
      // מצרך הוא שורה קצרה; פסקה ארוכה מסמנת את סוף הרשימה
      var cut = [], tailSteps = null;
      for (var i = 0; i < raw.length && cut.length < 80; i++) {
        // כותרת ההכנה שלא זוהתה כאלמנט (למשל שורה בתוך אותה פסקה): ממנה מתחילים השלבים
        var bare = raw[i].replace(/[:：\s]+$/, "");
        if (cut.length >= 2 && bare.length <= 40 && !/\d/.test(bare) && (STEP_HEAD.test(bare) || STEP_PART.test(bare))) {
          tailSteps = raw.slice(STEP_HEAD.test(bare) ? i + 1 : i);
          break;
        }
        if (raw[i].length > 220) break;
        cut.push(raw[i]);
      }
      ingLines = sectionize(dedupeRepeat(cut), true);
      if (!step && tailSteps) stepLines = sectionize(trimSteps(dedupeRepeat(tailSteps.slice(0, 60))), false);
    } else if (step) {
      ingLines = sectionize(ingredientsBefore(step.el, heads), true);
    }
    if (step) {
      var next = nextHead(step);
      var rawSteps = untilEnd(between(step.el, next && next.el)).filter(function (l) { return !JUNK.test(l); });
      stepLines = sectionize(trimSteps(dedupeRepeat(rawSteps.slice(0, 60))), false);
    }
    if (ingLines.items.length < 2) {
      var lists = fromLists();
      if (lists) ingLines = { items: lists.recipe.recipeIngredient, sections: [] };
    }
    if (ingLines.items.length < 2 && !stepLines.items.length) return null;
    return {
      recipe: { "@type": "Recipe", recipeIngredient: ingLines.items, recipeInstructions: stepLines.items, recipeYield: yieldNote && /\d/.test(yieldNote) ? yieldNote.trim() : undefined },
      sections: { ingredients: ingLines.sections, instructions: stepLines.sections },
    };
  }

  // ---- נרמול הוראות ----
  function stepsOf(v, sectionsOut) {
    var out = [];
    (function walk(x, sectionTitle) {
      if (x == null) return;
      if (typeof x === "string") {
        splitSteps(x).forEach(function (s, i) {
          if (i === 0 && sectionTitle) sectionsOut.push({ title: sectionTitle, index: out.length });
          out.push(s);
        });
        return;
      }
      if (Array.isArray(x)) { x.forEach(function (y, i) { walk(y, i === 0 ? sectionTitle : null); }); return; }
      if (isType(x, "HowToSection")) {
        var title = oneLine(x.name || "");
        walk(x.itemListElement || x.steps, title || null);
        return;
      }
      if (x.itemListElement && !x.text) { walk(x.itemListElement, sectionTitle); return; }
      var t = x.text || x.description || x.name || "";
      walk(String(t), sectionTitle);
    })(v, null);
    return out.filter(Boolean);
  }

  // גוש טקסט אחד → שלבים: לפי שורות, ואז לפי מספור ("1." / "2)"), ואז, אם זו פסקה ארוכה מאוד, לפי משפטים.
  function splitSteps(text) {
    var t = clean(text);
    if (!t) return [];
    var lines = t.split(/\n+/).map(function (l) { return l.trim(); }).filter(Boolean);
    if (lines.length === 1) {
      var numbered = t.split(/(?:^|\s)(?=\d{1,2}[.)]\s+\S)/).map(function (l) { return l.trim(); }).filter(Boolean);
      if (numbered.length >= 3) lines = numbered;
    }
    if (lines.length === 1 && t.length > 400) {
      var sentences = t.match(/[^.!?。]+[.!?。]+["')\]]*\s*|[^.!?。]+$/g) || [t];
      // מאחדים משפטים קצרים כדי שכל שלב יהיה פעולה ברורה
      var merged = [], cur = "";
      sentences.forEach(function (s) {
        cur += s;
        if (cur.length >= 120) { merged.push(cur.trim()); cur = ""; }
      });
      if (cur.trim()) merged.push(cur.trim());
      if (merged.length >= 2) lines = merged;
    }
    return lines.map(function (l) { return l.replace(/^(?:step\s*)?\d{1,2}[.):]\s*|^שלב\s*\d+[.:]?\s*/i, "").trim(); }).filter(function (l) { return l.length > 1; });
  }

  function ingredientsOf(v) {
    var list = [];
    arr(v).forEach(function (x) {
      var s = typeof x === "string" ? x : x && (x.text || x.name || "");
      clean(s).split(/\n+/).forEach(function (l) { l = oneLine(l); if (l) list.push(l); });
    });
    return list;
  }

  function imageOf(v) {
    var best = null;
    arr(v).forEach(function (x) {
      if (best) return;
      if (typeof x === "string") best = x;
      else if (x && (x.url || x.contentUrl)) best = x.url || x.contentUrl;
      else if (x && x["@id"] && /^https?:/.test(x["@id"])) best = x["@id"];
    });
    return abs(best);
  }

  // תמונת המתכון כשאין og:image: הגדולה ביותר בתוכן, בלי לוגו, כותרת אתר, פרסומות ותמונות פרופיל.
  // תמונה שהטקסט החלופי שלה הוא שם המתכון עדיפה על כל השאר.
  var IMG_SKIP = "nav,footer,aside,[aria-hidden=true],[role=banner],[role=contentinfo],[class*=related],[class*=share],[class*=advert],[class*=-ad-],[id*=taboola],[class*=taboola],[class*=outbrain],[class*=comment],[class*=newsletter],[class*=author],[class*=avatar]";
  var IMG_JUNK = /logo|brand|avatar|icon|sprite|pixel|badge|placeholder|spinner|loading|ads?[_-]/i;
  function largestImage() {
    var best = null, score = 0;
    var name = norm(title());
    var scope = document.querySelector("article, main, [role=main]") || document.body;
    scope.querySelectorAll("img").forEach(function (img) {
      if (img.closest(IMG_SKIP)) return;
      // כותרת האתר; כותרת של כתבה בתוך article או main היא חלק מהתוכן
      var header = img.closest("header");
      if (header && !header.parentElement.closest("article, main")) return;
      var src = img.getAttribute("data-src") || img.getAttribute("data-lazy-src") || img.currentSrc || img.src;
      if (!src || /^data:/.test(src)) return;
      if (IMG_JUNK.test([src, img.alt, img.className, img.id].join(" "))) return;
      // ב-WebView של האפליקציה התמונות לא נטענות, ותמונה שלא נטענה מוצגת בגודל הטקסט החלופי שלה.
      // לכן הגודל מגיע מ-width ו-height של התגית, ורק בלעדיהם מהגודל המוצג.
      var w = img.naturalWidth || parseInt(img.getAttribute("width"), 10) || img.width || 0;
      var h = img.naturalHeight || parseInt(img.getAttribute("height"), 10) || img.height || 0;
      if (w * h < 200 * 150) return;
      var alt = norm(img.alt || "");
      var s = w * h * (name && alt.length >= 3 && (alt.indexOf(name) >= 0 || name.indexOf(alt) >= 0) ? 100 : 1);
      if (s > score) { score = s; best = src; }
    });
    return abs(best);
  }

  function title() {
    var h1 = Array.prototype.filter.call(document.querySelectorAll("h1"), visible)[0];
    return oneLine(meta("og:title") || (h1 && h1.textContent) || document.title).replace(/\s*[|\-–—]\s*[^|\-–—]{2,40}$/, "");
  }

  // ---- הרכבה ----
  var ld = fromJsonLd();
  var micro = ld ? null : fromMicrodata();
  var plugin = fromPlugins();
  var structure = fromStructure();

  var base = ld || micro || (plugin && plugin.recipe) || (structure && structure.recipe);
  if (!base) return JSON.stringify({ ok: false, title: title(), image: meta("og:image") || largestImage(), text: textOf(document.querySelector("article, main") || document.body).slice(0, 20000) });

  var recipe = {};
  Object.keys(base).forEach(function (k) { recipe[k] = base[k]; });
  recipe["@context"] = "https://schema.org";
  recipe["@type"] = "Recipe";
  var sections = { ingredients: [], instructions: [] };

  var ingredients = ingredientsOf(recipe.recipeIngredient || recipe.ingredients);
  var steps = stepsOf(recipe.recipeInstructions, sections.instructions);
  var source = ld ? "jsonld" : micro ? "microdata" : plugin ? "plugin" : "structure";

  // מקור משלים: תוסף או מבנה הדף, כשהם עשירים יותר (למשל JSON-LD עם הוראות בגוש אחד או בלי מצרכים)
  function norm(x) { return oneLine(x).toLowerCase().replace(/[^\p{L}\p{N}]+/gu, ""); }
  // כותרות מקטע מהמקור המשלים, לפי הפריט הראשון של כל מקטע ברשימה הקיימת
  function mapSections(altItems, altSections, items) {
    var out = [], from = 0;
    altSections.forEach(function (sec) {
      var first = norm(altItems[sec.index] || "");
      if (!first) return;
      for (var j = from; j < items.length; j++) {
        var n = norm(items[j]);
        if (n && (n === first || n.indexOf(first) === 0 || first.indexOf(n) === 0)) { out.push({ title: sec.title, index: j }); from = j + 1; return; }
      }
    });
    return out;
  }
  [plugin, structure].forEach(function (alt) {
    if (!alt) return;
    var altIng = alt.recipe.recipeIngredient || [];
    var altSteps = alt.recipe.recipeInstructions || [];
    var tag = alt === plugin ? "plugin" : "structure";
    if (altIng.length >= 2 && ingredients.length < 2) {
      ingredients = altIng;
      sections.ingredients = alt.sections.ingredients;
      source += "+" + tag + "-ing";
    } else if (!sections.ingredients.length && alt.sections.ingredients.length) {
      var mapped = mapSections(altIng, alt.sections.ingredients, ingredients);
      if (mapped.length) { sections.ingredients = mapped; source += "+" + tag + "-sections"; }
    }
    if (!recipe.recipeYield && alt.recipe.recipeYield) recipe.recipeYield = alt.recipe.recipeYield;
    if (altSteps.length && (steps.length === 0 || (steps.length === 1 && altSteps.length > 1))) {
      steps = altSteps;
      sections.instructions = alt.sections.instructions;
      source += "+" + tag + "-steps";
    } else if (!sections.instructions.length && alt.sections.instructions.length) {
      var mappedSteps = mapSections(altSteps, alt.sections.instructions, steps);
      if (mappedSteps.length) sections.instructions = mappedSteps;
    }
  });
  // כותרות מקטע שנכנסו כמצרך ("לבצק:")
  if (!sections.ingredients.length) {
    var s2 = sectionize(ingredients, true);
    if (s2.sections.length && s2.items.length >= 2) { ingredients = s2.items; sections.ingredients = s2.sections; }
  }

  // שורת כמות ("מספר מנות במתכון המקורי: 18") שנכנסה לרשימת המצרכים עוברת לשדה הכמות
  var keptIdx = [];
  ingredients.forEach(function (l, i) {
    if (!isYieldLine(l)) { keptIdx.push(i); return; }
    if (!recipe.recipeYield) recipe.recipeYield = yieldOf(l);
  });
  if (keptIdx.length < ingredients.length) {
    sections.ingredients = sections.ingredients.map(function (s) {
      return { title: s.title, index: keptIdx.filter(function (i) { return i < s.index; }).length };
    }).filter(function (s, i, all) { return s.index < keptIdx.length && (!all[i + 1] || all[i + 1].index > s.index); });
    ingredients = keptIdx.map(function (i) { return ingredients[i]; });
  }
  // כמות וזמנים שהמקור לא כולל, מתוך הדף עצמו
  var facts = pageFacts();
  ["recipeYield", "prepTime", "cookTime", "totalTime"].forEach(function (k) {
    if (!recipe[k] && facts[k]) recipe[k] = facts[k];
  });

  recipe.recipeIngredient = ingredients;
  recipe.recipeInstructions = steps.map(function (s) { return { "@type": "HowToStep", text: s }; });
  delete recipe.ingredients;
  recipe.name = oneLine(recipe.name || (plugin && plugin.recipe.name) || title());
  if (!recipe.description) recipe.description = meta("og:description") || meta("description");
  var img = imageOf(recipe.image) || abs(meta("og:image")) || largestImage();
  if (img) recipe.image = img;
  recipe.url = recipe.url || location.href;
  delete recipe.review; delete recipe.aggregateRating; delete recipe.video; delete recipe.author;

  return JSON.stringify({
    ok: ingredients.length >= 1 || steps.length >= 1,
    source: source,
    recipe: recipe,
    sections: sections,
    image: img,
    counts: { ingredients: ingredients.length, steps: steps.length },
  });
})();
