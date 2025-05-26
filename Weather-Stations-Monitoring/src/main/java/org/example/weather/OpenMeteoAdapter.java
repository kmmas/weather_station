package org.example.weather;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.example.KafkaConfig;
import org.springframework.web.client.RestTemplate;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

public class OpenMeteoAdapter {
    private final KafkaProducer<String, String> producer;
    private final String topic = "weather-data";
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private long messageCounter = 0;
    private final long virtualStationId = 1000;
    private final RestTemplate restTemplate = new RestTemplate();

    public OpenMeteoAdapter() {
        this.producer = new KafkaProducer<>(KafkaConfig.getProducerConfig());
    }

    public void startFetching(double latitude, double longitude) {
        // Initial immediate fetch
        fetchAndSendData(latitude, longitude);

        // Then fetch every hour
        scheduler.scheduleAtFixedRate(() -> {
            fetchAndSendData(latitude, longitude);
        }, 1, 1, TimeUnit.HOURS);
    }

    private void fetchAndSendData(double latitude, double longitude) {
        try {
            System.out.println("Fetching data for latitude: " + latitude + ", longitude: " + longitude);
            List<HourlyWeatherData> hourlyData = fetchHourlyWeatherData(latitude, longitude);
            System.out.println("Received " + hourlyData.size() + " hourly records from API");

            if (!hourlyData.isEmpty()) {
                sendHourlyDataToKafka(hourlyData);
                System.out.println("Successfully sent " + hourlyData.size() + " records to Kafka");
            } else {
                System.out.println("No data received from API");
            }
        } catch (Exception e) {
            System.err.println("Error in fetch/send: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private List<HourlyWeatherData> fetchHourlyWeatherData(double latitude, double longitude) {
        String url = String.format(
                "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f&hourly=temperature_2m,relative_humidity_2m,wind_speed_10m",
                latitude, longitude
        );

        System.out.println("Requesting URL: " + url);
        OpenMeteoResponse response = restTemplate.getForObject(url, OpenMeteoResponse.class);

        if (response == null) {
            System.err.println("Received null response from API");
            return Collections.emptyList();
        }

        return processHourlyResponse(response);
    }

    private List<HourlyWeatherData> processHourlyResponse(OpenMeteoResponse response) {
        List<HourlyWeatherData> hourlyData = new ArrayList<>();

        if (response.hourly == null) {
            System.err.println("No hourly data in response");
            return hourlyData;
        }

        try {
            int size = response.hourly.time.size();
            System.out.println("Processing " + size + " hourly records");

            for (int i = 0; i < size; i++) {
                HourlyWeatherData data = new HourlyWeatherData();
                data.time = response.hourly.time.get(i);
                data.temperature = response.hourly.temperature_2m.get(i);
                data.humidity = response.hourly.relative_humidity_2m.get(i);
                data.windSpeed = response.hourly.wind_speed_10m.get(i);
                hourlyData.add(data);
            }
        } catch (Exception e) {
            System.err.println("Error processing response: " + e.getMessage());
            e.printStackTrace();
        }

        return hourlyData;
    }

    private void sendHourlyDataToKafka(List<HourlyWeatherData> hourlyData) {
        for (HourlyWeatherData data : hourlyData) {
            String weatherMessage = createWeatherMessage(data);

            try {
                producer.send(new ProducerRecord<>(topic, String.valueOf(virtualStationId), weatherMessage),
                        (metadata, exception) -> {
                            if (exception != null) {
                                System.err.println("Failed to send message: " + exception.getMessage());
                            }
                        }).get(); // Wait for send to complete

                System.out.println("Sent message: " + weatherMessage);
                Thread.sleep(100); // Small delay between messages
            } catch (Exception e) {
                System.err.println("Error sending message: " + e.getMessage());
            }
        }
    }

    private String createWeatherMessage(HourlyWeatherData data) {
        messageCounter++;
        return String.format(
                "{ \"stationId\": %d, \"sNo\": %d, \"batteryStatus\": \"high\", " +
                        "\"statusTimestamp\": %d, \"weather\": { " +
                        "\"humidity\": %d, \"temperature\": %d, \"windSpeed\": %d } }",
                virtualStationId,
                messageCounter,
                convertTimeToTimestamp(data.time),
                data.humidity,
                (int) Math.round(data.temperature),
                (int) Math.round(data.windSpeed)
        );
    }

    private long convertTimeToTimestamp(String isoTime) {
        try {
            // Handle both formats: "2025-05-31T21:00" and ISO_INSTANT format
            if (isoTime.length() == 16) { // Format: "2025-05-31T21:00"
                return Instant.parse(isoTime + ":00Z").getEpochSecond();
            } else {
                return Instant.parse(isoTime).getEpochSecond();
            }
        } catch (Exception e) {
            System.err.println("Error parsing time: " + isoTime + " - " + e.getMessage());
            return System.currentTimeMillis() / 1000; // Fallback to current time
        }
    }

    public void shutdown() {
        scheduler.shutdown();
        producer.close();
    }

    // Updated response classes to match Open-Meteo API structure
    private static class OpenMeteoResponse {
        @JsonProperty("hourly")
        HourlyData hourly;
    }

    private static class HourlyData {
        @JsonProperty("time")
        List<String> time;
        @JsonProperty("temperature_2m")
        List<Double> temperature_2m;
        @JsonProperty("relative_humidity_2m")
        List<Integer> relative_humidity_2m;
        @JsonProperty("wind_speed_10m")
        List<Double> wind_speed_10m;
    }

    private static class HourlyWeatherData {
        String time;
        double temperature;
        int humidity;
        double windSpeed;
    }

    public static void main(String[] args) {
        System.out.println("🚀 Starting Open-Meteo Adapter");
        OpenMeteoAdapter adapter = new OpenMeteoAdapter();

        double latitude = args.length > 0 ? Double.parseDouble(args[0]) : 52.52;
        double longitude = args.length > 1 ? Double.parseDouble(args[1]) : 13.41;

        adapter.startFetching(latitude, longitude);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down gracefully...");
            adapter.shutdown();
        }));

        while (true) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
}