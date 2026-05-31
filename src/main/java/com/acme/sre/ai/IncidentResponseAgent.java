package com.acme.sre.ai;

import java.util.regex.Pattern;

import com.acme.sre.domain.ConfidenceScore;
import com.acme.sre.domain.Diagnosis;
import com.acme.sre.domain.IncidentResult;
import com.acme.sre.domain.RemediationAction;
import com.acme.sre.domain.Severity;

import dev.langchain4j.agentic.declarative.Output;
import dev.langchain4j.agentic.declarative.SequenceAgent;
import dev.langchain4j.agentic.scope.ResultWithAgenticScope;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.V;

public interface IncidentResponseAgent {

    Pattern SCORE_PATTERN = Pattern.compile("(\\d+\\.?\\d*)");

    @SequenceAgent(
        description = "Agentic incident diagnosis: classify severity, then diagnose with iterative refinement",
        outputKey = "incidentResult",
        subAgents = {SeverityClassifier.class, DiagnosticLoopAgent.class})
    ResultWithAgenticScope<IncidentResult> respond(@MemoryId String memoryId,
            @V("message") String message,
            @V("service") String service,
            @V("metric") String metric,
            @V("value") String value);

    @Output
    static IncidentResult buildResult(@V("severity") Severity severity,
                                      @V("diagnosis") Diagnosis diagnosis,
                                      @V("remediation") RemediationAction remediation,
                                      @V("score") String score) {
        ConfidenceScore cs = parseScore(score);
        return new IncidentResult(severity, diagnosis, remediation, cs,
                "Post-incident summary is produced by the durable workflow (Layer 3).");
    }

    private static ConfidenceScore parseScore(String score) {
        if (score == null || score.isBlank()) {
            return new ConfidenceScore(0.5, "No score provided");
        }
        try {
            double v = Double.parseDouble(score.trim());
            return new ConfidenceScore(v, "");
        } catch (NumberFormatException e) {
            var matcher = SCORE_PATTERN.matcher(score);
            if (matcher.find()) {
                return new ConfidenceScore(Double.parseDouble(matcher.group(1)), score.trim());
            }
            return new ConfidenceScore(0.5, score.trim());
        }
    }
}
