# Ham Radio Toolkit

**Language / Język:** [English](#english) | [Polski](#polski)

---

## English

A toolkit for radio amateurs on Android. One app brings together propagation, DX Cluster, a station log, APRS, repeaters, satellites, a contest and award calendar, calculators, and FT8/FT4, RTTY, CW, SSTV, DSSTV and Horus Binary decoders.

**Download:** get the APK from [Releases](https://github.com/SP3WRO/Ham_Radio_Toolkit/releases). The current stable version is 26.11.1.

The app requires Android 7.0 or newer. The interface is available in English, Polish, German, Czech, Slovak and Russian. You can change the language and the theme (light, dark or system) in Settings. Translations were made with the help of AI, so corrections are welcome.

### Main screens

The bottom menu leads to five screens.

#### Home
UTC and local time, QTH locator from GPS, coordinates and altitude, nearby APRS stations, and the nearest repeater with its frequencies, mode and CTCSS tone.

#### Propagation
- Solar data: SFI, sunspot number (SN), A and K indices
- HF band conditions by day and by night
- Tropocast VHF tropospheric propagation maps (ICON-EU) for Europe and selected countries
- The last downloaded data when there is no connection

#### DX Cluster
- DX Summit spots with search and filters for band, mode and country
- Refresh manually, every minute or every 5 minutes
- Markers for countries not yet in your log and for stations of followed award programmes
- Sending your own spots and quick QSO logging from a spot

#### Logger
- Adding and editing QSOs, with automatic station data lookup
- Duplicate detection, history of contacts with a station, distance and bearing
- Search by callsign, band, mode, country and date
- ADIF import and export
- QSO upload to QRZ Logbook (requires a QRZ subscription) and eQSL
- Statistics: QSOs by band and mode, DXCC entities, POTA parks and WWFF references

Statistics show worked countries. The app does not download LoTW or eQSL confirmations yet.

#### Toolbox
All the other tools, described below.

### Toolbox

#### On the air
- **Callsign lookup:** DXCC entity, CQ/ITU zones and local time work offline. Station data comes from the Polish UKE register, RadioID, HamQTH, QRZCQ and HamDB, and the locator from PSK Reporter, APRS-IS, your log, QRZCQ, HamQTH or HamDB. The result also shows distance, short-path and long-path bearing, and your QSOs with that station.
- **Live activations:** POTA and WWFF spots sorted by distance, with QSO logging. SOTA spots are not shown because the SOTA API terms require prior approval.
- **APRS:** receiving APRS-IS stations within a chosen radius, including their movement, altitude, PHG and weather data, and sending your own position manually. The default SSID is -5 and the default map icon is a walker; you can pick another icon or enter your own APRS symbol code, with a preview. Without a callsign the app only listens.
- **Repeaters:** search within a chosen radius and a filter for working repeaters. Sources are przemienniki.eu, przemienniki.net, HearHam and repeatermap.de, and DMR details come from RadioID. Outside Poland the source switches automatically unless you choose one manually.
- **Satellites:** TLE updates, a pass list with AOS and LOS, a sky plot, a compass with azimuth and elevation, frequencies from AMSAT and SatNOGS, and pass reminders.
- **NCDXF beacons:** a schedule showing which station is on the air now and when the others transmit.

#### Digital modes and CW
The phone receives the signal through its microphone, so just place it next to the radio's speaker. The decoders do not transmit.

| Decoder | Features | WAV file |
| --- | --- | --- |
| **FT8 / FT4** | "CQ only" filter, frequency list, sender lookup by tapping a message; the phone clock must be accurate to ±1 s | yes |
| **RTTY** | ITA2, automatic AFC tuning, spectrum, 170–850 Hz shift, 45.45–100 Bd, reversed polarity, USOS, noise squelch, recording | yes |
| **CW** | automatic speed adjustment and tone search; works best with a clean signal | no |
| **SSTV** | Robot 36/72, PD 50–290, Martin 1/2, Scottie 1/2/DX, Wraase SC2-180, HF Fax and Raw; automatic VIS detection and image saving | yes |
| **DSSTV (HamDRM)** | compatible with EasyPal and QSSTV; 4-QAM and 16-QAM work through the microphone in quiet surroundings, and a cable from the radio's audio output is the most reliable | yes |
| **Balloons (Horus 4FSK)** | Horus Binary 4FSK 100 Bd: balloon position, altitude, speed, temperature and battery, and showing it on a map | yes |

The **CW trainer** teaches Morse code with the Koch method. You can set the character speed and the effective (Farnsworth) speed, the tone and the number of groups. It suggests moving to the next lesson at 90% accuracy.

#### Planning and maps
- **Contest calendar:** data from SP9CXN, PK-UKF / SP1CNV and QRZ.RU (the current and next two months, with a link to the original contest page), filters for Polish, international, VHF, digital and country-of-stay contests, and adding contests to the phone calendar. Without internet a built-in calendar with selected major contests is available.
- **Awards:** running and upcoming programmes from radiodyplom.pl, stations active on the bands now, and following programmes. Stations of followed programmes are marked in the DX Cluster.
- **Abroad:** CEPT T/R 61-01 licence rules, the callsign to use in a given country, guest prefixes, IARU Region, zones and time, and nearby repeaters. Check the country's current regulations before you travel.
- **Locator and bearing:** converting between locator and coordinates, distance plus short-path and long-path bearing to a locator, point or callsign, and an azimuthal map.
- **Gray line:** a terminator map with sunrise and sunset times for your QTH.
- **Band plan:** IARU band plans for Regions 1, 2 and 3, with Polish practice in Region 1.
- **Activation references:** POTA, SOTA and WWFF references near your position.

#### Calculators
- **Antennas:** dipole, EFHW, ¼λ ground plane, J-pole, 3-element Yagi, Moxon rectangle, Quad/Delta loop
- **Matching and power supply:** cable sections, λ/4 transformer, SWR, power and ERP/EIRP, battery runtime
- **Cable loss:** loss and power at the antenna for a chosen cable, frequency and length

Antenna results are starting dimensions that need tuning in practice.

#### Cheat sheets
DXCC entities, Q codes, RST reports, SP call areas, ham slang, Morse code and the phonetic alphabet.

### Notifications and widget

- **Satellite passes:** a reminder before a pass of a watched satellite, with a choice of lead time and minimum elevation
- **New countries in the DX Cluster:** checks every 15 minutes for spots of countries not in your log
- **Geomagnetic storms:** checks the Kp threshold and NOAA warnings every hour
- **"Propagation and satellites" widget:** solar indices, HF band conditions and the next pass of a watched satellite

You can turn notifications on in the app's settings.

### Installation

1. Download the APK from [Releases](https://github.com/SP3WRO/Ham_Radio_Toolkit/releases) to your phone.
2. Open it and allow installation from this source if Android asks.
3. Enter your callsign in the app's settings. Without a callsign you can use the app as a listener.

Newer releases install as an update without losing data.

#### Permissions

| Permission | What it is for |
| --- | --- |
| Location | QTH locator, nearby repeaters and APRS stations, satellite passes, distance to stations; you can also enter the locator manually |
| Microphone | decoders |
| Internet | data from external services |
| Notifications | reminders and alerts |
| Exact alarms | a pass reminder on the minute; without this permission it may come a few minutes late |

#### Service accounts

Basic features work without accounts. Login details are only needed for these services:

| Service | What you need |
| --- | --- |
| QRZ Logbook | API key and a QRZ subscription |
| eQSL | login and password |
| HamQTH | free account |
| QRZCQ | account |

#### Privacy

Passwords and keys are stored only on the phone and are not included in cloud backups. GPS works only while the app is open. External services receive only the search area, and your position appears on the APRS network only after you press "Send position".

### Building from source

You need a current Android Studio with Android SDK 36.

```powershell
git clone https://github.com/SP3WRO/Ham_Radio_Toolkit.git
cd Ham_Radio_Toolkit
.\gradlew assembleDebug
```

The APK appears in `app/build/outputs/apk/debug/`. Run the unit tests with `.\gradlew test`.

#### Translations

Interface texts are in the `tools/i18n/*.tsv` files, one column per language. The `strings_*.xml` files are generated from them by `tools/i18n/gen.ps1`, so make changes in the TSV files, not in the XML. See the [translation guide](tools/i18n/README.md) for details.

### License

The project is available under the [MIT](LICENSE) license, without any warranty. Copyright © 2026 Mateusz SP3WRO.

Code by other authors keeps its own license:

| Component | Used for | License |
| --- | --- | --- |
| ft8_lib | FT8 / FT4 decoder | [MIT](app/src/main/java/io/github/sp3wro/hamradiotoolkit/ft8/LICENSE-ft8_lib.txt) |
| robot36 | SSTV decoder | [0BSD](app/src/main/java/io/github/sp3wro/hamradiotoolkit/sstv/robot36/LICENSE-robot36.txt) |
| horusdemodlib | Horus Binary decoder | [LGPL-2.1](app/src/main/java/io/github/sp3wro/hamradiotoolkit/horus/LICENSE-horusdemodlib.txt) |
| pdf.js | JPEG 2000 decoder in DSSTV | [Apache 2.0](app/src/main/java/io/github/sp3wro/hamradiotoolkit/dsstv/LICENSE-pdfjs.txt) |

### Acknowledgements

Thanks to the creators of the free services and projects the app uses. The full list of data sources with links is in the app, on the **About** screen.

The cable loss calculator is based on the Coax Calculator project by Olgierd SQ3SWF.

For strong support and motivation, thanks to Dorota SQ3TGY.

Created with the help of AI by Mateusz SP3WRO.

*No transistors were harmed in the making of this app!*

---

## Polski

Zestaw narzędzi dla krótkofalowców na Androida. W jednej aplikacji łączy propagację, DX Cluster, log łączności, APRS, przemienniki, satelity, kalendarz zawodów i dyplomów, kalkulatory oraz dekodery FT8/FT4, RTTY, CW, SSTV, DSSTV i Horus Binary.

**Pobieranie:** plik APK znajdziesz w zakładce [Releases](https://github.com/SP3WRO/Ham_Radio_Toolkit/releases). Aktualna wersja stabilna to 26.11.1.

Aplikacja wymaga Androida 7.0 lub nowszego. Interfejs jest dostępny po angielsku, polsku, niemiecku, czesku, słowacku i rosyjsku, a język i motyw (jasny, ciemny lub systemowy) zmienisz w ustawieniach. Tłumaczenia powstały z pomocą AI, więc poprawki są mile widziane.

### Główne ekrany

Dolne menu prowadzi do pięciu ekranów.

#### Home
Czas UTC i lokalny, lokator QTH z GPS, współrzędne i wysokość, stacje APRS w okolicy oraz najbliższy przemiennik z częstotliwościami, emisją i tonem CTCSS.

#### Propagacja
- Parametry słoneczne: SFI, liczba plam (SN), indeksy A i K
- Warunki na pasmach KF w dzień i w nocy
- Mapy propagacji troposferycznej UKF Tropocast (ICON-EU) dla Europy i wybranych krajów
- Ostatnie pobrane dane, gdy nie ma połączenia

#### DX Cluster
- Spoty z DX Summit z wyszukiwaniem i filtrami pasma, emisji oraz kraju
- Odświeżanie ręczne, co 1 minutę lub co 5 minut
- Oznaczenia krajów, których nie masz jeszcze w logu, i stacji z obserwowanych akcji dyplomowych
- Dodawanie własnych spotów i szybkie logowanie QSO ze spotu

#### Logger
- Dodawanie i edycja QSO, także z automatycznym wyszukaniem danych stacji
- Wykrywanie dubli, historia łączności ze stacją oraz odległość i azymut
- Wyszukiwanie po znaku, paśmie, emisji, kraju i dacie
- Import i eksport ADIF
- Wysyłanie QSO do QRZ Logbook (wymaga subskrypcji QRZ) i eQSL
- Statystyki: QSO według pasm i emisji, kraje DXCC, parki POTA i obiekty WWFF

Statystyki pokazują kraje zrobione (worked). Aplikacja nie pobiera jeszcze potwierdzeń z LoTW ani eQSL.

#### Przybornik
Pozostałe narzędzia, opisane poniżej.

### Przybornik

#### Na antenie
- **Wyszukiwarka znaków:** kraj DXCC, strefy CQ/ITU i czas lokalny działają bez internetu. Dane stacji pochodzą z rejestru UKE, RadioID, HamQTH, QRZCQ i HamDB, a lokator z PSK Reporter, APRS-IS, Twojego logu, QRZCQ, HamQTH lub HamDB. Wynik obejmuje też odległość, azymut krótką i długą drogą oraz Twoje łączności z tą stacją.
- **Aktywacje na żywo:** spoty POTA i WWFF z sortowaniem według odległości i logowaniem QSO. Spoty SOTA nie są wyświetlane, ponieważ regulamin API SOTA wymaga wcześniejszej zgody.
- **APRS:** odbiór stacji z APRS-IS w wybranym promieniu, w tym ich ruchu, wysokości, danych PHG i pogody, oraz ręczne nadawanie własnej pozycji. Domyślny SSID to -5, a domyślna ikona na mapie to pieszy; możesz wybrać inną ikonę albo wpisać własny kod symbolu APRS, z podglądem. Bez znaku wywoławczego aplikacja działa tylko w trybie nasłuchu.
- **Przemienniki:** wyszukiwanie w wybranym promieniu i filtr sprawnych przemienników. Źródła to przemienniki.eu, przemienniki.net, HearHam i repeatermap.de, a szczegóły DMR pochodzą z RadioID. Poza Polską źródło przełącza się samo, chyba że wybierzesz je ręcznie.
- **Satelity:** aktualizacja danych TLE, lista przelotów z AOS i LOS, skyplot, kompas z azymutem i elewacją, częstotliwości z AMSAT i SatNOGS oraz przypomnienia o przelotach.
- **Beacony NCDXF:** harmonogram z informacją, która stacja nadaje teraz i kiedy nadają pozostałe.

#### Emisje cyfrowe i CW
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

#### Planowanie i mapy
- **Kalendarz zawodów:** dane SP9CXN, PK-UKF / SP1CNV i QRZ.RU (bieżący i dwa kolejne miesiące, z linkiem do oryginalnej strony zawodów), filtry zawodów polskich, międzynarodowych, UKF, cyfrowych i z kraju pobytu oraz dodawanie do kalendarza telefonu. Bez internetu dostępny jest kalendarz wbudowany z wybranymi dużymi zawodami.
- **Dyplomy:** trwające i nadchodzące akcje z radiodyplom.pl, stacje aktywne teraz na pasmach i obserwowanie akcji. Stacje obserwowanych akcji są oznaczane w DX Clusterze.
- **Za granicą:** zasady licencji CEPT T/R 61-01, znak do użycia w danym kraju, prefiksy dla gości, Region IARU, strefy i czas oraz przemienniki w okolicy. Przed wyjazdem sprawdź aktualne przepisy kraju.
- **Lokator i azymut:** przeliczanie lokatora i współrzędnych, odległość i azymut krótką oraz długą drogą do lokatora, punktu lub znaku, a także mapa azymutalna.
- **Szara linia:** mapa terminatora oraz godziny wschodu i zachodu słońca dla Twojego QTH.
- **Bandplan:** plany pasm IARU dla Regionów 1, 2 i 3, z polską praktyką w Regionie 1.
- **Obiekty do aktywacji:** obiekty POTA, SOTA i WWFF w pobliżu Twojej pozycji.

#### Kalkulatory
- **Anteny:** dipol, EFHW, ground plane ¼λ, J-pole, Yagi 3-elementowa, prostokąt Moxona, pętla Quad/Delta
- **Dopasowanie i zasilanie:** odcinki kabla, transformator λ/4, SWR, moc i ERP/EIRP, czas pracy na akumulatorze
- **Tłumienność kabli:** straty i moc na antenie dla wybranego kabla, częstotliwości i długości

Wyniki dla anten to wymiary startowe, które trzeba dostroić w praktyce.

#### Ściągi
Kraje DXCC, kody Q, raporty RST, okręgi SP, slang, alfabet Morse'a i literowanie.

### Powiadomienia i widżet

- **Przeloty satelitów:** przypomnienie przed przelotem obserwowanego satelity, z wyborem wyprzedzenia i minimalnej elewacji
- **Nowe kraje w DX Clusterze:** sprawdzanie co 15 minut spotów krajów, których nie masz w logu
- **Burze geomagnetyczne:** sprawdzanie co godzinę progu Kp i ostrzeżeń NOAA
- **Widżet „Propagacja i satelity”:** wskaźniki słoneczne, warunki na pasmach KF i najbliższy przelot obserwowanego satelity

Powiadomienia włączysz w ustawieniach aplikacji.

### Instalacja

1. Pobierz plik APK z zakładki [Releases](https://github.com/SP3WRO/Ham_Radio_Toolkit/releases) na telefon.
2. Otwórz go i zezwól na instalację z tego źródła, jeśli Android o to zapyta.
3. W ustawieniach aplikacji wpisz swój znak wywoławczy. Bez znaku możesz korzystać z aplikacji jako nasłuchowiec.

Nowsze wydania instalują się jako aktualizacja bez utraty danych.

#### Uprawnienia

| Uprawnienie | Do czego służy |
| --- | --- |
| Lokalizacja | lokator QTH, przemienniki i stacje APRS w pobliżu, przeloty satelitów, odległość do stacji; lokator możesz też wpisać ręcznie |
| Mikrofon | dekodery |
| Internet | dane z serwisów zewnętrznych |
| Powiadomienia | przypomnienia i alerty |
| Dokładne alarmy | przypomnienie o przelocie co do minuty; bez tej zgody może przyjść kilka minut później |

#### Konta w serwisach

Podstawowe funkcje działają bez kont. Dane logowania są potrzebne tylko do tych usług:

| Usługa | Co jest potrzebne |
| --- | --- |
| QRZ Logbook | klucz API i subskrypcja QRZ |
| eQSL | login i hasło |
| HamQTH | bezpłatne konto |
| QRZCQ | konto |

#### Prywatność

Hasła i klucze są zapisane tylko w telefonie i nie trafiają do kopii zapasowej w chmurze. GPS działa tylko wtedy, gdy aplikacja jest otwarta. Serwisy zewnętrzne dostają tylko obszar wyszukiwania, a Twoja pozycja pojawia się w sieci APRS dopiero po naciśnięciu „Nadaj pozycję”.

### Budowanie ze źródeł

Potrzebujesz aktualnego Android Studio z Android SDK 36.

```powershell
git clone https://github.com/SP3WRO/Ham_Radio_Toolkit.git
cd Ham_Radio_Toolkit
.\gradlew assembleDebug
```

Plik APK pojawi się w `app/build/outputs/apk/debug/`. Testy jednostkowe uruchomisz komendą `.\gradlew test`.

#### Tłumaczenia

Teksty interfejsu są w plikach `tools/i18n/*.tsv`, po jednej kolumnie na język. Pliki `strings_*.xml` są z nich generowane skryptem `tools/i18n/gen.ps1`, więc zmiany wprowadzaj w TSV, a nie w XML. Szczegóły opisuje [instrukcja tłumaczeń](tools/i18n/README.md).

### Licencja

Projekt jest udostępniany na licencji [MIT](LICENSE), bez żadnej gwarancji. Copyright © 2026 Mateusz SP3WRO.

Fragmenty kodu innych autorów zachowują własne licencje:

| Składnik | Zastosowanie | Licencja |
| --- | --- | --- |
| ft8_lib | dekoder FT8 / FT4 | [MIT](app/src/main/java/io/github/sp3wro/hamradiotoolkit/ft8/LICENSE-ft8_lib.txt) |
| robot36 | dekoder SSTV | [0BSD](app/src/main/java/io/github/sp3wro/hamradiotoolkit/sstv/robot36/LICENSE-robot36.txt) |
| horusdemodlib | dekoder Horus Binary | [LGPL-2.1](app/src/main/java/io/github/sp3wro/hamradiotoolkit/horus/LICENSE-horusdemodlib.txt) |
| pdf.js | dekoder JPEG 2000 w DSSTV | [Apache 2.0](app/src/main/java/io/github/sp3wro/hamradiotoolkit/dsstv/LICENSE-pdfjs.txt) |

### Podziękowania

Dziękuję twórcom bezpłatnych serwisów i projektów, z których korzysta aplikacja. Pełna lista źródeł danych z odnośnikami jest w aplikacji, na ekranie **O programie**.

Kalkulator tłumienności kabli powstał na podstawie projektu Coax Calculator autorstwa SQ3SWF Olgierda.

Za silne wsparcie i motywację do działania dziękuję Dorocie SQ3TGY.

Aplikację stworzył z pomocą AI Mateusz SP3WRO.

*W trakcie budowania aplikacji nie ucierpiał żaden tranzystor!*
