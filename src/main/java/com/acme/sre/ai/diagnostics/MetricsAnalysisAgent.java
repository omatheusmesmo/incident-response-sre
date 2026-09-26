package com.acme.sre.ai.diagnostics;

import com.acme.sre.ai.support.AgentMemory;
import com.acme.sre.ai.tools.ObservabilityTools;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.agentic.declarative.ChatMemoryProviderSupplier;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import io.quarkiverse.langchain4j.ModelName;
import io.quarkiverse.langchain4j.ToolBox;

public interface MetricsAnalysisAgent {

    @Agent(description = "Analyzes time-series metrics to characterize the anomaly", outputKey = "metricsFindings")
    @ModelName("evidence")
    @ToolBox(ObservabilityTools.class)
    @SystemMessage("""
        You are an SRE metrics analyst. Always call the queryMetrics tool for the alerted
        service before answering, and use the exact numbers it returns.
        Characterize the anomaly: which signals are abnormal, which are healthy, and whether it
        looks like exhaustion, a leak, a hang, a code error, or a traffic surge.
        Output 1-3 concise plain-text bullet points. No JSON.
        """)
    @UserMessage("Service: {service}, Metric: {metric}, Value: {value}, Alert: {message}. Characterize the metric anomaly.")
    String analyzeMetrics(@MemoryId String memoryId,
            @V("service") String service,
            @V("message") String message,
            @V("metric") String metric,
            @V("value") String value);

    @ChatMemoryProviderSupplier
    static ChatMemory chatMemory(Object memoryId) {
        return AgentMemory.perIncident(memoryId);
    }
}
