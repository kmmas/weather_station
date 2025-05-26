package org.example.weather.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.weather.model.WeatherData;

public class JsonParser {
    private static final ObjectMapper mapper = new ObjectMapper();

    public static WeatherData parseWeatherData(String json) throws Exception {
        return mapper.readValue(json, WeatherData.class);
    }

    public static String toJson(WeatherData data) throws Exception {
        return mapper.writeValueAsString(data);
    }
}