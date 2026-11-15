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
const shots = path.resolve(import.meta.dirname, "screenshots");

// פרטי ברירת המחדל של שרת Mealie חדש (mealie/core/settings/settings.py).
const USER = "changeme@example.com";
const PASSWORD = "MyPassword";

test.beforeEach(async ({ page }) => {
  // מחליף את ה-WebMessageListener של אנדרואיד: אותו ממשק (postMessage / onmessage).
  await page.addInitScript(({ js, css }) => {
    window.__bridgeOut = [];
    window.MealieNative = {
      postMessage(m) {
        window.__bridgeOut.push(typeof m === "string" ? JSON.parse(m) : { binary: m.byteLength });
      },
      onmessage: null,
    };
    window.__mealieApp = { raw: false, arrayBuffer: true, theme: "auto", css };
    (0, eval)(js);
  }, { js, css });
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
  const setup = page.getByRole("link", { name: "I'm already set up, just bring me to the homepage" });
  if (await setup.isVisible({ timeout: 5_000 }).catch(() => false)) await setup.click();
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
  await expect.poll(async () => page.evaluate(() => window.__bridgeOut.filter((m) => m.binary).length)).toBe(2);
});
