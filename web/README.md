# Ham Radio Toolkit – wersja WWW (PWA)

Wersja przeglądarkowa aplikacji Ham Radio Toolkit, przeznaczona przede wszystkim dla iPhone'a i iPada. Po dodaniu do ekranu początkowego otwiera się na pełnym ekranie jak zwykła aplikacja i działa bez internetu.

Kod jest w czystym JavaScripcie (moduły ES) – **bez zależności i bez kroku budowania**. Serwer to jeden plik dla Node.js 18+.

## Co już działa (etap 1)

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
| Ustawienia | język (5), motyw, stacja, APRS, przemienniki, programy |

Pozostałe narzędzia mają w Przyborniku oznaczenie „Wkrótce”.

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

Przeglądarka nie może pobierać danych z większości serwisów krótkofalarskich (brak nagłówków CORS), dlatego robi to serwer. To **nie jest otwarte proxy**: obsługuje tylko usługi z białej listy w `server/server.mjs` (`SERVICES`), tylko GET, z limitem zapytań na adres IP i pamięcią podręczną (np. hamqsl.com – 15 min), żeby nie obciążać źródeł danych. Kolejne źródła (DX Summit, POTA/WWFF, przemienniki, UKE, HamQTH…) dochodzą tam w następnych etapach.

Zmienne środowiskowe: `PORT`, `HOST`, `STATIC_DIR`, `CACHE_MAX_MB`, `ALLOWED_ORIGINS` (gdy aplikacja jest na innym adresie niż serwer – wtedy w `index.html` ustaw `window.HRT_API_BASE`).

## Ograniczenia iOS

- **Dane są w przeglądarce na urządzeniu** (log w IndexedDB, ustawienia w localStorage). Aplikacja prosi o trwałe przechowywanie; mimo to warto regularnie eksportować ADIF – Safari może usuwać dane stron nieużywanych od tygodni, jeśli nie są dodane do ekranu początkowego.
- **Brak pracy w tle**: przypomnienia o przelotach satelitów i alerty burz geomagnetycznych będą wymagały Web Push (iOS 16.4+, tylko po dodaniu do ekranu początkowego) wysyłanego z serwera.
- **APRS-IS** to połączenie TCP – w przeglądarce potrzebny będzie most WebSocket↔TCP na serwerze (konfiguracja nginx jest już na to przygotowana).
- **Mikrofon** (dekodery FT8/RTTY/CW/SSTV) działa w Safari po zgodzie użytkownika; kompas (satelity) wymaga zgody na czujniki ruchu.

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
