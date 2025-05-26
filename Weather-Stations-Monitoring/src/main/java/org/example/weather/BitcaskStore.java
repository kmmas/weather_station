package org.example.weather;

import org.example.weather.model.EntryMetadata;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
public class BitcaskStore {
    private static final int MAX_SEGMENT_SIZE = 1024 * 1024;
    private final Map<String, EntryMetadata> keyDir = new ConcurrentHashMap<>();
    private RandomAccessFile activeSegment;
    private long activeSegmentId = 0;
    private final String dataDirectory = System.getenv("BITCASK_DATA_PATH");
    private final ScheduledExecutorService compactionExecutor = Executors.newSingleThreadScheduledExecutor();
    public BitcaskStore() { initialize(); scheduleCompaction(); }

    private void initialize() {
        try {
            new File(dataDirectory).mkdirs();
            activeSegmentId = findLatestSegmentId();
            activeSegment = openOrCreateSegment(activeSegmentId);
            loadExistingData();
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize Bitcask", e);
        }
    }

    private long findLatestSegmentId() {
        File[] files = new File(dataDirectory).listFiles((dir, name) -> name.matches("segment-\\d+\\.data"));
        if (files == null || files.length == 0) return 0;
        return Arrays.stream(files).mapToLong(f -> Long.parseLong(f.getName().split("[.-]")[1])).max().orElse(0);
    }

    private RandomAccessFile openOrCreateSegment(long segmentId) throws IOException {
        return new RandomAccessFile(dataDirectory + "/segment-" + segmentId + ".data", "rw");
    }

    private void loadExistingData() throws IOException {
        loadFromHintFiles();
        scanSegmentsWithoutHints();
    }

    private void loadFromHintFiles() throws IOException {
        File[] hintFiles = new File(dataDirectory).listFiles((dir, name) -> name.matches("segment-\\d+\\.hint"));
        if (hintFiles == null) return;

        for (File hintFile : hintFiles) {
            long segmentId = Long.parseLong(hintFile.getName().split("[.-]")[1]);
            try (DataInputStream dis = new DataInputStream(new FileInputStream(hintFile))) {
                while (dis.available() > 0) {
                    long timestamp = dis.readLong();
                    int keySize = dis.readInt();
                    byte[] keyBytes = new byte[keySize];
                    dis.readFully(keyBytes);
                    String key = new String(keyBytes, StandardCharsets.UTF_8);
                    long offset = dis.readLong();
                    int size = dis.readInt();
                    keyDir.put(key, new EntryMetadata(segmentId, offset, size, timestamp));
                }
            }
        }
    }

    private void scanSegment(long segmentId) throws IOException {
        try (RandomAccessFile segment = new RandomAccessFile(dataDirectory + "/segment-" + segmentId + ".data", "r")) {
            long filePointer = 0;
            while (filePointer < segment.length()) {
                long timestamp = segment.readLong();
                int keySize = segment.readInt();
                int valueSize = segment.readInt();
                byte[] keyBytes = new byte[keySize];
                segment.readFully(keyBytes);
                String key = new String(keyBytes, StandardCharsets.UTF_8);
                segment.skipBytes(valueSize);
                int entrySize = 16 + keySize + valueSize;
                keyDir.put(key, new EntryMetadata(segmentId, filePointer, entrySize, timestamp));
                filePointer = segment.getFilePointer();
            }
        }
    }

    private void writeHintEntry(String key, long segmentId, long offset, int size, long timestamp) throws IOException {
        String hintPath = dataDirectory + "/segment-" + segmentId + ".hint";
        try (DataOutputStream dos = new DataOutputStream(new FileOutputStream(hintPath, true))) {
            dos.writeLong(timestamp);
            dos.writeInt(key.getBytes(StandardCharsets.UTF_8).length);
            dos.write(key.getBytes(StandardCharsets.UTF_8));
            dos.writeLong(offset);
            dos.writeInt(size);
        }
    }

    private void rotateSegment() throws IOException {
        activeSegment.close();
        activeSegmentId = getNextSegmentId(); // Use unified ID generation
        activeSegment = openOrCreateSegment(activeSegmentId);
    }

    private void scheduleCompaction() {
        compactionExecutor.scheduleAtFixedRate(this::compact, 1, 1, TimeUnit.HOURS);
    }
    private long getNextSegmentId() {
        File[] dataFiles = new File(dataDirectory).listFiles(
                f -> f.getName().matches("segment-\\d+\\.data")
        );

        return Arrays.stream(dataFiles)
                .mapToLong(f -> Long.parseLong(f.getName().split("[.-]")[1]))
                .max()
                .orElse(-1L) + 1;
    }
    private synchronized void compact() {
        try {
            System.out.println("Doing Compaction");
            List<Long> segmentIds = getSegmentIdsForCompaction();
            if (segmentIds.isEmpty()) return;
            // Get new segment id
            long newSegmentId = getNextSegmentId();

            // Safety check
            if (newSegmentId <= activeSegmentId) {
                throw new IllegalStateException("Segment ID conflict! New: " + newSegmentId
                        + " must be greater than active: " + activeSegmentId);
            }
            RandomAccessFile newSegment = openOrCreateSegment(newSegmentId);
            DataOutputStream hintStream = new DataOutputStream(
                    new FileOutputStream(dataDirectory + "/segment-" + newSegmentId + ".hint"));

            Map<String, EntryMetadata> compactedKeys = new HashMap<>();
            for (String key : keyDir.keySet()) {
                EntryMetadata meta = keyDir.get(key);
                if (segmentIds.contains(meta.getSegmentId())) {
                    compactedKeys.put(key, meta);
                }
            }

            for (Map.Entry<String, EntryMetadata> entry : compactedKeys.entrySet()) {
                String key = entry.getKey();
                EntryMetadata oldMeta = entry.getValue();
                String value = get(key);

                long offset = newSegment.getFilePointer();
                byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
                byte[] valueBytes = value.getBytes(StandardCharsets.UTF_8);

                newSegment.writeLong(oldMeta.getTimestamp());
                newSegment.writeInt(keyBytes.length);
                newSegment.writeInt(valueBytes.length);
                newSegment.write(keyBytes);
                newSegment.write(valueBytes);

                int entrySize = 16 + keyBytes.length + valueBytes.length;
                keyDir.put(key, new EntryMetadata(newSegmentId, offset, entrySize, oldMeta.getTimestamp()));

                hintStream.writeLong(oldMeta.getTimestamp());
                hintStream.writeInt(keyBytes.length);
                hintStream.write(keyBytes);
                hintStream.writeLong(offset);
                hintStream.writeInt(entrySize);
            }

            hintStream.close();
            newSegment.close();
            System.out.println("[Compaction] Starting - Segments to compact: " + segmentIds);
            System.out.println("[Compaction] Active segment ID: " + activeSegmentId);
            System.out.println("[Compaction] New segment ID: " + newSegmentId);
            System.out.println("[Compaction] Keys to preserve: " + compactedKeys.size());
            for (long segmentId : segmentIds) {
                boolean dataDeleted = new File(dataDirectory + "/segment-" + segmentId + ".data").delete();
                boolean hintDeleted = new File(dataDirectory + "/segment-" + segmentId + ".hint").delete();
                System.out.println("[Compaction] delete segment"+segmentId+"(data:"+dataDeleted+"(hint:"+hintDeleted);

            }
            System.out.println("[Compaction] Removed old segments: " + segmentIds);
            System.out.println("[Compaction] Final keyDir size: " + keyDir.size());
        } catch (IOException e) {
            System.err.println("Compaction failed: " + e.getMessage());
        }
    }

    public void put(String key, String value) {
        try {
            if (activeSegment.length() >= MAX_SEGMENT_SIZE) {
                rotateSegment();
            }

            long offset = activeSegment.getFilePointer();
            byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
            byte[] valueBytes = value.getBytes(StandardCharsets.UTF_8);
            long timestamp = System.currentTimeMillis();

            activeSegment.writeLong(timestamp);
            activeSegment.writeInt(keyBytes.length);
            activeSegment.writeInt(valueBytes.length);
            activeSegment.write(keyBytes);
            activeSegment.write(valueBytes);

            keyDir.put(key, new EntryMetadata(activeSegmentId, offset,
                    keyBytes.length + valueBytes.length + 16, timestamp));

            writeHintEntry(key, activeSegmentId, offset,
                    keyBytes.length + valueBytes.length + 16, timestamp);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write to Bitcask", e);
        }
    }

    public String get(String key) {
        EntryMetadata metadata = keyDir.get(key);
        if (metadata == null) return null;

        try (RandomAccessFile segment = openSegmentForReading(metadata.getSegmentId())) {
            segment.seek(metadata.getOffset());
            segment.readLong(); // Skip timestamp
            int keySize = segment.readInt();
            int valueSize = segment.readInt();
            segment.skipBytes(keySize);
            byte[] valueBytes = new byte[valueSize];
            segment.readFully(valueBytes);
            return new String(valueBytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read from Bitcask", e);
        }
    }

    public Map<String, String> getAll() {
        Map<String, String> result = new HashMap<>();
        for (String key : keyDir.keySet()) {
            result.put(key, get(key));
        }
        return result;
    }

    private RandomAccessFile openSegmentForReading(long segmentId) throws IOException {
        return new RandomAccessFile(dataDirectory + "/segment-" + segmentId + ".data", "r");
    }
    // Add these methods to the BitcaskStore class
    private void scanSegmentsWithoutHints() throws IOException {
        File[] dataFiles = new File(dataDirectory).listFiles((dir, name) ->
                name.matches("segment-\\d+\\.data"));

        if (dataFiles == null) return;

        for (File dataFile : dataFiles) {
            long segmentId = Long.parseLong(dataFile.getName().split("[.-]")[1]);
            File hintFile = new File(dataDirectory + "/segment-" + segmentId + ".hint");
            if (!hintFile.exists()) {
                scanSegment(segmentId);
            }
        }
    }

    private List<Long> getSegmentIdsForCompaction() {
        File[] dataFiles = new File(dataDirectory).listFiles((dir, name) ->
                name.matches("segment-\\d+\\.data"));

        if (dataFiles == null || dataFiles.length <= 1) return Collections.emptyList();

        return Arrays.stream(dataFiles)
                .map(f -> Long.parseLong(f.getName().split("[.-]")[1]))
                .filter(id -> id != activeSegmentId)
                .sorted()
                .collect(Collectors.toList());
    }
    public void close() {
        try {
            compactionExecutor.shutdown();
            activeSegment.close();
        } catch (IOException e) {
            System.err.println("Error closing Bitcask: " + e.getMessage());
        }
    }
}