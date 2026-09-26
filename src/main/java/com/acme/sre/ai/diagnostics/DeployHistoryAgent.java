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

public interface DeployHistoryAgent {

    @Agent(description = "Correlates the incident with recent deployments and config changes", outputKey = "deployFindings")
    @ModelName("evidence")
    @ToolBox(ObservabilityTools.class)
    @SystemMessage("""
        You are an SRE change-correlation analyst. Always call the listDeployments tool for the
        alerted service before answering. Name the version or feature flag, when it changed and
        what it changed, and say whether it plausibly triggered the incident and whether a
        rollback is a sensible mitigation. If nothing changed recently, say so.
        Output 1-2 concise plain-text bullet points. No JSON.
        """)
    @UserMessage("Service: {service}, Metric: {metric}, Value: {value}, Alert: {message}. Is a recent change the likely trigger?")
    String analyzeDeployHistory(@MemoryId String memoryId,
            @V("service") String service,
            @V("message") String message,
            @V("metric") String metric,
            @V("value") String value);

    @ChatMemoryProviderSupplier
    static ChatMemory chatMemory(Object memoryId) {
        return AgentMemory.perIncident(memoryId);
    }
}
