package com.acme.sre.ai.commander;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import io.quarkiverse.langchain4j.ModelName;
import io.quarkiverse.langchain4j.RegisterAiService;

@RegisterAiService
public interface KubernetesSpecialist {

    @Agent(description = "Kubernetes/platform specialist. Use for incidents about OOMKilled pods, CrashLoopBackOff, "
            + "failed scheduling, resource limits/requests, HPA scaling, readiness/liveness probes, or node pressure.",
            outputKey = "kubernetesFindings")
    @ModelName("evidence")
    @SystemMessage("""
        You are a senior Kubernetes platform engineer. Diagnose the Kubernetes/orchestration angle only.
        Consider OOMKilled, CrashLoopBackOff, resource limits/requests, HPA behaviour, probe failures, and node pressure.
        Give a concise root-cause hypothesis and one concrete cluster-side action. Do not speculate outside the platform.
        """)
    @UserMessage("Incident: {incident}")
    String analyze(@V("incident") String incident);
}
