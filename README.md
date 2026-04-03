# buildingsplitter

JOSM plugin for splitting building ways.

## Demo

![BuildingSplitter Demo](docs/images/bs-demo.gif)

## Features

- Manual mode: split a building by drawing a split line.
- Ctrl quick mode (inside Split Building tool): click a building to open AutoSplit.
- AutoSplit mode: split simple 4-corner buildings into multiple parts.
- Live preview in the editor with safe undo/finalize behavior.
- Optional address assignment in the AutoSplit dialog:
  - `addr:street` from visible street names (editable combo box)
  - `addr:postcode` (pre-filled when exactly one visible building postcode is detected)
  - optional `addr:housenumber` sequence generation

## External Address Context (optional)

Another plugin can provide a one-shot default context before AutoSplit opens:

```java
import org.openstreetmap.josm.plugins.buildingsplitter.AddressContextBridge;

AddressContextBridge.setAddressContext("Main Street", "12345");
```

Default precedence used by AutoSplit dialogs:

1. external context (`AddressContextBridge`)
2. last used dialog values
3. visible context detection (street list / uniform visible postcode)

The bridge is one-shot: context is consumed when AutoSplit dialog flow starts.

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

`deployPlugin` resolves the plugin directory by OS and copies `buildingsplitter.jar` there:

- Linux: `~/.josm/plugins/`
- macOS: `~/Library/JOSM/plugins/`
- Windows: `%APPDATA%/JOSM/plugins/`

```bash
./gradlew deployPlugin
./gradlew -q printPluginInstallPath
```

Other useful tasks:

```bash
./gradlew installPlugin   # Alias for deployPlugin
./gradlew removePlugin    # Removes the deployed plugin JAR
```
