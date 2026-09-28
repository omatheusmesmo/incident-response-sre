package com.acme.sre.ai.support;

import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;

/**
 * Chat memory policy for the agents: one window per incident ({@code @MemoryId}) and per agent.
 * Without it every {@code @Agent} falls back to Quarkus' shared store under a single default id,
 * so parallel agents read each other's messages and a new incident inherits the previous one's
 * history. Request scope is not an option: in Layer 3 the agents run on Quarkus Flow threads,
 * outside any HTTP request, and a durable workflow may resume after a restart.
 */
public final class AgentMemory {

    private static final int MAX_MESSAGES = 20;

    private AgentMemory() {
    }

    public static ChatMemory perIncident(Object memoryId) {
        return MessageWindowChatMemory.builder().id(memoryId).maxMessages(MAX_MESSAGES).build();
    }
}
