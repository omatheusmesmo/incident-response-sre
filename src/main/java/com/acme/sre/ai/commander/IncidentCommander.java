package com.acme.sre.ai.commander;

import dev.langchain4j.agentic.declarative.SupervisorAgent;
import dev.langchain4j.agentic.declarative.SupervisorRequest;
import dev.langchain4j.agentic.supervisor.SupervisorResponseStrategy;
import dev.langchain4j.service.V;
import io.quarkiverse.langchain4j.RegisterAiService;

@RegisterAiService
public interface IncidentCommander {

    @SupervisorAgent(
        description = "Incident commander that autonomously decides which domain specialists to consult "
                + "(database, kubernetes, network, cache) and synthesizes their findings into one response.",
        responseStrategy = SupervisorResponseStrategy.SUMMARY,
        maxAgentsInvocations = 6,
        subAgents = {
            DatabaseSpecialist.class,
            KubernetesSpecialist.class,
            NetworkSpecialist.class,
            CacheSpecialist.class
        })
    String command(@V("incident") String incident);

    @SupervisorRequest
    static String request(@V("incident") String incident) {
        return """
            You are the incident commander. Read the incident below and consult ONLY the specialists
            whose domain is relevant. Cross-correlate their findings and produce a single coordinated
            assessment: the most probable root cause and the recommended next action.

            Incident: %s
            """.formatted(incident);
    }
}
