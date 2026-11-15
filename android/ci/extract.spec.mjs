/*
 * בדיקות לחילוץ מתכון מדף (assets/import/extract.js) על דפים לדוגמה, בלי רשת.
 * כל דף מייצג מבנה שנמצא באתרים אמיתיים: JSON-LD עם הוראות בגוש אחד (foody), כתבה עם
 * כותרות בתוך פסקה ושורות <br> (walla, ynet), תוסף WPRM עם מקטעים, ו-microdata.
 */
import { test, expect } from "@playwright/test";
import fs from "node:fs";
import path from "node:path";

const code = fs.readFileSync(path.resolve(import.meta.dirname, "../app/src/main/assets/import/extract.js"), "utf8");

async function extract(page, html) {
  await page.setContent(`<!doctype html><html lang="he" dir="rtl"><head><meta charset="utf-8"><meta property="og:image" content="https://example.com/dish.jpg"></head><body>${html}</body></html>`);
  return JSON.parse(await page.evaluate(code));
}

test("JSON-LD with instructions in one block is split into steps", async ({ page }) => {
  const ld = {
    "@context": "https://schema.org",
    "@graph": [{ "@type": "WebPage" }, {
      "@type": "Recipe",
      name: "קציצות עדשים",
      recipeIngredient: ["1 כוס עדשים כתומות", "2 גזרים", "1 בצל"],
      recipeInstructions: "1. משרים את העדשים. 2. מגררים את הגזר והבצל. 3. מערבבים ויוצרים קציצות. 4. אופים 25 דקות.",
    }],
  };
  const r = await extract(page, `<script type="application/ld+json">${JSON.stringify(ld)}</script><h1>קציצות עדשים</h1>`);
  expect(r.ok).toBe(true);
  expect(r.source).toContain("jsonld");
  expect(r.recipe.recipeIngredient).toHaveLength(3);
  expect(r.recipe.recipeInstructions.map((s) => s.text)).toEqual([
    "משרים את העדשים.", "מגררים את הגזר והבצל.", "מערבבים ויוצרים קציצות.", "אופים 25 דקות.",
  ]);
  expect(r.image).toBe("https://example.com/dish.jpg");
});

test("news article with inline headings, sub-sections and a yield", async ({ page }) => {
  const r = await extract(page, `
    <article>
      <h1>סמבוסק במילוי בשר</h1>
      <p>פתיח של הכתבה עם סיפור על המתכון.</p>
      <p><b>מצרכים:</b><br>(ל-15 יחידות)<br>לבצק<br>2 כוסות קמח<br>1 כפית מלח<br>למילוי:<br>300 גרם בשר טחון<br>1 בצל קצוץ</p>
      <p><b>אופן ההכנה:</b></p>
      <ol><li>מערבבים את חומרי הבצק ולשים 5 דקות.</li><li>מטגנים את הבצל ומוסיפים את הבשר.</li><li>ממלאים, סוגרים ומטגנים בשמן עמוק.</li></ol>
      <p>בתאבון!</p>
      <div class="comments"><p>תגובה של קורא</p></div>
    </article>`);
  expect(r.ok).toBe(true);
  expect(r.recipe.recipeIngredient).toEqual(["2 כוסות קמח", "1 כפית מלח", "300 גרם בשר טחון", "1 בצל קצוץ"]);
  expect(r.sections.ingredients).toEqual([{ title: "לבצק", index: 0 }, { title: "למילוי", index: 2 }]);
  expect(r.recipe.recipeInstructions).toHaveLength(3);
  expect(r.recipe.recipeYield).toBe("ל-15 יחידות");
});

test("WP Recipe Maker groups become section titles", async ({ page }) => {
  const r = await extract(page, `
    <div class="wprm-recipe"><h2 class="wprm-recipe-name">Laksa</h2>
      <div class="wprm-recipe-ingredient-group"><h4 class="wprm-recipe-group-name">Broth</h4>
        <ul><li class="wprm-recipe-ingredient">1 l stock</li><li class="wprm-recipe-ingredient">2 tbsp paste</li></ul></div>
      <div class="wprm-recipe-ingredient-group"><h4 class="wprm-recipe-group-name">Toppings</h4>
        <ul><li class="wprm-recipe-ingredient">200 g noodles</li></ul></div>
      <div class="wprm-recipe-instruction-group"><ul>
        <li><div class="wprm-recipe-instruction-text">Simmer the stock for 10 minutes.</div></li>
        <li><div class="wprm-recipe-instruction-text">Add noodles and serve.</div></li></ul></div>
    </div>`);
  expect(r.ok).toBe(true);
  expect(r.recipe.recipeIngredient).toHaveLength(3);
  expect(r.sections.ingredients).toEqual([{ title: "Broth", index: 0 }, { title: "Toppings", index: 2 }]);
  expect(r.recipe.recipeInstructions).toHaveLength(2);
});

test("microdata recipe", async ({ page }) => {
  const r = await extract(page, `
    <div itemscope itemtype="https://schema.org/Recipe"><h1 itemprop="name">Shortbread</h1>
      <span itemprop="recipeYield">36 cookies</span>
      <ul><li itemprop="recipeIngredient">130 g flour</li><li itemprop="recipeIngredient">225 g butter</li></ul>
      <div itemprop="recipeInstructions"><p>Mix.</p><p>Chill for 1 hour.</p><p>Bake 12 minutes.</p></div>
    </div>`);
  expect(r.ok).toBe(true);
  expect(r.source).toBe("microdata");
  expect(r.recipe.name).toBe("Shortbread");
  expect(r.recipe.recipeIngredient).toEqual(["130 g flour", "225 g butter"]);
  expect(r.recipe.recipeInstructions).toHaveLength(3);
});

test("a page that is not a recipe returns its text for AI import", async ({ page }) => {
  const r = await extract(page, `<article><h1>חדשות</h1><p>כתבה בלי מתכון בכלל.</p></article>`);
  expect(r.ok).toBe(false);
  expect(r.text).toContain("כתבה בלי מתכון");
});
