package org.truetranslation.omegat.plugin;

import org.omegat.core.Core;
import org.omegat.core.data.IProject;
import org.omegat.core.data.SourceTextEntry;
import org.omegat.core.data.TMXEntry;
import org.omegat.util.Preferences;

import javax.swing.Timer;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class SegmentHistoryManager {

    private Timer snapshotTimer;
    private SourceTextEntry currentEntry;

    private String currentCacheKey;
    private String currentFileHash;
    private String currentSrcHash;
    private int currentRelativeIndex;

    private SegmentHistoryData currentData;
    private boolean isAlternativeMode;

    // In-memory cache and append-only queue
    private Map<String, SegmentHistoryData> historyCache = new ConcurrentHashMap<>();
    private List<String> pendingWrites = Collections.synchronizedList(new ArrayList<>());
    private File historyFile;

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

    public void loadProjectHistory() {
        historyCache.clear();
        pendingWrites.clear();

        if (Core.getProject() == null || !Core.getProject().isProjectLoaded()) return;

        File projectRoot = new File(Core.getProject().getProjectProperties().getProjectRoot());
        File omegatDir = new File(projectRoot, "omegat");
        if (!omegatDir.exists()) omegatDir.mkdirs();

        historyFile = new File(omegatDir, "segment_history.tsv");

        if (!historyFile.exists()) return;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(historyFile), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                String[] parts = line.split("\t", -1);
                if (parts.length >= 10) {
                    String fileHash = parts[0];
                    String srcHash = parts[1];
                    int relativeIndex = Integer.parseInt(parts[2]);
                    boolean isAlt = Boolean.parseBoolean(parts[3]);
                    long timestamp = Long.parseLong(parts[4]);

                    String author = decodeB64(parts[5]);
                    String origin = decodeB64(parts[6]);
                    String text = decodeB64(parts[7]);
                    String sourceText = decodeB64(parts[8]);
                    String sourceFileName = decodeB64(parts[9]);

                    String key = generateCacheKey(fileHash, srcHash, relativeIndex, isAlt);

                    SegmentHistoryData data = historyCache.computeIfAbsent(key, k -> {
                        SegmentHistoryData d = new SegmentHistoryData();
                        d.setSourceText(sourceText);
                        d.setSourceFileName(sourceFileName);
                        d.setRelativePosition(relativeIndex);
                        return d;
                    });

                    HistorySnapshot snapshot = new HistorySnapshot();
                    snapshot.setTimestamp(timestamp);
                    snapshot.setAuthor(author);
                    snapshot.setOrigin(origin);
                    snapshot.setText(text);
                    snapshot.setAlternative(isAlt);

                    data.addSnapshot(snapshot);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void savePendingHistory() {
        if (historyFile == null || pendingWrites.isEmpty()) return;

        List<String> toWrite;
        synchronized(pendingWrites) {
            toWrite = new ArrayList<>(pendingWrites);
            pendingWrites.clear();
        }

        if (toWrite.isEmpty()) return;

        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(historyFile, true), StandardCharsets.UTF_8))) {
            for (String line : toWrite) {
                writer.write(line);
                writer.newLine();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void clearCache() {
        historyCache.clear();
        pendingWrites.clear();
        historyFile = null;
    }

    public void onSegmentActivated(SourceTextEntry entry) {
        stopTimer();

        if (Core.getProject() == null || !Core.getProject().isProjectLoaded()) return;

        TMXEntry tmx = Core.getProject().getTranslationInfo(entry);
        boolean isDefault = (tmx == null) || tmx.defaultTranslation;
        this.isAlternativeMode = !isDefault;

        SourceTextEntry masterEntry = isAlternativeMode ? entry : findFirstOccurrence(entry);
        this.currentEntry = masterEntry;

        FileInfo info = getFileInfo(masterEntry);
        if (info == null) return;

        this.currentFileHash = getMD5(info.filePath);
        this.currentSrcHash = getMD5(masterEntry.getSrcText());
        this.currentRelativeIndex = info.relativeIndex;
        this.currentCacheKey = generateCacheKey(currentFileHash, currentSrcHash, currentRelativeIndex, isAlternativeMode);

        this.currentData = historyCache.computeIfAbsent(this.currentCacheKey, k -> {
            SegmentHistoryData d = new SegmentHistoryData();
            d.setSourceText(masterEntry.getSrcText());
            d.setSourceFileName(info.filePath);
            d.setRelativePosition(info.relativeIndex);
            d.setSegmentId(masterEntry.entryNum());
            return d;
        });

        seedFromTmxIfNeeded(masterEntry);

        int intervalSeconds = SegmentHistoryPrefs.getSnapshotInterval();
        snapshotTimer = new Timer(intervalSeconds * 1000, e -> takeSnapshot());
        snapshotTimer.setRepeats(true);
        snapshotTimer.start();

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
            // If the text hasn't changed, don't record a new snapshot.
            if (last.getText() != null && last.getText().equals(currentText)) {
                return;
            }
        }

        HistorySnapshot snapshot = new HistorySnapshot();
        snapshot.setTimestamp(System.currentTimeMillis());
        snapshot.setText(currentText);
        snapshot.setAlternative(isAlternativeMode);

        String author = Preferences.getPreferenceDefault(Preferences.TEAM_AUTHOR, System.getProperty("user.name"));
        snapshot.setAuthor(author);

        // Explicitly mark active editor changes as 'gui'
        snapshot.setOrigin("gui");

        currentData.addSnapshot(snapshot);

        String line = buildTsvLine(currentFileHash, currentSrcHash, currentRelativeIndex, isAlternativeMode, snapshot, currentData);
        pendingWrites.add(line);
        notifyListener();
    }

    public List<HistorySnapshot> getHistoryFor(SourceTextEntry entry) {
        TMXEntry tmx = Core.getProject().getTranslationInfo(entry);
        boolean isDefault = (tmx == null) || tmx.defaultTranslation;
        boolean altMode = !isDefault;

        SourceTextEntry masterEntry = altMode ? entry : findFirstOccurrence(entry);
        FileInfo info = getFileInfo(masterEntry);

        if (info == null) return new ArrayList<>();

        String fileHash = getMD5(info.filePath);
        String srcHash = getMD5(masterEntry.getSrcText());
        String key = generateCacheKey(fileHash, srcHash, info.relativeIndex, altMode);

        SegmentHistoryData data = historyCache.get(key);
        if (data == null) {
             return new ArrayList<>();
        }

        return data.getSnapshots();
    }

    private SourceTextEntry findFirstOccurrence(SourceTextEntry entry) {
        String dupStatus = String.valueOf(entry.getDuplicate());
        if ("NONE".equals(dupStatus) || "FIRST".equals(dupStatus)) {
            return entry;
        }

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

        String author = Preferences.getPreferenceDefault(Preferences.TEAM_AUTHOR, System.getProperty("user.name"));
        snapshot.setAuthor(author);
        snapshot.setOrigin("gui");

        currentData.addSnapshot(snapshot);

        String line = buildTsvLine(currentFileHash, currentSrcHash, currentRelativeIndex, isAlternativeMode, snapshot, currentData);
        pendingWrites.add(line);
        notifyListener();
    }

    private void seedFromTmxIfNeeded(SourceTextEntry entry) {
        if (entry == null || currentData == null) return;

        TMXEntry info = Core.getProject().getTranslationInfo(entry);
        if (info == null) return;

        String currentText = Core.getEditor().getCurrentTranslation();
        String tmxText = info.translation;

        // We only want to seed from TM if the TM has a valid translation
        if (tmxText == null || tmxText.trim().isEmpty()) return;

        List<HistorySnapshot> history = currentData.getSnapshots();
        if (history != null && !history.isEmpty()) {
            HistorySnapshot last = history.get(history.size() - 1);

            // If the last snapshot matches the TM text exactly, we don't need to seed.
            if (last.getText() != null && last.getText().equals(tmxText)) {
                return;
            }

            // If the editor has live text that matches the last snapshot (meaning the user
            // typed it but hasn't moved away), and it matches the TM, do nothing.
            if (currentText != null && currentText.equals(last.getText()) && currentText.equals(tmxText)) {
                 return;
            }
        }

        // 1. Author Logic: changer -> creator -> NO fallback to preferences
        String changer = readStringProperty(info, "changer");
        if (changer == null || changer.trim().isEmpty()) {
            changer = readStringProperty(info, "creator");
        }
        if (changer == null) {
            changer = "";
        }

        // 2. Date Logic: changeDate -> creationDate -> System.currentTimeMillis()
        long changeDate = readLongProperty(info, "changeDate");
        if (changeDate <= 0) {
            changeDate = readLongProperty(info, "creationDate");
        }

        long ts = normalizeEpochMillis(changeDate);
        if (ts <= 0) {
            ts = System.currentTimeMillis();
        }

        HistorySnapshot snapshot = new HistorySnapshot();
        snapshot.setTimestamp(ts);

        snapshot.setText(tmxText);
        snapshot.setAlternative(isAlternativeMode);
        snapshot.setAuthor(changer);
        snapshot.setOrigin("tm");

        currentData.addSnapshot(snapshot);

        String line = buildTsvLine(currentFileHash, currentSrcHash, currentRelativeIndex, isAlternativeMode, snapshot, currentData);
        pendingWrites.add(line);
        notifyListener();
    }

    private void notifyListener() {
        if (updateListener != null) {
            updateListener.run();
        }
    }

    private long normalizeEpochMillis(long raw) {
        if (raw <= 0) return raw;
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

        try {
            java.lang.reflect.Field f = obj.getClass().getField(name);
            return f.get(obj);
        } catch (Exception ignored) {}
        try {
            java.lang.reflect.Field f = obj.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(obj);
        } catch (Exception ignored) {}

        String getter = "get" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
        try {
            java.lang.reflect.Method m = obj.getClass().getMethod(getter);
            return m.invoke(obj);
        } catch (Exception ignored) {}
        try {
            java.lang.reflect.Method m = obj.getClass().getDeclaredMethod(getter);
            m.setAccessible(true);
            return m.invoke(obj);
        } catch (Exception ignored) {}

        return null;
    }

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

                for (int i=0; i < fi.entries.size(); i++) {
                    if (fi.entries.get(i).entryNum() == currentNum) {
                        info.relativeIndex = i + 1;
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

    private String buildTsvLine(String fileHash, String srcHash, int relativeIndex, boolean isAlt, HistorySnapshot snapshot, SegmentHistoryData data) {
        return String.join("\t",
            fileHash,
            srcHash,
            String.valueOf(relativeIndex),
            String.valueOf(isAlt),
            String.valueOf(snapshot.getTimestamp()),
            encodeB64(snapshot.getAuthor()),
            encodeB64(snapshot.getOrigin()),
            encodeB64(snapshot.getText()),
            encodeB64(data.getSourceText()),
            encodeB64(data.getSourceFileName())
        );
    }

    private String encodeB64(String s) {
        if (s == null) return "";
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private String decodeB64(String s) {
        if (s == null || s.isEmpty()) return "";
        try {
            return new String(Base64.getDecoder().decode(s), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private String generateCacheKey(String fileHash, String srcHash, int relativeIndex, boolean isAlt) {
        return String.format("%s_%s_%d_%b", fileHash, srcHash, relativeIndex, isAlt);
    }
}
