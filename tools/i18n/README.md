# Tłumaczenia / Translations

The app is available in English (default, `res/values`), Polish, German, Czech and Slovak.

## UI texts — `strings_*.tsv`

Each `.tsv` file in this folder is one translation table: one row per text, columns separated by a TAB:

```
key	en	pl	de	cs	sk
```

- Placeholders: `%1$s`, `%2$d` … (same set in every column). A literal percent sign next to a placeholder is `%%`.
- New line inside a text: `\n`.
- Plurals: key `plural:name`, value `one:…|few:…|many:…|other:…` (only the quantities the language uses).
- String arrays: key `array:name`, items separated by ` || `.

After editing a table, regenerate the Android resources (writes `app/src/main/res/values*/strings_*.xml`):

```
powershell -ExecutionPolicy Bypass -File tools/i18n/gen.ps1            # all tables
powershell -ExecutionPolicy Bypass -File tools/i18n/gen.ps1 -Tsv tools/i18n/strings_logger.tsv
```

Do not edit the generated XML files by hand — the next run of `gen.ps1` overwrites them.

## Cheat sheets — `app/src/main/assets/vademecum/*.tsv`

DXCC entities, Q codes, CW abbreviations, SP call areas and the phonetic alphabet are data tables with one column
per language (`en`, `pl`, `de`, `cs`, `sk`). They are read directly by the app; an empty cell falls back to English.

Translations into German, Czech and Slovak were made with the help of AI — corrections from native speakers are welcome.
