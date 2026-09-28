package com.acme.sre.ai.tools;

import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

import com.acme.sre.observability.DeploymentRecord;
import com.acme.sre.observability.LogEntry;
import com.acme.sre.observability.ObservabilityClient;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

/**
 * Deterministic access to the (simulated) observability stack for the evidence agents.
 * The LLM decides when to call these tools; the data itself always comes from our code,
 * which is what grounds the findings instead of letting the model invent logs or deploys.
 */
@ApplicationScoped
public class ObservabilityTools {

    private static final Logger LOG = Logger.getLogger(ObservabilityTools.class);

    private final ObservabilityClient client;

    public ObservabilityTools(@RestClient ObservabilityClient client) {
        this.client = client;
    }

    @Tool("Current Prometheus metrics for a service: CPU, memory, error rate and p99 latency")
    public String queryMetrics(@P("service name exactly as in the alert, e.g. payments-api") String service) {
        return call("queryMetrics", service, () -> client.metrics(service).toPromptText());
    }

    @Tool("Recent log lines (errors, warnings, platform events) for a service, newest first")
    public String searchLogs(@P("service name exactly as in the alert, e.g. payments-api") String service) {
        return call("searchLogs", service, () -> lines(client.logs(service), LogEntry::toPromptText));
    }

    @Tool("Recent deployments and configuration changes (feature flags) for a service, newest first")
    public String listDeployments(@P("service name exactly as in the alert, e.g. payments-api") String service) {
        return call("listDeployments", service, () -> lines(client.deployments(service), DeploymentRecord::toPromptText));
    }

    private static <T> String lines(List<T> items, Function<T, String> format) {
        return items.isEmpty() ? "No entries found." : items.stream().map(format).collect(Collectors.joining("\n"));
    }

    private static String call(String tool, String service, Supplier<String> query) {
        LOG.infof("[tool] %s(service=%s)", tool, service);
        try {
            return query.get();
        } catch (RuntimeException e) {
            LOG.warnf("[tool] %s(service=%s) failed: %s", tool, service, e.getMessage());
            return "Observability backend unavailable for " + service + ": " + e.getMessage();
        }
    }
}
