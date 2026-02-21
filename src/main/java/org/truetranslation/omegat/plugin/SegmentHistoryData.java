package org.truetranslation.omegat.plugin;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class SegmentHistoryData implements Serializable {
    private static final long serialVersionUID = 1L;

    private String sourceText = "";
    private int segmentId = 0;
    private String sourceFileName = "";
    private int relativePosition = 0;
    private String prevSegmentSource = "";
    private String nextSegmentSource = "";
    private List<HistorySnapshot> snapshots = new ArrayList<>();

    public SegmentHistoryData() {}

    public String getSourceText() { return sourceText; }
    public void setSourceText(String sourceText) { this.sourceText = sourceText; }

    public int getSegmentId() { return segmentId; }
    public void setSegmentId(int segmentId) { this.segmentId = segmentId; }

    public String getSourceFileName() { return sourceFileName; }
    public void setSourceFileName(String sourceFileName) { this.sourceFileName = sourceFileName; }

    public int getRelativePosition() { return relativePosition; }
    public void setRelativePosition(int relativePosition) { this.relativePosition = relativePosition; }

    public String getPrevSegmentSource() { return prevSegmentSource; }
    public void setPrevSegmentSource(String prevSegmentSource) { this.prevSegmentSource = prevSegmentSource; }

    public String getNextSegmentSource() { return nextSegmentSource; }
    public void setNextSegmentSource(String nextSegmentSource) { this.nextSegmentSource = nextSegmentSource; }

    public List<HistorySnapshot> getSnapshots() { return snapshots; }
    public void setSnapshots(List<HistorySnapshot> snapshots) { this.snapshots = snapshots; }

    public void addSnapshot(HistorySnapshot s) {
        if (this.snapshots == null) this.snapshots = new ArrayList<>();
        this.snapshots.add(s);
    }
}
