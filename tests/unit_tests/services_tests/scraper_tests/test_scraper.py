from pathlib import Path

import pytest

from mealie.lang.providers import get_locale_provider
from mealie.schema.recipe.recipe import RecipeDraft
from mealie.services.recipe.recipe_data_service import RecipeDataService
from mealie.services.scraper import scraper
from mealie.services.scraper.recipe_scraper import RecipeScraper
from mealie.services.scraper.scraped_extras import ScrapedExtras


@pytest.mark.asyncio
async def test_create_from_html_truncates_long_slug(monkeypatch):
    """A recipe scraped with an excessively long name must yield a slug short enough
    to be used as a filesystem directory name.

    Regression test for GH #7662: scraping (e.g. an Instagram caption) dumped the entire
    text into the recipe name. The scraper used a raw ``slugify`` instead of the shared
    ``create_recipe_slug`` helper, so the persisted slug could exceed the OS 255-byte
    filename limit. Editing/saving the recipe then raised an uncaught
    ``OSError: [Errno 36] File name too long`` in ``RecipeService.check_assets``.
    """
    # ~1300 characters, mirroring the caption-as-title data from the bug report
    long_name = "High Protein Low Calorie Recipes Smash Or Pass " * 28

    async def fake_scrape(self, url, html=None, on_progress=None, **kwargs):
        return RecipeDraft(name=long_name), ScrapedExtras()

    monkeypatch.setattr(RecipeScraper, "scrape", fake_scrape)

    translator = get_locale_provider()
    recipe, _ = await scraper.create_from_html(
        "https://example.com/recipe",
        repos=None,  # type: ignore[arg-type]
        translator=translator,
        html="<html></html>",
    )

    # The full title is preserved (name column is unbounded)...
    assert recipe.name == long_name
    # ...but the slug is truncated to a filesystem-safe length.
    assert 0 < len(recipe.slug) <= 250


@pytest.mark.parametrize(
    ("scraped_image", "download_result", "expect_key"),
    [
        pytest.param("https://example.com/img.jpg", Path("/images/original.webp"), True, id="image-downloaded"),
        pytest.param("https://example.com/img.jpg", None, False, id="download-failed"),
        pytest.param("no image", None, False, id="placeholder-url"),
        pytest.param(None, None, False, id="no-image-url"),
    ],
)
@pytest.mark.asyncio
async def test_finalize_only_stamps_a_cache_key_when_an_image_landed(
    monkeypatch, scraped_image, download_result, expect_key
):
    """`recipe.image` must stay empty unless a file actually reached the disk.

    The frontend reads the presence of this cache key as "this recipe has a picture" and
    skips the request entirely when it is empty. Stamping a key regardless of whether the
    download succeeded is what left recipes asking the media route for a file that 404s on
    every single render (mealie-recipes/mealie#8271, GH #4804).
    """

    async def fake_scrape_image(self, image_url):
        return download_result

    monkeypatch.setattr(RecipeDataService, "scrape_image", fake_scrape_image)

    recipe = await scraper.finalize_scraped_recipe(
        RecipeDraft(name="Test Recipe", image=scraped_image),
        get_locale_provider(),
    )

    assert bool(recipe.image) is expect_key


@pytest.mark.asyncio
async def test_finalize_falls_back_to_the_next_image_candidate(monkeypatch):
    """כשהתמונה הראשונה לא נטענת (למשל מקור של תמונה ממוזערת שלא קיים), עוברים לבאה בתור."""
    tried: list[str] = []

    async def fake_scrape_image(self, image_url):
        tried.append(image_url)
        if image_url.endswith("broken.jpg"):
            raise ValueError("404")
        return Path("/images/original.webp") if image_url.endswith("good.jpg") else None

    monkeypatch.setattr(RecipeDataService, "scrape_image", fake_scrape_image)
    extras = ScrapedExtras()
    extras.set_images(
        ["https://example.com/missing.jpg", "https://example.com/good.jpg", "https://example.com/never.jpg"]
    )

    recipe = await scraper.finalize_scraped_recipe(
        RecipeDraft(name="Test Recipe", image="https://example.com/broken.jpg"), get_locale_provider(), extras=extras
    )

    assert recipe.image
    assert tried == [
        "https://example.com/broken.jpg",
        "https://example.com/missing.jpg",
        "https://example.com/good.jpg",
    ]


@pytest.mark.asyncio
async def test_finalize_saves_the_gallery_as_assets(monkeypatch):
    async def fake_scrape_image(self, image_url):
        return None

    async def fake_fetch_image(self, image_url, max_bytes=None):
        if "video" in image_url:
            raise ValueError("not an image")
        return (b"\x89PNG", "png") if image_url.endswith(".png") else (b"<svg/>", "svg+xml")

    monkeypatch.setattr(RecipeDataService, "scrape_image", fake_scrape_image)
    monkeypatch.setattr(RecipeDataService, "fetch_image", fake_fetch_image)
    extras = ScrapedExtras()
    extras.set_gallery(["https://example.com/step-1.png", "https://example.com/video", "https://example.com/icon.svg"])

    recipe = await scraper.finalize_scraped_recipe(
        RecipeDraft(name="Test Recipe"), get_locale_provider(), extras=extras
    )

    # תמונה שנכשלה או בפורמט שלא נשמר מדולגת, בלי להפיל את הייבוא
    assert [asset.name for asset in recipe.assets] == ["Image 2"]
    asset_file = RecipeDataService(recipe.id).dir_assets / recipe.assets[0].file_name  # type: ignore[arg-type, operator]
    assert asset_file.read_bytes() == b"\x89PNG"
