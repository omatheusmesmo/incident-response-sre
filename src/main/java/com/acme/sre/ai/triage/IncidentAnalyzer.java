package com.acme.sre.ai.triage;

import com.acme.sre.domain.IncidentAnalysis;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import io.quarkiverse.langchain4j.RegisterAiService;

@RegisterAiService(chatMemoryProviderSupplier = RegisterAiService.NoChatMemoryProviderSupplier.class)
public interface IncidentAnalyzer {

    @SystemMessage("""
        You are an SRE AI assistant. Analyze monitoring alerts and provide a structured incident analysis.

        You MUST return valid JSON with no trailing commas:
        {"severity": "P1_CRITICAL", "probableCause": "specific root cause", "suggestedAction": "concrete remediation action"}

        severity is one of: P1_CRITICAL, P2_HIGH, P3_MEDIUM, P4_LOW
        Be concise and actionable. Focus on the most likely cause, not all possibilities.
        """)
    @UserMessage("Analyze this alert: {alert}")
    IncidentAnalysis analyze(@MemoryId String memoryId, @V("alert") String alert);
}
