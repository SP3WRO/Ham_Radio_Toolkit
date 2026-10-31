# Kalendarz zawodów SP9CXN → JSON

Co 48 godzin workflow `.github/workflows/contest-calendars.yml` pobiera
[kalendarz zawodów SP9CXN](https://sp9cxn.pzk.pl/kalendarz.html), zamienia go na JSON
i commituje `data/contests.json` **tylko wtedy, gdy coś się zmieniło**.

Strona pokazuje przesuwające się okno ok. 3 miesięcy, więc plik zawiera zawsze
aktualne okno (lista miesięcy w polu `months`) – starsze miesiące znikają razem ze stroną.

Adres pliku dla aplikacji:

```
https://raw.githubusercontent.com/SP3WRO/Ham_Radio_Toolkit/main/tools/sp9cxn-calendar/data/contests.json
```

## Pola jednego wpisu

| Pole | Opis |
|---|---|
| `id` | stabilny identyfikator (hash sekcji, nazwy i początku) |
| `name` | nazwa zawodów (bez znaczników `*`, `??`, `UA i EU`) |
| `section` / `domestic` | `domestic` (krajowe) albo `international` (międzynarodowe) |
| `section_month`, `section_year` | miesiąc z nagłówka tabeli na stronie |
| `start_utc`, `end_utc` | ISO 8601 UTC (`2026-10-03T06:00:00Z`) |
| `start_raw`, `end_raw` | oryginalny tekst daty i godziny |
| `rules_url` | link do regulaminu (adresy względne zamienione na pełne) |
| `rules_pl_url` | link „PL” – tłumaczenie regulaminu lub `null` |
| `rules_pl_machine_translated` | `true`, gdy „PL” to automatyczne tłumaczenie Google |
| `vhf` | nazwa wskazuje zawody UKF (VHF/UHF/SHF, SPAC, MHz…) |
| `modes` | emisje rozpoznane w nazwie: `CW`, `SSB`, `RTTY`, `DIGI` |
| `unconfirmed` | `??` na stronie – termin niepotwierdzony |
| `old_rules` | `*` na stronie – regulamin z poprzedniej edycji |
| `no_ru_by_contacts` | „UA i EU” – łączności ze stacjami z Rosji i Białorusi nie są punktowane |
| `problems` | błędy wykryte w danych źródłowych (patrz niżej), zwykle pusta lista |

### `problems`

- `end_date_fixed` – data końca była błędna (np. rok 2016 albo koniec przed startem); przyjęto godzinę końca w dniu startu lub następnym.
- `end_unparsed` – nie udało się odczytać końca, `end_utc` = `null`.
- `date_outside_section` – data nie pasuje do miesiąca tabeli, w której stoi wpis (prawdopodobna pomyłka na stronie).

## Lokalnie

```bash
cd tools/sp9cxn-calendar
pip install -r requirements.txt
python -m pytest -q tests
python scraper/scrape.py --out data
```

Gdy strona zwróci mniej niż 10 zawodów (np. po zmianie wyglądu), skrypt kończy się błędem
i nie nadpisuje danych.
