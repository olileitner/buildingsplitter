# buildingsplitter

JOSM-Plugin zum Aufteilen von Gebaeuden.

## Funktionen

- Manueller Modus: Gebaeude mit einer gezeichneten Linie teilen.
- AutoSplit-Modus: rechteckige Gebaeude in mehrere Teile aufteilen.
- Optionales Zuweisen von Hausnummern im AutoSplit-Dialog.

## Voraussetzungen

- Java 11
- Gradle Wrapper (`./gradlew`)
- `libs/josm-tested.jar` im Projekt (wird als `compileOnly`/`testImplementation` genutzt)

## Build und Test

```bash
./gradlew clean test
./gradlew jar
```

## Lokales Deploy in JOSM

Der Task `deployPlugin` kopiert das Plugin-JAR nach `~/.josm/plugins/` (unter Linux).

```bash
./gradlew deployPlugin
./gradlew -q printPluginInstallPath
```

Weitere nuetzliche Tasks:

```bash
./gradlew installPlugin   # Alias fuer deployPlugin
./gradlew removePlugin    # entfernt das deployte Plugin-JAR
```

## Git Remote (GitHub)

Falls `origin` noch nicht gesetzt ist:

```bash
git remote add origin https://github.com/olileitner/buildingsplitter.git
```

Falls `origin` bereits existiert und aktualisiert werden soll:

```bash
git remote set-url origin https://github.com/olileitner/buildingsplitter.git
```

Push mit Upstream:

```bash
git push -u origin "$(git branch --show-current)"
```

