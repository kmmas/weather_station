package org.example.weather.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public class WeatherData {
    @JsonProperty("stationId")  // or "station_id" if your JSON uses that
    private long stationId;
    @JsonProperty("sNo")  // explicitly maps JSON's "sNo" to this field
    private long sNo;
    @JsonProperty("batteryStatus")
    private String batteryStatus;
    @JsonProperty("statusTimestamp")
    private long statusTimestamp;
    private Weather weather;

    public static class Weather {
        private int humidity;
        private int temperature;
        @JsonProperty("windSpeed")
        private int windSpeed;

        // Getters and setters
        public int getHumidity() { return humidity; }
        public void setHumidity(int humidity) { this.humidity = humidity; }
        public int getTemperature() { return temperature; }
        public void setTemperature(int temperature) { this.temperature = temperature; }
        public int getWindSpeed() { return windSpeed; }
        public void setWindSpeed(int windSpeed) { this.windSpeed = windSpeed; }
    }

    // Getters and setters
    public long getStationId() { return stationId; }
    public void setStationId(long stationId) { this.stationId = stationId; }
    public long getSNo() { return sNo; }
    public void setSNo(long sNo) { this.sNo = sNo; }
    public String getBatteryStatus() { return batteryStatus; }
    public void setBatteryStatus(String batteryStatus) { this.batteryStatus = batteryStatus; }
    public long getStatusTimestamp() { return statusTimestamp; }
    public void setStatusTimestamp(long statusTimestamp) { this.statusTimestamp = statusTimestamp; }
    public Weather getWeather() { return weather; }
    public void setWeather(Weather weather) { this.weather = weather; }
}