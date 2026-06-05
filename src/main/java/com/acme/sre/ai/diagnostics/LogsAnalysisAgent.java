package com.acme.sre.ai.diagnostics;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import io.quarkiverse.langchain4j.ModelName;
import io.quarkiverse.langchain4j.RegisterAiService;

@RegisterAiService
public interface LogsAnalysisAgent {

    @Agent(description = "Analyzes application logs to surface error patterns and stack traces", outputKey = "logsFindings")
    @ModelName("evidence")
    @SystemMessage("""
        You are an SRE log analyst. Given an alert, infer the most likely log signatures
        an on-call engineer would find for this kind of incident: error messages, exception
        types, repeated warnings, or saturation indicators.

        Output 1-3 concise plain-text bullet points. No JSON. Be specific and technical.
        """)
    @UserMessage("Service: {service}, Metric: {metric}, Value: {value}, Alert: {message}. What do the logs most likely show?")
    String analyzeLogs(@V("service") String service,
            @V("message") String message,
            @V("metric") String metric,
            @V("value") String value);
}
