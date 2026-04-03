# buildingsplitter

JOSM plugin for splitting building ways.

## Demo

![BuildingSplitter Demo](docs/images/bs-demo.gif)

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
