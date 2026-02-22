package org.truetranslation.omegat.plugin;

import org.omegat.gui.dialogs.KeyStrokeEditorDialog;
import org.omegat.gui.preferences.BasePreferencesController;
import org.omegat.util.Preferences;
import org.omegat.util.gui.StaticUIUtils;

import javax.swing.*;
import java.awt.*;
import java.util.ResourceBundle;

public class SegmentHistoryPrefs extends BasePreferencesController {

    private static final String PREF_ENABLED = "seghistory_enabled";
    private static final String PREF_INTERVAL = "seghistory_interval";
    private static final String PREF_DIFF_BASE = "seghistory_diff_base"; 
    private static final String PREF_DIFF_MODE = "seghistory_diff_mode";
    private static final String PREF_SHORTCUT = "seghistory_shortcut";
    private static final String PREF_TEMPLATE = "seghistory_list_template";
    private static final String DEFAULT_TEMPLATE = "${day}.${month}.${year} ${hour}:${minute}:${second} (${author} | ${length} chars):\\t${text}";

    private static final ResourceBundle BUNDLE = ResourceBundle.getBundle("org.truetranslation.omegat.plugin.SegmentHistoryBundle");

    private static Runnable changeCallback;

    private JCheckBox enableCb;
    private JSpinner intervalSpinner;

    // UI fields for 2-column layout
    private JRadioButton diffCurrentRb;
    private JRadioButton diffPrevRb;
    private JRadioButton diffWordRb;
    private JRadioButton diffCharRb;

    // UI fields for template
    private JTextField txtTemplate;
    private JComboBox<String> cmbVariables;

    private JLabel keystrokeLabel;
    private KeyStroke currentShortcut;

    private JPanel panel;

    public SegmentHistoryPrefs() {
        panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // 1. Enable
        enableCb = new JCheckBox(BUNDLE.getString("seghistory_pref_enable_checkbox"));
        enableCb.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(enableCb);

        panel.add(Box.createVerticalStrut(10));

        // 2. Interval
        JPanel intervalPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        intervalPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        intervalPanel.add(new JLabel(BUNDLE.getString("seghistory_pref_interval_label") + " "));
        intervalSpinner = new JSpinner(new SpinnerNumberModel(3, 1, 3600, 1));
        intervalPanel.add(intervalSpinner);
        panel.add(intervalPanel);

        panel.add(Box.createVerticalStrut(25));

        // 3. Two Columns: Comparison & Granularity
        JPanel columnsPanel = new JPanel(new GridLayout(1, 2, 20, 0));
        columnsPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        // Limit height not to stretch vertically
        columnsPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 100));

        // Left Column: Diff Base
        JPanel basePanel = new JPanel();
        basePanel.setLayout(new BoxLayout(basePanel, BoxLayout.Y_AXIS));

        JLabel baseLabel = new JLabel(BUNDLE.getString("seghistory_pref_diff_base_label"));
        baseLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        basePanel.add(baseLabel);
        basePanel.add(Box.createVerticalStrut(5));

        diffCurrentRb = new JRadioButton(BUNDLE.getString("seghistory_pref_diff_base_current"));
        diffCurrentRb.setAlignmentX(Component.LEFT_ALIGNMENT);
        diffCurrentRb.setBorder(BorderFactory.createEmptyBorder(0, 15, 0, 0));

        diffPrevRb = new JRadioButton(BUNDLE.getString("seghistory_pref_diff_base_prev"));
        diffPrevRb.setAlignmentX(Component.LEFT_ALIGNMENT);
        diffPrevRb.setBorder(BorderFactory.createEmptyBorder(0, 15, 0, 0));

        ButtonGroup bgBase = new ButtonGroup();
        bgBase.add(diffCurrentRb);
        bgBase.add(diffPrevRb);
        basePanel.add(diffCurrentRb);
        basePanel.add(diffPrevRb);

        columnsPanel.add(basePanel);

        // Right Column: Diff Mode
        JPanel modePanel = new JPanel();
        modePanel.setLayout(new BoxLayout(modePanel, BoxLayout.Y_AXIS));

        JLabel modeLabel = new JLabel(BUNDLE.getString("seghistory_pref_diff_granularity_label"));
        modeLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        modePanel.add(modeLabel);
        modePanel.add(Box.createVerticalStrut(5));

        diffWordRb = new JRadioButton(BUNDLE.getString("seghistory_pref_diff_mode_word"));
        diffWordRb.setAlignmentX(Component.LEFT_ALIGNMENT);
        diffWordRb.setBorder(BorderFactory.createEmptyBorder(0, 15, 0, 0));

        diffCharRb = new JRadioButton(BUNDLE.getString("seghistory_pref_diff_mode_char"));
        diffCharRb.setAlignmentX(Component.LEFT_ALIGNMENT);
        diffCharRb.setBorder(BorderFactory.createEmptyBorder(0, 15, 0, 0));

        ButtonGroup bgMode = new ButtonGroup();
        bgMode.add(diffWordRb);
        bgMode.add(diffCharRb);
        modePanel.add(diffWordRb);
        modePanel.add(diffCharRb);

        columnsPanel.add(modePanel);

        panel.add(columnsPanel);

        panel.add(Box.createVerticalStrut(25));

        // 4. Template Section
        JLabel templateLabel = new JLabel(BUNDLE.getString("seghistory_pref_template_label"));
        templateLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(templateLabel);

        txtTemplate = new JTextField();
        txtTemplate.setAlignmentX(Component.LEFT_ALIGNMENT);
        txtTemplate.setMaximumSize(new Dimension(Integer.MAX_VALUE, txtTemplate.getPreferredSize().height));
        panel.add(txtTemplate);

        panel.add(Box.createVerticalStrut(5));

        JPanel varPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        varPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        varPanel.add(new JLabel(BUNDLE.getString("seghistory_pref_template_vars_label") + " "));

        String[] vars = {
            "${hour}", "${minute}", "${second}", 
            "${day}", "${month}", "${year}", 
            "${length}", "${text}", "${author}", "${alt}", "${origin}"
        };
        cmbVariables = new JComboBox<>(vars);
        varPanel.add(cmbVariables);

        varPanel.add(Box.createHorizontalStrut(5));

        JButton btnInsert = new JButton(BUNDLE.getString("seghistory_pref_template_insert_button"));
        btnInsert.addActionListener(e -> {
            String var = (String) cmbVariables.getSelectedItem();
            if (var != null) {
                try {
                    txtTemplate.getDocument().insertString(txtTemplate.getCaretPosition(), var, null);
                    txtTemplate.requestFocus();
                } catch (Exception ignored) {}
            }
        });
        varPanel.add(btnInsert);
        panel.add(varPanel);

        panel.add(Box.createVerticalStrut(25));

        // 5. Shortcut
        JPanel shortcutPanel = new JPanel();
        shortcutPanel.setLayout(new BoxLayout(shortcutPanel, BoxLayout.LINE_AXIS));
        shortcutPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

        shortcutPanel.add(new JLabel(BUNDLE.getString("seghistory_pref_shortcut_label")));
        shortcutPanel.add(Box.createHorizontalStrut(5));

        keystrokeLabel = new JLabel();
        keystrokeLabel.setFont(keystrokeLabel.getFont().deriveFont(Font.BOLD));
        shortcutPanel.add(keystrokeLabel);

        shortcutPanel.add(Box.createHorizontalStrut(5));

        JButton setKeystrokeButton = new JButton(BUNDLE.getString("seghistory_pref_assign_shortcut_button"));
        setKeystrokeButton.addActionListener(e -> captureShortcut());
        shortcutPanel.add(setKeystrokeButton);

        shortcutPanel.add(Box.createHorizontalGlue()); // Push everything else to the left

        panel.add(shortcutPanel);

        panel.add(Box.createVerticalGlue()); // Push everything up

        load();
    }

    private void captureShortcut() {
        KeyStrokeEditorDialog dialog = new KeyStrokeEditorDialog(currentShortcut);
        if (dialog.show(SwingUtilities.windowForComponent(panel))) {
            currentShortcut = dialog.getResult();
            updateShortcutDisplay();
        }
    }

    private void updateShortcutDisplay() {
        if (currentShortcut != null) {
            keystrokeLabel.setText(StaticUIUtils.getKeyStrokeText(currentShortcut));
        } else {
            keystrokeLabel.setText(BUNDLE.getString("seghistory_pref_shortcut_not_set"));
        }
    }

    public static void setCallback(Runnable callback) {
        changeCallback = callback;
    }

    public static boolean isHistoryEnabled() {
        return Boolean.parseBoolean(Preferences.getPreferenceDefault(PREF_ENABLED, "true"));
    }

    public static boolean isEnabled() { return isHistoryEnabled(); }

    public static void setHistoryEnabled(boolean enabled) {
        Preferences.setPreference(PREF_ENABLED, Boolean.toString(enabled));
    }

    public static void setEnabled(boolean enabled) { setHistoryEnabled(enabled); }

    public static int getSnapshotInterval() {
        try {
            return Integer.parseInt(Preferences.getPreferenceDefault(PREF_INTERVAL, "10"));
        } catch (NumberFormatException e) {
            return 10;
        }
    }

    public static String getDiffBase() {
        return Preferences.getPreferenceDefault(PREF_DIFF_BASE, "CURRENT");
    }

    public static String getDiffMode() {
        return Preferences.getPreferenceDefault(PREF_DIFF_MODE, "WORD");
    }

    public static String getTemplate() {
        return Preferences.getPreferenceDefault(PREF_TEMPLATE, DEFAULT_TEMPLATE);
    }

    public static KeyStroke getShortcut() {
        String s = Preferences.getPreferenceDefault(PREF_SHORTCUT, null);
        if (s == null || s.isEmpty()) return null;
        try {
            return KeyStroke.getKeyStroke(s);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public Component getGui() { return panel; }

    @Override
    public String toString() { return BUNDLE.getString("seghistory_plugin_name"); }

    @Override
    public void initFromPrefs() {
        load();
    }

    private void load() {
        enableCb.setSelected(isHistoryEnabled());
        intervalSpinner.setValue(getSnapshotInterval());

        String diffBase = getDiffBase();
        if ("PREV".equals(diffBase)) {
            diffPrevRb.setSelected(true);
        } else {
            diffCurrentRb.setSelected(true);
        }

        String diffMode = getDiffMode();
        if ("CHAR".equals(diffMode)) {
            diffCharRb.setSelected(true);
        } else {
            diffWordRb.setSelected(true);
        }

        txtTemplate.setText(getTemplate());

        currentShortcut = getShortcut();
        updateShortcutDisplay();
    }

    @Override
    public void persist() {
        setHistoryEnabled(enableCb.isSelected());
        Preferences.setPreference(PREF_INTERVAL, intervalSpinner.getValue().toString());

        String diffBase = diffPrevRb.isSelected() ? "PREV" : "CURRENT";
        Preferences.setPreference(PREF_DIFF_BASE, diffBase);

        String diffMode = diffCharRb.isSelected() ? "CHAR" : "WORD";
        Preferences.setPreference(PREF_DIFF_MODE, diffMode);

        Preferences.setPreference(PREF_TEMPLATE, txtTemplate.getText());

        if (currentShortcut != null) {
            Preferences.setPreference(PREF_SHORTCUT, currentShortcut.toString());
        } else {
            Preferences.setPreference(PREF_SHORTCUT, "");
        }

        if (changeCallback != null) {
            changeCallback.run();
        }
    }

    @Override
    public void restoreDefaults() {
        enableCb.setSelected(true);
        intervalSpinner.setValue(3);
        diffCurrentRb.setSelected(true);
        diffWordRb.setSelected(true);
        txtTemplate.setText(DEFAULT_TEMPLATE);
        currentShortcut = null;
        updateShortcutDisplay();
    }
}
