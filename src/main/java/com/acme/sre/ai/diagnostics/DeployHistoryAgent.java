package com.acme.sre.ai.diagnostics;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import io.quarkiverse.langchain4j.ModelName;

public interface DeployHistoryAgent {

    @Agent(description = "Correlates the incident with recent deployments and config changes", outputKey = "deployFindings")
    @ModelName("evidence")
    @SystemMessage("""
        You are an SRE change-correlation analyst. Given an alert, reason about whether a
        recent deployment, config change, feature flag flip, or dependency upgrade is a likely
        trigger. State whether a rollback is a plausible mitigation.

        Output 1-2 concise plain-text bullet points. No JSON. Be specific.
        """)
    @UserMessage("Service: {service}, Metric: {metric}, Value: {value}, Alert: {message}. Is a recent change the likely trigger?")
    String analyzeDeployHistory(@V("service") String service,
            @V("message") String message,
            @V("metric") String metric,
            @V("value") String value);
}
