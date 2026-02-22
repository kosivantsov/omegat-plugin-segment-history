package org.truetranslation.omegat.plugin;

import org.omegat.core.Core;
import org.omegat.core.data.IProject;
import org.omegat.core.data.SourceTextEntry;
import org.omegat.core.data.TMXEntry;
import org.omegat.util.Preferences;

import javax.swing.Timer;
import java.beans.XMLDecoder;
import java.beans.XMLEncoder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class SegmentHistoryManager {

    private Timer snapshotTimer;
    private SourceTextEntry currentEntry;
    private String currentHistoryFile;
    private SegmentHistoryData currentData;
    private boolean isAlternativeMode;

    // Listener for UI updates
    private Runnable updateListener;

    public SegmentHistoryManager() {
    }

    public SourceTextEntry getCurrentEntry() {
        return currentEntry;
    }

    public boolean isAlternativeMode() {
        return isAlternativeMode;
    }

    public void setUpdateListener(Runnable listener) {
        this.updateListener = listener;
    }

    public void onSegmentActivated(SourceTextEntry entry) {
        stopTimer();

        if (Core.getProject() == null || !Core.getProject().isProjectLoaded()) return;

        // 1. Determine if this is an alternative translation
        // TMXEntry has 'defaultTranslation'
        TMXEntry tmx = Core.getProject().getTranslationInfo(entry);

        // If tmx is null, it means no translation exists yet, so it defaults to the 'default' behavior
        boolean isDefault = (tmx == null) || tmx.defaultTranslation;
        this.isAlternativeMode = !isDefault;

        // 2. Resolve the "Master" entry for file naming
        // If Default -> Find the FIRST occurrence (source of propagation)
        // If Alternative -> Use CURRENT entry (individual history)
        SourceTextEntry masterEntry;
        if (isAlternativeMode) {
            masterEntry = entry;
        } else {
            masterEntry = findFirstOccurrence(entry);
        }

        this.currentEntry = masterEntry;

        // 3. Generate Filename based on Master Entry
        // Format: <filepath_md5>_<sourcetext_md5>_<relative_num>[_alt].xml.gz
        this.currentHistoryFile = getHistoryFilePath(masterEntry, isAlternativeMode);

        // 4. Load Data with Conflict Resolution
        this.currentData = loadHistoryData(this.currentHistoryFile, masterEntry);

        // 4b. Seed snapshot from existing translation metadata if history is empty or out-of-sync with TMX
        seedFromTmxIfNeeded(masterEntry);

        // 5. Start Timer
        int intervalSeconds = SegmentHistoryPrefs.getSnapshotInterval();
        snapshotTimer = new Timer(intervalSeconds * 1000, e -> takeSnapshot());
        snapshotTimer.setRepeats(true);
        snapshotTimer.start();

        // Take immediate snapshot if content differs
        takeSnapshot();
    }

    public void stopTimer() {
        if (snapshotTimer != null) {
            snapshotTimer.stop();
        }
    }

    private void takeSnapshot() {
        if (currentEntry == null || currentData == null) return;

        String currentText = Core.getEditor().getCurrentTranslation();
        if (currentText == null) return;

        List<HistorySnapshot> history = currentData.getSnapshots();
        if (!history.isEmpty()) {
            HistorySnapshot last = history.get(history.size() - 1);
            if (last.getText() != null && last.getText().equals(currentText)) {
                return;
            }
        }

        HistorySnapshot snapshot = new HistorySnapshot();
        snapshot.setTimestamp(System.currentTimeMillis());
        snapshot.setText(currentText);
        snapshot.setAlternative(isAlternativeMode);

        // Save Author & Origin
        String author = Preferences.getPreferenceDefault(Preferences.TEAM_AUTHOR, System.getProperty("user.name"));
        snapshot.setAuthor(author);
        snapshot.setOrigin("gui");

        currentData.addSnapshot(snapshot);
        saveHistory(currentHistoryFile, currentData);
    }

    public List<HistorySnapshot> getHistoryFor(SourceTextEntry entry) {
        TMXEntry tmx = Core.getProject().getTranslationInfo(entry);
        boolean isDefault = (tmx == null) || tmx.defaultTranslation;
        boolean altMode = !isDefault;

        SourceTextEntry masterEntry = altMode ? entry : findFirstOccurrence(entry);
        String path = getHistoryFilePath(masterEntry, altMode);

        // Load without conflict resolution side-effects for just viewing
        SegmentHistoryData data = loadHistoryDataRaw(path);

        // If raw load is empty/mismatched, try to resolve logic similar to activation
        if (!validateData(data, masterEntry)) {
             return new ArrayList<>();
        }

        return data.getSnapshots();
    }

    // Find the segment where isDup is "FIRST" or "NONE" with same source text
    private SourceTextEntry findFirstOccurrence(SourceTextEntry entry) {
        String dupStatus = String.valueOf(entry.getDuplicate());
        if ("NONE".equals(dupStatus) || "FIRST".equals(dupStatus)) {
            return entry;
        }

        // It's a repetition (NEXT). Find the FIRST in the whole project.
        String src = entry.getSrcText();
        for (SourceTextEntry cand : Core.getProject().getAllEntries()) {
            if (cand.getSrcText().equals(src)) {
                String d = String.valueOf(cand.getDuplicate());
                if ("FIRST".equals(d) || "NONE".equals(d)) {
                    return cand;
                }
            }
        }
        return entry;
    }

    private String getHistoryFilePath(SourceTextEntry entry, boolean isAlternative) {
        try {
            File projectRoot = new File(Core.getProject().getProjectProperties().getProjectRoot());
            File editsDir = new File(projectRoot, "omegat/.edits");
            if (!editsDir.exists()) editsDir.mkdirs();

            FileInfo info = getFileInfo(entry);
            if (info == null) return null;

            String fileHash = getMD5(info.filePath);
            String srcHash = getMD5(entry.getSrcText());

            // Construct filename with checksums
            String filename = String.format("%s_%s_%d%s.xml.gz",
                    fileHash, srcHash, info.relativeIndex, isAlternative ? "_alt" : "");

            return new File(editsDir, filename).getAbsolutePath();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private SegmentHistoryData loadHistoryData(String path, SourceTextEntry expectedEntry) {
        if (path == null) return new SegmentHistoryData();

        File f = new File(path);

        // 1. Try to load existing
        if (f.exists()) {
            SegmentHistoryData data = loadHistoryDataRaw(path);

            // 2. Validate Source Text
            if (validateData(data, expectedEntry)) {
                return data;
            }

            // 3. Mismatch detected.
            // Strategy: Look for the file 5 segments back and forward (heuristic for shifted segments)
            FileInfo info = getFileInfo(expectedEntry);
            if (info != null) {
                String fileHash = getMD5(info.filePath);
                String srcHash = getMD5(expectedEntry.getSrcText());
                File editsDir = f.getParentFile();

                for (int offset = -5; offset <= 5; offset++) {
                    if (offset == 0) continue;
                    int neighborIndex = info.relativeIndex + offset;
                    if (neighborIndex < 1) continue;

                    String neighborName = String.format("%s_%s_%d%s.xml.gz",
                            fileHash, srcHash, neighborIndex, isAlternativeMode ? "_alt" : "");
                    File neighborFile = new File(editsDir, neighborName);

                    if (neighborFile.exists()) {
                        SegmentHistoryData neighborData = loadHistoryDataRaw(neighborFile.getAbsolutePath());
                        if (validateData(neighborData, expectedEntry)) {
                            // Found it! Rename neighbor to current (move history)
                            // But first, backup current file if it exists
                            renameToBackup(f);
                            neighborFile.renameTo(f);
                            return neighborData;
                        }
                    }
                }
            }

            // 4. Still not found? Backup the mismatched file and start new.
            renameToBackup(f);
        }

        // 5. New Data
        SegmentHistoryData newData = new SegmentHistoryData();
        fillMetadata(newData, expectedEntry);
        return newData;
    }

    private void renameToBackup(File f) {
        if (!f.exists()) return;
        String backupName = f.getName() + "." + System.currentTimeMillis() + ".bak";
        f.renameTo(new File(f.getParentFile(), backupName));
    }

    private SegmentHistoryData loadHistoryDataRaw(String path) {
        ClassLoader originalCL = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(this.getClass().getClassLoader());

        try (XMLDecoder d = new XMLDecoder(new BufferedInputStream(new GZIPInputStream(new FileInputStream(path))))) {
            Object obj = d.readObject();
            if (obj instanceof SegmentHistoryData) {
                return (SegmentHistoryData) obj;
            }
        } catch (Exception e) {
            // ignore
        } finally {
            Thread.currentThread().setContextClassLoader(originalCL);
        }
        return new SegmentHistoryData();
    }

    private boolean validateData(SegmentHistoryData data, SourceTextEntry entry) {
        if (data == null) return false;
        // Check if source text matches.
        if (data.getSourceText() == null || data.getSourceText().isEmpty()) return false;
        return data.getSourceText().equals(entry.getSrcText());
    }

    private void saveHistory(String path, SegmentHistoryData data) {
        if (path == null) return;

        ClassLoader originalCL = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(this.getClass().getClassLoader());

        try (XMLEncoder e = new XMLEncoder(new BufferedOutputStream(new GZIPOutputStream(new FileOutputStream(path))))) {
            e.writeObject(data);
        } catch (Exception ex) {
            ex.printStackTrace();
        } finally {
            Thread.currentThread().setContextClassLoader(originalCL);
        }

        // Notify listener that data has changed
        if (updateListener != null) {
            updateListener.run();
        }
    }

    private void fillMetadata(SegmentHistoryData data, SourceTextEntry entry) {
        data.setSourceText(entry.getSrcText());
        data.setSegmentId(entry.entryNum());

        FileInfo info = getFileInfo(entry);
        if (info != null) {
            data.setSourceFileName(info.filePath);
            data.setRelativePosition(info.relativeIndex);
        }
    }

    public void forceSnapshot(String text) {
        if (currentData == null || text == null) return;

        List<HistorySnapshot> history = currentData.getSnapshots();
        if (!history.isEmpty()) {
            HistorySnapshot last = history.get(history.size() - 1);
            if (last.getText() != null && last.getText().equals(text)) {
                return;
            }
        }

        HistorySnapshot snapshot = new HistorySnapshot();
        snapshot.setTimestamp(System.currentTimeMillis());
        snapshot.setText(text);
        snapshot.setAlternative(isAlternativeMode);

        // Save Author & Origin
        String author = Preferences.getPreferenceDefault(Preferences.TEAM_AUTHOR, System.getProperty("user.name"));
        snapshot.setAuthor(author);
        snapshot.setOrigin("gui");

        currentData.addSnapshot(snapshot);
        saveHistory(currentHistoryFile, currentData);
    }

    /**
     * If a segment has a translation (TMXEntry exists), we want to ensure its latest
     * state is recorded. This applies when:
     * 1. No history existed yet (seeding the very first snapshot).
     * 2. History exists, but the latest snapshot differs from the current translation
     *    (indicating it was modified externally, e.g. via TM enforce).
     *
     * In both cases, we use the TMXEntry's changer and changeDate and flag the origin as 'tm'.
     */
    private void seedFromTmxIfNeeded(SourceTextEntry entry) {
        if (entry == null || currentData == null) return;

        TMXEntry info = Core.getProject().getTranslationInfo(entry);
        if (info == null) return;

        String currentText = Core.getEditor().getCurrentTranslation();
        if (currentText == null || currentText.trim().isEmpty()) return;

        List<HistorySnapshot> history = currentData.getSnapshots();
        if (history != null && !history.isEmpty()) {
            HistorySnapshot last = history.get(history.size() - 1);
            if (last.getText() != null && last.getText().equals(currentText)) {
                return;
            }
        }

        String changer = readStringProperty(info, "changer");
        long changeDate = readLongProperty(info, "changeDate");

        long ts = normalizeEpochMillis(changeDate);
        if (ts <= 0) {
            ts = System.currentTimeMillis();
        }

        if (changer == null || changer.trim().isEmpty()) {
            changer = Preferences.getPreferenceDefault(Preferences.TEAM_AUTHOR, System.getProperty("user.name"));
        }

        HistorySnapshot snapshot = new HistorySnapshot();
        snapshot.setTimestamp(ts);
        snapshot.setText(currentText);
        snapshot.setAlternative(isAlternativeMode);
        snapshot.setAuthor(changer);
        snapshot.setOrigin("tm");

        currentData.addSnapshot(snapshot);
        saveHistory(currentHistoryFile, currentData);
    }

    private long normalizeEpochMillis(long raw) {
        if (raw <= 0) return raw;
        // Heuristic: if it looks like seconds-since-epoch, convert to milliseconds.
        if (raw < 10_000_000_000L) {
            return raw * 1000L;
        }
        return raw;
    }

    private String readStringProperty(Object obj, String name) {
        Object v = readProperty(obj, name);
        return v == null ? null : String.valueOf(v);
    }

    private long readLongProperty(Object obj, String name) {
        Object v = readProperty(obj, name);
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        if (v != null) {
            try {
                return Long.parseLong(String.valueOf(v));
            } catch (Exception ignored) {
            }
        }
        return 0L;
    }

    private Object readProperty(Object obj, String name) {
        if (obj == null || name == null || name.isEmpty()) return null;

        // 1) Try field (public then declared)
        try {
            java.lang.reflect.Field f = obj.getClass().getField(name);
            return f.get(obj);
        } catch (Exception ignored) {
        }
        try {
            java.lang.reflect.Field f = obj.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(obj);
        } catch (Exception ignored) {
        }

        // 2) Try getter (getXxx)
        String getter = "get" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
        try {
            java.lang.reflect.Method m = obj.getClass().getMethod(getter);
            return m.invoke(obj);
        } catch (Exception ignored) {
        }
        try {
            java.lang.reflect.Method m = obj.getClass().getDeclaredMethod(getter);
            m.setAccessible(true);
            return m.invoke(obj);
        } catch (Exception ignored) {
        }

        return null;
    }

    // --- Helpers ---

    private static class FileInfo {
        String filePath;
        int relativeIndex;
    }

    private FileInfo getFileInfo(SourceTextEntry entry) {
        int currentNum = entry.entryNum();
        List<IProject.FileInfo> files = Core.getProject().getProjectFiles();

        for (IProject.FileInfo fi : files) {
            if (fi.entries == null || fi.entries.isEmpty()) continue;

            int first = fi.entries.get(0).entryNum();
            int last = fi.entries.get(fi.entries.size() - 1).entryNum();

            if (currentNum >= first && currentNum <= last) {
                FileInfo info = new FileInfo();
                info.filePath = fi.filePath;

                // Find relative index within the file
                for (int i=0; i < fi.entries.size(); i++) {
                    if (fi.entries.get(i).entryNum() == currentNum) {
                        info.relativeIndex = i + 1; // 1-based index
                        return info;
                    }
                }
            }
        }
        return null;
    }

    private String getMD5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] messageDigest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : messageDigest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }
}
