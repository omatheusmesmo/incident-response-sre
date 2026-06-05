package com.acme.sre.ai.diagnostics;

import com.acme.sre.domain.diagnosis.Diagnosis;
import com.acme.sre.domain.diagnosis.RemediationAction;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import io.quarkiverse.langchain4j.RegisterAiService;

@RegisterAiService
public interface ConfidenceScorer {

    @Agent(description = "Scores confidence in the diagnosis and remediation from 0.0 to 1.0", outputKey = "score")
    @SystemMessage("""
        You score confidence in an incident diagnosis and remediation plan.
        Consider completeness of data, clarity of root cause, specificity of action, and severity alignment.

        Output ONLY a single number between 0.0 and 1.0. Nothing else. No words, no explanation, no JSON.
        Examples of valid output: 0.85 or 0.92 or 0.6
        Be strict: if the diagnosis is vague or the action is generic, score below 0.7.
        """)
    @UserMessage("Diagnosis: {diagnosis}, Remediation: {remediation}. Score confidence (number only).")
    String score(@V("diagnosis") Diagnosis diagnosis,
            @V("remediation") RemediationAction remediation);
}
