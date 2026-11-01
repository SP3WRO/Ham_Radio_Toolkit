# Ham Radio Toolkit – wersja WWW (PWA)

Wersja przeglądarkowa aplikacji Ham Radio Toolkit, przeznaczona przede wszystkim dla iPhone'a i iPada. Po dodaniu do ekranu początkowego otwiera się na pełnym ekranie jak zwykła aplikacja i działa bez internetu.

Kod jest w czystym JavaScripcie (moduły ES) – **bez zależności i bez kroku budowania**. Serwer to jeden plik dla Node.js 18+.

## Co już działa

| Ekran | Uwagi |
| --- | --- |
| Home | czas UTC i lokalny, lokator QTH z GPS, współrzędne, wysokość |
| Propagacja | SFI, plamy, indeksy A/K, warunki KF dzień/noc (hamqsl.com), mapy Tropocast z przesunięciem ±6 h, ostatnie dane bez sieci |
| Logger | dodawanie, edycja, usuwanie, wyszukiwanie, kraj DXCC, wykrywanie dubli, historia QSO ze stacją, odległość i azymut, import i eksport ADIF |
| Statystyki logu | QSO, znaki, DXCC, POTA, WWFF, tabela pasm i emisji, lista krajów |
| Lokator | moja stacja, odległość i azymut SP/LP do lokatora, współrzędnych lub znaku (z logu lub kraju DXCC), mapa azymutalna z przybliżaniem, przelicznik |
| Szara linia, Beacony NCDXF, Bandplan R1/R2/R3 | jak w aplikacji |
| Kalkulatory, Tłumienność kabli | wszystkie 11 kalkulatorów i 16 kabli |
| Vademecum | DXCC, kody Q, RST, okręgi SP, skróty, Morse, alfabet fonetyczny |
| Ustawienia | język (6), motyw, stacja, APRS, przemienniki, programy, konta HamQTH i QRZCQ |
| DX Cluster | spoty DX Summit z filtrami pasm, emisji i krajów, nowe kraje, wysyłanie spotów, QSO ze spotu, akcje dyplomowe przy spotach |
| Aktywacje na żywo, Aktywacje w okolicy | POTA, WWFF, SOTA – spoty i obiekty w promieniu do 500 km |
| Przemienniki | przemienniki.net / przemienniki.eu / HearHam / repeatermap, filtry, DMR z RadioID, najbliższy na ekranie głównym |
| Wyszukiwarka znaków | rejestr UKE, HamDB, RadioID, HamQTH, QRZCQ, PSK Reporter, log; autouzupełnianie okna QSO |
| Kalendarz zawodów | SP9CXN i PK-UKF / SP1CNV (JSON z repozytorium), QRZ.RU po miesiącach (z tłumaczeniem nazw), filtry, „Do kalendarza” (plik .ics → Kalendarz iOS) |
| Dyplomy | akcje radiodyplom.pl (public_events.json), obserwowane, stacje na pasmach z ostatnich 30 minut (public_bandmap.json) |
| Za granicą | znak wg CEPT T/R 61-01, kraj z pozycji (OpenStreetMap), region IARU, strefy i czas |
| Satelity | TLE z AMSAT/CelesTrak, przeloty liczone w przeglądarce (SGP4), radar nieboskłonu, częstotliwości AMSAT/SatNOGS |
| Trener i dekoder CW | metoda Kocha, tempo Farnswortha; dekoder z mikrofonu i pliku |
| APRS | APRS-IS przez most WebSocket na serwerze, mapa stacji, wysyłanie własnej pozycji |
| RTTY | widmo do strojenia, AFC, automatyczny przesuw, szybkość i polaryzacja |
| FT8 / FT4 | sloty zsynchronizowane z zegarem, dekodowanie w osobnym wątku, plik WAV |
| SSTV | port Robot36 – Robot 36/72, PD, Martin, Scottie, Wraase, HF Fax; zapis PNG |
| Balony (Horus Binary) | v1/v2/v3 (port horusdemodlib), widmo, telemetria, odległość i elewacja |
| DSSTV (HamDRM) | zgodny z EasyPal/QSSTV, tryby A/B/E, 4/16/64-QAM, RS, podgląd JPEG 2000 w trakcie odbioru |
| O programie | wersja aplikacji i wersji WWW, licencja MIT, kod innych autorów, źródła danych |

Wszystkie narzędzia z Przybornika są już przeniesione. Do zrobienia: wysyłka logu do QRZ/eQSL i powiadomienia Web Push.

## Jedno źródło danych z aplikacją na Androida

Teksty, bandplany i tabele **nie są kopiowane ręcznie** – generuje je `tools/build-data.mjs` ze źródeł aplikacji:

- tłumaczenia: `tools/i18n/*.tsv` (+ `web/i18n/strings_web.tsv` – teksty tylko dla WWW, ten sam format),
- bandplany: `BandplanData.kt`, `RegionalBandplans.kt`, zwroty z `BandplanText.kt`,
- `cty.csv`, ściągi `assets/vademecum/*.tsv`, kontury lądów `res/raw/world_path.txt`.

Po zmianie tłumaczeń, bandplanu albo plików aplikacji WWW uruchom:

```bash
cd web
node tools/build-data.mjs   # dane + lista plików i wersja dla service workera
node --test test/*.test.mjs # testy (te same przypadki co w app/src/test)
tsc -p jsconfig.json        # opcjonalnie: sprawdzenie typów (wymaga TypeScriptu)
```

Wersja service workera to skrót zawartości plików, więc po każdej zmianie iPhone pobierze aktualizację i pokaże „Nowa wersja jest gotowa – Odśwież”.

## Uruchomienie lokalne

```bash
cd web
node server/server.mjs      # http://127.0.0.1:8080
```

GPS i instalacja PWA wymagają HTTPS (wyjątek: `localhost`). Do testów na iPhonie w sieci domowej najwygodniej od razu użyć subdomeny z certyfikatem.

## Wdrożenie na serwerze Debian (subdomena)

Najprościej – jedno polecenie na serwerze (Debian 12, wcześniej: rekord DNS A dla domeny i otwarte porty 80/443).
Instaluje wszystko, a uruchomione ponownie aktualizuje aplikację do najnowszej wersji z GitHuba:

```bash
curl -fsSL https://raw.githubusercontent.com/SP3WRO/Ham_Radio_Toolkit/web-pwa/web/server/zainstaluj.sh | sudo bash
```

Domena jest ustawiona na `toolkit.czysty.eu`; inną podasz tak: `... | sudo DOMAIN=ham.example.pl bash`.

Ręcznie, krok po kroku:

```bash
# 1. Node.js 18+ (Debian 12 ma 18.x w repozytorium)
sudo apt install nodejs nginx certbot python3-certbot-nginx

# 2. Kod i użytkownik usługi
sudo useradd --system --home /opt/Ham_Radio_Toolkit --shell /usr/sbin/nologin hamradio
sudo git clone https://github.com/SP3WRO/Ham_Radio_Toolkit /opt/Ham_Radio_Toolkit
sudo chown -R hamradio:hamradio /opt/Ham_Radio_Toolkit

# 3. Usługa systemd
sudo cp /opt/Ham_Radio_Toolkit/web/server/hamradio-toolkit-web.service /etc/systemd/system/
sudo systemctl daemon-reload && sudo systemctl enable --now hamradio-toolkit-web
curl http://127.0.0.1:8080/api/health

# 4. nginx + HTTPS (zamień ham.example.pl na swoją subdomenę; rekord DNS A/AAAA musi wskazywać serwer)
sudo cp /opt/Ham_Radio_Toolkit/web/server/nginx-subdomain.conf.example /etc/nginx/sites-available/hamradio-toolkit
sudo sed -i 's/ham.example.pl/twoja.subdomena.pl/g' /etc/nginx/sites-available/hamradio-toolkit
sudo ln -s /etc/nginx/sites-available/hamradio-toolkit /etc/nginx/sites-enabled/
sudo certbot --nginx -d twoja.subdomena.pl
sudo nginx -t && sudo systemctl reload nginx
```

Aktualizacja: `cd /opt/Ham_Radio_Toolkit && sudo -u hamradio git pull && sudo systemctl restart hamradio-toolkit-web`.

### Serwer pośredniczący

Przeglądarka nie może pobierać danych z większości serwisów krótkofalarskich (brak nagłówków CORS), dlatego robi to serwer. To **nie jest otwarte proxy**: obsługuje tylko usługi z białej listy w `server/server.mjs` (`SERVICES`), z limitem zapytań na adres IP i pamięcią podręczną, żeby nie obciążać źródeł danych. Cięższe źródła serwer pobiera i parsuje sam (moduły w `server/`, parsery wspólne z przeglądarką w `public/js/core/`):

| Adres | Źródła | Odświeżanie |
| --- | --- | --- |
| `/api/activations` | POTA, SOTA, WWFF (CSV) | raz na dobę |
| `/api/repeaters` | przemienniki.net (rpts.json, gdy zmieni się timestamp.xml), przemienniki.eu (7 dni), HearHam, repeatermap | 1–7 dni |
| `/api/callbook` (POST) | UKE, HamDB, RadioID, HamQTH, QRZCQ, PSK Reporter | UKE raz w tygodniu, PSK Reporter najwyżej co 5 min |
| `/api/contests` | SP9CXN i PK-UKF / SP1CNV (JSON z tego repozytorium), QRZ.RU (bieżący i 2 kolejne miesiące) | 24 h |
| `/api/awards`, `/api/awards/bandmap` | radiodyplom.pl – tylko public_events.json i public_bandmap.json | 12 h / 5 min |
| `/api/satellites` | AMSAT/CelesTrak, SatNOGS, AMSAT DB | 24 h |
| `/api/country` | Nominatim (OpenStreetMap) | najwyżej 1 zapytanie/s, wyniki w pamięci |
| `/api/aprs` (WebSocket) | APRS-IS (TCP) | na żywo |
| `/api/proxy/horus/…` | listy ładunków Projektu Horus (GitHub) | 7 dni |

Hasła HamQTH/QRZCQ są zapisane tylko w przeglądarce; serwer używa ich wyłącznie do zapytania i nigdzie ich nie zapisuje.

Zmienne środowiskowe: `PORT`, `HOST`, `STATIC_DIR`, `CACHE_MAX_MB`, `ALLOWED_ORIGINS` (gdy aplikacja jest na innym adresie niż serwer – wtedy w `index.html` ustaw `window.HRT_API_BASE`).

## Safari i iPhone

Aplikacja działa w Safari od iOS 15.4 (wcześniejsze wersje dostają zamienniki z `public/js/compat.js`: `AbortSignal.timeout`, okna dialogowe, `Array.at`). Zasady, których pilnuje kod:

- bez lookbehind w wyrażeniach regularnych (`(?<=…)`, `(?<!…)`) – Safari obsługuje je dopiero od 16.4, a jedno takie wyrażenie w module blokuje start całej aplikacji;
- pola tekstowe bez autokorekty i bez podmiany wartości w trakcie pisania (wielkie litery przez CSS) – iPhone inaczej podwaja znaki i psuje lokatory oraz znaki wywoławcze;
- okna z polem tekstowym otwierają się u góry ekranu i mieszczą się nad klawiaturą (`visualViewport`), fokus ustawiany od razu w obsłudze dotknięcia;
- mikrofon: węzeł AudioWorklet podłączony do wyjścia przez wyciszony GainNode (Safari nie przetwarza węzłów bez wyjścia), kontekst wznawiany po zgodzie na mikrofon;
- czcionka pól co najmniej 16 px (Safari powiększa stronę przy mniejszej).

Sprawdzenie składni pod Safari: `esbuild public/js/app.js --bundle --target=safari15 --outfile=/dev/null`.

## Ograniczenia iOS

- **Dane są w przeglądarce na urządzeniu** (log w IndexedDB, ustawienia w localStorage). Aplikacja prosi o trwałe przechowywanie; mimo to warto regularnie eksportować ADIF – Safari może usuwać dane stron nieużywanych od tygodni, jeśli nie są dodane do ekranu początkowego.
- **Kalendarz**: „Do kalendarza” tworzy plik .ics – Safari od razu proponuje dodanie wydarzenia.
- **Brak pracy w tle**: przypomnienia o przelotach satelitów i alerty burz geomagnetycznych będą wymagały Web Push (iOS 16.4+, tylko po dodaniu do ekranu początkowego) wysyłanego z serwera.
- **APRS-IS** to połączenie TCP – przeglądarka łączy się przez most WebSocket↔TCP na serwerze (`/api/aprs`); serwer proxy (Apache/nginx) musi przepuszczać WebSockety.
- **Mikrofon** (dekodery FT8/RTTY/CW/SSTV/Horus/DSSTV) działa w Safari po zgodzie użytkownika; kompas (satelity) wymaga zgody na czujniki ruchu.

## Struktura

```
web/
  public/              pliki aplikacji (serwowane bez zmian)
    js/core/           logika przeniesiona z Kotlina – bez DOM, testowana w Node
    js/screens/        ekrany
    js/ui.js           komponenty w stylu Material 3
    data/              generowane przez tools/build-data.mjs
    sw.js              service worker (praca offline)
  server/              serwer Node.js, usługa systemd, przykład nginx
  tools/               generatory danych, ikon i ikon Material
  i18n/                teksty tylko dla wersji WWW
  test/                testy (node --test)
```

Ikony interfejsu: Google Material Design Icons (Apache 2.0).

## Licencje kodu zewnętrznego

- Dekoder SSTV: port Robot36 (Ahmet Inan, 0BSD).
- Horus Binary: port horusdemodlib (David Rowe i in., LGPL-2.1).
- JPEG 2000: `public/js/core/jpx/` z pdf.js 3.11.174 (Mozilla, Apache 2.0).
