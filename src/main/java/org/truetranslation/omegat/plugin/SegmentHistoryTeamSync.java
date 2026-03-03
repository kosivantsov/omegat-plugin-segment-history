package org.truetranslation.omegat.plugin;

import org.omegat.core.Core;
import org.omegat.core.data.RealProject;
import org.omegat.core.data.SourceTextEntry;
import org.omegat.core.team2.IRemoteRepository2;
import org.omegat.core.team2.RemoteRepositoryProvider;
import org.omegat.util.Log;
import org.omegat.util.Preferences;

import javax.swing.SwingUtilities;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Handles team synchronisation of segment_history.tsv.
 * We wait for OmegaT's own save/sync mechanisms to complete, then explicitly
 * fetch the latest upstream segment history, merge it with our preserved 
 * omegat/segment_history_local.tsv, and commit the result back to the server.
 */
public class SegmentHistoryTeamSync {

    static final String HISTORY_RELPATH = "omegat/segment_history.tsv";
    static final String HISTORY_LOCAL_RELPATH = "omegat/segment_history_local.tsv";

    public static void scheduleSyncAfterSave() {
        if (Core.getProject() == null
                || !Core.getProject().isProjectLoaded()
                || !Core.getProject().isRemoteProject()) {
            return;
        }

        // We run this in a background thread. By asking for executeExclusively, 
        // we guarantee this block runs strictly AFTER OmegaT finishes its own 
        // save & team sync tasks (which also hold this lock).
        Thread t = new Thread(() -> {
            try {
                Core.executeExclusively(true, () -> {
                    try {
                        doSync();
                    } catch (IRemoteRepository2.NetworkException e) {
                        Log.logWarningRB("SegmentHistoryTeamSync: network error – "
                                + (e.getCause() != null ? e.getCause() : e.getMessage()));
                    } catch (Exception e) {
                        Log.log("SegmentHistoryTeamSync: error during sync: " + e);
                    }
                });
            } catch (Exception e) {
                Log.log("SegmentHistoryTeamSync: could not acquire exclusive lock: " + e);
            }
        }, "SegHistTeamSync");
        t.setDaemon(true);
        t.start();
    }

    private static void doSync() throws Exception {
        if (!(Core.getProject() instanceof RealProject)) return;
        RealProject realProject = (RealProject) Core.getProject();
        File projectRoot = realProject.getProjectProperties().getProjectRootDir();

        RemoteRepositoryProvider provider = getProvider();
        if (provider == null || provider.getTeamSettings() == null) return;

        if (!provider.isUnderMapping(HISTORY_RELPATH)) {
            return; // File is not configured for version control in this project
        }

        // 1. FORCIBLY PULL LATEST FROM UPSTREAM SERVER
        // Updates the hidden .repositories/ cache to the remote HEAD
        provider.switchToVersion(HISTORY_RELPATH, null);
        // Copies the freshly pulled remote file into the active project (omegat/segment_history.tsv)
        provider.copyFilesFromReposToProject(HISTORY_RELPATH);

        // 2. LOCATE FILES
        File historyFile = new File(projectRoot, HISTORY_RELPATH);
        File localHistoryFile = new File(projectRoot, HISTORY_LOCAL_RELPATH);

        // 3. MERGE REMOTE AND LOCAL
        List<String> merged = mergeTsvFiles(historyFile, localHistoryFile);

        // 4. WRITE MERGED DATA TO BOTH FILES (keeping local safe harbor synced)
        writeLines(historyFile, merged);
        writeLines(localHistoryFile, merged);

        // 5. COPY BACK TO HIDDEN CACHE AND PUSH TO UPSTREAM
        provider.copyFilesFromProjectToRepos(HISTORY_RELPATH, StandardCharsets.UTF_8.name());

        String author = Preferences.getPreferenceDefault(Preferences.TEAM_AUTHOR, System.getProperty("user.name"));
        String comment = "Segment history sync by " + author;
        provider.commitFiles(HISTORY_RELPATH, comment);

        // 6. HOT-RELOAD UI SO NEW REMOTE SEGMENTS APPEAR INSTANTLY
        reloadUI();
    }

    private static void reloadUI() {
        SegmentHistoryManager mgr = SegmentHistoryPlugin.getManager();
        if (mgr != null) {
            // Re-read the newly merged files from disk
            mgr.loadProjectHistory();

            // Force the history dialog to visibly update if the translator is looking at it
            SourceTextEntry current = mgr.getCurrentEntry();
            if (current != null) {
                mgr.onSegmentActivated(current);
                SwingUtilities.invokeLater(() -> SegmentHistoryDialog.updateIfVisible(current));
            }
        }
    }

    public static List<String> mergeTsvFiles(File file1, File file2) {
        List<String> merged = new ArrayList<>();
        Set<String> dedupe = new LinkedHashSet<>();
        String header = null;

        for (File f : new File[]{file1, file2}) {
            if (f != null && f.exists()) {
                try {
                    List<String> lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
                    for (String line : lines) {
                        if (line.trim().isEmpty()) continue;
                        if (Character.isDigit(line.charAt(0)) || line.matches("^[a-fA-F0-9]{32}.*")) {
                            if (dedupe.add(line)) {
                                merged.add(line);
                            }
                        } else if (header == null) {
                            header = line;
                        }
                    }
                } catch (Exception e) {
                    Log.log("SegmentHistoryTeamSync: error reading " + f.getName() + " - " + e);
                }
            }
        }

        // Sort chronologically by timestamp (assumed to be column index 4)
        merged.sort((a, b) -> {
            String[] pA = a.split("\t", 6);
            String[] pB = b.split("\t", 6);
            if (pA.length >= 5 && pB.length >= 5) {
                try {
                    return Long.compare(Long.parseLong(pA[4]), Long.parseLong(pB[4]));
                } catch (NumberFormatException e) {
                    return 0;
                }
            }
            return 0;
        });

        List<String> result = new ArrayList<>();
        if (header != null) result.add(header);
        result.addAll(merged);
        return result;
    }

    public static void writeLines(File file, List<String> lines) {
        try {
            Files.write(file.toPath(), lines, StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.log("SegmentHistoryTeamSync: error writing " + file.getName() + " - " + e);
        }
    }

    private static RemoteRepositoryProvider getProvider() throws Exception {
        Field f = RealProject.class.getDeclaredField("remoteRepositoryProvider");
        f.setAccessible(true);
        return (RemoteRepositoryProvider) f.get(Core.getProject());
    }
}
