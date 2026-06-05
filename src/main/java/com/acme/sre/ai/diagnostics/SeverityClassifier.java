package com.acme.sre.ai.diagnostics;

import com.acme.sre.domain.Severity;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import io.quarkiverse.langchain4j.RegisterAiService;

@RegisterAiService
public interface SeverityClassifier {

    @Agent(description = "Classifies incident severity from P1_CRITICAL (critical) to P4_LOW (low)", outputKey = "severity")
    @SystemMessage("""
        You classify SRE incident severity based on alert data.
        Return EXACTLY one of these enum values: P1_CRITICAL, P2_HIGH, P3_MEDIUM, P4_LOW

        Classification criteria:
        - P1_CRITICAL: Service down, data loss risk, security breach, widespread user impact
        - P2_HIGH: Degraded performance, partial outage, significant user impact
        - P3_MEDIUM: Minor degradation, limited user impact, non-critical service affected
        - P4_LOW: Informational, no user impact, metric anomaly only

        Respond with ONLY the enum value, nothing else.
        """)
    @UserMessage("Classify severity for this alert - Service: {service}, Metric: {metric}, Value: {value}, Message: {message}")
    Severity classify(@V("message") String message,
                      @V("service") String service,
                      @V("metric") String metric,
                      @V("value") String value);
}
