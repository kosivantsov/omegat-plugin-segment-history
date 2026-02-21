package org.truetranslation.omegat.plugin;

import org.omegat.core.Core;
import org.omegat.core.data.SourceTextEntry;
import org.omegat.util.Preferences;
import org.omegat.util.gui.Styles;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.ListSelectionEvent;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.text.MessageFormat;
import java.util.*;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SegmentHistoryDialog extends JDialog {

    private static final ResourceBundle BUNDLE = ResourceBundle.getBundle("org.truetranslation.omegat.plugin.SegmentHistoryBundle");
    private static SegmentHistoryDialog currentInstance;

    private JList<HistorySnapshot> list;
    private JTextPane diffPane;
    private final SegmentHistoryManager manager;
    private SourceTextEntry currentEntry;
    
    // UI Controls
    private JRadioButton rbDiffCurrent;
    private JRadioButton rbDiffPrev;
    private JRadioButton rbDiffWord;
    private JRadioButton rbDiffChar;

    public static void show(Frame owner, SegmentHistoryManager manager, SourceTextEntry entry) {
        if (currentInstance != null && currentInstance.isVisible()) {
            currentInstance.updateForEntry(entry);
            currentInstance.toFront();
        } else {
            currentInstance = new SegmentHistoryDialog(owner, manager, entry);
            currentInstance.setVisible(true);
        }
    }
    
    public static void updateIfVisible(SourceTextEntry entry) {
        if (currentInstance != null && currentInstance.isVisible()) {
            currentInstance.updateForEntry(entry);
        }
    }

    private SegmentHistoryDialog(Frame owner, SegmentHistoryManager manager, SourceTextEntry entry) {
        super(owner, BUNDLE.getString("seghistory_dialog_title"), false);
        this.manager = manager;
        
        setLayout(new BorderLayout());
        setSize(900, 700);
        setLocationRelativeTo(owner);
        
        list = new JList<>();
        list.setCellRenderer(new HistoryCellRenderer());
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.addListSelectionListener(this::onSelectionChange);
        list.setBackground(Styles.EditorColor.COLOR_BACKGROUND.getColor());
        
        list.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) restoreSelected();
            }
        });

        diffPane = new JTextPane();
        diffPane.setEditable(false);
        // Use OmegaT editor styles if available
        diffPane.setBackground(Styles.EditorColor.COLOR_BACKGROUND.getColor());
        diffPane.setForeground(Styles.EditorColor.COLOR_FOREGROUND.getColor());
        diffPane.setCaretColor(Styles.EditorColor.COLOR_FOREGROUND.getColor());
        try { diffPane.setFont(getEditorFont()); } catch (Exception ignored) {}

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(list), new JScrollPane(diffPane));
        split.setDividerLocation(250);
        add(split, BorderLayout.CENTER);

        // --- BOTTOM: Controls & Buttons ---
        JPanel bottomPanel = new JPanel();
        bottomPanel.setLayout(new BoxLayout(bottomPanel, BoxLayout.Y_AXIS));

        // 1. Controls Row
        JPanel controlsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        
        // Compare Base Group
        JPanel pnlBase = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        pnlBase.add(new JLabel(BUNDLE.getString("seghistory_dialog_compare_with") + " "));
        rbDiffCurrent = new JRadioButton(BUNDLE.getString("seghistory_dialog_compare_current"));
        rbDiffPrev = new JRadioButton(BUNDLE.getString("seghistory_dialog_compare_prev"));
        ButtonGroup bgBase = new ButtonGroup();
        bgBase.add(rbDiffCurrent);
        bgBase.add(rbDiffPrev);
        pnlBase.add(rbDiffCurrent);
        pnlBase.add(rbDiffPrev);
        
        // Diff Granularity Group
        JPanel pnlMode = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        pnlMode.add(new JLabel("   " + BUNDLE.getString("seghistory_dialog_diff_type") + " "));
        rbDiffWord = new JRadioButton(BUNDLE.getString("seghistory_dialog_diff_word"));
        rbDiffChar = new JRadioButton(BUNDLE.getString("seghistory_dialog_diff_char"));
        ButtonGroup bgMode = new ButtonGroup();
        bgMode.add(rbDiffWord);
        bgMode.add(rbDiffChar);
        pnlMode.add(rbDiffWord);
        pnlMode.add(rbDiffChar);

        // Load defaults from prefs
        if ("PREV".equals(SegmentHistoryPrefs.getDiffBase())) rbDiffPrev.setSelected(true);
        else rbDiffCurrent.setSelected(true);

        if ("CHAR".equals(SegmentHistoryPrefs.getDiffMode())) rbDiffChar.setSelected(true);
        else rbDiffWord.setSelected(true);

        // Listener to update view immediately
        Runnable updateView = this::updateDiffView;
        rbDiffCurrent.addActionListener(e -> updateView.run());
        rbDiffPrev.addActionListener(e -> updateView.run());
        rbDiffWord.addActionListener(e -> updateView.run());
        rbDiffChar.addActionListener(e -> updateView.run());

        controlsPanel.add(pnlBase);
        controlsPanel.add(pnlMode);
        bottomPanel.add(controlsPanel);

        // 2. Button Row
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton btnRestore = new JButton(BUNDLE.getString("seghistory_dialog_btn_restore"));
        btnRestore.addActionListener(e -> restoreSelected());
        JButton btnCancel = new JButton(BUNDLE.getString("seghistory_dialog_btn_close"));
        btnCancel.addActionListener(e -> dispose());
        
        buttonPanel.add(btnRestore);
        buttonPanel.add(btnCancel);
        bottomPanel.add(buttonPanel);
        
        add(bottomPanel, BorderLayout.SOUTH);

        // --- Global ESC Key Binding ---
        JRootPane rootPane = getRootPane();
        rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "closeDialog");
        rootPane.getActionMap().put("closeDialog", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                dispose();
            }
        });
        
        // ------------------------------

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                currentInstance = null;
                // Unregister listener when dialog closes
                if (manager != null) {
                    manager.setUpdateListener(null);
                }
            }
        });
        
        // Register this dialog as a listener to history updates
        manager.setUpdateListener(() -> SwingUtilities.invokeLater(() -> updateForEntry(this.currentEntry)));

        updateForEntry(entry);
    }
    
    public void updateForEntry(SourceTextEntry entry) {
        if (entry == null) return;
        this.currentEntry = entry;
        
        // Store selected index to restore it after update
        int selectedIndex = list.getSelectedIndex();
        
        List<HistorySnapshot> history = new ArrayList<>(manager.getHistoryFor(entry));
        // Sort: newest first
        history.sort((s1, s2) -> Long.compare(s2.getTimestamp(), s1.getTimestamp()));
        
        list.setListData(history.toArray(new HistorySnapshot[0]));
        
        boolean isAlternative = manager.isAlternativeMode();
        String typeText = isAlternative ? BUNDLE.getString("seghistory_dialog_type_alternative") 
                                        : BUNDLE.getString("seghistory_dialog_type_default");
        
        String title = MessageFormat.format(BUNDLE.getString("seghistory_dialog_title_format"), 
                entry.entryNum(), typeText, history.size());
        setTitle(title);
        
        // Restore selection or select first
        if (!history.isEmpty()) {
            if (selectedIndex >= 0 && selectedIndex < history.size()) {
                list.setSelectedIndex(selectedIndex);
            } else {
                list.setSelectedIndex(0);
            }
        } else {
            diffPane.setText("");
        }
    }
    
    private Font getEditorFont() {
        String name = Preferences.getPreferenceDefault(Preferences.TF_SRC_FONT_NAME, Preferences.TF_FONT_DEFAULT);
        int size = Preferences.getPreferenceDefault(Preferences.TF_SRC_FONT_SIZE, Preferences.TF_FONT_SIZE_DEFAULT);
        return new Font(name, Font.PLAIN, size);
    }
    
    private void onSelectionChange(ListSelectionEvent e) {
        if (e != null && e.getValueIsAdjusting()) return;
        updateDiffView();
    }
    
    private void updateDiffView() {
        int index = list.getSelectedIndex();
        if (index == -1) {
            diffPane.setText("");
            return;
        }

        HistorySnapshot selected = list.getSelectedValue();
        String currentTargetText = Core.getEditor().getCurrentTranslation();
        if (currentTargetText == null) currentTargetText = "";
        
        String baseText;
        boolean compareWithPrev = rbDiffPrev.isSelected();

        if (compareWithPrev) {
            ListModel<HistorySnapshot> model = list.getModel();
            if (index < model.getSize() - 1) {
                baseText = model.getElementAt(index + 1).getText();
            } else {
                baseText = ""; // No previous snapshot (it's the oldest)
            }
        } else {
            // Compare against current editor state
            baseText = currentTargetText;
        }

        renderDiff(baseText, selected.getText());
    }
    
    private void restoreSelected() {
        HistorySnapshot selected = list.getSelectedValue();
        if (selected != null) {
            String current = Core.getEditor().getCurrentTranslation();
            manager.forceSnapshot(current); // Save current state before restoring old one
            Core.getEditor().replaceEditText(selected.getText());
        }
    }

    private void renderDiff(String base, String revised) {
        if (base == null) base = "";
        if (revised == null) revised = "";

        List<String> baseTokens = tokenize(base);
        List<String> revisedTokens = tokenize(revised);

        List<DiffOp> ops = computeDiff(baseTokens, revisedTokens);

        diffPane.setText("");
        StyledDocument doc = diffPane.getStyledDocument();
        
        Color fgColor = Styles.EditorColor.COLOR_FOREGROUND.getColor();
        Color delColor = Styles.EditorColor.COLOR_MATCHES_DEL_ACTIVE.getColor();
        if (delColor == null) delColor = Color.RED;
        Color insColor = Styles.EditorColor.COLOR_MATCHES_INS_ACTIVE.getColor();
        if (insColor == null) insColor = Color.BLUE;

        Style def = doc.addStyle("default", null);
        StyleConstants.setForeground(def, fgColor);

        Style del = doc.addStyle("deleted", null);
        StyleConstants.setForeground(del, delColor);
        StyleConstants.setStrikeThrough(del, true);

        Style ins = doc.addStyle("inserted", null);
        StyleConstants.setForeground(ins, insColor);
        StyleConstants.setUnderline(ins, true); 
        
        try {
            for (DiffOp op : ops) {
                switch (op.type) {
                    case EQUAL:
                        doc.insertString(doc.getLength(), op.text, def);
                        break;
                    case DELETE:
                        doc.insertString(doc.getLength(), op.text, del);
                        break;
                    case INSERT:
                        doc.insertString(doc.getLength(), op.text, ins);
                        break;
                }
            }
        } catch (BadLocationException e) {
            e.printStackTrace();
        }
        diffPane.setCaretPosition(0);
    }

    private List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        boolean wordMode = rbDiffWord.isSelected();
        if (wordMode) {
            Matcher m = Pattern.compile("(?U)\\w+|\\s+|\\W").matcher(text);
            while (m.find()) tokens.add(m.group());
        } else {
            for (char c : text.toCharArray()) tokens.add(String.valueOf(c));
        }
        return tokens;
    }

    private enum OpType { EQUAL, INSERT, DELETE }
    private static class DiffOp {
        OpType type;
        String text;
        DiffOp(OpType type, String text) { this.type = type; this.text = text; }
    }

    private List<DiffOp> computeDiff(List<String> s1, List<String> s2) {
        int[][] dp = new int[s1.size() + 1][s2.size() + 1];
        for (int i = 1; i <= s1.size(); i++) {
            for (int j = 1; j <= s2.size(); j++) {
                if (s1.get(i - 1).equals(s2.get(j - 1))) {
                    dp[i][j] = dp[i - 1][j - 1] + 1;
                } else {
                    dp[i][j] = Math.max(dp[i - 1][j], dp[i][j - 1]);
                }
            }
        }
        
        int i = s1.size(), j = s2.size();
        LinkedList<DiffOp> stack = new LinkedList<>();
        while (i > 0 && j > 0) {
            if (s1.get(i - 1).equals(s2.get(j - 1))) {
                stack.addFirst(new DiffOp(OpType.EQUAL, s1.get(i - 1)));
                i--; j--;
            } else if (dp[i - 1][j] > dp[i][j - 1]) {
                stack.addFirst(new DiffOp(OpType.DELETE, s1.get(i - 1)));
                i--;
            } else {
                stack.addFirst(new DiffOp(OpType.INSERT, s2.get(j - 1)));
                j--;
            }
        }
        while (i > 0) { stack.addFirst(new DiffOp(OpType.DELETE, s1.get(i - 1))); i--; }
        while (j > 0) { stack.addFirst(new DiffOp(OpType.INSERT, s2.get(j - 1))); j--; }
        return new ArrayList<>(stack);
    }
    
    private static class HistoryCellRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            
            HistorySnapshot snap = (HistorySnapshot) value;
            String template = SegmentHistoryPrefs.getTemplate();
            
            // Interpret template control characters
            template = template.replace("\\t", "&nbsp;&nbsp;&nbsp;&nbsp;").replace("\\n", "<br>");
            
            Date date = new Date(snap.getTimestamp());
            Calendar cal = Calendar.getInstance();
            cal.setTime(date);
            
            String txt = snap.getText() == null ? "" : snap.getText();
            // Important: Replace actual newlines/returns with spaces for the summary line
            String summary = txt.replace("\n", " ").replace("\r", " ").trim();
            if (summary.length() > 80) summary = summary.substring(0, 80) + "...";
            
            String author = snap.getAuthor();
            if (author == null || author.isEmpty()) {
                author = BUNDLE.getString("seghistory_dialog_unknown_author");
            }
            
            String altTag = snap.isAlternative() ? escapeHtml(BUNDLE.getString("seghistory_dialog_alt_tag")) : escapeHtml(BUNDLE.getString("seghistory_dialog_def_tag"));

            String hexInfo = toHex(Styles.EditorColor.COLOR_ACTIVE_SOURCE_FG.getColor());
            String hexText = toHex(Styles.EditorColor.COLOR_FOREGROUND.getColor());

            String rText = String.format("<span style='color:%s'>%s</span>", hexText, escapeHtml(summary));
            
            String formatted = template
                .replace("${hour}", String.format("%02d", cal.get(Calendar.HOUR_OF_DAY)))
                .replace("${minute}", String.format("%02d", cal.get(Calendar.MINUTE)))
                .replace("${second}", String.format("%02d", cal.get(Calendar.SECOND)))
                .replace("${day}", String.format("%02d", cal.get(Calendar.DAY_OF_MONTH)))
                .replace("${month}", String.format("%02d", cal.get(Calendar.MONTH) + 1))
                .replace("${year}", String.format("%04d", cal.get(Calendar.YEAR)))
                .replace("${length}", String.valueOf(txt.length()))
                .replace("${text}", rText)
                .replace("${author}", escapeHtml(author))
                .replace("${alt}", altTag);

            setText(String.format("<html><span style='color:%s'>%s</span></html>", hexInfo, formatted));
            
            if (isSelected) {
                setBackground(list.getSelectionBackground());
                setForeground(list.getSelectionForeground());
            } else {
                setBackground(list.getBackground());
                setForeground(list.getForeground());
            }
            return this;
        }

        private static String toHex(Color c) {
            if (c == null) return "#000000";
            return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
        }

        private static String escapeHtml(String s) {
            if (s == null) return "";
            return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }
    }
}
