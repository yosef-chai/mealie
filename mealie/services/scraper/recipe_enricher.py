"""משלים מתכון שנקרא מאתר בעזרת מבנה הדף: כותרות מקטעים, זמני שלבים, כמות, כלים, תמונות, תגיות וקרדיט."""

from __future__ import annotations

import re
from typing import Any

from mealie.core.root_logger import get_logger
from mealie.lang.providers import Translator, get_locale_provider
from mealie.schema.recipe.recipe import RecipeDraft
from mealie.schema.recipe.recipe_ingredient import RecipeIngredient
from mealie.schema.recipe.recipe_notes import RecipeNote
from mealie.schema.recipe.recipe_step import RecipeStep

from . import cleaner
from . import recipe_text as rt
from .page_analysis import Alignment, Line, PageAnalysis, image_identity
from .scraped_extras import ScrapedExtras

logger = get_logger("recipe-scraper")

MAX_IMAGE_CANDIDATES = 6
MAX_GALLERY_IMAGES = 8

# קטגוריות ניווט של אתרים שאין בהן מידע על המתכון
_NAVIGATION_CATEGORY = re.compile(
    r"^(?:מתכונים\s+)?(?:אחרונים|חדשים|מומלצים|פופולריים|נבחרים|כללי)$|^(?:ה)?מתכונים\s+(?:ה)?(?:אחרונים|חדשים|מומלצים)$|"
    r"^(?:latest|recent|new|featured|popular|trending|uncategori[sz]ed|general|all)(?:\s+recipes)?$",
    re.IGNORECASE,
)
_PREP_IN_TEXT = re.compile(
    r"זמן\s+(?:ה)?הכנה\s*:?\s*(?P<value>[^.,\n]{2,30})|(?:prep|total)\s+time\s*:?\s*(?P<en>[^.,\n]{2,30})", re.I
)


# ---------------------------------------------------------------------------
# מצרכים


def _ingredient_texts(recipe: RecipeDraft) -> list[str]:
    return [(ing.note or ing.display or "").strip() for ing in recipe.recipe_ingredient]


def build_ingredients(lines: list[Line]) -> list[RecipeIngredient]:
    """שורות עם כותרות הופכות למצרכים, והכותרת נשמרת על המצרך הראשון בקבוצה."""
    ingredients: list[RecipeIngredient] = []
    title = ""
    for line in lines:
        text = cleaner.clean_string(rt.strip_shortcodes(line.text)[0])
        if not text or rt.is_junk_line(text) or rt.generic_heading(text):
            continue
        if line.heading:
            title = rt.heading_text(text)
            continue
        ingredients.append(RecipeIngredient(title=title or None, note=text))
        title = ""
    return ingredients


def _ingredient_lines(texts: list[str], titles: list[str], alignment: Alignment | None) -> list[Line]:
    lines: list[Line] = []
    for index, text in enumerate(texts):
        if alignment and (title := alignment.titles.get(index)):
            lines.append(Line(title, heading=True))
        elif titles[index]:
            lines.append(Line(titles[index], heading=True))
        marked = bool(alignment and index in alignment.marked_headings and len(text.split()) <= 8)
        has_amount = bool(re.match(r"^\s*[\d½¼¾⅓⅔]", text))
        heading = (marked and not has_amount) or rt.looks_like_section_heading(text)
        lines.append(Line(text, heading=heading))
    if alignment:
        lines.extend(alignment.extras)

    # כותרת בסוף הרשימה היא בעצם מצרך
    if lines and lines[-1].heading:
        lines[-1] = Line(lines[-1].text)
    return lines


# ---------------------------------------------------------------------------
# שלבים


def _numbered_follows(texts: list[str], index: int) -> bool:
    return index + 1 < len(texts) and rt.starts_numbered(texts[index + 1], 1) and not rt.starts_numbered(texts[index])


def _short_label(text: str) -> bool:
    return len(text.split()) <= 4 and not re.search(r"[.!?,]$|\d", text)


def build_steps(steps: list[RecipeStep], alignment: Alignment | None = None) -> list[RecipeStep]:
    """מזהה כותרות מקטעים בין השלבים, מסיר מספור וכותרות כלליות, ומשלים זמן לכל שלב."""
    texts = [rt.strip_shortcodes(step.text or "")[0].strip() for step in steps]
    result: list[RecipeStep] = []
    pending = ""
    last_content = max((i for i, t in enumerate(texts) if t and not rt.is_junk_line(t)), default=-1)

    for index, (step, text) in enumerate(zip(steps, texts, strict=True)):
        text = cleaner.clean_string(text) if "<" in text else text
        if not text or rt.is_junk_line(text) or rt.generic_heading(text) or rt.generic_step_name(text):
            continue

        marked = bool(alignment and index in alignment.marked_headings and _short_label(text.rstrip(":")))
        heading = (
            rt.looks_like_section_heading(text) or marked or (_numbered_follows(texts, index) and _short_label(text))
        )
        if heading and index < last_content:
            pending = rt.heading_text(text)
            continue

        title = step.title or (alignment.titles.get(index, "") if alignment else "") or pending
        pending = ""
        summary = step.summary if step.summary and not rt.generic_step_name(step.summary) else ""
        clean_text = rt.strip_step_number(text)
        duration = step.duration_seconds or rt.step_duration(clean_text)

        result.append(
            step.model_copy(
                update={
                    "title": title,
                    "summary": summary,
                    "text": clean_text,
                    "duration_seconds": duration,
                }
            )
        )
    return result


def _split_lines(steps: list[RecipeStep]) -> list[str]:
    lines: list[str] = []
    for step in steps:
        text, _ = rt.strip_shortcodes(step.text or "")
        lines += [line.strip() for line in text.splitlines() if line.strip()]
    return lines


def split_article(steps: list[RecipeStep]) -> tuple[list[Line], list[RecipeStep], list[str], float | None] | None:
    """מתכון שכולו טקסט אחד ("מצרכים ל-8 מנות ... אופן הכנה: ...") מתפצל למצרכים ולשלבים.

    מחזיר מצרכים, שלבים, שורות הפתיח ומספר מנות, או None כשאין בטקסט שני המקטעים.
    """
    lines = _split_lines(steps)
    kinds = [rt.generic_heading(line) for line in lines]
    try:
        start = kinds.index("ingredients")
        end = kinds.index("instructions", start + 1)
    except ValueError:
        return None

    ingredient_lines = [
        Line(line, heading=rt.looks_like_section_heading(line))
        for line in lines[start + 1 : end]
        if not rt.is_junk_line(line)
    ]
    if not any(not line.heading for line in ingredient_lines):
        return None

    step_texts = lines[end + 1 :]
    new_steps = [RecipeStep(text=text) for text in step_texts]
    return ingredient_lines, new_steps, lines[:start], rt.servings_in_heading(lines[start])


def _expand_inline_numbering(steps: list[RecipeStep]) -> list[RecipeStep]:
    expanded: list[RecipeStep] = []
    for step in steps:
        parts = rt.split_inline_numbering(step.text or "")
        if len(parts) == 1:
            expanded.append(step.model_copy(update={"text": parts[0]}) if parts[0] != step.text else step)
            continue
        expanded.append(step.model_copy(update={"text": parts[0]}))
        expanded += [RecipeStep(text=part) for part in parts[1:]]
    return expanded


def _refine_single_step(steps: list[RecipeStep], page: PageAnalysis) -> list[RecipeStep]:
    """שלב יחיד וארוך שבדף מחולק לכמה שלבים. משתמשים בחלוקה של הדף כשהיא מכסה את אותו טקסט."""
    if len(steps) != 1 or len(steps[0].text or "") < 200:
        return steps
    section = [line for line in page.section("instructions") if not line.heading]
    if len(section) < 2:
        return steps

    blob = set(rt.comparable(rt.strip_shortcodes(steps[0].text)[0]).split())
    words = set(rt.comparable(" ".join(line.text for line in section)).split())
    if not words or len(words & blob) / len(words) < 0.7:
        return steps
    return [RecipeStep(text=line.text) for line in page.section("instructions")]


# ---------------------------------------------------------------------------
# זמנים מתוך ה-structured data של השלבים


def _raw_step_durations(raw_instructions: Any) -> dict[str, int]:
    """זמן לכל שלב לפי HowToStep.timeRequired / performTime / totalTime, לפי הטקסט שלו."""
    durations: dict[str, int] = {}

    def walk(value: Any) -> None:
        if isinstance(value, list):
            for item in value:
                walk(item)
        elif isinstance(value, dict):
            if "itemListElement" in value:
                walk(value["itemListElement"])
            text = value.get("text")
            if isinstance(text, str):
                for key in ("timeRequired", "performTime", "totalTime", "duration"):
                    if seconds := cleaner.clean_duration(value.get(key)):
                        durations[rt.comparable(cleaner.clean_string(text))] = seconds
                        break

    walk(raw_instructions)
    return durations


def _raw_step_images(raw_instructions: Any) -> list[str]:
    urls: list[str] = []

    def walk(value: Any) -> None:
        if isinstance(value, list):
            for item in value:
                walk(item)
        elif isinstance(value, dict):
            if "itemListElement" in value:
                walk(value["itemListElement"])
            image = value.get("image")
            for url in cleaner.clean_image(image, default="") if image else []:
                if url:
                    urls.append(url)

    walk(raw_instructions)
    return urls


# ---------------------------------------------------------------------------
# תגיות, קטגוריות וכלים


def clean_tag_list(values: list[str], recipe_name: str) -> list[str]:
    name = rt.comparable(recipe_name)
    seen: set[str] = set()
    tags: list[str] = []
    for value in values:
        tag = " ".join(cleaner.clean_string(value).split()).strip(" #,")
        key = rt.comparable(tag)
        # תגית שהיא בעצם שם המתכון ("עוגיות הבית של פסח (ללא גלוטן)") אינה מתייגת דבר
        is_name = bool(name) and (key == name or (len(name.split()) >= 2 and key.startswith(name)))
        if not tag or not key or key in seen or is_name or len(tag) > 50 or key.isdigit():
            continue
        seen.add(key)
        tags.append(tag)
    return tags


def _split_values(value: Any) -> list[str]:
    if isinstance(value, str):
        return [part for part in re.split(r"[,،;|]", value) if part.strip()]
    if isinstance(value, list):
        return [part for item in value for part in _split_values(item)]
    if isinstance(value, dict):
        return _split_values(value.get("name"))
    return []


def schema_tools(value: Any) -> list[str]:
    """כלים מ-schema.org: טקסט, HowToTool או רשימה של שניהם."""
    names: list[str] = []
    for item in value if isinstance(value, list) else [value]:
        name = item.get("name") or item.get("text") if isinstance(item, dict) else item
        if isinstance(name, str) and (name := cleaner.clean_string(name)) and len(name) <= 60:
            names.append(name)
    return names


def merge_tools(extras: ScrapedExtras, names: list[str]) -> None:
    tools = list(extras._tools)
    existing = {rt.comparable(tool) for tool in tools}
    for name in names:
        name = " ".join(name.split()).strip(" -•:")
        if name and (key := rt.comparable(name)) and key not in existing:
            existing.add(key)
            tools.append(name)
    extras.set_tools(tools)


def recipe_is_hebrew(recipe: RecipeDraft) -> bool:
    sample = " ".join(
        [recipe.name or "", *_ingredient_texts(recipe)[:5], *[s.text for s in recipe.recipe_instructions[:3]]]
    )
    return rt.is_hebrew(sample)


def finish_recipe(recipe: RecipeDraft, extras: ScrapedExtras) -> RecipeDraft:
    """השלמות שמתאימות לכל מקור: זמן לשלב שאין לו, וכלים שהשלבים מזכירים."""
    for step in recipe.recipe_instructions:
        if not step.duration_seconds and step.text:
            step.duration_seconds = rt.step_duration(step.text)

    texts = [step.text for step in recipe.recipe_instructions if step.text]
    if texts:
        merge_tools(extras, [tool.name for tool in recipe.tools] + rt.find_tools(texts, recipe_is_hebrew(recipe)))
    return recipe


# ---------------------------------------------------------------------------
# קרדיט


def translate(translator: Translator, key: str, **kwargs: str) -> str:
    """תרגום עם נפילה לאנגלית, עד ש-Crowdin מוסיף את המפתח לשאר השפות."""
    value = translator.t(key, **kwargs)
    if value == key:
        value = get_locale_provider("en-US").t(key, **kwargs)
    return value


def credit_note(
    page: PageAnalysis, translator: Translator, url: str | None, extra_photographers: list[str]
) -> RecipeNote | None:
    credit = page.credit()
    photographers = list(credit.photographers)
    for name in extra_photographers:
        if name not in photographers:
            photographers.append(name)

    lines: list[str] = []
    site = credit.site_name
    authors = [a for a in credit.authors if rt.comparable(a) != rt.comparable(site)]
    if authors:
        lines.append(f"{translate(translator, 'recipe.import-credit.recipe-by')}: {', '.join(authors[:4])}")
    if photographers:
        lines.append(f"{translate(translator, 'recipe.import-credit.photo-by')}: {', '.join(photographers[:4])}")
    if site or url:
        source = " · ".join(part for part in (site, url or "") if part)
        lines.append(f"{translate(translator, 'recipe.import-credit.source')}: {source}")

    if not (authors or photographers or site):
        return None
    return RecipeNote(title=translate(translator, "recipe.import-credit.title"), text="\n".join(lines))


# ---------------------------------------------------------------------------
# נקודת הכניסה


def enrich_recipe(
    recipe: RecipeDraft,
    extras: ScrapedExtras,
    page: PageAnalysis,
    translator: Translator,
    schema: dict | None = None,
    url: str | None = None,
) -> RecipeDraft:
    """משפר מתכון שנבנה מ-structured data בעזרת הדף עצמו. כל שלב נכשל לבד בלי להפיל את הייבוא."""
    schema = schema or page.recipe_node or {}
    captions: list[str] = []
    for step in recipe.recipe_instructions:
        captions += rt.strip_shortcodes(step.text or "")[1]

    text_servings: list[float] = []
    for name, task in (
        ("structure", lambda: _enrich_structure(recipe, page, schema, text_servings)),
        ("yield", lambda: _enrich_yield(recipe, page, schema, text_servings)),
        ("times", lambda: _enrich_times(recipe, page)),
        ("organizers", lambda: _enrich_organizers(recipe, extras, page, schema)),
        ("tools", lambda: _enrich_tools(extras, page, schema)),
        ("images", lambda: _enrich_images(recipe, extras, page, schema)),
        ("credit", lambda: _enrich_credit(recipe, page, translator, url, captions)),
    ):
        try:
            task()
        except Exception:
            logger.exception(f"Could not enrich the scraped recipe ({name}), keeping it as parsed")

    return recipe


def _enrich_structure(recipe: RecipeDraft, page: PageAnalysis, schema: dict, text_servings: list[float]) -> None:
    steps = list(recipe.recipe_instructions)
    texts = _ingredient_texts(recipe)

    if len([t for t in texts if t]) <= 1 and (article := split_article(steps)):
        ingredient_lines, steps, preface, servings = article
        recipe.recipe_ingredient = build_ingredients(ingredient_lines)
        if servings:
            text_servings.append(servings)
        preface_text = "\n".join(line for line in preface if not rt.photo_credits(line))
        for match in _PREP_IN_TEXT.finditer(preface_text):
            if not recipe.total_time_seconds and (
                seconds := rt.duration_value(match.group("value") or match.group("en"))
            ):
                recipe.total_time_seconds = seconds
        if not recipe.description and preface_text:
            recipe.description = cleaner.clean_string(_PREP_IN_TEXT.sub("", preface_text))[:500]
    elif not any(texts) and (section := page.section("ingredients")):
        recipe.recipe_ingredient = build_ingredients(section)
    else:
        titles = [ing.title or "" for ing in recipe.recipe_ingredient]
        alignment = page.align(texts, "ingredients") if page.blocks else None
        lines = _ingredient_lines(texts, titles, alignment)
        rebuilt = build_ingredients(lines)
        if rebuilt:
            recipe.recipe_ingredient = rebuilt

    steps = _refine_single_step(_expand_inline_numbering(steps), page)
    step_alignment = (
        page.align([rt.strip_shortcodes(s.text or "")[0] for s in steps], "instructions") if page.blocks else None
    )
    durations = _raw_step_durations(schema.get("recipeInstructions"))
    for step in steps:
        if not step.duration_seconds and (seconds := durations.get(rt.comparable(step.text or ""))):
            step.duration_seconds = seconds
    if rebuilt_steps := build_steps(steps, step_alignment):
        recipe.recipe_instructions = rebuilt_steps

    if not recipe.description:
        recipe.description = cleaner.clean_string(page.description())


def _enrich_yield(recipe: RecipeDraft, page: PageAnalysis, schema: dict, text_servings: list[float]) -> None:
    """הכמות כפי שהאתר כתב אותה. recipe_scrapers הופך כל מספר ל-"servings", גם "2 פיצות".

    הערכים נקבעים כאן במלואם, ו-cleaner.clean לא מחשב אותם מחדש.
    """
    if text_servings:
        # כותרת גלויה ("מצרכים ל-8 מנות") אמינה יותר מערך ברירת מחדל ב-structured data
        parsed: rt.YieldValue | None = rt.YieldValue(servings=text_servings[0])
    else:
        raw = schema.get("recipeYield")
        parsed = rt.parse_yield(raw) if raw not in (None, "", []) else None
        if parsed is None and (label := page.labeled_values().get("servings")):
            parsed = rt.parse_yield(label)
    if parsed is None:
        return

    recipe.recipe_servings = parsed.servings
    recipe.recipe_yield_quantity = parsed.quantity
    recipe.recipe_yield = parsed.text


def _enrich_times(recipe: RecipeDraft, page: PageAnalysis) -> None:
    if recipe.prep_time_seconds and recipe.perform_time_seconds and recipe.total_time_seconds:
        return
    labels = page.labeled_values()
    for key, field in (
        ("prep", "prep_time_seconds"),
        ("cook", "perform_time_seconds"),
        ("total", "total_time_seconds"),
    ):
        if getattr(recipe, field) or getattr(recipe, field.removesuffix("_seconds")) or key not in labels:
            continue
        if seconds := rt.duration_value(labels[key]):
            setattr(recipe, field, seconds)


def _enrich_organizers(recipe: RecipeDraft, extras: ScrapedExtras, page: PageAnalysis, schema: dict) -> None:
    tags = list(extras._tags) or page.article_tags()
    tags += _split_values(schema.get("recipeCuisine"))
    extras.set_tags(clean_tag_list(tags, recipe.name or ""))

    categories = [c for c in extras._categories if not _NAVIGATION_CATEGORY.match(c.strip())]
    extras.set_categories(clean_tag_list(categories, recipe.name or ""))


def _enrich_tools(extras: ScrapedExtras, page: PageAnalysis, schema: dict) -> None:
    names = schema_tools(schema.get("tool")) if schema.get("tool") else []
    names += [line.text for line in page.section("equipment") if not line.heading and len(line.text) <= 60]
    merge_tools(extras, names)


def _enrich_images(recipe: RecipeDraft, extras: ScrapedExtras, page: PageAnalysis, schema: dict) -> None:
    candidates = [c.url for c in page.image_candidates()]
    current = recipe.image[0] if isinstance(recipe.image, list) and recipe.image else recipe.image
    if not isinstance(current, str) or current in (cleaner.NO_IMAGE, ""):
        current = None
    if current:
        # התמונה שכבר נבחרה נשארת ברשימה, אבל תמונה גדולה יותר קודמת לה
        candidates = candidates + [current] if candidates else [current]
    candidates = list(dict.fromkeys(candidates))[:MAX_IMAGE_CANDIDATES]
    if not candidates:
        return

    recipe.image = candidates[0]
    extras.set_images(candidates[1:])

    main = image_identity(candidates[0])
    gallery: list[str] = []
    seen = {main}
    raw_images = cleaner.clean_image(page.resolve(schema.get("image")), default="") if schema.get("image") else []
    step_images = _raw_step_images(schema.get("recipeInstructions"))

    step_alignment = None
    texts = [s.text for s in recipe.recipe_instructions if s.text]
    if texts and page.blocks:
        step_alignment = page.align(texts, "instructions")
    content_images = (
        page.images_between(min(step_alignment.matched.values()), max(step_alignment.matched.values()))
        if step_alignment and step_alignment.matched
        else []
    )
    for url in [*raw_images, *step_images, *content_images]:
        absolute = page.absolute(url) if url else ""
        if absolute and (identity := image_identity(absolute)) not in seen:
            seen.add(identity)
            gallery.append(absolute)
    extras.set_gallery(gallery[:MAX_GALLERY_IMAGES])


def _enrich_credit(
    recipe: RecipeDraft, page: PageAnalysis, translator: Translator, url: str | None, captions: list[str]
) -> None:
    photographers = [name for caption in captions for name in rt.photo_credits(caption)]
    note = credit_note(page, translator, url or recipe.org_url, photographers)
    if note is None:
        return
    if not any(existing.title == note.title for existing in recipe.notes):
        recipe.notes = [*recipe.notes, note]
