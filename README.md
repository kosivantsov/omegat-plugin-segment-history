# Segment History Plugin for OmegaT
This OmegaT plugin records per-segment editing history so you can inspect (and effectively “go back to”) earlier versions of a segment after you’ve moved on.
This is useful because OmegaT’s Undo (Ctrl+Z / Cmd+Z) only applies while the segment is current; the modification history is lost once you leave the segment and come back later.

## What it records
When you activate a segment, the plugin periodically takes snapshots of the current target text and stores them as compressed history files in the project under `omegat/.edits/` (`*.xml.gz`).
Each segment’s history is stored separately (file name is derived from the source file path and source text), and the plugin also handles “alternative translations” by keeping separate histories.

### First snapshot author/time (seed behavior)
If a segment already had a translation (from the project TMX) but did not yet have a history file, the plugin seeds the first snapshot with the original TMX entry’s `changer` as author and `changeDate` as the timestamp.
All subsequent snapshots use OmegaT’s `Preferences.TEAM_AUTHOR` (translator's ID set in OmegaT) or the system username fallback as the author and the current time as the timestamp.

## How to build
This project is built with Gradle and targets OmegaT 6.0.x (configured via the `org.omegat.gradle` plugin and an OmegaT dependency).

Build the plugin JAR:
```bash
./gradlew clean jar
```

Optional: build a distribution ZIP (includes the JAR and bundled files):
```bash
./gradlew distZip
```

## How to install

The most reliable approach to install OmegaT plugins is to copy the plugin's `*.jar` file into the `plugins` folder inside your OmegaT user configuration folder. If the `plugins` folder doesn't exist, simply create it. You can also create a subfolder for each plugin you install, which can make plugin maintenance a bit easier.

Place the plugin JAR here:

- Windows: `C:\Users\<you>\AppData\Roaming\OmegaT\plugins` (same as `%APPDATA%\OmegaT\plugins`).
- macOS: `~/Library/Preferences/OmegaT/plugins`.
- Linux/BSD: `~/.omegat/plugins`.

Restart OmegaT after copying the JAR so the plugin is loaded.

## License
GPLv3 (see `LICENSE`).
