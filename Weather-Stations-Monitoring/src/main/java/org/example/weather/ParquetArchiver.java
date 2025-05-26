package org.example.weather;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.hadoop.conf.Configuration;
import org.apache.parquet.avro.AvroParquetWriter;
import org.apache.parquet.hadoop.ParquetWriter;
import org.example.weather.model.WeatherData;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

public class ParquetArchiver {
    private static final int BATCH_SIZE = 10000;
    private final Queue<WeatherData> batch = new ConcurrentLinkedQueue<>();

    private static final String AVRO_SCHEMA =
            "{" +
                    "  \"type\": \"record\"," +
                    "  \"name\": \"WeatherData\"," +
                    "  \"fields\": [" +
                    "    {\"name\": \"station_id\", \"type\": \"long\"}," +
                    "    {\"name\": \"s_no\", \"type\": \"long\"}," +
                    "    {\"name\": \"battery_status\", \"type\": \"string\"}," +
                    "    {\"name\": \"status_timestamp\", \"type\": \"long\"}," +
                    "    {" +
                    "      \"name\": \"weather\"," +
                    "      \"type\": {" +
                    "        \"type\": \"record\"," +
                    "        \"name\": \"Weather\"," +
                    "        \"fields\": [" +
                    "          {\"name\": \"humidity\", \"type\": \"int\"}," +
                    "          {\"name\": \"temperature\", \"type\": \"int\"}," +
                    "          {\"name\": \"wind_speed\", \"type\": \"int\"}" +
                    "        ]" +
                    "      }" +
                    "    }" +
                    "  ]" +
                    "}";

    private final Schema schema;
    private Configuration getHadoopConfig() {
        Configuration conf = new Configuration();
        // Set Hadoop home directory (replace with your actual path)
        System.setProperty("hadoop.home.dir", "C:/hadoop");
        // Force local filesystem
        conf.set("fs.file.impl", org.apache.hadoop.fs.LocalFileSystem.class.getName());
        conf.set("fs.defaultFS", "file:///");
        conf.set("parquet.avro.write-checksum", "false"); // In getHadoopConfig()
        // Disable dictionary encoding for all columns
        conf.set("parquet.enable.dictionary", "false");
        conf.set("parquet.avro.add-list-element-records", "false");
        return conf;
    }
    public ParquetArchiver() {
        this.schema = new Schema.Parser().parse(AVRO_SCHEMA);
    }

    public void addToBatch(WeatherData data) {
        batch.add(data);
        System.out.println("Batch: "  + batch.size() + "/"+BATCH_SIZE);
        if (batch.size() >= BATCH_SIZE) {
            System.out.println("Flushing the batches");
            flush();
        }
    }

    private synchronized void flush() {
        if (batch.isEmpty()) return;

        List<WeatherData> currentBatch = new ArrayList<>();
        while (!batch.isEmpty() && currentBatch.size() < BATCH_SIZE) {
            currentBatch.add(batch.poll());
        }

        // Group by date first, then by station ID
        Map<String, List<WeatherData>> groupedData = new HashMap<>();
        for (WeatherData data : currentBatch) {
            long timestampMillis = data.getStatusTimestamp() * 1000;
            LocalDateTime dateTime = LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(timestampMillis),
                    ZoneId.systemDefault()
            );
            String date = dateTime.toLocalDate().toString();
            String key = date + "_" + data.getStationId(); // Change grouping key
            groupedData.computeIfAbsent(key, k -> new ArrayList<>()).add(data);
        }

        groupedData.forEach((key, list) -> {
            String[] parts = key.split("_");
            String date = parts[0];
            long stationId = Long.parseLong(parts[1]);

            Path outputDir = Paths.get(System.getenv("PARQUET_DATA_PATH"), "date=" + date, "station_id=" + stationId);            String fileName = "data_" + System.currentTimeMillis() + ".parquet";
            Path outputPath = outputDir.resolve(fileName);

            try {
                Files.createDirectories(outputDir);
                writeParquetFile(list, outputPath);
            } catch (IOException e) {
                System.err.println("Failed to write Parquet file: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    private void writeParquetFile(List<WeatherData> dataList, Path outputPath) throws IOException {
        try (ParquetWriter<GenericRecord> writer = AvroParquetWriter.<GenericRecord>builder(
                        new org.apache.hadoop.fs.Path(outputPath.toString()))
                .withSchema(schema)
                .withConf(getHadoopConfig())
                .build()) {

            for (WeatherData data : dataList) {
                GenericRecord record = new GenericData.Record(schema);

                // Map Java model fields to Avro schema fields
                record.put("station_id", data.getStationId());
                record.put("s_no", data.getSNo());
                record.put("battery_status", data.getBatteryStatus());
                record.put("status_timestamp", data.getStatusTimestamp());

                // Handle nested weather data
                Schema weatherSchema = schema.getField("weather").schema();
                GenericRecord weatherRecord = new GenericData.Record(weatherSchema);
                weatherRecord.put("humidity", data.getWeather().getHumidity());
                weatherRecord.put("temperature", data.getWeather().getTemperature());
                weatherRecord.put("wind_speed", data.getWeather().getWindSpeed());

                record.put("weather", weatherRecord);
                writer.write(record);
            }
        }
    }

    public void close() {
        System.out.println("Flushing the batches of size "+ batch.size());
        flush();
        System.out.println("Parquet archiver closed. Final batch flushed.");
    }
}