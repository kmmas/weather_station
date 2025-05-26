package org.example;

import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.*;
import org.apache.kafka.streams.kstream.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class RainDetector {
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String INPUT_TOPIC = "weather-data";
    private static final String OUTPUT_TOPIC = "rain-alerts";

    public static Topology createTopology() {
        StreamsBuilder builder = new StreamsBuilder();

        builder.stream(INPUT_TOPIC,
                        Consumed.with(Serdes.String(), Serdes.String()))

                // Filter for high humidity
                .filter((stationId, json) -> {
                    try {
                        JsonNode data = mapper.readTree(json);
                        return data.path("weather").path("humidity").asInt() > 70;
                    } catch (Exception e) {
                        return false;
                    }
                })

                // Transform to alert format
                .mapValues(json -> {
                    try {
                        JsonNode data = mapper.readTree(json);
                        return String.format(
                                "{ \"stationId\": %d, \"statusTimestamp\": %d, " +
                                        "\"humidity\": %d, \"alert\": \"RAINING\" }",
                                data.path("stationId").asInt(),
                                data.path("statusTimestamp").asLong(),
                                data.path("weather").path("humidity").asInt()
                        );
                    } catch (Exception e) {
                        return "{ \"error\": \"Invalid message\" }";
                    }
                })

                // Send to alerts topic
                .to(OUTPUT_TOPIC,
                        Produced.with(Serdes.String(), Serdes.String()));

        return builder.build();
    }

    public static void main(String[] args) {
        KafkaStreams streams = new KafkaStreams(
                createTopology(),
                KafkaConfig.getStreamsConfig()
        );

        streams.start();
        Runtime.getRuntime().addShutdownHook(new Thread(streams::close));
    }
}
