"""ייבוא מאתרים: דפים קטנים שמחקים מבנים שנפוצים באתרי מתכונים בעברית ובאנגלית."""

import json

import pytest

from mealie.lang.providers import get_locale_provider
from mealie.schema.recipe.recipe import RecipeDraft
from mealie.services.scraper.page_analysis import PageAnalysis
from mealie.services.scraper.recipe_scraper import RecipeScraper
from mealie.services.scraper.scraped_extras import ScrapedExtras
from mealie.services.scraper.scraper_strategies import RecipeScraperHtml, RecipeScraperPackage

URL = "https://recipes.example.com/recipe/cake/"


def page(body: str, *ld: dict, head: str = "") -> str:
    documents = [json.dumps({"@context": "https://schema.org"} | node, ensure_ascii=False) for node in ld]
    scripts = "".join(f'<script type="application/ld+json">{document}</script>' for document in documents)
    return f"<!DOCTYPE html><html><head><title>מתכון | המטבח</title>{head}{scripts}</head><body>{body}</body></html>"


async def scrape(html: str, url: str = URL) -> tuple[RecipeDraft, ScrapedExtras]:
    scraper = RecipeScraper(None, get_locale_provider(), scrapers=[RecipeScraperPackage, RecipeScraperHtml])  # type: ignore[arg-type]
    recipe, extras = await scraper._scrape_with_strategies(scraper.scrapers, url, html, url)
    assert recipe is not None and extras is not None
    return recipe, extras


def ingredient_groups(recipe: RecipeDraft) -> list[tuple[str | None, str]]:
    return [(ing.title, ing.note) for ing in recipe.recipe_ingredient]


@pytest.mark.asyncio
async def test_bold_group_headings_in_the_page_become_ingredient_and_step_sections():
    """ה-JSON-LD שטוח, אבל בדף יש כותרות מודגשות לכל קבוצה."""
    recipe_ld = {
        "@context": "https://schema.org",
        "@type": "Recipe",
        "name": "עוגת תפוחים",
        "recipeIngredient": ["2 כוסות קמח", "חצי כוס שמן", "6 תפוחים", "חצי כוס סוכר"],
        "recipeInstructions": [
            {"@type": "HowToStep", "text": "לערבב את הקמח עם השמן עד שמתקבל בצק פירורים."},
            {"@type": "HowToStep", "text": "לחתוך את התפוחים ולערבב עם הסוכר."},
            {"@type": "HowToStep", "text": "לאפות 40-50 דקות עד שהבצק מזהיב."},
        ],
    }
    body = """
    <h1>עוגת תפוחים</h1>
    <div class="recipe-ingredients"><h3>רכיבים</h3>
      <p><span><strong>לבצק</strong>:</span></p><p>2 כוסות קמח</p><p>חצי כוס שמן</p>
      <p><strong>למילוי תפוחים:</strong></p><p>6 תפוחים</p><p>חצי כוס סוכר</p>
    </div>
    <div class="recipe-method"><h3>אופן ההכנה</h3>
      <p><b>בצק:</b></p><ul><li>לערבב את הקמח עם השמן עד שמתקבל בצק פירורים.</li></ul>
      <p><b>מילוי:</b></p><ul><li>לחתוך את התפוחים ולערבב עם הסוכר.</li><li>לאפות 40-50 דקות עד שהבצק מזהיב.</li></ul>
    </div>"""

    recipe, _ = await scrape(page(body, recipe_ld))

    assert ingredient_groups(recipe) == [
        ("לבצק", "2 כוסות קמח"),
        (None, "חצי כוס שמן"),
        ("למילוי תפוחים", "6 תפוחים"),
        (None, "חצי כוס סוכר"),
    ]
    assert [step.title for step in recipe.recipe_instructions] == ["בצק", "מילוי", ""]
    assert recipe.recipe_instructions[2].duration_seconds == 40 * 60


@pytest.mark.asyncio
async def test_group_missing_from_structured_data_is_added_from_the_page():
    recipe_ld = {
        "@type": "Recipe",
        "name": "עוגת תפוזים",
        "recipeIngredient": ["3 ביצים", "כוס סוכר", "כוס וחצי קמח"],
        "recipeInstructions": [
            {"@type": "HowToStep", "name": "null 1", "text": "מקציפים במיקסר ביצים וסוכר למשך 3-4 דקות."},
            {"@type": "HowToStep", "name": "null 2", "text": "מוסיפים קמח ואופים כ-30 דקות בתבנית אינגליש קייק."},
        ],
    }
    body = """
    <div class="ingredients"><h3>המרכיבים</h3>
      <div><p class="list_name">לתבנית אחת:</p><ul><li>3 ביצים</li><li>כוס סוכר</li><li>כוס וחצי קמח</li></ul></div>
      <div><p class="list_name">לקישוט:</p><ul><li>אבקת סוכר</li></ul></div>
    </div>
    <div><h3>אופן ההכנה</h3><p>מקציפים במיקסר ביצים וסוכר למשך 3-4 דקות.</p></div>"""

    recipe, extras = await scrape(page(body, recipe_ld))

    assert ingredient_groups(recipe)[-1] == ("לקישוט", "אבקת סוכר")
    assert ingredient_groups(recipe)[0] == ("לתבנית אחת", "3 ביצים")
    # "null 1" הוא שם שלב בלי מידע
    assert all(not step.summary for step in recipe.recipe_instructions)
    assert [step.duration_seconds for step in recipe.recipe_instructions] == [180, 1800]
    assert extras._tools == ["מיקסר", "תבנית אינגליש קייק"]


@pytest.mark.asyncio
async def test_headings_and_numbering_inside_a_text_block_of_instructions():
    recipe_ld = {
        "@type": "Recipe",
        "name": "אורז וכופתה",
        "recipeIngredient": ["2 כוסות אורז", "2 תפוחי אדמה"],
        "recipeInstructions": (
            "אופן ההכנה:\nלאורז\n1. בסיר בינוני מחממים שמן.\n2. מוסיפים אורז ומים ומבשלים 20 דקות.\n"
            "לכופתה\n1. מבשלים את תפוחי האדמה.\n2. מטגנים כדורים עד הזהבה.\nצילחות\n1. מגישים חם."
        ),
    }

    recipe, _ = await scrape(page("<h1>אורז וכופתה</h1>", recipe_ld))

    steps = [(step.title, step.text) for step in recipe.recipe_instructions]
    assert steps == [
        ("לאורז", "בסיר בינוני מחממים שמן."),
        ("", "מוסיפים אורז ומים ומבשלים 20 דקות."),
        ("לכופתה", "מבשלים את תפוחי האדמה."),
        ("", "מטגנים כדורים עד הזהבה."),
        ("צילחות", "מגישים חם."),
    ]
    assert recipe.recipe_instructions[1].duration_seconds == 1200


@pytest.mark.asyncio
async def test_ingredient_headings_inside_the_structured_list():
    recipe_ld = {
        "@type": "Recipe",
        "name": "פיצה",
        "recipeYield": '2 פיצות בקוטר 33 ס"מ',
        "recipeIngredient": [
            "4 כוסות קמח",
            "רוטב מהיר ללא בישול (לערבב הכל בקערית):",
            "400 גרם עגבניות מרוסקות",
            "תוספות",
            "זיתים",
            "ראו עוד תוספות לפיצה",
        ],
        "recipeInstructions": [
            {"@type": "HowToStep", "name": "שלב 1", "text": "לשים את הבצק כ-4 דקות."},
            {"@type": "HowToStep", "name": "שלב 2", "text": "אופים 15 דקות."},
        ],
    }

    recipe, _ = await scrape(page("<h1>פיצה</h1>", recipe_ld))

    assert ingredient_groups(recipe) == [
        (None, "4 כוסות קמח"),
        ("רוטב מהיר ללא בישול (לערבב הכל בקערית)", "400 גרם עגבניות מרוסקות"),
        ("תוספות", "זיתים"),
    ]
    assert [step.summary for step in recipe.recipe_instructions] == ["", ""]
    assert (recipe.recipe_servings, recipe.recipe_yield_quantity, recipe.recipe_yield) == (0, 2, 'פיצות בקוטר 33 ס"מ')


@pytest.mark.asyncio
async def test_pan_size_is_not_read_as_servings():
    recipe_ld = {
        "@type": "Recipe",
        "name": "עוגה",
        "recipeYield": "תבנית ריבועית 23*23",
        "recipeIngredient": ["2 כוסות קמח", "כוס סוכר"],
        "recipeInstructions": ["מערבבים ואופים."],
    }
    recipe, _ = await scrape(page("", recipe_ld))
    assert (recipe.recipe_servings, recipe.recipe_yield_quantity, recipe.recipe_yield) == (0, 0, "תבנית ריבועית 23*23")


@pytest.mark.asyncio
async def test_article_style_recipe_is_split_into_ingredients_and_steps():
    """כל המתכון בתוך שדה ההוראות, כולל כיתוב תמונה עם קרדיט לצלם."""
    recipe_ld = {
        "@type": "Recipe",
        "name": "בורקס דג",
        "author": {"@type": "Person", "name": "סבינה"},
        "recipeYield": 2,
        "recipeInstructions": (
            "מנה מרשימה וקלה להכנה.\n"
            '[caption id="attachment_1" width="580"] צילום: עידן גרוס. עריכה: רונית[/caption]\n'
            "זמן הכנה 50 דקות\nמצרכים ל-8 מנות\n3 כפות שמן זית\n1 בצל\nלציפוי:\n8 עלי פילו\n"
            "אופן הכנה:\nמטגנים את הבצל כ-5 דקות.\nאופים 15 דקות.\nבואו להכין עוד מנה"
        ),
    }

    recipe, _ = await scrape(page("", recipe_ld, head='<meta property="og:site_name" content="ערוץ האוכל">'))

    assert ingredient_groups(recipe) == [(None, "3 כפות שמן זית"), (None, "1 בצל"), ("לציפוי", "8 עלי פילו")]
    assert [step.text for step in recipe.recipe_instructions] == ["מטגנים את הבצל כ-5 דקות.", "אופים 15 דקות."]
    assert recipe.recipe_servings == 8
    assert recipe.total_time_seconds == 50 * 60
    assert recipe.description == "מנה מרשימה וקלה להכנה."
    credit = next(note for note in recipe.notes if note.title == "Credit")
    assert credit.text.splitlines()[:2] == ["Recipe: סבינה", "Photos: עידן גרוס"]
    assert credit.text.splitlines()[2].startswith("Source: ערוץ האוכל · ")


@pytest.mark.asyncio
async def test_page_without_structured_data_is_read_from_its_headings():
    body = """
    <main><h1>עוגת קפה</h1><p class="summary">עוגה רכה</p>
    <ul class="meta"><li>זמן: 15 דקות</li><li>רמת קושי: קל</li></ul>
    <img src="assets/coffee-cake.jpeg" width="520" height="520" alt="עוגת קפה">
    <section><h2>רכיבים</h2><p>מספר מנות במתכון המקורי: 20</p>
      <ul><li>6 ביצים</li><li>2.5 כוסות סוכר</li><li>4 כוסות קמח</li></ul></section>
    <section><h2>אופן ההכנה</h2><ol><li>מקציפים את החלבונים במיקסר.</li><li>אופים 30–35 דקות.</li>
      <li>לדיגוג</li><li>מערבבים את הקפה עם אבקת הסוכר.</li><li>בתאבון!</li></ol></section></main>
    <footer>כל הזכויות שמורות</footer>"""

    recipe, extras = await scrape(page(body), url="https://chef.example.com/recipes/coffee/")

    assert recipe.name == "עוגת קפה"
    assert [ing.note for ing in recipe.recipe_ingredient] == ["6 ביצים", "2.5 כוסות סוכר", "4 כוסות קמח"]
    assert [(step.title, step.text) for step in recipe.recipe_instructions] == [
        ("", "מקציפים את החלבונים במיקסר."),
        ("", "אופים 30–35 דקות."),
        ("לדיגוג", "מערבבים את הקפה עם אבקת הסוכר."),
    ]
    assert recipe.recipe_servings == 20
    assert recipe.total_time_seconds == 15 * 60
    assert recipe.image == "https://chef.example.com/recipes/coffee/assets/coffee-cake.jpeg"
    assert extras._tools == ["מיקסר"]
    assert recipe.notes[-1].text.startswith("Source: המטבח")


@pytest.mark.asyncio
async def test_page_without_a_recipe_is_not_accepted():
    body = "<h1>חדשות</h1><p>אין כאן מתכון</p><h2>מצרכים</h2><p>רק שורה אחת</p>"
    scraper = RecipeScraper(None, get_locale_provider(), scrapers=[RecipeScraperHtml])  # type: ignore[arg-type]
    recipe, _ = await scraper._scrape_with_strategies(scraper.scrapers, URL, page(body), URL)
    assert recipe is None


@pytest.mark.asyncio
async def test_single_long_step_is_split_like_the_page():
    long_step = (
        "אופן הכנה מערבבים את היוגורט והמלח ומעבירים לחיתול בד מעל הכיור. "
        "[caption id=״a״ width=״800״] מערבבים יחד. צילום: עדי[ caption] "
        "משאירים להגיר נוזלים במשך 12 שעות לפחות. ככל שתשאירו יותר, כך הגבינה תתקשה. "
        "מגישים עם שמן זית וזעתר ליד פיתה חמה, או מורחים על כריך של בוקר."
    )
    recipe_ld = {
        "@type": "Recipe",
        "name": "לאבנה",
        "recipeIngredient": ["1.5 ליטר יוגורט", "כף מלח"],
        "recipeInstructions": long_step,
    }
    body = """<div class="content"><h2>אופן הכנה</h2><ol>
      <li>מערבבים את היוגורט והמלח ומעבירים לחיתול בד מעל הכיור.
        <figure><img src="/img/step1.jpg" width="800" height="600">
        <figcaption>מערבבים יחד. צילום: עדי</figcaption></figure></li>
      <li>משאירים להגיר נוזלים במשך 12 שעות לפחות.</li>
      <li>ככל שתשאירו יותר, כך הגבינה תתקשה.</li>
      <li>מגישים עם שמן זית וזעתר ליד פיתה חמה, או מורחים על כריך של בוקר.</li></ol></div>"""

    head = '<meta property="og:image" content="https://recipes.example.com/img/labane.jpg">'
    recipe, extras = await scrape(page(body, recipe_ld, head=head))

    assert recipe.image == "https://recipes.example.com/img/labane.jpg"
    assert len(recipe.recipe_instructions) == 4
    assert recipe.recipe_instructions[1].duration_seconds == 12 * 3600
    assert extras.gallery == ["https://recipes.example.com/img/step1.jpg"]
    assert "בד חיתול" in extras._tools


@pytest.mark.asyncio
async def test_credit_follows_graph_references_and_cleans_the_site_name():
    graph = {
        "@context": "https://schema.org",
        "@graph": [
            {"@type": "Person", "@id": "#chef", "name": "אורלי"},
            {
                "@type": "Recipe",
                "name": "סלט",
                "author": {"@id": "#chef"},
                "image": {"@type": "ImageObject", "url": "https://img.example.com/salad.jpg", "creditText": "דן לב"},
                "recipeIngredient": ["1 מלפפון", "1 עגבנייה"],
                "recipeInstructions": ["חותכים ומערבבים."],
            },
        ],
    }
    head = '<meta property="og:site_name" content="סלט | השולחן">'

    recipe, _ = await scrape(page("", graph, head=head))

    assert recipe.notes[-1].title == "Credit"
    assert recipe.notes[-1].text.splitlines() == ["Recipe: אורלי", "Photos: דן לב", f"Source: השולחן · {URL}"]


@pytest.mark.asyncio
async def test_no_credit_note_without_any_credit():
    recipe_ld = {
        "@context": "https://schema.org",
        "@type": "Recipe",
        "name": "X",
        "recipeIngredient": ["1 a", "2 b"],
        "recipeInstructions": ["Mix."],
    }
    html = f'<html><head><script type="application/ld+json">{json.dumps(recipe_ld)}</script></head><body></body></html>'
    recipe, _ = await scrape(html)
    assert recipe.notes == []


@pytest.mark.asyncio
async def test_tags_tools_and_categories():
    recipe_ld = {
        "@type": "Recipe",
        "name": "עוגה",
        "recipeCategory": "עוגות, מתכונים אחרונים",
        "recipeCuisine": "חלבי",
        "tool": [{"@type": "HowToTool", "name": "מערוך"}, "סכין שף"],
        "recipeIngredient": ["2 כוסות קמח", "כוס חלב"],
        "recipeInstructions": ["מרדדים ואופים בתנור."],
    }
    head = '<meta property="article:tag" content="עוגה"><meta property="article:tag" content="אפייה">'
    body = "<h3>ציוד</h3><ul><li>מסננת</li><li>קערה גדולה</li></ul>"

    recipe, extras = await scrape(page(body, recipe_ld, head=head))

    # בלי keywords לוקחים את התגיות של המאמר, בלי תגית ששווה לשם המתכון
    assert extras._tags == ["אפייה", "חלבי"]
    assert extras._categories == ["עוגות"]
    assert extras._tools == ["מערוך", "סכין שף", "מסננת", "קערה גדולה", "תנור"]


def test_image_candidates_prefer_the_original_over_a_wordpress_thumbnail():
    head = (
        '<meta property="og:image" content="https://cdn.example.com/uploads/2020/04/cheese-300x200.jpg">'
        '<meta property="og:image:width" content="1024">'
    )
    recipe_ld = {"@type": "Recipe", "image": "https://cdn.example.com/uploads/2020/04/cheese-355x236.jpg"}
    analysis = PageAnalysis(page("", recipe_ld, head=head), URL)

    urls = [candidate.url for candidate in analysis.image_candidates()]

    assert urls[0] == "https://cdn.example.com/uploads/2020/04/cheese.jpg"
    assert set(urls[1:]) == {
        "https://cdn.example.com/uploads/2020/04/cheese-355x236.jpg",
        "https://cdn.example.com/uploads/2020/04/cheese-300x200.jpg",
    }


def test_large_declared_image_wins_and_noise_is_skipped():
    recipe_ld = {
        "@type": "Recipe",
        "image": [
            {"@type": "ImageObject", "url": "https://img.example.com/a-small.jpg", "width": 300, "height": 200},
            {"@type": "ImageObject", "url": "https://img.example.com/a-big.jpg", "width": 1600, "height": 900},
        ],
    }
    head = '<meta property="og:image" content="https://img.example.com/site-logo.png">'
    urls = [c.url for c in PageAnalysis(page("", recipe_ld, head=head), URL).image_candidates()]
    assert urls == ["https://img.example.com/a-big.jpg", "https://img.example.com/a-small.jpg"]


def test_image_size_given_as_quantitative_value():
    recipe_ld = {
        "@type": "Recipe",
        "image": [
            {"@type": "ImageObject", "url": "https://img.example.com/a.jpg", "width": 300, "height": 200},
            {
                "@type": "ImageObject",
                "url": "https://img.example.com/b.jpg",
                "width": {"@type": "QuantitativeValue", "value": 1600},
                "height": {"@type": "QuantitativeValue", "value": 900},
            },
        ],
    }
    urls = [c.url for c in PageAnalysis(page("", recipe_ld), URL).image_candidates()]
    assert urls == ["https://img.example.com/b.jpg", "https://img.example.com/a.jpg"]


def test_alignment_keeps_a_repeated_ingredient_in_its_own_group():
    """אותו מצרך בשתי קבוצות ("10 גרם כמון" לאורז וגם לרוטב) לא נבלע בבלוק הראשון."""
    body = """<h3>מצרכים:</h3>
      <p><strong>לאורז</strong></p><ul><li>10 גרם כמון</li><li>2 כוסות אורז</li></ul>
      <p><strong>לרוטב</strong></p><ul><li>10 גרם כמון</li><li>4 עגבניות</li></ul>"""
    alignment = PageAnalysis(page(body), URL).align(
        ["10 גרם כמון", "2 כוסות אורז", "10 גרם כמון", "4 עגבניות"], "ingredients"
    )
    assert alignment is not None
    assert len(set(alignment.matched.values())) == 4
    assert alignment.titles == {0: "לאורז", 2: "לרוטב"}


@pytest.mark.asyncio
async def test_enrichment_failure_keeps_the_parsed_recipe(monkeypatch):
    from mealie.services.scraper import recipe_enricher

    def broken(*args, **kwargs):
        raise RuntimeError("boom")

    monkeypatch.setattr(recipe_enricher, "_enrich_structure", broken)
    monkeypatch.setattr(recipe_enricher, "_enrich_images", broken)
    recipe_ld = {"@type": "Recipe", "name": "X", "recipeIngredient": ["1 a", "2 b"], "recipeInstructions": ["Mix."]}

    recipe, _ = await scrape(page("", recipe_ld))

    assert [ing.note for ing in recipe.recipe_ingredient] == ["1 a", "2 b"]
    assert [step.text for step in recipe.recipe_instructions] == ["Mix."]
