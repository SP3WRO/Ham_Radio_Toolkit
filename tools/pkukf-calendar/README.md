# Kalendarz zawodów PK-UKF → JSON

Uwaga, dane udostępnione za zgodą PK-UKF na potrzeby aplikacji Ham Radio Toolkit.
Jeżeli chcesz korzystać z tych danych - zapytaj Zarząd PK-UKF.

Co 48 godzin workflow `.github/workflows/contest-calendars.yml` pobiera
[kalendarz zawodów VHF/UHF/SHF PK-UKF](https://pk-ukf.pl/kalendarz-zawodow-2026/),
zamienia go na JSON i commituje do `data/` **tylko wtedy, gdy coś się zmieniło**.

Adres pliku dla aplikacji:

```
https://raw.githubusercontent.com/SP3WRO/ham_radio_toolkit/main/tools/pkukf-calendar/data/contests.json
```

| Plik | Zawartość |
|---|---|
| `data/contests-<rok>.json` | jeden rok + metadane źródła (`source_version`, `source_modified`) |
| `data/contests.json` | wszystkie dostępne lata (bieżący + następny, gdy PK-UKF go opublikuje) |

## Pola jednego wpisu

| Pole | Opis |
|---|---|
| `id` | stabilny identyfikator (hash nazwy, daty i pasma) |
| `name` | nazwa zawodów |
| `major` | `true`, gdy nazwa jest pogrubiona na stronie (większe zawody) |
| `is_spac` | zawody SPAC |
| `month` | miesiąc z nagłówka tabeli (działa też, gdy data to `??`) |
| `start_date`, `end_date` | `YYYY-MM-DD` lub `null` przy nieznanej dacie |
| `start_utc`, `end_utc` | ISO 8601 UTC (`2026-03-07T14:00:00Z`) lub `null` |
| `band`, `organizer` | tekst ze strony |
| `rules_pl`, `rules_en` | linki do regulaminów lub `null` |
| `log_deadline` | data terminu logów; „30 dni po zawodach” jest przeliczane na datę, `null` tylko gdy nie da się ustalić |
| `log_deadline_computed` | `true`, gdy termin wyliczono z liczby dni (strona podaje „N dni po zawodach” albo błędną datę) |
| `*_raw` | oryginalny tekst ze strony |

## Lokalnie

```bash
cd tools/pkukf-calendar
pip install -r requirements.txt
python -m pytest -q tests
python scraper/scrape.py --out data
```

## Zabezpieczenia

- Gdy strona zwróci tabelę bez zawodów (np. po zmianie wyglądu), skrypt kończy się błędem i nie nadpisuje danych – w Actions pojawi się czerwony krzyżyk i e-mail.
- JSON nie zawiera czasu pobrania, więc commit powstaje tylko przy realnej zmianie kalendarza.

Źródła danych kalendarza: PK-UKF / SP1CNV
