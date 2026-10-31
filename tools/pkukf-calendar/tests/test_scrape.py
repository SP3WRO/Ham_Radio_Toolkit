from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scraper"))
from scrape import parse_calendar, parse_date_range, parse_time_range, build_utc  # noqa: E402

FIXTURE = Path(__file__).with_name("fixture_2026.html").read_text(encoding="utf-8")


def by_name(data, name):
    return next(c for c in data["contests"] if c["name"] == name)


def test_counts_and_meta():
    d = parse_calendar(FIXTURE, 2026)
    assert d["count"] == 8
    assert d["source_version"] == "1.03"
    assert d["source_modified"].startswith("2026-09-27")


def test_single_day_and_dotted_time():
    c = by_name(parse_calendar(FIXTURE, 2026), "AGCW VHF/UHF Contest")
    assert c["start_utc"] == "2026-01-01T14:00:00Z"
    assert c["end_utc"] == "2026-01-01T18:00:00Z"
    assert c["major"] and c["month"] == 1
    assert c["rules_pl"] is None and c["rules_en"].startswith("https://www.agcw.de")
    assert c["log_deadline"] == "2026-01-02"


def test_typo_time_and_spac():
    c = by_name(parse_calendar(FIXTURE, 2026), "SPAC Styczeń 144MHz")
    assert c["start_utc"] == "2026-01-06T18:00:00Z"
    assert c["is_spac"] and not c["major"]


def test_weekend_contest():
    c = by_name(parse_calendar(FIXTURE, 2026), "I Próby Subregionalne")
    assert (c["start_utc"], c["end_utc"]) == ("2026-03-07T14:00:00Z", "2026-03-08T14:00:00Z")


def test_cross_month():
    c = by_name(parse_calendar(FIXTURE, 2026), "EME ARRL I-part")
    assert (c["start_date"], c["end_date"]) == ("2026-10-31", "2026-11-01")
    assert c["end_utc"] == "2026-11-01T23:59:00Z"
    assert c["log_deadline"] == "2026-12-01" and c["log_deadline_computed"]  # "30 dni po zawodach"


def test_unknown_date():
    c = by_name(parse_calendar(FIXTURE, 2026), "CQ WW VHF Contest digital")
    assert c["start_date"] is None and c["start_utc"] is None
    assert c["month"] == 7


def test_three_day_ms_sprint():
    c = by_name(parse_calendar(FIXTURE, 2026), "144MHz Meteorscatter Sprint Contest 2026")
    assert (c["start_utc"], c["end_utc"]) == ("2026-08-12T07:00:00Z", "2026-08-14T07:00:00Z")


def test_ids_stable_and_unique():
    a = parse_calendar(FIXTURE, 2026)
    b = parse_calendar(FIXTURE, 2026)
    ids = [c["id"] for c in a["contests"]]
    assert ids == [c["id"] for c in b["contests"]]
    assert len(set(ids)) == len(ids)


def test_helpers():
    assert parse_date_range("08-09 Sierpień 2026", 2026) == ("2026-08-08", "2026-08-09")
    assert parse_time_range("18:0 – 22:00") == ("18:00", "22:00")
    assert parse_time_range("brak") == (None, None)
    assert build_utc("2026-01-01", "2026-01-01", "22:00", "02:00")[1] == "2026-01-02T02:00:00Z"


# ---- prawdziwa kopia strony (wycinek tabel z pk-ukf.pl, stan na 2026-10-31)
REAL = Path(__file__).with_name("real_2026.html").read_text(encoding="utf-8")


def test_real_page_counts():
    d = parse_calendar(REAL, 2026)
    assert d["count"] == 166
    per_month = {}
    for c in d["contests"]:
        per_month[c["month"]] = per_month.get(c["month"], 0) + 1
    assert per_month == {1: 13, 2: 12, 3: 14, 4: 14, 5: 14, 6: 15, 7: 15, 8: 16, 9: 14, 10: 14, 11: 14, 12: 11}
    assert len({c["id"] for c in d["contests"]}) == 166


def test_real_page_split_bold_name():
    names = {c["name"] for c in parse_calendar(REAL, 2026)["contests"]}
    assert "EUROPEAN EME Contest 3cm" in names
    assert "EUROPEAN EME Contest 1,2cm" in names


def test_real_page_log_deadlines():
    d = parse_calendar(REAL, 2026)
    dur_march = next(c for c in d["contests"] if c["name"] == "DUR GHz Activity Contest" and c["start_date"] == "2026-03-15")
    assert dur_march["log_deadline"] == "2026-03-23" and dur_march["log_deadline_computed"]
    mikrofale = next(c for c in d["contests"] if c["name"] == "Mikrofale")
    assert mikrofale["log_deadline"] == "2026-06-10"
    arrl = next(c for c in d["contests"] if c["name"] == "EME ARRL II-part")
    assert arrl["log_deadline"] == "2026-12-29"
    agcw = next(c for c in d["contests"] if c["start_date"] == "2026-01-01")
    assert agcw["log_deadline"] == "2026-01-02" and not agcw["log_deadline_computed"]
    # każdy termin logów, jeśli jest, nie wypada przed końcem zawodów
    for c in d["contests"]:
        if c["log_deadline"] and c["end_date"]:
            assert c["log_deadline"] >= c["end_date"], c["name"]
