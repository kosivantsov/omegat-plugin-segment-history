# Segment History Plugin for OmegaT
This OmegaT plugin records per-segment editing history so you can inspect (and effectively “go back to”) earlier versions of a segment after you’ve moved on.
This is useful because OmegaT’s Undo (Ctrl+Z / Cmd+Z) only applies while the segment is active; the modification history is lost once you leave the segment and come back later.

## What it records
When you activate a segment, the plugin periodically takes snapshots of the current target text and appends them to the history tsv file.
History for repeated segments are recorded for the first instance in the repetition block, thus correctly storing editing history for autopropagetes segments no matter which of the repetiotions was edited.
Segments with alternative translation have separate history bound to the segment's source file name, segment's source text and its position in the file, so alternative and default translations are not mixed in the same history log.


## Snapshots author/time
If a segment already had a translation (from the project TMX) but did not yet have a history record, the plugin seeds the first snapshot with the original TMX entry’s `changer` as author and `changeDate` as the timestamp.
In most cases, the subsequent snapshots use OmegaT’s `Preferences.TEAM_AUTHOR` (translator's ID set in OmegaT) or the system username fallback as the author and the current time as the timestamp. The only exception is in cases when a segment is activated, but its translation appears to have been changed since the last recorded snapshot (for instance, from a TMX file placed in `tm/enforce/`). Then a new snapshot is recorded with the author and date from the segment's metadata.

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
GPLv3 (see [`LICENSE`](LICENSE)).
