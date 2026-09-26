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

public interface CacheSpecialist {

    @Agent(description = "Caching specialist. Use for incidents about low cache hit-rate, mass eviction, cache stampede, "
            + "Redis/Memcached memory pressure or OOM, stale entries, or hot-key contention.",
            outputKey = "cacheFindings")
    @ModelName("evidence")
    @SystemMessage("""
        You are a senior caching specialist. Diagnose the caching angle of the incident only.
        Consider hit-rate collapse, eviction storms, stampede, Redis/Memcached memory pressure, stale data, and hot keys.
        Give a concise root-cause hypothesis and one concrete cache-side action. Do not speculate outside the cache layer.
        """)
    @UserMessage("Incident: {incident}")
    String analyze(@MemoryId String memoryId,
            @V("incident") String incident);

    @ChatMemoryProviderSupplier
    static ChatMemory chatMemory(Object memoryId) {
        return AgentMemory.perIncident(memoryId);
    }
}
