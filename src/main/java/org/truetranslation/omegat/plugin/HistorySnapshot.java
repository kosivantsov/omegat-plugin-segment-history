package org.truetranslation.omegat.plugin;

import java.io.Serializable;

public class HistorySnapshot implements Serializable {
    private static final long serialVersionUID = 1L;

    private long timestamp;
    private String text;
    private boolean isAlternative;

    private String author;
    private String origin;

    public HistorySnapshot() {}

    public String getAuthor() { return author; }
    public void setAuthor(String author) { this.author = author; }

    public String getOrigin() { return origin; }
    public void setOrigin(String origin) { this.origin = origin; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }

    public boolean isAlternative() { return isAlternative; }
    public void setAlternative(boolean alternative) { isAlternative = alternative; }
}
