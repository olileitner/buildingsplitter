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

