package com.acme.sre;

import com.acme.sre.domain.ActionType;
import com.acme.sre.domain.Incident;
import com.acme.sre.domain.IncidentStatus;
import com.acme.sre.domain.Severity;
import com.acme.sre.messaging.IncidentProjectionUpdater;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies the workflow -> Incident write-back: the emitted IncidentResult JSON is projected onto
 * the persisted record so the REST view matches the durable workflow's terminal/HITL state.
 */
@QuarkusTest
class IncidentProjectionUpdaterTest {

    @Inject
    IncidentProjectionUpdater updater;

    @Inject
    ObjectMapper mapper;

    @Test
    @Transactional
    void projectsResolvedStateOntoIncident() throws Exception {
        String wf = "wf-projection-test-" + System.nanoTime();
        Incident incident = new Incident();
        incident.workflowInstanceId = wf;
        incident.status = IncidentStatus.REMEDIATING;
        incident.persist();

        var result = mapper.readTree("""
            {
              "severity": "P2_HIGH",
              "diagnosis": {"rootCause": "memory leak", "explanation": "cache never evicted"},
              "remediation": {"type": "RESTART_POD", "description": "restart pod", "destructive": true, "targetService": "api-gateway"},
              "confidenceScore": {"score": 0.83, "reasoning": "", "sufficient": true}
            }
            """);

        updater.apply(wf, IncidentStatus.RESOLVED, result);

        Incident reloaded = Incident.find("workflowInstanceId", wf).firstResult();
        assertEquals(IncidentStatus.RESOLVED, reloaded.status);
        assertEquals(Severity.P2_HIGH, reloaded.severity);
        assertEquals("memory leak", reloaded.rootCause);
        assertEquals("cache never evicted", reloaded.diagnosis);
        assertEquals(ActionType.RESTART_POD, reloaded.remediationType);
        assertEquals(true, reloaded.remediationDestructive);
        assertEquals(0.83, reloaded.confidenceScore, 1e-9);
    }

    @Test
    @Transactional
    void missingWorkflowInstanceIsIgnored() throws Exception {
        var result = mapper.readTree("{\"severity\":\"P1_CRITICAL\"}");
        updater.apply("nonexistent-wf-id", IncidentStatus.RESOLVED, result);
        assertEquals(0L, Incident.find("workflowInstanceId", "nonexistent-wf-id").count());
    }
}
