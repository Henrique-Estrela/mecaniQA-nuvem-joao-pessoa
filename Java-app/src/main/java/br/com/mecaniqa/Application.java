package br.com.mecaniqa;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

public final class Application {
    private static final int MAX_SENSOR_READING_BYTES = 64 * 1024;
    private static final AtomicLong SUCCESSFUL_SENSOR_READINGS = new AtomicLong();
    private static final AtomicLong FAILED_SENSOR_READINGS = new AtomicLong();

    private Application() {
    }

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        HttpServer metricsServer = HttpServer.create(new InetSocketAddress(9091), 0);
        server.createContext("/", Application::handleRequest);
        metricsServer.createContext("/metrics", Application::handleMetricsRequest);
        server.start();
        metricsServer.start();
        System.out.println("MecaniQA iniciado nas portas 8080 (API) e 9091 (metricas internas)");
    }

    private static void handleRequest(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();

        if ("/".equals(path) && "GET".equals(method)) {
            respond(exchange, 200, "MecaniQA online", "text/plain; charset=UTF-8");
            return;
        }

        if ("/api/sensor-readings".equals(path)) {
            if (!"POST".equals(method)) {
                FAILED_SENSOR_READINGS.incrementAndGet();
                respond(exchange, 405, "Method not allowed", "text/plain; charset=UTF-8");
                return;
            }

            acceptSensorReading(exchange);
            return;
        }

        respond(exchange, 404, "Not found", "text/plain; charset=UTF-8");
    }

    private static void handleMetricsRequest(HttpExchange exchange) throws IOException {
        if (!"/metrics".equals(exchange.getRequestURI().getPath())) {
            respond(exchange, 404, "Not found", "text/plain; charset=UTF-8");
            return;
        }
        if (!"GET".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, "Method not allowed", "text/plain; charset=UTF-8");
            return;
        }
        respond(exchange, 200, metrics(), "text/plain; version=0.0.4; charset=utf-8");
    }

    private static void acceptSensorReading(HttpExchange exchange) throws IOException {
        try (InputStream input = exchange.getRequestBody()) {
            byte[] reading = input.readNBytes(MAX_SENSOR_READING_BYTES + 1);
            if (reading.length == 0) {
                FAILED_SENSOR_READINGS.incrementAndGet();
                respond(exchange, 400, "Sensor reading must not be empty", "text/plain; charset=UTF-8");
                return;
            }
            if (reading.length > MAX_SENSOR_READING_BYTES) {
                FAILED_SENSOR_READINGS.incrementAndGet();
                respond(exchange, 413, "Sensor reading is too large", "text/plain; charset=UTF-8");
                return;
            }

            SUCCESSFUL_SENSOR_READINGS.incrementAndGet();
            respond(exchange, 202, "Sensor reading accepted", "text/plain; charset=UTF-8");
        }
    }

    private static String metrics() {
        StringBuilder output = new StringBuilder();
        output.append("# HELP mecaniqa_sensor_readings_total Sensor readings accepted or rejected by the API.\n");
        output.append("# TYPE mecaniqa_sensor_readings_total counter\n");
        output.append("mecaniqa_sensor_readings_total{result=\"success\"} ")
                .append(SUCCESSFUL_SENSOR_READINGS.get()).append('\n');
        output.append("mecaniqa_sensor_readings_total{result=\"error\"} ")
                .append(FAILED_SENSOR_READINGS.get()).append('\n');

        java.lang.management.OperatingSystemMXBean operatingSystem =
                ManagementFactory.getOperatingSystemMXBean();
        if (operatingSystem instanceof com.sun.management.OperatingSystemMXBean) {
            double cpuLoad = ((com.sun.management.OperatingSystemMXBean) operatingSystem).getProcessCpuLoad();
            if (Double.isFinite(cpuLoad) && cpuLoad >= 0) {
                output.append("# HELP mecaniqa_process_cpu_usage_ratio Process CPU usage as a ratio.\n");
                output.append("# TYPE mecaniqa_process_cpu_usage_ratio gauge\n");
                output.append("mecaniqa_process_cpu_usage_ratio ").append(cpuLoad).append('\n');
            }
        }

        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        long heapUsed = memory.getHeapMemoryUsage().getUsed();
        long heapMax = memory.getHeapMemoryUsage().getMax();
        output.append("# HELP mecaniqa_jvm_memory_used_bytes JVM heap memory currently used.\n");
        output.append("# TYPE mecaniqa_jvm_memory_used_bytes gauge\n");
        output.append("mecaniqa_jvm_memory_used_bytes ").append(heapUsed).append('\n');
        if (heapMax > 0) {
            output.append("# HELP mecaniqa_jvm_memory_max_bytes Maximum JVM heap memory.\n");
            output.append("# TYPE mecaniqa_jvm_memory_max_bytes gauge\n");
            output.append("mecaniqa_jvm_memory_max_bytes ").append(heapMax).append('\n');
        }
        return output.toString();
    }

    private static void respond(HttpExchange exchange, int status, String message, String contentType)
            throws IOException {
        byte[] response = message.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, response.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(response);
        }
    }
}
