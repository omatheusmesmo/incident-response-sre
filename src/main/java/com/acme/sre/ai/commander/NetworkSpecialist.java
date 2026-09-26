package com.acme.sre.ai.commander;

import com.acme.sre.ai.support.AgentMemory;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.agentic.declarative.ChatMemoryProviderSupplier;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import io.quarkiverse.langchain4j.ModelName;

public interface NetworkSpecialist {

    @Agent(description = "Network specialist. Use for incidents about elevated latency, DNS resolution failures, "
            + "connection timeouts, TLS handshake errors, packet loss, service-mesh routing, or load-balancer issues.",
            outputKey = "networkFindings")
    @ModelName("evidence")
    @SystemMessage("""
        You are a senior network engineer. Diagnose the network angle of the incident only.
        Consider latency, DNS, timeouts, TLS, packet loss, service-mesh routing, and load-balancer health.
        Give a concise root-cause hypothesis and one concrete network-side action. Do not speculate outside the network.
        """)
    @UserMessage("Incident: {incident}")
    String analyze(@MemoryId String memoryId,
            @V("incident") String incident);

    @ChatMemoryProviderSupplier
    static ChatMemory chatMemory(Object memoryId) {
        return AgentMemory.perIncident(memoryId);
    }
}
