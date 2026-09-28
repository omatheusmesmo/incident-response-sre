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

public interface LogsAnalysisAgent {

    @Agent(description = "Analyzes application logs to surface error patterns and stack traces", outputKey = "logsFindings")
    @ModelName("evidence")
    @ToolBox(ObservabilityTools.class)
    @SystemMessage("""
        You are an SRE log analyst. Always call the searchLogs tool for the alerted service
        before answering, and base your findings only on the log lines it returns.
        Quote the decisive exception or message and its timestamp. If the tool returns no
        entries, say so instead of guessing.
        Output 1-3 concise plain-text bullet points. No JSON.
        """)
    @UserMessage("Service: {service}, Metric: {metric}, Value: {value}, Alert: {message}. What do the logs show?")
    String analyzeLogs(@MemoryId String memoryId,
            @V("service") String service,
            @V("message") String message,
            @V("metric") String metric,
            @V("value") String value);

    @ChatMemoryProviderSupplier
    static ChatMemory chatMemory(Object memoryId) {
        return AgentMemory.perIncident(memoryId);
    }
}
