/*
 * בדיקות לזיהוי זמני בישול (assets/inject/timers.js), על משפטים אמיתיים ממתכונים.
 * הרצה: node --test android/ci/timers.test.mjs
 */
import { test } from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import vm from "node:vm";

const file = path.resolve(import.meta.dirname, "../app/src/main/assets/inject/timers.js");
const ctx = {};
ctx.globalThis = ctx;
vm.createContext(ctx);
vm.runInContext(fs.readFileSync(file, "utf8"), ctx);
// התוצאות נוצרות בהקשר vm אחר; JSON מנרמל אותן לאובייקטים רגילים לצורך השוואה
const find = (t) => JSON.parse(JSON.stringify(ctx.MealieTimers.find(t).map((m) => [m.text, m.min, m.max])));

const M = 60, H = 3600;

const cases = [
  // עברית: ספרות, טווחים, מילים, תחיליות, צורות זוגיות ושברים
  ["אופים ב-160 מעלות כ-20 דקות.", [["20 דקות", 20 * M, 20 * M]]],
  ["אופים 15 עד 18 דקות, לפי רמת הרכות.", [["15 עד 18 דקות", 15 * M, 18 * M]]],
  ["אופים בתנור 20-25 דקות, עד הזהבה.", [["20-25 דקות", 20 * M, 25 * M]]],
  ["מקררים לשעה להתייצבות.", [["לשעה", H, H]]],
  ["אופים ב-180 מעלות חצי שעה בלבד.", [["חצי שעה", 30 * M, 30 * M]]],
  ["מתפיחים כשעה וחצי בטמפרטורת החדר.", [["כשעה וחצי", 90 * M, 90 * M]]],
  ["מכסים לשעתיים-שלוש, לפי הזמן שיש.", [["לשעתיים-שלוש", 2 * H, 3 * H]]],
  ["עד תערובת אחידה (דקה או שתיים).", [["דקה או שתיים", M, 2 * M]]],
  ["אצל הכותבת זה לקח כשעה ו-20 דקות.", [["כשעה ו-20 דקות", 80 * M, 80 * M]]],
  ["ואופים בין שעה ורבע לשעה וחצי.", [["שעה ורבע לשעה וחצי", 75 * M, 90 * M]]],
  ["מבשלים בערך שלושת רבעי שעה.", [["שלושת רבעי שעה", 45 * M, 45 * M]]],
  ["אופים עשר דקות בתנור שחומם מראש.", [["עשר דקות", 10 * M, 10 * M]]],
  ["בעשר הדקות האחרונות לוקחים מהרוטב.", [["בעשר הדקות", 10 * M, 10 * M]]],
  ["מטגנים 3-4 דקות מכל צד.", [["3-4 דקות", 3 * M, 4 * M]]],
  ["אחרי 5-10 שניות, כשהנייר מתחיל להשחים.", [["5-10 שניות", 5, 10]]],
  ["ממתינים דקה.", [["דקה", M, M]]],
  ["טורפים במרץ כחצי דקה.", [["כחצי דקה", 30, 30]]],
  // לא זמן: "דקה/דקות" במובן רזה, מעלות, כמויות, ו"לשנייה" במובן "לבאה"
  ["מטגנים רצועות דקות של בצל סגול.", []],
  ["מורחים שכבה דקה של ריבה.", []],
  ["מוסיפים 2 כוסות קמח ואופים ב-180 מעלות.", []],
  ["להטמעה מלאה בין אחת לשנייה.", []],
  ["מערבבים כמה דקות.", []],
  // English
  ["Bake for 25 minutes, until golden.", [["25 minutes", 25 * M, 25 * M]]],
  ["Simmer 1 hour 30 minutes.", [["1 hour 30 minutes", 90 * M, 90 * M]]],
  ["Rest for half an hour.", [["half an hour", 30 * M, 30 * M]]],
  ["Cook for an hour and a half.", [["an hour and a half", 90 * M, 90 * M]]],
  ["Bake 10 to 12 mins.", [["10 to 12 mins", 10 * M, 12 * M]]],
  ["Roast 1½ hours.", [["1½ hours", 90 * M, 90 * M]]],
  ["Add the eggs one at a time.", []],
  // שפות נוספות
  ["Im vorgeheizten Ofen ca. 25 Minuten backen.", [["25 Minuten", 25 * M, 25 * M]]],
  ["Faire cuire 1 h 30 à feu doux.", [["1 h 30", 90 * M, 90 * M]]],
  ["Hornear durante 30-35 minutos.", [["30-35 minutos", 30 * M, 35 * M]]],
  ["Cuocere in forno per 40 minuti.", [["40 minuti", 40 * M, 40 * M]]],
  ["Варить 15 минут на медленном огне.", [["15 минут", 15 * M, 15 * M]]],
  ["تُخبز لمدة ٢٥ دقيقة.", [["٢٥ دقيقة", 25 * M, 25 * M]]],
  ["弱火で20分煮る。", [["20分", 20 * M, 20 * M]]],
  // מגבלת השעון: עד 24 שעות
  ["Marinate for 48 hours.", []],
];

for (const [text, expected] of cases) {
  test(text, () => assert.deepEqual(find(text), expected));
}
