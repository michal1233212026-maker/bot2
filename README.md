# Anarchia Bot Manager (Fabric, Minecraft Java 1.21.5)

Mod kliencki: po naciśnięciu **X** otwiera panel zarządzania botem (zakładki Bot / Ruch / Czat / Logi).
Bot to **prawdziwy, osobny klient Minecraft** (MCProtocolLib, protokół 1.21.5) uruchamiany jako
proces potomny; mod rozmawia z nim przez linie JSON (stdin/stdout).

## Struktura
```
anarchia-bot-manager/
├─ settings.gradle · build.gradle · gradle.properties · gradle/wrapper/gradle-wrapper.properties
├─ bot-runner/                      ← proces bota (osobny moduł Gradle, fat-jar osadzany w modzie)
│  ├─ build.gradle
│  └─ src/main/java/pl/anarchiabot/runner/
│     ├─ Main.java                  (pętla komend JSON)
│     ├─ BotClient.java             (sesja MC, czat, ruch, TPA-odporna obsługa pozycji)
│     ├─ PacketFactory.java · Reflect.java · Emitter.java
└─ src/main/
   ├─ java/pl/anarchiabot/
   │  ├─ AnarchiaBotClient.java     (klawisz X, tick, zamykanie)
   │  ├─ config/  BotConfig, ConfigManager
   │  ├─ core/    BotController (logika), BotStatus, BotMode, LogBuffer
   │  ├─ bridge/  BotProcess (komunikacja z procesem bota)
   │  └─ gui/     BotManagerScreen (interfejs)
   └─ resources/ fabric.mod.json, assets/anarchiabot/lang/{pl_pl,en_us}.json
```

## Kompilacja
Wymagane: **JDK 21** i dostęp do internetu (Maven Fabric + opencollab).

1. Wrapper (jednorazowo; jeśli masz zainstalowane Gradle): `gradle wrapper --gradle-version 8.12`
   – albo skopiuj `gradlew`, `gradlew.bat` i `gradle/wrapper/gradle-wrapper.jar` z oficjalnego szablonu Fabric.
2. `./gradlew build` → gotowy plik: `build/libs/anarchia-bot-manager-1.0.0.jar`
3. Test w dev: `./gradlew runClient`

Jeśli Gradle nie znajdzie `1.21.5-SNAPSHOT` MCProtocolLib, sprawdź w repo
`https://repo.opencollab.dev/` aktualną wersję dla 1.21.5 i wpisz ją w `gradle.properties` (`mcprotocollib_version`).

## Instalacja
Minecraft 1.21.5 + Fabric Loader ≥ 0.16.10 + Fabric API 0.119.9+1.21.5 → wrzuć jar do `mods/`.
Klawisz zmienisz w *Opcje → Sterowanie → Anarchia Bot Manager*.

## Użycie
1. **Bot → Utwórz bota** (uruchamia proces; status wciąż „Nieaktywny”).
2. Wpisz serwer (domyślnie `anarchia.gg`), opcjonalnie komendy po wejściu (np. `/login hasło`), **Połącz**.
   Status „Połączony” pojawia się dopiero, gdy serwer wpuści bota do gry.
3. **Czat → TP bota do mnie** wysyła z konta bota `/tpa <twój nick>`; potem **Akceptuj TPA (ja)**
   (wysyła `/tpaccept` z Twojego konta). Pole na dole pisze jako bot (wiadomości i `/komendy`).
   Opcjonalnie „Auto-TPA” – bot sam akceptuje prośby (wyrażenie w `autoAcceptRegex`).
4. **Ruch**: podążanie, współrzędne, powrót do startu, zatrzymanie.

Konfiguracja: `config/anarchia_bot_manager.json`. **Uwaga:** komendy po wejściu (np. hasło) są tam zapisane jawnym tekstem.

## Ograniczenia (uczciwie)
- Bot loguje się **nickiem offline** (bez konta Microsoft). Jeśli serwer wymaga premium, połączenie zostanie odrzucone
  i zobaczysz to w logach – mod nie udaje, że bot wszedł.
- Bot nie ma danych o świecie (chunki/kolizje), więc **nie ma pathfindingu** – idzie po linii prostej i nie ma grawitacji.
  Anty-cheat/serwer może go cofać (bot przyjmuje wtedy pozycję od serwera). Najpewniejszy ruch to **TPA**.
- „Podążaj za graczem” działa, gdy gracz jest w zasięgu renderowania **Twojego** klienta (to Twój klient widzi jego pozycję
  i przekazuje ją botowi). Poza zasięgiem bot czeka, a log o tym informuje.
- „Pozycja startowa” = pierwsza pozycja nadana botowi przez serwer po wejściu.
- Serwer może wymagać rejestracji/captchy/logowania – użyj pola „Komendy po wejściu” lub czatu bota.
- Zasady serwera: sprawdź regulamin anarchia.gg dotyczący botów/AFK.
