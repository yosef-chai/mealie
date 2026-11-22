import pytest

from mealie.services.scraper import recipe_text as rt


@pytest.mark.parametrize(
    ("text", "seconds"),
    [
        pytest.param("מערבבים כ-2 דקות, לשים כ-4 דקות וממשיכים ללוש עוד כ-3 דקות.", 9 * 60, id="sum-of-durations"),
        pytest.param("מתפיחים חצי שעה-שעה.", 30 * 60, id="range-in-words-takes-lower-bound"),
        pytest.param("אופים במשך 30–35 דקות או עד שהעוגה אפויה.", 30 * 60, id="en-dash-range"),
        pytest.param("ממשיכים להקציף כשתי דקות נוספות.", 2 * 60, id="hebrew-number-word"),
        pytest.param("משאירים להגיר במשך 12 שעות לפחות.", 12 * 3600, id="hours"),
        pytest.param("אופים שעה ו-20 דקות.", 80 * 60, id="hour-and-minutes"),
        pytest.param("אופים כשעה וחצי.", 90 * 60, id="hour-and-a-half"),
        pytest.param("Bake for 25 to 30 minutes, then cool for 10 minutes.", 35 * 60, id="english"),
        pytest.param("Simmer for an hour and a half.", 90 * 60, id="english-words"),
        pytest.param("מחממים תנור ל-180 מעלות.", None, id="temperature-is-not-time"),
        pytest.param("אופים רבע שעה לפני שהאורחים מגיעים.", None, id="lead-time-is-not-duration"),
        pytest.param("בשעה טובה מגישים.", None, id="hour-as-a-word"),
    ],
)
def test_step_duration(text: str, seconds: int | None):
    assert rt.step_duration(text) == seconds


@pytest.mark.parametrize(
    ("raw", "expected"),
    [
        pytest.param("4 מנות", rt.YieldValue(servings=4), id="servings-he"),
        pytest.param("ל-8 מנות", rt.YieldValue(servings=8), id="servings-with-prefix"),
        pytest.param("Serves 4-6", rt.YieldValue(servings=4), id="servings-en-range"),
        pytest.param("מספר מנות במתכון המקורי: 20", rt.YieldValue(servings=20), id="servings-label"),
        pytest.param(
            '2 פיצות בקוטר 33 ס"מ', rt.YieldValue(quantity=2, text='פיצות בקוטר 33 ס"מ'), id="count-of-things"
        ),
        pytest.param("תבנית אפייה ריבועית 23*23", rt.YieldValue(text="תבנית אפייה ריבועית 23*23"), id="pan-size"),
        pytest.param("24 cookies", rt.YieldValue(quantity=24, text="cookies"), id="count-en"),
        pytest.param("כ-24 עוגיות", rt.YieldValue(quantity=24, text="עוגיות"), id="approximate-count"),
        pytest.param("1", rt.YieldValue(servings=1), id="bare-number"),
        pytest.param(6, rt.YieldValue(servings=6), id="int"),
        pytest.param(["4", "4 servings"], rt.YieldValue(servings=4), id="list"),
    ],
)
def test_parse_yield(raw, expected: rt.YieldValue):
    assert rt.parse_yield(raw) == expected


@pytest.mark.parametrize(
    ("line", "heading"),
    [
        ("לבצק:", True),
        ("לאורז", True),
        ("לרוטב הירוק", True),
        ("למריחה על הבצק (מומלץ, לא חובה)", True),
        ("רוטב מהיר ללא בישול (לערבב הכל בקערית, אפשר להקפיא שאריות):", True),
        ("תוספות", True),
        ("For the sauce:", True),
        ("לימון", False),
        ("לערבב היטב", False),
        ("1 בצל", False),
        ("מלח", False),
        ("Salt", False),
    ],
)
def test_looks_like_section_heading(line: str, heading: bool):
    assert rt.looks_like_section_heading(line) is heading


@pytest.mark.parametrize(
    ("line", "kind"),
    [
        ("אופן ההכנה:", "instructions"),
        ("הוראות הכנה", "instructions"),
        ("Directions", "instructions"),
        ("מצרכים ל-8 מנות", "ingredients"),
        ("המצרכים", "ingredients"),
        ("Ingredients", "ingredients"),
        ("ציוד נדרש", "equipment"),
        ("Equipment", "equipment"),
        ("טיפים", "notes"),
        ("מקציפים את החלבונים", None),
    ],
)
def test_generic_heading(line: str, kind: str | None):
    assert rt.generic_heading(line) == kind


@pytest.mark.parametrize("name", ["שלב 1", "Step 2:", "null 3", "1", "Paso 4"])
def test_generic_step_names_carry_no_information(name: str):
    assert rt.generic_step_name(name)


@pytest.mark.parametrize("name", ["בצק", "Make the dough"])
def test_real_step_names_are_kept(name: str):
    assert not rt.generic_step_name(name)


def test_strip_step_number_leaves_quantities_alone():
    assert rt.strip_step_number("1. בסיר בינוני שמים שמן") == "בסיר בינוני שמים שמן"
    assert rt.strip_step_number("Step 2: Whisk") == "Whisk"
    assert rt.strip_step_number("3 כפות סוכר") == "3 כפות סוכר"


def test_split_inline_numbering():
    assert rt.split_inline_numbering("אופן ההכנה 1. שמים במעבד מזון. 2. מעבירים לצנצנת. 3. שומרים במקרר.") == [
        "שמים במעבד מזון.",
        "מעבירים לצנצנת.",
        "שומרים במקרר.",
    ]
    # מספור לא רציף, או מספר שהוא כמות, נשארים כמו שהם
    assert rt.split_inline_numbering("מוסיפים 2. כפות סוכר ו-1.5 כוסות קמח") == ["מוסיפים 2. כפות סוכר ו-1.5 כוסות קמח"]
    assert rt.split_inline_numbering("אופן ההכנה: מערבבים הכל") == ["מערבבים הכל"]


@pytest.mark.parametrize("line", ["ראו עוד תוספות לפיצה", "בתאבון!", "Read more", "בואו להכין רוסט במילוי"])
def test_junk_lines(line: str):
    assert rt.is_junk_line(line)


def test_find_tools_in_hebrew_prefers_specific_terms():
    tools = rt.find_tools(
        ["מקציפים במיקסר", "משמנים תבנית אינגליש קייק ומחממים תנור", "מסירים מהאש", "בסיר בינוני מחממים שמן"],
        hebrew=True,
    )
    # "תבנית אינגליש קייק" מחליפה את "תבנית אפייה", ו"מסירים" אינו סיר
    assert tools == ["מיקסר", "תבנית אינגליש קייק", "תנור", "סיר"]


def test_find_tools_in_english():
    assert rt.find_tools(["Whisk in a bowl, pour into a loaf pan and bake in the oven."], hebrew=False) == [
        "Whisk",
        "Loaf pan",
        "Oven",
    ]


def test_strip_shortcodes_returns_captions():
    text, captions = rt.strip_shortcodes(
        "מערבבים.[caption id=״attachment_1״ width=״800״] מערבבים יחד. צילום: עדי חזן[ caption] משאירים"
    )
    assert "caption" not in text
    assert text.split() == ["מערבבים.", "משאירים"]
    assert rt.photo_credits(captions[0]) == ["עדי חזן"]


def test_photo_credit_stops_at_other_roles():
    assert rt.photo_credits("צילום: עידן גרוס. עריכה: רונית שוקרון") == ["עידן גרוס"]
    assert rt.photo_credits("Photo by Jane Doe | Styling: John") == ["Jane Doe"]
