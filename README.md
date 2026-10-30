# Ham Radio Toolkit

Zestaw narzędzi dla krótkofalowców na Androida: propagacja, DX Cluster, log łączności, APRS, przemienniki, satelity, kalendarz zawodów i dekodery emisji cyfrowych w jednej aplikacji.

**Pobieranie:** plik APK znajdziesz w zakładce [Releases](https://github.com/SP3WRO/Ham_Radio_Toolkit/releases).

Aplikacja jest w fazie beta. Działa na Androidzie 7.0 lub nowszym i jest dostępna po angielsku, polsku, niemiecku, czesku i słowacku. Język można zmienić w ustawieniach aplikacji.

## Funkcje

### Na antenie
- **Ekran główny:** czas UTC, lokator QTH, wysokość n.p.m., najbliższy przemiennik i stacje APRS w okolicy
- **Propagacja:** indeksy słoneczne, warunki na pasmach, indeks Kp, mapy propagacji troposferycznej i widżet na ekran główny
- **DX Cluster:** spoty DX z rozpoznawaniem podmiotów DXCC oraz dodawanie własnych spotów
- **Aktywacje:** POTA, SOTA i WWFF w pobliżu oraz spoty na żywo
- **APRS:** stacje z sieci APRS-IS i wysyłanie własnej pozycji
- **Przemienniki:** bazy dla Polski, Europy i świata, także DMR
- **Satelity:** przewidywanie przelotów, kompas do śledzenia satelity, częstotliwości transponderów i przypomnienia o przelotach
- **Latarnie NCDXF/IARU:** harmonogram z informacją, która latarnia nadaje w tej chwili

### Log i dyplomy
- **Log łączności:** import i eksport ADIF, statystyki oraz wysyłanie QSO do QRZ Logbook i eQSL
- **Wyszukiwarka znaków:** rejestr UKE, HamQTH, HamDB, QRZCQ, RadioID i PSK Reporter
- **Kalendarz zawodów:** imprezy krajowe, międzynarodowe i UKF
- **Dyplomy:** programy, stacje i ranking z radiodyplom.pl oraz postęp na podstawie logu

### Dekodery
Dźwięk dekodowany jest z mikrofonu telefonu, bez dodatkowego sprzętu.

- **FT8 / FT4**
- **CW**, a także trener telegrafii
- **RTTY**
- **SSTV** (dekoder robot36)
- **DSSTV** (HamDRM)
- **Horus Binary** (balony stratosferyczne)

### Narzędzia i wiedza
- **Plany pasm** IARU dla Regionów 1, 2 i 3
- **Licencja CEPT** i prefiksy za granicą
- **Kalkulatory:** m.in. anteny i tłumienie kabli koncentrycznych
- **Linia szarości**, czyli terminator dzień/noc z godzinami wschodu i zachodu słońca dla QTH
- **Lokator** Maidenhead
- **Vademecum:** kody Q, slang, alfabet fonetyczny, podmioty DXCC i okręgi SP

### Powiadomienia
Aplikacja może powiadamiać o przelotach satelitów, nowych krajach DXCC i burzach geomagnetycznych.

## Instalacja

1. Pobierz plik APK z zakładki [Releases](https://github.com/SP3WRO/Ham_Radio_Toolkit/releases) na telefon.
2. Otwórz go i zezwól na instalację z tego źródła, jeśli Android o to zapyta.
3. Po uruchomieniu przyznaj uprawnienia do funkcji, z których chcesz korzystać.

### Uprawnienia

| Uprawnienie | Do czego służy |
| --- | --- |
| Lokalizacja | lokator QTH, obiekty w pobliżu, przeloty satelitów |
| Mikrofon | dekodery emisji cyfrowych |
| Internet | dane z serwisów zewnętrznych |
| Powiadomienia | przypomnienia i alerty |
| Dokładne alarmy | przypomnienie o przelocie co do minuty; bez tej zgody przypomnienie jest przybliżone |

Niektóre usługi wymagają własnego konta lub klucza, który podajesz w ustawieniach aplikacji, np. wysyłanie QSO do QRZ i eQSL, HamQTH oraz RepeaterBook.

## Budowanie ze źródeł

Potrzebujesz aktualnego Android Studio z Android SDK 36.

```powershell
git clone https://github.com/SP3WRO/Ham_Radio_Toolkit.git
cd Ham_Radio_Toolkit
.\gradlew assembleDebug
```

Plik APK pojawi się w `app/build/outputs/apk/debug/`. Testy jednostkowe uruchomisz komendą `.\gradlew test`.

### Tłumaczenia

Teksty interfejsu są w plikach `tools/i18n/*.tsv`. Pliki `strings_*.xml` są z nich generowane skryptem `tools/i18n/gen.ps1`, więc zmiany wprowadzaj w TSV, a nie w XML.

## Licencja

Projekt jest udostępniany na licencji [MIT](LICENSE), bez żadnej gwarancji. Copyright © 2026 Mateusz SP3WRO.

Fragmenty kodu innych autorów zachowują własne licencje:

| Składnik | Zastosowanie | Licencja |
| --- | --- | --- |
| ft8_lib | dekoder FT8 / FT4 | [MIT](app/src/main/java/io/github/sp3wro/hamradiotoolkit/ft8/LICENSE-ft8_lib.txt) |
| robot36 | dekoder SSTV | [0BSD](app/src/main/java/io/github/sp3wro/hamradiotoolkit/sstv/robot36/LICENSE-robot36.txt) |
| horusdemodlib | dekoder Horus Binary | [LGPL-2.1](app/src/main/java/io/github/sp3wro/hamradiotoolkit/horus/LICENSE-horusdemodlib.txt) |
| pdf.js | dekoder JPEG 2000 w DSSTV | [Apache 2.0](app/src/main/java/io/github/sp3wro/hamradiotoolkit/dsstv/LICENSE-pdfjs.txt) |

## Podziękowania

Dziękuję twórcom bezpłatnych serwisów i projektów, z których korzysta aplikacja. Pełna lista źródeł danych z odnośnikami jest w aplikacji, na ekranie **O aplikacji**.

Za silne wsparcie i motywację do działania dziękuję Dorocie SQ3TGY.

Aplikację stworzył z pomocą AI Mateusz SP3WRO.

*W trakcie budowania aplikacji nie ucierpiał żaden tranzystor!*
