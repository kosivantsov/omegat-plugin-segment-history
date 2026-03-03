# Segment History Plugin for OmegaT

This OmegaT plugin records per-segment editing history so you can inspect (and effectively "go back to") earlier versions of a segment after you’ve moved on.

This is useful because OmegaT’s Undo (Ctrl+Z / Cmd+Z) only applies while the segment is active. Without this plugin, modification history is lost once you save or leave the segment and come back later.

## Using the plugin

The plugin adds a submenu **Segment History** under the **Options** menu to enable/disable recording of the editing history, as well as an option to delete the file with the entire editing history for all segments in the project.

To list available edits for the current segment, use one of the following methods:

- Select **Edit** > **Show Segment History**.
- Right-click in the current segment and select **Show Segment History**.
- Press the shortcut set in the [Plugin configuration](#plugin-configuration).

## Recorded edits

When you activate a segment, the plugin periodically takes snapshots of the current target text and appends them to the history TSV file.

### Repetitions and alternative translations

History for repeated segments is recorded for the first instance in the repetition block. This ensures the editing history for auto-propagated segments is stored correctly, regardless of which repetition was edited.

Segments with alternative translations have separate history entries bound to the segment's source file name, source text, and position in the file. This ensures that alternative and default translations are not mixed up in the history log for each segment.

The snapshot list title bar always indicates whether the current segment's translation is default or alternative.

### Snapshot author and time

If a segment already had a translation (from the project TMX) but did not yet have a history record, the plugin seeds the first snapshot using the original TMX entry’s `changer` as the author and `changeDate` as the timestamp.

Further edits made by a user in the OmegaT editor are captured using OmegaT’s `Preferences.TEAM_AUTHOR` (the translator's ID set in OmegaT) or the system username fallback for the author, alongside the current time for the timestamp.

If a segment is activated but its translation appears to have been changed since the last recorded snapshot (for instance, from a TMX file placed in `tm/enforce/`), a new snapshot is recorded using the author and date from the segment's metadata.

## Plugin configuration

The plugin can be configured in the Preferences dialog (**Preferences** > **Plugins** > **Segment History**).

### Configuration options

| Option | Description |
|---|---|
| Snapshot interval | Time in seconds between snapshots. |
| Compare segments with | Options for the diff view for the selected snapshot. |
| List template | Format string used to list available snapshots. |
| Shortcut to show history snapshots | Key combination to show the list of available history snapshots for the current segment. |

### Available variables for the list of snapshots

| Variable | Description |
|---|---|
| `${hour}` | Snapshot hour (time of edit or from metadata). |
| `${minute}` | Snapshot minute (time of edit or from metadata). |
| `${second}` | Snapshot second (time of edit or from metadata). |
| `${day}` | Snapshot day (time of edit or from metadata). |
| `${month}` | Snapshot month (time of edit or from metadata). |
| `${year}`| Snapshot year (time of edit or from metadata). |
| `${length}` | Length of the snapshot target text (in characters). |
| `${text}` | Snapshot target text. |
| `${author}` | Snapshot author (from OmegaT preferences or segment metadata). |
| `${alt}` | Alternative/default translation indicator. |
| `${origin}` | Indicator of the snapshot origin (`gui` or `tm`). |

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

- **Windows:** `C:\Users\<you>\AppData\Roaming\OmegaT\plugins` (same as `%APPDATA%\OmegaT\plugins`).
- **macOS:** `~/Library/Preferences/OmegaT/plugins`.
- **Linux/BSD:** `~/.omegat/plugins`.

Restart OmegaT after copying the JAR so the plugin is loaded.

## License

GPLv3 (see [`LICENSE`](LICENSE)).
