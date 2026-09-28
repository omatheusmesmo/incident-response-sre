package com.acme.sre.ai.tools;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The evidence agents are only as grounded as the data their tools return: each tool must hit the
 * observability API for the requested service and render it as compact text the LLM can cite.
 */
@QuarkusTest
class ObservabilityToolsTest {

    @Inject
    ObservabilityTools tools;

    @Test
    void queryMetricsRendersTheServicesTelemetry() {
        String metrics = tools.queryMetrics("payments-api");

        assertTrue(metrics.contains("payments-api"), metrics);
        assertTrue(metrics.contains("memory 97.0%"), metrics);
    }

    @Test
    void searchLogsReturnsTheDecisiveErrorWithItsTimestamp() {
        String logs = tools.searchLogs("payments-api");

        assertTrue(logs.contains("2026-09-26T14:07:12Z ERROR"), logs);
        assertTrue(logs.contains("OutOfMemoryError"), logs);
    }

    @Test
    void listDeploymentsIncludesFeatureFlagChanges() {
        String changes = tools.listDeployments("checkout-api");

        assertTrue(changes.contains("FEATURE_FLAG flag:checkout.recommendations.inline"), changes);
    }

    @Test
    void unknownServiceYieldsAnExplicitEmptyResultInsteadOfNothing() {
        assertEquals("No entries found.", tools.searchLogs("not-a-service"));
    }
}
