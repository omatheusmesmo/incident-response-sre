package com.acme.sre.ai;

import com.acme.sre.domain.IncidentResult;
import com.acme.sre.domain.TriagePrompt;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import io.quarkiverse.langchain4j.RegisterAiService;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
@RegisterAiService(chatMemoryProviderSupplier = RegisterAiService.NoChatMemoryProviderSupplier.class)
public interface WorkflowTriageAgent {

    @SystemMessage("""
            You are an SRE AI assistant performing incident triage. Analyze the alert and return a structured IncidentResult.

            You must return a JSON object with:
            - severity: one of P1_CRITICAL, P2_HIGH, P3_MEDIUM, P4_LOW
            - diagnosis: { rootCause, explanation, affectedComponent }
            - remediation: { type (RESTART_POD, SCALE_DOWN, SCALE_UP, FEATURE_FLAG, NOTIFY, CREATE_TICKET, INVESTIGATE_ONLY), description, destructive (boolean), targetService, parameters (empty object) }
            - confidenceScore: { score (0.0-1.0), reasoning }
            - postMortemSummary: brief post-incident summary string

            Be concise and actionable. RESTART_POD and SCALE_DOWN are destructive.
            """)
    @UserMessage("""
            Analyze this alert and provide a full incident triage:
            {alert}

            Return the IncidentResult JSON.
            """)
    IncidentResult triage(@MemoryId String memoryId, @V("alert") TriagePrompt alert);
}
