import asyncio
from collections.abc import Awaitable, Callable
from enum import StrEnum
from re import search as regex_search
from uuid import uuid4

from fastapi import HTTPException, status

from mealie.core.root_logger import get_logger
from mealie.lang.providers import Translator
from mealie.pkgs import cache
from mealie.repos.repository_factory import AllRepositories
from mealie.schema.recipe import RecipeDraft
from mealie.schema.recipe.recipe import create_recipe_slug
from mealie.schema.recipe.recipe_asset import RecipeAsset
from mealie.services.recipe.recipe_data_service import RecipeDataService
from mealie.services.scraper.scraped_extras import ScrapedExtras

from .cleaner import NO_IMAGE
from .recipe_enricher import translate
from .recipe_scraper import RecipeScraper


class ParserErrors(StrEnum):
    BAD_RECIPE_DATA = "BAD_RECIPE_DATA"
    NO_RECIPE_DATA = "NO_RECIPE_DATA"
    CONNECTION_ERROR = "CONNECTION_ERROR"


async def create_from_html(
    url: str,
    repos: AllRepositories,
    translator: Translator,
    html: str | None = None,
    on_progress: Callable[[str], Awaitable[None]] | None = None,
    include_tags: bool = False,
    include_categories: bool = False,
) -> tuple[RecipeDraft, ScrapedExtras | None]:
    """Main entry point for generating a recipe from a URL. Pass in a URL and
    a RecipeDraft object will be returned if successful. Optionally pass in the HTML to skip fetching it.

    Args:
        url (str): a valid string representing a URL
        html (str | None): optional HTML string to skip network request. Defaults to None.
        on_progress: optional async callable invoked with a status message at each stage.

    Returns:
        RecipeDraft: RecipeDraft Object
    """
    scraper = RecipeScraper(repos, translator)

    if not html:
        extracted_url = regex_search(r"(https?://|www\.)[^\s]+", url)
        if not extracted_url:
            raise HTTPException(status.HTTP_400_BAD_REQUEST, {"details": ParserErrors.BAD_RECIPE_DATA.value})
        url = extracted_url.group(0)

    new_recipe, extras = await scraper.scrape(
        url,
        html,
        on_progress=on_progress,
        include_tags=include_tags,
        include_categories=include_categories,
    )

    if not new_recipe:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, {"details": ParserErrors.BAD_RECIPE_DATA.value})

    new_recipe = await finalize_scraped_recipe(new_recipe, translator, on_progress=on_progress, extras=extras)
    return new_recipe, extras


GALLERY_IMAGE_MAX_BYTES = 10 * 1024 * 1024
_GALLERY_EXTENSIONS = {"jpeg": "jpg", "jpg": "jpg", "png": "png", "webp": "webp", "gif": "gif", "avif": "avif"}


async def _download_main_image(recipe_data_service: RecipeDataService, candidates: list[str]) -> bool:
    """מנסה את התמונות לפי הסדר עד שאחת נשמרת. תמונה שנכשלה לא מפילה את הייבוא."""
    for url in candidates:
        try:
            if await recipe_data_service.scrape_image(url) is not None:
                return True
        except Exception as e:
            recipe_data_service.logger.warning(f"Could not download recipe image {url}: {e}")
    return False


async def _save_gallery(
    recipe: RecipeDraft, recipe_data_service: RecipeDataService, urls: list[str], translator: Translator
) -> None:
    """שומר את התמונות הנוספות של המתכון כקבצים מצורפים."""

    async def fetch(url: str) -> tuple[bytes, str] | None:
        try:
            return await recipe_data_service.fetch_image(url, max_bytes=GALLERY_IMAGE_MAX_BYTES)
        except Exception as e:
            recipe_data_service.logger.warning(f"Skipping recipe gallery image {url}: {e}")
            return None

    results = await asyncio.gather(*(fetch(url) for url in urls))
    number = 1
    for result in results:
        if result is None or (extension := _GALLERY_EXTENSIONS.get(result[1])) is None:
            continue
        number += 1
        file_name = f"image-{number}-{uuid4().hex[:8]}.{extension}"
        recipe_data_service.dir_assets.joinpath(file_name).write_bytes(result[0])
        name = translate(translator, "recipe.import-credit.image", number=str(number))
        recipe.assets.append(RecipeAsset(name=name, icon="mdi-image", file_name=file_name))


async def finalize_scraped_recipe(
    recipe: RecipeDraft,
    translator: Translator,
    on_progress: Callable[[str], Awaitable[None]] | None = None,
    extras: ScrapedExtras | None = None,
) -> RecipeDraft:
    """Assigns an id to a freshly scraped recipe, downloads its image, and guarantees it has a name and slug.

    The recipe is not persisted; that's up to the caller.
    """

    recipe.id = uuid4()
    logger = get_logger()
    logger.debug(f"Image {recipe.image}")

    recipe_data_service = RecipeDataService(recipe.id)

    image_written = False

    try:
        if isinstance(recipe.image, list):
            recipe.image = recipe.image[0] if recipe.image else None

        # NO_IMAGE is a placeholder rather than a URL, so there's nothing to download for it
        candidates = [recipe.image] if recipe.image and recipe.image != NO_IMAGE else []
        candidates += [url for url in (extras.images if extras else []) if url not in candidates]
        if candidates:
            if on_progress:
                await on_progress(translator.t("recipe.create-progress.downloading-image"))
            image_written = await _download_main_image(recipe_data_service, candidates)  # type: ignore[arg-type]

        if recipe.name is None:
            recipe.name = "Untitled"

        recipe.slug = create_recipe_slug(recipe.name)
    except Exception as e:
        recipe_data_service.logger.exception(f"Error Scraping Image: {e}")
        image_written = False

    # `image` is the cache key the frontend uses to decide whether to request an image at
    # all, so it must stay empty unless a file actually landed on disk. Stamping a key for
    # a recipe with no image is what makes every render ask for a file that 404s.
    recipe.image = cache.new_key(4) if image_written else None

    if extras and extras.gallery:
        try:
            await _save_gallery(recipe, recipe_data_service, extras.gallery, translator)
        except Exception as e:
            logger.warning(f"Could not save the recipe's additional images: {e}")

    if recipe.name is None or recipe.name == "":
        recipe.name = f"No Recipe Name Found - {uuid4()!s}"
        recipe.slug = create_recipe_slug(recipe.name)

    return recipe
