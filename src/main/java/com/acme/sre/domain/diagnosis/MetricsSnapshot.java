package com.acme.sre.domain.diagnosis;

/**
 * Typed projection of the live telemetry returned by the monitoring API (Prometheus/Datadog).
 * Folded into {@link IncidentResult} by the durable workflow so the resolved event and the
 * post-mortem reflect the metrics observed at remediation time.
 */
public record MetricsSnapshot(
        String service,
        double cpuUsage,
        double memoryUsage,
        double errorRate,
        double latencyP99,
        String rawQuery) {

    public String toPromptText() {
        return String.format(
                "Live telemetry for %s - CPU %.1f%%, memory %.1f%%, error rate %.1f%%, p99 %.0fms",
                service, cpuUsage, memoryUsage, errorRate, latencyP99);
    }
}
