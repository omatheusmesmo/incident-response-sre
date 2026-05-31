package com.acme.sre.domain;

public record MetricsData(
        String service,
        double cpuUsage,
        double memoryUsage,
        double errorRate,
        double latencyP99,
        String rawQuery) {

    public String toPromptText() {
        return String.format(
                "Metrics for '%s': CPU=%.1f%%, Memory=%.1f%%, ErrorRate=%.2f%%, LatencyP99=%.0fms (query: %s)",
                service, cpuUsage, memoryUsage, errorRate, latencyP99, rawQuery);
    }
}
