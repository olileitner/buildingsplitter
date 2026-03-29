# buildingsplitter

JOSM plugin for splitting building ways.

## Features

- Manual mode: split a building by drawing a split line.
- AutoSplit mode: split rectangular buildings into multiple parts.
- Optional house number assignment in the AutoSplit dialog.

## Requirements

- Java 11
- Gradle Wrapper (`./gradlew`)
- `libs/josm-tested.jar` in the project (used as `compileOnly`/`testImplementation`)

## Build and Test

```bash
./gradlew clean test
./gradlew jar
```

## Local Deploy to JOSM

The `deployPlugin` task copies the plugin JAR to `~/.josm/plugins/` (on Linux).

```bash
./gradlew deployPlugin
./gradlew -q printPluginInstallPath
```

Other useful tasks:

```bash
./gradlew installPlugin   # Alias for deployPlugin
./gradlew removePlugin    # Removes the deployed plugin JAR
```

## Submission Checklist (Review)

- Build and tests pass locally (`./gradlew clean test jar`).
- Plugin JAR is generated as `build/libs/buildingsplitter.jar`.
- Required manifest fields are present (`Plugin-Class`, `Plugin-Version`, `Plugin-Mainversion`, `Plugin-Description`).
- Recommended manifest fields are present (`Author`, `Plugin-Link`, `Plugin-Icon`, `Plugin-Minimum-Java-Version`).
- Icons are packaged in the JAR under `images/`.

Quick manifest check:

```bash
unzip -p build/libs/buildingsplitter.jar META-INF/MANIFEST.MF
```

## Git Remote (GitHub)

If `origin` is not set yet:

```bash
git remote add origin https://github.com/olileitner/buildingsplitter.git
```

If `origin` already exists and should be updated:

```bash
git remote set-url origin https://github.com/olileitner/buildingsplitter.git
```

Push with upstream:

```bash
git push -u origin "$(git branch --show-current)"
```

