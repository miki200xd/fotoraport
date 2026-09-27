# Foto Raport – aplikacja na Androida

Aplikacja do raportu zdjęciowego OPL / TMPL (szablon Foto_raport V3.1 i V1). Działa w pełni offline.

- Każde zdjęcie z aparatu ma od razu kopię w galerii: **Pictures/FotoRaport/<numer stacji>/** (np. `BTS_24.jpg`, `Wspolne_1.jpg`).
- ZIP dla makra zapisuje się w **Pobrane/FotoRaport/**, a przyciskiem „Wyślij ZIP” wyślesz go przez WhatsApp, maila albo na Dysk.
- G5 (PDF) wczytuje się bez internetu.
- Wymaga Androida 10 lub nowszego.

## Jak zbudować plik .apk (za darmo, przez GitHub)

1. Załóż konto na https://github.com.
2. Kliknij **+ → New repository**, nazwa np. `fotoraport`, zaznacz **Public** i kliknij **Create repository**.
3. Na stronie repozytorium kliknij link **„uploading an existing file”** i przeciągnij **całą zawartość** tego folderu (razem z folderem `.github`). Na dole kliknij **Commit changes**.
4. Wejdź w zakładkę **Actions**. Budowanie „Zbuduj aplikację” rusza samo i trwa ok. 5–8 minut. Jeśli GitHub pyta o włączenie Actions, kliknij zgodę.
5. Gdy pojawi się zielony ptaszek, wróć na stronę główną repozytorium. Po prawej stronie w sekcji **Releases** kliknij najnowszą wersję i pobierz **FotoRaport.apk**.

## Instalacja na telefonie

1. Otwórz na telefonie stronę z Releases i pobierz `FotoRaport.apk`.
2. Otwórz pobrany plik. Android poprosi o zgodę na „instalowanie nieznanych aplikacji” dla przeglądarki: zezwól.
3. Jeśli Play Protect ostrzeże o nieznanym wydawcy, wybierz **Więcej szczegółów → Zainstaluj mimo to**.

## Aktualizacje

Podmieniasz pliki w repozytorium (znowu „Add file → Upload files”) i GitHub sam buduje nową wersję. Nowy .apk instalujesz na starym. Stacje i zdjęcia w aplikacji zostają, bo każda wersja jest podpisana tym samym kluczem (`app/fotoraport.jks` – nie usuwaj go).

## Gdy budowanie się nie uda

W zakładce **Actions** kliknij czerwony krzyżyk, potem **build**, i skopiuj albo zrób zrzut ekranu z czerwonym błędem. Wyślij to Claude'owi.
