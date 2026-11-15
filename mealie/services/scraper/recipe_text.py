"""ניתוח טקסט חופשי של מתכון: זמנים, כמויות, כותרות מקטעים, כלים ושורות זבל. בעברית ובאנגלית."""

import re
from dataclasses import dataclass
from fractions import Fraction

HEBREW_LETTER = re.compile(r"[א-ת]")

# מקפים, גרשיים וסימני פיסוק עבריים שאתרים מערבבים עם המקבילות הלטיניות
_PUNCT_MAP = str.maketrans({"–": "-", "—": "-", "־": "-", "״": '"', "׳": "'", "‏": "", "‎": ""})

MAX_STEP_DURATION_SECONDS = 48 * 3600


def normalize_punctuation(text: str) -> str:
    return text.translate(_PUNCT_MAP)


def comparable(text: str) -> str:
    """צורה להשוואה בין מקורות: בלי פיסוק, רווחים ורישיות."""
    text = normalize_punctuation(text).casefold()
    text = re.sub(r"[^\w\s]", " ", text)
    return " ".join(text.split())


def is_hebrew(text: str) -> bool:
    return bool(HEBREW_LETTER.search(text))


# ---------------------------------------------------------------------------
# shortcodes וכיתובי תמונה

_CAPTION_BLOCK = re.compile(r"\[\s*caption[^\]]*\](.*?)\[\s*/?\s*caption\s*\]", re.IGNORECASE | re.DOTALL)
_SHORTCODE = re.compile(
    r"\[\s*/?\s*(?:caption|gallery|embed|video|audio|playlist|wp_[a-z_]+|vc_[a-z_]+|et_pb_[a-z_]+|wprm[a-z_-]*)\b[^\]]*\]",
    re.IGNORECASE,
)
_PHOTO_CREDIT = re.compile(
    r"(?:קרדיט\s+צילום|צילום|צלם|צלמת|photo(?:graph(?:y)?)?\s+(?:by|credit)|photo|credit)\s*[:：\-]?\s*"
    r"(?P<name>[^.|,;\n\[\]()]{2,60})",
    re.IGNORECASE,
)
# מה שבא אחרי שם הצלם באותו כיתוב ("צילום: עידן גרוס. עריכה: ...") כבר לא חלק מהשם
_CREDIT_STOP = re.compile(r"\s+(?:עריכה|סטיילינג|styling|food styling|הפקה|ע\"י)\b.*$", re.IGNORECASE)


def photo_credits(text: str) -> list[str]:
    """שמות צלמים מכיתובים כמו "צילום: עדי חזן"."""
    names: list[str] = []
    for match in _PHOTO_CREDIT.finditer(normalize_punctuation(text)):
        name = _CREDIT_STOP.sub("", match.group("name")).strip(" -:\"'")
        if name and len(name.split()) <= 6 and name not in names:
            names.append(name)
    return names


def strip_shortcodes(text: str) -> tuple[str, list[str]]:
    """מסיר shortcodes של WordPress ומחזיר גם את כיתובי התמונות שהיו בהם."""
    captions = [m.group(1) for m in _CAPTION_BLOCK.finditer(text)]
    text = _CAPTION_BLOCK.sub(" ", text)
    text = _SHORTCODE.sub(" ", text)
    return text, captions


# ---------------------------------------------------------------------------
# זמנים

_HE_NUMBERS = {
    "אחת": 1,
    "אחד": 1,
    "שתי": 2,
    "שני": 2,
    "שתיים": 2,
    "שניים": 2,
    "שלוש": 3,
    "שלושה": 3,
    "ארבע": 4,
    "ארבעה": 4,
    "חמש": 5,
    "חמישה": 5,
    "שש": 6,
    "שישה": 6,
    "שבע": 7,
    "שבעה": 7,
    "שמונה": 8,
    "תשע": 9,
    "תשעה": 9,
    "עשר": 10,
    "עשרה": 10,
    "חמש עשרה": 15,
    "עשרים": 20,
    "עשרים וחמש": 25,
    "שלושים": 30,
    "ארבעים": 40,
    "ארבעים וחמש": 45,
    "חמישים": 50,
    "שישים": 60,
    "תשעים": 90,
}
_EN_NUMBERS = {
    "one": 1,
    "two": 2,
    "three": 3,
    "four": 4,
    "five": 5,
    "six": 6,
    "seven": 7,
    "eight": 8,
    "nine": 9,
    "ten": 10,
    "twelve": 12,
    "fifteen": 15,
    "twenty": 20,
    "thirty": 30,
    "forty": 40,
    "forty-five": 45,
    "fifty": 50,
    "sixty": 60,
    "ninety": 90,
}
_UNICODE_FRACTIONS = {"½": 0.5, "¼": 0.25, "¾": 0.75, "⅓": 1 / 3, "⅔": 2 / 3}

_NUMBER_WORDS = "|".join(sorted([*_HE_NUMBERS, *_EN_NUMBERS], key=len, reverse=True))
_AMOUNT = rf"(?:\d+(?:[.,]\d+)?(?:\s+\d/\d)?|\d/\d|[½¼¾⅓⅔]|{_NUMBER_WORDS})"

_UNITS: dict[str, int] = {
    "שניות|שנייה|שניה|seconds?|secs?": 1,
    "דקות|דקה|דק'|ד'|minutes?|mins?": 60,
    "שעות|שעה|hours?|hrs?": 3600,
}
_UNIT = "|".join(_UNITS)

# תחילית מחוברת בעברית ("כ-20", "ל-5", "כשתי", "ו-10") או מילת זמן באנגלית
_PREFIX = r"(?:(?<![\w])(?:[כלוב]-?|ו?כ-?|about\s+|for\s+|another\s+)?)"

_RANGE_DURATION = re.compile(
    rf"{_PREFIX}(?P<a>{_AMOUNT})(?:\s*(?:-|עד|או|to|or)\s*(?:[כ]-?)?(?P<b>{_AMOUNT}))?\s*"
    rf"(?P<unit>{_UNIT})(?![\w'])(?P<half>\s+(?:וחצי|and\s+a\s+half))?",
    re.IGNORECASE,
)

# ביטויים עם משמעות קבועה, נבדקים לפני המספרים כדי לא לספור אותם פעמיים
_FIXED_DURATIONS: list[tuple[re.Pattern, int]] = [
    (re.compile(rf"(?<![\w]){p}(?![\w])", re.IGNORECASE), s)
    for p, s in (
        (r"[כלו]?-?שעתיים\s+וחצי", 150 * 60),
        (r"[כלו]?-?שעה\s+וחצי", 90 * 60),
        (r"[כלו]?-?שעה\s+ורבע", 75 * 60),
        (r"[כלו]?-?שלושת\s+רבעי\s+ה?שעה", 45 * 60),
        (r"[כלו]?-?שעתיים", 120 * 60),
        (r"[כלו]?-?חצי\s+שעה", 30 * 60),
        (r"[כלו]?-?רבע\s+שעה", 15 * 60),
        (r"[כלו]?-?יממה", 24 * 3600),
        (r"an\s+hour\s+and\s+a\s+half", 90 * 60),
        (r"half\s+an\s+hour", 30 * 60),
        (r"(?:an|one)\s+hour", 3600),
        (r"a\s+minute", 60),
    )
]
# "שעה" או "דקה" בלי מספר, למשל "אופים שעה" או "-שעה" אחרי טווח
_SINGLE_UNIT = re.compile(r"(?<![\w])(?:[כלו]-?)?(?P<unit>שעה|דקה)(?![\w])")
# זמן שמתאר מתי לעשות משהו ולא כמה זמן הוא נמשך ("רבע שעה לפני ההגשה")
_LEAD_TIME = re.compile(r"^\s*(?:לפני|מראש|before|ahead|in advance)", re.IGNORECASE)
_RANGE_JOIN = re.compile(r"\s*(?:-|עד|או|to|or)\s*", re.IGNORECASE)


def _amount_value(raw: str) -> float | None:
    raw = raw.strip().lower()
    if raw in _HE_NUMBERS:
        return _HE_NUMBERS[raw]
    if raw in _EN_NUMBERS:
        return _EN_NUMBERS[raw]
    if raw in _UNICODE_FRACTIONS:
        return _UNICODE_FRACTIONS[raw]
    try:
        if " " in raw:
            whole, frac = raw.split(None, 1)
            return float(whole) + float(Fraction(frac))
        if "/" in raw:
            return float(Fraction(raw))
        return float(raw.replace(",", "."))
    except ValueError, ZeroDivisionError:
        return None


def _unit_seconds(unit: str) -> int:
    unit = unit.lower()
    for pattern, seconds in _UNITS.items():
        if re.fullmatch(pattern, unit, re.IGNORECASE):
            return seconds
    return 0


def durations_in_text(text: str) -> list[int]:
    """כל משכי הזמן שהטקסט מציין, בשניות. בטווח ("40-50 דקות") לוקחים את הקצה הנמוך."""
    text = normalize_punctuation(text)
    spans: list[tuple[int, int, float]] = []
    seconds: float

    def taken(start: int, end: int) -> bool:
        return any(start < e and end > s for s, e, _ in spans)

    def add(seconds: float, start: int, end: int) -> None:
        spans.append((start, end, seconds))

    for pattern, seconds in _FIXED_DURATIONS:
        for match in pattern.finditer(text):
            if not taken(*match.span()):
                add(seconds, *match.span())

    for match in _RANGE_DURATION.finditer(text):
        if taken(*match.span()):
            continue
        amount = _amount_value(match.group("a"))
        if amount is None:
            continue
        if match.group("half"):
            amount += 0.5
        add(amount * _unit_seconds(match.group("unit")), *match.span())

    for match in _SINGLE_UNIT.finditer(text):
        if not taken(*match.span()):
            add(_unit_seconds(match.group("unit")), *match.span())

    found: list[int] = []
    previous_end: int | None = None
    for start, end, seconds in sorted(spans):
        # הקצה העליון של טווח שנכתב בשתי צורות ("חצי שעה-שעה") לא נספר שוב
        is_range_end = previous_end is not None and _RANGE_JOIN.fullmatch(text[previous_end:start])
        previous_end = end
        if is_range_end or _LEAD_TIME.match(text[end:]):
            continue
        if 0 < seconds <= MAX_STEP_DURATION_SECONDS:
            found.append(round(seconds))
    return found


def step_duration(text: str) -> int | None:
    """הזמן שהשלב לוקח: סכום הזמנים שהוא מציין."""
    total = sum(durations_in_text(text))
    return total if 0 < total <= MAX_STEP_DURATION_SECONDS else None


def duration_value(text: str) -> int | None:
    """ערך של שדה זמן ("10 דקות", "60", "שעה וחצי"). מספר בלבד נחשב לדקות."""
    text = normalize_punctuation(text).strip()
    if re.fullmatch(r"\d+(?:[.,]\d+)?", text):
        seconds = float(text.replace(",", ".")) * 60
        return round(seconds) if 0 < seconds <= MAX_STEP_DURATION_SECONDS * 10 else None
    total = sum(durations_in_text(text))
    return total or None


# ---------------------------------------------------------------------------
# כמות / מנות


@dataclass(slots=True)
class YieldValue:
    servings: float = 0
    quantity: float = 0
    text: str = ""


_SERVING_WORDS = r"(?:מנות|מנה|סועדים|סועד|אנשים|איש|נפשות|servings?|serves|people|persons|portions?)"
_SERVINGS_FIRST = re.compile(
    rf"(?:^|[\s(])(?:ל-?)?(?P<n>{_AMOUNT})(?:\s*-\s*{_AMOUNT})?\s*{_SERVING_WORDS}(?![\w])", re.I
)
_SERVINGS_AFTER = re.compile(
    rf"{_SERVING_WORDS}(?:\s+[^\d:]{{1,30}}?)?\s*[:\-]?\s*(?P<n>\d+(?:[.,]\d+)?)(?!\s*[*x×])", re.IGNORECASE
)
_LEADING_COUNT = re.compile(
    rf"^(?:makes\s+|yields?:?\s+|מכין\s+|יוצא(?:ות|ים)?\s+)?(?:about\s+|approx\.?\s+|around\s+|כ-?\s?)?"
    rf"(?P<n>{_AMOUNT})\s+(?P<rest>\D.*)$",
    re.I,
)

# כותרת כללית שנדבקה לתחילת שלב ("אופן ההכנה 1. שמים...")
_LEADING_GENERIC_HEADING = re.compile(
    r"^\s*(?:אופן\s+ה?הכנה|הוראות\s+ה?הכנה|דרך\s+ה?הכנה|instructions|directions|method)\s*:?\s+(?=\S)", re.I
)
_INLINE_NUMBER = re.compile(r"(?:^|\s)(\d{1,2})[.)]\s+(?=\S)")


def split_inline_numbering(text: str) -> list[str]:
    """שלב אחד שמכיל רשימה ממוספרת ("1. ... 2. ...") מתפצל לשלבים, רק כשהמספור רציף."""
    text = _LEADING_GENERIC_HEADING.sub("", text, count=1)
    matches = list(_INLINE_NUMBER.finditer(text))
    numbers = [int(m.group(1)) for m in matches]
    if len(matches) < 2 or numbers != list(range(1, len(numbers) + 1)):
        return [text]
    parts = [
        text[m.end() : (matches[i + 1].start() if i + 1 < len(matches) else len(text))] for i, m in enumerate(matches)
    ]
    lead = text[: matches[0].start()].strip()
    steps = [part.strip() for part in parts if part.strip()]
    return [lead, *steps] if lead else steps


def parse_yield(raw: object) -> YieldValue | None:
    """מפרש כמות כמו "4 מנות", "ל-8 מנות", "2 פיצות בקוטר 33 ס"מ" או "תבנית 23*23"."""
    if raw is None or isinstance(raw, bool):
        return None
    if isinstance(raw, int | float):
        return YieldValue(servings=float(raw)) if raw > 0 else None
    if isinstance(raw, list):
        values = [v for v in (parse_yield(item) for item in raw) if v]
        servings = next((v for v in values if v.servings), None)
        counted = next((v for v in values if v.quantity or v.text), None)
        if not (servings or counted):
            return None
        return YieldValue(
            servings=servings.servings if servings else 0,
            quantity=counted.quantity if counted else 0,
            text=counted.text if counted else "",
        )

    text = " ".join(normalize_punctuation(str(raw)).split()).strip(" :")
    if not text:
        return None

    if (match := _SERVINGS_FIRST.search(text)) or (match := _SERVINGS_AFTER.search(text)):
        if (value := _amount_value(match.group("n"))) and value > 0:
            return YieldValue(servings=value)

    if re.fullmatch(r"\d+(?:[.,]\d+)?", text):
        value = float(text.replace(",", "."))
        return YieldValue(servings=value) if value > 0 else None

    if (match := _LEADING_COUNT.match(text)) and (value := _amount_value(match.group("n"))):
        return YieldValue(quantity=value, text=match.group("rest").strip())

    # מידות תבנית ("23*23 ס"מ") הן לא מספר מנות, אז הטקסט נשמר כמו שהוא
    return YieldValue(text=text)


def servings_in_heading(text: str) -> float | None:
    """מנות שכותרת המצרכים מציינת, כמו "מצרכים ל-8 מנות"."""
    if (match := _SERVINGS_FIRST.search(normalize_punctuation(text))) and (value := _amount_value(match.group("n"))):
        return value
    return None


# ---------------------------------------------------------------------------
# כותרות ושורות לא רלוונטיות

_GENERIC_HEADINGS = {
    "ingredients": re.compile(
        r"^(?:ה?מצרכים|ה?רכיבים|ה?מרכיבים|ה?חומרים|רשימת\s+ה?מצרכים|ingredients?|what\s+you(?:'ll)?\s+need)\b[^.!?]{0,40}$",
        re.IGNORECASE,
    ),
    "instructions": re.compile(
        r"^(?:אופן\s+ה?הכנה|הוראות\s+ה?הכנה|דרך\s+ה?הכנה|שלבי\s+ה?הכנה|ה?הכנה|instructions?|directions?|method|"
        r"preparation|steps|how\s+to\s+make(?:\s+it)?)\s*:?$",
        re.IGNORECASE,
    ),
    "equipment": re.compile(
        r"^(?:ציוד(?:\s+נדרש)?|כלים(?:\s+נדרשים)?|כלי\s+עבודה|כלים\s+ו?ציוד|מה\s+צריך|equipment|tools|"
        r"kitchen\s+tools|you(?:'ll|\s+will)\s+need)\s*:?$",
        re.IGNORECASE,
    ),
    "notes": re.compile(r"^(?:טיפים|טיפ|הערות|הערה|דגשים|tips?|notes?|cook'?s\s+notes?)\s*:?$", re.IGNORECASE),
}

# רכיבים נפוצים של מנה שמשמשים ככותרת מקטע ("לבצק", "למילוי תפוחים", "תוספות")
_SECTION_NOUNS = (
    "בצק|מילוי|מלית|רוטב|רטבים|ציפוי|דיגוג|סירופ|קרם|קצפת|תחתית|בסיס|שכבה|קישוט|הגשה|מרינדה|תיבול|ממרח|"
    "תוספת|תוספות|טופינג|פירורים|קראמבל|גנאש|ג'לי|מוס|אורז|כופתה|פירה|מחית|מריחה|הברשה|טיגון|ציר|ויניגרט|"
    "סלט|קרמל|שוקולד|עוף|בשר|דג|ירקות|פסטה|לביבות|קציצות|שמרים|רביכה|פרלינים|עוגה|עוגיות|קרום|מעטפת"
)
_SECTION_LINE = re.compile(rf"^(?:ל|ו?ה)?(?:{_SECTION_NOUNS})(?:\s+[^\d]{{1,30}})?:?$")
_EN_SECTION_LINE = re.compile(
    r"^(?:for\s+the\s+[a-z\s'-]{2,30}|(?:the\s+)?(?:dough|filling|sauce|topping|toppings|frosting|icing|glaze|crust|"
    r"garnish|marinade|dressing|batter|syrup|assembly|to\s+serve|for\s+serving))\s*:?$",
    re.IGNORECASE,
)
_NUMBERED = re.compile(r"^\s*(?:(?:שלב|step)\s*)?(?P<n>\d{1,2})\s*[.):\-]\s*(?=\S)", re.IGNORECASE)
_GENERIC_STEP_NAME = re.compile(
    r"^(?:(?:שלב|step|étape|paso|schritt|passo|stap|null|none|undefined)\s*)?#?\d{0,3}\s*[:.)]?$", re.IGNORECASE
)
_JUNK = re.compile(
    r"^(?:ראו\s+(?:עוד|גם)|ראו\s+כאן|לחצו|לחץ\s+כאן|לצפייה|צפו\s+ב|קראו\s+(?:עוד|גם)|למתכון\s+המלא|בואו\s+(?:להכין|לראות)|"
    r"הצטרפו|הירשמו|see\s+(?:more|also)|read\s+more|click\s+here|watch\s+the|subscribe|jump\s+to|print\s+recipe)",
    re.IGNORECASE,
)
_SIGN_OFF = re.compile(r"^(?:בתאבון|בהצלחה|bon\s+app[eé]tit|enjoy)\s*[!.]*$", re.IGNORECASE)


def generic_heading(text: str) -> str | None:
    """סוג המקטע שהשורה פותחת ("ingredients", "instructions"...), או None לשורה רגילה."""
    line = " ".join(normalize_punctuation(text).split()).strip()
    if not line or len(line.split()) > 8:
        return None
    for kind, pattern in _GENERIC_HEADINGS.items():
        if pattern.match(line):
            return kind
    return None


def is_junk_line(text: str) -> bool:
    line = " ".join(text.split())
    return bool(_JUNK.match(line) or _SIGN_OFF.match(line))


def generic_step_name(name: str) -> bool:
    """שם שלב שאין בו מידע ("שלב 1", "Step 2", "null 3")."""
    return bool(_GENERIC_STEP_NAME.match(" ".join(name.split())))


def strip_step_number(text: str) -> str:
    return _NUMBERED.sub("", text, count=1)


def starts_numbered(text: str, number: int | None = None) -> bool:
    match = _NUMBERED.match(text)
    return bool(match and (number is None or int(match.group("n")) == number))


def looks_like_section_heading(text: str) -> bool:
    """שורה שנראית ככותרת של קבוצת מצרכים או שלבים, לפי הטקסט בלבד."""
    line = " ".join(normalize_punctuation(text).split()).strip()
    if not line or len(line) > 140:
        return False
    # הסבר בסוגריים ("(לערבב הכל בקערית)") לא הופך כותרת למשפט
    words = re.sub(r"\([^)]*\)", " ", line).split()
    if line.endswith(":") and len(words) <= 8 and not re.match(r"^\d", line):
        return True
    if len(line) > 80:
        return False
    if re.search(r"\d", line) or re.search(r"[.!?]$", line):
        return False
    return bool(len(words) <= 6 and (_SECTION_LINE.match(line) or _EN_SECTION_LINE.match(line)))


def heading_text(text: str) -> str:
    return " ".join(text.split()).strip().rstrip(":：").strip()


# ---------------------------------------------------------------------------
# כלים


@dataclass(frozen=True, slots=True)
class ToolTerm:
    he: str
    en: str
    pattern: re.Pattern
    # כלי כללי יותר שהמונח הזה מחליף כשהוא נמצא ("תבנית אינגליש קייק" במקום "תבנית אפייה")
    replaces: str = ""


# תחיליות מחוברות בעברית. "מ" לבד לא נכללת, כי "מסירים" אינו סיר
_HE_PREFIX = r"(?:ו?(?:[בכלה]|מה|בה|לה|שב|שה|כש)?)"


def _term(he: str, en: str, he_stems: str, en_stems: str, replaces: str = "") -> ToolTerm:
    pattern = re.compile(
        rf"(?<![א-ת]){_HE_PREFIX}(?:{he_stems})(?![א-ת])|(?<![a-z])(?:{en_stems})(?![a-z])",
        re.IGNORECASE,
    )
    return ToolTerm(he=he, en=en, pattern=pattern, replaces=replaces)


TOOL_TERMS: tuple[ToolTerm, ...] = (
    _term(
        "מיקסר",
        "Mixer",
        "מיקסר|מיקסרים|מקציף\\s+חשמלי|מטרפה\\s+חשמלית",
        "stand\\s+mixer|hand\\s+mixer|electric\\s+mixer|mixer",
    ),
    _term(
        "בלנדר מוט",
        "Immersion blender",
        "בלנדר\\s+מוט|בלנדר\\s+ידני",
        "immersion\\s+blender|stick\\s+blender|hand\\s+blender",
    ),
    _term("בלנדר", "Blender", "בלנדר", "blender"),
    _term("מעבד מזון", "Food processor", "מעבד\\s+(?:ה)?מזון", "food\\s+processor"),
    _term("תנור", "Oven", "תנור|תנורים", "oven"),
    _term("סיר לחץ", "Pressure cooker", "סיר\\s+(?:ה)?לחץ", "pressure\\s+cooker|instant\\s*pot"),
    _term("סיר בישול איטי", "Slow cooker", "סיר\\s+בישול\\s+איטי|סלואו\\s+קוקר", "slow\\s+cooker|crock\\s*pot"),
    _term("סיר טיגון באוויר", "Air fryer", "סיר\\s+טיגון\\s+(?:ב)?אוויר|נינג'ה|אייר\\s*פרייר", "air\\s*fryer"),
    _term("סיר", "Pot", "סיר|סירים", "pot|saucepan|stockpot|dutch\\s+oven"),
    _term("מחבת", "Frying pan", "מחבת|מחבתות", "frying\\s+pan|skillet|fry\\s+pan|saut[eé]\\s+pan"),
    _term("ווק", "Wok", "ווק", "wok"),
    _term("גריל", "Grill", "גריל|מנגל", "grill|barbecue|bbq"),
    _term("טוסטר אובן", "Toaster oven", "טוסטר\\s+אובן", "toaster\\s+oven"),
    _term(
        "תבנית אינגליש קייק",
        "Loaf pan",
        "תבני(?:ו)?ת\\s+(?:ה)?אינגליש\\s+קייק|תבני(?:ו)?ת\\s+(?:ה)?לחם|תבני(?:ו)?ת\\s+(?:ה)?כיכר",
        "loaf\\s+pans?",
        replaces="תבנית אפייה",
    ),
    _term(
        "תבנית קפיצית",
        "Springform pan",
        "תבנית\\s+(?:ה)?קפיצית|תבנית\\s+(?:ה)?שקע|תבנית\\s+עם\\s+תחתית\\s+נשלפת",
        "springform\\s+pans?",
        replaces="תבנית אפייה",
    ),
    _term(
        "תבנית מאפינס",
        "Muffin tin",
        "תבני(?:ו)?ת\\s+(?:ה)?מאפינס|תבני(?:ו)?ת\\s+(?:ה)?שקעים",
        "muffin\\s+(?:tin|pan)s?|cupcake\\s+pans?",
        replaces="תבנית אפייה",
    ),
    _term(
        "תבנית בונדט",
        "Bundt pan",
        "תבנית\\s+(?:ה)?בונדט|תבנית\\s+(?:ה)?קוגלהוף|תבנית\\s+(?:ה)?צינור",
        "bundt\\s+pans?|tube\\s+pans?",
        replaces="תבנית אפייה",
    ),
    _term(
        "תבנית טארט",
        "Tart pan",
        "תבנית\\s+(?:ה)?טארט|תבנית\\s+(?:ה)?פאי",
        "tart\\s+pans?|pie\\s+(?:dish|pan|plate)s?",
        replaces="תבנית אפייה",
    ),
    _term(
        "תבנית אפייה",
        "Baking pan",
        "תבנית|תבניות|תבנית\\s+אפייה|תבנית\\s+תנור|מגש\\s+אפייה",
        "baking\\s+(?:pan|dish|sheet|tray)s?|sheet\\s+pans?|cake\\s+pans?|casserole\\s+dish",
    ),
    _term("מטרפה", "Whisk", "מטרפה|מטרפת\\s+יד", "whisk"),
    _term("מרית", "Spatula", "מרית|מריית", "spatula"),
    _term("כף עץ", "Wooden spoon", "כף\\s+עץ|כפות\\s+עץ", "wooden\\s+spoon"),
    _term("מערוך", "Rolling pin", "מערוך", "rolling\\s+pin"),
    _term("פומפייה", "Grater", "פומפייה|פומפיה|מגררת", "grater|microplane|zester"),
    _term("קולפן", "Peeler", "קולפן", "peeler"),
    _term("מסננת", "Sieve", "מסננת|משמרת|מסננות", "sieve|strainer|colander"),
    _term("שקית זילוף", "Piping bag", "שקית\\s+זילוף|שק\\s+זילוף|משפך\\s+זילוף", "piping\\s+bag|pastry\\s+bag"),
    _term("מדחום", "Thermometer", "מדחום|מד\\s+חום", "thermometer"),
    _term("רשת צינון", "Cooling rack", "רשת\\s+(?:ה)?צינון|רשת\\s+(?:ה)?קירור", "cooling\\s+rack|wire\\s+rack"),
    _term("קרש חיתוך", "Cutting board", "קרש\\s+(?:ה)?חיתוך|קרש", "cutting\\s+board|chopping\\s+board"),
    _term("מטחנת בשר", "Meat grinder", "מטחנת\\s+בשר", "meat\\s+grinder"),
    _term("מכונת לחם", "Bread machine", "מכונת\\s+לחם|אופה\\s+לחם", "bread\\s+machine|bread\\s+maker"),
    _term("מכונת פסטה", "Pasta machine", "מכונת\\s+פסטה", "pasta\\s+machine|pasta\\s+maker"),
    _term(
        "בד חיתול", "Cheesecloth", "חיתול\\s+(?:ה)?בד|בד\\s+(?:ה)?חיתול|בד\\s+גבינה|בד\\s+כותנה", "cheesecloth|muslin"
    ),
    _term("שיפודים", "Skewers", "שיפוד|שיפודים", "skewers?"),
    _term("סיר אידוי", "Steamer", "סיר\\s+אידוי|סל\\s+אידוי|מאדה", "steamer"),
    _term("מנדולינה", "Mandoline", "מנדולינה", "mandoline"),
)
_GENERIC_REPLACEMENTS = {"סיר": ("סיר לחץ", "סיר בישול איטי", "סיר טיגון באוויר", "סיר אידוי"), "בלנדר": ("בלנדר מוט",)}


def find_tools(texts: list[str], hebrew: bool) -> list[str]:
    """כלים שהשלבים מזכירים, בסדר הופעתם, בשפת המתכון."""
    joined = normalize_punctuation("\n".join(texts))
    hits: list[tuple[int, ToolTerm]] = []
    for term in TOOL_TERMS:
        if match := term.pattern.search(joined):
            hits.append((match.start(), term))

    found = {term.he for _, term in hits}
    tools: list[str] = []
    for _, term in sorted(hits, key=lambda hit: hit[0]):
        if any(other.replaces == term.he for _, other in hits):
            continue
        if any(specific in found for specific in _GENERIC_REPLACEMENTS.get(term.he, ())):
            continue
        name = term.he if hebrew else term.en
        if name not in tools:
            tools.append(name)
    return tools
