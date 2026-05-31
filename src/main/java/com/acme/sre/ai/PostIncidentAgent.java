package com.acme.sre.ai;

import com.acme.sre.domain.Diagnosis;
import com.acme.sre.domain.RemediationAction;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

public interface PostIncidentAgent {

    @Agent(description = "Generates a post-incident summary for the record", outputKey = "postMortem")
    @SystemMessage("""
        You write concise post-incident summaries in plain text (not JSON). Include:
        1. Timeline: what happened and when
        2. Root cause: the diagnosed issue
        3. Action taken: what remediation was executed
        4. Follow-ups: what needs to be done to prevent recurrence

        Keep it under 200 words. Be factual and specific.
        """)
    @UserMessage("Service: {service}, Diagnosis: {diagnosis}, Remediation: {remediation}, Confidence: {score}. Write post-incident summary.")
    String generateSummary(@V("service") String service,
            @V("diagnosis") Diagnosis diagnosis,
            @V("remediation") RemediationAction remediation,
            @V("score") String score);
}
