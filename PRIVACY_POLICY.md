# Polityka prywatności – Ham Radio Toolkit

*[English version below](#privacy-policy--ham-radio-toolkit)*

Obowiązuje od: 2 listopada 2026 r.

Ta polityka dotyczy aplikacji **Ham Radio Toolkit** na Androida (pakiet `io.github.sp3wro.hamradiotoolkit`). Autorem i wydawcą aplikacji jest SP3WRO.

## W skrócie

- Aplikacja nie ma kont użytkowników ani własnego serwera.
- Nie zawiera reklam, analityki ani narzędzi śledzących.
- Nie sprzedaje i nie przekazuje danych podmiotom trzecim w celach marketingowych.
- Wszystko, co wpiszesz (znak, lokator, log łączności, ustawienia), jest zapisywane wyłącznie w telefonie.
- Dane wysyłane są tylko do serwisów krótkofalarskich, z których **sam zdecydujesz się skorzystać**.

## Uprawnienia i do czego służą

| Uprawnienie | Cel |
|---|---|
| Lokalizacja (przybliżona i dokładna) | Wyznaczenie lokatora QTH, odległości i azymutów, przelotów satelitów, najbliższych przemienników. Opcjonalnie wysłanie pozycji do sieci APRS-IS. |
| Mikrofon | Odbiór dźwięku z radia przez dekodery (SSTV, DSSTV, FT8, Horus i inne). Dźwięk jest przetwarzany tylko w telefonie i nigdzie nie jest zapisywany ani wysyłany. |
| Powiadomienia i dokładne alarmy | Przypomnienia o przelotach satelitów i rozpoczęciu zawodów ustawione przez użytkownika. |
| Internet | Pobieranie danych publicznych serwisów (patrz niżej). |

Odmowa któregokolwiek uprawnienia wyłącza tylko związaną z nim funkcję.

## Dane przechowywane w telefonie

Znak wywoławczy, lokator, ustawienia, log łączności, ulubione elementy i pobrane dane (kalendarz zawodów, przemienniki itp.) są zapisywane lokalnie. Loginy, hasła i klucze API do zewnętrznych serwisów są przechowywane w osobnym pliku, który jest **wyłączony z kopii zapasowej Google**. Odinstalowanie aplikacji lub wyczyszczenie jej danych usuwa wszystkie te informacje.

## Dane wysyłane do zewnętrznych serwisów

Aplikacja łączy się bezpośrednio (bez pośrednika) z publicznymi serwisami krótkofalarskimi i naukowymi. Większość połączeń to tylko pobieranie danych publicznych: kalendarz zawodów, prognozy propagacji, dane satelitów, listy przemienników, spoty DX, aktywności SOTA/POTA/WWFF i podobne. Przy tych zapytaniach serwis widzi jedynie zwykłe informacje techniczne (adres IP, typ urządzenia).

Dane wpisane przez Ciebie trafiają do zewnętrznych serwisów tylko w poniższych przypadkach i tylko po Twoim działaniu:

- **APRS-IS (aprs2.net)**: po użyciu funkcji wysłania pozycji wysyłany jest znak wywoławczy, kod APRS, pozycja i komentarz. Sieć APRS jest publiczna: pakiet jest widoczny dla wszystkich (np. na mapach aprs.fi) i może być archiwizowany przez inne serwisy. Połączenie z APRS-IS nie jest szyfrowane, zgodnie ze standardem tej sieci.
- **Wyszukiwarki znaków (HamQTH, QRZCQ, HamDB, RadioID, PSK Reporter i inne)**: wysyłany jest wyszukiwany znak oraz – jeśli je podasz – Twój login i hasło do danego serwisu.
- **QRZ Logbook i eQSL**: po wysłaniu logu przekazywane są dane łączności (znak korespondenta, data, pasmo, emisja itp.) oraz Twój klucz API lub login i hasło.

Za przetwarzanie danych przez te serwisy odpowiadają ich operatorzy na podstawie własnych polityk prywatności.

## Dzieci

Aplikacja nie jest skierowana do dzieci poniżej 13 roku życia i świadomie nie zbiera ich danych.

## Twoje prawa

Ponieważ autor aplikacji nie gromadzi żadnych Twoich danych na swoim serwerze, nie ma czego udostępniać ani usuwać po jego stronie – pełną kontrolę masz w telefonie. W sprawie danych przekazanych do serwisów zewnętrznych (np. APRS-IS, QRZ, eQSL) zwróć się do ich operatorów.

## Zmiany

Zmiany tej polityki będą publikowane w tym pliku, wraz z nową datą obowiązywania.

## Kontakt

Pytania dotyczące prywatności: czysty61@gmail.com lub [zgłoszenie na GitHubie](https://github.com/SP3WRO/Ham_Radio_Toolkit/issues).

---

# Privacy Policy – Ham Radio Toolkit

Effective date: November 2, 2026

This policy applies to the **Ham Radio Toolkit** Android app (package `io.github.sp3wro.hamradiotoolkit`), developed and published by SP3WRO.

## Summary

- The app has no user accounts and no server of its own.
- It contains no ads, analytics or tracking tools.
- It does not sell data or share it with third parties for marketing.
- Everything you enter (callsign, locator, QSO log, settings) is stored only on your phone.
- Data is sent only to amateur radio services that **you choose to use**.

## Permissions and their purpose

| Permission | Purpose |
|---|---|
| Location (approximate and precise) | QTH locator, distances and bearings, satellite passes, nearest repeaters. Optionally sending your position to the APRS-IS network. |
| Microphone | Receiving radio audio in the decoders (SSTV, DSSTV, FT8, Horus and others). Audio is processed only on the device and is never stored or transmitted. |
| Notifications and exact alarms | Reminders about satellite passes and contest starts set up by the user. |
| Internet | Downloading data from public services (see below). |

Denying any permission only disables the related feature.

## Data stored on your device

Your callsign, locator, settings, QSO log, favourites and downloaded data (contest calendar, repeaters, etc.) are stored locally. Logins, passwords and API keys for external services are kept in a separate file that is **excluded from Google backup**. Uninstalling the app or clearing its data removes all of this information.

## Data sent to external services

The app connects directly (with no intermediary) to public amateur radio and scientific services. Most connections only download public data: contest calendars, propagation forecasts, satellite data, repeater lists, DX spots, SOTA/POTA/WWFF activity and similar. For these requests the service only sees ordinary technical information (IP address, device type).

Data you entered is sent to external services only in the cases below, and only after your action:

- **APRS-IS (aprs2.net)**: when you send your position, your callsign, APRS passcode, position and comment are sent. APRS is a public network: the packet is visible to everyone (e.g. on aprs.fi maps) and may be archived by other services. The APRS-IS connection is not encrypted, as per that network's standard.
- **Callsign lookups (HamQTH, QRZCQ, HamDB, RadioID, PSK Reporter and others)**: the callsign you search for and, if provided, your login and password for that service.
- **QRZ Logbook and eQSL**: when you upload a log, QSO data (station worked, date, band, mode, etc.) and your API key or login and password are sent.

These services process data under their own privacy policies.

## Children

The app is not directed at children under 13 and does not knowingly collect their data.

## Your rights

Since the developer does not collect any of your data on a server, there is nothing to access or delete on the developer's side – you have full control on your phone. For data sent to external services (e.g. APRS-IS, QRZ, eQSL), please contact their operators.

## Changes

Changes to this policy will be published in this file together with a new effective date.

## Contact

Privacy questions: czysty61@gmail.com or [open an issue on GitHub](https://github.com/SP3WRO/Ham_Radio_Toolkit/issues).
