package com.acme.sre.ai.support;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.agentic.declarative.ChatMemoryProviderSupplier;
import dev.langchain4j.agentic.declarative.SequenceAgent;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.V;
import io.quarkiverse.langchain4j.ModelName;
import io.quarkus.test.junit.QuarkusTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Answers whether {@code @MemoryId} alone isolates two agents of the same incident, or whether
 * the per-agent {@code @ChatMemoryProviderSupplier} ({@link AgentMemory#perIncident}) is needed.
 * <p>
 * Two agents run in sequence with the same memory id, as the app does with the incident id
 * (the severity classifier, then the evidence agents; the loop's agents across iterations).
 * A stub model records every request, so the test sees exactly what agent B sends.
 */
@QuarkusTest
class AgentMemoryTest {

    static final List<ChatRequest> REQUESTS = new CopyOnWriteArrayList<>();

    @Inject
    SharedPair shared;

    @Inject
    IsolatedPair isolated;

    @BeforeEach
    void reset() {
        REQUESTS.clear();
    }

    @Test
    void withMemoryIdOnlyTheSecondAgentSeesTheFirstAgentsConversation() {
        shared.run(UUID.randomUUID().toString(), "disk");

        String secondRequest = render(REQUESTS.get(1));
        assertTrue(secondRequest.contains("PROBE-A question about disk"),
                "agent B's request carries agent A's user message:\n" + secondRequest);
    }

    @Test
    void withAPerAgentProviderTheSecondAgentSeesOnlyItsOwnConversation() {
        isolated.run(UUID.randomUUID().toString(), "disk");

        String secondRequest = render(REQUESTS.get(1));
        assertFalse(secondRequest.contains("PROBE-A"), "no trace of agent A:\n" + secondRequest);
        assertEquals(1, countSystemMessages(REQUESTS.get(1)));
        assertTrue(secondRequest.contains("You are probe B"), secondRequest);
    }

    private static String render(ChatRequest request) {
        return request.messages().stream()
                .map(m -> m.type() + ": " + text(m))
                .collect(Collectors.joining("\n"));
    }

    private static long countSystemMessages(ChatRequest request) {
        return request.messages().stream().filter(SystemMessage.class::isInstance).count();
    }

    private static String text(ChatMessage message) {
        return switch (message) {
            case SystemMessage s -> s.text();
            case UserMessage u -> u.singleText();
            case AiMessage a -> String.valueOf(a.text());
            default -> message.toString();
        };
    }

    @ApplicationScoped
    static class ProbeModelProducer {

        @Produces
        @ModelName("memory-probe")
        ChatModel probe() {
            return new ChatModel() {
                @Override
                public ChatResponse doChat(ChatRequest request) {
                    REQUESTS.add(request);
                    return ChatResponse.builder().aiMessage(AiMessage.from("ok")).build();
                }
            };
        }
    }

    public interface SharedPair {

        @SequenceAgent(outputKey = "b", subAgents = { SharedA.class, SharedB.class })
        String run(@MemoryId String memoryId, @V("topic") String topic);
    }

    public interface SharedA {

        @Agent(description = "probe A", outputKey = "a")
        @ModelName("memory-probe")
        @dev.langchain4j.service.SystemMessage("You are probe A")
        @dev.langchain4j.service.UserMessage("PROBE-A question about {{topic}}")
        String ask(@MemoryId String memoryId, @V("topic") String topic);
    }

    public interface SharedB {

        @Agent(description = "probe B", outputKey = "b")
        @ModelName("memory-probe")
        @dev.langchain4j.service.SystemMessage("You are probe B")
        @dev.langchain4j.service.UserMessage("PROBE-B question about {{topic}}")
        String ask(@MemoryId String memoryId, @V("topic") String topic);
    }

    public interface IsolatedPair {

        @SequenceAgent(outputKey = "b", subAgents = { IsolatedA.class, IsolatedB.class })
        String run(@MemoryId String memoryId, @V("topic") String topic);
    }

    public interface IsolatedA {

        @Agent(description = "probe A", outputKey = "a")
        @ModelName("memory-probe")
        @dev.langchain4j.service.SystemMessage("You are probe A")
        @dev.langchain4j.service.UserMessage("PROBE-A question about {{topic}}")
        String ask(@MemoryId String memoryId, @V("topic") String topic);

        @ChatMemoryProviderSupplier
        static ChatMemory chatMemory(Object memoryId) {
            return AgentMemory.perIncident(memoryId);
        }
    }

    public interface IsolatedB {

        @Agent(description = "probe B", outputKey = "b")
        @ModelName("memory-probe")
        @dev.langchain4j.service.SystemMessage("You are probe B")
        @dev.langchain4j.service.UserMessage("PROBE-B question about {{topic}}")
        String ask(@MemoryId String memoryId, @V("topic") String topic);

        @ChatMemoryProviderSupplier
        static ChatMemory chatMemory(Object memoryId) {
            return AgentMemory.perIncident(memoryId);
        }
    }
}
