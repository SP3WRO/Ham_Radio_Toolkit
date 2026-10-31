#!/usr/bin/env python3
"""
Pobiera kalendarz zawodów VHF/UHF/SHF ze strony PK-UKF i zapisuje go jako JSON.

Użycie:
    python scraper/scrape.py                      # pobiera bieżący (i następny, jeśli jest) rok
    python scraper/scrape.py --year 2026          # konkretny rok
    python scraper/scrape.py --html plik.html --year 2026   # parsowanie lokalnego pliku (testy)

Plik wynikowy jest deterministyczny (brak znacznika "czas pobrania"),
więc zmienia się tylko wtedy, gdy zmieniła się treść kalendarza.
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

import requests
from bs4 import BeautifulSoup

URL_TEMPLATE = "https://pk-ukf.pl/kalendarz-zawodow-{year}/"
USER_AGENT = "HamRadioToolkit-ContestScraper/1.0 (+https://github.com/)"

MONTHS = {
    "styczen": 1, "luty": 2, "marzec": 3, "kwiecien": 4, "maj": 5, "czerwiec": 6,
    "lipiec": 7, "sierpien": 8, "wrzesien": 9, "pazdziernik": 10, "listopad": 11, "grudzien": 12,
}
# obsługa odmian ("stycznia", "lutego", "maja" ...) – dopasowanie po rdzeniu
MONTH_STEMS = {
    "stycz": 1, "lut": 2, "mar": 3, "kwie": 4, "maj": 5, "czerw": 6,
    "lip": 7, "sierp": 8, "wrze": 9, "pazdz": 10, "listop": 11, "grud": 12,
}


# ---------------------------------------------------------------- helpers

def strip_accents(s: str) -> str:
    s = s.replace("ł", "l").replace("Ł", "L")
    return "".join(c for c in unicodedata.normalize("NFKD", s) if not unicodedata.combining(c))


def clean(s: str) -> str:
    s = s.replace("\xa0", " ").replace("​", "").replace("﻿", "")
    return re.sub(r"\s+", " ", s).strip()


def month_number(word: str) -> int | None:
    w = strip_accents(word).lower()
    if w in MONTHS:
        return MONTHS[w]
    for stem, n in MONTH_STEMS.items():
        if w.startswith(stem):
            return n
    return None


DASH = r"\s*[-–—]\s*"


def parse_date_range(text: str, default_year: int) -> tuple[str | None, str | None]:
    """'7-8 Marzec 2026', '31 Październik – 1 Listopad 2026', '??-?? Lipiec 2026'
    -> ('2026-03-07', '2026-03-08'); nieznane -> (None, None)."""
    t = clean(text)
    if "?" in t:
        return None, None
    # dzień miesiąc – dzień miesiąc rok
    m = re.match(rf"^(\d{{1,2}})\s+(\w+){DASH}(\d{{1,2}})\s+(\w+)\s+(\d{{4}})$", t)
    if m:
        y = int(m.group(5))
        m1, m2 = month_number(m.group(2)), month_number(m.group(4))
        if m1 and m2:
            y1 = y - 1 if m1 > m2 else y
            return _iso(y1, m1, int(m.group(1))), _iso(y, m2, int(m.group(3)))
    # dzień-dzień miesiąc rok
    m = re.match(rf"^(\d{{1,2}}){DASH}(\d{{1,2}})\s+(\w+)(?:\s+(\d{{4}}))?$", t)
    if m:
        y = int(m.group(4) or default_year)
        mo = month_number(m.group(3))
        if mo:
            d1, d2 = int(m.group(1)), int(m.group(2))
            end_mo, end_y = mo, y
            if d2 < d1:  # np. 30-1 -> przejście miesiąca
                end_mo = mo % 12 + 1
                end_y = y + (1 if mo == 12 else 0)
            return _iso(y, mo, d1), _iso(end_y, end_mo, d2)
    # dzień miesiąc rok
    m = re.match(r"^(\d{1,2})\s+(\w+)(?:\s+(\d{4}))?$", t)
    if m:
        mo = month_number(m.group(2))
        if mo:
            d = _iso(int(m.group(3) or default_year), mo, int(m.group(1)))
            return d, d
    return None, None


def _iso(y: int, m: int, d: int) -> str | None:
    try:
        return dt.date(y, m, d).isoformat()
    except ValueError:
        return None


def parse_time_range(text: str) -> tuple[str | None, str | None]:
    """'14.00 – 18.00', '18:0 – 22:00' -> ('14:00', '18:00')."""
    t = clean(text)
    m = re.match(rf"^(\d{{1,2}})[:.](\d{{1,2}}){DASH}(\d{{1,2}})[:.](\d{{1,2}})$", t)
    if not m:
        return None, None
    h1, m1, h2, m2 = (int(x) for x in m.groups())
    if not (0 <= h1 < 24 and 0 <= m1 < 60 and 0 <= h2 < 24 and 0 <= m2 < 60):
        return None, None
    return f"{h1:02d}:{m1:02d}", f"{h2:02d}:{m2:02d}"


def build_utc(start_date, end_date, start_time, end_time):
    """Zwraca znaczniki ISO 8601 UTC początku i końca zawodów (lub None)."""
    if not (start_date and start_time and end_time):
        return None, None
    sd = dt.date.fromisoformat(start_date)
    ed = dt.date.fromisoformat(end_date) if end_date else sd
    st = dt.time.fromisoformat(start_time)
    et = dt.time.fromisoformat(end_time)
    start = dt.datetime.combine(sd, st, tzinfo=dt.timezone.utc)
    end = dt.datetime.combine(ed, et, tzinfo=dt.timezone.utc)
    if end <= start:  # np. 1-dniowe 22:00–02:00 albo 12-14 sierpnia 07:00–07:00
        end = dt.datetime.combine(sd + dt.timedelta(days=1), et, tzinfo=dt.timezone.utc)
        if ed > sd:
            end = dt.datetime.combine(ed, et, tzinfo=dt.timezone.utc)
    if end <= start:
        return None, None
    fmt = "%Y-%m-%dT%H:%M:%SZ"
    return start.strftime(fmt), end.strftime(fmt)


def parse_log_deadline(raw: str | None, contest_end: str | None, year: int) -> tuple[str | None, bool]:
    """Zwraca (data YYYY-MM-DD, czy_wyliczona).

    '2 Styczeń 2026 (24 godziny)' -> data wprost.
    '30 dni po zawodach'          -> koniec zawodów + 30 dni.
    Gdy data wprost wypada przed końcem zawodów lub ma inny rok (błąd na stronie,
    np. '23 Luty' dla zawodów 15 marca), a podano '(8dni)', liczymy od końca zawodów.
    """
    if not raw:
        return None, False
    end = dt.date.fromisoformat(contest_end) if contest_end else None
    days = None
    dm = re.search(r"(\d+)\s*dni", raw)
    if dm:
        days = int(dm.group(1))
    hm = re.search(r"(\d+)\s*godzin", raw)
    if hm and not dm:
        days = max(1, round(int(hm.group(1)) / 24))

    explicit = None
    lm = re.match(r"^(\d{1,2}\s+\w+(?:\s+\d{4})?)", raw)
    if lm and not re.match(r"^\d+\s*dni", raw):
        explicit, _ = parse_date_range(lm.group(1), year)

    if explicit:
        ok = end is None or (explicit >= end.isoformat() and explicit[:4] in (str(end.year), str(end.year + 1)))
        if ok or days is None or end is None:
            return explicit, False
    if days is not None and end is not None:
        return (end + dt.timedelta(days=days)).isoformat(), True
    return None, False


def cell_link(td) -> str | None:
    a = td.find("a", href=True)
    if not a:
        return None
    href = a["href"].strip()
    return href if href.startswith("http") else None


def cell_text(td) -> str | None:
    t = clean(td.get_text(" "))
    return None if t in ("", "–", "-", "—") else t


def make_id(year: int, name: str, date_raw: str, band: str | None) -> str:
    key = f"{year}|{strip_accents(name).lower()}|{date_raw}|{band or ''}"
    return hashlib.sha1(key.encode()).hexdigest()[:12]


# ---------------------------------------------------------------- parsing

def parse_calendar(html: str, year: int) -> dict:
    soup = BeautifulSoup(html, "html.parser")

    modified = None
    meta = soup.find("meta", attrs={"property": "article:modified_time"})
    if meta and meta.get("content"):
        modified = meta["content"]

    version = None
    vm = re.search(r"V\.\s?(\d+(?:\.\d+)*)", soup.get_text(" "))
    if vm:
        version = vm.group(1)

    contests = []
    month = None
    for tr in soup.find_all("tr"):
        cells = tr.find_all(["td", "th"])
        if not cells:
            continue
        first = clean(cells[0].get_text(" "))
        filled = [c for c in cells if clean(c.get_text(" "))]

        # wiersz nagłówka miesiąca, np. "Styczeń 2026"
        mm = re.match(r"^(\w+)\s+(\d{4})$", first)
        if mm and month_number(mm.group(1)) and len(filled) == 1:
            month = month_number(mm.group(1))
            continue

        if not first.isdigit() or len(cells) < 9:
            continue  # nagłówek kolumn, "powrót na początek" itp.

        lp, date_td, time_td, name_td, band_td, org_td, pl_td, en_td, log_td = cells[:9]
        name = cell_text(name_td)
        if not name:
            continue
        date_raw = cell_text(date_td) or ""
        time_raw = cell_text(time_td) or ""
        start_date, end_date = parse_date_range(date_raw, year)
        start_time, end_time = parse_time_range(time_raw)
        start_utc, end_utc = build_utc(start_date, end_date, start_time, end_time)
        log_raw = cell_text(log_td)
        log_date, log_computed = parse_log_deadline(log_raw, end_date or start_date, year)

        band = cell_text(band_td)
        # get_text("") – bo strona dzieli pogrubienie w środku słowa: <b>Contest</b> 3<b>cm</b>
        name = clean(name_td.get_text("")) or name
        contests.append({
            "id": make_id(year, name, date_raw, band),
            "name": name,
            "major": bool(name_td.find(["strong", "b"])),
            "month": month,
            "date_raw": date_raw,
            "start_date": start_date,
            "end_date": end_date,
            "time_utc_raw": time_raw,
            "start_utc": start_utc,
            "end_utc": end_utc,
            "band": band,
            "organizer": cell_text(org_td),
            "rules_pl": cell_link(pl_td),
            "rules_en": cell_link(en_td),
            "log_deadline_raw": log_raw,
            "log_deadline": log_date,
            "log_deadline_computed": log_computed,
            "is_spac": name.upper().startswith("SPAC"),
        })

    contests.sort(key=lambda c: (c["start_date"] or f"{year}-{(c['month'] or 12):02d}-99", c["start_utc"] or "", c["name"]))
    return {
        "year": year,
        "source_url": URL_TEMPLATE.format(year=year),
        "source_version": version,
        "source_modified": modified,
        "count": len(contests),
        "contests": contests,
    }


# ---------------------------------------------------------------- main

def fetch(year: int) -> str | None:
    url = URL_TEMPLATE.format(year=year)
    r = requests.get(url, headers={"User-Agent": USER_AGENT}, timeout=30)
    if r.status_code == 404:
        return None
    r.raise_for_status()
    r.encoding = r.encoding or "utf-8"
    return r.text


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--year", type=int, action="append", help="rok (można podać kilka razy)")
    ap.add_argument("--html", help="parsuj lokalny plik HTML zamiast pobierać")
    ap.add_argument("--out", default="data", help="katalog wyjściowy (domyślnie: data)")
    args = ap.parse_args(argv)

    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)

    today = dt.date.today()
    years = args.year or [today.year, today.year + 1]

    results = []
    for y in years:
        if args.html:
            html = Path(args.html).read_text(encoding="utf-8")
        else:
            try:
                html = fetch(y)
            except requests.RequestException as e:
                print(f"[{y}] błąd pobierania: {e}", file=sys.stderr)
                if y == today.year and not args.year:
                    return 1
                # przejściowy błąd dla innego roku: zachowaj poprzednie dane
                prev = out_dir / f"contests-{y}.json"
                if prev.exists():
                    results.append(json.loads(prev.read_text(encoding="utf-8")))
                continue
            if html is None:
                print(f"[{y}] brak strony kalendarza – pomijam")
                continue
        data = parse_calendar(html, y)
        if data["count"] == 0:
            print(f"[{y}] nie znaleziono żadnych zawodów – czy zmienił się układ strony?", file=sys.stderr)
            if y == today.year or args.year:
                return 2  # nie nadpisujemy dobrych danych pustymi
            continue
        write_json(out_dir / f"contests-{y}.json", data)
        results.append(data)
        print(f"[{y}] zapisano {data['count']} zawodów")

    if results:
        combined = {
            "years": [d["year"] for d in results],
            "count": sum(d["count"] for d in results),
            "contests": [dict(c, year=d["year"]) for d in results for c in d["contests"]],
        }
        write_json(out_dir / "contests.json", combined)
    return 0


def write_json(path: Path, data) -> None:
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    sys.exit(main())
