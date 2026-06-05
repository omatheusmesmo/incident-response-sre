package com.acme.sre.ai.support;

import com.acme.sre.domain.ConfidenceScore;
import com.acme.sre.domain.ConfidenceScoreDeserializer;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;

import io.quarkus.jackson.ObjectMapperCustomizer;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Hardens JSON handling for the agentic and Flow paths: a lenient {@link ConfidenceScore}
 * deserializer (LLMs emit the score in inconsistent shapes) plus tolerant parsing for the JSON
 * produced by agents and round-tripped through CloudEvents / workflow context.
 */
@ApplicationScoped
public class AgenticJsonObjectMapperCustomizer implements ObjectMapperCustomizer {

    @Override
    public void customize(ObjectMapper objectMapper) {
        SimpleModule module = new SimpleModule();
        module.addDeserializer(ConfidenceScore.class, new ConfidenceScoreDeserializer());
        objectMapper.registerModule(module);

        objectMapper.enable(JsonReadFeature.ALLOW_TRAILING_COMMA.mappedFeature());
        objectMapper.enable(JsonReadFeature.ALLOW_MISSING_VALUES.mappedFeature());
    }
}
