package com.acme.sre.domain.diagnosis;

import java.io.IOException;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

public class ConfidenceScoreDeserializer extends StdDeserializer<ConfidenceScore> {

    private static final Pattern SCORE_PATTERN = Pattern.compile("^\\s*(\\d+\\.?\\d*)\\s*(?:\\(.*\\))?(?:\\s*.*)?$");

    public ConfidenceScoreDeserializer() {
        super(ConfidenceScore.class);
    }

    @Override
    public ConfidenceScore deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonNode node = p.getCodec().readTree(p);

        if (node.isObject() && node.has("score")) {
            double score = node.get("score").asDouble();
            String reasoning = node.has("reasoning") ? node.get("reasoning").asText() : "";
            return new ConfidenceScore(score, reasoning);
        }

        if (node.isTextual()) {
            return parsePlainText(node.asText());
        }

        if (node.isNumber()) {
            return new ConfidenceScore(node.asDouble(), "");
        }

        return new ConfidenceScore(0.5, "Unable to parse confidence score");
    }

    static ConfidenceScore parsePlainText(String text) {
        var matcher = SCORE_PATTERN.matcher(text.trim());
        if (matcher.find()) {
            double score = Double.parseDouble(matcher.group(1));
            return new ConfidenceScore(score, text.trim());
        }
        return new ConfidenceScore(0.5, text.trim());
    }
}
