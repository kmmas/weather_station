package org.example;

import org.apache.kafka.clients.producer.*;
import java.util.*;
import java.util.concurrent.*;

public class WeatherStation {
    private final long stationId;
    private long messageCounter = 0;
    private final KafkaProducer<String, String> producer;
    private final String topic;

    public WeatherStation(long stationId) {
        this.stationId = stationId;
        this.topic = "weather-data";
        this.producer = new KafkaProducer<>(KafkaConfig.getProducerConfig());
    }

    private String getBatteryStatus() {
        double rand = Math.random();
        if (rand < 0.3) return "low";
        else if (rand < 0.7) return "medium";
        else return "high";
    }

    private boolean shouldDropMessage() {
        return Math.random() < 0.10; // 10% drop rate
    }

    public String generateMessage() {
        messageCounter++;
        if (shouldDropMessage()) return null;

        // Simulate occasional rain (humidity > 70%)
        int humidity = Math.random() < 0.15 ?
                70 + new Random().nextInt(30) :
                new Random().nextInt(70);

        return String.format(
                "{ \"stationId\": %d, \"sNo\": %d, \"batteryStatus\": \"%s\", " +
                        "\"statusTimestamp\": %d, \"weather\": { " +
                        "\"humidity\": %d, \"temperature\": %d, \"windSpeed\": %d } }",
                stationId, messageCounter, getBatteryStatus(),
                System.currentTimeMillis() / 1000,
                humidity,
                -20 + new Random().nextInt(140),
                new Random().nextInt(100)
        );
    }

    public void run() {
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        scheduler.scheduleAtFixedRate(() -> {
            String message = generateMessage();
            if (message != null) {
                producer.send(new ProducerRecord<>(topic, String.valueOf(stationId), message),
                        (metadata, e) -> {
                            if (e != null) System.err.println("Send failed: " + e.getMessage());
                        });
                System.out.printf("[Station %d] Sent: s_no=%d%n", stationId, messageCounter);
            } else {
                System.out.printf("[Station %d] Dropped: s_no=%d%n", stationId, messageCounter);
            }
        }, 0, 1, TimeUnit.SECONDS);
    }

    public static void main(String[] args) {
        // Get station pod name from environment variable or use default
        String podName = System.getenv().getOrDefault("POD_NAME", "weather-station-0");
        long stationId = 1; // Default station ID

        try {
            // Extract the last numeric part from the pod name
            String[] parts = podName.split("-");
            String idStr = parts[parts.length - 1];
            stationId = Long.parseLong(idStr) + 1;
        } catch (Exception e) {
            System.err.println("⚠ Failed to parse station ID from pod name: " + podName);
            e.printStackTrace();
            System.exit(1);
        }

        System.out.printf("🚀 Starting Weather Station with ID: %d%n", stationId);
        new WeatherStation(stationId).run();
    }
}

