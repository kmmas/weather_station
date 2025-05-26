package org.example.weather;

import org.apache.avro.generic.GenericRecord;
import org.apache.hadoop.conf.Configuration;
import org.apache.http.HttpHost;
import org.apache.parquet.avro.AvroParquetReader;
import org.apache.parquet.hadoop.ParquetReader;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.xcontent.XContentType;

import java.io.IOException;
import java.nio.file.*;
import java.util.Set;
import java.util.concurrent.*;

public class ParquetToElasticsearchIndexer {
    private final RestHighLevelClient esClient;
    private final String parquetDataPath;
    private final ScheduledExecutorService scheduler;
    private final Set<Path> processedFiles = ConcurrentHashMap.newKeySet();

    public ParquetToElasticsearchIndexer() throws IOException {
        String esHost = System.getenv("ELASTICSEARCH_HOST");
        int esPort = Integer.parseInt(System.getenv("ELASTICSEARCH_PORT"));
        String dataPath = System.getenv("PARQUET_DATA_PATH");

        this.esClient = new RestHighLevelClient(
                RestClient.builder(new HttpHost(esHost, esPort, "http"))
        );
        this.parquetDataPath = dataPath;
        Files.createDirectories(Paths.get(parquetDataPath));
        this.scheduler = Executors.newScheduledThreadPool(2);
    }

    public void start() {
        scheduler.scheduleAtFixedRate(this::indexNewParquetFiles, 0, 5, TimeUnit.MINUTES);
    }

    private void indexNewParquetFiles() {
        try {
            Files.walk(Paths.get(parquetDataPath))
                    .filter(path -> path.toString().endsWith(".parquet"))
                    .filter(path -> !processedFiles.contains(path)) // Skip already processed files
                    .forEach(path -> {
                        try {
                            if (indexParquetFile(path)) {
                                processedFiles.add(path);
                                System.out.println("Successfully indexed: " + path);
                            }
                        } catch (IOException e) {
                            System.err.println("Failed to index " + path + ": " + e.getMessage());
                        }
                    });
        } catch (IOException e) {
            System.err.println("Directory traversal failed: " + e.getMessage());
        }
    }

    private boolean indexParquetFile(Path parquetFile) throws IOException {
        Configuration conf = new Configuration();
        BulkRequest bulkRequest = new BulkRequest();
        int batchSize = 0;
        final int maxBatchSize = 500; // Smaller batches for better reliability

        try (ParquetReader<GenericRecord> reader = AvroParquetReader.<GenericRecord>builder(
                        new org.apache.hadoop.fs.Path(parquetFile.toString()))
                .withConf(conf)
                .build()) {

            GenericRecord record;
            while ((record = reader.read()) != null) {
                try {
                    IndexRequest request = new IndexRequest("weather_data")
                            .id(record.get("station_id") + "-" + record.get("s_no"))
                            .source(convertRecordToSafeJson(record), XContentType.JSON);
                    bulkRequest.add(request);
                    batchSize++;

                    // Send batches incrementally
                    if (batchSize >= maxBatchSize) {
                        sendBulkRequest(bulkRequest);
                        bulkRequest = new BulkRequest();
                        batchSize = 0;
                    }
                } catch (Exception e) {
                    System.err.println("Failed to process record in " + parquetFile + ": " + e.getMessage());
                }
            }

            // Send remaining documents
            if (bulkRequest.numberOfActions() > 0) {
                sendBulkRequest(bulkRequest);
            }
            return true;

        } catch (Exception e) {
            System.err.println("Failed to process file " + parquetFile + ": " + e.getMessage());
            return false;
        }
    }

    private void sendBulkRequest(BulkRequest bulkRequest) throws IOException {
        try {
            BulkResponse response = esClient.bulk(bulkRequest, RequestOptions.DEFAULT);
            if (response.hasFailures()) {
                System.err.println("Bulk request had failures: " + response.buildFailureMessage());
            }
        } catch (Exception e) {
            System.err.println("Bulk request failed: " + e.getMessage());
            throw e;
        }
    }

    private String convertRecordToSafeJson(GenericRecord record) {
        StringBuilder json = new StringBuilder();
        json.append("{");
        // Add all top-level fields
        json.append("\"station_id\":").append(record.get("station_id")).append(",");
        json.append("\"s_no\":").append(record.get("s_no")).append(",");
        json.append("\"battery_status\":\"").append(record.get("battery_status")).append("\",");
        json.append("\"status_timestamp\":").append(record.get("status_timestamp")).append(",");

        // Handle nested weather object
        Object weather = record.get("weather");
        if (weather instanceof GenericRecord) {
            GenericRecord weatherRec = (GenericRecord) weather;
            json.append("\"weather\":{");
            json.append("\"humidity\":").append(weatherRec.get("humidity")).append(",");
            json.append("\"temperature\":").append(weatherRec.get("temperature")).append(",");
            json.append("\"wind_speed\":").append(weatherRec.get("wind_speed"));
            json.append("}");
        }

        json.append("}");
        return json.toString();
    }

    public void close() throws IOException {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(30, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        esClient.close();
    }
}