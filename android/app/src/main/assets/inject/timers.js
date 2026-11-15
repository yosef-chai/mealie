/*
 * זיהוי זמני בישול בטקסט של שלבי ההכנה, בכל השפות של Mealie.
 * מחזיר טווחים בטקסט עם משך בשניות, למשל "אופים 20-25 דקות" → {min:1200, max:1500}.
 * עצמאי (בלי DOM), כדי שאפשר יהיה לבדוק אותו ב-Node מול מתכונים אמיתיים.
 */
(function (root) {
  "use strict";

  var H = 3600, M = 60, S = 1;

  // ---- מילון: יחידות זמן לפי שפה (צורות יחיד, רבים וקיצורים) ----
  var UNITS = {
    // עברית
    "שעה": H, "שעות": H, "דקה": M, "דקות": M, "דק'": M, "דק׳": M, "שנייה": S, "שניה": S, "שניות": S, "שנ'": S, "שנ׳": S,
    // English
    hour: H, hours: H, hr: H, hrs: H, h: H, minute: M, minutes: M, min: M, mins: M, second: S, seconds: S, sec: S, secs: S,
    // العربية
    "ساعة": H, "ساعات": H, "دقيقة": M, "دقائق": M, "دقيقه": M, "د": M, "ثانية": S, "ثواني": S, "ثوان": S,
    // Deutsch
    stunde: H, stunden: H, std: H, minuten: M, sekunde: S, sekunden: S, sek: S,
    // Français
    heure: H, heures: H, minutes_fr: M, seconde: S, secondes: S,
    // Español / Português / Galego / Català
    hora: H, horas: H, minuto: M, minutos: M, segundo: S, segundos: S, hores: H, minuts: M, segons: S,
    // Italiano
    ora: H, ore: H, minuti: M, secondi: S,
    // Nederlands / Afrikaans
    uur: H, minuut: M, minuten_nl: M, seconde_nl: S, seconden: S, ure: H, sekondes: S,
    // Svenska / Dansk / Norsk / Íslenska
    timme: H, timmar: H, tim: H, minut: M, minuter: M, sekund: S, sekunder: S, time: H, timer: H, minutter: M,
    "klukkustund": H, "klukkustundir": H, "klst": H, "mínúta": M, "mínútur": M, "mín": M, "sekúndur": S,
    // Suomi / Eesti
    tunti: H, tuntia: H, tunnin: H, minuutti: M, minuuttia: M, minuutin: M, sekuntia: S, tund: H, tundi: H, minutit: M, sekundit: S,
    // Magyar
    "óra": H, "órát": H, "óráig": H, perc: M, percet: M, percig: M, "másodperc": S, "másodpercig": S,
    // Polski / Čeština / Slovenčina
    godzina: H, godziny: H, godzin: H, "godzinę": H, godz: H, minuta: M, minuty: M, minut_pl: M, "minutę": M, sekundy: S, sekund: S,
    hodina: H, hodiny: H, hodin: H, hodinu: H, hod: H, minutu: M, "minút": M, "sekúnd": S,
    // Русский / Українська / Български / Српски
    "час": H, "часа": H, "часов": H, "ч": H, "минута": M, "минуты": M, "минут": M, "минуту": M, "мин": M,
    "секунда": S, "секунды": S, "секунд": S, "сек": S,
    "година": H, "години": H, "годин": H, "год": H, "хвилина": M, "хвилини": M, "хвилин": M, "хв": M, "секунди": S,
    "часът": H, "минути": M, "сат": H, "сати": H, "сата": H,
    // Hrvatski / Slovenščina / Srpski (latinica)
    sat: H, sati: H, sata: H, minute_hr: M, ura: H, uri: H, sekundi: S, sekunde: S,
    // Lietuvių / Latviešu
    valanda: H, valandos: H, "valandų": H, val: H, "minutė": M, "minutės": M, "minučių": M, "sekundžių": S,
    stunda: H, stundas: H, "stundu": H, st: H, "minūte": M, "minūtes": M, "minūtēm": M, sekundes: S,
    // Ελληνικά
    "ώρα": H, "ώρες": H, "λεπτό": M, "λεπτά": M, "δευτερόλεπτα": S,
    // Türkçe / Română / Tiếng Việt
    saat: H, dakika: M, dk: M, saniye: S, "oră": H, oră: H, ore_ro: H, minut_ro: M, secunde: S,
    "giờ": H, "phút": M, "giây": S,
    // 日本語 / 中文 / 한국어 (צמודים למספר)
    "時間": H, "分": M, "分間": M, "秒": S, "小时": H, "小時": H, "分钟": M, "分鐘": M, "시간": H, "분": M, "초": S,
  };
  // מפתחות עם סיומת שפה נועדו רק למנוע כפילות במילון; המילה עצמה זהה.
  Object.keys(UNITS).forEach(function (k) {
    var m = k.match(/^(.+)_[a-z]{2}$/);
    if (m) { UNITS[m[1]] = UNITS[k]; delete UNITS[k]; }
  });

  // יחידות זוגיות: "שעתיים" = 2 שעות.
  var DUAL = { "שעתיים": [2, H], "דקותיים": [2, M], "ساعتين": [2, H], "ساعتان": [2, H], "دقيقتين": [2, M], "دقيقتان": [2, M] };

  // "חצי שעה", "полчаса": יחידה שלמה עם שבר.
  var COMPOUND = { "полчаса": 30 * M, "півгодини": 30 * M, "demi-heure": 30 * M, "mezz'ora": 30 * M, "mezzora": 30 * M, "halbstündig": 30 * M };

  // מספרים במילים (יחיד וזוגי, זכר ונקבה).
  var NUMBERS = {
    // עברית
    "אחת": 1, "אחד": 1, "שתיים": 2, "שתים": 2, "שתי": 2, "שניים": 2, "שני": 2, "שלוש": 3, "שלושה": 3, "שלושת": 3, "ארבע": 4, "ארבעה": 4,
    "חמש": 5, "חמישה": 5, "שש": 6, "שישה": 6, "שבע": 7, "שבעה": 7, "שמונה": 8, "תשע": 9, "תשעה": 9, "עשר": 10, "עשרה": 10,
    "עשרים": 20, "שלושים": 30, "ארבעים": 40, "חמישים": 50, "שישים": 60, "תשעים": 90,
    // English
    a: 1, an: 1, one: 1, two: 2, three: 3, four: 4, five: 5, six: 6, seven: 7, eight: 8, nine: 9, ten: 10, eleven: 11,
    twelve: 12, fifteen: 15, twenty: 20, thirty: 30, forty: 40, fifty: 50, sixty: 60, ninety: 90, couple: 2,
    // מילת יחיד בשפות אחרות ("une heure", "una hora", "eine Stunde")
    un: 1, une: 1, una: 1, uno: 1, "un'": 1, um: 1, uma: 1, ein: 1, eine: 1, einer: 1, einen: 1, een: 1, en: 1, ett: 1, "одну": 1, "один": 1,
    "واحدة": 1,
  };
  var HALF = { "חצי": 0.5, half: 0.5, "نصف": 0.5, halbe: 0.5, halben: 0.5, "demi": 0.5, "demie": 0.5, media: 0.5, medio: 0.5, mezza: 0.5, meia: 0.5, meio: 0.5, halve: 0.5, halv: 0.5, "רבע": 0.25, quarter: 0.25, "ربع": 0.25, viertel: 0.25, quart: 0.25, cuarto: 0.25, quarto: 0.25, kwartier: 0.25 };
  var PLUS_HALF = { "וחצי": 0.5, "ורבע": 0.25, "ونصف": 0.5, "وربع": 0.25, "anderthalb": 1.5, "eineinhalb": 1.5, "полтора": 1.5, "півтори": 1.5 };
  var RANGE = { "-": 1, "–": 1, "—": 1, "~": 1, "עד": 1, "או": 1, to: 1, or: 1, bis: 1, "à": 1, a: 1, ou: 1, o: 1, tot: 1, till: 1, til: 1, "до": 1, "или": 1, "або": 1, "إلى": 1, "او": 1, "أو": 1, til_da: 1, eller: 1, lub: 1, albo: 1, "és": 1, ili: 1, "ή": 1, "veya": 1, "sau": 1, "đến": 1, "～": 1, "〜": 1, "至": 1, "到": 1 };
  var AND = { and: 1, "ו": 1, und: 1, et: 1, y: 1, e: 1, en: 1, i: 1, "и": 1, "і": 1, och: 1, og: 1, ja: 1, a_cs: 1, "و": 1, "και": 1, ve: 1, "và": 1, "," : 1 };
  var BETWEEN = { "בין": 1, between: 1, zwischen: 1, entre: 1, tra: 1, fra: 1, tussen: 1, "между": 1, "بين": 1, mellan: 1 };
  // אחרי "בין": המילה הבאה מתחילה ב-ל ("בין 20 ל-25"); לא נכון באופן כללי, ולכן רק כאן.
  var BETWEEN_SEP = { "ל": 1, and: 1, und: 1, et: 1, y: 1, e: 1, en: 1, "и": 1, "و": 1, och: 1, og: 1 };
  // הקשר שמאפשר "דקה" בלי מספר ("ממתינים עוד דקה"); "דקה" לבד יכולה להיות גם "דקה" (רזה).
  var CONTEXT = { "עוד": 1, "במשך": 1, "למשך": 1, "תוך": 1, "בערך": 1, "אחרי": 1, "לפחות": 1, "כמעט": 1, "ממתינים": 1, "מחכים": 1, "ממתינה": 1, "מחכה": 1, "חכו": 1, "המתינו": 1, "מערבבים": 1, "for": 1, "about": 1, "another": 1, "after": 1, "within": 1 };

  // יחידות שבלי ספרה לפניהן הן מילה אחרת ("a time" = פעם, "ora" = עכשיו).
  var AMBIG = { time: 1, timer: 1, h: 1, st: 1, val: 1, hod: 1, godz: 1, std: 1, sek: 1, sec: 1, secs: 1, min: 1, mins: 1, hr: 1, hrs: 1, dk: 1, tim: 1, klst: 1, "mín": 1, sat: 1, ora: 1, "ч": 1, "мин": 1, "сек": 1, "хв": 1, "год": 1, "د": 1 };
  // יחידות שמותר לזהות בלי מספר: "מקררים לשעה", "ממתינים דקה". שניות לא ("לשנייה" היא גם "לשלב הבא").
  var BARE = { "שעה": H, "דקה": M, "ساعة": H, "دقيقة": M };
  var ARTICLES = { a: 1, an: 1 }; // "half an hour", "an hour and a half"
  var VULGAR = { "½": 0.5, "¼": 0.25, "¾": 0.75, "⅓": 1 / 3, "⅔": 2 / 3, "⅛": 0.125 };
  // תחיליות עבריות (כ-20, לשעה, בעשר) וערביות (لمدة, بعد) שנצמדות למילה.
  var HE_PREFIX = /^[וכלבמשה]{1,3}/;
  var AR_PREFIX = /^[وبلف]{1,2}/;

  // יחידות שנצמדות למספר בסינית, יפנית וקוריאנית; הארוכות קודם ("分間" לפני "分")
  var CJK_UNITS = ["時間", "分間", "分钟", "分鐘", "小时", "小時", "시간", "分", "秒", "분", "초"];

  function foldDigits(s) {
    return s.replace(/[٠-٩]/g, function (c) { return String(c.charCodeAt(0) - 0x0660); })
      .replace(/[۰-۹]/g, function (c) { return String(c.charCodeAt(0) - 0x06F0); })
      .replace(/[０-９]/g, function (c) { return String(c.charCodeAt(0) - 0xFF10); });
  }

  // ---- פירוק לטוקנים, עם מיקום בטקסט המקורי ----
  var TOKEN = /(\d+(?:[.,]\d+)?(?:\s?[½¼¾⅓⅔⅛]|\s\d+\/\d+)?|\d+\/\d+|[½¼¾⅓⅔⅛])|([぀-ヿ一-鿿가-힯]+)|([\p{L}\p{M}]+(?:['׳’][\p{L}\p{M}]*)?)|([-–—~～〜,])/gu;

  function tokenize(text) {
    var folded = foldDigits(text);
    var out = [];
    var m;
    TOKEN.lastIndex = 0;
    while ((m = TOKEN.exec(folded))) {
      var t = { start: m.index, end: m.index + m[0].length, raw: m[0] };
      if (m[1]) { t.kind = "num"; t.value = parseNumber(m[1]); }
      else if (m[2]) {
        t.kind = "cjk";
        // "20分煮る": היחידה היא רק תחילת הרצף; את השאר מפרקים מחדש
        var unit = CJK_UNITS.filter(function (k) { return m[2].indexOf(k) === 0; })[0];
        if (unit && unit.length < m[2].length) {
          t.raw = unit;
          t.end = t.start + unit.length;
          TOKEN.lastIndex = t.end;
        }
      }
      else if (m[3]) { t.kind = "word"; t.word = m[3].toLowerCase().replace(/[’׳]/g, function (c) { return c === "׳" ? "׳" : "'"; }); }
      else { t.kind = "sep"; t.word = m[4]; }
      out.push(t);
    }
    return out;
  }

  function parseNumber(s) {
    var v = 0;
    var frac = s.match(/[½¼¾⅓⅔⅛]/);
    var slash = s.match(/(\d+)\/(\d+)$/);
    var main = s.replace(/[½¼¾⅓⅔⅛]/, "").replace(/\s?\d+\/\d+$/, "").trim();
    if (main) v = parseFloat(main.replace(",", "."));
    if (frac) v += VULGAR[frac[0]];
    if (slash && +slash[2]) v += +slash[1] / +slash[2];
    return v;
  }

  // מילה עם תחילית עברית או ערבית: מנסים את המילה כמו שהיא, ואז בלי התחילית.
  function lookup(word, table) {
    if (word == null) return null;
    if (Object.prototype.hasOwnProperty.call(table, word)) return { v: table[word], prefixed: false };
    var pre = word.match(HE_PREFIX) || word.match(AR_PREFIX);
    if (pre) {
      for (var n = 1; n <= pre[0].length; n++) {
        var rest = word.slice(n);
        if (rest && Object.prototype.hasOwnProperty.call(table, rest)) return { v: table[rest], prefixed: true, prefix: word.slice(0, n) };
      }
    }
    return null;
  }

  // CJK: היחידה נצמדת למספר ("25分", "1時間30分").
  function cjkUnit(tok) {
    var s = tok.raw;
    var keys = ["時間", "分間", "分钟", "分鐘", "小时", "小時", "시간", "分", "秒", "분", "초"];
    for (var i = 0; i < keys.length; i++) if (s.indexOf(keys[i]) === 0) return UNITS[keys[i]];
    return null;
  }

  function unitAt(toks, i) {
    var t = toks[i];
    if (!t) return null;
    if (t.kind === "cjk") return cjkUnit(t) ? { unit: cjkUnit(t), prefixed: false } : null;
    if (t.kind !== "word") return null;
    var u = lookup(t.word, UNITS);
    return u ? { unit: u.v, prefixed: u.prefixed, prefix: u.prefix } : null;
  }

  function numberAt(toks, i) {
    var t = toks[i];
    if (!t) return null;
    if (t.kind === "num") return { value: t.value, len: 1 };
    if (t.kind !== "word") return null;
    var n = lookup(t.word, NUMBERS);
    if (!n) return null;
    // "עשרים וחמש", "twenty five"
    var next = toks[i + 1];
    if (n.v >= 20 && next && next.kind === "word") {
      var units = lookup(next.word, NUMBERS);
      if (units && units.v < 10) return { value: n.v + units.v, len: 2 };
    }
    return { value: n.v, len: 1 };
  }

  // ---- חלק אחד: "20 דקות", "חצי שעה", "שעתיים", "שעה וחצי", "an hour" ----
  // מחזיר {seconds, unit, next, end} או null.
  function part(toks, i, allowBare) {
    var t = toks[i];
    if (!t) return null;

    // מילה אחת שהיא משך שלם
    if (t.kind === "word") {
      var c = lookup(t.word, COMPOUND);
      if (c) return finish(toks, i + 1, c.v, 30 * M, t);
      var d = lookup(t.word, DUAL);
      if (d) return finish(toks, i + 1, d.v[0] * d.v[1], d.v[1], t);
      var ph = lookup(t.word, PLUS_HALF);
      if (ph && ph.v > 1) { // "anderthalb Stunden", "полтора часа"
        var uu = unitAt(toks, i + 1);
        if (uu) return finish(toks, i + 2, ph.v * uu.unit, uu.unit, t);
      }
      // "חצי שעה", "half an hour", "media hora", "רבע שעה"
      var hf = lookup(t.word, HALF);
      if (hf) {
        var j = i + 1;
        if (toks[j] && toks[j].kind === "word" && ARTICLES[toks[j].word]) j++;
        if (toks[j] && toks[j].kind === "sep" && toks[j].word === "-") j++; // demi-heure
        var hu = unitAt(toks, j);
        if (hu && hu.unit >= M) return finish(toks, j + 1, hf.v * hu.unit, hu.unit, t);
        return null;
      }
      // "שלושת רבעי שעה"
      if (lookup(t.word, NUMBERS) && toks[i + 1] && /^(רבעי|quarters)$/.test(toks[i + 1].word || "")) {
        var qu = unitAt(toks, i + 2);
        if (qu) return finish(toks, i + 3, numberAt(toks, i).value * 0.25 * qu.unit, qu.unit, t);
      }
    }

    // מספר ויחידה
    var n = numberAt(toks, i);
    if (n) {
      var k = i + n.len;
      // "1 hour", "an hour"; לא "1 h" כשהמספר הוא מילה ("a h")
      var u = unitAt(toks, k);
      if (u) {
        var uw = toks[k].kind === "word" ? (u.prefixed ? toks[k].word.slice(u.prefix.length) : toks[k].word) : "";
        if (t.kind === "word" && AMBIG[uw]) return null;
        // "10 הדקות האחרונות" כן, "5 ושעה" לא
        if (u.prefixed && u.prefix !== "ה") return null;
        return finish(toks, k + 1, n.value * u.unit, u.unit, t);
      }
      return null;
    }

    // יחידה בודדת: "שעה" = שעה אחת; "דקה" רק בהקשר של זמן (אחרת זו גם "דקה" במובן רזה).
    var bare = unitAt(toks, i);
    if (bare && t.kind === "word") {
      var w = bare.prefixed ? t.word.slice(bare.prefix.length) : t.word;
      if (Object.prototype.hasOwnProperty.call(BARE, w)) {
        var prev = toks[i - 1];
        var next = toks[i + 1];
        var context = (bare.prefixed && /[כל]/.test(bare.prefix)) ||
          (prev && prev.kind === "word" && lookup(prev.word, CONTEXT)) ||
          (prev && prev.word === "-" && toks[i - 2] && /^[כל]$/.test(toks[i - 2].word || "")) ||
          (next && next.kind === "word" && (lookup(next.word, PLUS_HALF) || next.word === "או"));
        if (BARE[w] === H || context || allowBare) return finish(toks, i + 1, BARE[w], BARE[w], t);
      }
    }
    return null;
  }

  // "וחצי", "ורבע", "and a half" אחרי היחידה; ואז חלק נוסף עם יחידה קטנה יותר ("שעה ו-20 דקות", "1 hour 30 minutes").
  function finish(toks, j, seconds, unit, first) {
    var end = toks[j - 1].end;
    var t = toks[j];
    var ph = t && t.kind === "word" ? lookup(t.word, PLUS_HALF) : null;
    if (ph && ph.v < 1) { seconds += ph.v * unit; end = t.end; j++; }
    else if (t && t.word === "and" && toks[j + 1] && toks[j + 1].word === "a" && toks[j + 2] && lookup(toks[j + 2].word, HALF)) {
      seconds += lookup(toks[j + 2].word, HALF).v * unit; end = toks[j + 2].end; j += 3;
    } else if (t && (t.word === "et" || t.word === "y" || t.word === "e") && toks[j + 1] && /^(demie|media|mezza|meia|mezzo)$/.test(toks[j + 1].word || "")) {
      seconds += 0.5 * unit; end = toks[j + 1].end; j += 2;
    }
    // חלק נוסף ביחידה קטנה יותר
    var k = j;
    var joiner = toks[k] && (toks[k].kind === "word" || toks[k].kind === "sep") && lookup(toks[k].word, AND);
    if (joiner) k++;
    if (toks[k] && toks[k].kind === "sep" && toks[k].word === "-") k++; // "ו-20"
    // "שעה ועשרים דקות": ה-ו צמודה למספר
    var nextTok = toks[k];
    if (nextTok && unit > S) {
      var sub = null;
      if (nextTok.kind === "word" && !joiner && /^ו/.test(nextTok.word)) {
        var stripped = { kind: "word", word: nextTok.word.slice(1), start: nextTok.start, end: nextTok.end, raw: nextTok.raw };
        var copy = toks.slice(); copy[k] = stripped;
        sub = part(copy, k, false);
      } else if (joiner || nextTok.kind === "num" || nextTok.kind === "cjk" || (nextTok.kind === "word" && lookup(nextTok.word, NUMBERS))) {
        sub = part(toks, k, false);
      }
      // "1h30" / "1 h 30": מספר בלי יחידה אחרי שעות = דקות
      if (!sub && unit === H && nextTok.kind === "num" && !unitAt(toks, k + 1) && toks[j - 1] && /^(h|ч|std)$/.test(toks[j - 1].word || "") && Number.isInteger(nextTok.value) && nextTok.value < 60) {
        sub = { seconds: nextTok.value * M, unit: M, next: k + 1, end: nextTok.end };
      }
      if (sub && sub.unit < unit) return { seconds: seconds + sub.seconds, unit: sub.unit, next: sub.next, end: sub.end, start: first.start };
    }
    return { seconds: seconds, unit: unit, next: j, end: end, start: first.start };
  }

  // ---- טווחים: "20-25 דקות", "15 עד 18 דקות", "שעתיים-שלוש", "דקה או שתיים", "בין 20 ל-25 דקות" ----
  function duration(toks, i) {
    var t = toks[i];
    var afterBetween = toks[i - 1] && toks[i - 1].kind === "word" && lookup(toks[i - 1].word, BETWEEN);

    // מספר, מפריד, מספר, יחידה
    var a = numberAt(toks, i);
    if (a) {
      var s = i + a.len;
      var sep = toks[s];
      var isSep = sep && (sep.kind === "sep" || sep.kind === "word") && sep.word !== "," &&
        (RANGE[sep.word] || (afterBetween && lookup(sep.word, BETWEEN_SEP)));
      var j = isSep ? s + 1 : s;
      if (isSep && toks[j] && toks[j].kind === "sep" && toks[j].word === "-") j++; // "ל-25"
      if (isSep) {
        var b = numberAt(toks, j);
        if (b && b.value > a.value) {
          var p = part(toks, j, false);
          if (p) {
            var unitSec = p.seconds / b.value;
            if (Number.isFinite(unitSec) && unitSec > 0) {
              return { start: t.start, end: p.end, min: Math.round(a.value * unitSec), max: Math.round(p.seconds), next: p.next };
            }
          }
        }
      }
    }

    var first = part(toks, i, false);
    if (!first) return null;
    var r = { start: first.start, end: first.end, min: Math.round(first.seconds), max: Math.round(first.seconds), next: first.next };

    // "שעתיים-שלוש", "דקה או שתיים": מספר בלי יחידה אחרי המפריד, באותה יחידה
    var sp = toks[first.next];
    if (sp && (sp.kind === "sep" || sp.kind === "word") && sp.word !== "," && (RANGE[sp.word] || (afterBetween && lookup(sp.word, BETWEEN_SEP)))) {
      var k = first.next + 1;
      if (toks[k] && toks[k].kind === "sep" && toks[k].word === "-") k++;
      var second = part(toks, k, true);
      if (second && second.seconds > first.seconds) {
        return { start: r.start, end: second.end, min: r.min, max: Math.round(second.seconds), next: second.next };
      }
      var bn = numberAt(toks, k);
      if (bn && !unitAt(toks, k + bn.len)) {
        var max = bn.value * first.unit;
        if (max > first.seconds) return { start: r.start, end: toks[k + bn.len - 1].end, min: r.min, max: Math.round(max), next: k + bn.len };
      }
    }
    // "בין שעה ורבע לשעה וחצי": ה-ל צמודה לחלק השני
    if (afterBetween && sp && sp.kind === "word" && /^ל/.test(sp.word)) {
      var copy = toks.slice();
      copy[first.next] = { kind: "word", word: sp.word.slice(1), start: sp.start, end: sp.end, raw: sp.raw };
      var sec2 = part(copy, first.next, true);
      if (sec2 && sec2.seconds > first.seconds) return { start: r.start, end: sec2.end, min: r.min, max: Math.round(sec2.seconds), next: sec2.next };
    }
    return r;
  }

  var MAX = 24 * H;

  /** כל הזמנים בטקסט, לפי הסדר. כל תוצאה: {start, end, min, max} בשניות, כש-end לא כולל. */
  function find(text) {
    if (!text) return [];
    var toks = tokenize(text);
    var out = [];
    for (var i = 0; i < toks.length;) {
      var d = duration(toks, i);
      if (d && d.min >= 1 && d.max <= MAX && d.min <= d.max) {
        // מתחילים את הטווח בטקסט מהתחילית ("כ-20 דקות" מסומן מ-20).
        out.push({ start: d.start, end: d.end, min: d.min, max: d.max, text: text.slice(d.start, d.end) });
        i = d.next;
      } else {
        i++;
      }
    }
    return out;
  }

  root.MealieTimers = { find: find };
})(typeof window !== "undefined" ? window : globalThis);
