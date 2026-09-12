package com.acme.sre.ai.diagnostics;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import io.quarkiverse.langchain4j.ModelName;

public interface MetricsAnalysisAgent {

    @Agent(description = "Analyzes time-series metrics to characterize the anomaly", outputKey = "metricsFindings")
    @ModelName("evidence")
    @SystemMessage("""
        You are an SRE metrics analyst. Given an alert, describe the most likely metric
        behavior: trend (spike, ramp, flatline), correlated signals (CPU, memory, latency,
        error rate, saturation), and whether it looks like exhaustion, a leak, or a traffic surge.

        Output 1-3 concise plain-text bullet points. No JSON. Be specific and quantitative where possible.
        """)
    @UserMessage("Service: {service}, Metric: {metric}, Value: {value}, Alert: {message}. Characterize the metric anomaly.")
    String analyzeMetrics(@V("service") String service,
            @V("message") String message,
            @V("metric") String metric,
            @V("value") String value);
}
