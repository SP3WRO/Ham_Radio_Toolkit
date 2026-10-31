from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scraper"))
from scrape import parse_calendar, parse_dt  # noqa: E402

FIXTURE = Path(__file__).with_name("fixture.html").read_bytes()
DATA = parse_calendar(FIXTURE)


def get(name, start=None):
    return next(c for c in DATA["contests"] if c["name"] == name and (start is None or c["start_utc"].startswith(start)))


def test_meta():
    assert DATA["source_updated"] == "2026-10-20"
    assert DATA["months"] == ["2026-10", "2026-12"]
    assert DATA["count"] == 14


def test_sections_and_relative_link():
    c = get("63 Dni Męstwa i Chwały CW/SSB")
    assert c["domestic"] and c["section"] == "domestic" and c["section_month"] == 10
    assert c["rules_url"] == "https://sp9cxn.pzk.pl/chwala_cwssb_2026.pdf"
    assert c["rules_pl_url"] is None
    assert get("Oceania DX Contest SSB")["section"] == "international"


def test_times():
    c = get("Oceania DX Contest SSB")
    assert (c["start_utc"], c["end_utc"]) == ("2026-10-03T06:00:00Z", "2026-10-04T06:00:00Z")
    assert c["rules_pl_url"] == "https://sp9cxn.pzk.pl/OCDX_2026.pdf"
    assert not c["rules_pl_machine_translated"] and c["modes"] == ["SSB"]


def test_markers():
    hell = get("International Hell 80 m Contest")
    assert hell["old_rules"] and hell["rules_pl_machine_translated"] and "DIGI" in hell["modes"]
    on = get("ON Contest 80/40 m SSB")
    assert on["no_ru_by_contacts"] and on["name"] == "ON Contest 80/40 m SSB"
    t = get("Zawody Testowe")
    assert t["unconfirmed"] and t["rules_url"] == "https://sp9cxn.pzk.pl/zawody_xyz.pdf"
    assert get("IARU UHF/SHF")["rules_pl_url"] is None
    assert get("IARU UHF/SHF")["vhf"] and get("SPAC – 144 MHz")["vhf"]


def test_end_year_typo_fixed():
    c = get("Sunday Contest CW", "2026-10")
    assert c["end_utc"] == "2026-10-18T15:30:00Z"
    assert "end_date_fixed" in c["problems"]


def test_long_contest_kept():
    c = get("Nocne Marki")
    assert c["end_utc"] == "2026-12-22T00:00:00Z" and c["problems"] == []


def test_midnight_24():
    assert get("Test Midnight Contest")["end_utc"] == "2026-12-20T00:00:00Z"
    assert parse_dt("19.12.2026", "24:30") is None


def test_date_outside_section_flagged():
    c = get("Sunday Contest CW", "2026-06")
    assert "date_outside_section" in c["problems"] and c["section_month"] == 12


def test_duplicate_link_name_and_headers_skipped():
    assert get("Narodziny Krótkofalarstwa Polskiego")["name"] == "Narodziny Krótkofalarstwa Polskiego"
    assert all(c["start_raw"][0].isdigit() for c in DATA["contests"])


def test_ids_unique_and_stable():
    ids = [c["id"] for c in DATA["contests"]]
    assert len(set(ids)) == len(ids)
    assert ids == [c["id"] for c in parse_calendar(FIXTURE)["contests"]]


# ---- prawdziwa kopia strony sp9cxn.pzk.pl/kalendarz.html (aktualizacja 20.10.2026)
REAL = parse_calendar(Path(__file__).with_name("real_2026-10.html").read_bytes())


def test_real_page_counts():
    assert REAL["count"] == 176
    assert REAL["months"] == ["2026-10", "2026-11", "2026-12"]
    assert REAL["source_updated"] == "2026-10-20"
    domestic = sum(c["domestic"] for c in REAL["contests"])
    assert (domestic, REAL["count"] - domestic) == (65, 111)
    assert all(c["rules_url"] for c in REAL["contests"])


def test_real_page_source_errors():
    flagged = {(c["name"], c["start_utc"][:10]): c["problems"] for c in REAL["contests"] if c["problems"]}
    assert flagged == {
        ("DARC FT4 Contest", "2026-02-10"): ["date_outside_section"],
        ("Sunday Contest CW", "2026-06-07"): ["date_outside_section"],
        ("Sunday Contest CW", "2026-10-18"): ["end_date_fixed"],
    }
    okdx = next(c for c in REAL["contests"] if c["name"] == "OK DX RTTY Contest")
    assert okdx["end_utc"] == "2026-12-20T00:00:00Z"


def test_real_page_markers():
    c = REAL["contests"]
    assert sum(x["old_rules"] for x in c) == 19
    assert sum(x["no_ru_by_contacts"] for x in c) == 14
    assert not any(x["domestic"] and x["rules_pl_url"] for x in c)
    assert not any(ch in x["name"] for x in c for ch in "*?")
