package org.example.weather.model;

public class EntryMetadata {
    private final long segmentId;
    private final long offset;
    private final int size;
    private final long timestamp;

    public EntryMetadata(long segmentId, long offset, int size, long timestamp) {
        this.segmentId = segmentId;
        this.offset = offset;
        this.size = size;
        this.timestamp = timestamp;
    }

    // Getters
    public long getSegmentId() { return segmentId; }
    public long getOffset() { return offset; }
    public int getSize() { return size; }
    public long getTimestamp() { return timestamp; }
}