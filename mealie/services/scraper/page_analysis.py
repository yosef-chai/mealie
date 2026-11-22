"""ניתוח מבנה של דף מתכון: בלוקים של טקסט בסדר הקריאה, JSON-LD, תמונות וקרדיט.

משלים את מה שה-structured data מפספס (כותרות מקטעים, כלים, צלם) ומאפשר לקרוא מתכון
מדף שאין בו structured data בכלל.
"""

from __future__ import annotations

import json
import math
import re
from dataclasses import dataclass, field
from functools import cached_property
from typing import Any
from urllib.parse import urljoin, urlsplit

from bs4 import BeautifulSoup, Comment, Doctype, NavigableString, ProcessingInstruction, Tag

from mealie.core.root_logger import get_logger

from . import recipe_text as rt

logger = get_logger("recipe-scraper")

_BLOCK_TAGS = frozenset(
    "address article aside blockquote center dd details div dl dt fieldset figcaption figure footer h1 h2 h3 h4 h5 h6 "
    "header hr label li main ol p pre section summary table tbody thead tfoot tr td th ul".split()
)
_SKIP_TAGS = frozenset(
    "script style noscript template svg iframe button select textarea input option nav object video audio canvas "
    "head map picture source track meta link".split()
)
_BOLD_TAGS = frozenset({"b", "strong", "mark"})
_HEADING_TAGS = {f"h{n}": n for n in range(1, 7)}
_HIDDEN_CLASSES = re.compile(
    r"(?:^|\s)(?:sr-only|screen-reader-text|visually-hidden|elementor-screen-only|d-none)(?:\s|$)"
)
_HEADING_CLASS = re.compile(r"(?:^|[_\-\s])(?:title|heading|subtitle|group-name|name)(?:$|[_\-\s])", re.IGNORECASE)
_CAPTION_CLASS = re.compile(r"caption|credit", re.IGNORECASE)
_IMAGE_NOISE = re.compile(
    r"logo|icon|avatar|gravatar|emoji|sprite|pixel|spinner|loader|lazy|blank|spacer|placeholder|banner|badge|brand|"
    r"profile|/ads?/|advert|facebook|whatsapp",
    re.IGNORECASE,
)
_WP_SIZE = re.compile(r"-(\d{2,4})x(\d{2,4})(?=\.(?:jpe?g|png|webp|gif|avif)$)", re.IGNORECASE)
_IDENTITY_NOISE = re.compile(
    r"-(?:\d{2,4}x\d{2,4}|scaled|rotated|e\d{10,})|@\dx|[-_](?:small|medium|large|thumb)", re.I
)
_CLOUD_TRANSFORMS = re.compile(r"/(?:t_[^/]+|[a-z]_[^/]*,[^/]+|w_\d+[^/]*|c_[^/]+)(?=/)")

_MAX_GAP = 6
_SMALL_IMAGE_PIXELS = 160_000


@dataclass(slots=True)
class Block:
    text: str
    element: Tag
    heading: bool
    level: int
    caption: bool
    index: int = 0


@dataclass(slots=True)
class Line:
    """שורה במתכון: פריט, או כותרת שפותחת קבוצה."""

    text: str
    heading: bool = False


@dataclass(slots=True)
class Alignment:
    """איך רשימה מה-structured data נפרסת בדף."""

    matched: dict[int, int]
    titles: dict[int, str]
    marked_headings: set[int]
    extras: list[Line] = field(default_factory=list)


@dataclass(slots=True)
class ImageCandidate:
    url: str
    width: int = 0
    height: int = 0
    source: str = ""

    @property
    def area(self) -> int:
        return self.width * self.height


@dataclass(slots=True)
class PageCredit:
    authors: list[str] = field(default_factory=list)
    photographers: list[str] = field(default_factory=list)
    site_name: str = ""


def _types(node: dict) -> list[str]:
    raw = node.get("@type") or node.get("type") or []
    values = raw if isinstance(raw, list) else [raw]
    return [str(v).rsplit("/", 1)[-1] for v in values]


def _as_int(value: Any) -> int:
    # schema.org מתיר גם QuantitativeValue: {"@type": "QuantitativeValue", "value": 1200}
    if isinstance(value, dict):
        value = value.get("value")
    try:
        return int(float(str(value).strip().removesuffix("px")))
    except TypeError, ValueError:
        return 0


def _text(value: Any) -> str:
    if isinstance(value, str):
        return " ".join(value.split())
    if isinstance(value, dict):
        return _text(value.get("name") or value.get("text") or value.get("textValue") or "")
    return ""


def image_identity(url: str) -> str:
    """מפתח שזהה לכל הגדלים והחיתוכים של אותה תמונה."""
    parts = urlsplit(url)
    path = _CLOUD_TRANSFORMS.sub("", parts.path)
    name = path.rsplit("/", 1)[-1].rsplit(".", 1)[0]
    return _IDENTITY_NOISE.sub("", name).casefold() or path.casefold()


def _url_dimensions(url: str) -> tuple[int, int]:
    if match := _WP_SIZE.search(urlsplit(url).path):
        return int(match.group(1)), int(match.group(2))
    return 0, 0


class PageAnalysis:
    """נוצר פעם אחת לכל דף, וכל החלקים מחושבים רק כשמבקשים אותם."""

    def __init__(self, html: str, url: str | None = None) -> None:
        self.html = html or ""
        self.url = url or ""
        # מתכון מ-microdata או מ-JSON שהודבק, כשאין בדף JSON-LD של מתכון
        self.fallback_recipe: dict | None = None

    # ------------------------------------------------------------------ parsing

    @cached_property
    def soup(self) -> BeautifulSoup:
        try:
            return BeautifulSoup(self.html, "lxml")
        except Exception:
            return BeautifulSoup(self.html, "html.parser")

    @cached_property
    def base_url(self) -> str:
        base = self.soup.find("base", href=True)
        href = base.get("href") if isinstance(base, Tag) else None
        return urljoin(self.url, str(href)) if href else self.url

    def absolute(self, url: str) -> str:
        url = url.strip()
        if not url or url.startswith(("data:", "javascript:", "blob:")):
            return ""
        absolute = urljoin(self.base_url, url) if self.base_url else url
        return absolute if absolute.startswith(("http://", "https://")) else ""

    @cached_property
    def meta(self) -> dict[str, list[str]]:
        values: dict[str, list[str]] = {}
        for tag in self.soup.find_all("meta"):
            key = tag.get("property") or tag.get("name") or tag.get("itemprop")
            content = tag.get("content")
            if key and content:
                values.setdefault(str(key).lower(), []).append(" ".join(str(content).split()))
        return values

    def meta_first(self, *keys: str) -> str:
        for key in keys:
            if values := self.meta.get(key):
                return values[0]
        return ""

    # ------------------------------------------------------------------ JSON-LD

    @cached_property
    def jsonld_nodes(self) -> list[dict]:
        """כל הצמתים עם @type מכל בלוקי ה-JSON-LD, כולל @graph וצמתים מקוננים."""
        nodes: list[dict] = []

        def collect(value: Any, depth: int = 0) -> None:
            if depth > 12:
                return
            if isinstance(value, list):
                for item in value:
                    collect(item, depth + 1)
            elif isinstance(value, dict):
                if "@type" in value:
                    nodes.append(value)
                for child in value.values():
                    if isinstance(child, dict | list):
                        collect(child, depth + 1)

        for script in self.soup.find_all("script", type=re.compile(r"ld\+json", re.I)):
            raw = (script.string or script.get_text() or "").strip()
            raw = re.sub(r"^\s*(?:<!--|//\s*<!\[CDATA\[)|(?:-->|//\s*\]\]>)\s*$", "", raw)
            if not raw:
                continue
            try:
                collect(json.loads(raw, strict=False))
            except ValueError:
                logger.debug("Skipping an unparsable JSON-LD block")
        return nodes

    @cached_property
    def nodes_by_id(self) -> dict[str, dict]:
        index: dict[str, dict] = {}
        for node in self.jsonld_nodes:
            node_id = node.get("@id")
            if isinstance(node_id, str) and (node_id not in index or len(node) > len(index[node_id])):
                index[node_id] = node
        return index

    def resolve(self, value: Any) -> Any:
        """מחליף הפניה {"@id": ...} בצומת המלא מה-@graph, כשהוא קיים."""
        if isinstance(value, dict) and set(value) <= {"@id", "@type"} and (node_id := value.get("@id")):
            return self.nodes_by_id.get(node_id, value)
        return value

    @cached_property
    def recipe_node(self) -> dict | None:
        recipes = [n for n in self.jsonld_nodes if "Recipe" in _types(n)]
        if not recipes:
            return None
        return max(recipes, key=lambda n: len(n.get("recipeIngredient") or n.get("ingredients") or []))

    def recipe_value(self, key: str) -> Any:
        recipe = self.recipe_node or self.fallback_recipe
        return recipe.get(key) if recipe else None

    def nodes_of_type(self, type_name: str) -> list[dict]:
        return [n for n in self.jsonld_nodes if type_name in _types(n)]

    # ------------------------------------------------------------------ text blocks

    @cached_property
    def _collected(self) -> tuple[list[Block], list[tuple[int, Tag]]]:
        blocks: list[Block] = []
        images: list[tuple[int, Tag]] = []

        def hidden(tag: Tag) -> bool:
            if tag.get("aria-hidden") == "true" or tag.has_attr("hidden"):
                return True
            style = str(tag.get("style") or "").replace(" ", "").lower()
            if "display:none" in style or "visibility:hidden" in style:
                return True
            classes = tag.get("class")
            return bool(classes and _HIDDEN_CLASSES.search(" ".join(classes)))

        def flush(owner: Tag, buffer: list[tuple[str, bool]], caption: bool) -> None:
            text = " ".join("".join(piece for piece, _ in buffer).split())
            visible = [(piece, bold) for piece, bold in buffer if piece.strip()]
            buffer.clear()
            if not text:
                return

            level = _HEADING_TAGS.get(owner.name, 0)
            bold_only = bool(visible) and all(bold for _, bold in visible)
            classes = " ".join(owner.get("class") or [])
            classy = bool(classes and _HEADING_CLASS.search(classes)) and len(text.split()) <= 8
            heading = bool(level) or (bold_only and len(text.split()) <= 12) or classy
            blocks.append(Block(text=text, element=owner, heading=heading, level=level, caption=caption))

        def visit(el: Tag, owner: Tag, bold: bool, buffer: list[tuple[str, bool]], caption: bool) -> None:
            for child in el.children:
                if isinstance(child, Comment | Doctype | ProcessingInstruction):
                    continue
                if isinstance(child, NavigableString):
                    buffer.append((str(child), bold))
                    continue
                if not isinstance(child, Tag) or child.name in _SKIP_TAGS or hidden(child):
                    continue
                if child.name == "img":
                    images.append((len(blocks), child))
                    continue
                if child.name == "br":
                    flush(owner, buffer, caption)
                    continue
                if child.name in _BLOCK_TAGS:
                    flush(owner, buffer, caption)
                    is_caption = (
                        caption
                        or child.name == "figcaption"
                        or bool(_CAPTION_CLASS.search(" ".join(child.get("class") or [])))
                    )
                    inner: list[tuple[str, bool]] = []
                    visit(child, child, False, inner, is_caption)
                    flush(child, inner, is_caption)
                    continue
                visit(child, owner, bold or child.name in _BOLD_TAGS, buffer, caption)

        body = self.soup.body or self.soup
        if isinstance(body, Tag):
            root_buffer: list[tuple[str, bool]] = []
            visit(body, body, False, root_buffer, False)
            flush(body, root_buffer, False)

        for index, block in enumerate(blocks):
            block.index = index
        return blocks, images

    @property
    def blocks(self) -> list[Block]:
        return self._collected[0]

    @cached_property
    def _keys(self) -> list[str]:
        return [rt.comparable(block.text) for block in self.blocks]

    @cached_property
    def captions(self) -> list[str]:
        return [block.text for block in self.blocks if block.caption]

    def _heading_like(self, block: Block) -> bool:
        # מספר שלב מודגש ("1", "02") אינו כותרת
        if not re.search(r"[^\W\d_]", block.text) or rt.generic_step_name(block.text):
            return False
        return (block.heading and len(block.text.split()) <= 10) or rt.looks_like_section_heading(block.text)

    # ------------------------------------------------------------------ alignment

    @staticmethod
    def _matches(item: str, block: str) -> bool:
        if not item or not block:
            return False
        if item == block:
            return True
        short, long = sorted((item, block), key=len)
        if len(short) >= 6 and short in long and len(short) / len(long) >= 0.5:
            return True
        item_tokens, block_tokens = set(item.split()), set(block.split())
        common = len(item_tokens & block_tokens)
        return common / len(item_tokens) >= 0.85 and common / len(block_tokens) >= 0.6

    def align(self, items: list[str], kind: str) -> Alignment | None:
        """מוצא את פריטי הרשימה בדף, ואת הכותרות שמופיעות ביניהם."""
        keys = [rt.comparable(item) for item in items]
        if not keys or not self.blocks:
            return None

        best: Alignment | None = None
        for first in range(min(2, len(keys))):
            starts = [k for k, block_key in enumerate(self._keys) if self._matches(keys[first], block_key)][:12]
            for start in starts:
                alignment = self._align_from(keys, kind, first, start)
                if best is None or len(alignment.matched) > len(best.matched):
                    best = alignment

        if best is None:
            return None
        needed = 1 if len(keys) == 1 else max(2, math.ceil(len(keys) * 0.6))
        return best if len(best.matched) >= needed else None

    def _absorb_same_block(self, keys: list[str], j: int, k: int, matched: dict[int, int]) -> int:
        """פריטים נוספים שיושבים באותו בלוק כמו הפריט הקודם. מחזיר את הפריט הבא שלא שובץ.

        פריט שחוזר על הקודם, או שמתאים גם לבלוק הבא, נשאר לבלוק הבא.
        """
        while j < len(keys) and keys[j] != keys[j - 1] and self._matches(keys[j], self._keys[k]):
            if k + 1 < len(self._keys) and self._matches(keys[j], self._keys[k + 1]):
                break
            matched[j] = k
            j += 1
        return j

    def _align_from(self, keys: list[str], kind: str, first: int, start: int) -> Alignment:
        blocks = self.blocks
        matched = {first: start}
        titles: dict[int, str] = {}
        marked: set[int] = {first} if blocks[start].heading else set()

        # כותרת שמופיעה מעט לפני הפריט הראשון
        back, seen = start - 1, 0
        while back >= 0 and seen < 3:
            block = blocks[back]
            if not block.caption:
                seen += 1
                if rt.generic_heading(block.text):
                    break
                if self._heading_like(block):
                    titles[first] = rt.heading_text(block.text)
                    break
            back -= 1

        j = self._absorb_same_block(keys, first + 1, start, matched)
        k = start + 1

        pending: list[str] = []
        gap = 0
        while k < len(blocks) and j < len(keys):
            block = blocks[k]
            if block.caption:
                k += 1
                continue
            if self._matches(keys[j], self._keys[k]):
                matched[j] = k
                if block.heading:
                    marked.add(j)
                if pending:
                    titles[j] = pending[-1]
                pending, gap = [], 0
                j = self._absorb_same_block(keys, j + 1, k, matched)
                k += 1
                continue
            if j + 1 < len(keys) and self._matches(keys[j + 1], self._keys[k]):
                j += 1  # פריט שלא מוצג בדף
                continue
            generic = rt.generic_heading(block.text)
            if generic and generic != kind:
                break
            if not generic and self._heading_like(block):
                pending.append(rt.heading_text(block.text))
            gap += 1
            if gap > _MAX_GAP:
                break
            k += 1

        alignment = Alignment(matched=matched, titles=titles, marked_headings=marked)
        if kind == "ingredients" and j >= len(keys) and matched:
            alignment.extras = self._trailing_group(matched, k)
        return alignment

    def _trailing_group(self, matched: dict[int, int], k: int) -> list[Line]:
        """קבוצת מצרכים נוספת שמופיעה בדף מתחת לרשימה, אבל חסרה ב-structured data."""
        blocks = self.blocks
        first_el, last_el = blocks[min(matched.values())].element, blocks[max(matched.values())].element
        container = _common_ancestor(first_el, last_el)
        allowed = {id(container)}
        parent = container
        for _ in range(2):
            parent = parent.parent if isinstance(parent.parent, Tag) else parent
            allowed.add(id(parent))

        extras: list[Line] = []
        title_pending = False
        scanned = 0
        while k < len(blocks) and scanned < 20:
            block = blocks[k]
            k += 1
            if block.caption:
                continue
            scanned += 1
            if id(_common_ancestor(first_el, block.element)) not in allowed or rt.generic_heading(block.text):
                break
            # רק כותרת שנראית ככותרת של קבוצת מצרכים, לא כל שורה מודגשת ("רוצים עוד מתכונים?")
            if rt.looks_like_section_heading(block.text):
                extras.append(Line(rt.heading_text(block.text), heading=True))
                title_pending = True
            elif title_pending and len(block.text.split()) <= 10 and not rt.is_junk_line(block.text):
                extras.append(Line(block.text))
            else:
                break

        # רק קבוצה עם כותרת ולפחות פריט אחד נחשבת לחלק מהרשימה
        while extras and extras[-1].heading:
            extras.pop()
        return extras if any(not line.heading for line in extras) else []

    # ------------------------------------------------------------------ sections

    def section(self, kind: str) -> list[Line]:
        """שורות המקטע שכותרתו מסוג kind ("ingredients", "instructions", "equipment")."""
        best: list[Line] = []
        for block in self.blocks:
            if block.caption or rt.generic_heading(block.text) != kind:
                continue
            lines = self._section_after(block)
            if len(lines) > len(best):
                best = lines
        return best

    def _section_after(self, heading: Block) -> list[Line]:
        blocks = self.blocks
        following = blocks[heading.index + 1 : heading.index + 120]

        container: Tag | None = heading.element
        for _ in range(5):
            if container is None:
                break
            inside = [b for b in following if _is_inside(b.element, container)]
            if len(inside) >= 2:
                break
            container = container.parent if isinstance(container.parent, Tag) else None
        if container is None:
            return []

        lines: list[Line] = []
        for block in following:
            if block.caption:
                continue
            if not _is_inside(block.element, container) or rt.generic_heading(block.text):
                break
            if heading.level and block.level and block.level <= heading.level:
                break
            if rt.is_junk_line(block.text) or label_of(block.text):
                continue
            lines.append(Line(block.text, heading=self._heading_like(block)))

        while lines and lines[-1].heading:
            lines.pop()
        return lines

    def labeled_values(self) -> dict[str, str]:
        """ערכים עם תווית בדף: זמנים ומנות ("זמן הכנה: 10 דקות", "כמות סועדים" ואחריה "2")."""
        values: dict[str, str] = {}
        blocks = [b for b in self.blocks if not b.caption]
        for position, block in enumerate(blocks):
            if len(block.text) > 80:
                continue
            if match := _INLINE_LABEL.match(rt.normalize_punctuation(block.text)):
                key, value = label_of(match.group("label")), match.group("value").strip()
            elif (key := label_of(block.text)) and position + 1 < len(blocks):
                value = blocks[position + 1].text
                if len(value.split()) > 6 or label_of(value):
                    continue
            else:
                continue
            if key and value and key not in values:
                values[key] = value
        return values

    # ------------------------------------------------------------------ images

    def image_candidates(self) -> list[ImageCandidate]:
        """תמונות ראשיות אפשריות, מהטובה לפחות טובה."""
        found: dict[str, ImageCandidate] = {}

        def add(url: Any, width: Any = 0, height: Any = 0, source: str = "") -> None:
            if isinstance(url, list):
                for item in url:
                    add(item, source=source)
                return
            if isinstance(url, dict):
                add(
                    url.get("url") or url.get("contentUrl") or url.get("@id"),
                    url.get("width"),
                    url.get("height"),
                    source,
                )
                return
            if not isinstance(url, str) or not (absolute := self.absolute(url)) or _IMAGE_NOISE.search(absolute):
                return
            w, h = _as_int(width), _as_int(height)
            # מידות בשם הקובץ של WordPress ("-300x200") אמינות יותר ממה שהאתר מצהיר
            if (url_size := _url_dimensions(absolute)) != (0, 0) or not (w and h):
                w, h = url_size
            current = found.get(absolute)
            if current is None:
                found[absolute] = ImageCandidate(absolute, w, h, source)
            elif not current.area and w and h:
                current.width, current.height = w, h

        add(self.resolve(self.recipe_value("image")), source="recipe")
        for node in self.nodes_of_type("ImageObject"):
            if "primaryimage" in str(node.get("@id", "")).lower():
                add(node, source="primary")
        add(
            self.meta_first("og:image:secure_url", "og:image"),
            self.meta_first("og:image:width"),
            self.meta_first("og:image:height"),
            "og",
        )
        add(self.meta_first("twitter:image", "twitter:image:src"), source="twitter")
        if (link := self.soup.find("link", rel="image_src")) and isinstance(link, Tag):
            add(link.get("href"), source="link")
        for img in self._content_images()[:1]:
            add(img.url, img.width, img.height, "content")

        candidates = list(found.values())
        # המקור של תמונה ממוזערת של WordPress ("-300x200.jpg") כמעט תמיד קיים, ונבדק כשהתמונה קטנה
        for candidate in list(candidates):
            original = _WP_SIZE.sub("", candidate.url)
            if original != candidate.url and original not in found:
                found[original] = ImageCandidate(original, 0, 0, candidate.source + "-original")
                candidates.append(found[original])

        sources = ["recipe", "primary", "og", "twitter", "link", "content"]

        def rank(candidate: ImageCandidate) -> tuple[int, int, int]:
            source = candidate.source.removesuffix("-original")
            priority = sources.index(source) if source in sources else len(sources)
            # תמונה מגוף הכתבה היא רק גיבוי לתמונה שהאתר הצהיר עליה
            if source == "content":
                return 4 if candidate.area >= _SMALL_IMAGE_PIXELS or not candidate.area else 5, 0, -candidate.area
            if candidate.area >= _SMALL_IMAGE_PIXELS:
                return 0, priority, -candidate.area
            if candidate.source.endswith("-original"):
                return 1, priority, 0
            if not candidate.area:
                return 2, priority, 0
            return 3, priority, -candidate.area

        return sorted(candidates, key=rank)

    def _image_from_tag(self, img: Tag) -> ImageCandidate | None:
        src = ""
        if srcset := img.get("srcset") or img.get("data-srcset"):
            best_width = -1
            for part in str(srcset).split(","):
                bits = part.split()
                if bits and (width := _as_int(bits[1].rstrip("w")) if len(bits) > 1 else 0) >= best_width:
                    best_width, src = width, bits[0]
        for attr in ("data-lazy-src", "data-src", "data-original", "src"):
            if src:
                break
            value = img.get(attr)
            if value and not str(value).startswith("data:"):
                src = str(value)
        absolute = self.absolute(src)
        if not absolute or _IMAGE_NOISE.search(absolute) or _IMAGE_NOISE.search(" ".join(img.get("class") or [])):
            return None
        width, height = _as_int(img.get("width")), _as_int(img.get("height"))
        if (width and width < 120) or (height and height < 120) or absolute.lower().endswith(".svg"):
            return None
        return ImageCandidate(absolute, width, height, "content")

    def _content_images(self) -> list[ImageCandidate]:
        """תמונות מתוך גוף המתכון: מהכותרת הראשית ועד סוף ההוראות."""
        blocks = self.blocks
        h1 = next((b.index for b in blocks if b.level == 1), 0)
        images = []
        for position, img in self._collected[1]:
            if position >= h1 and (candidate := self._image_from_tag(img)):
                images.append(candidate)
        return images

    def images_between(self, first_block: int, last_block: int) -> list[str]:
        """תמונות בתוך אזור ההוראות: בין הבלוק הראשון לאחרון, ובתוך המכל המשותף שלהם."""
        container = _common_ancestor(self.blocks[first_block].element, self.blocks[last_block].element)
        urls: list[str] = []
        for position, img in self._collected[1]:
            if not first_block <= position <= last_block + 1 or not _is_inside(img, container):
                continue
            if candidate := self._image_from_tag(img):
                urls.append(candidate.url)
        return urls

    # ------------------------------------------------------------------ credit

    def credit(self) -> PageCredit:
        credit = PageCredit()

        def add_person(value: Any, target: list[str]) -> None:
            value = self.resolve(value)
            if isinstance(value, list):
                for item in value:
                    add_person(item, target)
                return
            name = _text(value)
            if name and len(name) <= 80 and not name.startswith("http") and name not in target:
                target.append(name)

        add_person(self.recipe_value("author"), credit.authors)
        add_person(self.recipe_value("creator"), credit.authors)
        if not credit.authors:
            for article in self.nodes_of_type("Article") + self.nodes_of_type("BlogPosting"):
                add_person(article.get("author"), credit.authors)
        if not credit.authors:
            add_person(self.meta_first("author", "article:author", "dc.creator"), credit.authors)

        image = self.resolve(self.recipe_value("image"))
        images = image if isinstance(image, list) else [image]
        for node in [
            *images,
            *[n for n in self.nodes_of_type("ImageObject") if "primaryimage" in str(n.get("@id", ""))],
        ]:
            if isinstance(node, dict):
                for key in ("creditText", "creator", "copyrightHolder", "author"):
                    add_person(node.get(key), credit.photographers)
        for caption in self.captions:
            for name in rt.photo_credits(caption):
                if name not in credit.photographers:
                    credit.photographers.append(name)

        credit.site_name = self.site_name()
        return credit

    def site_name(self) -> str:
        names = [self.meta_first("og:site_name", "application-name")]
        names += [_text(node.get("name")) for node in self.nodes_of_type("WebSite")]
        names += [_text(self.resolve(self.recipe_value("publisher")))]
        for name in names:
            if name := (name or "").strip():
                # יש אתרים שממלאים כאן את הכותרת המלאה ("סלט סושי | השולחן")
                return re.split(r"\s+[|\-–]\s+", name)[-1].strip()
        # בלי מטא-דאטה, שם האתר הוא בדרך כלל הסיומת של <title> ("עוגת קפה | אפרת ושדי")
        title = self.soup.title.get_text(" ", strip=True) if self.soup.title else ""
        parts = [p.strip() for p in re.split(r"\s+[|–]\s+", title) if p.strip()]
        if len(parts) > 1 and len(parts[-1]) <= 40:
            return parts[-1]
        # שם המארח אינו שם האתר, וכתובת המקור כבר נשמרת במתכון
        return ""

    # ------------------------------------------------------------------ misc

    def article_tags(self) -> list[str]:
        tags = list(self.meta.get("article:tag", []))
        for article in self.nodes_of_type("Article") + self.nodes_of_type("BlogPosting"):
            keywords = article.get("keywords")
            if isinstance(keywords, str):
                tags += keywords.split(",")
            elif isinstance(keywords, list):
                tags += [k for k in keywords if isinstance(k, str)]
        return [t.strip() for t in tags if t and t.strip()]

    def title(self) -> str:
        if h1 := next((b for b in self.blocks if b.level == 1), None):
            return h1.text
        title = self.meta_first("og:title") or (self.soup.title.get_text() if self.soup.title else "")
        site = self.site_name()
        parts = [p.strip() for p in re.split(r"\s+[|\-–]\s+", title) if p.strip()]
        parts = [p for p in parts if p != site] or parts
        return parts[0] if parts else ""

    def description(self) -> str:
        return self.meta_first("og:description", "description", "twitter:description")


_INLINE_LABEL = re.compile(r"^(?P<label>[^:]{2,40}?)\s*:\s*(?P<value>\S.{0,40})$")
_LABELS = {
    "prep": re.compile(r"^(?:זמן\s+)?הכנה$|^זמן\s+עבודה$|^prep(?:aration)?(?:\s+time)?$|^active\s+time$", re.I),
    "cook": re.compile(
        r"^(?:זמן\s+)?(?:בישול|אפייה|אפיה|צלייה|טיגון)(?:\s*/\s*(?:בישול|אפייה|אפיה))?$|^cook(?:ing)?(?:\s+time)?$|^bake(?:\s+time)?$",
        re.I,
    ),
    "total": re.compile(
        r"^(?:זמן\s+כולל|סה\"כ(?:\s+זמן)?|זמן|משך(?:\s+זמן)?)$|^total(?:\s+time)?$|^ready\s+in$|^time$", re.I
    ),
    "servings": re.compile(
        r"^(?:מנות|מס'?\s*מנות|מספר\s+(?:ה)?מנות.*|כמות(?:\s+(?:סועדים|מנות))?|סועדים|תפוקה)$|^(?:servings|serves|yield|makes)$",
        re.I,
    ),
}


def label_of(text: str) -> str | None:
    label = rt.normalize_punctuation(text).strip().rstrip(":").strip()
    if not label or len(label) > 40:
        return None
    for key, pattern in _LABELS.items():
        if pattern.match(label):
            return key
    return None


def _is_inside(element: Tag, container: Tag) -> bool:
    node: Any = element
    while node is not None:
        if node is container:
            return True
        node = node.parent
    return False


def _common_ancestor(a: Tag, b: Tag) -> Tag:
    ancestors = {id(a)}
    node: Any = a.parent
    while node is not None:
        ancestors.add(id(node))
        node = node.parent
    node = b
    while node is not None and id(node) not in ancestors:
        node = node.parent
    return node if isinstance(node, Tag) else a
