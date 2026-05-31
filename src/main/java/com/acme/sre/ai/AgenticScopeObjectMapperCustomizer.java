package com.acme.sre.ai;

import com.acme.sre.domain.ConfidenceScore;
import com.acme.sre.domain.ConfidenceScoreDeserializer;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;

import dev.langchain4j.agentic.scope.AgenticScope;
import dev.langchain4j.agentic.scope.ResultWithAgenticScope;
import io.quarkus.jackson.ObjectMapperCustomizer;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class AgenticScopeObjectMapperCustomizer implements ObjectMapperCustomizer {

    @Override
    public void customize(ObjectMapper objectMapper) {
        SimpleModule module = new SimpleModule();
        module.addSerializer(AgenticScope.class, new AgenticScopeSerializer());
        module.addDeserializer(ConfidenceScore.class, new ConfidenceScoreDeserializer());
        objectMapper.registerModule(module);

        objectMapper.addMixIn(ResultWithAgenticScope.class, ResultWithAgenticScopeMixin.class);

        objectMapper.enable(JsonReadFeature.ALLOW_TRAILING_COMMA.mappedFeature());
        objectMapper.enable(JsonReadFeature.ALLOW_MISSING_VALUES.mappedFeature());
    }

    abstract static class ResultWithAgenticScopeMixin {
        @JsonIgnore
        abstract AgenticScope agenticScope();
    }
}
