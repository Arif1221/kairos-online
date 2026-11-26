package com.skarif.kairos.lyrics;

import java.util.Collections;
import java.util.List;

/** Lyrics for one song, either time-synced or plain. */
public final class Lyrics {
    public static final String SOURCE_EMBEDDED = "embedded";
    public static final String SOURCE_LRC_FILE = "lrc";
    public static final String SOURCE_ONLINE = "online";

    public final boolean synced;
    public final List<LyricLine> lines;
    public final String source;

    public Lyrics(boolean synced, List<LyricLine> lines, String source) {
        this.synced = synced;
        this.lines = Collections.unmodifiableList(lines);
        this.source = source;
    }

    public boolean isEmpty() {
        return lines.isEmpty();
    }

    /**
     * Index of the line that should be highlighted at {@code positionMs}, or -1 before the first line.
     * Binary search, so it is cheap to call on every position update.
     */
    public int indexAt(long positionMs) {
        if (!synced || lines.isEmpty()) return -1;
        int lo = 0, hi = lines.size() - 1, found = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (lines.get(mid).timeMs <= positionMs) {
                found = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return found;
    }
}
