#!/usr/bin/env python3
"""
Pobiera kalendarz zawodów SP9CXN (https://sp9cxn.pzk.pl/kalendarz.html) i zapisuje go jako JSON.

Strona pokazuje przesuwające się okno ~3 miesięcy: dla każdego miesiąca tabelę zawodów
KRAJOWYCH i MIĘDZYNARODOWYCH. Kolumny: data i godzina początku (UTC), nazwa (link do
regulaminu), [link PL – tylko międzynarodowe], data i godzina końca (UTC).

Użycie:
    python scraper/scrape.py                       # pobiera stronę i zapisuje do data/
    python scraper/scrape.py --html kopia.html      # parsowanie lokalnego pliku (testy)

Wynik jest deterministyczny (bez czasu pobrania) – zmienia się tylko przy zmianie treści.
"""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import re
import sys
import unicodedata
from pathlib import Path
from urllib.parse import urljoin

import requests
from bs4 import BeautifulSoup, NavigableString, Tag

URL = "https://sp9cxn.pzk.pl/kalendarz.html"
USER_AGENT = "HamRadioToolkit-ContestScraper/1.0 (+https://github.com/SP3WRO/Ham_Radio_Toolkit)"

MONTH_STEMS = {
    "stycz": 1, "lut": 2, "mar": 3, "kwie": 4, "maj": 5, "czerw": 6,
    "lip": 7, "sierp": 8, "wrze": 9, "pazdz": 10, "listop": 11, "grud": 12,
}
HEADING = re.compile(r"KALENDARZ\s+ZAWOD\S*\s+(KRAJOWYCH|MI\S*DZYNARODOWYCH)(?:\s+(\S+)\s+(\d{4}))?", re.I)
DATE = re.compile(r"^(\d{1,2})\.(\d{1,2})\.(\d{4})$")
TIME = re.compile(r"^(\d{1,2})[:.](\d{2})$")
UPDATED = re.compile(r"Aktualizacja\s*:?\s*(\d{1,2})\.(\d{1,2})\.(\d{4})", re.I)
NO_RU_BY = re.compile(r"UA\s*i\s*EU")
DAY = dt.timedelta(days=1)
FMT = "%Y-%m-%dT%H:%M:%SZ"

VHF_HINTS = re.compile(r"\bVHF\b|\bUKF\b|\bUHF\b|\bSHF\b|\bSPAC\b|MHz|GHz|\bEME\b|FT8 Activity", re.I)
MODE_HINTS = {
    "CW": re.compile(r"\bCW\b|Telegraph", re.I),
    "SSB": re.compile(r"\bSSB\b|\bFM\b|\bphone\b|fonia", re.I),
    "RTTY": re.compile(r"RTTY", re.I),
    "DIGI": re.compile(r"DIGI|\bFT[48]\b|FT4/FT8|\bPSK|Hell|\bMFSK|\bJT\d", re.I),
}


def strip_accents(s: str) -> str:
    s = s.replace("ł", "l").replace("Ł", "L")
    return "".join(c for c in unicodedata.normalize("NFKD", s) if not unicodedata.combining(c))


def clean(s: str) -> str:
    s = s.replace("\xa0", " ").replace("​", "").replace("﻿", "")
    return re.sub(r"\s+", " ", s).strip()


def month_number(word: str | None) -> int | None:
    if not word:
        return None
    w = strip_accents(word).lower()
    for stem, n in MONTH_STEMS.items():
        if w.startswith(stem):
            return n
    return None


def parse_dt(date_text: str, time_text: str) -> dt.datetime | None:
    d = DATE.match(clean(date_text))
    t = TIME.match(clean(time_text))
    if not d or not t:
        return None
    day, month, year = (int(x) for x in d.groups())
    hour, minute = int(t.group(1)), int(t.group(2))
    try:
        base = dt.datetime(year, month, day, tzinfo=dt.timezone.utc)
    except ValueError:
        return None
    if hour == 24 and minute == 0:  # "24:00" = północ następnego dnia
        return base + DAY
    if hour > 23 or minute > 59:
        return None
    return base.replace(hour=hour, minute=minute)


def absolute(href: str | None) -> str | None:
    if not href:
        return None
    href = href.strip()
    if not href or href.startswith(("#", "mailto:", "javascript:")):
        return None
    return urljoin(URL, href)


def make_id(section: str, name: str, start_raw: str) -> str:
    key = f"sp9cxn|{section}|{strip_accents(name).lower()}|{start_raw}"
    return hashlib.sha1(key.encode()).hexdigest()[:12]


def iter_tables_with_headings(soup: BeautifulSoup):
    """Zwraca (tabela, sekcja, miesiąc, rok) – sekcja z ostatniego nagłówka przed tabelą."""
    heading = None
    for el in soup.descendants:
        if isinstance(el, NavigableString):
            m = HEADING.search(clean(str(el)))
            if m:
                heading = m
        elif isinstance(el, Tag) and el.name == "table" and not el.find("table"):
            # nagłówek może stać przed tabelą albo w jej pierwszym wierszu
            first_row = el.find("tr")
            inner = HEADING.search(clean(first_row.get_text(" "))) if first_row else None
            h = inner or heading
            if h:
                section = "domestic" if h.group(1).upper().startswith("K") else "international"
                yield el, section, month_number(h.group(2)), int(h.group(3)) if h.group(3) else None


def parse_calendar(html: str | bytes) -> dict:
    soup = BeautifulSoup(html, "html.parser")
    text = clean(soup.get_text(" "))

    updated = None
    um = UPDATED.search(text)
    if um:
        try:
            updated = dt.date(int(um.group(3)), int(um.group(2)), int(um.group(1))).isoformat()
        except ValueError:
            pass

    contests = []
    for table, section, sec_month, sec_year in iter_tables_with_headings(soup):
        for tr in table.find_all("tr"):
            cells = tr.find_all("td")
            if len(cells) < 5:
                continue
            c = parse_row(cells, section, sec_month, sec_year)
            if c:
                contests.append(c)

    # ta sama pozycja potrafi wystąpić dwa razy – zostawiamy pierwszą
    unique = {}
    for c in contests:
        unique.setdefault(c["id"], c)
    contests = sorted(unique.values(), key=lambda c: (c["start_utc"] or "9999", c["section"], c["name"]))

    months = sorted({(c["section_year"], c["section_month"]) for c in contests if c["section_month"]})
    return {
        "source": "SP9CXN",
        "source_url": URL,
        "source_updated": updated,
        "months": [f"{y:04d}-{m:02d}" for y, m in months if y],
        "count": len(contests),
        "contests": contests,
    }


def parse_row(cells, section: str, sec_month: int | None, sec_year: int | None) -> dict | None:
    start_raw = f"{clean(cells[0].get_text(' '))} {clean(cells[1].get_text(' '))}"
    end_raw = f"{clean(cells[-2].get_text(' '))} {clean(cells[-1].get_text(' '))}"
    start = parse_dt(cells[0].get_text(" "), cells[1].get_text(" "))
    if start is None:
        return None  # nagłówek tabeli albo pusty wiersz
    end = parse_dt(cells[-2].get_text(" "), cells[-1].get_text(" "))

    name_td = cells[2]
    links = name_td.find_all("a", href=True)
    cell_text = clean(name_td.get_text(" "))
    name = clean(links[0].get_text(" ")) if links and clean(links[0].get_text(" ")) else cell_text
    unconfirmed = "??" in cell_text
    old_rules = bool(re.search(r"\*", cell_text))
    no_ru_by = bool(NO_RU_BY.search(cell_text))
    name = NO_RU_BY.sub("", name.replace("??", "")).replace("*", "")
    name = clean(name)
    if not name:
        return None

    rules_pl = None
    if len(cells) >= 6:
        a = cells[3].find("a", href=True)
        rules_pl = absolute(a["href"]) if a else None

    problems = []
    # Literówka w dacie końca (np. 2016 zamiast 2026) lub koniec przed startem:
    # bierzemy godzinę końca w dniu startu (albo następnym, jeśli wypada wcześniej).
    if end is None or end <= start or (end - start).days > 31:
        if end is not None:
            problems.append("end_date_fixed")
            fixed = start.replace(hour=end.hour, minute=end.minute)
            if fixed <= start:
                fixed += DAY
            end = fixed
        else:
            problems.append("end_unparsed")
    if sec_month and (start.month != sec_month or (sec_year and start.year != sec_year)):
        problems.append("date_outside_section")

    mode_text = f"{name} {cell_text}"
    return {
        "id": make_id(section, name, start_raw),
        "name": name,
        "section": section,
        "domestic": section == "domestic",
        "section_month": sec_month,
        "section_year": sec_year,
        "start_utc": start.strftime(FMT),
        "end_utc": end.strftime(FMT) if end else None,
        "start_raw": start_raw,
        "end_raw": end_raw,
        "rules_url": absolute(links[0]["href"]) if links else None,
        "rules_pl_url": rules_pl,
        "rules_pl_machine_translated": bool(rules_pl and "translate.goog" in rules_pl),
        "vhf": bool(VHF_HINTS.search(name)),
        "modes": [m for m, rx in MODE_HINTS.items() if rx.search(mode_text)],
        "unconfirmed": unconfirmed,
        "old_rules": old_rules,
        "no_ru_by_contacts": no_ru_by,
        "problems": problems,
    }


def fetch() -> bytes:
    r = requests.get(URL, headers={"User-Agent": USER_AGENT}, timeout=30)
    r.raise_for_status()
    return r.content  # bajty – BeautifulSoup sam rozpozna kodowanie z <meta charset>


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--html", help="parsuj lokalny plik HTML zamiast pobierać")
    ap.add_argument("--out", default="data", help="katalog wyjściowy (domyślnie: data)")
    args = ap.parse_args(argv)

    try:
        html = Path(args.html).read_bytes() if args.html else fetch()
    except requests.RequestException as e:
        print(f"Błąd pobierania: {e}", file=sys.stderr)
        return 1

    data = parse_calendar(html)
    if data["count"] < 10:
        print(f"Znaleziono tylko {data['count']} zawodów – czy zmienił się układ strony? Nie zapisuję.", file=sys.stderr)
        return 2

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    (out / "contests.json").write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    flagged = sum(1 for c in data["contests"] if c["problems"])
    print(f"Zapisano {data['count']} zawodów ({', '.join(data['months'])}); z poprawkami/uwagami: {flagged}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
