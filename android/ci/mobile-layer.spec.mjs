/*
 * בודק שהסקריפט שהאפליקציה מזריקה (assets/inject) עובד מול גרסת Mealie ששוחררה:
 * ה-router של Nuxt נגיש, הסלקטורים קיימים, ניווט בלי טעינה מחדש, ולכידת הורדות blob.
 * כשל כאן עוצר את שחרור ה-APK.
 */
import { test, expect } from "@playwright/test";
import fs from "node:fs";
import path from "node:path";

const injectDir = path.resolve(import.meta.dirname, "../app/src/main/assets/inject");
const js = fs.readFileSync(path.join(injectDir, "mobile.js"), "utf8");
const css = fs.readFileSync(path.join(injectDir, "mobile.css"), "utf8");
const timers = fs.readFileSync(path.join(injectDir, "timers.js"), "utf8");
const shots = path.resolve(import.meta.dirname, "screenshots");

// פרטי ברירת המחדל של שרת Mealie חדש (mealie/core/settings/settings.py).
const USER = "changeme@example.com";
const PASSWORD = "MyPassword";

test.beforeEach(async ({ page }) => {
  // מחליף את ה-WebMessageListener של אנדרואיד: אותו ממשק (postMessage / onmessage).
  await page.addInitScript(({ js, css, timers }) => {
    window.__bridgeOut = [];
    window.MealieNative = {
      postMessage(m) {
        window.__bridgeOut.push(typeof m === "string" ? JSON.parse(m) : { binary: m.byteLength });
      },
      onmessage: null,
    };
    window.__mealieApp = { raw: false, arrayBuffer: true, theme: "auto", css, timers: true };
    (0, eval)(timers);
    (0, eval)(js);
  }, { js, css, timers });
});

async function messages(page, type) {
  return page.evaluate((t) => window.__bridgeOut.filter((m) => m.type === t), type);
}

async function send(page, cmd) {
  await page.evaluate((c) => window.MealieNative.onmessage({ data: JSON.stringify(c) }), cmd);
}

async function login(page) {
  await page.goto("/login");
  await page.getByLabel("Email or Username", { exact: true }).fill(USER);
  await page.getByLabel("Password", { exact: true }).fill(PASSWORD);
  await page.getByRole("button", { name: "Login", exact: true }).click();
  // שרת חדש עם פרטי ברירת המחדל: Mealie מפנה לאשף ההגדרה (pages/admin/setup.vue), עם כפתור דילוג
  await expect(page).toHaveURL(/\/(g\/[^/]+|admin\/setup)/, { timeout: 15_000 });
  if (/\/admin\/setup/.test(page.url())) {
    await page.getByText("I'm already set up, just bring me to the homepage", { exact: true }).click();
  }
  await expect(page).toHaveURL(/\/g\/[^/]+/);
}

test("router, selectors and styles are available", async ({ page }) => {
  await login(page);
  await expect.poll(async () => (await messages(page, "ready")).some((m) => m.router)).toBe(true);
  await expect(page.locator("#mealie-app-style")).toHaveCount(1);
  await expect(page.locator("header").first()).toBeVisible();
  await expect(page.locator("header button").first()).toBeVisible();
  await expect(page.locator(".v-application")).toHaveClass(/v-theme--(light|dark)/);
  await expect.poll(async () => (await messages(page, "theme")).length).toBeGreaterThan(0);
  const routes = await messages(page, "route");
  expect(routes.some((r) => r.path.startsWith("/g/"))).toBe(true);

  // עיצוב המובייל נשען על המחלקות האלה בדף המתכונים; אם Mealie משנה אותן, העיצוב לא חל
  await expect(page.locator(".search-box .v-field").first()).toBeVisible();
  const row = page.locator(".search-row").first();
  await expect(row).toHaveCSS("flex-wrap", "nowrap");
  expect(await row.locator(".v-btn").count()).toBeGreaterThanOrEqual(2);
  await expect(row.locator(".v-btn").first()).toHaveCSS("border-radius", "8px");
});

test("native tab navigation uses the router without reloading", async ({ page }) => {
  await login(page);
  await expect.poll(async () => (await messages(page, "ready")).some((m) => m.router)).toBe(true);
  await page.evaluate(() => { window.__noReload = true; });

  const tabs = [
    ["shopping", "/shopping-lists"],
    ["mealplan", "/household/mealplan/planner/view"],
  ];
  fs.mkdirSync(shots, { recursive: true });
  for (const [name, target] of tabs) {
    await send(page, { cmd: "navigate", path: target, seq: 1 });
    await expect(page).toHaveURL(new RegExp(target.replace(/\//g, "\\/")));
    await expect.poll(async () => (await messages(page, "nav")).at(-1)?.ok).toBe(true);
    await page.screenshot({ path: path.join(shots, `${name}.png`) });
  }
  expect(await page.evaluate(() => window.__noReload)).toBe(true);

  await send(page, { cmd: "openDrawer" });
  await expect.poll(async () => (await messages(page, "drawer")).at(-1)?.ok).toBe(true);
  await page.screenshot({ path: path.join(shots, "drawer.png") });
});

test("blob downloads are captured with framed chunks", async ({ page }) => {
  await login(page);
  await page.evaluate(() => {
    const blob = new Blob([new Uint8Array(3 * 1024 * 1024)], { type: "application/octet-stream" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = "test.bin";
    a.click();
    URL.revokeObjectURL(url);
  });
  await expect.poll(async () => (await messages(page, "download-begin")).length).toBe(1);
  const begin = (await messages(page, "download-begin"))[0];
  expect(begin.chunks).toBe(2);
  await expect.poll(async () => page.evaluate(() => window.__bridgeOut.filter((m) => typeof m.binary === "number").length)).toBe(2);
});

test("cooking times in recipe steps become timer buttons", async ({ page, request }) => {
  // מתכון עם שלבים בעברית ובאנגלית, דרך ה-API (כמו ייבוא)
  const auth = await request.post("/api/auth/token", { form: { username: USER, password: PASSWORD } });
  const token = (await auth.json()).access_token;
  const headers = { Authorization: `Bearer ${token}` };
  const recipe = {
    "@context": "https://schema.org",
    "@type": "Recipe",
    name: `Timer check ${Date.now()}`,
    recipeIngredient: ["2 cups flour"],
    recipeInstructions: [
      { "@type": "HowToStep", text: "אופים ב-180 מעלות 20-25 דקות, עד הזהבה." },
      { "@type": "HowToStep", text: "Let it rest for half an hour." },
    ],
  };
  const created = await request.post("/api/recipes/create/html-or-json", { headers, data: { data: JSON.stringify(recipe) } });
  expect(created.ok()).toBe(true);
  const slug = await created.json();

  await login(page);
  const self = await (await request.get("/api/users/self", { headers })).json();
  await page.goto(`/g/${self.groupSlug}/r/${slug}`);
  // Mealie מצייר את השלבים גם בתצוגות מוסתרות (למשל מצב בישול); גם שם יש כפתורים, וסופרים את הגלויים
  const chips = page.locator(".mealie-timer:visible");
  await expect(chips).toHaveCount(2);
  await expect(chips.first()).toHaveText("20-25 דקות");
  await expect(page.locator(".recipe-step-title:visible").first()).toHaveCSS("font-weight", "600");
  await expect(page.locator(".fixed-bar .custom-btn-group").first()).toHaveCSS("border-radius", "999px");
  // כרטיסי השלבים: שרת חדש ב-CI ריק ממתכונים, ולכן בודקים כרטיס כאן ולא ברשימת המתכונים
  await expect(page.locator(".v-card:has(> .recipe-step-title):visible").first()).toHaveCSS("border-top-left-radius", "12px");
  fs.mkdirSync(shots, { recursive: true });
  await page.screenshot({ path: path.join(shots, "timers.png"), fullPage: true });

  // לחיצה שולחת בקשת טיימר לאפליקציה ולא מסמנת את השלב כבוצע
  await chips.nth(1).click();
  await expect.poll(async () => (await messages(page, "timer")).at(-1)).toMatchObject({ min: 1800, max: 1800, step: 2 });
  await chips.first().click();
  await expect.poll(async () => (await messages(page, "timer")).at(-1)).toMatchObject({ min: 1200, max: 1500, step: 1 });
});

test("servings arrows and pull-to-refresh guard", async ({ page, request }) => {
  test.setTimeout(120_000);
  // מתכון עם מצרך מקושר: רק אז Mealie מציג את כפתורי שינוי המנות
  const auth = await request.post("/api/auth/token", { form: { username: USER, password: PASSWORD } });
  const headers = { Authorization: `Bearer ${(await auth.json()).access_token}` };
  const food = await (await request.post("/api/foods", { headers, data: { name: `flour ${Date.now()}` } })).json();
  const slug = await (await request.post("/api/recipes", { headers, data: { name: `Servings check ${Date.now()}` } })).json();
  const recipe = await (await request.get(`/api/recipes/${slug}`, { headers })).json();
  recipe.recipeServings = 2;
  recipe.recipeIngredient = [{ quantity: 2, food, note: "", referenceId: "11111111-1111-4111-8111-111111111111" }];
  expect((await request.put(`/api/recipes/${slug}`, { headers, data: recipe })).ok()).toBe(true);

  await login(page);
  const self = await (await request.get("/api/users/self", { headers })).json();
  await page.goto(`/g/${self.groupSlug}/r/${slug}`);

  const row = page.locator("[data-mealie-stepper]:visible").first();
  await expect(row).toBeVisible();
  const value = row.locator(".v-card");
  const [dec, inc] = [row.locator(".v-item-group > .v-btn").first(), row.locator(".v-item-group > .v-btn").last()];
  await expect(dec).toHaveAttribute("aria-label", /.+/);
  // חץ למטה לפני הכמות וחץ למעלה אחריה (order ב-CSS), שלושתם בגובה 40
  await expect(inc).toHaveCSS("order", "2");
  await expect(value).toHaveCSS("height", "40px");
  await expect(value).toContainText("2");
  await inc.click();
  await expect(value).toContainText("3");

  // החלון: חיצים סביב שדה הכמות, משנים את אותו ערך והחלון נשאר פתוח
  await value.click();
  const popup = page.locator("[data-mealie-servings]");
  // החלון נפתח באנימציית scale; לחיצה באמצע נוחתת על התוכן שמתחתיו
  await expect(page.locator(".v-overlay__content:has([data-mealie-servings])")).toHaveCSS("transform", "none");
  await expect(popup.locator(".mealie-pop-step")).toHaveCount(2);
  await popup.locator(".mealie-pop-step.inc").click();
  await expect(popup.locator("input")).toHaveValue("4");
  await expect(value).toContainText("4");
  await expect(popup).toBeVisible();
  for (let i = 0; i < 3; i++) await popup.locator(".mealie-pop-step.dec").click();
  await expect(value).toContainText("1");
  await expect(popup.locator(".mealie-pop-step.dec")).toBeDisabled();

  // משיכה לרענון: לא מתוך חלון פתוח
  await popup.locator("input").dispatchEvent("touchstart");
  await expect.poll(async () => (await messages(page, "pull")).at(-1)?.allowed).toBe(false);
  await page.keyboard.press("Escape");
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.locator("main.v-main").dispatchEvent("touchstart");
  await expect.poll(async () => (await messages(page, "pull")).at(-1)?.allowed).toBe(true);
  // ולא כשהדף גלול
  await page.evaluate(() => window.scrollTo(0, 300));
  await expect.poll(async () => (await messages(page, "pull")).at(-1)?.allowed).toBe(false);
  await page.evaluate(() => window.scrollTo(0, 0));
  await expect.poll(async () => (await messages(page, "pull")).at(-1)?.allowed).toBe(true);
  // ולא כשדיאלוג נועל את הדף (Vuetify מקבע את המסמך, והגלילה שלו נראית 0)
  // חיפוש בכותרת (בטלפון: הכפתור שלפני היציאה) נפתח כדיאלוג
  await page.locator("header.v-app-bar button").nth(-2).click();
  await expect(page.locator(".v-dialog.v-overlay--active")).toBeVisible();
  await expect.poll(async () => (await messages(page, "pull")).at(-1)?.allowed).toBe(false);
});
