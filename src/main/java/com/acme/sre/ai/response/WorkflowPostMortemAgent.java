package com.acme.sre.ai.response;

import com.acme.sre.domain.diagnosis.IncidentResult;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import io.quarkiverse.langchain4j.RegisterAiService;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
@RegisterAiService(chatMemoryProviderSupplier = RegisterAiService.NoChatMemoryProviderSupplier.class)
public interface WorkflowPostMortemAgent {

    @SystemMessage("""
            You write concise post-incident summaries. Include:
            1. Timeline: what happened and when
            2. Root cause: the diagnosed issue
            3. Action taken: what remediation was executed
            4. Follow-ups: what needs to be done to prevent recurrence

            Keep it under 200 words. Be factual and specific.
            """)
    @UserMessage("""
            Generate a post-incident summary:
            Service: {incident.diagnosis.affectedComponent}
            Diagnosis: {incident.diagnosis.rootCause} - {incident.diagnosis.explanation}
            Remediation: {incident.remediation.type} - {incident.remediation.description}
            Confidence: {incident.confidenceScore}
            Live metrics at remediation: CPU {incident.liveMetrics.cpuUsage}%, memory {incident.liveMetrics.memoryUsage}%, error rate {incident.liveMetrics.errorRate}%, p99 {incident.liveMetrics.latencyP99}ms

            Write the post-mortem.
            """)
    String generate(@MemoryId String memoryId, @V("incident") IncidentResult incident);
}
