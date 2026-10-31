# Ham Radio Toolkit

Zestaw narzędzi dla krótkofalowców na Androida. W jednej aplikacji łączy propagację, DX Cluster, log łączności, APRS, przemienniki, satelity, kalendarz zawodów i dyplomów, kalkulatory oraz dekodery FT8/FT4, RTTY, CW, SSTV, DSSTV i Horus Binary.

**Pobieranie:** plik APK znajdziesz w zakładce [Releases](https://github.com/SP3WRO/Ham_Radio_Toolkit/releases).

**iPhone / przeglądarka:** wersja WWW (PWA) jest w katalogu [`web/`](web/README.md) – instalacja na serwerze i lista przeniesionych ekranów.

Aplikacja jest w fazie beta. Wymaga Androida 7.0 lub nowszego. Interfejs jest dostępny po angielsku, polsku, niemiecku, czesku i słowacku, a język i motyw (jasny, ciemny lub systemowy) zmienisz w ustawieniach. Tłumaczenia powstały z pomocą AI, więc poprawki są mile widziane.

## Główne ekrany

Dolne menu prowadzi do pięciu ekranów.

### Home
Czas UTC i lokalny, lokator QTH z GPS, współrzędne i wysokość, stacje APRS w okolicy oraz najbliższy przemiennik z częstotliwościami, emisją i tonem CTCSS.

### Propagacja
- Parametry słoneczne: SFI, liczba plam (SN), indeksy A i K
- Warunki na pasmach KF w dzień i w nocy
- Mapy propagacji troposferycznej UKF Tropocast (ICON-EU) dla Europy i wybranych krajów
- Ostatnie pobrane dane, gdy nie ma połączenia

### DX Cluster
- Spoty z DX Summit z wyszukiwaniem i filtrami pasma, emisji oraz kraju
- Odświeżanie ręczne, co 1 minutę lub co 5 minut
- Oznaczenia krajów, których nie masz jeszcze w logu, i stacji z obserwowanych akcji dyplomowych
- Dodawanie własnych spotów i szybkie logowanie QSO ze spotu

### Logger
- Dodawanie i edycja QSO, także z automatycznym wyszukaniem danych stacji
- Wykrywanie dubli, historia łączności ze stacją oraz odległość i azymut
- Wyszukiwanie po znaku, paśmie, emisji, kraju i dacie
- Import i eksport ADIF
- Wysyłanie QSO do QRZ Logbook (wymaga subskrypcji QRZ) i eQSL
- Statystyki: QSO według pasm i emisji, kraje DXCC, parki POTA i obiekty WWFF

Statystyki pokazują kraje zrobione (worked). Aplikacja nie pobiera jeszcze potwierdzeń z LoTW ani eQSL.

### Przybornik
Pozostałe narzędzia, opisane poniżej.

## Przybornik

### Na antenie
- **Wyszukiwarka znaków:** kraj DXCC, strefy CQ/ITU i czas lokalny działają bez internetu. Dane stacji pochodzą z rejestru UKE, a lokator z PSK Reporter, APRS-IS, Twojego logu, QRZCQ, HamQTH lub HamDB. Wynik obejmuje też odległość, azymut krótką i długą drogą oraz Twoje łączności z tą stacją.
- **Aktywacje na żywo:** spoty POTA i WWFF z sortowaniem według odległości i logowaniem QSO. Spoty SOTA nie są wyświetlane, ponieważ regulamin API SOTA wymaga wcześniejszej zgody.
- **APRS:** odbiór stacji z APRS-IS w wybranym promieniu, w tym ich ruchu, wysokości, danych PHG i pogody, oraz ręczne nadawanie własnej pozycji. Bez znaku wywoławczego aplikacja działa tylko w trybie nasłuchu.
- **Przemienniki:** wyszukiwanie w wybranym promieniu i filtr sprawnych przemienników. Źródła to przemienniki.eu, przemienniki.net, HearHam, repeatermap.de i RepeaterBook (wymaga tokenu), a szczegóły DMR pochodzą z RadioID. Poza Polską źródło przełącza się samo, chyba że wybierzesz je ręcznie.
- **Satelity:** aktualizacja danych TLE, lista przelotów z AOS i LOS, skyplot, kompas z azymutem i elewacją, częstotliwości z AMSAT i SatNOGS oraz przypomnienia o przelotach.
- **Beacony NCDXF:** harmonogram z informacją, która stacja nadaje teraz i kiedy nadają pozostałe.

### Emisje cyfrowe i CW
Telefon odbiera sygnał mikrofonem, więc wystarczy położyć go przy głośniku radia. Dekodery nie nadają.

| Dekoder | Możliwości | Plik WAV |
| --- | --- | --- |
| **FT8 / FT4** | filtr „Tylko CQ”, lista częstotliwości, sprawdzanie znaku nadawcy po dotknięciu wiadomości; zegar telefonu musi być dokładny do ±1 s | tak |
| **RTTY** | ITA2, automatyczne strojenie AFC, widmo, przesuw 170–850 Hz, szybkość 45,45–100 Bd, odwrócona polaryzacja, USOS, blokada szumów, nagrywanie | tak |
| **CW** | automatyczne dopasowanie prędkości i wyszukiwanie tonu; najlepiej działa przy czystym sygnale | nie |
| **SSTV** | Robot 36/72, PD 50–290, Martin 1/2, Scottie 1/2/DX, Wraase SC2-180, HF Fax i Raw; automatyczne wykrywanie VIS i zapis obrazów | tak |
| **DSSTV (HamDRM)** | zgodny z EasyPal i QSSTV; przez mikrofon działają tryby 4-QAM i 16-QAM przy cichym otoczeniu, a najpewniejszy jest odbiór kablem z wyjścia audio radia | tak |
| **Balony (Horus 4FSK)** | Horus Binary 4FSK 100 Bd: pozycja, wysokość, prędkość, temperatura i bateria balonu oraz pokazanie go na mapie | tak |

**Trener CW** uczy alfabetu Morse'a metodą Kocha. Ma regulowaną prędkość znaku i prędkość efektywną (Farnsworth), wysokość tonu i liczbę grup, a do kolejnej lekcji zachęca po osiągnięciu 90% skuteczności.

### Planowanie i mapy
- **Kalendarz zawodów:** dane SP9CXN, Polskiego Klubu UKF i QRZ.RU, filtry zawodów polskich, międzynarodowych, UKF, cyfrowych i z kraju pobytu oraz dodawanie do kalendarza telefonu. Bez internetu dostępny jest kalendarz wbudowany z wybranymi dużymi zawodami.
- **Dyplomy:** trwające i nadchodzące akcje z radiodyplom.pl, obserwowanie akcji, Twój wynik w rankingu i szacunek punktów z Twojego logu. Wiążący jest ranking radiodyplom.pl.
- **Za granicą:** zasady licencji CEPT T/R 61-01, znak do użycia w danym kraju, prefiksy dla gości, Region IARU, strefy i czas oraz przemienniki w okolicy. Przed wyjazdem sprawdź aktualne przepisy kraju.
- **Lokator i azymut:** przeliczanie lokatora i współrzędnych, odległość i azymut krótką oraz długą drogą do lokatora, punktu lub znaku, a także mapa azymutalna.
- **Szara linia:** mapa terminatora oraz godziny wschodu i zachodu słońca dla Twojego QTH.
- **Bandplan:** plany pasm IARU dla Regionów 1, 2 i 3, z polską praktyką w Regionie 1.
- **Obiekty do aktywacji:** obiekty POTA, SOTA i WWFF w pobliżu Twojej pozycji.

### Kalkulatory
- **Anteny:** dipol, EFHW, ground plane ¼λ, J-pole, Yagi 3-elementowa, prostokąt Moxona, pętla Quad/Delta
- **Dopasowanie i zasilanie:** odcinki kabla, transformator λ/4, SWR, moc i ERP/EIRP, czas pracy na akumulatorze
- **Tłumienność kabli:** straty i moc na antenie dla wybranego kabla, częstotliwości i długości

Wyniki dla anten to wymiary startowe, które trzeba dostroić w praktyce.

### Ściągi
Kraje DXCC, kody Q, raporty RST, okręgi SP, slang, alfabet Morse'a i literowanie.

## Powiadomienia i widżet

- **Przeloty satelitów:** przypomnienie przed przelotem obserwowanego satelity, z wyborem wyprzedzenia i minimalnej elewacji
- **Nowe kraje w DX Clusterze:** sprawdzanie co 15 minut spotów krajów, których nie masz w logu
- **Burze geomagnetyczne:** sprawdzanie co godzinę progu Kp i ostrzeżeń NOAA
- **Widżet „Propagacja i satelity”:** wskaźniki słoneczne, warunki na pasmach KF i najbliższy przelot obserwowanego satelity

Powiadomienia włączysz w ustawieniach aplikacji.

## Instalacja

1. Pobierz plik APK z zakładki [Releases](https://github.com/SP3WRO/Ham_Radio_Toolkit/releases) na telefon.
2. Otwórz go i zezwól na instalację z tego źródła, jeśli Android o to zapyta.
3. W ustawieniach aplikacji wpisz swój znak wywoławczy. Bez znaku możesz korzystać z aplikacji jako nasłuchowiec.

Nowsze wydania instalują się jako aktualizacja bez utraty danych.

### Uprawnienia

| Uprawnienie | Do czego służy |
| --- | --- |
| Lokalizacja | lokator QTH, przemienniki i stacje APRS w pobliżu, przeloty satelitów, odległość do stacji; lokator możesz też wpisać ręcznie |
| Mikrofon | dekodery |
| Internet | dane z serwisów zewnętrznych |
| Powiadomienia | przypomnienia i alerty |
| Dokładne alarmy | przypomnienie o przelocie co do minuty; bez tej zgody może przyjść kilka minut później |

### Konta w serwisach

Podstawowe funkcje działają bez kont. Dane logowania są potrzebne tylko do tych usług:

| Usługa | Co jest potrzebne |
| --- | --- |
| QRZ Logbook | klucz API i subskrypcja QRZ |
| eQSL | login i hasło |
| HamQTH | bezpłatne konto |
| QRZCQ | konto |
| RepeaterBook | token aplikacji i e-mail kontaktowy |

### Prywatność

Hasła i klucze są zapisane tylko w telefonie i nie trafiają do kopii zapasowej w chmurze. GPS działa tylko wtedy, gdy aplikacja jest otwarta. Serwisy zewnętrzne dostają tylko obszar wyszukiwania, a Twoja pozycja pojawia się w sieci APRS dopiero po naciśnięciu „Nadaj pozycję”.

## Budowanie ze źródeł

Potrzebujesz aktualnego Android Studio z Android SDK 36.

```powershell
git clone https://github.com/SP3WRO/Ham_Radio_Toolkit.git
cd Ham_Radio_Toolkit
.\gradlew assembleDebug
```

Plik APK pojawi się w `app/build/outputs/apk/debug/`. Testy jednostkowe uruchomisz komendą `.\gradlew test`.

### Tłumaczenia

Teksty interfejsu są w plikach `tools/i18n/*.tsv`. Pliki `strings_*.xml` są z nich generowane skryptem `tools/i18n/gen.ps1`, więc zmiany wprowadzaj w TSV, a nie w XML. Szczegóły opisuje [instrukcja tłumaczeń](tools/i18n/README.md).

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

Dziękuję twórcom bezpłatnych serwisów i projektów, z których korzysta aplikacja. Pełna lista źródeł danych z odnośnikami jest w aplikacji, na ekranie **O programie**.

Kalkulator tłumienności kabli powstał na podstawie projektu Coax Calculator autorstwa SQ3SWF Olgierda.

Za silne wsparcie i motywację do działania dziękuję Dorocie SQ3TGY.

Aplikację stworzył z pomocą AI Mateusz SP3WRO.

*W trakcie budowania aplikacji nie ucierpiał żaden tranzystor!*
