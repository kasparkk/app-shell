# app-shell

Android-Hülle für meine Web-Apps. Jede App lädt ihre Live-URL (Netlify), Web-Deploys kommen also ohne Neuinstallation an. Die APK muss nur neu gebaut werden, wenn sich die Hülle ändert.

## Installieren

| App | APK |
|---|---|
| Zeiterfassung & Rechnungen | [zeiterfassung.apk](https://github.com/kasparkk/app-shell/releases/latest/download/zeiterfassung.apk) |
| Wildverkauf | [wildverkauf.apk](https://github.com/kasparkk/app-shell/releases/latest/download/wildverkauf.apk) |
| Jahrringzähler | [jahrringzaehler.apk](https://github.com/kasparkk/app-shell/releases/latest/download/jahrringzaehler.apk) |

Updates: neue APK über die alte installieren (fester Signierschlüssel `app/shell.keystore`, Daten bleiben erhalten).

## Neue App hinzufügen

1. `apps/<id>.json` anlegen:
   ```json
   {
     "name": "Name auf dem Startbildschirm",
     "package": "de.kasparkk.<id>",
     "url": "https://<seite>.netlify.app/",
     "icon": "auto",
     "internalHosts": []
   }
   ```
   Optional: `themeColor`, `backgroundColor`, `iconBackground`, `icon` als Pfad/URL (dann `iconMaskable: true/false`).
   `internalHosts` sind weitere Hosts, die in der App bleiben sollen (z. B. `"*.supabase.co"`); alles andere öffnet im Browser.
2. `<id>` in `.github/workflows/apk.yml` unter `matrix.app` eintragen.
3. Push auf `main` baut alle APKs neu und ersetzt den Release `latest`.

## Was die Hülle kann

- Datei-Auswahl mit Kamera, Galerie, Dateien (`<input type=file>`, `capture` öffnet direkt die Kamera)
- Downloads: `blob:`/`data:`-Links, `download`-Attribut, `FileSaver.js`, normale HTTP-Downloads → Ordner Downloads, Leiste mit Öffnen/Teilen
- `navigator.share` (auch mit Dateien) → Android-Teilen
- `window.print()` → Android-Druckdialog inkl. „Als PDF speichern“ (`@media print` wirkt)
- Kamera/Mikro (`getUserMedia`) mit Laufzeit-Berechtigung, nur für die eigene Seite
- Zurück-Taste: Web-History, dann App schließen; `window.open` als eigenes Fenster
- Externe Links, `mailto:`, `tel:`, `intent:` → passende App
- Edge-to-edge (targetSdk 35): Statusleiste in `theme_color`, Inhalt per Insets darunter, Tastatur schiebt mit
- Icon aus dem PWA-Manifest (maskable bevorzugt), Splash in `background_color`
- Offline-Seite mit „Neu laden“, falls die Seite nicht lädt und kein Service Worker sie liefert

Web-Apps erkennen die Hülle an `window.isAndroidShell` bzw. `AppShell/1` im User-Agent.

## Bauen

GitHub Actions (`.github/workflows/apk.yml`): `tools/prepare.py <id>` erzeugt Name, Farben, Icons und `config.json`, danach `gradle assembleRelease`. Schlägt ein Build fehl, steht das Log im Branch `ci-log-<id>`.
