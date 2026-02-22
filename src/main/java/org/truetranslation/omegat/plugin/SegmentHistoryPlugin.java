package org.truetranslation.omegat.plugin;

import org.omegat.core.Core;
import org.omegat.core.CoreEvents;
import org.omegat.core.data.SourceTextEntry;
import org.omegat.core.events.IApplicationEventListener;
import org.omegat.core.events.IEntryEventListener;
import org.omegat.core.events.IProjectEventListener;
import org.omegat.gui.editor.IPopupMenuConstructor;
import org.omegat.gui.editor.SegmentBuilder;
import org.omegat.gui.main.IMainMenu;
import org.omegat.gui.preferences.PreferencesControllers;

import javax.swing.*;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.io.File;
import java.util.ResourceBundle;

public class SegmentHistoryPlugin {

    private static final ResourceBundle BUNDLE = ResourceBundle.getBundle("org.truetranslation.omegat.plugin.SegmentHistoryBundle");

    private static SegmentHistoryManager manager;
    private static JCheckBoxMenuItem enableMenuItem;
    private static JMenuItem deleteHistoryItem;
    private static JMenuItem editMenuItem;

    public static void loadPlugins() {
        manager = new SegmentHistoryManager();

        CoreEvents.registerApplicationEventListener(new IApplicationEventListener() {
            @Override
            public void onApplicationStartup() {
                initMenus();
                Core.getEditor().registerPopupMenuConstructors(1000, new HistoryPopupConstructor());

                PreferencesControllers.addSupplier(SegmentHistoryPrefs::new);
                SegmentHistoryPrefs.setCallback(SegmentHistoryPlugin::updateFromPrefs);
            }

            @Override
            public void onApplicationShutdown() {
                manager.stopTimer();
            }
        });

        // Register hook into OmegaT's save/load lifecycle
        CoreEvents.registerProjectChangeListener(new IProjectEventListener() {
            @Override
            public void onProjectChanged(PROJECT_CHANGE_TYPE eventType) {
                if (eventType == PROJECT_CHANGE_TYPE.LOAD) {
                    manager.loadProjectHistory();
                } else if (eventType == PROJECT_CHANGE_TYPE.SAVE) {
                    manager.savePendingHistory();
                } else if (eventType == PROJECT_CHANGE_TYPE.CLOSE) {
                    manager.savePendingHistory();
                    manager.clearCache();
                }
            }
        });

        CoreEvents.registerEntryEventListener(new IEntryEventListener() {
            @Override
            public void onNewFile(String activeFileName) {}

            @Override
            public void onEntryActivated(SourceTextEntry newEntry) {
                updateEditMenuState(newEntry);

                boolean hasEntry = (newEntry != null);

                if (SegmentHistoryPrefs.isHistoryEnabled() && hasEntry) {
                    manager.onSegmentActivated(newEntry);
                    SwingUtilities.invokeLater(() -> SegmentHistoryDialog.updateIfVisible(newEntry));
                } else {
                    manager.stopTimer();
                }
            }
        });
    }

    public static void unloadPlugins() {
        if (manager != null) manager.stopTimer();
    }

    public static void updateFromPrefs() {
        SwingUtilities.invokeLater(() -> {
            boolean enabled = SegmentHistoryPrefs.isHistoryEnabled();
            if (enableMenuItem != null) {
                enableMenuItem.setSelected(enabled);
            }

            if (editMenuItem != null) {
                editMenuItem.setAccelerator(SegmentHistoryPrefs.getShortcut());
                updateEditMenuState(manager.getCurrentEntry());
            }

            if (enabled) {
                SourceTextEntry current = manager.getCurrentEntry();
                if (current != null) manager.onSegmentActivated(current);
            } else {
                manager.stopTimer();
            }
        });
    }

    private static void updateEditMenuState(SourceTextEntry entry) {
        if (editMenuItem != null) {
            boolean enabled = SegmentHistoryPrefs.isHistoryEnabled();
            boolean hasEntry = (entry != null);
            editMenuItem.setEnabled(enabled && hasEntry);
        }
    }

    private static void initMenus() {
        IMainMenu mainMenu = Core.getMainWindow().getMainMenu();
        JMenu optionsMenu = mainMenu.getOptionsMenu();

        JMenu historySubMenu = new JMenu(BUNDLE.getString("seghistory_menu_options_menu"));

        historySubMenu.addMenuListener(new MenuListener() {
            @Override
            public void menuSelected(MenuEvent e) {
                if (deleteHistoryItem != null) {
                    boolean isLoaded = Core.getProject() != null && Core.getProject().isProjectLoaded();
                    deleteHistoryItem.setEnabled(isLoaded);
                }
            }

            @Override
            public void menuDeselected(MenuEvent e) {}

            @Override
            public void menuCanceled(MenuEvent e) {}
        });

        enableMenuItem = new JCheckBoxMenuItem(BUNDLE.getString("seghistory_menu_enable"));
        enableMenuItem.setSelected(SegmentHistoryPrefs.isHistoryEnabled());
        enableMenuItem.addActionListener(e -> {
            SegmentHistoryPrefs.setHistoryEnabled(enableMenuItem.isSelected());
            updateFromPrefs();
        });
        historySubMenu.add(enableMenuItem);

        deleteHistoryItem = new JMenuItem(BUNDLE.getString("seghistory_menu_delete_history"));
        boolean isProjectLoaded = (Core.getProject() != null && Core.getProject().isProjectLoaded());
        deleteHistoryItem.setEnabled(isProjectLoaded);

        deleteHistoryItem.addActionListener(e -> deleteHistory());
        historySubMenu.add(deleteHistoryItem);

        int targetIndex = 4;
        int count = optionsMenu.getItemCount();
        if (targetIndex < count && targetIndex >= 0) {
            optionsMenu.insert(historySubMenu, targetIndex);
        } else {
            optionsMenu.add(historySubMenu);
        }

        JMenuBar menuBar = Core.getMainWindow().getApplicationFrame().getJMenuBar();
        JMenu editMenu = null;
        if (menuBar != null && menuBar.getMenuCount() > 1) {
            editMenu = menuBar.getMenu(1);
        }

        if (editMenu != null) {
            editMenu.addSeparator();
            editMenuItem = new JMenuItem(BUNDLE.getString("seghistory_menu_show"));
            editMenuItem.setEnabled(false);
            editMenuItem.setAccelerator(SegmentHistoryPrefs.getShortcut());
            editMenuItem.addActionListener(e -> showHistoryDialog());
            editMenu.add(editMenuItem);
        }
    }

    private static void deleteHistory() {
        if (Core.getProject() == null || !Core.getProject().isProjectLoaded()) return;

        int choice = JOptionPane.showConfirmDialog(
            Core.getMainWindow().getApplicationFrame(),
            BUNDLE.getString("seghistory_delete_confirm_message"),
            BUNDLE.getString("seghistory_delete_confirm_title"),
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE
        );

        if (choice == JOptionPane.YES_OPTION) {
            try {
                String rootPath = Core.getProject().getProjectProperties().getProjectRoot();
                File projectRoot = new File(rootPath);

                File historyFile = new File(projectRoot, "omegat/segment_history.tsv");

                if (historyFile.exists()) {
                    manager.stopTimer();
                    manager.clearCache();
                    historyFile.delete();

                    if (SegmentHistoryPrefs.isHistoryEnabled()) {
                        SourceTextEntry current = manager.getCurrentEntry();
                        if (current != null) {
                            manager.onSegmentActivated(current);
                            SwingUtilities.invokeLater(() -> SegmentHistoryDialog.updateIfVisible(current));
                        }
                    }

                    JOptionPane.showMessageDialog(
                        Core.getMainWindow().getApplicationFrame(),
                        BUNDLE.getString("seghistory_delete_success_message")
                    );
                } else {
                    JOptionPane.showMessageDialog(
                        Core.getMainWindow().getApplicationFrame(),
                        BUNDLE.getString("seghistory_delete_not_found")
                    );
                }
            } catch (Exception e) {
                JOptionPane.showMessageDialog(
                    Core.getMainWindow().getApplicationFrame(),
                    "Error deleting history: " + e.getMessage(),
                    "Error",
                    JOptionPane.ERROR_MESSAGE
                );
                e.printStackTrace();
            }
        }
    }

    private static void showHistoryDialog() {
        if (!SegmentHistoryPrefs.isHistoryEnabled()) return;

        try {
            SourceTextEntry ste = manager.getCurrentEntry();
            if (ste == null) {
                Toolkit.getDefaultToolkit().beep();
                return;
            }
            SegmentHistoryDialog.show(Core.getMainWindow().getApplicationFrame(), manager, ste);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static class HistoryPopupConstructor implements IPopupMenuConstructor {
        @Override
        public void addItems(JPopupMenu menu, JTextComponent comp, int mousepos,
                             boolean isInActiveEntry, boolean isInActiveTranslation, SegmentBuilder sb) {

            if (!SegmentHistoryPrefs.isHistoryEnabled()) return;
            if (!isInActiveEntry) return;
            if (manager == null || manager.getCurrentEntry() == null) return;

            menu.addSeparator();
            JMenuItem item = new JMenuItem(BUNDLE.getString("seghistory_menu_show"));
            item.addActionListener(e -> showHistoryDialog());
            menu.add(item);
        }
    }
}
