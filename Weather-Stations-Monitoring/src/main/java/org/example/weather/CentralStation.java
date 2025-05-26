package org.example.weather;

import java.io.IOException;

public class CentralStation {
    public static void main(String[] args) {
        BitcaskStore bitcaskStore = null;
        ParquetArchiver parquetArchiver = null;
        WeatherDataConsumer consumer = null;
        CentralStationServer server = null;
        ParquetToElasticsearchIndexer indexer = null;  // Add this
        Thread consumerThread = null;
        Thread rainDetectorThread = null;

        try {
            // Initialize components
            System.out.println("Initializing BitcaskStore...");
            bitcaskStore = new BitcaskStore();

            System.out.println("Initializing ParquetArchiver...");
            parquetArchiver = new ParquetArchiver();

            System.out.println("Initializing Parquet-to-Elasticsearch Indexer...");
            indexer = new ParquetToElasticsearchIndexer();
            indexer.start();

            // Start HTTP server
            System.out.println("Starting HTTP server...");
            server = new CentralStationServer(bitcaskStore, 8080);
            server.start();

            // Start Kafka consumer
            System.out.println("Starting WeatherDataConsumer...");
            consumer = new WeatherDataConsumer(bitcaskStore, parquetArchiver);
            consumerThread = new Thread(consumer, "WeatherDataConsumer");
            consumerThread.setDaemon(true);
            consumerThread.start();
            System.out.println("Central Station is running. Press Ctrl+C to stop.");
            // Keep running until shutdown
            while (true) {
                Thread.sleep(1000);
            }
        } catch (InterruptedException e) {
            System.out.println("Central Station shutting down...");
        } catch (Exception e) {
            System.err.println("Central Station failed: " + e.getMessage());
            e.printStackTrace();
        } finally {
            System.out.println("Shutting down components...");
            // Cleanup
            if (consumer != null) consumer.close();
            if (parquetArchiver != null) parquetArchiver.close();
            if (indexer != null) {
                try {indexer.close();}
                catch (IOException e) {System.err.println("Error closing indexer: " + e.getMessage());}
            }
            if (bitcaskStore != null) bitcaskStore.close();
            if (server != null) server.stop();
            if (consumerThread != null) consumerThread.interrupt();
            if (rainDetectorThread != null) rainDetectorThread.interrupt();
            System.out.println("Shutdown complete");
        }
    }
}



//            // Start Rain Detector in separate thread
//            System.out.println("Starting RainDetector...");
//            rainDetectorThread = new Thread(() -> {
//                try {
//                    org.example.RainDetector.main(new String[]{});
//                } catch (Exception e) {
//                    System.err.println("Rain Detector crashed: " + e.getMessage());
//                    e.printStackTrace();
//                }
//            }, "RainDetector");
//            rainDetectorThread.setDaemon(true);
//            rainDetectorThread.start();