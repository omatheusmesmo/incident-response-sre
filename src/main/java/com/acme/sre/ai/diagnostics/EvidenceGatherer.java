package com.acme.sre.ai.diagnostics;

import com.acme.sre.domain.diagnosis.Evidence;

import dev.langchain4j.agentic.declarative.Output;
import dev.langchain4j.agentic.declarative.ParallelAgent;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.V;
import io.quarkiverse.langchain4j.RegisterAiService;

@RegisterAiService
public interface EvidenceGatherer {

    @ParallelAgent(
        description = "Fan-out evidence gathering: logs, metrics and deploy history analyzed in parallel",
        outputKey = "evidence",
        subAgents = {LogsAnalysisAgent.class, MetricsAnalysisAgent.class, DeployHistoryAgent.class})
    Evidence gather(@MemoryId String memoryId,
            @V("service") String service,
            @V("message") String message,
            @V("metric") String metric,
            @V("value") String value);

    @Output
    static Evidence aggregate(@V("logsFindings") String logsFindings,
            @V("metricsFindings") String metricsFindings,
            @V("deployFindings") String deployFindings) {
        return new Evidence(
                logsFindings != null ? logsFindings : "No log findings",
                metricsFindings != null ? metricsFindings : "No metric findings",
                deployFindings != null ? deployFindings : "No deploy findings");
    }
}
