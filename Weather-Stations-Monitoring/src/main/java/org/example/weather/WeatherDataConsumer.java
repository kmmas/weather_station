package org.example.weather;

import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.example.KafkaConfig;
import org.example.weather.model.WeatherData;
import org.example.weather.util.JsonParser;
import java.time.Duration;
import java.util.*;

public class WeatherDataConsumer implements Runnable {
    private final KafkaConsumer<String, String> consumer;
    private final BitcaskStore bitcaskStore;
    private final ParquetArchiver parquetArchiver;
    private volatile boolean running = true;

    public WeatherDataConsumer(BitcaskStore bitcaskStore, ParquetArchiver parquetArchiver) {
        this.bitcaskStore = bitcaskStore;
        this.parquetArchiver = parquetArchiver;

        Properties props = new Properties();
        String bootstrapServers = System.getenv("KAFKA_BOOTSTRAP_SERVERS");
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "central-station-consumer");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");

        this.consumer = new KafkaConsumer<>(props);
    }

    @Override
    public void run() {
        try {
            consumer.subscribe(Arrays.asList("weather-data", "rain-alerts"));
            System.out.println("Consumer started, subscribed to topics");

            while (running) {
                try {
                    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(1000));
//                    System.out.println("Polled " + records.count() + " records");

                    for (ConsumerRecord<String, String> record : records) {
                        processMessage(record.topic(), record.value());
                    }
                } catch (Exception e) {
                    System.err.println("Error processing records: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        } finally {
            consumer.close();
            System.out.println("Consumer closed");
        }
    }

    private void processMessage(String topic, String message) {
        try {
//            System.out.println("Processing message from topic: " + topic);
            if (topic.equals("weather-data")) {
                WeatherData data = JsonParser.parseWeatherData(message);
//                System.out.println("Storing weather data for station: " + data.getStationId());
                bitcaskStore.put("station-" + data.getStationId(), message);
                parquetArchiver.addToBatch(data);
            }
//            else if (topic.equals("rain-alerts")) {
//                System.out.println("Rain Alert: " + message);
//            }
        } catch (Exception e) {
            System.err.println("Error processing message: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void close() {
        running = false;
    }
}