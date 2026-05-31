package com.acme.sre.ai;

import java.io.IOException;
import java.util.Map;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;

import dev.langchain4j.agentic.scope.AgenticScope;

public class AgenticScopeSerializer extends StdSerializer<AgenticScope> {

    public AgenticScopeSerializer() {
        super(AgenticScope.class);
    }

    @Override
    public void serialize(AgenticScope value, JsonGenerator gen, SerializerProvider provider) throws IOException {
        gen.writeStartObject();
        gen.writeStringField("memoryId", String.valueOf(value.memoryId()));
        gen.writeFieldName("state");
        gen.writeStartObject();
        Map<?, ?> state = value.state();
        if (state != null) {
            for (Map.Entry<?, ?> entry : state.entrySet()) {
                Object v = entry.getValue();
                gen.writeStringField(String.valueOf(entry.getKey()), v != null ? v.toString() : null);
            }
        }
        gen.writeEndObject();
        gen.writeEndObject();
    }
}
